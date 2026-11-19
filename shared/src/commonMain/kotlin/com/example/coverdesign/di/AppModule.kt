package com.example.coverdesign.di

// shared/commonMain/di/AppModule.kt

import com.example.coverdesign.data.local.DatabaseDriverFactory
import com.example.coverdesign.data.remote.client.createHttpClient
import com.example.coverdesign.data.remote.firestore.CareCloudDataSource
import com.example.coverdesign.data.remote.firestore.OrderCloudDataSource
import com.example.coverdesign.data.remote.interceptor.InMemoryTokenStorage
import com.example.coverdesign.data.remote.interceptor.TokenStorage
import com.example.coverdesign.data.remote.service.CareApiService
import com.example.coverdesign.data.remote.service.Model3DApiService
import com.example.coverdesign.data.remote.service.OrderApiService
import com.example.coverdesign.data.remote.service.ProductApiService
import com.example.coverdesign.data.remote.service.RehabApiService
import com.example.coverdesign.data.repoimpl.CareRepositoryImpl
import com.example.coverdesign.data.repoimpl.FireAuthRepositoryImpl
import com.example.coverdesign.data.repoimpl.Model3DRepositoryImpl
import com.example.coverdesign.data.repoimpl.OrderRepositoryImpl
import com.example.coverdesign.data.repoimpl.ProductRepositoryImpl
import com.example.coverdesign.data.repoimpl.RehabRepositoryImpl
import com.example.coverdesign.db.AppDatabase
import com.example.coverdesign.domain.repo.CareRepository
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.domain.repo.Model3DRepository
import com.example.coverdesign.domain.repo.OrderRepository
import com.example.coverdesign.domain.repo.ProductRepository
import com.example.coverdesign.domain.repo.RehabRepository
import com.example.coverdesign.domain.usecase.CalculatePriceUseCase
import com.example.coverdesign.domain.usecase.ConfigureProductUseCase
import com.example.coverdesign.domain.usecase.CreateConfigFromProductUseCase
import com.example.coverdesign.domain.usecase.GenerateModelUseCase
import com.example.coverdesign.domain.usecase.GenerateProstheticCoverModelUseCase
import com.example.coverdesign.domain.usecase.GetCatalogUseCase
import com.example.coverdesign.domain.usecase.GetOrderDetailUseCase
import com.example.coverdesign.domain.usecase.GetDoctorPatientsUseCase
import com.example.coverdesign.domain.usecase.GetOrderListUseCase
import com.example.coverdesign.domain.usecase.GetRehabDataUseCase
import com.example.coverdesign.domain.usecase.LinkOrderToAdaptedModelUseCase
import com.example.coverdesign.domain.usecase.LogoutUseCase
import com.example.coverdesign.domain.usecase.PollModelStatusUseCase
import com.example.coverdesign.domain.usecase.PollRehabSessionUseCase
import com.example.coverdesign.domain.usecase.SaveInsoleOrderLocallyUseCase
import com.example.coverdesign.domain.usecase.SaveOrderLocallyUseCase
import com.example.coverdesign.domain.usecase.SubmitProstheticOrderUseCase
import com.example.coverdesign.domain.usecase.SubmitRecommendationUseCase
import com.example.coverdesign.domain.usecase.SubmitRehabSessionUseCase
import com.example.coverdesign.domain.usecase.SyncOrdersUseCase
import com.example.coverdesign.domain.usecase.UpdateConfigUseCase
import com.example.coverdesign.domain.usecase.UpsertGaitProfileUseCase
import com.example.coverdesign.domain.usecase.UploadReferencePhotoUseCase
import com.example.coverdesign.domain.usecase.UploadTextureImageUseCase
import com.example.coverdesign.domain.validator.MeasurementValidator
import com.example.coverdesign.ui.viewmodel.AppViewModel
import com.example.coverdesign.ui.viewmodel.AuthViewModel
import com.example.coverdesign.ui.viewmodel.CatalogViewModel
import com.example.coverdesign.ui.viewmodel.ClinicianHomeViewModel
import com.example.coverdesign.ui.viewmodel.ClinicianPatientsViewModel
import com.example.coverdesign.ui.viewmodel.HomeViewModel
import com.example.coverdesign.ui.viewmodel.ModelPreviewViewModel
import com.example.coverdesign.ui.viewmodel.OrderListViewModel
import com.example.coverdesign.ui.viewmodel.OrderSummaryViewModel
import com.example.coverdesign.ui.viewmodel.ProductConfigViewModel
import com.example.coverdesign.ui.viewmodel.RecommendationsViewModel
import com.example.coverdesign.ui.viewmodel.RehabViewModel
import com.example.coverdesign.ui.viewmodel.SensorCameraViewModel
import com.example.coverdesign.ui.viewmodel.WriteRecommendationViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module


val databaseModule = module {
    // DatabaseDriverFactory is expect/actual — platform provides Context on Android
    single { get<DatabaseDriverFactory>().createDriver() }
    single { AppDatabase(driver = get()) }

    // SQLDelight query objects — generated from .sq files
    single { get<AppDatabase>().productConfigQueries }
    single { get<AppDatabase>().orderQueries }
    single { get<AppDatabase>().catalogQueries }
    single { get<AppDatabase>().model3DQueries }
    single { get<AppDatabase>().insoleOrderConfigQueries }
    single { get<AppDatabase>().prostheticCoverOrderConfigQueries }
    //single { DatabaseSeeder(db = get()) }
}

