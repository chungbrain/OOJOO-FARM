package com.oojoo.farm.master.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.oojoo.farm.master.model.Plant
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

/**
 * 2D emoji farm scene.
 *
 * 레이아웃 (하단 60%가 초록 바닥):
 *   하늘   : 천체/구름 (상단 40%)
 *   바닥   : 4x4 격자. 식물이 심어진 cell은 흙(갈색)으로 표시, 빈 cell은 초록.
 *            row 0(맨 위/뒤) → 이모지 작게, row 3(맨 아래/앞) → 이모지 크게 (원근감).
 *   로봇   : 격자 아래 통로에서 좌우로 자율 왕복 (식물과 y축 분리로 겹치지 않음).
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

    // 4x4 격자의 16개 cell 중 식물 개수만큼 무작위 선택 (시드 = 식물 id 해시).
    val plantCells = remember(displayPlants.map { it.id }) {
        val seed = displayPlants.map { it.id }.joinToString("").hashCode().toLong()
        val rnd = Random(seed)
        val allCells = (0 until GRID).flatMap { r -> (0 until GRID).map { c -> PlantCell(r, c) } }
        val shuffled = allCells.shuffled(rnd)
        displayPlants.mapIndexed { i, _ -> shuffled[i % shuffled.size] }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    val sway by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )

    val robotX by infiniteTransition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse),
        label = "robotX"
    )

    val robotBob by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
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

        // 흙 색상
        val soilColor = androidx.compose.ui.graphics.Color(0xFF6D4C2E)

        // === 하늘 (상단 40%) ===
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
        // 바닥 전체 Box (초록 바닥 위에 흙 cell들을 그림)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(groundH.dp)
        ) {
            // 식물이 있는 cell들에 흙 배경 + 식물 이모지
            displayPlants.forEachIndexed { i, plant ->
                val cell = plantCells[i]
                val cellLeft = cell.col * cellW
                // row 0이 맨 위(뒤), row 3이 맨 아래(앞). 바닥 Box 기준 위에서부터 배치.
                val cellTop = cell.row * cellH
                val emoji = plantEmojiFor(plant.species, plant.stage)
                // 원근감: row 0(뒤) = 18sp, row 3(앞) = 38sp
                val fontSize = (18f + (cell.row.toFloat() / (GRID - 1)) * 20f).sp

                // 흙 cell
                Box(
                    Modifier
                        .offset(x = cellLeft.dp, y = cellTop.dp)
                        .height(cellH.dp)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(soilColor.copy(alpha = 0.85f)),
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

        // === 로봇 (격자 아래 통로, 식물과 y축 분리) ===
        val robotXOffset = (robotX * maxW * 0.3f).dp
        val robotYOffset = -(groundH * 0.04f).dp + robotBob.dp
        Text(
            "🤖", fontSize = 40.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = robotXOffset, y = robotYOffset)
        )

        // === 잔디 (최하단) ===
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
