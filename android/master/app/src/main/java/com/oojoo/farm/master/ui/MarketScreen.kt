package com.oojoo.farm.master.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.oojoo.farm.master.data.Cart
import com.oojoo.farm.master.data.LocalAppStrings
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.model.*
import com.oojoo.farm.master.network.ApiClient
import kotlinx.coroutines.launch

fun won(v: Int): String = "₩" + "%,d".format(v)

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
            try { products = api.marketProducts(selected.takeIf { it != "all" }, query.takeIf { it.isNotBlank() }).products } catch (_: Exception) {}
            loading = false
        }
    }
    fun select(cat: String) { selected = cat; load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(nav: NavController, vm: MarketViewModel = viewModel()) {
    val S = LocalAppStrings.current
    val cartCount = Cart.count()
    Scaffold(
        topBar = { TopAppBar(title = { Text(S.marketTitle, color = Color.White, fontWeight = FontWeight.Bold) }, actions = { BadgedBox(badge = { if (cartCount > 0) Badge { Text("$cartCount") } }) { TextButton(onClick = { nav.navigate("market_cart") }) { Text("🛒", color = Color.White, fontSize = 18.sp) } } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) },
        containerColor = OojooTheme.Bg
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OojooField(vm.query, { vm.query = it }, S.searchProducts) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OojooChip(vm.selected == "all", { vm.select("all") }, S.all)
                    vm.categories.forEach { c -> OojooChip(vm.selected == c.key, { vm.select(c.key) }, "${c.label} ${c.count}") }
                }
            }
            if (vm.selected == "all" && vm.query.isBlank() && vm.recommendations.isNotEmpty()) {
                item { Text(S.recommendTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.recommendations) { pr ->
                            Card(Modifier.width(130.dp).shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { nav.navigate("market_product/${pr.id}") }, shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pr.image ?: "🛒", fontSize = 26.sp)
                                    Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, color = OojooTheme.Ink)
                                    Text(won(pr.price), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
            if (vm.selected == "all" && vm.query.isBlank() && vm.bundles.isNotEmpty()) {
                item { Text(S.bundleKits, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
                items(vm.bundles) { b ->
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.image ?: "📦", fontSize = 26.sp); Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) { Text(b.name, fontWeight = FontWeight.Bold, color = OojooTheme.Ink); Text(b.description ?: "", color = OojooTheme.Muted, fontSize = 12.sp) }
                                Text(won(b.price), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                            }
                            Text("${S.contains} ${b.items.joinToString(", ") { it.name }}", color = OojooTheme.Muted, fontSize = 12.sp)
                            OutlineButton(text = S.addBundle, onClick = { b.items.forEach { Cart.add(Product(id = it.id, name = it.name, price = it.price, image = it.image)) } }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item { Text(if (vm.query.isBlank()) S.products else "${S.searchResultsPrefix}${vm.query}${S.searchResultsSuffix}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OojooTheme.Ink) }
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) } }
            items(vm.products.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { pr ->
                        Card(Modifier.weight(1f).shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape).clickable { nav.navigate("market_product/${pr.id}") }, shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pr.image ?: "🛒", fontSize = 30.sp)
                                Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, color = OojooTheme.Ink)
                                Text("${S.rating} ${pr.rating} ${S.vendor} ${if (pr.affiliate_url != null) S.affiliate else S.selfHost}", color = OojooTheme.Muted, fontSize = 11.sp)
                                Text(won(pr.price), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { TextButton(onClick = { nav.navigate("market_orders") }) { Text(S.viewOrders, color = OojooTheme.Green) } }
        }
    }
}

@Composable
fun OojooChip(selected: Boolean, onClick: () -> Unit, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) OojooTheme.Green else OojooTheme.Card,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, OojooTheme.Line),
        modifier = Modifier.clickable { onClick() }
    ) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = if (selected) Color.White else OojooTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

class ProductViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var product by mutableStateOf<Product?>(null)
    var msg by mutableStateOf<String?>(null)
    fun load(id: String) { viewModelScope.launch { try { product = api.marketProduct(id) } catch (e: Exception) { msg = e.message } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(nav: NavController, productId: String, vm: ProductViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val S = LocalAppStrings.current
    LaunchedEffect(productId) { vm.load(productId) }
    Scaffold(topBar = { TopAppBar(title = { Text(S.productTitle, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        val pr = vm.product
        if (pr == null) { Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OojooTheme.Green) }; return@Scaffold }
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(pr.image ?: "🛒", fontSize = 64.sp)
            Text(pr.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = OojooTheme.Ink, modifier = Modifier.fillMaxWidth())
            Text("${S.rating} ${pr.rating} ${S.vendor} ${pr.vendor ?: ""} ${S.vendor} ${S.stock} ${pr.stock}", color = OojooTheme.Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Text(won(pr.price), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, modifier = Modifier.fillMaxWidth())
            pr.description?.let { Text(it, color = OojooTheme.Muted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()) }
            if (pr.affiliate_url != null) {
                GradientButton(text = S.buyAtAffiliate, onClick = { scope.launch { try { val r = ApiClient.api.marketAffiliate(pr.id, AffiliateRequest(Session.userId)); ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } catch (e: Exception) { vm.msg = e.message } } }, modifier = Modifier.fillMaxWidth())
                Text(S.affiliateNotice, color = OojooTheme.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
            } else {
                GradientButton(text = S.addToCart, onClick = { Cart.add(pr); vm.msg = S.addToCartDone }, modifier = Modifier.fillMaxWidth())
                OutlineButton(text = S.directBuy, onClick = { Cart.add(pr); nav.navigate("market_cart") }, modifier = Modifier.fillMaxWidth())
            }
            vm.msg?.let { Text(it, color = OojooTheme.Green, fontSize = 13.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val S = LocalAppStrings.current
    var msg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(S.cart, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (Cart.lines.isEmpty()) {
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) { Text(S.emptyCart, Modifier.padding(24.dp), color = OojooTheme.Ink) }
            } else {
                Cart.lines.forEach { line ->
                    Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(line.product.image ?: "🛒", fontSize = 26.sp); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(line.product.name, fontWeight = FontWeight.Bold, maxLines = 1, color = OojooTheme.Ink); Text(won(line.product.price), color = OojooTheme.Muted, fontSize = 13.sp) }
                            IconButton(onClick = { Cart.dec(line.product.id) }) { Text("−", color = OojooTheme.Green) }
                            Text("${line.qty}", color = OojooTheme.Ink)
                            IconButton(onClick = { Cart.inc(line.product.id) }) { Text("+", color = OojooTheme.Green) }
                        }
                    }
                }
                HorizontalDivider(color = OojooTheme.Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(S.total, fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                    Text(won(Cart.total()), fontWeight = FontWeight.ExtraBold, color = OojooTheme.Green)
                }
                GradientButton(text = "${S.checkout} (${won(Cart.total())})", onClick = { loading = true; msg = null; scope.launch { try { val r = ApiClient.api.marketCreateOrder(CreateOrderRequest(Session.userId, Cart.toOrderItems())); Cart.clear(); msg = "${r.orderId.take(8)} (${won(r.total)})" } catch (e: Exception) { msg = e.message ?: "Checkout failed" }; loading = false } }, enabled = !loading, modifier = Modifier.fillMaxWidth())
            }
            msg?.let { Text(it, color = OojooTheme.Green, fontSize = 13.sp) }
            OutlineButton(text = S.ordersNav, onClick = { nav.navigate("market_orders") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

class OrdersViewModel : ViewModel() {
    private val api get() = ApiClient.api
    var orders by mutableStateOf<List<Order>>(emptyList())
    var loading by mutableStateOf(false)
    init { refresh() }
    fun refresh() { loading = true; viewModelScope.launch { try { orders = api.marketOrders(Session.userId).orders } catch (_: Exception) {}; loading = false } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(nav: NavController, vm: OrdersViewModel = viewModel()) {
    val S = LocalAppStrings.current
    Scaffold(topBar = { TopAppBar(title = { Text(S.ordersNav, color = Color.White, fontWeight = FontWeight.Bold) }, navigationIcon = { TextButton(onClick = { nav.navigateUp() }) { Text("‹", color = Color.White, fontSize = 20.sp) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = OojooTheme.Green)) }, containerColor = OojooTheme.Bg) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.loading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = OojooTheme.Green) } }
            if (vm.orders.isEmpty() && !vm.loading) item { Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) { Text(S.noOrders, Modifier.padding(24.dp), color = OojooTheme.Ink) } }
            items(vm.orders) { o ->
                Card(Modifier.fillMaxWidth().shadow(OojooTheme.ShadowOffset, OojooTheme.CardShape).border(2.dp, OojooTheme.Ink, OojooTheme.CardShape).clip(OojooTheme.CardShape), shape = OojooTheme.CardShape, colors = CardDefaults.cardColors(containerColor = OojooTheme.Card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${S.orderPrefix}${o.id.take(8)}", fontWeight = FontWeight.Bold, color = OojooTheme.Ink)
                            Text(won(o.total), color = OojooTheme.Green, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("${o.status ?: ""} · ${o.created_at ?: ""}", color = OojooTheme.Muted, fontSize = 11.sp)
                        o.items.forEach { Text("· ${it.name} × ${it.qty}", color = OojooTheme.Muted, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}
