package com.example.coverdesign.domain.repo

import com.example.coverdesign.domain.model.InsoleOrderConfig
import com.example.coverdesign.domain.model.Order
import com.example.coverdesign.domain.model.OrderDetail
import com.example.coverdesign.domain.model.OrderId
import com.example.coverdesign.domain.model.OrderListItem
import com.example.coverdesign.domain.model.ProstheticCoverOrderConfig
import com.example.coverdesign.domain.util.Resource
import kotlinx.coroutines.flow.Flow

// shared/commonMain/domain/repo/OrderRepository.kt
interface OrderRepository {
    // ── Catalog-based order flow (ProductConfig) ───────────────────────────────
    suspend fun submitOrder(order: Order): Resource<OrderId>
    suspend fun saveOrderLocally(order: Order): Resource<Unit>
    fun getOrderHistory(): Flow<List<Order>>
    //suspend fun getOrderById(id: String): Resource<Order>
    suspend fun linkOrderToAdaptedModel(configId: String, adaptedModelId: String): Resource<Unit>

    // ── Direct order flow — Insole ─────────────────────────────────────────────
    suspend fun submitInsoleOrder(config: InsoleOrderConfig): Resource<OrderId>
    suspend fun saveInsoleOrderLocally(config: InsoleOrderConfig): Resource<Unit>
    fun getInsoleOrderHistory(): Flow<List<InsoleOrderConfig>>
    suspend fun getInsoleOrderById(orderId: String): Resource<InsoleOrderConfig>

    // ── Direct order flow — Prosthetic Cover ───────────────────────────────────
    suspend fun submitProstheticCoverOrder(config: ProstheticCoverOrderConfig): Resource<OrderId>
    suspend fun saveProstheticCoverOrderLocally(config: ProstheticCoverOrderConfig): Resource<Unit>
    fun getProstheticCoverOrderHistory(): Flow<List<ProstheticCoverOrderConfig>>
    suspend fun getProstheticCoverOrderById(orderId: String): Resource<ProstheticCoverOrderConfig>

    // ── Combined list for My Orders screen ─────────────────────────────────────
    fun getOrderListItems(): Flow<List<OrderListItem>>

    // ── Unified detail lookup (all order types) ────────────────────────────────
    suspend fun getOrderDetailById(id: String): Resource<OrderDetail>
    suspend fun getOrderById(orderId: String): Resource<Order>

    // ── Remote sync (Firestore, per-user) ──────────────────────────────────────
    /** Reloads the signed-in user's orders from Firestore into the local DB. */
    suspend fun syncOrdersFromRemote(): Resource<Unit>

    /** Wipes all locally-cached orders and related configs (e.g. on sign-out). */
    suspend fun clearLocalOrders(): Resource<Unit>
}