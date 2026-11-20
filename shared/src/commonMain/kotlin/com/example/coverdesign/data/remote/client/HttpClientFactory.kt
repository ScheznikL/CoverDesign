package com.example.coverdesign.data.remote.client

// data/remote/client/HttpClientFactory.kt
import com.example.coverdesign.data.remote.interceptor.TokenStorage
import com.example.coverdesign.domain.model.Token
import com.example.coverdesign.domain.util.getPlatformName
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class ApiClient( //TODO temp
    private val httpClient: HttpClient,
    private val baseUrl: String
) {
    suspend fun testConnection(): Result<String> = runCatching {
        val response = httpClient.get("$baseUrl/openapi.json")
        println("Connection successful: ${response.status}")
        response.bodyAsText()
    }.onFailure { e ->
        e.printStackTrace()
    }
}

object TempApiClientFactory {
    fun create(
        httpClient: HttpClient,
        baseUrl: String
    ): ApiClient = ApiClient(
        httpClient = httpClient,
        baseUrl = baseUrl.trimEnd('/')
    )
}
fun createHttpClient(tokenStorage: TokenStorage): HttpClient = HttpClient {

    // ── JSON ────────────────────────────────────────────────────────────────
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true   // safe: backend can add fields freely
            isLenient = true
            explicitNulls = false
        })
    }

//     ── Logging (debug only) ────────────────────────────────────────────────
    install(Logging) {
        level = LogLevel.ALL
        // level  = LogLevel.BODY        // switch to NONE for release builds
    }

    // ── Timeouts ────────────────────────────────────────────────────────────
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }

    // ── Auth: bearer token + silent refresh ─────────────────────────────────
    install(Auth) {
        bearer {
            loadTokens {
                val token = tokenStorage.getToken() ?: return@loadTokens null
                BearerTokens(
                    accessToken = token.accessToken, refreshToken = token.refreshToken
                )
            }
            refreshTokens {
                println("Refreshing Firebase token...")
                val user = Firebase.auth.currentUser
                    ?: run { tokenStorage.clearToken(); return@refreshTokens null }

                val freshToken = user.getIdToken(forceRefresh = true) //was true
                    ?: run { tokenStorage.clearToken(); return@refreshTokens null }

                val newToken = Token(accessToken = freshToken, refreshToken = "")
                tokenStorage.saveToken(newToken)

                BearerTokens(
                    accessToken = freshToken,
                    refreshToken = ""   // Firebase doesn't use refresh tokens directly
                )
            }
        }
    }

    // ── Error response handling ──────────────────────────────────────────────
    //install(HttpResponseValidator)
    HttpResponseValidator {
        validateResponse { response ->
            val code = response.status.value
            if (code < 400) return@validateResponse

            val rawBody = runCatching { response.bodyAsText() }.getOrDefault("")
            val detail = runCatching {
                val element = Json.parseToJsonElement(rawBody)
                val detailEl = element.jsonObject["detail"]
                when {
                    detailEl == null -> null
                    detailEl is JsonPrimitive -> detailEl.content
                    else -> detailEl.toString()
                }
            }.getOrNull()

            val message = detail ?: response.status.description

            throw when (code) {
                401 -> ApiException.Unauthorized(message)
                403 -> ApiException.Forbidden(message)
                404 -> ApiException.NotFound(message)
                422 -> ApiException.UnprocessableEntity(message)
                in 500..599 -> ApiException.ServerError(code, message)
                else -> ApiException.Unknown(code, message)
            }
        }
    }

    // ── Default request config ───────────────────────────────────────────────
    defaultRequest {
        url(ApiConstants.BASE_URL)
        headers.append("Accept", "application/json")
        headers.append("Content-Type", "application/json")
        headers.append("X-Platform", getPlatformName())   // expect/actual below
    }
}