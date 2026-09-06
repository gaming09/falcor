package com.falcor.viewer.data.api

import com.falcor.viewer.data.api.LocalSsl.trustLocalSelfSigned
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object FrigateClientFactory {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Build an OkHttp client. When [bearerToken] is set, attaches
     * `Authorization: Bearer …`. Does **not** send HTTP Basic — Frigate auth
     * uses POST /api/login → JWT cookie / Bearer token.
     */
    fun okHttpClient(bearerToken: String? = null): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
            val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
            if (token != null) {
                val value =
                    if (token.startsWith("Bearer ", ignoreCase = true)) token
                    else "Bearer $token"
                request.header("Authorization", value)
            }
            chain.proceed(request.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .trustLocalSelfSigned()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    fun create(credentials: SecureCredentialStore.Credentials): FrigateApi =
        create(credentials.baseUrl, credentials.token)

    fun create(baseUrl: String, bearerToken: String? = null): FrigateApi {
        val base = baseUrl.trim().trimEnd('/') + "/api/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(okHttpClient(bearerToken))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FrigateApi::class.java)
    }
}
