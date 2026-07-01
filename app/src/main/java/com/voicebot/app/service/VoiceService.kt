package com.voicebot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicebot.app.R
import com.voicebot.app.command.CommandRouter
import com.voicebot.app.data.Prefs
import com.voicebot.app.recognition.VoskRecognizer
import com.voicebot.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Always-on foreground service. Owns the microphone + recognizer and forwards
 * finalized phrases to [CommandRouter]. Started/stopped by the master toggle.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var router: CommandRouter
    private var recognizer: VoskRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        router = CommandRouter(this, prefs, scope)
        startForeground(NOTIF_ID, buildNotification("Слушаю…"))
        startRecognition()
    }

    private fun startRecognition() {
        recognizer = VoskRecognizer(
            context = this,
            onPartial = { partial ->
                // Live feedback: proves the mic + recognizer are actually working.
                if (partial.isNotBlank()) updateNotification("Слышу: $partial")
            },
            onResult = { text ->
                // Show what was heard so behaviour is debuggable at a glance.
                updateNotification("Услышал: $text")
                router.handle(text)
            },
            onError = { msg -> updateNotification("Ошибка: $msg") },
        )
        val started = recognizer?.start() == true
        if (!started) {
            updateNotification("Распознавание не запущено — см. статус в приложении")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        recognizer?.stop()
        recognizer = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification -------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Голосовой бот активен")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Голосовой бот",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "voicebot_service"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
