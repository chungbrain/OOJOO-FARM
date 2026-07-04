package com.oojoo.farm.master.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Cart
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

fun won(v: Int): String = "₩" + "%,d".format(v)

// ============================ 마켓 목록 ============================
class MarketViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var categories by mutableStateOf<List<MarketCategory>>(emptyList())
    var products by mutableStateOf<List<Product>>(emptyList())
    var bundles by mutableStateOf<List<Bundle>>(emptyList())
    var recommendations by mutableStateOf<List<Product>>(emptyList())
    var selected by mutableStateOf("all")
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)

    init { loadStatic(); load() }

    private fun loadStatic() {
        viewModelScope.launch {
            try { categories = api.marketCategories().categories } catch (_: Exception) {}
            try { bundles = api.marketBundles().bundles } catch (_: Exception) {}
            try { recommendations = api.marketRecommendations(Session.userId).recommendations } catch (_: Exception) {}
        }
    }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                products = api.marketProducts(
                    category = selected.takeIf { it != "all" },
                    q = query.takeIf { it.isNotBlank() }
                ).products
            } catch (_: Exception) {}
            loading = false
        }
    }

    fun select(cat: String) { selected = cat; load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(nav: NavController, vm: MarketViewModel = viewModel()) {
    val cartCount = Cart.count()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("마켓") },
                actions = {
                    BadgedBox(badge = { if (cartCount > 0) Badge { Text("$cartCount") } }) {
                        TextButton(onClick = { nav.navigate("market_cart") }) { Text("🛒 장바구니") }
                    }
                }
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    label = { Text("상품 검색 (비료, 토마토, ESP32…)") },
                    singleLine = true,
                    trailingIcon = { TextButton(onClick = { vm.load() }) { Text("검색") } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.selected == "all", onClick = { vm.select("all") }, label = { Text("전체") })
                    vm.categories.forEach { c ->
                        FilterChip(
                            selected = vm.selected == c.key,
                            onClick = { vm.select(c.key) },
                            label = { Text("${c.label} ${c.count}") }
                        )
                    }
                }
            }

            if (vm.selected == "all" && vm.query.isBlank() && vm.recommendations.isNotEmpty()) {
                item { Text("내 식물 맞춤 추천", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.recommendations) { pr ->
                            Card(Modifier.width(140.dp).clickable { nav.navigate("market_product/${pr.id}") }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pr.image ?: "🛒", style = MaterialTheme.typography.headlineMedium)
                                    Text(pr.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text(won(pr.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (vm.selected == "all" && vm.query.isBlank() && vm.bundles.isNotEmpty()) {
                item { Text("추천 번들 키트", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
                items(vm.bundles) { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.image ?: "📦", style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, fontWeight = FontWeight.Bold)
                                    Text(b.description ?: "", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(won(b.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text("구성: " + b.items.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                b.items.forEach { Cart.add(Product(id = it.id, name = it.name, price = it.price, image = it.image)) }
                            }, modifier = Modifier.fillMaxWidth()) { Text("번들 담기") }
                        }
                    }
                }
            }

            item {
                Text(if (vm.query.isBlank()) "상품" else "\"${vm.query}\" 검색결과", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            if (vm.loading) item { CircularProgressIndicator() }
            items(vm.products.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { pr ->
                        Card(Modifier.weight(1f).clickable { nav.navigate("market_product/${pr.id}") }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pr.image ?: "🛒", style = MaterialTheme.typography.headlineMedium)
                                Text(pr.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text("⭐ ${pr.rating}  ·  ${if (pr.affiliate_url != null) "제휴" else "자체"}", style = MaterialTheme.typography.labelSmall)
                                Text(won(pr.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { TextButton(onClick = { nav.navigate("market_orders") }) { Text("주문 내역 보기 ›") } }
        }
    }
}

// ============================ 상품 상세 ============================
class ProductViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var product by mutableStateOf<Product?>(null)
    var msg by mutableStateOf<String?>(null)
    fun load(id: String) {
        viewModelScope.launch { try { product = api.marketProduct(id) } catch (e: Exception) { msg = e.message } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(nav: NavController, productId: String, vm: ProductViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(productId) { vm.load(productId) }

    Scaffold(topBar = { TopAppBar(title = { Text("상품") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        val pr = vm.product
        if (pr == null) {
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(pr.image ?: "🛒", style = MaterialTheme.typography.displayMedium)
            Text(pr.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            Text("⭐ ${pr.rating}  ·  ${pr.vendor ?: ""}  ·  재고 ${pr.stock}", style = MaterialTheme.typography.bodySmall)
            Text(won(pr.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            pr.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (pr.affiliate_url != null) {
                Button(onClick = {
                    scope.launch {
                        try {
                            val r = ApiClient.api.marketAffiliate(pr.id, AffiliateRequest(Session.userId))
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                        } catch (e: Exception) { vm.msg = e.message }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("제휴몰에서 구매하기 ↗") }
                Text("외부 제휴 상품입니다 (CPS/CPA)", style = MaterialTheme.typography.labelSmall)
            } else {
                Button(onClick = { Cart.add(pr); vm.msg = "장바구니에 담았습니다" }, modifier = Modifier.fillMaxWidth()) { Text("장바구니 담기") }
                OutlinedButton(onClick = { Cart.add(pr); nav.navigate("market_cart") }, modifier = Modifier.fillMaxWidth()) { Text("바로 구매") }
            }
            vm.msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ============================ 장바구니 / 결제 ============================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("장바구니") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (Cart.lines.isEmpty()) {
                Card(Modifier.fillMaxWidth()) { Text("장바구니가 비어 있습니다", Modifier.padding(24.dp)) }
            } else {
                Cart.lines.forEach { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(line.product.image ?: "🛒", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(line.product.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(won(line.product.price), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { Cart.dec(line.product.id) }) { Text("−") }
                            Text("${line.qty}")
                            IconButton(onClick = { Cart.inc(line.product.id) }) { Text("+") }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("합계", fontWeight = FontWeight.Bold)
                    Text(won(Cart.total()), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Button(
                    onClick = {
                        loading = true; msg = null
                        scope.launch {
                            try {
                                val r = ApiClient.api.marketCreateOrder(CreateOrderRequest(Session.userId, Cart.toOrderItems()))
                                Cart.clear()
                                msg = "결제 완료! 주문번호 ${r.orderId.take(8)} (${won(r.total)})"
                            } catch (e: Exception) { msg = e.message ?: "결제 실패" }
                            loading = false
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("결제하기 (${won(Cart.total())})") }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(onClick = { nav.navigate("market_orders") }, modifier = Modifier.fillMaxWidth()) { Text("주문 내역") }
        }
    }
}

// ============================ 주문 내역 ============================
class OrdersViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var orders by mutableStateOf<List<Order>>(emptyList())
    var loading by mutableStateOf(false)
    init { refresh() }
    fun refresh() {
        loading = true
        viewModelScope.launch {
            try { orders = api.marketOrders(Session.userId).orders } catch (_: Exception) {}
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(nav: NavController, vm: OrdersViewModel = viewModel()) {
    Scaffold(topBar = { TopAppBar(title = { Text("주문 내역") }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹") } }) }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.loading) item { CircularProgressIndicator() }
            if (vm.orders.isEmpty() && !vm.loading) item { Card(Modifier.fillMaxWidth()) { Text("주문 내역이 없습니다", Modifier.padding(24.dp)) } }
            items(vm.orders) { o ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("주문 ${o.id.take(8)}", fontWeight = FontWeight.Bold)
                            Text(won(o.total), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text("${o.status ?: ""} · ${o.created_at ?: ""}", style = MaterialTheme.typography.labelSmall)
                        o.items.forEach { it2 -> Text("· ${it2.name} × ${it2.qty}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
