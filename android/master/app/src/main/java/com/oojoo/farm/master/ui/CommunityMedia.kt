package com.oojoo.farm.master.ui

import android.net.Uri
import android.widget.VideoView
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.MediaKind
import com.oojoo.farm.master.data.MediaProblem
import com.oojoo.farm.master.data.MediaRules
import com.oojoo.farm.master.data.PreparedMedia
import com.oojoo.farm.master.model.CommunityMedia
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 이웃 글 사진·동영상 — 글쓰기 첨부 / 글 상세 갤러리 / 전체 화면 뷰어 / 피드 썸네일 ──────────

private fun absoluteUrl(path: String): String = ApiClient.baseUrl.trimEnd('/') + path

@StringRes
internal fun MediaProblem.messageRes(): Int = when (this) {
    MediaProblem.UNSUPPORTED -> R.string.community_media_unsupported
    MediaProblem.TOO_LARGE -> R.string.community_media_too_large
    MediaProblem.UNREADABLE -> R.string.community_media_unreadable
    MediaProblem.UPLOAD_FAILED -> R.string.community_media_upload_failed
}

/** 글쓰기 화면의 첨부 1건 — 고른 순간부터 업로드 완료까지의 상태. */
@Stable
class DraftMedia(val uri: Uri) {
    /** null = 아직 파일을 읽는 중 */
    var prepared by mutableStateOf<PreparedMedia?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var problem by mutableStateOf<MediaProblem?>(null)
    /** 서버에 올라간 결과 — 재시도할 때 다시 올리지 않는다 */
    var uploaded by mutableStateOf<CommunityMedia?>(null)
    var uploading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)

    val kind: MediaKind? get() = prepared?.kind
}

private val TileShape = RoundedCornerShape(12.dp)

