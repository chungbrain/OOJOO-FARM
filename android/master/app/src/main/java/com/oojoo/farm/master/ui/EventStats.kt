package com.oojoo.farm.master.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.model.FarmEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * 최근 이벤트 — 통계 먼저, 그다음 시계열.
 *
 *   ① 기간 요약 (최근 14일 · N건)
 *   ② 그룹별 KPI 타일 3개 (관수 / 해충 / 생육·기기) — 아래 그래프의 범례 역할도 겸함
 *   ③ 일별 누적 막대 그래프 (탭/드래그로 날짜 선택 → 그날 그룹별 건수) + 표 보기 전환
 *   ④ 유형별 순위 막대 (상위 5 + 기타)
 *   ⑤ 최근 기록 목록 (5건, 더 보기)
 *
 * 색: 데이터비즈 기본 팔레트 슬롯 1~3 (blue/orange/aqua) — 흰 카드 위에서
 * validate_palette.js 전 쌍 CVD/일반시 검사 통과. 주황·청록은 흰 배경 대비 3:1 미만이라
 * 색만으로 읽히지 않게 이름·숫자 라벨과 표 보기를 항상 함께 둔다. 텍스트는 색을 입히지 않는다.
 */

internal const val EVENT_WINDOW_DAYS = 14

/** 이벤트 그룹 — 순서(ordinal)가 곧 누적 막대의 아래→위 순서이자 색 슬롯 순서. */
enum class EventGroup(val color: Color, @StringRes val labelRes: Int) {
    WATER(Color(0xFF257DE0), R.string.event_group_water),
    PEST(Color(0xFFF47230), R.string.event_group_pest),
    GROWTH(Color(0xFF00B894), R.string.event_group_growth),
}

private val OtherColor = Color(0xFFA0A0A0)

internal fun eventGroupOf(type: String): EventGroup = when (type) {
    "auto_water", "manual_water", "water_low", "water_failed" -> EventGroup.WATER
    "pest_detected", "pest_control" -> EventGroup.PEST
    else -> EventGroup.GROWTH // health_change, harvest_ready, anomaly, battery_low, capture, 기타
}

