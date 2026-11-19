package com.example.coverdesign.data.remote.client

// data/remote/client/ApiConstants.kt
object ApiConstants {
    // swap via BuildConfig / xcconfig per environment
    const val BASE_URL = "http://10.0.2.2:8000/"
//    const val BASE_URL = "https://8000-01jvzhcj3c8gp76vp628c9jp47.cloudspaces.litng.ai/"

    object Endpoints {
        const val BASE_MODEL = ""

        // products
        const val CATALOG = "/products"
        const val PRODUCT = "/products/{id}"
        const val PRICE = "/products/price"

        // 3D model
        const val GENERATE_MODEL = "/model/generate"
        const val GENERATE_COVER_MODEL = "/model/cover_generate"
        const val MODEL_STATUS = "/model/{taskId}/status"
        const val MODEL_URL = "/model/{id}/url"
        const val TRY_ON = "/model/tryon"
        const val TEXTURES = "/model/textures"

        // orders
        const val SUBMIT_ORDER = "/orders"
        const val SUBMIT_INSOLE_ORDER = "/orders/insole"
        const val SUBMIT_COVER_ORDER = "/orders/cover"
        const val ORDER_BY_ID = "/orders/{id}"
        const val ORDERS = "/orders"

        // rehab
        const val REHAB = "/rehab"                                  // GET — current user's sessions + recommendations
        const val REHAB_PROFILE = "/rehab/profile"                  // GET/PUT — current user's gait profile (biometrics + settings)
        const val SUBMIT_REHAB_SESSION = "/rehab/sessions"          // POST — kick off processing of a recorded video
        const val REHAB_SESSION_STATUS = "/rehab/sessions/{taskId}/status" // GET — poll processing status

        // care (doctor ↔ patient)
        const val CARE_PATIENTS = "/care/patients"  // GET — the calling doctor's patient roster

        // auth
        const val LOGIN = "/auth/login"

        //const val REFRESH        = "/auth/refresh"
        const val LOGOUT = "/auth/logout"
    }
}