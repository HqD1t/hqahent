package com.voicebot.app.command

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.Toast
import com.voicebot.app.data.Prefs
import com.voicebot.app.llm.ClaudeClient
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

    fun handle(rawText: String) {
        val text = rawText.trim().lowercase()
        if (text.isBlank()) return

        // In dictation mode everything is text, no wake word needed.
        if (dictating) {
            handleDictation(text)
            return
        }

        // Outside dictation the phrase must be addressed to the bot by name,
        // e.g. "боб, домой". Everything before/including the name is stripped.
        val command = extractAfterWakeWord(text) ?: return
        if (command.isBlank()) return

        route(command)
    }

    /**
     * Returns the command part after the wake word, or null if the phrase
     * isn't addressed to the bot. If no wake word is configured, passes through.
     */
    private fun extractAfterWakeWord(text: String): String? {
        val wake = prefs.wakeWord.trim().lowercase()
        if (wake.isBlank()) return text
        val idx = text.indexOf(wake)
        if (idx < 0) return null
        // Drop the wake word and any trailing comma/space after it.
        return text.substring(idx + wake.length).trimStart(' ', ',', '.', '!')
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
            val out = if (prefs.grammarFix && prefs.apiKey.isNotBlank()) {
                ClaudeClient(prefs.apiKey).correctText(text)
            } else text
            withContext(Dispatchers.Main) {
                if (a11y()?.typeIntoFocusedField(out) != true) {
                    toast("Нет активного поля ввода")
                }
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
        val intent = pm.getInstalledApplications(0)
            .firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(target)
            }
            ?.let { pm.getLaunchIntentForPackage(it.packageName) }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            toast("Приложение «$target» не найдено")
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
}
