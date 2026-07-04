package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.SubscriptionResponse
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

class SubscriptionViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var current by mutableStateOf<SubscriptionResponse?>(null)
    var plans by mutableStateOf<List<SubscriptionResponse>>(emptyList())
    var msg by mutableStateOf<String?>(null)

    init { load() }
    fun load() {
        viewModelScope.launch {
            try { plans = api.subscriptionPlans().plans } catch (_: Exception) {}
            try { current = api.subscription(Session.userId) } catch (_: Exception) {}
        }
    }
    fun subscribe(plan: String) {
        viewModelScope.launch {
            try { current = api.subscribe(com.oojoo.farm.master.model.SubscribeRequest(Session.userId, plan)); msg = "‘${current?.name}’ 플랜으로 변경되었습니다" }
            catch (e: Exception) { msg = e.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(nav: NavController, vm: SubscriptionViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("구독 플랜") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.current?.let { Text("현재 플랜: ${it.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            vm.plans.forEach { plan ->
                val isCurrent = vm.current?.plan == plan.plan
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(plan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(if (plan.price == 0) "무료" else "₩${"%,d".format(plan.price)}/월", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text("• Farmer 등록: ${if (plan.maxFarmers >= 999) "무제한" else "${plan.maxFarmers}대"}", style = MaterialTheme.typography.bodyMedium)
                        Text("• 상세 리포트: ${if (plan.detailedReport) "제공" else "미제공"}", style = MaterialTheme.typography.bodyMedium)
                        Text("• 우선 CS: ${if (plan.priorityCs) "제공" else "미제공"}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { vm.subscribe(plan.plan) }, enabled = !isCurrent, modifier = Modifier.fillMaxWidth()) {
                            Text(if (isCurrent) "이용 중" else if (plan.price == 0) "무료 전환" else "구독하기")
                        }
                    }
                }
            }
            vm.msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("결제는 데모(시뮬레이션)입니다.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
