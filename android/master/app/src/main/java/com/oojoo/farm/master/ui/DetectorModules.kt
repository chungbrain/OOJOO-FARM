package com.oojoo.farm.master.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.model.DetectorModuleInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/*
 * 온디바이스 검출 모듈 UI — 식물 카드의 추가/삭제 다이얼로그 + 식물 상세의 판독 결과 카드.
 * 모듈 목록·이름·라벨은 서버 카탈로그가 내려준 그대로 그린다(새 모듈이 생겨도 앱 수정 불필요).
 */

// ---------- 표시 도우미 ----------

internal fun DetectorModuleInfo.displayName(en: Boolean): String = if (en && nameEn.isNotBlank()) nameEn else name

internal fun DetectorModuleInfo.displayDescription(en: Boolean): String =
    if (en && descriptionEn.isNotBlank()) descriptionEn else description

internal fun DetectorModuleInfo.labelText(label: String, en: Boolean): String {
    val i = labels.indexOf(label)
    return (if (en) labelsEn else labelsKo).getOrNull(i) ?: label
}

/** 판독 라벨의 심각도 색 — 항상 라벨 글자와 함께 쓰여 색만으로 구분하지 않는다. */
internal fun detectorLabelColor(moduleId: String, label: String?): Color = when {
    label == null -> OojooTheme.Muted
    moduleId == "health_grade" -> when (label) {
        "poor", "very_poor" -> OojooTheme.Red
        "fair" -> OojooTheme.Orange
        else -> OojooTheme.Teal
    }
    moduleId == "pest" -> when (label) {
        "none" -> OojooTheme.Teal
        "light" -> OojooTheme.Orange
        else -> OojooTheme.Red
    }
    moduleId == "watering" -> if (label == "ok") OojooTheme.Teal else OojooTheme.Orange
    else -> OojooTheme.Blue
}

/** 바이트 → "20KB" */
internal fun formatModelSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${(bytes / 1024.0).roundToInt()}KB"
    else -> "${bytes}B"
}

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun hours(v: Double): String = if (v >= 10) "${v.roundToInt()}" else "%.1f".format(v)

@Composable
private fun relativeTime(createdAt: String?): String? {
    val t = eventLocalTime(createdAt) ?: return null
    val mins = Duration.between(t, LocalDateTime.now()).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> stringResource(R.string.detector_time_now)
        mins < 60 -> stringResource(R.string.detector_time_min, mins.toInt())
        mins < 24 * 60 -> stringResource(R.string.detector_time_hour, (mins / 60).toInt())
        else -> rememberAlbumFormats().short(t.toLocalDate())
    }
}

@Composable
private fun triggerText(trigger: String?): String? = when (trigger) {
    "periodic" -> stringResource(R.string.detector_trigger_periodic)
    "module_added" -> stringResource(R.string.detector_trigger_module_added)
    "run_now" -> stringResource(R.string.detector_trigger_run_now)
    "watered" -> stringResource(R.string.detector_trigger_watered)
    "watered_followup" -> stringResource(R.string.detector_trigger_watered_followup)
    "pest_response" -> stringResource(R.string.detector_trigger_pest_response)
    "health_drop" -> stringResource(R.string.detector_trigger_health_drop)
    "roi_changed" -> stringResource(R.string.detector_trigger_roi_changed)
    else -> null
}

