package com.oojoo.farm.master.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.oojoo.farm.master.data.AppStrings
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.DiagnosisConfirmRequest
import com.oojoo.farm.master.model.DiagnosisFrame
import com.oojoo.farm.master.model.DiagnosisSession
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 6-class health labels — API 토큰 → 현지화 라벨 중앙 매핑. */
private val HEALTH_CLASSES = listOf("healthy", "water_low", "water_high", "light_low", "pest", "heat")

private fun healthClassLabel(token: String, S: AppStrings): String = when (token) {
    "healthy" -> S.healthClassHealthy
    "water_low" -> S.healthClassWaterLow
    "water_high" -> S.healthClassWaterHigh
    "light_low" -> S.healthClassLightLow
    "pest" -> S.healthClassPest
    "heat" -> S.healthClassHeat
    else -> token
}

private fun confidencePct(c: Double?): String = c?.let { " (${(it * 100).toInt()}%)" } ?: ""

class DiagnosisReviewViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var sessions by mutableStateOf<List<DiagnosisSession>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var msg by mutableStateOf<String?>(null)
    var confirming by mutableStateOf<String?>(null)

    /** videoId → 재생 가능한 전체 URL (baseUrl + url). */
    var videoUrls by mutableStateOf<Map<String, String>>(emptyMap())

    /** sessionId → 사용자가 선택한 라벨 (확정 전). */
    val selections = mutableStateMapOf<String, String>()

    private var pollJob: Job? = null
    private var entryAtMs = 0L
    private var loadedPlantId: String? = null

    fun start(plantId: String) {
        if (loadedPlantId == plantId) return
        loadedPlantId = plantId
        entryAtMs = System.currentTimeMillis()
        load(plantId)
        // 화면 진입 후 90초 동안 3초 간격 폴링 — 새 세션이 잡히면 조기 중단.
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var waited = 0
            while (waited < 90) {
                delay(3000)
                waited += 3
                val newestMs = sessions.firstOrNull()?.createdAt?.let { parseTime(it) } ?: 0L
                if (newestMs > entryAtMs) break
                try { fetchSessions(plantId) } catch (_: Exception) {}
            }
        }
    }

    fun load(plantId: String) {
        loading = true
        error = null
        viewModelScope.launch {
            try { fetchSessions(plantId) } catch (e: Exception) { error = e.message }
            loading = false
        }
    }

    fun refresh(plantId: String) {
        viewModelScope.launch {
            loading = true
            try { fetchSessions(plantId) } catch (e: Exception) { error = e.message }
            loading = false
        }
    }

    private suspend fun fetchSessions(plantId: String) {
        val resp = api.plantDiagnoses(plantId)
        sessions = resp.sessions
        // videoId 가 있는 세션의 영상 URL 을 미리 가져온다.
        sessions.filter { it.videoId != null && !videoUrls.containsKey(it.videoId) }.forEach { s ->
            val vid = s.videoId ?: return@forEach
            try {
                val v = api.videoInfo(vid)
                videoUrls = videoUrls + (vid to ApiClient.baseUrl.trimEnd('/') + v.url)
            } catch (_: Exception) {}
        }
    }

    fun confirm(plantId: String, session: DiagnosisSession) {
        val label = selections[session.id] ?: return
        confirming = session.id
        viewModelScope.launch {
            try {
                api.confirmDiagnosis(session.id, DiagnosisConfirmRequest(label, Session.userId))
                msg = "라벨이 확정되었습니다"
                selections.remove(session.id)
                try { fetchSessions(plantId) } catch (_: Exception) {}
            } catch (e: Exception) {
                msg = "확정 실패: ${e.message}"
            }
            confirming = null
        }
    }

    /** createdAt 파싱 — ISO 8601 (Z 포함/미포함 모두 허용), 실패 시 0. */
    private fun parseTime(s: String): Long = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.LocalDateTime.parse(s.replace(" ", "T"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { 0L }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisReviewScreen(nav: NavController, plantId: String, vm: DiagnosisReviewViewModel = viewModel()) {
    val S = LocalAppStrings.current
    LaunchedEffect(plantId) { vm.start(plantId) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(S.diagnosisTitle, color = Color.White, fontWeight = FontWeight.Black) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    mapDiagMsg(vm.msg, S)?.let { m ->
                        Text(m, color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { vm.refresh(plantId) }, enabled = !vm.loading) {
                        Text("↻ ${S.refresh}", color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            if (vm.loading && vm.sessions.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp)
                    }
                }
            }

            vm.error?.let { err ->
                item { Text("⚠️ $err", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }

            if (!vm.loading && vm.sessions.isEmpty() && vm.error == null) {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🔍", fontSize = 34.sp)
                            Text(S.diagnosisEmpty, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = OojooTheme.Ink, textAlign = TextAlign.Center)
                            Text(S.diagnosisEmptyHint, color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            items(vm.sessions, key = { it.id }) { session ->
                DiagnosisSessionCard(session, session.videoId?.let { vm.videoUrls[it] }, vm, plantId, S)
            }
        }
    }
}

@Composable
private fun DiagnosisSessionCard(session: DiagnosisSession, videoUrl: String?, vm: DiagnosisReviewViewModel, plantId: String, S: AppStrings) {
    val confirmed = session.humanLabel != null
    Card(
        Modifier.fillMaxWidth()
            .shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape)
            .border(2.dp, if (confirmed) OojooTheme.Green else OojooTheme.Orange, OojooTheme.CardShape)
            .clip(OojooTheme.CardShape),
        shape = OojooTheme.CardShape,
        colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 헤더 — 생성 시각 + 확인 상태
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(session.createdAt ?: "", color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (confirmed) {
                    Text("✓ ${S.diagnosisConfirmed}", color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                } else {
                    Text(S.diagnosisNeedHuman, color = OojooTheme.Orange, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            // AI 판정 칩 + 모델 배지
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = OojooTheme.PillShape, color = OojooTheme.GreenBg, border = OojooTheme.BorderThin) {
                    Text(
                        "${S.diagnosisAiVerdict}: ${healthClassLabel(session.aiLabel, S)}${confidencePct(session.aiConfidence)}",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = OojooTheme.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
                Surface(shape = OojooTheme.PillShape, color = OojooTheme.Yellow, border = OojooTheme.BorderThin) {
                    Text(
                        session.modelId ?: S.heuristicModel,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = OojooTheme.Ink2, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            // 진단 영상
            videoUrl?.let { url ->
                Text(S.diagnosisVideoLabel, color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth().height(220.dp).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                setVideoURI(Uri.parse(url))
                                setOnPreparedListener { it.isLooping = true; start() }
                            }
                        },
                        update = { view ->
                            if (view.tag != url) {
                                view.tag = url
                                view.setVideoURI(Uri.parse(url))
                                view.start()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 샘플 프레임 갤러리 (2행 × 3열)
            if (session.frames.isNotEmpty()) {
                Text(S.diagnosisFramesLabel, color = OojooTheme.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                session.frames.take(6).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { frame ->
                            Column(Modifier.weight(1f)) {
                                AsyncImage(
                                    model = ApiClient.baseUrl.trimEnd('/') + frame.url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.75f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(12.dp))
                                )
                                Text(
                                    frameCaption(frame, S),
                                    color = OojooTheme.Muted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // 사람 확인 영역
            if (!confirmed) {
                HorizontalDivider(color = OojooTheme.Line)
                HEALTH_CLASSES.chunked(3).forEach { rowClasses ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowClasses.forEach { cls ->
                            DiagnosisClassChip(
                                selected = vm.selections[session.id] == cls,
                                onClick = { vm.selections[session.id] = cls },
                                label = healthClassLabel(cls, S),
                                aiPick = cls == session.aiLabel,
                                S = S,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowClasses.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                GradientButton(
                    text = S.diagnosisConfirmBtn,
                    onClick = { vm.confirm(plantId, session) },
                    enabled = vm.selections[session.id] != null && vm.confirming == null,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                HorizontalDivider(color = OojooTheme.Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(S.diagnosisConfirmedLabel, color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${healthClassLabel(session.humanLabel!!, S)} · ${session.confirmedAt ?: ""}",
                        color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

/** 6-class 선택 칩 — OojooChip 스타일 + AI 추천 서브타이틀. */
@Composable
private fun DiagnosisClassChip(selected: Boolean, onClick: () -> Unit, label: String, aiPick: Boolean, S: AppStrings, modifier: Modifier = Modifier) {
    Surface(
        shape = OojooTheme.PillShape,
        color = if (selected) OojooTheme.Green else OojooTheme.Card,
        border = OojooTheme.BorderThin,
        modifier = modifier.clip(OojooTheme.PillShape).border(OojooTheme.BorderThin, OojooTheme.PillShape).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = if (selected) Color.White else OojooTheme.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            if (aiPick) {
                Text(
                    S.diagnosisAiPick,
                    color = if (selected) Color.White.copy(alpha = 0.85f) else OojooTheme.GreenDark,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

private fun frameCaption(frame: DiagnosisFrame, S: AppStrings): String =
    frame.condition?.let { "${healthClassLabel(it, S)}${confidencePct(frame.confidence)}" } ?: S.diagnosisNoCondition

private fun mapDiagMsg(m: String?, S: AppStrings): String? = when {
    m == null -> null
    m.startsWith("확정 실패") -> S.diagnosisConfirmFail + m.removePrefix("확정 실패")
    m == "라벨이 확정되었습니다" -> S.diagnosisConfirmDone
    else -> localizeMasterMessage(m, S)
}
