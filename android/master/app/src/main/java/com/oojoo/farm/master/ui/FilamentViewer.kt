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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
 *   바닥   : 4x4 격자. 식물이 심어진 cell은 밝은 흙색 원형으로 표시.
 *            식물이 없는 행은 로봇이 돌아다닐 수 있는 통로로 비워둠.
 *            식물이 같은 행에 여러 개면 한 칸씩 띄어 배치.
 *            row 0(맨 위/뒤) → 이모지 작게, row 3(맨 아래/앞) → 이모지 크게 (원근감).
 *   로봇   : 빈 통로(행)를 따라 1~2초에 1칸씩 이동하며 관리.
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
    // 시드 = 식물 id 해시로 안정성 확보.
    val plantCells = remember(displayPlants.map { it.id }) {
        val seed = displayPlants.map { it.id }.joinToString("").hashCode().toLong()
        val rnd = Random(seed)
        // 행별로 최대 2개까지만 (한 칸 띄어 배치 가능). 행 순서도 섞음.
        val rowOrder = (0 until GRID).shuffled(rnd)
        val usedColsByRow = mutableMapOf<Int, MutableList<Int>>()
        val allCells = mutableListOf<PlantCell>()
        for (plant in displayPlants) {
            var placed = false
            for (r in rowOrder) {
                val used = usedColsByRow.getOrPut(r) { mutableListOf() }
                // 사용 가능한 col (이미 사용된 col과 인접하지 않게 한 칸 띄어)
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
            if (!placed) {
                // 모든 행이 꽉 찬 경우 임의 위치에 배치
                allCells.add(PlantCell(rnd.nextInt(GRID), rnd.nextInt(GRID)))
            }
        }
        allCells
    }

    // 로봇이 순찰할 빈 행들 (식물이 전혀 없는 행).
    val emptyRows = remember(plantCells) {
        val usedRows = plantCells.map { it.row }.toSet()
        ((0 until GRID) - usedRows.toSet()).toList().ifEmpty { listOf(0) }
    }

    // 로봇이 현재 위치한 빈 행 (시간에 따라 순환).
    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    val sway by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )

    // 로봇이 1~2초에 1칸씩 움직이도록 1.5초 주기로 col 인덱스 변경.
    // 0→1→2→3→2→1→0... (양끝에서 반전).
    val robotColCycle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (GRID - 1).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(1500, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "robotCol"
    )

    // 로봇이 빈 행을 순환 (8초마다 다음 빈 행으로).
    val robotRowCycle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (emptyRows.size - 1).toFloat().coerceAtLeast(0f),
        animationSpec = infiniteRepeatable(
            tween((emptyRows.size * 8000).coerceAtLeast(8000), easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "robotRow"
    )

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

        // 밝은 흙색 (네모 → 원형)
        val soilColor = androidx.compose.ui.graphics.Color(0xFFB5905E)

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
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(groundH.dp)
        ) {
            // 식물이 있는 cell들에 흙 원형 + 식물 이모지
            displayPlants.forEachIndexed { i, plant ->
                val cell = plantCells[i]
                val cellLeft = cell.col * cellW
                val cellTop = cell.row * cellH
                val emoji = plantEmojiFor(plant.species, plant.stage)
                // 원근감: row 0(뒤) = 16sp, row 3(앞) = 34sp
                val fontSize = (16f + (cell.row.toFloat() / (GRID - 1)) * 18f).sp

                // 흙 원형 (cell 중앙, cell 크기의 70%)
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

        // === 로봇 (빈 통로 행을 따라 1~2초에 1칸씩 이동) ===
        val robotRowIdx = robotRowCycle.toInt().coerceIn(0, emptyRows.size - 1)
        val robotRow = emptyRows[robotRowIdx]
        // robotColCycle: 0→3→0 반전. col을 정수 인덱스로 반올림해서 1칸씩 이동 효과.
        val robotCol = robotColCycle.toInt().coerceIn(0, GRID - 1)
        val robotXOffset = (robotCol * cellW + cellW * 0.5f - 20f).dp  // cell 중앙으로
        val robotYOffset = -(groundH - (robotRow * cellH + cellH * 0.5f) + 20f).dp + robotBob.dp
        Text(
            "🤖", fontSize = 36.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = robotXOffset - (maxW / 2f).dp, y = robotYOffset)
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
