package com.oojoo.farm.master.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
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
import com.oojoo.farm.master.data.LocalAppStrings
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
    val S = LocalAppStrings.current
    Scaffold(topBar = { TopAppBar(title = { Text(S.subscriptionPlan, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            vm.current?.let { Text("${S.currentPlanPrefix}${it.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            vm.plans.forEach { plan ->
                val isCurrent = vm.current?.plan == plan.plan
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(plan.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = OojooTheme.Ink)
                            Text(if (plan.price == 0) S.free else "₩${"%,d".format(plan.price)}${S.perMonth}", color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("${S.farmerRegistrationPrefix}${if (plan.maxFarmers >= 999) S.unlimited else "${plan.maxFarmers}${S.farmerRegistrationUnit}"}", color = OojooTheme.Muted, fontSize = 14.sp)
                        Text("• ${S.detailedReport}: ${if (plan.detailedReport) S.provided else S.notProvided}", color = OojooTheme.Muted, fontSize = 14.sp)
                        Text("• ${S.priorityCs}: ${if (plan.priorityCs) S.provided else S.notProvided}", color = OojooTheme.Muted, fontSize = 14.sp)
                        if (isCurrent) OutlineButton(text = S.inUse, onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                        else GradientButton(text = if (plan.price == 0) S.freeConvert else S.subscribe, onClick = { vm.subscribe(plan.plan) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            vm.msg?.let { Text(localizeMasterMessage(it, S), color = OojooTheme.Green, fontSize = 13.sp) }
            Text(S.paymentDemoNotice, color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}
