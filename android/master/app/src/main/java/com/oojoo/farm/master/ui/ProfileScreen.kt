package com.oojoo.farm.master.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import com.oojoo.farm.master.model.ProfileUpdateRequest
import com.oojoo.farm.master.model.User
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class ProfileViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var user by mutableStateOf<User?>(null)
    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var nickname by mutableStateOf(Session.nickname)
    var region by mutableStateOf(Session.region)
    var interests by mutableStateOf<List<String>>(emptyList())
    var newInterest by mutableStateOf("")

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                val u = api.user(userId)
                user = u
                nickname = u.nickname?.ifBlank { null } ?: Session.nickname
                region = u.region?.ifBlank { null } ?: Session.region
                interests = u.interests ?: emptyList()
            } catch (_: Exception) {}
            loading = false
        }
    }

    fun addInterest() {
        val v = newInterest.trim()
        if (v.isEmpty() || v.length > 50 || interests.size >= 20) return
        if (interests.any { it.equals(v, ignoreCase = true) }) { newInterest = ""; return }
        interests = interests + v
        newInterest = ""
    }

    fun removeInterest(idx: Int) { interests = interests.filterIndexed { i, _ -> i != idx } }

    fun save() {
        if (nickname.isBlank()) { msg = "닉네임을 입력하세요"; return }
        saving = true
        viewModelScope.launch {
            try {
                api.updateProfile(userId, ProfileUpdateRequest(
                    nickname = nickname.trim(),
                    region = region.trim().ifBlank { null },
                    interests = interests
                ))
                Session.nickname = nickname.trim()
                if (region.isNotBlank()) Session.region = region.trim()
                msg = "프로필이 저장되었습니다"
                load()
            } catch (e: Exception) { msg = e.message }
            saving = false
        }
    }

    init { load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(nav: NavController, vm: ProfileViewModel = viewModel()) {
    val S = LocalAppStrings.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.profile_title), color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (vm.loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp) }
            } else {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).border(2.dp, OojooTheme.Ink, RoundedCornerShape(50)).background(OojooTheme.GreenBg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = OojooTheme.GreenDark, modifier = Modifier.size(36.dp))
                        }
                        Text(vm.user?.email?.ifBlank { null } ?: "🌱 ${vm.nickname.ifBlank { S.neighbor }}", color = OojooTheme.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text("${stringResource(R.string.profile_region_label)}: ${vm.region.ifBlank { S.unknown }} · ${vm.user?.plan?.uppercase() ?: "FREE"}", color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.profile_nickname_label), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        OojooField(vm.nickname, { vm.nickname = it.take(30) }, "")
                        Text(stringResource(R.string.profile_region_label), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        OojooField(vm.region, { vm.region = it.take(30) }, "")
                    }
                }

                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.profile_interests_label), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (vm.interests.isEmpty()) {
                            Text(stringResource(R.string.profile_interests_empty), color = OojooTheme.Muted, fontSize = 13.sp)
                        } else {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                vm.interests.forEachIndexed { idx, interest ->
                                    Surface(shape = RoundedCornerShape(50), color = OojooTheme.GreenBg, border = androidx.compose.foundation.BorderStroke(1.dp, OojooTheme.Ink)) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("🌱 $interest", color = OojooTheme.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                            TextButton(onClick = { vm.removeInterest(idx) }, contentPadding = PaddingValues(4.dp)) { Text("×", color = OojooTheme.Red, fontWeight = FontWeight.Bold) }
                                        }
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OojooField(vm.newInterest, { vm.newInterest = it.take(50) }, stringResource(R.string.profile_interest_ph), modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.addInterest() }) { Text(stringResource(R.string.profile_interest_add), color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }

                GradientButton(text = S.save, onClick = { vm.save() }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth())
                vm.msg?.let { Text(localizeMasterMessage(it, S), fontSize = 13.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
