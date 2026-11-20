package com.oojoo.farm.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.Conversation
import com.oojoo.farm.master.model.MessageIdResponse
import com.oojoo.farm.master.model.SendMessageRequest
import com.oojoo.farm.master.model.ThreadMessage
import com.oojoo.farm.master.model.User
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 대화 목록 — 5초 폴링 (FCM 없음, 결정 로그 참조). */
class MessagesViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    private var pollJob: Job? = null

    val totalUnread: Int get() = conversations.sumOf { it.unread }

    fun start() {
        refresh()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                try { conversations = api.conversations(Session.userId).conversations } catch (_: Exception) {}
            }
        }
    }

    fun refresh() {
        loading = true
        viewModelScope.launch {
            try { conversations = api.conversations(Session.userId).conversations } catch (e: Exception) { msg = e.message }
            loading = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}

/** 스레드 — 3초 폴링 + 안 읽음 표시. */
class ThreadViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var peer by mutableStateOf<User?>(null)
    var messages by mutableStateOf<List<ThreadMessage>>(emptyList())
    var loading by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var draft by mutableStateOf("")
    var msg by mutableStateOf<String?>(null)
    private var peerId: String = ""
    private var pollJob: Job? = null

    fun start(peerId: String) {
        if (this.peerId == peerId && pollJob?.isActive == true) return
        this.peerId = peerId
        loading = true
        messages = emptyList()
        viewModelScope.launch {
            try {
                peer = api.user(peerId)
                messages = api.messageThread(Session.userId, peerId).messages
                api.markMessagesRead(com.oojoo.farm.master.model.MessagesReadRequest(Session.userId, peerId))
            } catch (_: Exception) {}
            loading = false
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                try {
                    val r = api.messageThread(Session.userId, peerId)
                    if (r.messages.size != messages.size) {
                        messages = r.messages
                        api.markMessagesRead(com.oojoo.farm.master.model.MessagesReadRequest(Session.userId, peerId))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty() || body.length > 2000) return
        sending = true
        viewModelScope.launch {
            try {
                api.sendMessage(SendMessageRequest(Session.userId, peerId, body))
                draft = ""
                messages = api.messageThread(Session.userId, peerId).messages
            } catch (e: Exception) { msg = e.message }
            sending = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(nav: NavController, vm: MessagesViewModel = viewModel()) {
    val S = LocalAppStrings.current
    LaunchedEffect(Unit) { vm.start() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.messages_title), color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.loading && vm.conversations.isEmpty()) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) } }
            }
            if (vm.conversations.isEmpty() && !vm.loading) {
                item {
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("💬", fontSize = 56.sp); Spacer(Modifier.height(14.dp))
                            Text(stringResource(R.string.messages_empty), color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            items(vm.conversations) { c ->
                val peerName = rememberPeerName(c.peerId)
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffsetSm, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clickable {
                    nav.navigate("message_thread/${c.peerId}/${android.net.Uri.encode(peerName)}")
                }, shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(OojooTheme.GreenBg), contentAlignment = Alignment.Center) {
                            Text("💬", fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peerName, fontWeight = FontWeight.ExtraBold, color = OojooTheme.Ink, fontSize = 14.sp)
                            c.lastMessage?.let { Text(if (it.fromMe) "나: ${it.body}" else it.body, color = OojooTheme.Muted, fontSize = 12.sp, maxLines = 1) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            c.lastAt?.let { Text(it, color = OojooTheme.Muted2, fontSize = 10.sp) }
                            if (c.unread > 0) {
                                Box(Modifier.clip(RoundedCornerShape(50)).background(OojooTheme.Red).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                    Text("${c.unread}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            item { TextButton(onClick = { vm.refresh() }) { Text(S.refresh, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) } }
        }
    }
}

/** peerId → 닉네임 (로컬 캐시, 실패 시 ID 앞자). 스레드 진입 시 정확한 이름은 스레드 화면이 다시 조회한다. */
private val peerNameCache = mutableMapOf<String, String>()

@Composable
fun rememberPeerName(peerId: String): String {
    var name by remember(peerId) { mutableStateOf(peerNameCache[peerId] ?: "👤 ${peerId.take(6)}") }
    LaunchedEffect(peerId) {
        if (peerNameCache[peerId] == null) {
            try {
                val u = ApiClient.api.user(peerId)
                peerNameCache[peerId] = u.nickname?.ifBlank { null } ?: "👤 ${peerId.take(6)}"
                name = peerNameCache[peerId]!!
            } catch (_: Exception) {}
        }
    }
    return name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(nav: NavController, peerId: String, peerName: String, vm: ThreadViewModel = viewModel()) {
    val S = LocalAppStrings.current
    LaunchedEffect(peerId) { vm.start(peerId) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("💬 ${vm.peer?.nickname?.ifBlank { null } ?: peerName}", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                if (vm.loading && vm.messages.isEmpty()) {
                    item { Row(Modifier.fillMaxWidth().padding(40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) } }
                }
                if (vm.messages.isEmpty() && !vm.loading) {
                    item { Text(stringResource(R.string.message_thread_empty), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                items(vm.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (m.fromMe) OojooTheme.Green else OojooTheme.Card)
                                .border(2.dp, OojooTheme.Ink, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Text(m.body, color = if (m.fromMe) Color.White else OojooTheme.Ink, fontSize = 14.sp)
                                m.created_at?.let {
                                    Text(it, color = if (m.fromMe) Color.White.copy(alpha = 0.7f) else OojooTheme.Muted2, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
            vm.msg?.let { Text(localizeMasterMessage(it, S), color = OojooTheme.Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.draft,
                    onValueChange = { vm.draft = it.take(2000) },
                    placeholder = { Text(stringResource(R.string.message_draft_ph), color = OojooTheme.Muted) },
                    singleLine = true,
                    shape = OojooTheme.PillShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = OojooTheme.Green, unfocusedBorderColor = OojooTheme.Ink),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.send() }, enabled = !vm.sending && vm.draft.isNotBlank()) {
                    Text(stringResource(R.string.message_send), color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
