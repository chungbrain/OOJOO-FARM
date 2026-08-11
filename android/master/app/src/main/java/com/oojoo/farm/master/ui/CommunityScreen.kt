package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.AppStrings
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

private fun typeLabel(t: String?, S: AppStrings) = when (t) { "share" -> S.share; "sell" -> S.sell; "buy" -> S.buy; else -> t ?: "" }
private fun statusLabel(s: String?, S: AppStrings) = when (s) { "reserved" -> S.reserved; "done" -> S.tradedone; else -> "" }

class CommunityViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
    var type by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    init { load() }
    fun load() {
        loading = true
        viewModelScope.launch {
            try { posts = api.communityPosts(Session.region, type, query.takeIf { it.isNotBlank() }, Session.userId).posts } catch (_: Exception) {}
            loading = false
        }
    }
    fun selectType(t: String?) { type = t; load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(nav: NavController, vm: CommunityViewModel = viewModel()) {
    val S = LocalAppStrings.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("${S.neighbor} · ${Session.region}", color = Color.White, fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = { nav.navigate("community_write") }) { Text(S.writePost, color = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("community_write") }, containerColor = OojooTheme.Green, contentColor = Color.White) { Icon(Icons.Default.Add, contentDescription = S.writePost) } },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OojooField(vm.query, { vm.query = it }, S.searchCrop) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OojooChip(vm.type == null, { vm.selectType(null) }, S.all)
                    OojooChip(vm.type == "share", { vm.selectType("share") }, S.share)
                    OojooChip(vm.type == "sell", { vm.selectType("sell") }, S.sell)
                    OojooChip(vm.type == "buy", { vm.selectType("buy") }, S.buy)
                }
            }
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) } }
            if (vm.posts.isEmpty() && !vm.loading) {
                item { Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) { Text("${Session.region}${S.noPostsSuffix}", Modifier.padding(24.dp), color = OojooTheme.Ink) } }
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
                        Row {
                            Text("${post.image ?: "🙂"} ${post.author_name ?: S.neighbor} · ⭐${post.author_score} (${post.author_deals})", color = OojooTheme.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
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
        if (d == null) { Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OojooTheme.Green) }; return@Scaffold }
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
                api.communityCreate(CreatePostRequest(Session.userId, type, title.trim(), crop.trim().ifBlank { null }, quantity.trim().ifBlank { null }, if (type == "sell") price.trim().toIntOrNull() else null, Session.region, description.trim().ifBlank { null }, image))
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
    Scaffold(topBar = { TopAppBar(title = { Text("${S.writePost} · ${Session.region}", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
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
            GradientButton(text = S.postButton, onClick = { vm.submit { nav.navigateUp() } }, enabled = !vm.loading && vm.title.isNotBlank(), modifier = Modifier.fillMaxWidth())
            vm.error?.let { Text("⚠️ ${localizeMasterMessage(it, S)}", color = OojooTheme.Red, fontSize = 13.sp) }
        }
    }
}
