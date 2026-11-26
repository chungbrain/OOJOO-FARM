package com.oojoo.farm.slave.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.exp

/*
 * 온디바이스 검출 모듈의 순수 로직 (Android 의존 없음 → JVM 단위 테스트 대상).
 *  - softmax / 관수 라벨 해석
 *  - DetectorPlanner: 주기(periodic) + 이벤트 요청 → 이번 회차에 돌릴 (식물, 모듈, 계기)
 *  - ReportGate: 결과 업로드 스로틀 (라벨이 바뀌거나 이벤트면 즉시, 같으면 5분에 1회)
 *  - WateringCycleTracker: 관수 → 다시 마를 때까지 걸린 시간(관수 주기) 추적
 */

object DetectorIds {
    const val HEALTH = "health_grade"
    const val PEST = "pest"
    const val WATERING = "watering"
    /** 구버전 서버(detectors 필드 없음)에서의 기본 세트 — 기존 동작(건강·해충·물 감시 상시)과 같다 */
    val DEFAULTS = setOf(HEALTH, PEST, WATERING)
}

object Triggers {
    const val PERIODIC = "periodic"
    const val MODULE_ADDED = "module_added"
    const val RUN_NOW = "run_now"
    const val WATERED = "watered"
    const val WATERED_FOLLOWUP = "watered_followup"
    const val PEST_RESPONSE = "pest_response"
    const val HEALTH_DROP = "health_drop"
    const val ROI_CHANGED = "roi_changed"
}

/** 서버(backend/src/ml/registry.js normAlias)와 같은 종 이름 정규화 — 앞뒤 공백 제거, 소문자, 공백 제거 */
fun normalizeSpecies(raw: String): String = raw.trim().lowercase().replace(" ", "")

fun softmax(logits: FloatArray): DoubleArray {
    if (logits.isEmpty()) return DoubleArray(0)
    val max = logits.maxOrNull()!!.toDouble()
    val e = DoubleArray(logits.size) { exp(logits[it] - max) }
    val sum = e.sum()
    return DoubleArray(e.size) { if (sum > 0) e[it] / sum else 0.0 }
}

/**
 * 종별 진단 모델 출력(healthy/water_low/water_high/light_low/pest/heat) → 관수 라벨.
 * 가장 높은 클래스가 water_low → dry, water_high → wet, 그 외 → ok(신뢰도 = 1 − 물 관련 확률).
 */
fun waterLabelFromProbs(probs: DoubleArray, outputs: List<String>): Pair<String, Double> {
    if (probs.isEmpty()) return "ok" to 0.0
    val low = outputs.indexOf("water_low")
    val high = outputs.indexOf("water_high")
    val top = probs.indices.maxByOrNull { probs[it] } ?: 0
    val pLow = probs.getOrNull(low) ?: 0.0
    val pHigh = probs.getOrNull(high) ?: 0.0
    return when (top) {
        low -> "dry" to pLow
        high -> "wet" to pHigh
        else -> "ok" to (1.0 - pLow - pHigh).coerceIn(0.0, 1.0)
    }
}

/** 판독 회차 계획 — 주기 회차면 켜진 모듈 전부, 이벤트 요청은 기한이 되면 소모. */
class DetectorPlanner(private val maxPending: Int = 200) {
    data class Request(val trigger: String, val plantId: String?, val modules: Set<String>?, val dueAt: Long)

    private val pending = mutableListOf<Request>()

    /** plantId=null → 모든 식물, modules=null → 켜진 모듈 전부. */
    @Synchronized
    fun request(trigger: String, plantId: String?, modules: Set<String>?, dueAt: Long) {
        if (pending.any { it.trigger == trigger && it.plantId == plantId && it.modules == modules && kotlin.math.abs(it.dueAt - dueAt) < 1_000 }) return
        if (pending.size >= maxPending) pending.removeAt(0)
        pending += Request(trigger, plantId, modules, dueAt)
    }

    @Synchronized
    fun nextDueAt(): Long? = pending.minOfOrNull { it.dueAt }

