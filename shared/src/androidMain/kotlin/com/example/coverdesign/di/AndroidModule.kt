package com.example.coverdesign.di

import com.example.coverdesign.data.local.DatabaseDriverFactory
import com.example.coverdesign.data.remote.GoogleAuthenticator
import com.example.coverdesign.data.repoimpl.FirebaseCloudConfigRepositoryImpl
import com.example.coverdesign.data.repoimpl.FirebaseStorageRepositoryImpl
import com.example.coverdesign.domain.repo.CloudConfigRepository
import com.example.coverdesign.domain.repo.StorageRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

// androidMain/di/AndroidModule.kt
val androidModule = module {
    single { DatabaseDriverFactory(context = androidContext()) }
    
    single { FirebaseStorage.getInstance() }
    single { FirebaseFirestore.getInstance() }

    single<StorageRepository> {
        FirebaseStorageRepositoryImpl(storage = get())
    }
    single {
        val context = androidContext()
        val clientIdResource = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName
        )
        GoogleAuthenticator(
            webClientId = if (clientIdResource != 0) context.getString(clientIdResource) else ""
        )
    }
    single<CloudConfigRepository> { FirebaseCloudConfigRepositoryImpl(firestore = get()) }
}