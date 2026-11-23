package com.oojoo.farm.master.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.ImageLoader
import coil.request.ImageRequest
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 식물별 관심영역(RoI) 편집기 — Farmer가 보낸 최신 성장 사진 위에
 * 정규화 좌표 {x,y,w,h} 박스를 드래그로 지정한다.
 * 저장 시 백엔드에 저장되고, Farmer는 5분 안에 동기화해서 기존
 * ROI 모니터링 루프(크롭 → 3종 CNN: 건강/등급/해충)로 감시한다.
 *
 * 제스처: 박스 안에서 드래그 = 이동 / 모서리 핸들 = 크기 변경 /
 * 밖에서 드래그 = 새 박스. 최소 크기 한 변 2% (slave RoiStore.isValid 규칙).
 */
@Composable
fun RoiEditorScreen(nav: NavController, plantId: String) {
    val S = LocalAppStrings.current
    val ctx = LocalContext.current
    val api = ApiClient.api
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var plant by remember { mutableStateOf<Plant?>(null) }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var roi by remember { mutableStateOf<RoiBox?>(null) }
    val boxState = remember { mutableStateOf(RoiBox(0.25, 0.25, 0.5, 0.5)) }
    var box by boxState
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(plantId) {
        try {
            val p = api.plant(plantId)
            plant = p
            roi = p.roi
            box = p.roi ?: RoiBox(0.25, 0.25, 0.5, 0.5)
            // 사진은 taken_at ASC 정렬 — 마지막이 최신
            val photos = api.plantPhotos(plantId).photos
            photoUrl = photos.lastOrNull()?.let { ApiClient.baseUrl.trimEnd('/') + it.url }
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    // Bitmap은 메인 스레드에서 직접 디코딩할 수 없다 — IO에서 로드.
    LaunchedEffect(photoUrl) {
        val url = photoUrl ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader(ctx)
                val drawable = loader
                    .execute(ImageRequest.Builder(ctx).data(url).build())
                    .drawable
                drawable?.toBitmap()
            }.getOrNull()
        }
    }

    fun finish(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        nav.navigateUp()
    }

    Scaffold(containerColor = Color.Black) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            CartoonAppBar(
                title = plant?.let { "${S.roiEditorTitle} · ${it.name}" } ?: S.roiEditorTitle,
                onBack = { nav.navigateUp() }
            )
            Text(
                S.roiEditorHint,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)
            )

            val img = bmp
            if (loadFailed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(S.roiLoadFailed, color = Color.White, fontSize = 14.sp)
                }
            } else if (photoUrl == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(S.roiEditorNoPhotos, color = Color.White, fontSize = 14.sp)
                }
            } else if (img == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    // 사진을 뷰에 꽉 맞게(fit) 배치 — 정규화 좌표 기준이 되는 화면 영역
                    val maxW = constraints.maxWidth.toFloat()
                    val maxH = constraints.maxHeight.toFloat()
                    val imgAspect = img.width.toFloat() / img.height
                    val viewAspect = maxW / maxH
                    val dispW = if (imgAspect > viewAspect) maxW else maxH * imgAspect
                    val dispH = if (imgAspect > viewAspect) maxW / imgAspect else maxH
                    Box(
                        Modifier
                            .size(dispW.dp, dispH.dp)
                            .align(Alignment.Center)
                            .pointerInput(img) {
                                roiGestures(boxState, density)
                            }
                    ) {
                        Image(
                            bitmap = img.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(Modifier.fillMaxSize()) { drawRoiOverlay(box) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (roi != null) {
                    OutlineButton(
                        text = S.roiClear,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    api.updatePlant(plantId, UpdatePlantRequest(roi = null))
                                    finish(S.roiCleared)
                                } catch (e: Exception) {
                                    busy = false
                                    Toast.makeText(ctx, e.message ?: S.roiLoadFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                api.updatePlant(plantId, UpdatePlantRequest(roi = box))
                                finish(S.roiSaved)
                            } catch (e: Exception) {
                                busy = false
                                Toast.makeText(ctx, e.message ?: S.roiLoadFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !busy && img != null,
                    shape = OojooTheme.BtnShape,
                    modifier = Modifier.weight(if (roi != null) 2f else 3f)
                ) { Text(S.save, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            }
        }
    }
}

/**
 * RoI 드래그 — box(상태)를 직접 돌리는 제스처 처리.
 * 0=이동 1=좌상단 2=우상단 3=좌하단 4=우하단 5=새 박스.
 */
private suspend fun PointerInputScope.roiGestures(
    box: MutableState<RoiBox>,
    density: Density
) {
    val handleTh = with(density) { 34.dp.toPx() }
    var mode = 0
    var startPt = Offset.Zero

    detectDragGestures(
        onDragStart = { start ->
            startPt = start
            val W = size.width.toFloat()
            val H = size.height.toFloat()
            val b = box.value
            val nx = (start.x / W).toFloat()
            val ny = (start.y / H).toFloat()
            val cTL = Offset(b.x.toFloat() * W, b.y.toFloat() * H)
            val cTR = Offset((b.x + b.w).toFloat() * W, b.y.toFloat() * H)
            val cBL = Offset(b.x.toFloat() * W, (b.y + b.h).toFloat() * H)
            val cBR = Offset((b.x + b.w).toFloat() * W, (b.y + b.h).toFloat() * H)
            mode = when {
                dist(start, cTL) < handleTh -> 1
                dist(start, cTR) < handleTh -> 2
                dist(start, cBL) < handleTh -> 3
                dist(start, cBR) < handleTh -> 4
                nx >= b.x && nx <= b.x + b.w && ny >= b.y && ny <= b.y + b.h -> 0
                else -> 5
            }
        },
        onDragEnd = {},
        onDragCancel = { mode = 0 }
    ) { change, delta ->
        change.consume()
        val W = size.width.toFloat()
        val H = size.height.toFloat()
        when (mode) {
            0 -> {
                val b = box.value
                val nx = (b.x.toFloat() + delta.x / W).coerceIn(0f, 1f - b.w.toFloat())
                val ny = (b.y.toFloat() + delta.y / H).coerceIn(0f, 1f - b.h.toFloat())
                box.value = RoiBox(nx.toDouble(), ny.toDouble(), b.w, b.h)
            }
            1, 2, 3, 4 -> {
                val b = box.value
                // 반대편 모서리가 고정된다
                val fx = if (mode == 1 || mode == 3) (b.x + b.w).toFloat() else b.x.toFloat()
                val fy = if (mode == 1 || mode == 2) (b.y + b.h).toFloat() else b.y.toFloat()
                val cx = (change.position.x / W).toFloat().coerceIn(0f, 1f)
                val cy = (change.position.y / H).toFloat().coerceIn(0f, 1f)
                box.value = boxFromOpposite(fx, fy, cx, cy)
            }
            5 -> {
                val sx = (startPt.x / W).toFloat().coerceIn(0f, 1f)
                val sy = (startPt.y / H).toFloat().coerceIn(0f, 1f)
                val cx = (change.position.x / W).toFloat().coerceIn(0f, 1f)
                val cy = (change.position.y / H).toFloat().coerceIn(0f, 1f)
                box.value = boxFromOpposite(sx, sy, cx, cy)
            }
        }
    }
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** 고정 모서리(fx,fy) + 커서(cx,cy)로 박스 구성 — 프레임 안 유지, 한 변 최소 2%. */
private fun boxFromOpposite(fx: Float, fy: Float, cx: Float, cy: Float): RoiBox {
    var left = min(cx, fx)
    var right = max(cx, fx)
    var top = min(cy, fy)
    var bottom = max(cy, fy)
    if (right - left < ROI_MIN) {
        if (right - ROI_MIN >= 0f) left = right - ROI_MIN else right = left + ROI_MIN
    }
    if (bottom - top < ROI_MIN) {
        if (bottom - ROI_MIN >= 0f) top = bottom - ROI_MIN else bottom = top + ROI_MIN
    }
    left = left.coerceIn(0f, 1f)
    top = top.coerceIn(0f, 1f)
    right = right.coerceIn(0f, 1f)
    bottom = bottom.coerceIn(0f, 1f)
    return RoiBox(
        left.toDouble(), top.toDouble(),
        (right - left).toDouble(), (bottom - top).toDouble()
    )
}

/** Drawable → Bitmap. Coil 은 기본적으로 BitmapDrawable 을 돌려준다 —
 *  그 외 Drawable 은 Canvas 로 렌더링해 변환 (안전망). */
private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable) return bitmap
    val b = Bitmap.createBitmap(
        maxOf(1, intrinsicWidth),
        maxOf(1, intrinsicHeight),
        Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(b)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return b
}

private const val ROI_MIN = 0.02f

/** 오버레이 — 박스 밖 어둡게, 초록 테두리, 4개 모서리 핸들. */
private fun DrawScope.drawRoiOverlay(b: RoiBox) {
    val x = b.x.toFloat() * size.width
    val y = b.y.toFloat() * size.height
    val w = b.w.toFloat() * size.width
    val h = b.h.toFloat() * size.height
    val dim = Color.Black.copy(alpha = 0.55f)
    drawRect(dim, Offset.Zero, Size(x, size.height))
    drawRect(dim, Offset(x + w, 0f), Size(size.width - (x + w), size.height))
    drawRect(dim, Offset(x, 0f), Size(w, y))
    drawRect(dim, Offset(x, y + h), Size(w, size.height - (y + h)))
    val green = Color(0xFF00E676)
    drawRect(green, Offset(x, y), Size(w, h), style = Stroke(3.dp.toPx()))
    val hs = 10.dp.toPx()
    listOf(Offset(x, y), Offset(x + w, y), Offset(x, y + h), Offset(x + w, y + h)).forEach { c ->
        drawRect(Color.White, Offset(c.x - hs / 2f, c.y - hs / 2f), Size(hs, hs))
        drawRect(green, Offset(c.x - hs / 2f, c.y - hs / 2f), Size(hs, hs), style = Stroke(2.dp.toPx()))
    }
}