/** 글쓰기 — "사진·동영상" 칸: [추가] 타일 + 고른 항목 (빼기·업로드 진행·문제 표시). */
@Composable
fun DraftMediaSection(
    items: List<DraftMedia>,
    enabled: Boolean,
    @StringRes notice: Int?,
    onAdd: () -> Unit,
    onRemove: (DraftMedia) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.community_media_label, items.size, MediaRules.MAX_ITEMS),
            style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            if (items.size < MediaRules.MAX_ITEMS) {
                item(key = "add") { AddMediaTile(enabled, onAdd) }
            }
            items(items, key = { it.uri.toString() }) { d -> DraftMediaTile(d, enabled, onRemove) }
        }
        val problem = items.firstNotNullOfOrNull { it.problem }
        when {
            notice != null -> Text(
                if (notice == R.string.community_media_limit) stringResource(notice, MediaRules.MAX_ITEMS) else stringResource(notice),
                color = OojooTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            problem != null -> Text("⚠️ ${stringResource(problem.messageRes())}", color = OojooTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else -> Text(stringResource(R.string.community_media_hint, MediaRules.MAX_ITEMS), color = OojooTheme.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddMediaTile(enabled: Boolean, onAdd: () -> Unit) {
    Column(
        Modifier.size(84.dp)
            .clip(TileShape)
            .border(2.dp, if (enabled) OojooTheme.Ink else OojooTheme.Line, TileShape)
            .background(OojooTheme.GreenBg)
            .clickable(enabled = enabled, onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = OojooTheme.GreenDark, modifier = Modifier.size(28.dp))
        Text(stringResource(R.string.community_media_add), color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DraftMediaTile(d: DraftMedia, enabled: Boolean, onRemove: (DraftMedia) -> Unit) {
    val p = d.prepared
    Box(
        Modifier.size(84.dp)
            .clip(TileShape)
            .border(2.dp, if (d.problem != null) OojooTheme.Red else OojooTheme.Ink, TileShape)
            .background(OojooTheme.Ink)
    ) {
        val thumb = d.thumb
        when {
            thumb != null -> Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            p == null -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp).align(Alignment.Center))
            else -> Text(if (p.kind == MediaKind.VIDEO) "🎬" else "📷", fontSize = 26.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (p?.kind == MediaKind.VIDEO) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), OojooTheme.PillShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.community_media_video), tint = Color.White, modifier = Modifier.size(12.dp))
                p.durationMs?.let { Text(MediaRules.formatDuration(it), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        when {
            d.uploading -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { d.progress }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            }
            d.problem != null -> Box(Modifier.fillMaxSize().background(OojooTheme.Red.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("⚠️", fontSize = 22.sp)
            }
            d.uploaded != null -> Icon(
                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).background(OojooTheme.Green, CircleShape).padding(2.dp)
            )
        }
        if (enabled) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .clickable { onRemove(d) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.community_media_remove), tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** 글 상세 — 첨부 사진·동영상 넘겨 보기 + 썸네일 줄, 탭하면 전체 화면. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostMediaGallery(media: List<CommunityMedia>) {
    if (media.isEmpty()) return
    val pager = rememberPagerState { media.size }
    val scope = rememberCoroutineScope()
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    val openLabel = stringResource(R.string.community_media_open)

    Card(
        Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black)) {
            HorizontalPager(state = pager, key = { media[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                val m = media[page]
                Box(
                    Modifier.fillMaxSize().clickable(onClickLabel = openLabel) { viewerStart = page },
                    contentAlignment = Alignment.Center
                ) {
                    MediaStill(m, ContentScale.Fit, full = true)
                    if (m.isVideo) PlayBadge(m.duration_ms)
                }
            }
            if (media.size > 1) {
                Text(
                    stringResource(R.string.community_media_counter, pager.currentPage + 1, media.size),
                    Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), OojooTheme.PillShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        if (media.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(media, key = { _, m -> m.id }) { i, m ->
                    val selected = i == pager.currentPage
                    Box(
                        Modifier.size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(if (selected) 3.dp else 1.dp, if (selected) OojooTheme.Green else OojooTheme.Line, RoundedCornerShape(8.dp))
                            .background(OojooTheme.Ink)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center
                    ) {
                        MediaStill(m, ContentScale.Crop, full = false)
                        if (m.isVideo) Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.community_media_summary, media.count { !it.isVideo }, media.count { it.isVideo }),
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }

    viewerStart?.let { start ->
        PostMediaViewer(media, start) { last ->
            viewerStart = null
            scope.launch { pager.scrollToPage(last) }
        }
    }
}

/** 사진은 썸네일 위에 원본(full=true일 때), 동영상은 포스터(없으면 🎬). */
@Composable
private fun MediaStill(m: CommunityMedia, scale: ContentScale, full: Boolean) {
    val thumb = m.thumb_url?.let(::absoluteUrl)
    if (thumb != null) {
        AsyncImage(model = thumb, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
    }
    if (!m.isVideo && (full || thumb == null)) {
        AsyncImage(model = absoluteUrl(m.url), contentDescription = stringResource(R.string.community_media_photo), contentScale = scale, modifier = Modifier.fillMaxSize())
    }
    if (m.isVideo && thumb == null) Text("🎬", fontSize = 30.sp)
}

@Composable
private fun PlayBadge(durationMs: Long?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.community_media_play), tint = Color.White, modifier = Modifier.size(40.dp))
        }
        durationMs?.let {
            Text(
                MediaRules.formatDuration(it),
                Modifier.background(Color.Black.copy(alpha = 0.55f), OojooTheme.PillShape).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 전체 화면 — 좌우로 넘기고, 사진은 확대, 동영상은 재생/탐색. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostMediaViewer(media: List<CommunityMedia>, start: Int, onDismiss: (lastPage: Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, media.lastIndex)) { media.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    Dialog(onDismissRequest = { onDismiss(pager.currentPage) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { media[it].id },
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val m = media[page]
                if (m.isVideo) {
                    VideoPage(m, active = page == pager.currentPage)
                } else {
                    ZoomableImage(
                        fullUrl = absoluteUrl(m.url),
                        thumbUrl = m.thumb_url?.let(::absoluteUrl),
                        description = stringResource(R.string.community_media_photo),
                        onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                        onTap = {}
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onDismiss(pager.currentPage) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (media.size > 1) {
                    Text(
                        stringResource(R.string.community_media_counter, pager.currentPage + 1, media.size),
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

private class PlayerRef(var view: VideoView? = null)

/** 동영상 한 쪽 — 현재 페이지가 되면 처음부터 재생, 벗어나면 멈춤. 탭으로 재생/일시정지, 막대로 탐색. */
@Composable
private fun VideoPage(m: CommunityMedia, active: Boolean) {
    val url = absoluteUrl(m.url)
    val activeNow by rememberUpdatedState(active)
    // 화면 갱신과 무관한 참조라 상태로 두지 않는다
    val player = remember { PlayerRef() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(m.duration_ms ?: 0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    fun play() { player.view?.start(); playing = true }
    fun pause() { player.view?.pause(); playing = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setOnPreparedListener { mp ->
                        prepared = true
                        if (mp.duration > 0) durationMs = mp.duration.toLong()
                        if (activeNow) { start(); playing = true }
                    }
                    setOnCompletionListener { playing = false; positionMs = durationMs }
                    // true — 시스템 "재생할 수 없음" 대화상자 대신 화면 안에 안내
                    setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
                    setVideoPath(url)
                    player.view = this
                }
            },
            // wrapContentSize: VideoView 가 영상 비율대로 크기를 정하고 가운데 놓인다
            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
        )
        // 첫 재생 전에는 포스터 (SurfaceView 는 시작 전 검은 화면)
        if (!failed && (!prepared || (!playing && positionMs == 0L))) {
            m.thumb_url?.let {
                AsyncImage(model = absoluteUrl(it), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (!prepared && !failed) CircularProgressIndicator(color = Color.White)
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = prepared && !failed,
                onClickLabel = stringResource(if (playing) R.string.community_media_pause else R.string.community_media_play)
            ) { if (playing) pause() else play() }
        )
        if (prepared && !playing && !failed) {
            Box(Modifier.size(76.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
        if (failed) {
            Text(
                "⚠️ ${stringResource(R.string.community_media_video_error)}",
                Modifier.padding(24.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
        }
        if (prepared && !failed && durationMs > 0) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp)
            ) {
                val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                Slider(
                    value = seeking ?: fraction,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { f ->
                            val to = (f * durationMs).toLong()
                            player.view?.seekTo(to.toInt())
                            positionMs = to
                        }
                        seeking = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = OojooTheme.Green,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row {
                    val shown = seeking?.let { (it * durationMs).toLong() } ?: positionMs
                    Text(MediaRules.formatDuration(shown), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(MediaRules.formatDuration(durationMs), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 넘겨서 현재 페이지가 되면 처음부터 재생, 벗어나면 일시정지
    LaunchedEffect(active, prepared) {
        if (!active && playing) pause()
        else if (active && prepared && !failed && !playing && positionMs == 0L) play()
    }
    LaunchedEffect(playing) {
        while (playing) {
            player.view?.let { if (seeking == null) positionMs = it.currentPosition.toLong() }
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { player.view?.stopPlayback() } }
}

/** 피드 카드 오른쪽 — 첫 첨부 썸네일 (+나머지 개수, 동영상이면 ▶). */
@Composable
fun FeedMediaThumb(media: List<CommunityMedia>, modifier: Modifier = Modifier) {
    val first = media.firstOrNull() ?: return
    Box(
        modifier.size(72.dp).clip(TileShape).border(2.dp, OojooTheme.Ink, TileShape).background(OojooTheme.Ink),
        contentAlignment = Alignment.Center
    ) {
        val model = first.thumb_url ?: first.url.takeUnless { first.isVideo }
        if (model != null) {
            AsyncImage(model = absoluteUrl(model), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("🎬", fontSize = 26.sp)
        }
        if (first.isVideo) {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.community_media_video), tint = Color.White,
                modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
            )
        }
        if (media.size > 1) {
            Text(
                stringResource(R.string.community_media_more, media.size - 1),
                Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(topStart = 8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}
