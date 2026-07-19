package com.oojoo.farm.master.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.oojoo.farm.master.model.Plant
import kotlin.random.Random

/**
 * 작물 종류(species)와 생장 단계(stage)를 함께 고려한 이모지.
 * species가 알려진 작물이면 해당 작물 이모지를, 아니면 stage 기반 기본 이모지.
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

/** 식물 하나의 무작위 배치 (0~1 범위). depth: 0=뒤(위/작음), 1=앞(아래/큼). */
private data class PlantLayout(val xPct: Float, val depthPct: Float)

/**
 * 2D emoji farm scene.
 *
 * 레이아웃 (BoxWithConstraints 기반, 하단 60%가 초록 바닥):
 *   하늘   : 천체/구름 (상단 40%)
 *   바닥   : 초록 영역 (하단 60%) — 여기에 식물과 로봇이 모두 배치됨
 *   식물   : 바닥 영역 안에서 무작위 x/depth 배치. depth가 클수록(아래일수록) 크고 진함.
 *   로봇   : 식물보다 더 앞(아래)의 통로에서 좌우로 자율 왕복.
 *            y축이 식물과 분리되어 있어 이모지가 절대 겹치지 않음.
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

    // 식물 목록(= id 리스트)이 바뀔 때만 무작위 배치를 다시 계산.
    // 같은 식물 목록이면 항상 같은 위치에 그려진다 (재진입/리컴포지션 안정성).
    val plantLayouts = remember(displayPlants.map { it.id }) {
        val seed = displayPlants.map { it.id }.joinToString("").hashCode().toLong()
        val rnd = Random(seed)
        displayPlants.map {
            PlantLayout(
                xPct = 0.08f + rnd.nextFloat() * 0.84f,     // 0.08 ~ 0.92
                depthPct = rnd.nextFloat() * 0.7f            // 0 ~ 0.7 (로봇은 0.85)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    // 식물 미세 흔들림 (좌우 회전, 제자리).
    val sway by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sway"
    )

    // 로봇 자율 순찰 — 좌우로 넓게 왕복.
    val robotX by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "robotX"
    )

    // 로봇 살짝 상하 떠움직임.
    val robotBob by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "robotBob"
    )

    // 천체 펄스.
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // 구름 떠다님.
    val cloudDrift by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloudDrift"
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        // 초록 바닥 영역 높이 (HomeScreen FarmWeatherCard의 fillMaxHeight(0.6f)와 일치).
        val groundH = maxH * 0.6f

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
                "☁️",
                fontSize = 28.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 18.dp)
                    .offset(x = cloudDrift.dp)
                    .alpha(0.7f)
            )
            Text(
                "☁️",
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 42.dp, start = 120.dp)
                    .offset(x = (cloudDrift * 0.6f).dp)
                    .alpha(0.5f)
            )
        }

        // === 식물 (바닥 영역 안, 무작위 배치 + 원근감) ===
        // align(BottomCenter) 기준: y=0이 박스 바닥, 음수 y가 위.
        // depth 0(뒤/위) → 바닥 상단, 작은 이모지, 약간 투명
        // depth 1(앞/아래) → 바닥 하단, 큰 이모지, 불투명
        plantLayouts.forEachIndexed { i, layout ->
            val plant = displayPlants[i]
            val emoji = plantEmojiFor(plant.species, plant.stage)
            // depth 0~0.7 → 바닥의 15%~55% 위쪽 위치
            val yFactor = 0.15f + layout.depthPct * 0.4f
            val xOffset = ((layout.xPct - 0.5f) * maxW.value).dp
            val yOffset = -(groundH.value * yFactor).dp
            // 원근감: 뒤(작음) ~ 앞(큼), 20~52sp
            val fontSize = (20f + layout.depthPct * 32f).sp
            val emojiAlpha = 0.72f + layout.depthPct * 0.28f

            Text(
                emoji,
                fontSize = fontSize,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = xOffset, y = yOffset)
                    .alpha(emojiAlpha)
                    .graphicsLayer { rotationZ = sway + (i % 2) * 1.5f }
            )
        }

        // === 로봇 (가장 앞 통로, 식물보다 아래 → 절대 겹치지 않음) ===
        // offset 기반 이동 (graphicsLayer의 size=0 문제 회피).
        val robotXOffset = (robotX * maxW.value * 0.3f).dp
        val robotYOffset = -(groundH.value * 0.08f).dp + robotBob.dp  // 바닥에서 8% 위 (식물보다 아래)
        Text(
            "🤖",
            fontSize = 44.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = robotXOffset, y = robotYOffset)
        )

        // === 잔디 (최하단) ===
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(6) { i ->
                Text(
                    "🌱",
                    fontSize = (14 + (i % 3) * 4).sp,
                    modifier = Modifier.graphicsLayer { rotationZ = sway * 0.5f }
                )
            }
        }

        // 비 오버레이.
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