val networkModule = module {
    // TokenStorage — placeholder impl for now, replaced with
    // EncryptedStorage in the security step
    single<TokenStorage> { InMemoryTokenStorage() }

    // Ktor HttpClient — single instance shared across all services
    single { createHttpClient(tokenStorage = get()) }

    // API services
    singleOf(::ProductApiService)
    singleOf(::Model3DApiService)
    singleOf(::OrderApiService)
    singleOf(::RehabApiService)
    singleOf(::CareApiService)

    // Firestore data sources (GitLive — defaults to Firebase.firestore)
    single { OrderCloudDataSource() }
    single { CareCloudDataSource() }
}

val repositoryModule = module {
    single<ProductRepository> { ProductRepositoryImpl(api = get(), db = get()) }
    single<Model3DRepository> { Model3DRepositoryImpl(api = get(), db = get()) }
    single<OrderRepository> {
        OrderRepositoryImpl(api = get(), db = get(), cloud = get(), authRepo = get())
    }
    single<RehabRepository> { RehabRepositoryImpl(api = get()) }
    single<CareRepository> { CareRepositoryImpl(api = get(), cloud = get(), authRepo = get()) }
//    single<AuthRepository> {
//        AuthRepositoryImplTest(tokenStorage = get())
//    }
    single<FireAuthRepository> { FireAuthRepositoryImpl() }


//single<ArSessionRepository>{ ArSessionRepositoryImpl() }
}

val useCaseModule = module {
    // FR1
    factoryOf(::GetCatalogUseCase)

    // FR2, FR3
    factory { ConfigureProductUseCase(repo = get(), validator = get()) }

    // FR4
    factory { GenerateModelUseCase(get()) }
    factoryOf(::GenerateProstheticCoverModelUseCase)
    factory { SubmitProstheticOrderUseCase(orderRepo = get(), generateModel = get()) }
    factory { PollModelStatusUseCase(get()) }
    factory { SaveOrderLocallyUseCase(get()) }
    factoryOf(::SaveInsoleOrderLocallyUseCase)

    // FR5
    //factoryOf(::GenerateModelUseCase)

    // FR6
    factory { LinkOrderToAdaptedModelUseCase(orderRepo = get()) }

    // FR7
    factory { UpdateConfigUseCase(repo = get(), validator = get()) }
    factory { UploadTextureImageUseCase(storageRepo = get()) }
    factory { UploadReferencePhotoUseCase(storageRepo = get()) }

    factoryOf(::CreateConfigFromProductUseCase)

    // FR8
    factoryOf(::CalculatePriceUseCase)

    // FR10
    factoryOf(::GetOrderListUseCase)
    factory { GetOrderDetailUseCase(orderRepo = get(), model3DRepo = get()) }

    // Order sync / logout
    factoryOf(::SyncOrdersUseCase)
    factory { LogoutUseCase(orderRepo = get(), authRepo = get()) }

    // Rehabilitation
    factoryOf(::GetRehabDataUseCase)
    factoryOf(::GetDoctorPatientsUseCase)
    factoryOf(::SubmitRecommendationUseCase)
    factoryOf(::SubmitRehabSessionUseCase)
    factoryOf(::UpsertGaitProfileUseCase)
    factory { PollRehabSessionUseCase(get()) }

    // shared validator
    singleOf(::MeasurementValidator)
}
val viewModelModule = module {

    viewModel {
        ProductConfigViewModel(
            productRepo = get(),
            generateModel = get(),
            submitProstheticOrder = get(),
            validator = get(),
            uploadTextureImage = get(),
            uploadUserImage = get(),
            saveOrderLocally = get(),
            saveInsoleOrderLocally = get(),
            orderRepo = get()
        )
    }
    viewModel { CatalogViewModel(getCatalog = get()) }

    viewModel {
        ModelPreviewViewModel(
            pollModelStatus = get(), model3DRepo = get(), linkOrderToAdaptedModel = get()
        )
    }
    viewModel {
        OrderSummaryViewModel(orderRepo = get(), getOrderDetail = get())
    }
    viewModel { OrderListViewModel(getOrderList = get(), syncOrders = get()) }
    viewModel {
        HomeViewModel(
            getOrderList = get(),
            getRehabData = get(),
            authRepo = get(),
            logout = get(),
            syncOrders = get()
        )
    }
    viewModel { AuthViewModel(get(), get(), get()) }
    viewModel { RehabViewModel(getRehabData = get(), authRepo = get()) }
    viewModel { RecommendationsViewModel(getRehabData = get(), authRepo = get()) }
    viewModel { SensorCameraViewModel(submitRehabSession = get(), pollRehabSession = get(), authRepo = get()) }
    viewModel { AppViewModel(authRepo = get()) }
    viewModel { ClinicianHomeViewModel(authRepo = get(), getDoctorPatients = get()) }
    viewModel { ClinicianPatientsViewModel(authRepo = get(), getDoctorPatients = get()) }
    viewModel { (patientId: String) ->
        WriteRecommendationViewModel(patientId = patientId, getDoctorPatients = get(), submitRecommendation = get())
    }

}

val appModules = listOf(
    databaseModule, networkModule, repositoryModule, useCaseModule, viewModelModule
)