    @Synchronized
    fun pendingCount(): Int = pending.size

    /** 다음 회차까지 기다릴 ms — 다음 주기와 가장 이른 이벤트 요청 중 먼저 오는 쪽. */
    fun waitMs(now: Long, lastPeriodicAt: Long, periodMs: Long): Long {
        val periodic = lastPeriodicAt + periodMs - now
        val event = nextDueAt()?.let { it - now } ?: Long.MAX_VALUE
        return minOf(periodic, event).coerceAtLeast(0L)
    }

    /**
     * 이번 회차에 돌릴 식물 → (모듈 → 계기). 이벤트 계기가 같은 회차의 periodic 보다 우선한다
     * (결과에 '왜 판독했는지'가 남도록). 켜지지 않은 모듈/식물에 대한 요청은 버린다.
     */
    @Synchronized
    fun plan(now: Long, enabled: Map<String, Set<String>>, periodic: Boolean): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, MutableMap<String, String>>()
        if (periodic) {
            for ((p, mods) in enabled) for (m in mods) out.getOrPut(p) { linkedMapOf() }[m] = Triggers.PERIODIC
        }
        val due = pending.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        pending.removeAll(due)
        for (r in due) {
            val plants = r.plantId?.let { listOf(it) } ?: enabled.keys.toList()
            for (p in plants) {
                val mods = enabled[p] ?: continue
                val target = r.modules?.let { mods.intersect(it) } ?: mods
                for (m in target) out.getOrPut(p) { linkedMapOf() }[m] = r.trigger
            }
        }
        return out
    }

    /** 카메라가 준비되지 않아 못 돌린 이벤트 회차를 나중으로 미룬다 (periodic 은 다음 주기에 어차피 돈다). */
    @Synchronized
    fun requeue(plan: Map<String, Map<String, String>>, dueAt: Long) {
        for ((p, mods) in plan) {
            mods.entries.filter { it.value != Triggers.PERIODIC }.groupBy({ it.value }, { it.key }).forEach { (trigger, ms) ->
                if (pending.size < maxPending) pending += Request(trigger, p, ms.toSet(), dueAt)
            }
        }
    }
}

/** 결과 업로드 스로틀 — 이벤트 계기면 즉시, 라벨이 바뀌면 즉시, 같으면 minIntervalMs 에 1회. */
class ReportGate(private val minIntervalMs: Long = 5 * 60_000L) {
    private val last = mutableMapOf<String, Pair<String, Long>>()

    @Synchronized
    fun shouldReport(plantId: String, moduleId: String, label: String, trigger: String, now: Long): Boolean {
        if (trigger != Triggers.PERIODIC) return true
        val prev = last["$plantId|$moduleId"] ?: return true
        return prev.first != label || now - prev.second >= minIntervalMs
    }

    @Synchronized
    fun markReported(plantId: String, moduleId: String, label: String, now: Long) {
        last["$plantId|$moduleId"] = label to now
    }

    @Synchronized
    fun forget(plantId: String, moduleId: String) {
        last.remove("$plantId|$moduleId")
    }
}

@Serializable
data class WaterCycleState(
    var lastWateredAt: Long? = null,
    /** 연속 관측으로 확정된 상태 (dry / ok / wet) */
    var confirmed: String? = null,
    var pending: String? = null,
    var pendingCount: Int = 0,
    /** 마지막 관수 후 처음 '물 부족'이 확정된 시각 */
    var dryOnsetAt: Long? = null,
    /** 최근 관수 주기(관수 → 다시 마름) 시간 */
    val cyclesHours: MutableList<Double> = mutableListOf()
)

data class WaterSnapshot(
    val label: String,
    val confirmed: String?,
    val hoursSinceWater: Double?,
    val avgDryHours: Double?,
    val cycles: Int,
    val nextInHours: Double?,
    val becameDry: Boolean
)