/** 모듈 준비 상태 한 줄 (결과가 정상적으로 오고 있으면 null). */
@Composable
private fun stateLine(m: DetectorModuleInfo, hasFarmer: Boolean): Pair<String, Color>? {
    if (!m.enabled) return null
    if (!hasFarmer) return stringResource(R.string.detector_no_farmer) to OojooTheme.Orange
    val st = m.state ?: return if (m.latest == null) stringResource(R.string.detector_state_waiting) to OojooTheme.Muted else null
    return when (st.state) {
        "downloading" -> stringResource(R.string.detector_state_downloading) to OojooTheme.Blue
        "error" -> stringResource(R.string.detector_state_error, st.detail ?: "") to OojooTheme.Red
        "unsupported" -> stringResource(R.string.detector_state_unsupported) to OojooTheme.Orange
        "no_roi" -> stringResource(R.string.detector_state_no_roi) to OojooTheme.Orange
        "ready" -> when {
            st.detail?.startsWith("bundled") == true -> stringResource(R.string.detector_state_bundled) to OojooTheme.Muted
            m.latest == null -> stringResource(R.string.detector_state_ready_waiting) to OojooTheme.Muted
            else -> null
        }
        else -> null
    }
}

@Composable
private fun LabelPill(text: String, color: Color) {
    Surface(shape = OojooTheme.PillShape, color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, color = OojooTheme.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

// ---------- 식물 상세: 판독 결과 카드 ----------

@Composable
fun DetectorModulesCard(
    modules: List<DetectorModuleInfo>,
    hasFarmer: Boolean,
    running: Boolean,
    onManage: () -> Unit,
    onRunNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val en = LocalAppStrings.current.isEnglish
    val enabled = modules.filter { it.enabled }
    Card(
        modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🧠 " + stringResource(R.string.detector_title), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = OojooTheme.Ink)
                    Text(stringResource(R.string.detector_subtitle), color = OojooTheme.Muted, fontSize = 12.sp)
                }
                Surface(
                    shape = OojooTheme.PillShape,
                    color = OojooTheme.GreenBg,
                    border = BorderStroke(1.5.dp, OojooTheme.Ink),
                    modifier = Modifier.clip(OojooTheme.PillShape).clickable { onManage() }
                ) {
                    Text(
                        stringResource(R.string.detector_manage),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            if (enabled.isEmpty()) {
                Text(stringResource(R.string.detector_none_enabled), color = OojooTheme.Muted, fontSize = 13.sp)
            } else {
                enabled.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = OojooTheme.Line)
                    DetectorResultRow(m, hasFarmer, en)
                }
                GradientButton(
                    text = stringResource(if (running) R.string.detector_running else R.string.detector_run_now),
                    onClick = onRunNow,
                    enabled = hasFarmer && !running,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DetectorResultRow(m: DetectorModuleInfo, hasFarmer: Boolean, en: Boolean) {
    val latest = m.latest
    Row(verticalAlignment = Alignment.Top) {
        Text(m.icon, fontSize = 22.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.displayName(en), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink, modifier = Modifier.weight(1f))
                if (latest != null) LabelPill(m.labelText(latest.label, en), detectorLabelColor(m.id, latest.label))
            }
            if (latest != null) {
                val bits = listOfNotNull(
                    latest.confidence?.let { stringResource(R.string.detector_confidence, (it * 100).roundToInt()) },
                    relativeTime(latest.createdAt),
                    triggerText(latest.trigger)
                )
                Text(bits.joinToString(" · "), color = OojooTheme.Muted, fontSize = 11.sp)
            }
            if (m.id == "watering") WateringLines(m)
            stateLine(m, hasFarmer)?.let { (text, color) ->
                Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 관수 주기 모듈 — farmer 가 관측한 마름 주기 + 서버 관수 기록 간격. */
@Composable
private fun WateringLines(m: DetectorModuleInfo) {
    val d = m.latest?.detail
    val lines = mutableListOf<String>()
    d?.num("hoursSinceWater")?.let { lines += stringResource(R.string.detector_water_since, hours(it)) }
    val avg = d?.num("avgDryHours")
    val cycles = d?.int("cycles") ?: 0
    if (avg != null && cycles > 0) {
        lines += stringResource(R.string.detector_water_cycle, hours(avg), cycles)
        d.num("nextInHours")?.let { lines += stringResource(R.string.detector_water_next, hours(it)) }
    } else if (m.latest != null) {
        lines += stringResource(R.string.detector_water_learning)
    }
    m.cycle?.let { c -> c.avgIntervalHours?.takeIf { c.intervals > 0 } }?.let {
        lines += stringResource(R.string.detector_water_interval, hours(it))
    }
    if (d?.str("source") == "heuristic") lines += stringResource(R.string.detector_source_heuristic)
    lines.forEach { Text(it, color = OojooTheme.Ink, fontSize = 12.sp) }
}

// ---------- 식물 카드: 추가/삭제 다이얼로그 ----------

/**
 * 카탈로그의 모든 모듈을 보여주고 식물별로 추가/삭제한다.
 * @param modules null 이면 불러오는 중, 빈 목록이면 불러오기 실패
 * @param busyId 추가/삭제 요청 중인 모듈
 */
@Composable
fun DetectorManageDialog(
    plantName: String,
    modules: List<DetectorModuleInfo>?,
    busyId: String?,
    hasFarmer: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val S = LocalAppStrings.current
    val en = S.isEnglish
    var confirmRemove by remember { mutableStateOf<DetectorModuleInfo?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("🧠 " + stringResource(R.string.detector_title), fontWeight = FontWeight.ExtraBold, color = OojooTheme.Ink)
                Text(plantName, fontSize = 13.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.detector_dialog_desc), fontSize = 12.sp, color = OojooTheme.Muted)
                if (!hasFarmer) Text(stringResource(R.string.detector_no_farmer), fontSize = 12.sp, color = OojooTheme.Orange, fontWeight = FontWeight.Bold)
                when {
                    modules == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = OojooTheme.Green, modifier = Modifier.size(28.dp))
                    }
                    modules.isEmpty() -> Text(stringResource(R.string.detector_load_failed), color = OojooTheme.Red, fontSize = 13.sp)
                    else -> modules.forEach { m ->
                        ManageRow(m, en, busy = busyId == m.id, anyBusy = busyId != null, hasFarmer = hasFarmer,
                            onAdd = { onAdd(m.id) }, onRemove = { confirmRemove = m })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.close, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
    )

    confirmRemove?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.detector_remove_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.detector_remove_msg, m.displayName(en))) },
            confirmButton = {
                TextButton(onClick = { onRemove(m.id); confirmRemove = null }) {
                    Text(stringResource(R.string.detector_remove), color = OojooTheme.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(S.cancel) } }
        )
    }
}

@Composable
private fun ManageRow(
    m: DetectorModuleInfo,
    en: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    hasFarmer: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(OojooTheme.SmallShape)
            .background(if (m.enabled) OojooTheme.GreenBg else OojooTheme.Card)
            .border(1.5.dp, if (m.enabled) OojooTheme.GreenDark else OojooTheme.Line, OojooTheme.SmallShape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(m.icon, fontSize = 24.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(m.displayName(en), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink)
            Text(m.displayDescription(en), fontSize = 11.sp, color = OojooTheme.Muted)
            val meta = listOfNotNull(
                m.modelSize?.let { stringResource(R.string.detector_model_size, formatModelSize(it)) },
                if (m.perSpecies) stringResource(R.string.detector_model_per_species) else null,
                if (!m.available) stringResource(R.string.detector_unavailable) else null
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 11.sp, color = OojooTheme.Muted)
            if (m.enabled) stateLine(m, hasFarmer)?.let { (text, color) ->
                Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            busy -> CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            m.enabled -> OutlinedButton(
                onClick = onRemove,
                enabled = !anyBusy,
                border = BorderStroke(1.5.dp, OojooTheme.Red),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text(stringResource(R.string.detector_remove), color = OojooTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
            else -> Button(
                onClick = onAdd,
                enabled = !anyBusy && m.available,
                colors = ButtonDefaults.buttonColors(containerColor = OojooTheme.GreenDark),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text(stringResource(R.string.detector_add), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}
