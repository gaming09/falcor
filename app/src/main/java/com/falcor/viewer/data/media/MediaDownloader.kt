package com.falcor.viewer.data.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads Frigate media (clip.mp4, recording windows) through the trusted OkHttp
 * client (JWT + self-signed TLS), writing to the app cache so LibVLC can play via file://.
 */
object MediaDownloader {
    private const val TAG = "MediaDownloader"

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class NotFound(val url: String) : DownloadResult()
        data class Failed(val message: String, val code: Int? = null) : DownloadResult()
    }

    suspend fun downloadToCache(
        context: Context,
        client: OkHttpClient,
        url: String,
        filePrefix: String = "falcor_clip"
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        // Stable name from URL hash so scrubbing the same window reuses cache briefly
        val safeName = "${filePrefix}_${url.hashCode().toUInt().toString(16)}.mp4"
        val out = File(dir, safeName)
        if (out.exists() && out.length() > 0L) {
            Log.d(TAG, "Cache hit ${out.name} (${out.length()} bytes)")
            return@withContext DownloadResult.Success(out)
        }
        val tmp = File(dir, "$safeName.part")
        try {
            if (tmp.exists()) tmp.delete()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404, 400 -> return@withContext DownloadResult.NotFound(url)
                    else -> if (!response.isSuccessful) {
                        return@withContext DownloadResult.Failed(
                            "HTTP ${response.code}",
                            response.code
                        )
                    }
                }
                val body = response.body
                    ?: return@withContext DownloadResult.Failed("Empty body")
                tmp.outputStream().use { sink ->
                    body.byteStream().use { src -> src.copyTo(sink) }
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return@withContext DownloadResult.Failed("Empty download")
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                Log.d(TAG, "Downloaded ${out.name} (${out.length()} bytes) from $url")
                DownloadResult.Success(out)
            }
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "Download failed: ${e.message}")
            DownloadResult.Failed(e.message ?: "Network error")
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "Download failed: ${t.message}")
            DownloadResult.Failed(t.message ?: "Error")
        }
    }

    fun clearOldCache(context: Context, maxAgeMs: Long = 2 * 60 * 60 * 1000L) {
        val dir = File(context.cacheDir, "media")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }
}
