package com.oojoo.farm.master.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.model.Plant
import com.oojoo.farm.master.model.Slave
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
    return sqrt(dr * dr + dc * dc)
}

// --- 나무 팻말 색상 ---
private val WoodLight = Color(0xFFD2A26B)
private val WoodDark = Color(0xFFA9713B)
private val WoodEdge = Color(0xFF6D4423)

/**
 * 빈 cell 중 가장 좋은 위치 — 격자 중심에서 가까운 순, 같은 행에 1칸 간격 유지.
 * (새 식물 등록/이동 시 충돌 없이 배치하는 공용 규칙)
 */
private fun pickEmptyCell(used: Set<PlantCell>): PlantCell {
    val center = (GRID - 1) / 2f
    val order = (0 until GRID).flatMap { r ->
        (0 until GRID).map { c -> PlantCell(r, c) to distance(r, c, center, center) }
    }.sortedBy { it.second }.map { it.first }
    val spaced = order.firstOrNull { c ->
        c !in used && used.none { o -> o.row == c.row && kotlin.math.abs(o.col - c.col) <= 1 }
    }
    if (spaced != null) return spaced
    return order.firstOrNull { it !in used } ?: PlantCell(GRID - 1, GRID - 1)
}

/**
 * 2D emoji farm scene.
 *
 * 레이아웃:
 *   하늘   : 천체/구름 (상단 40%). 구름은 하늘을 가로질러 흐르고
 *            새 떼가 가끔 편대로 날아간다. 밤에는 별가루 반짝임 + 북두칠성.
 *   바닥   : 4x4 격자. 식물이 있는 cell은 밝은 흙색 원형 + 식물 이모지 + 나무 이름표.
 *            식물을 꾹 누르면 드래그해서 위치를 옮길 수 있다 (점유 cell → 가장 가까운
 *            빈 cell, 격자 가득차면 원위치).
 *   로봇   : 배정된 식물들을 순서대로 방문·관리. 이동은 X/Y 동시(직선), 거리에 비례
 *            하는 시간, 이동 방향으로 회전, 관수 중엔 물방울이 떨어진다.
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
    // 등록된 식물이 없으면 빈 농장 표시 (과거: 가짜 데모 식물 4종 렌더링)
    val displayPlants = plants

    // 식물 위치 — 식물 ID 키로 관리. 드래그로 옮겨둔 위치를 SharedPreferences에
    // 저장해 앱 재시작 후에도 그 자리에 배치한다. 목록 순서 변화(추가/재정렬)에도
    // 기존 식물은 움직이지 않고, 새 식물만 빈 cell에 결정적으로 배정,
    // 삭제된 식물의 cell은 해제(+저장 위치 정리).
    // 조합 내 동기 보정(첫 프레임부터 올바른 위치) + 이펙트로 상태 영속화.
    val ctx = LocalContext.current
    var cellMap by remember {
        val stored = Prefs.farmPlantCells(ctx).mapValues { (_, rc) -> PlantCell(rc.first, rc.second) }
        mutableStateOf(stored)
    }
    val currentIds = displayPlants.map { it.id }.toSet()
    val cells = remember(cellMap, currentIds) {
        if (cellMap.keys.containsAll(currentIds) && cellMap.size == currentIds.size) {
            cellMap
        } else {
            val m = cellMap.filterKeys { it in currentIds }.toMutableMap()
            for (p in displayPlants) {
                if (p.id !in m) m[p.id] = pickEmptyCell(m.values.toSet())
            }
            m
        }
    }
    LaunchedEffect(cells) {
        if (cellMap != cells) cellMap = cells
        // 위치 영속화 — 새로 배정/옮겨진 식물 위치 저장, 삭제된 식물의 저장 위치 정리
        cells.forEach { (id, c) -> Prefs.setFarmPlantCell(ctx, id, c.row, c.col) }
        Prefs.removeFarmPlantCells(ctx, Prefs.farmPlantCells(ctx).keys.filter { it !in currentIds })
    }

    // 드래그 중인 식물 ID (null = 드래그 아님)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // Farmer가 배정된 식물만 로봇 순찰 대상
    val assignedIds = remember(displayPlants, slaves) {
        displayPlants.filter { p -> slaves.any { it.id == p.slave_id } }.map { it.id }
    }
    val waypoints = remember(cells, assignedIds) {
        assignedIds.mapNotNull { id ->
            cells[id]?.let { cell ->
                val sideOffset = 0.32f
                val col = if (cell.col > 0) (cell.col - sideOffset) else (cell.col + sideOffset)
                Waypoint(cell.row, col)
            }
        }
    }
    val showRobot = waypoints.isNotEmpty()

    val infiniteTransition = rememberInfiniteTransition(label = "farm")

    val sway by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )

    // 로봇 부드러운 이동을 위한 Animatable (격자 단위 좌표)
    val robotX = remember { Animatable((GRID - 1) / 2f) }
    val robotY = remember { Animatable((GRID - 1) / 2f) }
    val robotScale = remember { Animatable(1f) }
    val tendingAlpha = remember { Animatable(0f) }
    val robotFacing = remember { Animatable(1f) }
    val robotTilt = remember { Animatable(0f) }

    // 순찰 인덱스 — 웨이포인트 목록이 바뀌어도(식물 이동/추가) 재개, 초기화 안 함.
    // (과거: LaunchedEffect(waypoints) 재기동마다 idx=0 리셋 → 첫 식물로 점프)
    var patrolIdx by remember { mutableStateOf(0) }

    LaunchedEffect(waypoints, showRobot) {
        if (!showRobot || waypoints.isEmpty()) {
            robotTilt.snapTo(0f)
            return@LaunchedEffect
        }
        var idx = if (waypoints.size > 1) patrolIdx % waypoints.size else 0
        while (true) {
            val wp = waypoints[idx]
            val dx = wp.col - robotX.value
            val dy = wp.row - robotY.value
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 0.02f) {
                // 이동 방향으로 회전
                robotFacing.animateTo(
                    if (dx < 0) -1f else 1f,
                    animationSpec = tween(160, easing = FastOutSlowInEasing)
                )
                // 직선 이동 — X/Y 동시 tween (과거: X 완료 후 Y → L자 꺾임),
                // 시간은 이동 거리에 비례
                val dur = (260f + dist * 320f).coerceIn(300f, 1500f).roundToInt()
                robotTilt.animateTo(
                    if (dx >= 0) 4f else -4f,
                    animationSpec = tween(dur, easing = FastOutSlowInEasing)
                )
                coroutineScope {
                    launch { robotX.animateTo(wp.col, animationSpec = tween(dur, easing = FastOutSlowInEasing)) }
                    launch { robotY.animateTo(wp.row.toFloat(), animationSpec = tween(dur, easing = FastOutSlowInEasing)) }
                }
            } else {
                robotTilt.animateTo(0f, animationSpec = tween(150, easing = FastOutSlowInEasing))
            }
            // 도착 — 관리 액션 (살짝 커짐 + 물방울 + 2초 관수)
            robotScale.animateTo(1.25f, animationSpec = tween(300, easing = FastOutSlowInEasing))
            tendingAlpha.animateTo(1f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            delay(2000)
            tendingAlpha.animateTo(0f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            robotScale.animateTo(1f, animationSpec = tween(300, easing = FastOutSlowInEasing))
            delay(500)
            idx = (idx + 1) % waypoints.size
            patrolIdx = idx
        }
    }

    val robotBob by infiniteTransition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "robotBob"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.90f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    // 구름 — 하늘을 가로질러 지속적으로 흐른다.
    // 한 바퀴 20~26초 — 흐름이 한눈에 보이는 속도.
    // (과거 42~58초였다가 멈춰 있는 것처럼 보인다는 피드백으로 단축)
    val cloudF1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart),
        label = "cloud1"
    )
    val cloudF2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "cloud2"
    )
    val cloudF3 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Restart),
        label = "cloud3"
    )

    // 밤하늘 별 반짝임 주기 (별가루 + 북두칠성 공용)
    val twinkle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle"
    )

    // 새 떼 — 가끔 하늘을 가로질러 날아간다.
    // 첫 비행은 2~5초 안에 (도입 확인용), 이후 6~15초 잠복 후 9초 비행.
    var birdsActive by remember { mutableStateOf(false) }
    val birdFlight = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var firstFlight = true
        while (true) {
            delay(if (firstFlight) Random.nextLong(2000, 5000) else Random.nextLong(6000, 15000))
            firstFlight = false
            birdsActive = true
            birdFlight.animateTo(1f, animationSpec = tween(9000, easing = LinearEasing))
            birdsActive = false
            birdFlight.snapTo(0f)
        }
    }

    // 관수 중 물방울 낙하 (0 → 16dp 후 리셋)
    val dropFall by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Restart),
        label = "dropFall"
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value
        val groundH = maxH * 0.6f
        val cellW = maxW / GRID
        val cellH = groundH / GRID

        val soilColor = Color(0xFFB5905E)

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
        // === 밤하늘 — 별가루 반짝임 + 북두칠성 ===
        if (isNight) {
            // 별 위치 — 시드 고정이라 재구성돼도 같은 자리에 빛난다.
            val skyStars = remember {
                val rnd = Random(20261121)
                List(18) { Triple(rnd.nextFloat(), 0.06f + rnd.nextFloat() * 0.86f, rnd.nextFloat()) }
            }
            Canvas(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height((maxH - groundH).dp)
            ) {
                val w = size.width
                val h = size.height
                // 별가루 — 개별 위상으로 각자 다른 속도로 반짝임
                skyStars.forEach { (sx, sy, phase) ->
                    val a = 0.25f + 0.75f * (0.5f + 0.5f * sin((twinkle + phase) * 2.0 * PI).toFloat())
                    drawCircle(
                        color = Color.White.copy(alpha = a),
                        radius = 1.6f + phase * 1.6f,
                        center = Offset(sx * w, sy * h)
                    )
                }
                // 북두칠성 — 국자 4개 별 + 자루 3개 별을 잇는 별자리 라인
                val dipper = listOf(
                    0.10f to 0.52f, 0.20f to 0.40f, 0.32f to 0.34f, 0.44f to 0.42f,
                    0.56f to 0.60f, 0.68f to 0.52f, 0.80f to 0.30f
                ).map { (x, y) -> Offset(x * w, y * h) }
                val lineColor = Color.White.copy(alpha = 0.35f)
                listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 0 to 4, 4 to 5, 5 to 6).forEach { (a, b) ->
                    drawLine(lineColor, dipper[a], dipper[b], strokeWidth = 2f)
                }
                dipper.forEachIndexed { i, p ->
                    drawCircle(Color.White.copy(alpha = 0.95f), radius = if (i % 2 == 0) 5f else 4f, center = p)
                    drawCircle(Color.White.copy(alpha = 0.22f), radius = 9f, center = p)
                }
            }
        }

        // === 구름 — 하늘을 가로질러 흐른다 (속도/높이가 제각각) ===
        val cloudDim = if (isNight) 0.4f else 1f
        val cloudTravel = maxW + 220f
        // 위상(phase) — 구름 3개가 화면 밖 같은 곳에서 함께 출발하면 처음 ~8초간
        // 하늘이 텅 비어 보인다. 위상을 엇갈리면 첫 프레임부터 구름이 떠 있고,
        // 순환 경계(f+phase=1)는 양 끝 화면 밖이라 되돌아감이 눈에 띄지 않는다.
        fun cloudX(f: Float, phase: Float): Float = (maxW + 80f) - ((f + phase) % 1f) * cloudTravel
        Text(
            "☁️", fontSize = 30.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = cloudX(cloudF1, 0f).dp, y = (18f + sway * 1.4f).dp)
                .alpha(0.75f * cloudDim)
        )
        Text(
            "☁️", fontSize = 22.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = cloudX(cloudF2, 0.4f).dp, y = (48f - sway * 1.1f).dp)
                .alpha(0.5f * cloudDim)
        )
        Text(
            "☁️", fontSize = 26.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = cloudX(cloudF3, 0.7f).dp, y = (72f + sway * 0.9f).dp)
                .alpha(0.62f * cloudDim)
        )

        // === 새 떼 — 가끔 V자 편대로 날아간다. 진행 방향은 구름과 같은 오른쪽→왼쪽 ===
        // (과거: 🐦 이모지를 왼쪽→오른쪽으로 이동 — 왼쪽을 바라보는 새 모양이
        //  거꾸로 날아 보였고, 이모지 자체가 앉아 있는 새라 '나는 새'가 아니었음.
        //  날개를 펄럭이는 날아가는 새 실루엣으로 교체)
        if (birdsActive) {
            val f = birdFlight.value
            val bx = (maxW + 90f) - f * (maxW + 180f)
            val by = maxH * 0.10f + sin(f * 2.5 * PI).toFloat() * 12f
            Canvas(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (bx - 40f).dp, y = (by - 22f).dp)
                    .size(width = 80.dp, height = 44.dp)
            ) {
                // 날개 펄럭임 — 비행 9초 동안 약 9번 (1Hz), 편대마다 위상 엇갈림
                val flapBase = f * 18.0 * PI
                drawBird(centerX = 40f, centerY = 20f, scale = 1f, flap = sin(flapBase.toFloat()))
                drawBird(centerX = 15f, centerY = 33f, scale = 0.8f, flap = sin((flapBase + 1.0).toFloat()))
                drawBird(centerX = 65f, centerY = 30f, scale = 0.85f, flap = sin((flapBase + 2.0).toFloat()))
            }
        }

        // === 4x4 격자 바닥 ===
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(groundH.dp)
        ) {
            displayPlants.forEachIndexed { i, plant ->
                val cell = cells[plant.id] ?: PlantCell(0, 0)
                val emoji = plantEmojiFor(plant.species, plant.stage)
                val fontSize = (16f + (cell.row.toFloat() / (GRID - 1)) * 18f).sp
                val soilSize = (minOf(cellW, cellH) * 0.7f).dp
                val cellLeft = cell.col * cellW
                val cellTop = cell.row * cellH

                val isDragging = (draggingId == plant.id)

                // 위치: 드래그 중이면 손가락 추적, 해제 시 새 cell로 부드럽게(~180ms)
                // 이동 (과거: 즉각 텔레포트)
                val baseX = cellLeft + (cellW - soilSize.value) / 2f
                val baseY = cellTop + (cellH - soilSize.value) / 2f
                val boxX by animateFloatAsState(
                    targetValue = if (isDragging) dragOffsetX else baseX,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "plantX"
                )
                val boxY by animateFloatAsState(
                    targetValue = if (isDragging) dragOffsetY else baseY,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "plantY"
                )

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
                        .pointerInput(plant.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = plant.id
                                    dragOffsetX = baseX
                                    dragOffsetY = baseY
                                },
                                onDragEnd = {
                                    // 드롭 위치 → cell. 점유 중이면 드롭점에서 가장
                                    // 가까운 빈 cell로, 빈 cell 없으면 원위치.
                                    val dropCx = (dragOffsetX + soilSize.value / 2f) / cellW
                                    val dropCy = (dragOffsetY + soilSize.value / 2f) / cellH
                                    val desired = PlantCell(
                                        dropCy.roundToInt().coerceIn(0, GRID - 1),
                                        dropCx.roundToInt().coerceIn(0, GRID - 1)
                                    )
                                    val others = cells.entries.filter { it.key != plant.id }.map { it.value }
                                    val target = if (desired in others) {
                                        (0 until GRID).flatMap { r -> (0 until GRID).map { c -> PlantCell(r, c) } }
                                            .filter { c -> c !in others }
                                            .minByOrNull { c ->
                                                val dx = c.col + 0.5f - dropCx
                                                val dy = c.row + 0.5f - dropCy
                                                dx * dx + dy * dy
                                            } ?: cell
                                    } else {
                                        desired
                                    }
                                    if (target != cell) {
                                        cellMap = cells + (plant.id to target)
                                        // 옮겨진 위치 기억 — 앱 재시작 후에도 이 자리에 배치
                                        Prefs.setFarmPlantCell(ctx, plant.id, target.row, target.col)
                                    }
                                    draggingId = null
                                },
                                onDragCancel = { draggingId = null },
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

                // 이름표 — 나무 팻말, 원 정중앙 아래 배치. 전체 높이 = 줄기 7 + 몸통.
                // 몸통 높이는 폰트 확대(fontScale)를 반영해 계산 — 시스템 글자 크기가
                // 커도 이름 텍스트가 아래로 잘리지 않는다.
                // 맨 아래 행(row GRID-1)은 팻말이 카드 하단으로 잘리지 않게 원 위쪽으로.
                val fontScale = LocalDensity.current.fontScale
                val signW = 64f
                val signBodyH = 17f * fontScale
                val signH = signBodyH + 7f
                val centerX = boxX + soilSize.value / 2f
                val onTop = cell.row >= GRID - 1
                Box(
                    Modifier
                        .offset(
                            x = (centerX - signW / 2f).dp,
                            y = if (onTop) (boxY - signH).dp else (boxY + soilSize.value).dp
                        )
                        .size(width = signW.dp, height = signH.dp)
                        .graphicsLayer {
                            alpha = if (isDragging) 0.6f else 1f
                            rotationZ = if (i % 2 == 0) -2.5f else 2f
                        }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (onTop) {
                            WoodSign(plant.name, signBodyH)
                            Box(Modifier.size(width = 2.5.dp, height = 7.dp).background(WoodEdge))
                        } else {
                            Box(Modifier.size(width = 2.5.dp, height = 7.dp).background(WoodEdge))
                            WoodSign(plant.name, signBodyH)
                        }
                    }
                }
            }

            // === 로봇 (배정된 식물이 있을 때만 표시) ===
            if (showRobot) {
                val robotPxX = robotX.value * cellW + cellW * 0.5f
                val robotPxY = robotY.value * cellH + cellH * 0.5f
                RobotFigure(
                    facing = robotFacing.value,
                    tilt = robotTilt.value,
                    scale = robotScale.value,
                    tending = tendingAlpha.value,
                    dropFall = dropFall,
                    modifier = Modifier.offset(
                        x = (robotPxX - 22f).dp,
                        y = (robotPxY - 24f + robotBob).dp
                    )
                )
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

/** 나무 팻말 — 고정 폭 64dp, 나무 그라디언트 + 어두운 테두리, 긴 이름은 줄임표시.
 *  이름 텍스트는 상하좌우 모두 가운데 정렬. 몸통 높이(bodyH)는 폰트 확대를
 *  반영한 값이라 아래쪽이 잘려 보이지 않는다. */
