package com.oojoo.farm.master.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.AppStrings
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.LocationHelper
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

private fun typeLabel(t: String?, S: AppStrings) = when (t) { "share" -> S.share; "sell" -> S.sell; "buy" -> S.buy; else -> t ?: "" }
private fun statusLabel(s: String?, S: AppStrings) = when (s) { "reserved" -> S.reserved; "done" -> S.tradedone; else -> "" }

class CommunityViewModel(app: Application) : AndroidViewModel(app) {
    private val api get() = ApiClient.api
    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
    var type by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    // 장소 스코프 — 기본은 FARM(farmer 위치). MINE(master 지역) / NEAR(내 주변 반경)은 추가 선택.
    var scope by mutableStateOf(SCOPE_FARM)
    var nearFail by mutableStateOf(false)
    var farmRegion by mutableStateOf<String?>(null) // 위치정보 가진 첫 farmer의 region — 탭의 기본 지역
    private var nearLat: Double? = null
    private var nearLon: Double? = null
    private var loadedRegion: String? = null
    private var loadSeq = 0

    init {
        // 기본 지역 = farmer가 있는 위치 — farmer를 먼저 조회해 region을 잡은 뒤 피드 로드
        viewModelScope.launch {
            try {
                val slaves = api.slaves(Session.userId).slaves
                farmRegion = slaves.firstOrNull { !it.region.isNullOrBlank() }?.region
            } catch (_: Exception) {}
            load()
        }
    }

    /** 유효한 region 필터 — FARM: farmer 위치(없으면 master 지역 폴백), MINE: master 지역. */
    private fun effectiveRegion(): String? = when (scope) {
        SCOPE_MINE -> Session.region
        else -> farmRegion ?: Session.region
    }

    /** 앱바/빈 상태 표시용 현재 스코프 라벨. */
    fun scopeTitle(S: AppStrings): String = when (scope) {
        SCOPE_NEAR -> S.nearMeScope
        else -> effectiveRegion() ?: S.myRegion
    }

    fun load() {
        val seq = ++loadSeq
        loading = true; nearFail = false
        viewModelScope.launch {
            try {
                val fetched = if (scope == SCOPE_NEAR) {
                    // 반경 검색 — region 필터 없이 위치 기준 (5km 내 위치 있는 글만)
                    if (nearLat == null || nearLon == null) {
                        val loc = LocationHelper.resolve(getApplication()) ?: throw Exception("no location")
                        nearLat = loc.lat; nearLon = loc.lon
                    }
                    api.communityPosts(null, type, query.takeIf { it.isNotBlank() }, Session.userId, nearLat, nearLon).posts
                } else {
                    loadedRegion = effectiveRegion()
                    api.communityPosts(loadedRegion, type, query.takeIf { it.isNotBlank() }, Session.userId).posts
                }
                if (seq == loadSeq) posts = fetched
            } catch (_: Exception) {
                if (seq == loadSeq && scope == SCOPE_NEAR) nearFail = true
            }
            if (seq == loadSeq) loading = false
        }
    }

    /** master 지역이 바뀌면(Home에서 위치 이동으로 자동 갱신) "내 지역" 스코프라면 다시 로드. */
    fun regionChanged() {
        if (scope == SCOPE_MINE && loadedRegion != Session.region) load()
    }

    fun selectScope(s: String) {
        if (scope == s) return
        scope = s
        load()
    }

    fun selectType(t: String?) { type = t; load() }
    fun search() { load() }

