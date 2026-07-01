package com.voicebot.app.recognition

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.voicebot.app.data.Prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max

/**
 * Continuous offline recognition with our OWN audio pipeline (not Vosk's
 * SpeechService), so we can improve far-field pickup:
 *  - request the hardware noise suppressor / echo canceller / auto gain
 *  - apply extra software gain (configurable) to boost quiet/distant speech
 *
 * Emits:
 *  - [emitPartial]: live in-progress text (UI feedback)
 *  - [emitResult]: a finalized utterance (fed to the command router)
 */
class VoskRecognizer(
    private val context: Context,
    private val emitPartial: (String) -> Unit,
    private val emitResult: (String) -> Unit,
    private val emitError: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    fun start(): Boolean {
        if (!ModelManager.isInstalled(context)) {
            emitError("Модель распознавания не установлена")
            return false
        }
        return try {
            val m = Model(ModelManager.modelDir(context).absolutePath)
            model = m
            recognizer = Recognizer(m, SAMPLE_RATE.toFloat())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = max(minBuf, SAMPLE_RATE) // ~1s of headroom
            @Suppress("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                emitError("Микрофон недоступен")
                return false
            }
            audioRecord = rec
            enableEffects(rec.audioSessionId)

            rec.startRecording()
            running = true
            thread = Thread { loop() }.also { it.start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognizer", e)
            emitError("Ошибка запуска распознавания: ${e.message}")
            stop()
            false
        }
    }

    private fun loop() {
        val rec = audioRecord ?: return
        val r = recognizer ?: return
        val gain = Prefs(context).micGain
        val buffer = ShortArray(BUFFER_SAMPLES)
        while (running) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            if (gain > 1f) applyGain(buffer, n, gain)
            try {
                if (r.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(r.result).optString("text", "")
                    if (text.isNotBlank()) main.post { emitResult(text) }
                } else {
                    val partial = JSONObject(r.partialResult).optString("partial", "")
                    if (partial.isNotBlank()) main.post { emitPartial(partial) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode error", e)
            }
        }
    }

    /** Amplify each sample by [gain] with hard clipping to 16-bit range. */
    private fun applyGain(buf: ShortArray, len: Int, gain: Float) {
        for (i in 0 until len) {
            val v = (buf[i] * gain).toInt()
            buf[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toShort()
            }
        }
    }

    private fun enableEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(sessionId)?.also { it.setEnabled(true); effects.add(it) }
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true); effects.add(it) }
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(sessionId)?.also { it.setEnabled(true); effects.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "audio effects unavailable", e)
        }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        audioRecord?.let {
            try { if (it.state == AudioRecord.STATE_INITIALIZED) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SAMPLES = 3200 // 0.2s chunks
    }
}
