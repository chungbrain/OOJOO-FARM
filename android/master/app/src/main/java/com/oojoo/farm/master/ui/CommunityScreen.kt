package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

private fun typeLabel(t: String?) = when (t) { "share" -> "나눔"; "sell" -> "판매"; "buy" -> "구입"; else -> t ?: "" }
private fun statusLabel(s: String?) = when (s) { "reserved" -> "예약중"; "done" -> "거래완료"; else -> "" }

// ============================ 피드 ============================
class CommunityViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
    var type by mutableStateOf<String?>(null)   // null=전체
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)

    init { load() }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                posts = api.communityPosts(
                    region = Session.region,
                    type = type,
                    q = query.takeIf { it.isNotBlank() },
                    viewerId = Session.userId
                ).posts
            } catch (_: Exception) {}
            loading = false
        }
    }

    fun setType(t: String?) { type = t; load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(nav: NavController, vm: CommunityViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("이웃 커뮤니티 · ${Session.region}") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("community_write") }) {
                Icon(Icons.Default.Add, contentDescription = "글쓰기")
            }
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = vm.query, onValueChange = { vm.query = it },
                    label = { Text("작물/제목 검색") }, singleLine = true,
                    trailingIcon = { TextButton(onClick = { vm.load() }) { Text("검색") } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.type == null, { vm.setType(null) }, { Text("전체") })
                    FilterChip(vm.type == "share", { vm.setType("share") }, { Text("나눔") })
                    FilterChip(vm.type == "sell", { vm.setType("sell") }, { Text("판매") })
                    FilterChip(vm.type == "buy", { vm.setType("buy") }, { Text("구입") })
                }
            }
            if (vm.loading) item { CircularProgressIndicator() }
            if (vm.posts.isEmpty() && !vm.loading) {
                item { Card(Modifier.fillMaxWidth()) { Text("${Session.region} 지역에 아직 글이 없습니다.\n첫 나눔/판매 글을 올려보세요!", Modifier.padding(24.dp)) } }
            }
            items(vm.posts) { post ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("community_post/${post.id}") }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(typeLabel(post.type)) })
                            Spacer(Modifier.width(8.dp))
                            Text(post.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (post.type == "sell" && (post.price ?: 0) > 0) Text(won(post.price ?: 0), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text(listOfNotNull(post.crop, post.quantity).joinToString(" · ").ifBlank { post.description ?: "" }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            Text("${post.image ?: "🙂"} ${post.author_name ?: "이웃"} · ⭐${post.author_score} (${post.author_deals}거래)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            statusLabel(post.status).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

// ============================ 상세 ============================
class PostDetailViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var detail by mutableStateOf<CommunityPostDetail?>(null)
    var comment by mutableStateOf("")
    var msg by mutableStateOf<String?>(null)

    fun load(id: String) { viewModelScope.launch { try { detail = api.communityPost(id) } catch (e: Exception) { msg = e.message } } }

    fun sendComment(id: String) {
        val b = comment.trim(); if (b.isEmpty()) return
        viewModelScope.launch {
            try { api.communityComment(id, CommentRequest(Session.userId, b)); comment = ""; load(id) }
            catch (e: Exception) { msg = e.message }
        }
    }
    fun setStatus(id: String, status: String) {
        viewModelScope.launch { try { api.communityStatus(id, StatusRequest(status)); load(id); msg = "상태: ${statusLabel(status).ifBlank { "판매중" }}" } catch (e: Exception) { msg = e.message } }
    }
    fun report(id: String, targetUser: String?) {
        viewModelScope.launch { try { api.communityReport(ReportRequest(Session.userId, id, targetUser, "부적절")); msg = "신고 접수됨" } catch (e: Exception) { msg = e.message } }
    }
    fun block(targetUser: String) {
        viewModelScope.launch { try { api.communityBlock(BlockRequest(Session.userId, targetUser)); msg = "차단했습니다 (피드에서 숨김)" } catch (e: Exception) { msg = e.message } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPostScreen(nav: NavController, postId: String, vm: PostDetailViewModel = viewModel()) {
    LaunchedEffect(postId) { vm.load(postId) }
    val d = vm.detail
    Scaffold(topBar = { TopAppBar(title = { Text("게시물") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        if (d == null) { Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        val post = d.post
        val isAuthor = post.user_id == Session.userId
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = {}, label = { Text(typeLabel(post.type)) })
                        Spacer(Modifier.width(8.dp))
                        Text(post.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    if (post.type == "sell" && (post.price ?: 0) > 0) Text(won(post.price ?: 0), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("작물: ${post.crop ?: "-"}  수량: ${post.quantity ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    Text("지역: ${post.region ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    post.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("${post.image ?: "🙂"} ${post.author_name ?: "이웃"} · ⭐${post.author_score} (${post.author_deals}거래) · ${statusLabel(post.status).ifBlank { "거래 가능" }}", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (isAuthor) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setStatus(postId, "reserved") }, modifier = Modifier.weight(1f)) { Text("예약중") }
                    Button(onClick = { vm.setStatus(postId, "done") }, modifier = Modifier.weight(1f)) { Text("거래완료") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.report(postId, post.user_id) }, modifier = Modifier.weight(1f)) { Text("신고") }
                    OutlinedButton(onClick = { post.user_id?.let { vm.block(it) } }, modifier = Modifier.weight(1f)) { Text("차단") }
                }
            }

            Text("댓글 (${d.comments.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            d.comments.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.author_name ?: "이웃", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text(c.body, style = MaterialTheme.typography.bodyMedium)
                        c.created_at?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.comment, { vm.comment = it }, label = { Text("댓글 달기") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.sendComment(postId) }) { Text("등록") }
            }
            vm.msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ============================ 작성 ============================
class WritePostViewModel : ViewModel() {
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

    fun submit(onDone: () -> Unit) {
        if (title.isBlank()) { error = "제목을 입력하세요"; return }
        loading = true; error = null
        viewModelScope.launch {
            try {
                api.communityCreate(CreatePostRequest(
                    userId = Session.userId, type = type, title = title.trim(),
                    crop = crop.trim().ifBlank { null }, quantity = quantity.trim().ifBlank { null },
                    price = if (type == "sell") price.trim().toIntOrNull() else null,
                    region = Session.region, description = description.trim().ifBlank { null }, image = image
                ))
                onDone()
            } catch (e: Exception) { error = e.message ?: "등록 실패" }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityWriteScreen(nav: NavController, vm: WritePostViewModel = viewModel()) {
    val emojis = listOf("🥬", "🍅", "🌿", "🌱", "🥕", "🌶️", "🍓", "🙂")
    Scaffold(topBar = { TopAppBar(title = { Text("글쓰기 · ${Session.region}") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("유형", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.type == "share", { vm.type = "share" }, { Text("나눔") })
                FilterChip(vm.type == "sell", { vm.type = "sell" }, { Text("판매") })
                FilterChip(vm.type == "buy", { vm.type = "buy" }, { Text("구입") })
            }
            OutlinedTextField(vm.title, { vm.title = it }, label = { Text("제목 *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.crop, { vm.crop = it }, label = { Text("작물 (예: 상추, 토마토)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.quantity, { vm.quantity = it }, label = { Text("수량 (예: 한 봉지, 1kg)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (vm.type == "sell") {
                OutlinedTextField(vm.price, { vm.price = it.filter { c -> c.isDigit() } }, label = { Text("가격 (원)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(vm.description, { vm.description = it }, label = { Text("설명") }, modifier = Modifier.fillMaxWidth())
            Text("대표 이모지", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                emojis.forEach { e -> FilterChip(vm.image == e, { vm.image = e }, { Text(e) }) }
            }
            Button(onClick = { vm.submit { nav.navigateUp() } }, enabled = !vm.loading && vm.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("게시하기")
            }
            vm.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