/** 서버 created_at 은 SQLite datetime('now') = UTC. 기기 로컬 시각으로 바꿔 날짜를 나눈다. */
internal fun eventLocalTime(createdAt: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
    parsePhotoDateTime(createdAt)?.atZone(ZoneOffset.UTC)?.withZoneSameInstant(zone)?.toLocalDateTime()

internal data class DayBucket(val date: LocalDate, val byGroup: List<Int>) {
    val total: Int get() = byGroup.sum()
}

internal data class EventStats(
    /** 오래된 날 → 오늘 */
    val days: List<DayBucket>,
    val byGroup: List<Int>,
    /** (type, count) 많은 순 */
    val byType: List<Pair<String, Int>>,
    val total: Int,
    /** 서버가 최신 N건만 줘서 창 앞쪽이 잘렸는지 */
    val partial: Boolean,
)

/**
 * 창(오늘 포함 windowDays일) 안의 이벤트를 일·그룹·유형별로 집계.
 * @param complete 창 안 이벤트가 빠짐없이 왔는지. false(구버전 서버의 최신 50건 등)이면
 *                 가장 오래된 이벤트 날짜부터만 집계해 '0건'으로 오해되는 날을 만들지 않는다.
 */
internal fun computeEventStats(
    events: List<FarmEvent>,
    today: LocalDate,
    windowDays: Int,
    complete: Boolean,
    zone: ZoneId,
): EventStats {
    val timed = events.mapNotNull { e -> eventLocalTime(e.created_at, zone)?.let { e to it.toLocalDate() } }
    val nominalStart = today.minusDays((windowDays - 1).toLong())
    var start = nominalStart
    if (!complete) {
        val oldest = timed.minOfOrNull { it.second }
        if (oldest != null && oldest.isAfter(start)) start = minOf(oldest, today)
    }
    val n = ChronoUnit.DAYS.between(start, today).toInt() + 1
    val counts = Array(n) { IntArray(EventGroup.entries.size) }
    val typeCounts = LinkedHashMap<String, Int>()
    for ((e, d) in timed) {
        if (d.isBefore(start) || d.isAfter(today)) continue
        counts[ChronoUnit.DAYS.between(start, d).toInt()][eventGroupOf(e.type).ordinal]++
        typeCounts[e.type] = (typeCounts[e.type] ?: 0) + 1
    }
    val days = counts.mapIndexed { i, c -> DayBucket(start.plusDays(i.toLong()), c.toList()) }
    val byGroup = EventGroup.entries.map { g -> days.sumOf { it.byGroup[g.ordinal] } }
    return EventStats(
        days = days,
        byGroup = byGroup,
        byType = typeCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
        total = byGroup.sum(),
        partial = start.isAfter(nominalStart),
    )
}

/** y축 상단 — 짝수라 중간 눈금도 정수. */
internal fun niceAxisMax(maxValue: Int): Int {
    val steps = intArrayOf(2, 4, 6, 8, 10, 12, 16, 20, 24, 30, 40, 50, 60, 80, 100, 120, 160, 200, 240, 300, 400, 500, 600, 800, 1000)
    return steps.firstOrNull { it >= maxValue } ?: ((maxValue + 199) / 200 * 200)
}

/**
 * 최근 이벤트 카드.
 * @param events 서버가 준 이벤트 (최신순)
 * @param complete 최근 [EVENT_WINDOW_DAYS]일 이벤트가 빠짐없이 왔는지
 */
@Composable
fun EventStatsCard(events: List<FarmEvent>, complete: Boolean, modifier: Modifier = Modifier) {
    val S = LocalAppStrings.current
    val fmt = rememberAlbumFormats()
    val today = remember { LocalDate.now() }
    val stats = remember(events, complete, today) {
        computeEventStats(events, today, EVENT_WINDOW_DAYS, complete, ZoneId.systemDefault())
    }
    var selected by remember(stats) { mutableIntStateOf(stats.days.lastIndex) }
    var showTable by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ① 헤더 + 기간 요약
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("📊", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(S.recentEvents, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = OojooTheme.Ink)
                Spacer(Modifier.weight(1f))
                if (events.isNotEmpty()) {
                    Text(
                        stringResource(R.string.event_stats_period, stats.days.size, stats.total),
                        color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            if (events.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(OojooTheme.Line2)
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📭", fontSize = 30.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(S.noEvents, color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {

            if (stats.partial) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.event_stats_partial, events.size),
                    color = OojooTheme.Muted, fontSize = 11.sp
                )
            }

            // ② 그룹별 KPI 타일 (= 그래프 범례)
            Spacer(Modifier.height(10.dp))
            // 타일 높이를 맞춘다 (영문 'Growth & device'처럼 두 줄이 되는 이름 대비)
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EventGroup.entries.forEach { g ->
                    GroupStatTile(g, stats.byGroup[g.ordinal], Modifier.weight(1f).fillMaxHeight())
                }
            }

            // ③ 일별 시계열
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.event_stats_chart_title),
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink
                )
                Spacer(Modifier.weight(1f))
                if (stats.total > 0) {
                    TextButton(
                        onClick = { showTable = !showTable },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            stringResource(if (showTable) R.string.event_stats_show_chart else R.string.event_stats_show_table),
                            color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp
                        )
                    }
                }
            }
            when {
                stats.total == 0 -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(OojooTheme.Line2).padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.event_stats_empty_period, stats.days.size),
                        color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                showTable -> EventDayTable(stats, fmt)
                else -> {
                    DailyEventChart(
                        stats = stats,
                        selected = selected.coerceIn(0, stats.days.lastIndex),
                        onSelect = { selected = it },
                        fmt = fmt,
                        todayLabel = stringResource(R.string.event_stats_today),
                        description = stringResource(R.string.event_stats_chart_desc, stats.days.size)
                    )
                    stats.days.getOrNull(selected)?.let { DayReadout(it, fmt, today) }
                }
            }

            // ④ 유형별 순위
            if (stats.byType.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.event_stats_by_type),
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink
                )
                Spacer(Modifier.height(4.dp))
                TypeRankBars(stats.byType)
            }

            // ⑤ 최근 기록
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.event_stats_latest),
                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink
            )
            Spacer(Modifier.height(2.dp))
            val shown = events.take(if (expanded) 30 else 5)
            shown.forEachIndexed { i, e ->
                LatestEventRow(e, fmt, today)
                if (i != shown.lastIndex) HorizontalDivider(color = OojooTheme.Line2)
            }
            if (events.size > 5) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (expanded) stringResource(R.string.event_stats_less)
                        else stringResource(R.string.event_stats_more, minOf(events.size, 30) - 5),
                        color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp
                    )
                }
            }
            } // events.isNotEmpty
        }
    }
}

