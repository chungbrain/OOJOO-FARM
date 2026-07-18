package com.oojoo.farm.master.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.oojoo.farm.master.model.Plant

/**
 * 2D emoji-based farm scene — replaces the Filament 3D viewer.
 *
 * Renders a layered, pseudo-3D farm with emoji plants and a robot farmer over
 * the transparent weather backdrop already painted by the caller. Plants sway
 * gently; the robot bobs; the sun/moon pulses. Plant emojis come from the
 * user's registered plants (with a sensible default when none exist yet).
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

    // Plant sway: gentle left/right rotation.
    val sway by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sway"
    )

    // Robot bob: up/down float.
    val bob by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    // Celestial body pulse.
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Cloud drift (only when not night-rain; light drift adds life).
    val cloudDrift by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloudDrift"
    )

    Box(modifier.fillMaxSize()) {
        // Sun / Moon — top-right corner.
        Text(
            if (isNight) "🌙" else "☀️",
            fontSize = 44.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 20.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .alpha(0.95f)
        )

        // Clouds (skip on clear night to keep the sky clean).
        if (!isNight || isRain) {
            Text(
                "☁️",
                fontSize = 32.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 30.dp)
                    .offset(x = cloudDrift.dp)
                    .alpha(0.7f)
            )
            Text(
                "☁️",
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 60.dp, start = 120.dp)
                    .offset(x = (cloudDrift * 0.7f).dp)
                    .alpha(0.5f)
            )
        }

        // Robot farmer — center foreground, bobbing.
        Text(
            "🤖",
            fontSize = 56.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationY = bob * density
                }
        )

        // Plants — arranged in two rows for pseudo-3D depth.
        // Back row (smaller, higher) and front row (larger, lower).
        val backRow = displayPlants.take((displayPlants.size + 1) / 2)
        val frontRow = displayPlants.drop(backRow.size)

        PlantRow(
            plants = backRow,
            rowModifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(top = 120.dp),
            emojiSize = 34,
            swayDegrees = sway,
            alpha = 0.85f
        )
        PlantRow(
            plants = frontRow,
            rowModifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(top = 220.dp),
            emojiSize = 48,
            swayDegrees = sway * 1.3f,
            alpha = 1.0f
        )

        // Ground accent — a strip of grass tufts for depth.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(6) { i ->
                Text(
                    "🌱",
                    fontSize = (18 + (i % 3) * 6).sp,
                    modifier = Modifier.graphicsLayer { rotationZ = sway * 0.6f }
                )
            }
        }

        // Rain overlay (extra emoji layer on top of the caller's RainAnimation).
        if (isRain) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 100.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(5) { Text("💧", fontSize = 18.sp, modifier = Modifier.alpha(0.6f)) }
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
                plantEmoji(plant.stage),
                fontSize = emojiSize.sp,
                modifier = Modifier
                    .alpha(alpha)
                    .graphicsLayer {
                        rotationZ = swayDegrees + (i % 2) * 2f
                    }
            )
        }
    }
}

private fun plantEmoji(stage: String?): String = when (stage) {
    "fruiting" -> "🍅"
    "flowering" -> "🌸"
    "vegetative" -> "🌿"
    else -> "🌱"
}

private fun defaultDemoPlants(): List<Plant> = listOf(
    Plant(id = "demo1", name = "베이비그린", stage = "vegetative"),
    Plant(id = "demo2", name = "토마토", stage = "fruiting"),
    Plant(id = "demo3", name = "바질", stage = "vegetative")
)
