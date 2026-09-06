package com.falcor.viewer.data.api

import android.util.Log
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Permissive TLS for self-hosted Frigate on LAN (port 8971 self-signed certs).
 *
 * INTENTIONAL: trusts any certificate and hostname. Suitable only for local /
 * private-network NVR access — not for untrusted public internet endpoints.
 * See README “Self-signed HTTPS”.
 */
object LocalSsl {
    private const val TAG = "LocalSsl"

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    val sslSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        ctx.socketFactory
    }

    fun OkHttpClient.Builder.trustLocalSelfSigned(): OkHttpClient.Builder {
        Log.d(TAG, "Enabling permissive TrustManager for local Frigate HTTPS")
        return sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
    }
}
