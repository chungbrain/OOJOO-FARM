package com.oojoo.farm.master.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.oojoo.farm.master.model.Plant
import kotlinx.coroutines.delay
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

/**
 * 2D emoji farm scene.
 *
 * 레이아웃:
 *   하늘   : 천체/구름 (상단 40%)
 *   바닥   : 4x4 격자. 식물이 있는 cell은 밝은 흙색 원형 + 식물 이모지.
 *   로봇   : 식물들을 하나씩 방문하며 관리. 각 식물 옆으로 이동 → 1.5초 관류 → 다음 식물로.
 *            Animatable 기반 부드러운 이동 + 도착 시 살짝 점프.
 *   잔디   : 최하단 풀잎들.
 */
@Composable
fun FarmSceneView(
    modifier: Modifier = Modifier,
    plants: List<Plant> = emptyList(),
    isNight: Boolean = false,
    isRain: Boolean = false
) {
    val displayPlants = plants.ifEmpty { defaultDemoPlants() }

    // 식물을 4x4 격자에 배치 — 같은 행에 여러 식물이면 한 칸씩 띄어 배치.
    val plantCells = remember(displayPlants.map { it.id }) {
        val seed = displayPlants.map { it.id }.joinToString("").hashCode().toLong()
        val rnd = Random(seed)
        val rowOrder = (0 until GRID).shuffled(rnd)
        val usedColsByRow = mutableMapOf<Int, MutableList<Int>>()
        val allCells = mutableListOf<PlantCell>()
        for (plant in displayPlants) {
            var placed = false
            for (r in rowOrder) {
                val used = usedColsByRow.getOrPut(r) { mutableListOf() }
                val candidates = (0 until GRID).filter { c ->
                    used.none { it == c || it == c - 1 || it == c + 1 }
                }
                if (candidates.isNotEmpty()) {
                    val c = candidates.random(rnd)
                    used.add(c)
                    allCells.add(PlantCell(r, c))
                    placed = true
                    break
                }
            }
            if (!placed) allCells.add(PlantCell(rnd.nextInt(GRID), rnd.nextInt(GRID)))
        }
        allCells
    }

    // 로봇 웨이포인트: 각 식물 바로 옆 (같은 행, col±1 중 유효한 칸).
    // 식물에 다가가서 관리하는 모습을 위해 로봇 위치 = 식물 옆 칸.
    val waypoints = remember(plantCells) {
        plantCells.map { cell ->
            // 식물 옆 칸 (왼쪽 우선, 경계 밖이면 오른쪽)
            val adjacentCol = if (cell.col > 0) (cell.col - 1).toFloat()
                              else (cell.col + 1).toFloat()
            Waypoint(cell.row, adjacentCol)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    val sway by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )

    // 로봇 부드러운 이동을 위한 Animatable (x, y)
    val robotX = remember { Animatable(waypoints.firstOrNull()?.col ?: 0f) }
    val robotY = remember { Animatable((waypoints.firstOrNull()?.row ?: 0).toFloat()) }
    // 로봇 관리 액션 펄스 (도착 시 살짝 커짐)
    val robotScale = remember { Animatable(1f) }

    // 로봇 순찰 루프: 웨이포인트를 순회하며 이동 + 관리
    LaunchedEffect(waypoints) {
        if (waypoints.isEmpty()) return@LaunchedEffect
        var idx = 0
        while (true) {
            val wp = waypoints[idx]
            // 식물 옆으로 이동 (1.2초 부드러운 이동)
            robotX.animateTo(wp.col, animationSpec = tween(1200, easing = LinearEasing))
            robotY.animateTo(wp.row.toFloat(), animationSpec = tween(1200, easing = LinearEasing))
            // 도착 — 관리 액션 (살짝 커짐 + 1.5초 대기)
            robotScale.animateTo(1.2f, animationSpec = tween(300, easing = LinearEasing))
            delay(1500)
            robotScale.animateTo(1f, animationSpec = tween(300, easing = LinearEasing))
            // 다음 식물로
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
                val cellLeft = cell.col * cellW
                val cellTop = cell.row * cellH
                val emoji = plantEmojiFor(plant.species, plant.stage)
                val fontSize = (16f + (cell.row.toFloat() / (GRID - 1)) * 18f).sp

                val soilSize = (minOf(cellW, cellH) * 0.7f).dp
                Box(
                    Modifier
                        .offset(x = (cellLeft + (cellW - soilSize.value) / 2f).dp,
                                y = (cellTop + (cellH - soilSize.value) / 2f).dp)
                        .size(soilSize)
                        .clip(CircleShape)
                        .background(soilColor.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        emoji,
                        fontSize = fontSize,
                        modifier = Modifier.graphicsLayer { rotationZ = sway + (i % 2) * 1.5f }
                    )
                }
            }
        }

        // === 로봇 (식물들을 순회하며 관리) ===
        // robotX/robotY는 cell 좌표 (col, row). 픽셀로 변환.
        val robotPxX = robotX.value * cellW + cellW * 0.5f
        val robotPxY = robotY.value * cellH + cellH * 0.5f
        // 로봇을 바닥 Box 안에 배치 (바닥 상단에서부터 robotPxY 위치)
        Text(
            "🤖", fontSize = 32.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationX = robotPxX - maxW / 2f
                    translationY = -(groundH - robotPxY) + robotBob
                    scaleX = robotScale.value
                    scaleY = robotScale.value
                }
        )

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

private fun defaultDemoPlants(): List<Plant> = listOf(
    Plant(id = "demo1", name = "베이비그린", species = "상추", stage = "vegetative"),
    Plant(id = "demo2", name = "방울토마토", species = "방울토마토", stage = "fruiting"),
    Plant(id = "demo3", name = "바질", species = "바질", stage = "vegetative"),
    Plant(id = "demo4", name = "딸기", species = "딸기", stage = "flowering")
)
