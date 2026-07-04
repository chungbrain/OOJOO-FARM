package com.oojoo.farm.master.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.oojoo.farm.master.model.OrderItemRequest
import com.oojoo.farm.master.model.Product

/**
 * 장바구니(싱글톤, Compose 관찰 가능). 화면 간 상태 유지.
 */
object Cart {
    class Line(val product: Product, qty: Int) {
        var qty by mutableIntStateOf(qty)
    }

    val lines = mutableStateListOf<Line>()

    fun add(p: Product, qty: Int = 1) {
        val e = lines.find { it.product.id == p.id }
        if (e != null) e.qty += qty else lines.add(Line(p, qty))
    }

    fun inc(id: String) { lines.find { it.product.id == id }?.let { it.qty += 1 } }
    fun dec(id: String) {
        val e = lines.find { it.product.id == id } ?: return
        if (e.qty > 1) e.qty -= 1 else lines.remove(e)
    }
    fun remove(id: String) { lines.removeAll { it.product.id == id } }

    fun count(): Int = lines.sumOf { it.qty }
    fun total(): Int = lines.sumOf { it.product.price * it.qty }
    fun clear() = lines.clear()

    fun toOrderItems(): List<OrderItemRequest> = lines.map { OrderItemRequest(it.product.id, it.qty) }
}
