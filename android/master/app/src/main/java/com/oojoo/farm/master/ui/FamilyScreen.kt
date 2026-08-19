package com.oojoo.farm.master.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.HouseholdAcceptRequest
import com.oojoo.farm.master.model.HouseholdInviteRequest
import com.oojoo.farm.master.model.HouseholdLeaveRequest
import com.oojoo.farm.master.model.HouseholdMember
import com.oojoo.farm.master.model.HouseholdResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class FamilyViewModel : ViewModel() {
    private val api get() = ApiClient.api
    val userId get() = Session.userId
    var household by mutableStateOf<HouseholdResponse?>(null)
    var loading by mutableStateOf(false)
    var msg by mutableStateOf<String?>(null)
    var lastCode by mutableStateOf<String?>(null)

    fun refresh() {
        if (userId.isBlank()) return
        loading = true
        viewModelScope.launch {
            try {
                household = api.household(userId)
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
            loading = false
        }
    }

    fun invite(email: String?) {
        viewModelScope.launch {
            try {
                val res = api.householdInvite(HouseholdInviteRequest(userId, email?.ifBlank { null }))
                lastCode = res.code
                household = api.household(userId)
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    fun accept(code: String) {
        viewModelScope.launch {
            try {
                household = api.householdAccept(HouseholdAcceptRequest(userId, code.trim().uppercase()))
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    fun leave() {
        viewModelScope.launch {
            try {
                api.householdLeave(HouseholdLeaveRequest(userId))
                household = api.household(userId)
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    fun remove(memberId: String) {
        viewModelScope.launch {
            try {
                household = api.householdRemoveMember(memberId, userId)
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    fun revoke(code: String) {
        viewModelScope.launch {
            try {
                household = api.householdRevokeInvite(code, userId)
                msg = null
            } catch (e: Exception) {
                msg = e.message
            }
        }
    }

    init { refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(nav: NavController, vm: FamilyViewModel = viewModel()) {
    val ctx = LocalContext.current
    val hh = vm.household
    val isOwner = hh?.role == "owner"
    var inviteEmail by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CartoonAppBar(
                title = stringResource(R.string.family_title),
                onBack = { nav.popBackStack() }
            )
        },
        containerColor = OojooTheme.Bg
    ) { p ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(p)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.family_desc),
                color = OojooTheme.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )

            if (vm.loading && hh == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = OojooTheme.Green, strokeWidth = 3.dp)
                }
            }

            hh?.let {
                OojooCard(modifier = Modifier.fillMaxWidth()) {
                    Text(it.household.name ?: stringResource(R.string.family_title), fontWeight = FontWeight.Black, fontSize = 18.sp, color = OojooTheme.GreenDark)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(if (isOwner) R.string.family_role_owner else R.string.family_role_member),
                        color = OojooTheme.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    it.members.forEach { m ->
                        MemberRow(
                            member = m,
                            canRemove = isOwner && m.userId != vm.userId,
                            onRemove = { vm.remove(m.userId) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (isOwner) {
                OojooCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.family_invite_title), fontWeight = FontWeight.Black, fontSize = 16.sp, color = OojooTheme.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.family_invite_hint), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OojooField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        placeholder = stringResource(R.string.family_invite_email_ph)
                    )
                    Spacer(Modifier.height(10.dp))
                    GradientButton(
                        text = stringResource(R.string.family_invite_action),
                        onClick = { vm.invite(inviteEmail.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    vm.lastCode?.let { code ->
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.family_invite_code_label), color = OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(code, fontWeight = FontWeight.Black, fontSize = 28.sp, color = OojooTheme.GreenDark)
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("invite", code))
                            vm.msg = ctx.getString(R.string.family_copied)
                        }) {
                            Text(stringResource(R.string.family_copy_code), color = OojooTheme.GreenDark, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    hh?.invites?.forEach { inv ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.code, fontWeight = FontWeight.Black, color = OojooTheme.Ink)
                                inv.invited_email?.let { Text(it, color = OojooTheme.Muted, fontSize = 12.sp) }
                            }
                            TextButton(onClick = { vm.revoke(inv.code) }) {
                                Text(stringResource(R.string.family_revoke), color = OojooTheme.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            OojooCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.family_join_title), fontWeight = FontWeight.Black, fontSize = 16.sp, color = OojooTheme.Ink)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.family_join_hint), color = OojooTheme.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OojooField(
                    value = joinCode,
                    onValueChange = { joinCode = it.uppercase() },
                    placeholder = stringResource(R.string.family_join_code_ph)
                )
                Spacer(Modifier.height(10.dp))
                GradientButton(
                    text = stringResource(R.string.family_join_action),
                    onClick = { if (joinCode.isNotBlank()) vm.accept(joinCode) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (hh != null && !isOwner) {
                OutlineButton(
                    text = stringResource(R.string.family_leave),
                    onClick = { vm.leave() },
                    modifier = Modifier.fillMaxWidth(),
                    color = OojooTheme.Red
                )
            }

            vm.msg?.let {
                Text(it, color = OojooTheme.GreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MemberRow(member: HouseholdMember, canRemove: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, OojooTheme.Ink, RoundedCornerShape(14.dp))
            .background(OojooTheme.GreenBg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .border(2.dp, OojooTheme.Ink, RoundedCornerShape(50))
                .background(OojooTheme.Card),
            contentAlignment = Alignment.Center
        ) { Text("👤", fontSize = 18.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.nickname?.ifBlank { null } ?: member.email ?: member.userId.take(8),
                fontWeight = FontWeight.Bold,
                color = OojooTheme.Ink,
                fontSize = 14.sp
            )
            Text(
                stringResource(if (member.role == "owner") R.string.family_role_owner else R.string.family_role_member),
                color = OojooTheme.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.family_remove), color = OojooTheme.Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}
