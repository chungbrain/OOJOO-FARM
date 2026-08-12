package com.oojoo.farm.master.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.LocationHelper
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.LoginRequest
import com.oojoo.farm.master.model.RegisterRequest
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val S = LocalAppStrings.current

    // string resources (auth 관련)
    val sTabSignup = stringResource(R.string.auth_tab_signup)
    val sTabLogin = stringResource(R.string.auth_tab_login)
    val sEmail = stringResource(R.string.auth_email)
    val sEmailPh = stringResource(R.string.auth_email_ph)
    val sPw = stringResource(R.string.auth_pw)
    val sPwPh = stringResource(R.string.auth_pw_ph)
    val sPwConfirm = stringResource(R.string.auth_pw_confirm)
    val sPwMismatch = stringResource(R.string.auth_pw_mismatch)
    val sSubmitSignup = stringResource(R.string.auth_submit_signup)
    val sSubmitLogin = stringResource(R.string.auth_submit_login)
    val sFailInvalidEmail = stringResource(R.string.auth_fail_invalid_email)
    val sFailShortPw = stringResource(R.string.auth_fail_short_pw)
    val sFailEmailUsed = stringResource(R.string.auth_fail_email_used)
    val sFailInvalidCreds = stringResource(R.string.auth_fail_invalid_creds)
    val sFailServer = stringResource(R.string.auth_fail_server)
    val sServerUrlHint = stringResource(R.string.server_url_hint)
    val sServerReconnect = stringResource(R.string.server_reconnect_action)

    // 인증 모드 (login / signup)
    var mode by remember { mutableStateOf("signup") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }

    // 서버 주소 (한 번 가입/로그인에 실패하면 직접 편집 가능)
    var serverUrl by remember { mutableStateOf(Prefs.serverUrl(ctx)) }
    var serverMsg by remember { mutableStateOf<String?>(null) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 위치 권한 거부되면 서울 폴백 */ }

    LaunchedEffect(Unit) {
        if (!LocationHelper.hasLocationPermission(ctx)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("🎨 ${S.appName}", color = Color.White, fontWeight = FontWeight.Black) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 헤더
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌻", fontSize = 60.sp)
                Spacer(Modifier.height(8.dp))
                Text(S.hello, fontWeight = FontWeight.Black, fontSize = 24.sp, color = OojooTheme.Ink)
                Spacer(Modifier.height(4.dp))
                Text(S.welcomeSubtitle, color = OojooTheme.Muted, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))

            // 모드 탭
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("signup" to sTabSignup, "login" to sTabLogin).forEach { (key, label) ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (mode == key) OojooTheme.Green else OojooTheme.Card,
                        border = OojooTheme.BorderThin,
                        modifier = Modifier.weight(1f).clickable { mode = key; error = null }
                    ) {
                        Text(
                            label,
                            Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = if (mode == key) Color.White else OojooTheme.Muted,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 이메일
            Text(sEmail, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                placeholder = { Text(sEmailPh, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) },
                singleLine = true,
                shape = OojooTheme.FieldShape,
                leadingIcon = { Icon(Icons.Default.Mail, contentDescription = null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OojooTheme.Green,
                    unfocusedBorderColor = OojooTheme.Ink,
                    focusedContainerColor = OojooTheme.Card,
                    unfocusedContainerColor = OojooTheme.Card
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 비밀번호
            Text(sPw, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                placeholder = { Text(sPwPh, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) },
                singleLine = true,
                shape = OojooTheme.FieldShape,
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    TextButton(onClick = { showPw = !showPw }) {
                        Text(if (showPw) "🙈" else "👁️", fontSize = 16.sp)
                    }
                },
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OojooTheme.Green,
                    unfocusedBorderColor = OojooTheme.Ink,
                    focusedContainerColor = OojooTheme.Card,
                    unfocusedContainerColor = OojooTheme.Card
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // signup 모드 추가 필드: 비밀번호 확인 + 닉네임
            if (mode == "signup") {
                Text(sPwConfirm, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = passwordConfirm,
                    onValueChange = { passwordConfirm = it; error = null },
                    placeholder = { Text(sPwPh, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    shape = OojooTheme.FieldShape,
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OojooTheme.Green,
                        unfocusedBorderColor = OojooTheme.Ink,
                        focusedContainerColor = OojooTheme.Card,
                        unfocusedContainerColor = OojooTheme.Card
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(S.nickname, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it; error = null },
                    placeholder = { Text(S.nicknamePh, color = OojooTheme.Muted, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    shape = OojooTheme.FieldShape,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OojooTheme.Green,
                        unfocusedBorderColor = OojooTheme.Ink,
                        focusedContainerColor = OojooTheme.Card,
                        unfocusedContainerColor = OojooTheme.Card
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 서버 주소 (펼침 — 언제나 노출)
            Text(S.serverAddress, style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted, fontWeight = FontWeight.ExtraBold)
            OojooField(serverUrl, { serverUrl = it; serverMsg = null }, sServerUrlHint)
            TextButton(onClick = {
                val normalized = serverUrl.trim().ifBlank { Prefs.serverUrl(ctx) }
                Prefs.setServerUrl(ctx, normalized)
                ApiClient.setBaseUrl(normalized)
                serverMsg = "✅ $normalized"
            }, modifier = Modifier.fillMaxWidth()) {
                Text(sServerReconnect, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold)
            }
            serverMsg?.let { Text(it, fontSize = 12.sp, color = OojooTheme.GreenDark, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(8.dp))

            // 제출 버튼
            GradientButton(
                text = if (mode == "signup") sSubmitSignup else sSubmitLogin,
                onClick = {
                    error = null
                    // 클라이언트 검증
                    if (email.isBlank() || !email.contains("@")) {
                        error = sFailInvalidEmail; return@GradientButton
                    }
                    if (password.length < 4) {
                        error = sFailShortPw; return@GradientButton
                    }
                    if (mode == "signup" && password != passwordConfirm) {
                        error = sPwMismatch; return@GradientButton
                    }
                    loading = true
                    Prefs.setServerUrl(ctx, serverUrl.trim())
                    ApiClient.setBaseUrl(serverUrl.trim())
                    scope.launch {
                        try {
                            val user = if (mode == "signup") {
                                // 위치 감지 후 region 설정
                                var detectedRegion = Prefs.region(ctx)
                                try {
                                    val loc = LocationHelper.resolve(ctx)
                                    if (loc != null) {
                                        val w = ApiClient.api.weatherByCoords(loc.lat, loc.lon)
                                        detectedRegion = w.region
                                        Prefs.setRegion(ctx, w.region)
                                    }
                                } catch (_: Exception) { /* 서울 폴백 */ }
                                ApiClient.api.registerUser(
                                    RegisterRequest(
                                        email = email.trim(),
                                        password = password,
                                        nickname = nickname.trim().ifBlank { null },
                                        region = detectedRegion
                                    )
                                )
                            } else {
                                ApiClient.api.loginUser(LoginRequest(email.trim(), password))
                            }
                            // 성공 → 계정 저장 + 세션 설정
                            Prefs.saveAuthAccount(
                                ctx,
                                userId = user.id,
                                email = user.email,
                                nickname = user.nickname,
                                region = user.region
                            )
                            Session.setAuth(user.id, user.email, user.nickname, user.region)
                            nav.navigate("home") { popUpTo("auth") { inclusive = true } }
                        } catch (e: retrofit2.HttpException) {
                            val code = e.code()
                            error = when (code) {
                                409 -> sFailEmailUsed
                                401, 403 -> sFailInvalidCreds
                                400 -> sFailInvalidEmail
                                else -> "$sFailServer (HTTP $code)"
                            }
                        } catch (e: Exception) {
                            error = "$sFailServer: ${e.message ?: ""}"
                        }
                        loading = false
                    }
                },
                enabled = !loading && email.isNotBlank() && password.isNotBlank() &&
                    (mode == "login" || passwordConfirm.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            )

            if (loading) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = OojooTheme.Green)
                }
            }
            error?.let {
                Text("⚠️ $it", color = OojooTheme.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(S.autoLocationNotice, color = OojooTheme.Muted2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}