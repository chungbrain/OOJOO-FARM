package com.oojoo.farm.slave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.slave.R
import com.oojoo.farm.slave.model.Plant
import com.oojoo.farm.slave.service.FarmerEngine
import com.oojoo.farm.slave.vision.CameraPreview
import com.oojoo.farm.slave.vision.PlantRoi
import com.oojoo.farm.slave.vision.RoiStore

private val RoiColors = listOf(
    Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFFF44336), Color(0xFF00BCD4)
)

private fun normalizeRect(a: Offset, b: Offset): Rect = Rect(
    left = minOf(a.x, b.x),
    top = minOf(a.y, b.y),
    right = maxOf(a.x, b.x),
    bottom = maxOf(a.y, b.y)
)

/**
 * ROI 설정 화면 — 카메라 프리뷰 위에서 드래그로 사각 영역을 지정하면
 * "어떤 식물인지" 선택 다이얼로그가 뜨고, 선택한 식물에 영역이 매핑된다.
 * 식물당 하나의 ROI. ROI는 정규화 좌표(0~1)로 저장되어 해상도 변경에도 유지된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoiEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val plants by FarmerEngine.plants.collectAsState()
    val rois = remember { mutableStateListOf<PlantRoi>() }
    var dragRect by remember { mutableStateOf<Rect?>(null) }
    // 드래그 완료된 영역(+뷰 크기) → 식물 선택 대기 중
    var pendingArea by remember { mutableStateOf<Pair<Rect, Pair<Float, Float>>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 프리뷰 카드 실측 크기 (라벨 배치·정규화 기준)
    var cardSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size(1f, 1f)) }

    // 진입 시 저장된 ROI 로드 (식물 목록 로드 후 이름/종 동기화)
    LaunchedEffect(plants) {
        val stored = RoiStore.list(ctx)
        rois.clear()
        rois.addAll(stored.map { s ->
            val p = plants.firstOrNull { it.id == s.plantId }
            if (p != null) s.copy(plantName = p.name, species = p.species) else s
        })
    }

    fun saveAll() {
        RoiStore.clear(ctx)
        rois.forEach { RoiStore.save(ctx, it) }
        FarmerEngine.onRoisChanged()
    }

    fun assignRoi(plant: Plant, rect: Rect, viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val norm = PlantRoi(
            plantId = plant.id,
            plantName = plant.name,
            species = plant.species,
            x = (rect.left / viewWidth).coerceIn(0f, 1f),
            y = (rect.top / viewHeight).coerceIn(0f, 1f),
            w = (rect.width / viewWidth).coerceIn(0f, 1f),
            h = (rect.height / viewHeight).coerceIn(0f, 1f)
        )
        if (norm.isValid()) {
            rois.removeAll { it.plantId == plant.id }
            rois.add(norm)
            saveAll()
            message = ctx.getString(R.string.roi_saved, plant.name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.roi_editor_title), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Teal)
            )
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(stringResource(R.string.roi_editor_guide), color = OojooTheme.Muted, fontSize = 13.sp)

            // 카메라 + ROI 오버레이 — 영역의 식물명 라벨 표시
            Card(
                Modifier.fillMaxWidth().height(300.dp).shadow(OojooTheme.ShadowOffset, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { sz ->
                    cardSize = androidx.compose.ui.geometry.Size(sz.width.toFloat(), sz.height.toFloat())
                }) {
                    CameraPreview(Modifier.fillMaxSize())

                    // 저장된 ROI + 식물명 라벨
                    rois.forEachIndexed { idx, roi ->
                        val color = RoiColors[idx % RoiColors.size]
                        Canvas(Modifier.fillMaxSize()) {
                            val r = Rect(
                                left = roi.x * size.width,
                                top = roi.y * size.height,
                                right = (roi.x + roi.w) * size.width,
                                bottom = (roi.y + roi.h) * size.height
                            )
                            drawRect(color = color, topLeft = r.topLeft, size = r.size, style = Stroke(width = 4f))
                            drawRect(color = color.copy(alpha = 0.15f), topLeft = r.topLeft, size = r.size)
                        }
                        // 식물명 라벨 (영역 좌상단)
                        Text(
                            roi.plantName,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .absoluteOffset(
                                    x = with(androidx.compose.ui.platform.LocalDensity.current) { (roi.x * cardSize.width).toDp() },
                                    y = with(androidx.compose.ui.platform.LocalDensity.current) { (roi.y * cardSize.height).toDp() }
                                )
                                .background(color.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // 드래그 중인 사각형
                    dragRect?.let { r ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(color = Color.White, topLeft = r.topLeft, size = r.size, style = Stroke(width = 3f))
                        }
                    }

                    // 드래그 제스처 — 영역 드래그 후 식물 선택 다이얼로그
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(plants) {
                                detectDragGestures(
                                    onDragStart = { off: Offset -> dragRect = Rect(off, off) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragRect = dragRect?.let { normalizeRect(it.topLeft, change.position) }
                                    },
                                    onDragEnd = {
                                        val r = dragRect
                                        if (r != null && r.width > 20f && r.height > 20f) {
                                            pendingArea = r to (size.width.toFloat() to size.height.toFloat())
                                        }
                                        dragRect = null
                                    }
                                )
                            }
                    )
                }
            }

            message?.let { Text(it, color = OojooTheme.TealDark, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            // 식물 목록 — ROI 매핑 상태 표시
            Text(stringResource(R.string.roi_select_plant), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            if (plants.isEmpty()) {
                Text(stringResource(R.string.roi_no_plants), color = OojooTheme.Muted, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items = plants, key = { plant: Plant -> plant.id }) { plant ->
                        val idx = rois.indexOfFirst { it.plantId == plant.id }
                        val color = if (idx >= 0) RoiColors[idx % RoiColors.size] else OojooTheme.Muted
                        Card(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth(),
                            shape = OojooTheme.CardShape,
                            colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(plant.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                                    val roi = rois.firstOrNull { it.plantId == plant.id }
                                    Text(
                                        if (roi != null) stringResource(
                                            R.string.roi_region_format,
                                            "%.0f".format(roi.x * 100), "%.0f".format(roi.y * 100),
                                            "%.0f".format(roi.w * 100), "%.0f".format(roi.h * 100)
                                        ) else stringResource(R.string.roi_not_set),
                                        color = OojooTheme.Muted, fontSize = 12.sp
                                    )
                                }
                                if (idx >= 0) {
                                    TextButton(onClick = {
                                        rois.removeAll { it.plantId == plant.id }
                                        saveAll()
                                        message = ctx.getString(R.string.roi_removed, plant.name)
                                    }) { Text(stringResource(R.string.roi_delete), color = OojooTheme.Red, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }

            OutlineButton(
                text = stringResource(R.string.roi_close),
                onClick = { nav.navigateUp() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 드래그 완료 → 어떤 식물인지 선택하는 다이얼로그
    val pending = pendingArea
    if (pending != null && plants.isNotEmpty()) {
        val rect = pending.first
        val (viewW, viewH) = pending.second
        AlertDialog(
            onDismissRequest = { pendingArea = null },
            title = { Text(stringResource(R.string.roi_pick_plant_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.roi_pick_plant_desc), fontSize = 13.sp, color = OojooTheme.Muted)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 320.dp)) {
                        items(items = plants, key = { plant: Plant -> plant.id }) { plant ->
                            val hasRoi = rois.any { it.plantId == plant.id }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (hasRoi) OojooTheme.TealLight else OojooTheme.Card,
                                border = androidx.compose.foundation.BorderStroke(1.dp, OojooTheme.Line),
                                onClick = {
                                    assignRoi(plant, rect, viewW, viewH)
                                    pendingArea = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🌱", fontSize = 20.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(plant.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OojooTheme.Ink)
                                        plant.species?.let { Text(it, color = OojooTheme.Muted, fontSize = 11.sp) }
                                    }
                                    if (hasRoi) Text("✓", color = OojooTheme.TealDark, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingArea = null }) { Text(stringResource(R.string.roi_cancel_selection)) }
            }
        )
    }
}
