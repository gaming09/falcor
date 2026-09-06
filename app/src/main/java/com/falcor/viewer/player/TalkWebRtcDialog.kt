package com.falcor.viewer.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.falcor.viewer.R

/**
 * In-app WebView hosting Frigate/go2rtc WebRTC player for two-way talk.
 * Injects JWT as cookie + Authorization header so port 8971 auth works.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TalkWebRtcDialog(
    pageUrl: String,
    bearerToken: String?,
    cookieName: String = "frigate_token",
    onDismiss: () -> Unit,
    onLoadFailed: () -> Unit
) {
    var loadFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_talk_webrtc_title)) },
        text = {
            Column {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        val token = bearerToken?.trim()?.removePrefix("Bearer ")
                            ?.removePrefix("bearer ")?.trim().orEmpty()
                        if (token.isNotEmpty()) {
                            injectAuthCookie(pageUrl, cookieName, token)
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loadFailed = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                if (failingUrl == pageUrl || failingUrl == null) {
                                    loadFailed = true
                                    onLoadFailed()
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }

                        val headers = mutableMapOf<String, String>()
                        if (token.isNotEmpty()) {
                            headers["Authorization"] = "Bearer $token"
                        }
                        loadUrl(pageUrl, headers)
                    }
                }
            )
            if (loadFailed) {
                Text(
                    stringResource(R.string.camera_talk_webrtc_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    stringResource(R.string.camera_talk_webrtc_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            } // Column
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.camera_talk_close))
            }
        }
    )

    DisposableEffect(pageUrl) {
        onDispose {
            // WebView cleanup handled when AndroidView leaves composition
        }
    }
}
