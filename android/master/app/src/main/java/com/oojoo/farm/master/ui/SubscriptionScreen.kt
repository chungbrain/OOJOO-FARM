package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.SubscribeRequest
import com.oojoo.farm.master.model.SubscriptionResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class SubscriptionViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var current by mutableStateOf<SubscriptionResponse?>(null)
    var plans by mutableStateOf<List<SubscriptionResponse>>(emptyList())
    var msg by mutableStateOf<String?>(null)
    init { load() }
    fun load() { viewModelScope.launch { try { plans = api.subscriptionPlans().plans } catch (_: Exception) {}; try { current = api.subscription(Session.userId) } catch (_: Exception) {} } }
    fun subscribe(plan: String) { viewModelScope.launch { try { current = api.subscribe(SubscribeRequest(Session.userId, plan)); msg = "'${current?.name}' 플랜으로 변경되었습니다" } catch (e: Exception) { msg = e.message } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(nav: NavController, vm: SubscriptionViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("구독 플랜", color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.current?.let { Text("현재 플랜: ${it.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            vm.plans.forEach { plan ->
                val isCurrent = vm.current?.plan == plan.plan
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.CardElevation, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(plan.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = OojooTheme.Ink)
                            Text(if (plan.price == 0) "무료" else "₩${"%,d".format(plan.price)}/월", color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("• Farmer 등록: ${if (plan.maxFarmers >= 999) "무제한" else "${plan.maxFarmers}대"}", color = OojooTheme.Muted, fontSize = 14.sp)
                        Text("• 상세 리포트: ${if (plan.detailedReport) "제공" else "미제공"}", color = OojooTheme.Muted, fontSize = 14.sp)
                        Text("• 우선 CS: ${if (plan.priorityCs) "제공" else "미제공"}", color = OojooTheme.Muted, fontSize = 14.sp)
                        if (isCurrent) OutlineButton(text = "이용 중", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                        else GradientButton(text = if (plan.price == 0) "무료 전환" else "구독하기", onClick = { vm.subscribe(plan.plan) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            vm.msg?.let { Text(it, color = OojooTheme.Green, fontSize = 13.sp) }
            Text("결제는 데모(시뮬레이션)입니다.", color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}
