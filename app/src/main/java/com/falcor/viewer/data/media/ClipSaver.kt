package com.falcor.viewer.data.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object ClipSaver {
    sealed class SaveResult {
        data class Success(val displayName: String) : SaveResult()
        data class Failed(val message: String) : SaveResult()
    }

    suspend fun saveToDownloads(
        context: Context,
        client: OkHttpClient,
        url: String,
        displayName: String
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SaveResult.Failed("HTTP ${response.code}")
                }
                val body = response.body ?: return@withContext SaveResult.Failed("Empty body")
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .let { if (it.endsWith(".mp4")) it else "$it.mp4" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext SaveResult.Failed("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { it.copyTo(out) }
                    } ?: return@withContext SaveResult.Failed("Cannot open output")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    SaveResult.Success(safeName)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, safeName)
                    FileOutputStream(file).use { out ->
                        body.byteStream().use { it.copyTo(out) }
                    }
                    SaveResult.Success(safeName)
                }
            }
        } catch (t: Throwable) {
            SaveResult.Failed(t.message ?: "Save failed")
        }
    }

    suspend fun saveLocalFileToDownloads(
        context: Context,
        localFile: File,
        displayName: String
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".mp4")) it else "$it.mp4" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext SaveResult.Failed("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { out ->
                    localFile.inputStream().use { it.copyTo(out) }
                } ?: return@withContext SaveResult.Failed("Cannot open output")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SaveResult.Success(safeName)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, safeName)
                localFile.copyTo(file, overwrite = true)
                SaveResult.Success(safeName)
            }
        } catch (t: Throwable) {
            SaveResult.Failed(t.message ?: "Save failed")
        }
    }
}
