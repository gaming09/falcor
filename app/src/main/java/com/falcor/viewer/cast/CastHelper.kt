package com.falcor.viewer.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.images.WebImage

/**
 * Cast the currently focused camera stream (single HLS/MJPEG URL) to a Cast device.
 * Dashboard multi-cam cast is not supported without a custom receiver.
 *
 * Limitation: Chromecast often cannot send Frigate JWT cookies; unauthenticated
 * LAN URLs (port 5000) cast more reliably than :8971 with auth.
 */
object CastHelper {
    private const val TAG = "CastHelper"

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

    fun castStream(
        context: Context,
        streamUrl: String,
        title: String,
        contentType: String = "application/x-mpegURL"
    ): Boolean {
        val session = castSession(context) ?: return false
        val remote = session.remoteMediaClient ?: return false
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
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Cast load failed: ${t.message}", t)
            false
        }
    }
}
