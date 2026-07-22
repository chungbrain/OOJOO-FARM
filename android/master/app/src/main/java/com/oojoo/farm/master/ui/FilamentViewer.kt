package com.oojoo.farm.master.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 작물 종류(species)와 생장 단계(stage)를 함께 고려한 이모지.
 */
fun plantEmojiFor(species: String?, stage: String?): String {
    val sp = species?.trim()?.lowercase()?.replace(" ", "")
    if (!sp.isNullOrEmpty()) {
        val fruitEmoji = when (sp) {
            "토마토", "방울토마토", "tomato", "cherrytomato" -> "🍅"
            "딸기", "strawberry" -> "🍓"
            "고추", "chili", "pepper" -> "🌶️"
            "호박", "애호박", "pumpkin", "squash" -> "🎃"
            "오이", "cucumber" -> "🥒"
            "가지", "eggplant" -> "🍆"
            "옥수수", "corn", "maize" -> "🌽"
            "포도", "grape" -> "🍇"
            "사과", "apple" -> "🍎"
            "레몬", "lemon" -> "🍋"
            "귤", "오렌지", "orange", "mandarin" -> "🍊"
            else -> null
        }
        if (fruitEmoji != null) return fruitEmoji
        return when (sp) {
            "상추", "lettuce" -> "🥬"
            "깻잎", "perilla" -> "🍃"
            "바질", "basil" -> "🌿"
            "로즈마리", "rosemary" -> "🌿"
            "박하", "mint", "peppermint" -> "🌿"
            "대파", "파", "greenonion" -> "🧅"
            "양파", "onion" -> "🧅"
            "마늘", "garlic" -> "🧄"
            "감자", "potato" -> "🥔"
            "고구마", "sweetpotato" -> "🍠"
            "당근", "carrot" -> "🥕"
            "무", "radish", "daikon" -> "🥬"
            "배추", "cabbage" -> "🥬"
            "브로콜리", "broccoli" -> "🥦"
            else -> stageEmoji(stage)
        }
    }
    return stageEmoji(stage)
}

private fun stageEmoji(stage: String?): String = when (stage) {
    "fruiting" -> "🍅"
    "flowering" -> "🌸"
    "vegetative" -> "🌿"
    else -> "🌱"
}

private const val GRID = 4

/** 식물 하나의 4x4 격자 위치 (row, col). row 0=맨 위(뒤), row 3=맨 아래(앞). */
private data class PlantCell(val row: Int, val col: Int)

/** 로봇이 순찰할 웨이포인트 — 식물 cell 옆 (같은 행, 옆 칸). */
private data class Waypoint(val row: Int, val col: Float)

/** 격자 중심으로부터의 거리 (유클리드). */
private fun distance(row: Int, col: Int, centerRow: Float, centerCol: Float): Float {
    val dr = row - centerRow
    val dc = col - centerCol
    return kotlin.math.sqrt(dr * dr + dc * dc)
}

/**
 * 2D emoji farm scene.
 *
 * 레이아웃:
 *   하늘   : 천체/구름 (상단 40%)
 *   바닥   : 4x4 격자. 식물이 있는 cell은 밝은 흙색 원형 + 식물 이모지.
 *            식물을 꾹 누르면 드래그해서 위치를 옮길 수 있다.
 *   로봇   : 식물들을 하나씩 방문하며 관리. 5초 이동 → 2초 관류.
 *   잔디   : 최하단 풀잎들.
 */
