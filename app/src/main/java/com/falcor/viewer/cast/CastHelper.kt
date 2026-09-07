package com.falcor.viewer.cast

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import java.net.URI

enum class CastOutcome {
    /** Media load request sent to an active Cast session. */
    Started,
    /** No Cast session — route picker was opened (or attempted). */
    NoSession,
    /** Session existed but load failed. */
    LoadFailed,
    /** Stream URL still looks JWT/auth-bound (:8971 / https) — Chromecast may fail. */
    AuthUrlWarning
}

/**
 * Cast the currently focused camera stream (single HLS/MJPEG URL) to a Cast device.
 * Dashboard multi-cam cast is not supported without a custom receiver.
 *
 * Limitation: Chromecast cannot send Frigate JWT cookies; prefer unauthenticated
 * LAN URLs on port 5000 over :8971 with auth.
 */
object CastHelper {
    private const val TAG = "CastHelper"
    private const val DEFAULT_RECEIVER_ID = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

    fun castSession(context: Context): CastSession? = try {
        CastContext.getSharedInstance(context.applicationContext).sessionManager.currentCastSession
    } catch (t: Throwable) {
        Log.w(TAG, "CastContext unavailable: ${t.message}")
        null
    }

    fun isCastAvailable(context: Context): Boolean = try {
        CastContext.getSharedInstance(context.applicationContext)
        true
    } catch (_: Throwable) {
        false
    }

    /** True when URL is likely to need JWT that Chromecast cannot send. */
    fun looksAuthenticated(streamUrl: String): Boolean {
        val uri = runCatching { URI(streamUrl) }.getOrNull() ?: return false
        val port = uri.port
        if (port == 8971) return true
        if (uri.scheme.equals("https", ignoreCase = true) && port != 5000) return true
        return false
    }

    /**
     * Open the system MediaRouter Cast device chooser when no session is active.
     */
    fun openCastRoutePicker(context: Context): Boolean {
        return try {
            val activity = context.findActivity() ?: run {
                Log.w(TAG, "No Activity to show MediaRouteChooserDialog")
                return false
            }
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(DEFAULT_RECEIVER_ID))
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()
            val dialog = MediaRouteChooserDialog(activity)
            dialog.routeSelector = selector
            dialog.show()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open Cast route picker: ${t.message}", t)
            false
        }
    }

    /**
     * Cast [streamUrl] if a session exists; otherwise open the Cast route picker.
     */
    fun castStream(
        context: Context,
        streamUrl: String,
        title: String,
        contentType: String = "application/x-mpegURL"
    ): CastOutcome {
        val session = castSession(context)
        if (session == null || !session.isConnected) {
            openCastRoutePicker(context)
            return CastOutcome.NoSession
        }
        val remote = session.remoteMediaClient ?: run {
            openCastRoutePicker(context)
            return CastOutcome.NoSession
        }
        if (looksAuthenticated(streamUrl)) {
            Log.w(TAG, "Cast URL may require JWT Chromecast cannot send: $streamUrl")
            // Still attempt load, but signal warning so UI can snackbar.
        }
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val info = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(contentType)
            .setMetadata(meta)
            .build()
        return try {
            remote.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .build()
            )
            if (looksAuthenticated(streamUrl)) CastOutcome.AuthUrlWarning else CastOutcome.Started
        } catch (t: Throwable) {
            Log.e(TAG, "Cast load failed: ${t.message}", t)
            CastOutcome.LoadFailed
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }
}
