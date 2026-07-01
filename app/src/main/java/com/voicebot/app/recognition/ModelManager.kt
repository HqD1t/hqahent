package com.voicebot.app.recognition

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small Russian Vosk model on first launch, so the APK
 * itself stays small. The model lives in the app's private files dir afterward.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val DIR_NAME = "vosk-model-small-ru-0.22"

    fun modelDir(context: Context): File =
        File(context.filesDir, DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        // A valid model has an "am" acoustic-model subfolder.
        return dir.isDirectory && File(dir, "am").isDirectory
    }

    /**
     * Blocking download + unzip. Call from a background thread.
     * [onProgress] receives 0..100 (approximate).
     */
    fun download(context: Context, onProgress: (Int) -> Unit): Boolean {
        if (isInstalled(context)) return true
        return try {
            val http = OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
            val request = Request.Builder().url(MODEL_URL).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Model download failed: ${resp.code}")
                    return false
                }
                val body = resp.body ?: return false
                val total = body.contentLength().coerceAtLeast(1)
                var read = 0L
                ZipInputStream(body.byteStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        // Entries are prefixed with the model dir name already.
                        val outFile = File(context.filesDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(16 * 1024)
                                var n = zip.read(buf)
                                while (n >= 0) {
                                    out.write(buf, 0, n)
                                    read += n
                                    onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                                    n = zip.read(buf)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            isInstalled(context)
        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            false
        }
    }
}