@Composable
private fun GroupStatTile(group: EventGroup, count: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).border(1.dp, OojooTheme.Line, shape).background(OojooTheme.Card)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 3.dp).size(10.dp).clip(RoundedCornerShape(3.dp)).background(group.color))
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(group.labelRes), color = OojooTheme.Muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.weight(1f).heightIn(min = 2.dp))
        Text(
            stringResource(R.string.event_count, count),
            color = OojooTheme.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1
        )
    }
}

/**
 * 일별 누적 막대. 막대 폭 ≤ 칸의 60%(최대 24dp), 위쪽만 4dp 둥글게, 바닥은 각지게.
 * 누적 조각 사이 2dp 카드색 틈, 격자는 1px 실선. 선택한 날은 연한 띠 + 막대 위 합계 라벨.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun DailyEventChart(
    stats: EventStats,
    selected: Int,
    onSelect: (Int) -> Unit,
    fmt: AlbumFormats,
    todayLabel: String,
    description: String,
) {
    val measurer = rememberTextMeasurer()
    val onSelectState by rememberUpdatedState(onSelect)
    val days = stats.days
    val n = days.size
    val axisTop = niceAxisMax(days.maxOf { it.total })
    val tickStyle = TextStyle(fontSize = 10.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
    val capStyle = TextStyle(fontSize = 11.sp, color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold)
    // x축 라벨: 오늘을 포함해 약 5개 (뒤에서부터 k일 간격)
    val xLabels = remember(days, fmt, todayLabel) {
        val k = maxOf(1, (n + 4) / 5)
        days.indices.filter { (n - 1 - it) % k == 0 }
            .associateWith { if (it == n - 1) todayLabel else fmt.short(days[it].date) }
    }
    val axisW = 26.dp
    val gridColor = OojooTheme.Line
    val bandColor = OojooTheme.Line2

    fun indexAt(x: Float, width: Float, axisPx: Float): Int =
        (((x - axisPx) / ((width - axisPx) / n)).toInt()).coerceIn(0, n - 1)

    Canvas(
        Modifier.fillMaxWidth()
            .height(168.dp)
            .semantics { contentDescription = description }
            .pointerInput(n) {
                detectTapGestures { p -> onSelectState(indexAt(p.x, size.width.toFloat(), axisW.toPx())) }
            }
            .pointerInput(n) {
                detectHorizontalDragGestures { change, _ ->
                    onSelectState(indexAt(change.position.x, size.width.toFloat(), axisW.toPx()))
                }
            }
    ) {
        val axisPx = axisW.toPx()
        val xBand = 20.dp.toPx()
        val plotTop = 18.dp.toPx() // 선택 막대 위 합계 라벨 자리
        val plotBottom = size.height - xBand
        val plotH = plotBottom - plotTop
        val slot = (size.width - axisPx) / n
        val barW = minOf(slot * 0.6f, 24.dp.toPx())
        val gap = 2.dp.toPx()
        val radius = 4.dp.toPx()
        val unit = plotH / axisTop

        // 선택한 날 띠 (막대 뒤)
        val selLeft = axisPx + slot * selected + slot * 0.08f
        drawRoundRect(
            color = bandColor,
            topLeft = Offset(selLeft, 0f),
            size = Size(slot * 0.84f, plotBottom),
            cornerRadius = CornerRadius(6.dp.toPx())
        )

        // 격자 + y 눈금 (0, 중간, 상단)
        for (v in listOf(0, axisTop / 2, axisTop)) {
            val y = plotBottom - v * unit
            drawLine(gridColor, Offset(axisPx, y), Offset(size.width, y), strokeWidth = 1f)
            val t = measurer.measure(v.toString(), tickStyle)
            drawText(t, topLeft = Offset((axisPx - 6.dp.toPx() - t.size.width).coerceAtLeast(0f), y - t.size.height / 2f))
        }

        // 누적 막대
        days.forEachIndexed { i, day ->
            val cx = axisPx + slot * (i + 0.5f)
            val segs = EventGroup.entries.filter { day.byGroup[it.ordinal] > 0 }
            var yBottom = plotBottom
            segs.forEachIndexed { k, g ->
                val h = day.byGroup[g.ordinal] * unit
                val segTop = yBottom - h
                val drawBottom = if (k == 0) yBottom else yBottom - gap
                val drawTop = minOf(segTop, drawBottom - 1.5.dp.toPx())
                val rect = Rect(cx - barW / 2f, drawTop, cx + barW / 2f, drawBottom)
                if (k == segs.lastIndex) {
                    val r = CornerRadius(minOf(radius, rect.height / 2f, barW / 2f))
                    val path = Path().apply {
                        addRoundRect(RoundRect(rect, topLeft = r, topRight = r, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero))
                    }
                    drawPath(path, g.color)
                } else {
                    drawRect(g.color, topLeft = rect.topLeft, size = rect.size)
                }
                yBottom = segTop
            }
            // 선택한 날 합계 — 막대 위
            if (i == selected && day.total > 0) {
                val t = measurer.measure(day.total.toString(), capStyle)
                val x = (cx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(x, yBottom - 3.dp.toPx() - t.size.height))
            }
        }
        // x축 날짜 라벨
        xLabels.forEach { (i, label) ->
            val style = if (i == selected) tickStyle.copy(color = OojooTheme.Ink) else tickStyle
            val t = measurer.measure(label, style)
            val cx = axisPx + slot * (i + 0.5f)
            val x = (cx - t.size.width / 2f).coerceIn(axisPx, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, plotBottom + 5.dp.toPx()))
        }
    }
}

/** 선택한 날의 그룹별 건수 — 값이 먼저, 이름은 뒤(작게). 색 키는 짧은 선. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayReadout(day: DayBucket, fmt: AlbumFormats, today: LocalDate) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(OojooTheme.Line2).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (day.date == today) "${stringResource(R.string.event_stats_today)} · ${fmt.day(day.date)}" else fmt.day(day.date),
                color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.event_count, day.total),
                color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.height(4.dp))
        // 좁은 화면·영문에서도 잘리지 않게 줄바꿈
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            EventGroup.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(12.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(g.color))
                    Spacer(Modifier.width(4.dp))
                    Text("${day.byGroup[g.ordinal]}", color = OojooTheme.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(g.labelRes), color = OojooTheme.Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 그래프와 같은 데이터의 표 보기 — 이벤트가 있는 날만, 최신 날짜부터. */
