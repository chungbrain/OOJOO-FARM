package com.oojoo.farm.slave.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.oojoo.farm.slave.model.DetectorCatalogResponse
import com.oojoo.farm.slave.model.DetectorModule
import com.oojoo.farm.slave.model.DetectorResultItem
import com.oojoo.farm.slave.model.DetectorResultsRequest
import com.oojoo.farm.slave.model.DetectorStateItem
import com.oojoo.farm.slave.model.DetectorStateRequest
import com.oojoo.farm.slave.model.ModelRef
import com.oojoo.farm.slave.model.Plant
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.vision.HealthGradeNet
import com.oojoo.farm.slave.vision.Ojp1
import com.oojoo.farm.slave.vision.Ojp1Model
import com.oojoo.farm.slave.vision.PestNet
import com.oojoo.farm.slave.vision.PlantHealthNet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 온디바이스 상태검출 모듈 관리자.
 *
 * master 가 식물 카드에서 켠 모듈(건강상태 판별 / 해충 검출 / 관수 주기 모니터링 …)마다
 *  1) 서버 카탈로그(GET /api/detectors)에서 모델 파일 정보를 받아
 *  2) 모델을 내려받아 sha256 검증 후 보관하고(ModelStore) — 모듈이 꺼지면 파일도 정리
 *  3) ROI 크롭을 주기적·이벤트 시 판독해(run)
 *  4) 결과를 POST /api/detectors/results 로 master 에 돌려준다(report).
 *
 * 서버가 /api/detectors 를 모르면(구버전) 앱 내장 모델로 기존 동작(건강·해충·물 감시)을 유지하고
 * 결과/상태 보고는 생략한다.
 */
object DetectorManager {
    private const val TAG = "DetectorManager"
    private const val CATALOG_FILE = "detector_catalog.json"
    private const val WATER_FILE = "watering_cycles.json"
    private const val PLANTS_FILE = "detector_plants.json"

    /** outputs: 모델 출력 순서의 라벨 (버전마다 다를 수 있다) */
    data class Loaded(
        val model: Ojp1Model,
        val file: String,
        val sha: String?,
        val source: String,
        val speciesId: String?,
        val outputs: List<String>
    )

