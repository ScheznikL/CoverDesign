package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.repo.OrderRepository
import com.example.coverdesign.domain.util.Resource

/** Reloads the signed-in user's orders from Firestore into the local DB (called on login). */
class SyncOrdersUseCase(private val orderRepo: OrderRepository) {
    suspend operator fun invoke(): Resource<Unit> = orderRepo.syncOrdersFromRemote()
}
