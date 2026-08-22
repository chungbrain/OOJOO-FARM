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
 * ROI 설정 화면 — 카메라 프리뷰 위에서 드래그로 사각 영역을 지정하고
 * 그 영역에 식물을 매핑한다. 식물당 하나의 ROI.
 * ROI는 정규화 좌표(0~1)로 저장되어 해상도 변경에도 유지된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoiEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val plants by FarmerEngine.plants.collectAsState()
    val rois = remember { mutableStateListOf<PlantRoi>() }
    var selectedPlantId by remember { mutableStateOf<String?>(null) }
    var dragRect by remember { mutableStateOf<Rect?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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

            // 카메라 + ROI 오버레이
            Card(
                Modifier.fillMaxWidth().height(260.dp).shadow(OojooTheme.ShadowOffset, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CameraPreview(Modifier.fillMaxSize())

                    // 저장된 ROI 표시
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
                    }

                    // 드래그 중인 사각형
                    dragRect?.let { r ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(color = Color.White, topLeft = r.topLeft, size = r.size, style = Stroke(width = 3f))
                        }
                    }

                    // 드래그 제스처 — 식물 선택 후 드래그
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(selectedPlantId) {
                                detectDragGestures(
                                    onDragStart = { off: Offset -> dragRect = Rect(off, off) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragRect = dragRect?.let { normalizeRect(it.topLeft, change.position) }
                                    },
                                    onDragEnd = {
                                        val r = dragRect
                                        val pid = selectedPlantId
                                        val plant = plants.firstOrNull { it.id == pid }
                                        if (r != null && plant != null && r.width > 20f && r.height > 20f) {
                                            val norm = PlantRoi(
                                                plantId = plant.id,
                                                plantName = plant.name,
                                                species = plant.species,
                                                x = (r.left / size.width).coerceIn(0f, 1f),
                                                y = (r.top / size.height).coerceIn(0f, 1f),
                                                w = (r.width / size.width).coerceIn(0f, 1f),
                                                h = (r.height / size.height).coerceIn(0f, 1f)
                                            )
                                            if (norm.isValid()) {
                                                rois.removeAll { it.plantId == plant.id }
                                                rois.add(norm)
                                                saveAll()
                                                message = ctx.getString(R.string.roi_saved, plant.name)
                                            }
                                        }
                                        dragRect = null
                                    }
                                )
                            }
                    )
                }
            }

            message?.let { Text(it, color = OojooTheme.TealDark, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            // 식물 선택 — ROI 지정 대상
            Text(stringResource(R.string.roi_select_plant), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
            if (plants.isEmpty()) {
                Text(stringResource(R.string.roi_no_plants), color = OojooTheme.Muted, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items = plants, key = { plant: Plant -> plant.id }) { plant ->
                        val idx = rois.indexOfFirst { it.plantId == plant.id }
                        val color = if (idx >= 0) RoiColors[idx % RoiColors.size] else OojooTheme.Muted
                        Card(
                            onClick = { selectedPlantId = plant.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = OojooTheme.CardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedPlantId == plant.id) OojooTheme.TealLight else OojooTheme.Card
                            )
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
}