@Composable
private fun EventDayTable(stats: EventStats, fmt: AlbumFormats) {
    val head = TextStyle(fontSize = 11.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
    val cell = TextStyle(fontSize = 12.sp, color = OojooTheme.Ink, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.event_stats_date), style = head, modifier = Modifier.weight(1.7f))
            EventGroup.entries.forEach { g ->
                Text(
                    stringResource(g.labelRes), style = head, textAlign = TextAlign.End, maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(stringResource(R.string.event_stats_total), style = head, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
        }
        HorizontalDivider(color = OojooTheme.Line)
        stats.days.asReversed().filter { it.total > 0 }.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.day(d.date), style = cell, modifier = Modifier.weight(1.7f), maxLines = 1)
                EventGroup.entries.forEach { g ->
                    Text("${d.byGroup[g.ordinal]}", style = cell, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
                Text(
                    "${d.total}", style = cell.copy(fontWeight = FontWeight.ExtraBold),
                    textAlign = TextAlign.End, modifier = Modifier.weight(0.9f)
                )
            }
            HorizontalDivider(color = OojooTheme.Line2)
        }
    }
}

/** 유형별 순위 막대 — 많은 순, 상위 5 + 기타. 막대 색 = 그 유형이 속한 그룹 색, 값은 막대 끝. */
@Composable
private fun TypeRankBars(byType: List<Pair<String, Int>>) {
    val S = LocalAppStrings.current
    val otherLabel = stringResource(R.string.event_stats_other)
    val rest = byType.drop(5).sumOf { it.second }
    val rows: List<Triple<String, Int, Color>> =
        byType.take(5).map { (t, c) -> Triple(notiLabel(t, S), c, eventGroupOf(t).color) } +
            (if (rest > 0) listOf(Triple(otherLabel, rest, OtherColor)) else emptyList())
    val maxC = rows.maxOf { it.second }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, count, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, color = OojooTheme.Ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(116.dp)
                )
                BoxWithConstraints(Modifier.weight(1f)) {
                    val barMax = maxWidth - 34.dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(barMax * (count / maxC.toFloat()))
                                .height(10.dp)
                                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                .background(color)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("$count", color = OojooTheme.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

private val TimeOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun LatestEventRow(e: FarmEvent, fmt: AlbumFormats, today: LocalDate) {
    val S = LocalAppStrings.current
    val local = eventLocalTime(e.created_at)
    val time = when {
        local == null -> e.created_at ?: ""
        local.toLocalDate() == today -> "${stringResource(R.string.event_stats_today)} ${local.format(TimeOnly)}"
        else -> "${fmt.short(local.toLocalDate())} ${local.format(TimeOnly)}"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(eventGroupOf(e.type).color))
        Spacer(Modifier.width(10.dp))
        Text(
            notiLabel(e.type, S), color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(time, color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
