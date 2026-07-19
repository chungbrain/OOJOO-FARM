package com.oojoo.farm.master.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.oojoo.farm.master.model.Plant

/**
 * 작물 종류(species)와 생장 단계(stage)를 함께 고려한 이모지.
 * species가 알려진 작물이면 해당 작물 이모지를, 아니면 stage 기반 기본 이모지.
 * PlantListScreen과 FarmSceneView 양쪽에서 공통 사용.
 */
fun plantEmojiFor(species: String?, stage: String?): String {
    val sp = species?.trim()?.lowercase()?.replace(" ", "")
    if (!sp.isNullOrEmpty()) {
        // 과일/결실 단계면 과일 이모지 우선
        val fruitEmoji = when (sp) {
            "토마토", "방울토마토", "tomato", "cherrytomato" -> "🍅"
            "딸기", "strawberry" -> "🍓"
            "고추", "chili", "pepper", "고추" -> "🌶️"
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

        // 작물 본체 이모지 (잎/채소类)
        return when (sp) {
            "상추", "lettuce" -> "🥬"
            "깻잎", "perilla" -> "🍃"
            "바질", "basil" -> "🌿"
            "로즈마리", "rosemary" -> "🌿"
            "박하", "mint", "peppermint" -> "🌿"
            "대파", "파", "파프리카", "greenonion", "onion" -> "🧅"
            "양파", "onion" -> "🧅"
            "마늘", "garlic" -> "🧄"
            "감자", "potato" -> "🥔"
            "고구마", "sweetpotato" -> "🍠"
            "당근", "carrot" -> "🥕"
            "무", "radish", "daikon" -> "🥬"
            "배추", "cabbage" -> "🥬"
            "브로콜리", "broccoli" -> "🥦"
            "딸기", "strawberry" -> "🍓"
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

/**
 * 2D emoji-based farm scene.
 *
 * 레이아웃 (위에서 아래로, y축 겹침 없음):
 *   하늘   : ☀️/🌙, ☁️ (상단)
 *   식물   : 가로 행 1~2개 (중앙, 고정 위치에서 좌우로만 살짝 흔들림)
 *   로봇   : 식물 행 아래에서 좌우로 자율 왕복 (식물과 y축이 다르므로 절대 겹치지 않음)
 *   잔디   : 최하단 풀잎들
 */
@Composable
fun FarmSceneView(
    modifier: Modifier = Modifier,
    plants: List<Plant> = emptyList(),
    isNight: Boolean = false,
    isRain: Boolean = false
) {
    val displayPlants = plants.ifEmpty { defaultDemoPlants() }

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

    // 로봇 자율 순찰 — 좌우로 넓게 왕복 (식물 행 아래 통로).
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

    Box(modifier.fillMaxSize()) {
        // === 하늘 영역 (상단) ===
        Text(
            if (isNight) "🌙" else "☀️",
            fontSize = 40.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 18.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .alpha(0.95f)
        )
        if (!isNight || isRain) {
            Text(
                "☁️",
                fontSize = 30.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 26.dp)
                    .offset(x = cloudDrift.dp)
                    .alpha(0.7f)
            )
            Text(
                "☁️",
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 54.dp, start = 130.dp)
                    .offset(x = (cloudDrift * 0.6f).dp)
                    .alpha(0.5f)
            )
        }

        // === 식물 행 (중앙 고정, 흔들림만) ===
        // 최대 4개까지 한 행에 배치, 그 이상이면 두 행.
        val frontRow = displayPlants.take(4)
        val backRow = displayPlants.drop(4).take(4)

        if (backRow.isNotEmpty()) {
            PlantRow(
                plants = backRow,
                rowModifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                emojiSize = 28,
                swayDegrees = sway * 0.7f,
                alpha = 0.8f
            )
        }
        PlantRow(
            plants = frontRow,
            rowModifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 170.dp),
            emojiSize = 40,
            swayDegrees = sway,
            alpha = 1.0f
        )

        // === 로봇 순찰 (식물 행 아래 통로, y축 분리로 절대 겹치지 않음) ===
        // robotX: -1 ~ 1 → 화면 가로 폭의 ±35% 범위 왕복
        Text(
            "🤖",
            fontSize = 52.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 290.dp)
                .graphicsLayer {
                    translationX = robotX * size.width * 0.35f
                    translationY = robotBob * density
                }
        )

        // === 잔디 (최하단) ===
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(6) { i ->
                Text(
                    "🌱",
                    fontSize = (16 + (i % 3) * 5).sp,
                    modifier = Modifier.graphicsLayer { rotationZ = sway * 0.5f }
                )
            }
        }

        // 비 오버레이 (상단 추가 드롭들).
        if (isRain) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 90.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(5) { Text("💧", fontSize = 16.sp, modifier = Modifier.alpha(0.6f)) }
            }
        }
    }
}

@Composable
private fun PlantRow(
    plants: List<Plant>,
    rowModifier: Modifier,
    emojiSize: Int,
    swayDegrees: Float,
    alpha: Float
) {
    if (plants.isEmpty()) {
        Spacer(rowModifier.height(0.dp))
        return
    }
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        plants.forEachIndexed { i, plant ->
            Text(
                plantEmojiFor(plant.species, plant.stage),
                fontSize = emojiSize.sp,
                modifier = Modifier
                    .alpha(alpha)
                    .graphicsLayer {
                        rotationZ = swayDegrees + (i % 2) * 1.5f
                    }
            )
        }
    }
}

private fun defaultDemoPlants(): List<Plant> = listOf(
    Plant(id = "demo1", name = "베이비그린", species = "상추", stage = "vegetative"),
    Plant(id = "demo2", name = "방울토마토", species = "방울토마토", stage = "fruiting"),
    Plant(id = "demo3", name = "바질", species = "바질", stage = "vegetative"),
    Plant(id = "demo4", name = "딸기", species = "딸기", stage = "flowering")
)
