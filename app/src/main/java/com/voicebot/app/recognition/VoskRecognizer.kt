package com.voicebot.app.recognition

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Wraps continuous offline recognition. Emits:
 *  - [onPartial]: live in-progress text (used only for UI feedback)
 *  - [onResult]: a finalized utterance (fed to the command router)
 */
class VoskRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    fun start(): Boolean {
        if (!ModelManager.isInstalled(context)) {
            onError("Модель распознавания не установлена")
            return false
        }
        return try {
            val m = Model(ModelManager.modelDir(context).absolutePath)
            model = m
            val recognizer = Recognizer(m, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(listener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognizer", e)
            onError("Ошибка запуска распознавания: ${e.message}")
            false
        }
    }

    fun stop() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = hypothesis?.let { JSONObject(it).optString("partial", "") } ?: ""
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onResult(hypothesis: String?) {
            val text = hypothesis?.let { JSONObject(it).optString("text", "") } ?: ""
            if (text.isNotBlank()) onResult(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            // Fires only when the service stops; onResult already delivered each
            // segment, so routing here too would double-execute commands.
        }

        override fun onError(exception: Exception?) {
            onError(exception?.message ?: "unknown error")
        }

        override fun onTimeout() {}
    }

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16000.0f
    }
}
