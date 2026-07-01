package com.voicebot.app.command

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import com.voicebot.app.data.Prefs
import com.voicebot.app.llm.LlmClient
import com.voicebot.app.service.BotAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns a finalized recognized phrase into an action.
 *
 * Two modes:
 *  - command mode (default): match trigger keywords -> navigation / control
 *  - dictation mode: entered by "печатай"/"пиши", everything after is typed into
 *    the focused field (optionally cleaned up by Claude first).
 */
class CommandRouter(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {

    @Volatile private var dictating = false
    @Volatile private var armedUntil = 0L

    // Created lazily and defensively: ToneGenerator can throw "Init failed" on
    // some devices, and we must never let that crash the whole process.
    private val tone: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            null
        }
    }

    /**
     * Entry point for every finalized phrase. Wrapped so that a failure in one
     * command can never crash the whole listening service.
     */
    fun handle(rawText: String) {
        try {
            dispatch(rawText)
        } catch (e: Exception) {
            Log.e(TAG, "Command handling failed for: $rawText", e)
            toast("Ошибка выполнения команды")
        }
    }

    private fun dispatch(rawText: String) {
        val text = rawText.trim().lowercase()
        if (text.isBlank()) return

        // In dictation mode everything is text, no wake word needed.
        if (dictating) {
            handleDictation(text)
            return
        }

        val wake = prefs.wakeWord.trim().lowercase()

        // Wake word disabled -> every phrase is a command.
        if (wake.isBlank()) {
            route(text)
            return
        }

        val idx = text.indexOf(wake)
        if (idx >= 0) {
            // The name was heard. Beep, then run whatever follows it in the same
            // phrase. If nothing follows, "arm" the bot so the NEXT phrase (which
            // Vosk may deliver as a separate segment) is treated as the command.
            beep()
            val remainder = text.substring(idx + wake.length)
                .trimStart(' ', ',', '.', '!')
            if (remainder.isNotBlank()) {
                armedUntil = 0L
                route(remainder)
            } else {
                armedUntil = System.currentTimeMillis() + WAKE_WINDOW_MS
            }
            return
        }

        // No name in this phrase: only act if the name was said just before.
        if (System.currentTimeMillis() <= armedUntil) {
            armedUntil = 0L
            route(text)
        }
        // otherwise: not addressed to the bot -> ignore
    }

    /** Short confirmation beep when the wake word is recognized. */
    private fun beep() {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
        }
    }

    private fun route(text: String) {
        when {
            // ---- enter/exit dictation ---------------------------------------
            startsWithAny(text, "печатай", "пиши", "диктовка", "напечатай") -> {
                dictating = true
                val remainder = stripPrefix(text, "печатай", "пиши", "диктовка", "напечатай")
                toast("Режим диктовки. Скажите «стоп» для завершения.")
                if (remainder.isNotBlank()) handleDictation(remainder)
            }

            // ---- navigation -------------------------------------------------
            containsAny(text, "домой", "на главный", "рабочий стол") -> a11y()?.goHome()
            containsAny(text, "назад", "вернись") -> a11y()?.goBack()
            containsAny(text, "последние", "недавние", "переключить приложение") ->
                a11y()?.openRecents()
            containsAny(text, "шторка", "уведомления") -> a11y()?.openNotifications()

            // ---- scrolling --------------------------------------------------
            containsAny(text, "прокрути вниз", "листай вниз", "вниз") ->
                a11y()?.scroll(BotAccessibilityService.Direction.DOWN)
            containsAny(text, "прокрути вверх", "листай вверх", "вверх") ->
                a11y()?.scroll(BotAccessibilityService.Direction.UP)

            // ---- volume -----------------------------------------------------
            containsAny(text, "громче", "прибавь звук") -> changeVolume(up = true)
            containsAny(text, "тише", "убавь звук") -> changeVolume(up = false)

            // ---- tap by visible label: "нажми <что-то>" ---------------------
            startsWithAny(text, "нажми", "тапни", "выбери", "кликни") -> {
                val label = stripPrefix(text, "нажми", "тапни", "выбери", "кликни")
                if (label.isNotBlank() && a11y()?.tapByText(label) != true) {
                    toast("Не нашёл на экране: «$label»")
                }
            }

            // ---- open app: "открой <название>" ------------------------------
            startsWithAny(text, "открой", "запусти") -> {
                val name = stripPrefix(text, "открой", "запусти")
                openApp(name)
            }

            // ---- insert saved template: "шаблон <имя>" ----------------------
            startsWithAny(text, "шаблон", "вставь шаблон") -> {
                val name = stripPrefix(text, "шаблон", "вставь шаблон").trim()
                insertTemplate(name)
            }

            else -> toast("Команда не распознана: «$text»")
        }
    }

    private fun handleDictation(text: String) {
        if (containsAny(text, "стоп", "конец диктовки", "хватит")) {
            dictating = false
            toast("Диктовка завершена")
            return
        }
        // Correct in background, then type into the focused field.
        scope.launch(Dispatchers.IO) {
            try {
                val out = if (prefs.grammarFix && prefs.apiKey.isNotBlank()) {
                    LlmClient(prefs.apiKey).correctText(text)
                } else text
                withContext(Dispatchers.Main) {
                    if (a11y()?.typeIntoFocusedField(out) != true) {
                        toast("Нет активного поля ввода")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dictation failed", e)
                toast("Ошибка обработки текста")
            }
        }
    }

    private fun insertTemplate(name: String) {
        val body = prefs.templates()[name.lowercase()]
        if (body == null) {
            toast("Шаблон «$name» не найден")
            return
        }
        if (a11y()?.typeIntoFocusedField(body) != true) {
            toast("Нет активного поля ввода")
        }
    }

    private fun openApp(query: String) {
        val pm = context.packageManager
        val target = query.trim()
        if (target.isBlank()) return
        try {
            val intent = pm.getInstalledApplications(0)
                .firstOrNull {
                    val label = pm.getApplicationLabel(it).toString().lowercase()
                    label.contains(target) || target.contains(label)
                }
                ?.let { pm.getLaunchIntentForPackage(it.packageName) }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                toast("Приложение «$target» не найдено")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp failed for '$target'", e)
            toast("Не удалось открыть «$target»")
        }
    }

    private fun changeVolume(up: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun a11y(): BotAccessibilityService? {
        val s = BotAccessibilityService.instance
        if (s == null) toast("Включите службу спец. возможностей в настройках")
        return s
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- text helpers -------------------------------------------------------

    private fun containsAny(text: String, vararg keys: String) =
        keys.any { text.contains(it) }

    private fun startsWithAny(text: String, vararg keys: String) =
        keys.any { text.startsWith(it) }

    private fun stripPrefix(text: String, vararg prefixes: String): String {
        for (p in prefixes) if (text.startsWith(p)) return text.removePrefix(p).trim()
        return text
    }

    companion object {
        private const val TAG = "CommandRouter"
        // How long after hearing the name the next phrase counts as a command.
        private const val WAKE_WINDOW_MS = 8000L
    }
}