    /** 모듈 1회 판독 결과 */
    data class Output(
        val moduleId: String,
        val label: String,
        val labelKo: String,
        val confidence: Double,
        val modelSha: String?,
        /** server | bundled | heuristic */
        val source: String,
        val detail: JsonObject?,
        val at: Long
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var appCtx: Context
    private lateinit var store: ModelStore
    @Volatile private var initialized = false

    @Volatile var catalog: List<DetectorModule> = emptyList()
        private set
    @Volatile private var defaults: Set<String> = DetectorIds.DEFAULTS
    /** false = 서버가 검출 모듈 API 를 모름(구버전) → 내장 모델로 동작, 결과/상태 보고 생략 */
    @Volatile var serverSupported = true
        private set
    @Volatile private var enabled: Map<String, Set<String>> = emptyMap()
    @Volatile private var firstSyncDone = false

    private val models = ConcurrentHashMap<String, Loaded>()          // "plantId|moduleId"
    private val latest = ConcurrentHashMap<String, Output>()          // "plantId|moduleId"
    private val reportedState = ConcurrentHashMap<String, String>()   // "plantId|moduleId" → 서명
    private val syncMutex = Mutex()

    val planner = DetectorPlanner()
    private val gate = ReportGate()
    private val water = WateringCycleTracker()

    fun init(ctx: Context) {
        if (initialized) return
        appCtx = ctx.applicationContext
        store = ModelStore(File(appCtx.filesDir, "detector_models")) { url -> download(url) }
        // 오프라인 기동 대비 — 마지막으로 받은 카탈로그와 관수 주기 기록 복원
        try {
            File(appCtx.filesDir, CATALOG_FILE).takeIf { it.isFile }?.readText()?.let {
                applyCatalog(json.decodeFromString<DetectorCatalogResponse>(it))
            }
        } catch (_: Exception) {}
        try {
            File(appCtx.filesDir, WATER_FILE).takeIf { it.isFile }?.readText()?.let { water.restore(it) }
        } catch (_: Exception) {}
        initialized = true
    }

    private suspend fun download(url: String): ByteArray = ApiClient.api.downloadModel(url).use { body ->
        val len = body.contentLength()
        if (len > ModelStore.MAX_MODEL_BYTES) throw IllegalStateException("model too large ($len bytes)")
        body.bytes()
    }

    /** 마지막으로 서버에서 받은 식물 목록 — 오프라인 재기동 때 모듈을 이어서 준비하기 위함. */
    fun cachedPlants(): List<Plant>? = try {
        File(appCtx.filesDir, PLANTS_FILE).takeIf { it.isFile }?.readText()?.let { json.decodeFromString<List<Plant>>(it) }
    } catch (_: Exception) {
        null
    }

    fun enabledSnapshot(): Map<String, Set<String>> = enabled
    fun enabledFor(plantId: String): Set<String> = enabled[plantId].orEmpty()

    /** 어떤 식물에든 이 모듈이 켜져 있는지. 아직 한 번도 동기화하지 못했으면 기존 동작대로 true. */
    fun anyEnabled(moduleId: String): Boolean {
        val e = enabled
        return !firstSyncDone || e.isEmpty() || e.values.any { moduleId in it }
    }
    fun moduleOf(id: String): DetectorModule? = catalog.firstOrNull { it.id == id }

    /** 식물의 모듈별 최신 판독 (켜진 모듈만) */
    fun latestFor(plantId: String): Map<String, Output> =
        enabledFor(plantId).mapNotNull { m -> latest["$plantId|$m"]?.let { m to it } }.toMap()

    fun waterSnapshot(plantId: String): WaterSnapshot? = water.snapshot(plantId, System.currentTimeMillis())

    /** 판독 요청 (이벤트 계기) — 실제 실행은 ROI 루프가 다음 회차에. */
    fun request(trigger: String, plantId: String?, modules: Set<String>?, delayMs: Long = 0) {
        planner.request(trigger, plantId, modules, System.currentTimeMillis() + delayMs)
    }

    fun onWatered(plantId: String, at: Long = System.currentTimeMillis()) {
        water.onWatered(plantId, at)
        saveWater()
    }

    // ---------- 카탈로그 / 동기화 ----------

    private fun applyCatalog(c: DetectorCatalogResponse) {
        if (c.modules.isNotEmpty()) catalog = c.modules
        defaults = c.defaults.toSet().ifEmpty { DetectorIds.DEFAULTS }
    }

    /** 구버전 서버/첫 기동 오프라인용 — 앱 내장(assets) 모델로 기존 기능을 유지한다. */
    private fun legacyCatalog(): List<DetectorModule> {
        val species = listOf(
            "basil", "cactus", "cherry_tomato", "green_onion", "herb", "lettuce", "onion",
            "pepper", "perilla", "potato", "pumpkin", "strawberry", "sweet_potato", "zucchini"
        )
        return listOf(
            DetectorModule(
                id = DetectorIds.HEALTH, icon = "🌿", name = "건강상태 판별", nameEn = "Health check",
                labels = HealthGradeNet.LABELS, labelsKo = HealthGradeNet.LABELS.map { HealthGradeNet.labelKo(it) },
                model = ModelRef("health_grade.bin")
            ),
            DetectorModule(
                id = DetectorIds.PEST, icon = "🐛", name = "해충 검출", nameEn = "Pest detection",
                labels = PestNet.LABELS, labelsKo = PestNet.LABELS.map { PestNet.labelKo(it) },
                model = ModelRef("pest.bin")
            ),
            DetectorModule(
                id = DetectorIds.WATERING, icon = "💧", name = "관수 주기 모니터링", nameEn = "Watering cycle",
                labels = listOf("dry", "ok", "wet"), labelsKo = listOf("물 부족", "적정", "물 과다"),
                perSpecies = true, speciesModels = species.associateWith { ModelRef("$it.bin") },
                speciesOutputs = PlantHealthNet.LABELS
            )
        )
    }

    private suspend fun refreshCatalog() {
        try {
            val c = ApiClient.api.detectorCatalog()
            serverSupported = true
            applyCatalog(c)
            try { File(appCtx.filesDir, CATALOG_FILE).writeText(json.encodeToString(c)) } catch (_: Exception) {}
        } catch (e: HttpException) {
            if (e.code() == 404) {
                serverSupported = false
                catalog = legacyCatalog()
                defaults = DetectorIds.DEFAULTS
            } else if (catalog.isEmpty()) {
                catalog = legacyCatalog()
            }
        } catch (_: Exception) {
            if (catalog.isEmpty()) catalog = legacyCatalog() // 오프라인 첫 기동
        }
    }

    /**
     * 식물 목록(plants[].detectors) 기준으로 모듈을 맞춘다.
     *  - 켜진 모듈: 모델 준비(필요하면 다운로드) → 상태 보고
     *  - 꺼진 모듈: 메모리·모델 파일 정리
     *  - 새로 켜진 모듈: 즉시 판독 요청(module_added)
     * @param cache 서버에서 받은 목록이면 true (오프라인용으로 보관)
     * @return 사람이 읽을 변경 요약 (로그용), 변경 없으면 null
     */
    suspend fun sync(slaveId: String, plants: List<Plant>, roiPlantIds: Set<String>, cache: Boolean = true): String? = syncMutex.withLock {
        if (cache) {
            try { File(appCtx.filesDir, PLANTS_FILE).writeText(json.encodeToString(plants)) } catch (_: Exception) {}
        }
        refreshCatalog()
        val byId = catalog.associateBy { it.id }
        val next = plants.associate { p ->
            p.id to (p.detectors?.toSet() ?: defaults).filter { it in byId }.toSet()
        }
        val prev = enabled
        val states = mutableListOf<DetectorStateItem>()
        val prepared = mutableListOf<String>()
        for (p in plants) {
            for (mid in next[p.id].orEmpty()) {
                val key = "${p.id}|$mid"
                val wasLoaded = models[key]
                var st = prepare(key, byId.getValue(mid), p, slaveId)
                if (st.state in setOf("ready", "unsupported") && p.id !in roiPlantIds) {
                    st = st.copy(state = "no_roi", detail = "set an RoI for this plant to start")
                }
                if (models[key] != null && models[key] !== wasLoaded) prepared += "${byId.getValue(mid).icon}${p.name}"
                val sig = "${st.state}|${st.detail}|${st.modelSha}"
                if (reportedState[key] != sig) {
                    states += st
                    reportedState[key] = sig
                }
            }
        }
        enabled = next

        // 꺼진 모듈 / 사라진 식물 정리
        val live = next.flatMap { (p, ms) -> ms.map { "$p|$it" } }.toSet()
        val removed = (prev.flatMap { (p, ms) -> ms.map { "$p|$it" } }.toSet() - live)
        models.keys.retainAll(live)
        latest.keys.retainAll(live)
        reportedState.keys.retainAll(live)
        removed.forEach { k -> k.split('|').let { gate.forget(it[0], it[1]) } }
        (prev.keys - next.keys).forEach { water.forget(it) }
        if (water.dirty) saveWater()
        val deleted = store.retainOnly(models.values.mapNotNull { it.sha }.toSet())

        reportStates(slaveId, states)

        val now = System.currentTimeMillis()
        val added = mutableListOf<String>()
        if (firstSyncDone) {
            for ((pid, ms) in next) {
                val newOnes = ms - prev[pid].orEmpty()
                if (newOnes.isNotEmpty()) {
                    planner.request(Triggers.MODULE_ADDED, pid, newOnes, now)
                    added += newOnes.map { "${byId[it]?.icon ?: ""}$it" }
                }
            }
        }
        firstSyncDone = true

        val parts = mutableListOf<String>()
        if (prepared.isNotEmpty()) parts += "모델 준비 ${prepared.size}건"
        if (added.isNotEmpty()) parts += "추가 ${added.joinToString(",")}"
        if (removed.isNotEmpty()) parts += "삭제 ${removed.size}건"
        if (deleted > 0) parts += "모델 파일 정리 ${deleted}개"
        if (!serverSupported) parts += "구버전 서버 — 내장 모델 사용"
        parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun stateItem(plant: Plant, moduleId: String, state: String, detail: String?, sha: String?) =
        DetectorStateItem(plant.id, moduleId, state, detail?.take(200), sha)

    /** 모듈 하나의 모델을 준비하고 최종 상태를 돌려준다. */
    private suspend fun prepare(key: String, module: DetectorModule, plant: Plant, slaveId: String): DetectorStateItem {
        var speciesId: String? = null
        val ref: ModelRef = if (module.perSpecies) {
            speciesId = resolveSpecies(module, plant.species ?: plant.name)
            speciesId?.let { module.speciesModels?.get(it) } ?: run {
                models.remove(key)
                // 종 모델이 없으면 색 통계(휴리스틱)로 추정 — 판독은 계속한다
                return stateItem(plant, module.id, "unsupported", "no species model for '${plant.species ?: plant.name}' — color heuristic", null)
            }
        } else {
            module.model ?: run {
                models.remove(key)
                return stateItem(plant, module.id, "error", "no model on server", null)
            }
        }
        val outputs = ref.labels.ifEmpty { if (module.perSpecies) module.speciesOutputs ?: PlantHealthNet.LABELS else module.labels }
        val expected = outputs.size

        val cur = models[key]
        if (cur != null && cur.file == ref.file && cur.outputs == outputs && (ref.sha256.isBlank() || cur.sha == ref.sha256)) {
            return stateItem(plant, module.id, "ready", cur.source, cur.sha)
        }

        var failure: String? = null
        // 1) 서버 모델 (sha256 검증)
        if (ref.sha256.isNotBlank()) {
            if (!store.has(ref) && serverSupported) {
                reportStates(slaveId, listOf(stateItem(plant, module.id, "downloading", ref.file, ref.sha256)), remember = false)
            }
            val r = store.ensure(ref)
            val bytes = r.getOrNull()
            if (bytes != null) {
                try {
                    val m = Ojp1.parse(bytes)
                    if (m.dense.out == expected) {
                        models[key] = Loaded(m, ref.file, ref.sha256, "server", speciesId, outputs)
                        return stateItem(plant, module.id, "ready", "server", ref.sha256)
                    }
                    failure = "model has ${m.dense.out} outputs, expected $expected"
                } catch (e: Exception) {
                    failure = "invalid model: ${e.message}"
                }
            } else {
                failure = r.exceptionOrNull()?.message ?: "download failed"
            }
            Log.w(TAG, "model ${ref.file} for ${module.id}: $failure")
        }
        // 2) 앱 내장 모델 (구버전 서버 / 오프라인)
        val bundled = Ojp1.load(appCtx, ref.file.removeSuffix(".bin"))
        if (bundled != null && bundled.dense.out == expected) {
            // 앱 내장 파일은 원래 출력 수 — 서버 버전이 라벨을 늘렸으면 맞지 않아 아래 error 로 간다
            models[key] = Loaded(bundled, ref.file, null, "bundled", speciesId, outputs)
            return stateItem(plant, module.id, "ready", if (failure != null) "bundled (download failed: $failure)" else "bundled", null)
        }
        models.remove(key)
        return stateItem(plant, module.id, "error", failure ?: "model unavailable", null)
    }

    private suspend fun reportStates(slaveId: String, items: List<DetectorStateItem>, remember: Boolean = true) {
        if (items.isEmpty() || !serverSupported || slaveId.isBlank()) return
        try {
            ApiClient.api.reportDetectorState(DetectorStateRequest(slaveId, items))
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 404) serverSupported = false
            // 다음 동기화 때 다시 보내도록 서명을 지운다
            if (remember) items.forEach { reportedState.remove("${it.plantId}|${it.moduleId}") }
        }
    }

    // ---------- 판독 ----------

    /**
     * ROI 크롭 하나에 대해 요청된 모듈을 판독한다.
     * @param modules 모듈 id → 계기
     * @param heuristicNeedWater 종 모델이 없을 때 관수 모듈이 쓰는 색 통계 판정
     */
    fun run(plantId: String, modules: Map<String, String>, crop: Bitmap, heuristicNeedWater: Boolean, now: Long): Map<String, Output> {
        val out = linkedMapOf<String, Output>()
        for (mid in modules.keys) {
            val module = moduleOf(mid) ?: continue
            val loaded = models["$plantId|$mid"]
            val o = try {
                if (mid == DetectorIds.WATERING) runWatering(module, loaded, plantId, crop, heuristicNeedWater, now)
                else runClassifier(module, loaded, crop, now)
            } catch (e: Exception) {
                Log.w(TAG, "detector $mid failed: ${e.message}")
                null
            } ?: continue
            out[mid] = o
            latest["$plantId|$mid"] = o
        }
        if (water.dirty) saveWater()
        return out
    }

    /** 식물 종 이름 → 종 모델 id: 서버 별칭(새 종 포함) 먼저, 없으면 앱 내장 별칭. */
    private fun resolveSpecies(module: DetectorModule, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        module.speciesAliases?.get(normalizeSpecies(raw))?.let { return it }
        return PlantHealthNet.resolve(raw)
    }

    private fun probsOf(loaded: Loaded, crop: Bitmap): DoubleArray =
        softmax(loaded.model.forward(loaded.model.bitmapToRgb48(crop)))

    /** 일반 분류 모듈 — 카탈로그 labels 순서가 모델 출력 순서. 서버에 새 분류 모듈을 추가해도 앱 수정 없이 동작. */
    private fun runClassifier(module: DetectorModule, loaded: Loaded?, crop: Bitmap, now: Long): Output? {
        loaded ?: return null
        val probs = probsOf(loaded, crop)
        val idx = probs.indices.maxByOrNull { probs[it] } ?: return null
        val label = loaded.outputs.getOrNull(idx) ?: return null
        val ko = module.labelsKo.getOrNull(module.labels.indexOf(label)) ?: label
        return Output(module.id, label, ko, probs[idx], loaded.sha, loaded.source, null, now)
    }

    private fun runWatering(module: DetectorModule, loaded: Loaded?, plantId: String, crop: Bitmap, heuristicNeedWater: Boolean, now: Long): Output {
        val outputs = loaded?.outputs ?: module.speciesOutputs ?: PlantHealthNet.LABELS
        val probs = loaded?.let { probsOf(it, crop) }
        val (label, conf) = if (probs != null) waterLabelFromProbs(probs, outputs)
        else (if (heuristicNeedWater) "dry" else "ok") to 0.5
        val snap = water.observe(plantId, label, now)
        val detail = buildJsonObject {
            put("source", if (loaded != null) "species:${loaded.speciesId}" else "heuristic")
            snap.confirmed?.let { put("confirmed", it) }
            snap.hoursSinceWater?.let { put("hoursSinceWater", round1(it)) }
            snap.avgDryHours?.let { put("avgDryHours", round1(it)) }
            put("cycles", snap.cycles)
            snap.nextInHours?.let { put("nextInHours", round1(it)) }
            if (probs != null) {
                probs.getOrNull(outputs.indexOf("water_low"))?.let { put("dryProb", round2(it)) }
                probs.getOrNull(outputs.indexOf("water_high"))?.let { put("wetProb", round2(it)) }
            }
        }
        val idx = module.labels.indexOf(label)
        return Output(
            DetectorIds.WATERING, label, module.labelsKo.getOrNull(idx) ?: label, conf,
            loaded?.sha, loaded?.source ?: "heuristic", detail, now
        )
    }

    /** 판독 결과 보고 — 이벤트 계기·라벨 변화는 즉시, 같은 라벨은 5분에 1회. */
    suspend fun report(slaveId: String, plantId: String, outputs: Map<String, Output>, triggers: Map<String, String>) {
        if (!serverSupported || outputs.isEmpty() || slaveId.isBlank()) return
        val now = System.currentTimeMillis()
        val items = outputs.values
            .filter { gate.shouldReport(plantId, it.moduleId, it.label, triggers[it.moduleId] ?: Triggers.PERIODIC, now) }
            .map {
                DetectorResultItem(
                    moduleId = it.moduleId,
                    label = it.label,
                    confidence = (it.confidence * 1000).roundToInt() / 1000.0,
                    trigger = triggers[it.moduleId] ?: Triggers.PERIODIC,
                    modelSha = it.modelSha,
                    detail = it.detail
                )
            }
        if (items.isEmpty()) return
        try {
            ApiClient.api.reportDetectorResults(DetectorResultsRequest(slaveId, plantId, items))
            items.forEach { gate.markReported(plantId, it.moduleId, it.label, now) }
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 404) serverSupported = false
            // 실패하면 표시하지 않음 → 다음 회차에 다시 보낸다
        }
    }

    private fun saveWater() {
        try { File(appCtx.filesDir, WATER_FILE).writeText(water.encode()) } catch (_: Exception) {}
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