/**
 * 관수 주기 추적 — 관수 시각과 '다시 마름(dry 확정)' 시각의 간격을 식물별로 모은다.
 * dry 확정은 연속 confirmCycles 회 관측(단일 오분류로 주기가 망가지지 않게).
 * 관수 직후 graceMs 동안은 잎이 아직 회복 전이라 'dry' 관측을 확정에 쓰지 않는다
 * (관수 직후의 dry 로 주기가 몇 분짜리로 잡히는 것을 막는다).
 */
class WateringCycleTracker(
    private val confirmCycles: Int = 2,
    private val keep: Int = 5,
    private val graceMs: Long = 60 * 60_000L
) {
    companion object {
        private const val HOUR = 3_600_000.0
        private const val MIN_CYCLE_H = 0.25
        private const val MAX_CYCLE_H = 24.0 * 30
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val states = mutableMapOf<String, WaterCycleState>()

    /** 마지막 저장 이후 상태가 바뀌었는지 (불필요한 파일 쓰기 방지). */
    @Volatile var dirty = false
        private set

    @Synchronized
    fun onWatered(plantId: String, at: Long) {
        val st = states.getOrPut(plantId) { WaterCycleState() }
        st.lastWateredAt = at
        st.dryOnsetAt = null
        // 관수 후 다시 마르는 시점을 새로 잡기 위해 확정 상태를 비운다
        st.confirmed = null
        st.pending = null
        st.pendingCount = 0
        dirty = true
    }

    @Synchronized
    fun observe(plantId: String, label: String, at: Long): WaterSnapshot {
        val st = states.getOrPut(plantId) { WaterCycleState() }
        var becameDry = false
        val inGrace = label == "dry" && st.lastWateredAt?.let { at - it < graceMs } == true
        if (inGrace) {
            // 관수 직후 — 관측 결과는 그대로 보고하되 확정/주기 계산에는 쓰지 않는다
        } else if (label == st.confirmed) {
            if (st.pending != null) { st.pending = null; st.pendingCount = 0; dirty = true }
        } else {
            if (st.pending == label) st.pendingCount++ else { st.pending = label; st.pendingCount = 1 }
            dirty = true
            if (st.pendingCount >= confirmCycles) {
                val prev = st.confirmed
                st.confirmed = label
                st.pending = null
                st.pendingCount = 0
                if (label == "dry" && prev != "dry") {
                    becameDry = true
                    val w = st.lastWateredAt
                    if (w != null && st.dryOnsetAt == null) {
                        val h = (at - w) / HOUR
                        if (h in MIN_CYCLE_H..MAX_CYCLE_H) {
                            st.cyclesHours += h
                            while (st.cyclesHours.size > keep) st.cyclesHours.removeAt(0)
                        }
                    }
                    st.dryOnsetAt = at
                }
            }
        }
        return snapshot(st, label, at, becameDry)
    }

    @Synchronized
    fun snapshot(plantId: String, at: Long): WaterSnapshot? =
        states[plantId]?.let { snapshot(it, it.confirmed ?: it.pending ?: "ok", at, false) }

    private fun snapshot(st: WaterCycleState, label: String, at: Long, becameDry: Boolean): WaterSnapshot {
        val since = st.lastWateredAt?.let { ((at - it) / HOUR).coerceAtLeast(0.0) }
        val avg = st.cyclesHours.takeIf { it.isNotEmpty() }?.average()
        val next = if (avg != null && since != null) (avg - since).coerceAtLeast(0.0) else null
        return WaterSnapshot(label, st.confirmed, since, avg, st.cyclesHours.size, next, becameDry)
    }

    @Synchronized
    fun forget(plantId: String) {
        if (states.remove(plantId) != null) dirty = true
    }

    @Synchronized
    fun encode(): String {
        dirty = false
        return json.encodeToString(states.toMap())
    }

    @Synchronized
    fun restore(text: String) {
        try {
            states.clear()
            states.putAll(json.decodeFromString<Map<String, WaterCycleState>>(text))
        } catch (_: Exception) { /* 손상 시 빈 상태로 시작 */ }
        dirty = false
    }
}