@Composable
fun FarmSceneView(
    modifier: Modifier = Modifier,
    plants: List<Plant> = emptyList(),
    slaves: List<Slave> = emptyList(),
    isNight: Boolean = false,
    isRain: Boolean = false
) {
    val displayPlants = plants.ifEmpty { defaultDemoPlants() }

    // Farmer가 배정된 식물만 로봇 순찰 대상
    val assignedPlants = displayPlants.filter { p -> slaves.any { it.id == p.slave_id } }

    // 식물 위치 — 드래그로 변경 가능하도록 mutableStateOf로 관리.
    // 초기 배치: 격자 중심(1.5, 1.5)에서 가까운 cell부터 랜덤 배정.
    var plantCells by remember(displayPlants.map { it.id }) {
        mutableStateOf(initialPlantCells(displayPlants))
    }

    // 드래그 중인 식물 인덱스 (-1 = 드래그 아님)
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // 로봇 웨이포인트: Farmer가 배정된 식물만 순찰 대상.
    // plantCells는 displayPlants 전체에 대한 것이므로, assignedPlants의 인덱스로 매핑.
    val assignedIndices = remember(displayPlants, slaves) {
        displayPlants.mapIndexedNotNull { i, p ->
            if (slaves.any { it.id == p.slave_id }) i else null
        }
    }
    val waypoints = remember(plantCells, assignedIndices) {
        assignedIndices.map { i ->
            val cell = plantCells[i]
            val sideOffset = 0.32f
            val col = if (cell.col > 0) (cell.col - sideOffset) else (cell.col + sideOffset)
            Waypoint(cell.row, col)
        }
    }
    val showRobot = assignedIndices.isNotEmpty()

    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    val sway by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )

    // 로봇 부드러운 이동을 위한 Animatable (x, y)
    val robotX = remember { Animatable(waypoints.firstOrNull()?.col ?: 0f) }
    val robotY = remember { Animatable((waypoints.firstOrNull()?.row ?: 0).toFloat()) }
    val robotScale = remember { Animatable(1f) }
    val tendingAlpha = remember { Animatable(0f) }

    // 로봇 순찰 루프: 할당된 식물이 있을 때만 동작.
    // 이산적 이동 — 한 칸씩 snap 이동 (1.5초 이동 + 2초 관리).
    LaunchedEffect(waypoints, showRobot) {
        if (!showRobot || waypoints.isEmpty()) return@LaunchedEffect
        var idx = 0
        while (true) {
            val wp = waypoints[idx]
            // 한 칸씩 이산적으로 이동 (0.4초 snap) + 0.5초 휴식 + 2초 관리
            robotX.animateTo(wp.col, animationSpec = tween(400, easing = FastOutSlowInEasing))
            robotY.animateTo(wp.row.toFloat(), animationSpec = tween(400, easing = FastOutSlowInEasing))
            // 도착 — 관리 액션 (살짝 커짐 + 💧 표시 + 2초 관류)
            robotScale.animateTo(1.25f, animationSpec = tween(300, easing = FastOutSlowInEasing))
            tendingAlpha.animateTo(1f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            delay(2000)
            tendingAlpha.animateTo(0f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            robotScale.animateTo(1f, animationSpec = tween(300, easing = FastOutSlowInEasing))
            // 0.5초 휴식 후 다음 식물로
            delay(500)
            idx = (idx + 1) % waypoints.size
        }
    }

    val robotBob by infiniteTransition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "robotBob"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val cloudDrift by infiniteTransition.animateFloat(
        initialValue = -14f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Reverse),
        label = "cloudDrift"
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value
        val groundH = maxH * 0.6f
        val cellW = maxW / GRID
        val cellH = groundH / GRID

        val soilColor = androidx.compose.ui.graphics.Color(0xFFB5905E)

        // === 하늘 ===
        Text(
            if (isNight) "🌙" else "☀️",
            fontSize = 36.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 16.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .alpha(0.95f)
        )
        if (!isNight || isRain) {
            Text(
                "☁️", fontSize = 28.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 18.dp)
                    .offset(x = cloudDrift.dp)
                    .alpha(0.7f)
            )
            Text(
                "☁️", fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 42.dp, start = 120.dp)
                    .offset(x = (cloudDrift * 0.6f).dp)
                    .alpha(0.5f)
            )
        }

        // === 4x4 격자 바닥 ===
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(groundH.dp)
        ) {
            displayPlants.forEachIndexed { i, plant ->
                val cell = plantCells[i]
                val emoji = plantEmojiFor(plant.species, plant.stage)
                val fontSize = (16f + (cell.row.toFloat() / (GRID - 1)) * 18f).sp
                val soilSize = (minOf(cellW, cellH) * 0.7f).dp
                val cellLeft = cell.col * cellW
                val cellTop = cell.row * cellH

                val isDragging = (draggingIndex == i)

                // 드래그 중이면 픽셀 오프셋 사용, 아니면 cell 기준
                val boxX = if (isDragging) dragOffsetX else (cellLeft + (cellW - soilSize.value) / 2f)
                val boxY = if (isDragging) dragOffsetY else (cellTop + (cellH - soilSize.value) / 2f)

                Box(
                    Modifier
                        .offset(x = boxX.dp, y = boxY.dp)
                        .size(soilSize)
                        .clip(CircleShape)
                        .background(soilColor.copy(alpha = if (isDragging) 0.7f else 0.9f))
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.15f else 1f
                            scaleY = if (isDragging) 1.15f else 1f
                        }
                        .pointerInput(i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = i
                                    dragOffsetX = cellLeft + (cellW - soilSize.value) / 2f
                                    dragOffsetY = cellTop + (cellH - soilSize.value) / 2f
                                },
                                onDragEnd = {
                                    val newCol = ((dragOffsetX + soilSize.value / 2f) / cellW)
                                        .roundToInt().coerceIn(0, GRID - 1)
                                    val newRow = ((dragOffsetY + soilSize.value / 2f) / cellH)
                                        .roundToInt().coerceIn(0, GRID - 1)
                                    plantCells = plantCells.toMutableList().also {
                                        it[i] = PlantCell(newRow, newCol)
                                    }
                                    draggingIndex = -1
                                },
                                onDragCancel = { draggingIndex = -1 },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetX = (dragOffsetX + dragAmount.x)
                                        .coerceIn(-soilSize.value / 2f, maxW - soilSize.value / 2f)
                                    dragOffsetY = (dragOffsetY + dragAmount.y)
                                        .coerceIn(-soilSize.value / 2f, groundH - soilSize.value / 2f)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        emoji,
                        fontSize = fontSize,
                        modifier = Modifier
                            .alpha(if (isDragging) 0.8f else 1f)
                            .graphicsLayer { rotationZ = sway + (i % 2) * 1.5f }
                    )
                }

                // 이름표 — 흙 원 밖 아래에 팻말처럼 표시 (직사각형, 작은 줄기 연결)
                val nameTagX = boxX + soilSize.value / 2f
                val nameTagY = boxY + soilSize.value  // 원 하단
                Box(
                    Modifier
                        .offset(x = (nameTagX - 30f).dp, y = nameTagY.dp)
                        .graphicsLayer { alpha = if (isDragging) 0.6f else 1f }
                ) {
                    // 작은 줄기 (원과 팻말 연결)
                    Box(
                        Modifier
                            .offset(x = 28.dp, y = (-4).dp)
                            .size(width = 2.dp, height = 8.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFF8B4513))
                    )
                    // 팻말 (직사각형)
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF8B4513))
                    ) {
                        Text(
                            plant.name,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFF2D3436),
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // === 로봇 (할당된 식물이 있을 때만 표시) ===
            if (showRobot) {
                val robotPxX = robotX.value * cellW + cellW * 0.5f
                val robotPxY = robotY.value * cellH + cellH * 0.5f
                val robotBoxSize = 40f
                Box(
                    Modifier
                        .offset(
                            x = (robotPxX - robotBoxSize / 2f).dp,
                            y = (robotPxY - robotBoxSize / 2f + robotBob).dp
                        )
                        .graphicsLayer {
                            scaleX = robotScale.value
                            scaleY = robotScale.value
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (tendingAlpha.value > 0.01f) {
                        Text(
                            "💧",
                            fontSize = 18.sp,
                            modifier = Modifier
                                .offset(y = (-22).dp)
                                .alpha(tendingAlpha.value)
                                .graphicsLayer { rotationZ = sway * 0.3f }
                        )
                    }
                    Text("🤖", fontSize = 34.sp)
                }
            }
        }

        // === 잔디 ===
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(6) { i ->
                Text("🌱", fontSize = (12 + (i % 3) * 4).sp,
                    modifier = Modifier.graphicsLayer { rotationZ = sway * 0.5f })
            }
        }

        // 비 오버레이
        if (isRain) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(5) { Text("💧", fontSize = 16.sp, modifier = Modifier.alpha(0.6f)) }
            }
        }
    }
}