@Composable
private fun WoodSign(plantName: String, bodyH: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(64.dp)
            .height(bodyH.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(WoodLight, WoodDark)))
            .border(1.dp, WoodEdge, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            plantName,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFF3E0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 로봇 — 하단 메뉴바의 farmer 탭과 같은 🤖 이모지 피겨.
 * (과거: primitives로 조립한 스틸 헤드+그린 몸통 로봇 — 모양이 어색해 교체)
 * facing(+1/-1)으로 이동 방향 반전, tilt으로 이동 기울기, scale로 도착 시 커짐,
 * tending(0..1)으로 관수 물방울이 떨어진다.
 */
@Composable
private fun RobotFigure(
    facing: Float,
    tilt: Float,
    scale: Float,
    tending: Float,
    dropFall: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.size(width = 44.dp, height = 48.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            "🤖",
            fontSize = 38.sp,
            modifier = Modifier
                .offset(y = 4.dp)
                .graphicsLayer {
                    scaleX = scale * facing
                    scaleY = scale
                    rotationZ = tilt
                }
        )

        // 관수 중 — 아래로 떨어지는 물방울 (떨어질수록 투명)
        if (tending > 0.01f) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { k ->
                    val f = dropFall + k * 5f
                    Text(
                        "💧",
                        fontSize = 10.sp,
                        modifier = Modifier
                            .offset(y = f.dp)
                            .alpha((tending * (1f - f / 24f)).coerceIn(0f, 1f))
                    )
                }
            }
        }
    }
}

/**
 * 날아가는 새 실루엣 — 몸통 점 + 좌우로 뻗은 곡선 날개(flap으로 펄럭임).
 * (과거 🐦 이모지 — 앉아 있는 새 모양에 진행 방향과 반대로 날아 보였음)
 */
private fun DrawScope.drawBird(centerX: Float, centerY: Float, scale: Float, flap: Float) {
    val ink = Color(0xFF263238)
    val s = 10f * scale          // 날개 길이
    val tip = flap * 4f * scale  // 날개 끝 상하 변위 (펄럭임)
    drawCircle(ink, radius = 2.4f * scale, center = Offset(centerX, centerY))
    val leftWing = Path().apply {
        moveTo(centerX, centerY)
        quadraticBezierTo(centerX - s * 0.45f, centerY - tip * 0.6f, centerX - s, centerY - tip)
    }
    val rightWing = Path().apply {
        moveTo(centerX, centerY)
        quadraticBezierTo(centerX + s * 0.45f, centerY - tip * 0.6f, centerX + s, centerY - tip)
    }
    drawPath(leftWing, ink, style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round))
    drawPath(rightWing, ink, style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round))
}
