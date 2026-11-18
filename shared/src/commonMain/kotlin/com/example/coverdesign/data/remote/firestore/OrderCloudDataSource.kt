package com.example.coverdesign.data.remote.firestore

import com.example.coverdesign.data.dto.InsoleOrderConfigDto
import com.example.coverdesign.data.dto.ProstheticCoverOrderConfigDto
import com.example.coverdesign.data.dto.toDomain
import com.example.coverdesign.domain.model.InsoleOrderConfig
import com.example.coverdesign.domain.model.OrderStatus
import com.example.coverdesign.domain.model.ProstheticCoverOrderConfig
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import kotlinx.serialization.Serializable

/**
 * One order the backend persisted to Firestore, carrying the two fields the
 * local SQLDelight entity needs but the domain config does not: [status] and [createdAt].
 */
sealed interface CloudOrder {
    val status: OrderStatus
    val createdAt: Long

    data class Insole(
        val config: InsoleOrderConfig,
        override val status: OrderStatus,
        override val createdAt: Long,
    ) : CloudOrder

    data class Cover(
        val config: ProstheticCoverOrderConfig,
        override val status: OrderStatus,
        override val createdAt: Long,
    ) : CloudOrder
}

/**
 * Read-only view of the orders the backend writes to Firestore.
 *
 * The server (see backend/orders/services.py + repository.py) writes every order to a top-level
 * `orders` collection, doc id = orderId, owned via a `patientId` field. `status`, `createdAt` and
 * `type` live at the document top level; the full config is nested under `parameters` as snake_case
 * JSON matching [InsoleOrderConfigDto] / [ProstheticCoverOrderConfigDto]. The client never writes
 * here — it only reloads the signed-in user's orders on login.
 */
class OrderCloudDataSource(
    private val firestore: FirebaseFirestore = Firebase.firestore,
) {

    suspend fun getOrdersForUser(uid: String): List<CloudOrder> {
        println("[ORDERS] getOrdersForUser() for $uid")
        val snapshot = firestore.collection(COLLECTION)
            .where { FIELD_PATIENT_ID equalTo uid }
            .get()
        println("[ORDERS] getOrdersForUser() for $snapshot")
        return snapshot.documents.mapNotNull { doc ->
            runCatching {
                when (doc.get<String?>(FIELD_TYPE)) {
                    TYPE_INSOLE -> doc.data<InsoleOrderDoc>().let {
                        CloudOrder.Insole(
                            config = it.parameters.toDomain(),
                            status = parseStatus(it.status),
                            createdAt = it.createdAt,
                        )
                    }

                    TYPE_COVER -> doc.data<ProstheticCoverOrderDoc>().let {
                        CloudOrder.Cover(
                            config = it.parameters.toDomain(),
                            status = parseStatus(it.status),
                            createdAt = it.createdAt,
                        )
                    }

                    else -> null
                }
            }.getOrNull()
        }
    }

    private fun parseStatus(raw: String): OrderStatus =
        runCatching { OrderStatus.valueOf(raw.uppercase()) }.getOrDefault(OrderStatus.SUBMITTED)

    // Only the top-level fields we consume are declared; GitLive ignores the rest
    // (patientId, doctorId, type, updatedAt, storageFolder, priceEstimate).
    @Serializable
    private data class InsoleOrderDoc(
        val orderId: String = "",
        val status: String = DEFAULT_STATUS,
        val createdAt: Long = 0L,
        val parameters: InsoleOrderConfigDto,
    )

    @Serializable
    private data class ProstheticCoverOrderDoc(
        val orderId: String = "",
        val status: String = DEFAULT_STATUS,
        val createdAt: Long = 0L,
        val parameters: ProstheticCoverOrderConfigDto,
    )

    private companion object {
        const val COLLECTION = "orders"
        const val FIELD_PATIENT_ID = "patientId"
        const val FIELD_TYPE = "type"
        const val TYPE_INSOLE = "INSOLE"
        const val TYPE_COVER = "PROSTHETIC_COVER"
        val DEFAULT_STATUS = OrderStatus.SUBMITTED.name
    }
}
