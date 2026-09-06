package com.falcor.viewer.data.api

import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Credentials
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

    fun create(credentials: SecureCredentialStore.Credentials): FrigateApi {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
            val token = credentials.token
            when {
                !token.isNullOrBlank() -> {
                    val value = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                    request.header("Authorization", value)
                }
                !credentials.username.isNullOrBlank() -> {
                    request.header(
                        "Authorization",
                        Credentials.basic(credentials.username, credentials.password.orEmpty())
                    )
                }
            }
            chain.proceed(request.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        val base = credentials.baseUrl.trimEnd('/') + "/api/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FrigateApi::class.java)
    }
}
