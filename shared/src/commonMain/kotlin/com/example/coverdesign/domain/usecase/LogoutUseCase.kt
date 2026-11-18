package com.example.coverdesign.domain.usecase

import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.repo.OrderRepository
import com.example.coverdesign.domain.util.Resource

/**
 * Signs the user out and wipes their locally-cached orders + related configs so the next
 * user on this device starts clean. Local data is cleared first; orders are reloaded from
 * Firestore on the next login via [SyncOrdersUseCase].
 */
class LogoutUseCase(
    private val orderRepo: OrderRepository,
    private val authRepo: FireAuthRepository,
) {
    suspend operator fun invoke(): Resource<Unit> {
        orderRepo.clearLocalOrders()
        return authRepo.signOut()
    }
}
