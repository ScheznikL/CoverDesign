package com.example.coverdesign.ui.navigation

sealed class Screen(val route: String) {
    // auth
    object Auth : Screen("auth")
    object SelectRole : Screen("select_role")
    object Login : Screen("login")
    object Register : Screen("register")
    object Main : Screen("main")

    // bottom nav roots
    object Home : Screen("home")
    object Model3D : Screen("3d/{productId}") {
        fun createRoute(productId: String) =
            "3d/$productId"
    }

    object Orders : Screen("orders")
    object Profile : Screen("profile")
    object Gait : Screen("gait")
    object GaitProfile : Screen("gait/profile")
    object GaitHistory : Screen("gait/history")
    object GaitCloud : Screen("gait/cloud")

    // detail screens
    object Catalog : Screen("catalog")

    //    object ProductConfig : Screen("config/{productId}") {
//        fun createRoute(productId: String) = "config/$productId"
//    }
    object ModelPreview : Screen("preview/{taskId}/{configId}") {
        fun createRoute(taskId: String, configId: String) =
            "preview/$taskId/$configId"
    }

    object ModelPreviewFromOrder : Screen("model3d/order/{adaptedModelId}") {
        fun createRoute(adaptedModelId: String) = "model3d/order/$adaptedModelId"
    }

    object ProductConfigFromCatalog : Screen("config/product/{productId}") {
        fun createRoute(productId: String) = "config/product/$productId"
    }

    object ProductConfigFromOrder : Screen("config/existing/{configId}") {
        fun createRoute(configId: String) = "config/existing/$configId"
    }

    object OrderSummary : Screen("order/{configId}") {
        fun createRoute(configId: String) = "order/$configId"
    }

    object OrderDetail : Screen("order/detail/{orderId}") {
        fun createRoute(orderId: String) = "order/detail/$orderId"
    }

    object RehabHistory : Screen("rehab_history")
    object PatientRecommendations : Screen("patient_recommendations")
    object SensorCamera : Screen("sensor_camera")
    object NewOrder : Screen("order/new")
    object NewInsoleOrder : Screen("order/new/insole")

    // role-specific graph roots
    object PatientMain : Screen("patient_main")
    object ClinicianMain : Screen("clinician_main")
    object ClinicianHome : Screen("clinician_home")
    object ClinicianProfile : Screen("clinician_profile")
    object WriteRecommendation : Screen("clinician/recommendation/{patientId}") {
        fun createRoute(patientId: String) = "clinician/recommendation/$patientId"
    }
}
