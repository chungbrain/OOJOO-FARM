package com.oojoo.farm.master.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.oojoo.farm.master.R
import com.oojoo.farm.master.model.PlantPhoto
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * 성장 사진첩 — 식물 상세의 사진 모음.
 *
 * 예전: 3열 그리드를 사진 수만큼 아래로 이어 붙여, 사진이 쌓일수록 화면이 끝없이 길어졌다.
 * 지금: 높이가 고정된 카드 하나로 정리한다.
 *   - 큰 대표 사진(가로 스와이프로 넘김, 처음엔 최신 사진) + D+N·날짜·순번 표시
 *   - 아래 가로 썸네일 띠 — 탭하면 해당 사진으로 이동, 현재 사진을 따라 자동 스크롤
 *   - [성장 재생] — 처음부터 끝까지 사진을 넘겨 보여주는 플립북
 *   - [전체 보기] — 날짜별로 묶은 전체 화면 그리드
 *   - 대표 사진/그리드 탭 → 전체 화면 뷰어 (스와이프, 핀치·두 번 탭 확대)
 */

private val PhotoDateRegex = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?""")

/** "yyyy-MM-dd HH:mm"(Farmer 촬영 시각) / "yyyy-MM-dd HH:mm:ss"(서버) / "yyyy-MM-dd" 를 관대하게 파싱. */
internal fun parsePhotoDateTime(s: String?): LocalDateTime? {
    val m = PhotoDateRegex.find(s ?: return null) ?: return null
    val g = m.groupValues
    return runCatching {
        LocalDateTime.of(
            g[1].toInt(), g[2].toInt(), g[3].toInt(),
            g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0
        )
    }.getOrNull()
}

private fun PlantPhoto.dateTime(): LocalDateTime? = parsePhotoDateTime(taken_at) ?: parsePhotoDateTime(created_at)

private fun PlantPhoto.fullUrl(): String = ApiClient.baseUrl.trimEnd('/') + url
private fun PlantPhoto.thumbUrl(): String = ApiClient.baseUrl.trimEnd('/') + (thumb_url ?: url)

/** 화면에 쓰는 날짜 포맷 묶음 — 앱 언어(ko/en)에 맞춘 패턴은 strings.xml 에 둔다. */
private class AlbumFormats(val locale: Locale, dayPattern: String, dayYearPattern: String, shortPattern: String) {
    private val day = DateTimeFormatter.ofPattern(dayPattern, locale)
    private val dayYear = DateTimeFormatter.ofPattern(dayYearPattern, locale)
    private val short = DateTimeFormatter.ofPattern(shortPattern, locale)
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val thisYear = LocalDate.now().year

    fun day(d: LocalDate): String = if (d.year == thisYear) d.format(day) else d.format(dayYear)
    fun dayTime(dt: LocalDateTime): String = "${day(dt.toLocalDate())} ${dt.format(time)}"
    fun short(d: LocalDate): String = d.format(short)
}

@Composable
private fun rememberAlbumFormats(): AlbumFormats {
    val locale = LocalConfiguration.current.locales[0]
    val dayP = stringResource(R.string.growth_album_fmt_day)
    val dayYearP = stringResource(R.string.growth_album_fmt_day_year)
    val shortP = stringResource(R.string.growth_album_fmt_short)
    return remember(locale, dayP, dayYearP, shortP) { AlbumFormats(locale, dayP, dayYearP, shortP) }
}

/** 심은 날 기준 경과일(D+N). 심은 날짜가 없거나 사진이 더 이르면 null. */
private fun dayCount(planted: LocalDate?, photo: PlantPhoto): Long? {
    val p = planted ?: return null
    val d = photo.dateTime()?.toLocalDate() ?: return null
    val n = ChronoUnit.DAYS.between(p, d)
    return if (n >= 0) n else null
}

private fun photoCaption(photo: PlantPhoto, fmt: AlbumFormats): String =
    photo.dateTime()?.let { fmt.dayTime(it) } ?: (photo.taken_at ?: photo.created_at ?: "")

/**
 * 성장 사진첩 카드.
 * @param photos 오래된 → 최신 순 (백엔드 `/api/photos/plant/:id` 정렬 그대로)
 * @param plantedAt 식물의 심은 날짜 (D+N 표시용, 없으면 생략)
 * @param footer 카드 하단 슬롯 — 관심영역/숏클립 버튼 등
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrowthAlbumCard(
    photos: List<PlantPhoto>,
    plantedAt: String?,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val fmt = rememberAlbumFormats()
    val planted = remember(plantedAt) { parsePhotoDateTime(plantedAt)?.toLocalDate() }
    var gridOpen by remember { mutableStateOf(false) }
    var viewerStart by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, OojooTheme.Ink, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 헤더 — 제목 + 장수 + [전체 보기]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("📸", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.growth_album_title),
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = OojooTheme.Ink
                )
                if (photos.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = OojooTheme.PillShape,
                        color = OojooTheme.GreenBg,
                        border = BorderStroke(1.dp, OojooTheme.GreenDark)
                    ) {
                        Text(
                            stringResource(R.string.growth_album_count, photos.size),
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = OojooTheme.GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (photos.isNotEmpty()) {
                    TextButton(
                        onClick = { gridOpen = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            stringResource(R.string.growth_album_view_all) + " ›",
                            color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp
                        )
                    }
                }
            }

            if (photos.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OojooTheme.GreenBg)
                        .padding(vertical = 22.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌱", fontSize = 34.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.growth_album_empty),
                        color = OojooTheme.Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.growth_album_hint),
                        color = OojooTheme.Muted, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                val pagerState = rememberPagerState(initialPage = photos.lastIndex) { photos.size }
                val scope = rememberCoroutineScope()
                var playing by remember { mutableStateOf(false) }

                // 손으로 넘기면 재생 중지
                val dragged by pagerState.interactionSource.collectIsDraggedAsState()
                LaunchedEffect(dragged) { if (dragged) playing = false }
                // 성장 재생 — 사진 수에 맞춰 전체가 몇 초 안에 끝나도록 간격 조절 (플립북)
                LaunchedEffect(playing) {
                    if (!playing) return@LaunchedEffect
                    if (pagerState.currentPage >= photos.lastIndex) pagerState.scrollToPage(0)
                    val interval = (8000L / photos.size).coerceIn(180L, 700L)
                    while (playing && pagerState.currentPage < photos.lastIndex) {
                        delay(interval)
                        val next = pagerState.currentPage + 1
                        if (interval >= 450L) pagerState.animateScrollToPage(next, animationSpec = tween(320))
                        else pagerState.scrollToPage(next)
                    }
                    playing = false
                }

                // 기록 기간 요약 + [성장 재생]
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val first = photos.first().dateTime()?.toLocalDate()
                    val last = photos.last().dateTime()?.toLocalDate()
                    val summary = when {
                        first == null || last == null -> stringResource(R.string.growth_album_hint)
                        first == last -> fmt.day(first)
                        else -> stringResource(
                            R.string.growth_album_range,
                            fmt.short(first), fmt.short(last),
                            ChronoUnit.DAYS.between(first, last).toInt() + 1
                        )
                    }
                    Text(
                        summary, color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (photos.size >= 2) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = { playing = !playing },
                            shape = OojooTheme.PillShape,
                            color = if (playing) OojooTheme.GreenDark else OojooTheme.Card,
                            border = BorderStroke(1.5.dp, OojooTheme.GreenDark)
                        ) {
                            Row(
                                Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (playing) Color.White else OojooTheme.GreenDark,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    stringResource(if (playing) R.string.growth_album_pause else R.string.growth_album_play),
                                    color = if (playing) Color.White else OojooTheme.GreenDark,
                                    fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                AlbumHero(
                    photos = photos,
                    pagerState = pagerState,
                    planted = planted,
                    fmt = fmt,
                    onOpen = { playing = false; viewerStart = it },
                    onStep = { delta ->
                        playing = false
                        scope.launch {
                            val target = (pagerState.currentPage + delta).coerceIn(0, photos.lastIndex)
                            pagerState.animateScrollToPage(target)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                ThumbStrip(
                    photos = photos,
                    selected = pagerState.currentPage,
                    fmt = fmt,
                    dark = false,
                    thumbSize = 54.dp,
                    onSelect = { i ->
                        playing = false
                        scope.launch { pagerState.animateScrollToPage(i) }
                    }
                )

                if (gridOpen) {
                    AlbumGridDialog(
                        photos = photos,
                        planted = planted,
                        fmt = fmt,
                        onPick = { viewerStart = it },
                        onDismiss = { gridOpen = false }
                    )
                }
                viewerStart?.let { start ->
                    PhotoViewerDialog(
                        photos = photos,
                        startIndex = start,
                        planted = planted,
                        fmt = fmt,
                        onDismiss = { lastPage ->
                            viewerStart = null
                            // 뷰어에서 보던 사진을 대표 사진으로 이어서 보여준다
                            scope.launch { pagerState.scrollToPage(lastPage) }
                        }
                    )
                }
            }

            footer()
        }
    }
}

/** 대표 사진 — 가로 스와이프 페이저 + 오버레이(D+N, 순번, 날짜/위치, 이전/다음, 크게 보기). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumHero(
    photos: List<PlantPhoto>,
    pagerState: PagerState,
    planted: LocalDate?,
    fmt: AlbumFormats,
    onOpen: (Int) -> Unit,
    onStep: (Int) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val current = photos.getOrNull(pagerState.currentPage) ?: return
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(shape)
            .border(2.dp, OojooTheme.Ink, shape)
            .background(OojooTheme.Line2)
    ) {
        HorizontalPager(
            state = pagerState,
            key = { photos.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(page) }) {
                // 썸네일을 먼저 깔고 원본이 오면 덮는다 (썸네일 띠에서 이미 캐시됨)
                if (photo.thumb_url != null) {
                    AsyncImage(
                        model = photo.thumbUrl(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                }
                AsyncImage(
                    model = photo.fullUrl(),
                    contentDescription = photoCaption(photo, fmt),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 상단: D+N / 순번
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dayCount(planted, current)?.let { n ->
                Surface(shape = OojooTheme.PillShape, color = OojooTheme.Green, border = BorderStroke(1.5.dp, OojooTheme.Ink)) {
                    Text(
                        stringResource(R.string.growth_album_dday, n.toInt()),
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = OojooTheme.PillShape, color = Color.Black.copy(alpha = 0.55f)) {
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // 좌우 이동 버튼 (스와이프를 모르는 사용자를 위해)
        if (pagerState.currentPage > 0) {
            HeroArrow("‹", stringResource(R.string.growth_album_prev), Modifier.align(Alignment.CenterStart)) { onStep(-1) }
        }
        if (pagerState.currentPage < photos.lastIndex) {
            HeroArrow("›", stringResource(R.string.growth_album_next), Modifier.align(Alignment.CenterEnd)) { onStep(1) }
        }

        // 하단: 날짜·위치 + 크게 보기
        Row(
            Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .padding(start = 12.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    photoCaption(current, fmt),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1
                )
                current.location?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "📍 $it", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { onOpen(pagerState.currentPage) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.growth_album_fullscreen),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun HeroArrow(glyph: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.8f),
        border = BorderStroke(1.5.dp, OojooTheme.Ink),
        modifier = modifier
            .padding(horizontal = 8.dp)
            .size(34.dp)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph, color = OojooTheme.Ink, fontSize = 22.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

/**
 * 가로 썸네일 띠. 선택된 사진이 가운데 근처에 오도록 자동 스크롤.
 * 날짜가 바뀌는 첫 사진에만 날짜 라벨을 달아 띠가 복잡해지지 않게 한다.
 */
@Composable
private fun ThumbStrip(
    photos: List<PlantPhoto>,
    selected: Int,
    fmt: AlbumFormats,
    dark: Boolean,
    thumbSize: Dp,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
    val spacing = 6.dp
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        val info = listState.layoutInfo
        val itemPx = info.visibleItemsInfo.firstOrNull()?.size ?: 0
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val perScreen = if (itemPx > 0) viewport / itemPx else 5
        val target = (selected - perScreen / 2).coerceAtLeast(0)
        if (firstRun) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        firstRun = false
    }
    val labels = remember(photos, fmt) {
        var prev: LocalDate? = null
        photos.map { p ->
            val d = p.dateTime()?.toLocalDate()
            val label = if (d != null && d != prev) fmt.short(d) else ""
            prev = d
            label
        }
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, photo ->
            val isSel = i == selected
            val thumbShape = RoundedCornerShape(10.dp)
            Column(Modifier.width(thumbSize), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = photo.thumbUrl(),
                    contentDescription = photoCaption(photo, fmt),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(thumbSize)
                        .clip(thumbShape)
                        .border(
                            if (isSel) 3.dp else 1.dp,
                            when {
                                isSel -> if (dark) Color.White else OojooTheme.Green
                                dark -> Color.White.copy(alpha = 0.25f)
                                else -> OojooTheme.Line
                            },
                            thumbShape
                        )
                        .background(if (dark) Color.White.copy(alpha = 0.08f) else OojooTheme.Line2)
                        .alpha(if (isSel) 1f else 0.72f)
                        .clickable { onSelect(i) }
                )
                Text(
                    labels[i].ifEmpty { " " },
                    color = when {
                        dark -> Color.White.copy(alpha = if (isSel) 1f else 0.7f)
                        isSel -> OojooTheme.GreenDark
                        else -> OojooTheme.Muted
                    },
                    fontSize = 10.sp,
                    fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/** [전체 보기] — 날짜별로 묶은 3열 그리드 (최신 날짜가 위). 사진을 누르면 전체 화면 뷰어. */
@Composable
private fun AlbumGridDialog(
    photos: List<PlantPhoto>,
    planted: LocalDate?,
    fmt: AlbumFormats,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // (원래 인덱스, 사진)을 날짜별로 묶어 최신 날짜 → 최신 사진 순
    val groups = remember(photos) {
        photos.withIndex()
            .groupBy { it.value.dateTime()?.toLocalDate() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<LocalDate?, List<IndexedValue<PlantPhoto>>>> { it.key ?: LocalDate.MIN })
            .map { it.key to it.value.reversed() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(OojooTheme.Bg)) {
            Row(
                Modifier.fillMaxWidth().background(OojooTheme.Green).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Text(
                    stringResource(R.string.growth_album_all_title),
                    color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.growth_album_count, photos.size),
                    color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                groups.forEach { (day, dayPhotos) ->
                    item(key = "h/${day ?: "none"}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                day?.let { fmt.day(it) } ?: "—",
                                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = OojooTheme.Ink
                            )
                            dayPhotos.firstOrNull()?.let { first ->
                                dayCount(planted, first.value)?.let { n ->
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = OojooTheme.PillShape, color = OojooTheme.GreenBg, border = BorderStroke(1.dp, OojooTheme.GreenDark)) {
                                        Text(
                                            stringResource(R.string.growth_album_dday, n.toInt()),
                                            Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                            color = OojooTheme.GreenDark, fontSize = 10.sp, fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(R.string.growth_album_count, dayPhotos.size),
                                color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    dayPhotos.forEach { (index, photo) ->
                        item(key = photo.id) {
                            AsyncImage(
                                model = photo.thumbUrl(),
                                contentDescription = photoCaption(photo, fmt),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, OojooTheme.Line, RoundedCornerShape(10.dp))
                                    .background(OojooTheme.Line2)
                                    .clickable { onPick(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 전체 화면 사진 뷰어 — 좌우 스와이프, 핀치/두 번 탭 확대, 한 번 탭으로 정보 숨김. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewerDialog(
    photos: List<PlantPhoto>,
    startIndex: Int,
    planted: LocalDate?,
    fmt: AlbumFormats,
    onDismiss: (lastPage: Int) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Dialog(
        onDismissRequest = { onDismiss(pagerState.currentPage) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                key = { photos.getOrNull(it)?.id ?: it },
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(
                    photo = photos[page],
                    description = photoCaption(photos[page], fmt),
                    onZoomChanged = { if (page == pagerState.currentPage) zoomed = it },
                    onTap = { chrome = !chrome }
                )
            }

            val current = photos.getOrNull(pagerState.currentPage)

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onDismiss(pagerState.currentPage) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${pagerState.currentPage + 1} / ${photos.size}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(48.dp))
                }
            }

            AnimatedVisibility(
                visible = chrome && current != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (current != null) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                photoCaption(current, fmt),
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                            )
                            dayCount(planted, current)?.let { n ->
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = OojooTheme.PillShape, color = OojooTheme.Green) {
                                    Text(
                                        stringResource(R.string.growth_album_dday, n.toInt()),
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                        current.location?.takeIf { it.isNotBlank() }?.let {
                            Text("📍 $it", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        ThumbStrip(
                            photos = photos,
                            selected = pagerState.currentPage,
                            fmt = fmt,
                            dark = true,
                            thumbSize = 46.dp,
                            onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i) } }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.growth_album_zoom_hint),
                            color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 확대 가능한 사진. 한 손가락 드래그는 확대 전에는 페이저(좌우 넘김)에 양보하고,
 * 두 손가락이거나 이미 확대된 상태일 때만 직접 처리(확대/이동)한다.
 */
@Composable
private fun ZoomableImage(
    photo: PlantPhoto,
    description: String,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    // pointerInput(Unit) 블록은 한 번만 만들어지므로 콜백은 최신 값을 참조하게 한다
    val zoomCallback by rememberUpdatedState(onZoomChanged)
    val tapCallback by rememberUpdatedState(onTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun set(s: Float, o: Offset) {
        val wasZoomed = scale > 1f
        scale = s
        offset = if (s <= 1f) Offset.Zero else clamp(o, s)
        if (wasZoomed != (s > 1f)) zoomCallback(s > 1f)
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapCallback() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) set(1f, Offset.Zero)
                        else {
                            // 두 번 탭한 지점을 중심으로 2.5배 확대
                            val s = 2.5f
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            set(s, (center - tap) * (s - 1f))
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            set(newScale, offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offset.x; translationY = offset.y
        }
        if (photo.thumb_url != null) {
            AsyncImage(model = photo.thumbUrl(), contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
        }
        AsyncImage(model = photo.fullUrl(), contentDescription = description, contentScale = ContentScale.Fit, modifier = layer)
    }
}