    companion object {
        const val SCOPE_FARM = "farm" // 기본 — farmer가 있는 위치
        const val SCOPE_MINE = "mine" // master가 있는 지역 (추가)
        const val SCOPE_NEAR = "near" // 내 주변 (현재 위치 반경 검색)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(nav: NavController, vm: CommunityViewModel = viewModel()) {
    val S = LocalAppStrings.current
    // master 지역 변경 감지 — Home에서 위치 이동으로 Session.region 이 갱신되면
    // 다시 화면에 들어올 때(또는 이 화면에서 복귀할 때) 지역 피드 재로드
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.regionChanged()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("${S.neighbor} · ${vm.scopeTitle(S)}", color = Color.White, fontWeight = FontWeight.Bold) }, actions = { IconButton(onClick = { nav.navigate("messages") }) { Icon(Icons.Default.Message, contentDescription = stringResource(R.string.messages_title), tint = Color.White) }; TextButton(onClick = { nav.navigate("community_write") }) { Text(S.writePost, color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("community_write") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = S.writePost) } },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OojooField(
                    vm.query, { vm.query = it }, S.searchCrop,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() })
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OojooChip(vm.type == null, { vm.selectType(null) }, S.all)
                    OojooChip(vm.type == "share", { vm.selectType("share") }, S.share)
                    OojooChip(vm.type == "sell", { vm.selectType("sell") }, S.sell)
                    OojooChip(vm.type == "buy", { vm.selectType("buy") }, S.buy)
                }
            }
            item {
                // 장소 스코프 — 기본은 farmer 위치, master 지역/내 주변은 추가 선택
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OojooChip(vm.scope == CommunityViewModel.SCOPE_FARM, { vm.selectScope(CommunityViewModel.SCOPE_FARM) }, "🚜 ${S.writeLocationFarm}")
                    OojooChip(vm.scope == CommunityViewModel.SCOPE_MINE, { vm.selectScope(CommunityViewModel.SCOPE_MINE) }, "🏠 ${S.myRegion}")
                    OojooChip(vm.scope == CommunityViewModel.SCOPE_NEAR, { vm.selectScope(CommunityViewModel.SCOPE_NEAR) }, "📍 ${S.nearMe}")
                }
            }
            if (vm.nearFail) {
                item { Text("⚠️ ${S.nearMeFail}", color = OojooTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) } }
            if (vm.posts.isEmpty() && !vm.loading) {
                item { Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) { Text(if (vm.scope == CommunityViewModel.SCOPE_NEAR) S.noPostsNearMe else "${vm.scopeTitle(S)}${S.noPostsSuffix}", Modifier.padding(24.dp), color = OojooTheme.Ink) } }
            }
            items(vm.posts) { post ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { nav.navigate("community_post/${post.id}") }, shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(50), color = if (post.type == "sell") Color(0xFFFFF3E0) else Color(0xFFE8F5E9)) {
                                Text(typeLabel(post.type, S), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = if (post.type == "sell") Color(0xFFE65100) else OojooTheme.GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(post.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = OojooTheme.Ink)
                            if (post.type == "sell" && (post.price ?: 0) > 0) Text(won(post.price ?: 0), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                        }
                        Text(listOfNotNull(post.crop, post.quantity).joinToString(" · ").ifBlank { post.description ?: "" }, color = OojooTheme.Muted, fontSize = 12.sp)
                        // 식물카드 배지 — 원본 식물 + 성장스토리 동영상 유무
                        if (post.plant_name != null || post.video_url != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                post.plant_name?.let { pn ->
                                    Surface(
                                        shape = OojooTheme.PillShape,
                                        color = OojooTheme.GreenBg,
                                        border = BorderStroke(1.dp, OojooTheme.GreenDark)
                                    ) {
                                        Text("🌱 $pn", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = OojooTheme.GreenDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (post.video_url != null) {
                                    Surface(
                                        shape = OojooTheme.PillShape,
                                        color = OojooTheme.Yellow,
                                        border = BorderStroke(1.dp, OojooTheme.Amber)
                                    ) {
                                        Text("🎬 ${S.feedVideoBadge}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = OojooTheme.Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Row {
                            Text("${post.image ?: "🙂"} ${post.author_name ?: S.neighbor} · 📍 ${post.region ?: "-"} · ⭐${post.author_score} (${post.author_deals})", color = OojooTheme.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            statusLabel(post.status, S).takeIf { it.isNotBlank() }?.let { Text(it, color = OojooTheme.Red, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

class PostDetailViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var detail by mutableStateOf<CommunityPostDetail?>(null)
    var comment by mutableStateOf("")
    var msg by mutableStateOf<String?>(null)
    fun load(id: String) { viewModelScope.launch { try { detail = api.communityPost(id) } catch (e: Exception) { msg = e.message } } }
    fun sendComment(id: String) { val b = comment.trim(); if (b.isEmpty()) return; viewModelScope.launch { try { api.communityComment(id, CommentRequest(Session.userId, b)); comment = ""; load(id) } catch (e: Exception) { msg = e.message } } }
    fun setStatus(id: String, status: String) { viewModelScope.launch { try { api.communityStatus(id, StatusRequest(status)); load(id); msg = status } catch (e: Exception) { msg = e.message } } }
    fun report(id: String, targetUser: String?) { viewModelScope.launch { try { api.communityReport(ReportRequest(Session.userId, id, targetUser, "부적절")); msg = "신고 접수됨" } catch (e: Exception) { msg = e.message } } }
    fun block(targetUser: String) { viewModelScope.launch { try { api.communityBlock(BlockRequest(Session.userId, targetUser)); msg = "차단했습니다 (피드에서 숨김)" } catch (e: Exception) { msg = e.message } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPostScreen(nav: NavController, postId: String, vm: PostDetailViewModel = viewModel()) {
    val S = LocalAppStrings.current
    LaunchedEffect(postId) { vm.load(postId) }
    val d = vm.detail
    Scaffold(topBar = { TopAppBar(title = { Text(S.postTitle, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                if (vm.msg != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("⚠️", fontSize = 32.sp)
                        Text(vm.msg ?: "", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        OutlineButton(text = stringResource(R.string.retry), onClick = { vm.load(postId) })
                    }
                } else {
                    CircularProgressIndicator(color = OojooTheme.Green)
                }
            }
            return@Scaffold
        }
        val post = d.post
        val isAuthor = post.user_id == Session.userId
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(50), color = if (post.type == "sell") Color(0xFFFFF3E0) else Color(0xFFE8F5E9)) { Text(typeLabel(post.type, S), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = if (post.type == "sell") Color(0xFFE65100) else OojooTheme.GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text(post.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), color = OojooTheme.Ink)
                    }
                    if (post.type == "sell" && (post.price ?: 0) > 0) Text(won(post.price ?: 0), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("${S.cropInfoLabel} ${post.crop ?: "-"} · ${S.quantityInfoLabel} ${post.quantity ?: "-"}", color = OojooTheme.Muted, fontSize = 14.sp)
                    Text("${S.regionLabel}: ${post.region ?: "-"}", color = OojooTheme.Muted, fontSize = 13.sp)
                    post.description?.let { Text(it, color = OojooTheme.Ink, fontSize = 14.sp) }
                    Text("${post.image ?: "🙂"} ${post.author_name ?: S.neighbor} · ⭐${post.author_score} (${post.author_deals}) · ${statusLabel(post.status, S).ifBlank { S.available }}", color = OojooTheme.Muted, fontSize = 11.sp)
                }
            }
            // 성장스토리 동영상 (식물카드 — growth 클립)
            post.video_url?.let { vurl ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("🎬 ${S.feedVideoBadge}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                        AndroidView(
                            factory = { c ->
                                android.widget.VideoView(c).apply {
                                    setVideoPath(ApiClient.baseUrl.trimEnd('/') + vurl)
                                    setOnPreparedListener { it.isLooping = true; start() }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                        )
                    }
                }
            }
            if (isAuthor) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(text = S.reserved, onClick = { vm.setStatus(postId, "reserved") }, modifier = Modifier.weight(1f))
                    GradientButton(text = S.tradedone, onClick = { vm.setStatus(postId, "done") }, modifier = Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(text = S.reportAction, onClick = { vm.report(postId, post.user_id) }, modifier = Modifier.weight(1f))
                    OutlineButton(text = S.blockAction, onClick = { post.user_id?.let { vm.block(it) } }, modifier = Modifier.weight(1f))
                }
            }
            Text("${S.comments} (${d.comments.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink)
            d.comments.forEach { c ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.author_name ?: S.neighbor, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = OojooTheme.Ink)
                        Text(c.body, color = OojooTheme.Ink, fontSize = 14.sp)
                        c.created_at?.let { Text(it, color = OojooTheme.Muted, fontSize = 11.sp) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.comment, { vm.comment = it }, label = { Text(S.addComment) }, singleLine = true, shape = OojooTheme.FieldShape, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Line), modifier = Modifier.weight(1f))
                GradientButton(text = S.submit, onClick = { vm.sendComment(postId) })
            }
            vm.msg?.let { Text(statusLabel(it, S).ifBlank { localizeMasterMessage(it, S) }, color = OojooTheme.Green, fontSize = 13.sp) }
        }
    }
}

class WritePostViewModel(app: Application) : AndroidViewModel(app) {
    private val api get() = ApiClient.api
    var type by mutableStateOf("share")
    var title by mutableStateOf("")
    var crop by mutableStateOf("")
    var quantity by mutableStateOf("")
    var price by mutableStateOf("")
    var description by mutableStateOf("")
    var image by mutableStateOf("🥬")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    // 장소 — 기본은 farmer(농장) 위치. master가 이동하면 "내 위치"로 변경.
    var farmSlave: Slave? by mutableStateOf(null) // 위치정보 가진 첫 farmer
    var locMine by mutableStateOf(false)          // true = 내 현재 위치 사용
    var locBusy by mutableStateOf(false)
    var locOk: String? by mutableStateOf(null)    // "Seoul (gps)"
    var locFail by mutableStateOf(false)
    private var postRegion = Session.region
    private var postLat: Double? = null
    private var postLon: Double? = null

    init {
        viewModelScope.launch {
            try {
                val slaves = api.slaves(Session.userId).slaves
                // region 또는 좌표가 있는 첫 farmer — 그 위치가 기본 장소
                farmSlave = slaves.firstOrNull { !it.region.isNullOrBlank() || (it.lat != null && it.lon != null) }
                applyFarmLocation()
            } catch (_: Exception) {}
        }
    }

    private fun applyFarmLocation() {
        val s = farmSlave ?: return
        postRegion = s.region?.takeIf { it.isNotBlank() } ?: Session.region
        // 좌표는 둘 다 있어야 반경 검색에 쓰인다 — 한쪽만 있으면 안 넘김
        postLat = if (s.lat != null && s.lon != null) s.lat else null
        postLon = if (s.lat != null && s.lon != null) s.lon else null
    }

    fun selectFarmLocation() {
        locMine = false; locOk = null; locFail = false
        applyFarmLocation()
    }

    /** 내 현재 위치(GPS→Network→IP) + 역지리 region — master가 이동한 경우. */
    fun selectMyLocation() {
        locMine = true; locBusy = true; locOk = null; locFail = false
        viewModelScope.launch {
            try {
                val loc = LocationHelper.resolve(getApplication()) ?: throw Exception("no location")
                val w = api.weatherByCoords(loc.lat, loc.lon)
                postRegion = w.region.ifBlank { Session.region }
                postLat = loc.lat
                postLon = loc.lon
                locOk = "$postRegion (${loc.source})"
            } catch (_: Exception) {
                locFail = true // 이전 장소(농장 등) 그대로 유지
            }
            locBusy = false
        }
    }

    fun submit(onDone: () -> Unit) {
        if (title.isBlank()) { error = "제목을 입력하세요"; return }
        if (locBusy) return
        loading = true; error = null
        viewModelScope.launch {
            try {
                api.communityCreate(CreatePostRequest(Session.userId, type, title.trim(), crop.trim().ifBlank { null }, quantity.trim().ifBlank { null }, if (type == "sell") price.trim().toIntOrNull() else null, postRegion, description.trim().ifBlank { null }, image, lat = postLat, lon = postLon))
                onDone()
            } catch (e: Exception) { error = e.message ?: "등록 실패" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityWriteScreen(nav: NavController, vm: WritePostViewModel = viewModel()) {
    val S = LocalAppStrings.current
    val emojis = listOf("🥬", "🍅", "🌿", "🌱", "🥕", "🌶️", "🍓", "🙂")
    Scaffold(topBar = { TopAppBar(title = { Text(S.writePost, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(S.typeLabelGeneric, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OojooChip(vm.type == "share", { vm.type = "share" }, S.share)
                OojooChip(vm.type == "sell", { vm.type = "sell" }, S.sell)
                OojooChip(vm.type == "buy", { vm.type = "buy" }, S.buy)
            }
            Text(S.titleField, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.title, { vm.title = it }, S.titlePh)
            Text(S.cropLabel, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.crop, { vm.crop = it }, S.cropPh)
            Text(S.quantity, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.quantity, { vm.quantity = it }, S.quantityPh)
            if (vm.type == "sell") { Text(S.priceWon, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted); OojooField(vm.price, { vm.price = it.filter { c -> c.isDigit() } }, "5000") }
            Text(S.description, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            OojooField(vm.description, { vm.description = it }, S.descriptionPh)
            Text(S.emoji, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                emojis.forEach { e -> OojooChip(vm.image == e, { vm.image = e }, e) }
            }
            // 장소 — 기본은 농장(farmer 위치), master가 이동하면 내 위치로 변경
            Text(S.writeLocationLabel, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OojooChip(!vm.locMine, { vm.selectFarmLocation() }, "🚜 ${S.writeLocationFarm}")
                OojooChip(vm.locMine, { vm.selectMyLocation() }, "📍 ${S.writeLocationMe}")
            }
            when {
                vm.locBusy -> Text(S.writeLocationResolving, color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                vm.locFail -> Text(S.writeLocationFail, color = OojooTheme.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                vm.locOk != null -> Text("📍 ${vm.locOk}", color = OojooTheme.GreenDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                vm.farmSlave != null -> Text("🌱 ${S.writeLocationFarmOf.format(vm.farmSlave!!.name)}", color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                else -> Text(S.writeLocationNoFarmer, color = OojooTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            GradientButton(text = S.postButton, onClick = { vm.submit { nav.navigateUp() } }, enabled = !vm.loading && !vm.locBusy && vm.title.isNotBlank(), modifier = Modifier.fillMaxWidth())
            vm.error?.let { Text("⚠️ ${localizeMasterMessage(it, S)}", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