/** 초기 식물 배치 — 격자 중심에서 가까운 cell부터 랜덤 배정. */
private fun initialPlantCells(plants: List<Plant>): List<PlantCell> {
    val seed = plants.map { it.id }.joinToString("").hashCode().toLong()
    val rnd = Random(seed)
    val centerRow = (GRID - 1) / 2f
    val centerCol = (GRID - 1) / 2f
    val allCellsByDistance = (0 until GRID).flatMap { r ->
        (0 until GRID).map { c -> PlantCell(r, c) to distance(r, c, centerRow, centerCol) }
    }.sortedWith(compareBy({ it.second }, { rnd.nextFloat() })).map { it.first }

    val usedColsByRow = mutableMapOf<Int, MutableList<Int>>()
    val result = mutableListOf<PlantCell>()
    for (cell in allCellsByDistance) {
        if (result.size >= plants.size) break
        val used = usedColsByRow.getOrPut(cell.row) { mutableListOf() }
        if (used.none { it == cell.col || it == cell.col - 1 || it == cell.col + 1 }) {
            used.add(cell.col)
            result.add(cell)
        }
    }
    if (result.size < plants.size) {
        for (cell in allCellsByDistance) {
            if (result.size >= plants.size) break
            if (cell !in result) result.add(cell)
        }
    }
    return result
}

private fun defaultDemoPlants(): List<Plant> = listOf(
    Plant(id = "demo1", name = "베이비그린", species = "상추", stage = "vegetative"),
    Plant(id = "demo2", name = "방울토마토", species = "방울토마토", stage = "fruiting"),
    Plant(id = "demo3", name = "바질", species = "바질", stage = "vegetative"),
    Plant(id = "demo4", name = "딸기", species = "딸기", stage = "flowering")
)
