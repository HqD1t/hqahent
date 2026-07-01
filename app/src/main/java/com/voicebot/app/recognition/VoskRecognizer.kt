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
 *  - [emitPartial]: live in-progress text (used only for UI feedback)
 *  - [emitResult]: a finalized utterance (fed to the command router)
 *
 * NOTE: the callbacks are deliberately NOT named onResult/onError — those clash
 * with [RecognitionListener]'s own methods inside the anonymous object and would
 * recurse instead of calling out.
 */
class VoskRecognizer(
    private val context: Context,
    private val emitPartial: (String) -> Unit,
    private val emitResult: (String) -> Unit,
    private val emitError: (String) -> Unit,
) {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    fun start(): Boolean {
        if (!ModelManager.isInstalled(context)) {
            emitError("Модель распознавания не установлена")
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
            emitError("Ошибка запуска распознавания: ${e.message}")
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
            emitPartial(parse(hypothesis, "partial"))
        }

        override fun onResult(hypothesis: String?) {
            val text = parse(hypothesis, "text")
            if (text.isNotBlank()) emitResult(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            // Fires only when the service stops; onResult already delivered each
            // segment, so routing here too would double-execute commands.
        }

        override fun onError(exception: Exception?) {
            emitError(exception?.message ?: "unknown error")
        }

        override fun onTimeout() {}

        /** Vosk returns a JSON string like {"text":"..."}; extract [key] safely. */
        private fun parse(hypothesis: String?, key: String): String {
            if (hypothesis.isNullOrBlank()) return ""
            return try {
                JSONObject(hypothesis).optString(key, "")
            } catch (e: Exception) {
                ""
            }
        }
    }

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16000.0f
    }
}
