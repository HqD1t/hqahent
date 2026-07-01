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

            // ---- focus a text field: "текст" / "поле" -----------------------
            containsAny(text, "поле ввода", "найди поле") || text == "текст" -> {
                if (a11y()?.focusEditable() == true) {
                    toast("Поле готово. Скажите: «${prefs.wakeWord} ввод <текст>»")
                } else {
                    toast("Поле ввода не найдено")
                }
            }

            // ---- type into the focused field: "ввод <текст>" ----------------
            startsWithAny(text, "ввод", "введи", "напиши") -> {
                val toType = stripPrefix(text, "ввод", "введи", "напиши").trim()
                if (toType.isBlank()) toast("Скажите: «ввод <текст>»") else typeText(toType)
            }

            // ---- corner taps: "нажми верхний левый угол" --------------------
            containsAny(text, "угол") ||
                (containsAny(text, "клик", "нажми", "тапни", "тап") &&
                    containsAny(text, "верх", "низ", "лев", "прав")) -> tapCornerFrom(text)

            // ---- open chat / ticket: "открой 3 чат", "тикет 2678" -----------
            containsAny(text, "чат", "тикет", "диалог", "переписк") -> handleOpenChat(text)

            // ---- tap: "нажми <что-то>" / just "нажать" = клик по центру ------
            startsWithAny(text, "нажми", "тапни", "выбери", "кликни", "нажать", "клик", "тап") -> {
                val label = stripPrefix(
                    text, "нажми", "тапни", "выбери", "кликни", "нажать", "клик", "тап"
                )
                if (label.isBlank()) {
                    a11y()?.tapCenter()
                } else if (a11y()?.tapByText(label) != true) {
                    toast("Не нашёл на экране: «$label»")
                }
            }

            // ---- insert saved link: "ссылка <имя>" --------------------------
            startsWithAny(text, "вставь ссылку", "ссылка", "линк", "ссылку") -> {
                val name = stripPrefix(text, "вставь ссылку", "ссылку", "ссылка", "линк").trim()
                insertLink(name)
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

            // ---- unknown phrase: let DeepSeek figure out the intent ---------
            else -> interpretWithLlm(text)
        }
    }

    /**
     * Fallback for phrases that match no rule: ask the LLM to map the free-form
     * request to one concrete action, then execute it.
     */
    private fun interpretWithLlm(phrase: String) {
        if (prefs.apiKey.isBlank()) {
            toast("Команда не распознана: «$phrase»")
            return
        }
        scope.launch(Dispatchers.IO) {
            val action = try {
                LlmClient(prefs.apiKey).interpretCommand(phrase)
            } catch (e: Exception) {
                Log.e(TAG, "interpret failed", e); null
            }
            withContext(Dispatchers.Main) {
                if (action == null) toast("Не понял команду: «$phrase»")
                else executeSemanticAction(action.first, action.second, phrase)
            }
        }
    }

    private fun executeSemanticAction(action: String, arg: String, original: String) {
        when (action) {
            "go_home" -> a11y()?.goHome()
            "go_back" -> a11y()?.goBack()
            "recents" -> a11y()?.openRecents()
            "notifications" -> a11y()?.openNotifications()
            "scroll_up" -> a11y()?.scroll(BotAccessibilityService.Direction.UP)
            "scroll_down" -> a11y()?.scroll(BotAccessibilityService.Direction.DOWN)
            "volume_up" -> changeVolume(up = true)
            "volume_down" -> changeVolume(up = false)
            "tap_center" -> a11y()?.tapCenter()
            "tap_text" -> if (a11y()?.tapByText(arg) != true) toast("Не нашёл: «$arg»")
            "open_app" -> openApp(arg)
            "focus_field" -> if (a11y()?.focusEditable() != true) toast("Поле не найдено")
            "type_text" -> if (a11y()?.typeIntoFocusedField(arg) != true) toast("Нет поля ввода")
            "clear_all" -> if (a11y()?.clearFocusedField() != true) toast("Нет поля ввода")
            "delete_substring" -> deleteSubstring(arg)
            else -> toast("Не понял команду: «$original»")
        }
    }

    private fun deleteSubstring(sub: String) {
        val a = a11y() ?: return
        val current = a.getFocusedText()
        if (current == null) {
            toast("Нет поля ввода")
            return
        }
        val updated = if (sub.isBlank()) ""
        else current.replace(sub, "", ignoreCase = true).replace("  ", " ").trim()
        a.setFocusedText(updated)
    }

    private fun handleDictation(text: String) {
        if (containsAny(text, "стоп", "конец диктовки", "хватит")) {
            dictating = false
            toast("Диктовка завершена")
            return
        }
        typeText(text)
    }

    /** Optionally clean up [text] via the LLM, then type it into the focused field. */
    private fun typeText(text: String) {
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
                Log.e(TAG, "typeText failed", e)
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

    private fun insertLink(name: String) {
        val url = prefs.links()[name.lowercase()]
        if (url == null) {
            toast("Ссылка «$name» не найдена")
            return
        }
        if (a11y()?.typeIntoFocusedField(url) != true) {
            toast("Нет активного поля ввода")
        }
    }

    private fun tapCornerFrom(text: String) {
        val corner = when {
            text.contains("верх") && text.contains("лев") -> BotAccessibilityService.Corner.TOP_LEFT
            text.contains("верх") && text.contains("прав") -> BotAccessibilityService.Corner.TOP_RIGHT
            text.contains("низ") && text.contains("лев") -> BotAccessibilityService.Corner.BOTTOM_LEFT
            text.contains("низ") && text.contains("прав") -> BotAccessibilityService.Corner.BOTTOM_RIGHT
            else -> null
        }
        if (corner != null) a11y()?.tapCorner(corner)
        else toast("Скажите угол: верх/низ + лево/право")
    }

    private fun handleOpenChat(text: String) {
        val a = a11y() ?: return
        when {
            text.contains("тикет") -> {
                val q = text.substringAfter("тикет").trim()
                if (q.isBlank() || !a.tapByText(q)) toast("Тикет не найден: «$q»")
            }
            else -> {
                val n = parseNumber(text)
                if (n != null) {
                    if (!a.tapListItem(n)) toast("Не нашёл $n-й чат")
                } else {
                    val q = stripWords(
                        text, "открой", "запусти", "чат", "диалог", "переписку", "переписка"
                    )
                    if (q.isBlank() || !a.tapByText(q)) toast("Чат не найден: «$q»")
                }
            }
        }
    }

    /** First number in the phrase, as digits or a Russian numeral word (1..10). */
    private fun parseNumber(text: String): Int? {
        for (token in text.split(' ', ',', '-')) {
            token.trim().toIntOrNull()?.let { return it }
            NUMBER_WORDS[token.trim()]?.let { return it }
        }
        return null
    }

    private fun stripWords(text: String, vararg words: String): String {
        var s = text
        for (w in words) s = s.replace(w, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun openApp(query: String) {
        val pm = context.packageManager
        val target = query.trim().lowercase()
        if (target.isBlank()) return

        // Build a set of things to look for: the raw Russian word, its Latin
        // transliteration ("телеграм" -> "telegram"), and any known aliases.
        val needles = buildSet {
            add(target)
            add(translit(target))
            APP_ALIASES[target]?.let { add(it) }
            APP_ALIASES.forEach { (ru, en) -> if (target.contains(ru)) add(en) }
        }.filter { it.length >= 2 }

        try {
            val app = pm.getInstalledApplications(0).firstOrNull { info ->
                val label = pm.getApplicationLabel(info).toString().lowercase()
                val pkg = info.packageName.lowercase()
                needles.any { label.contains(it) || pkg.contains(it) }
            }
            val intent = app?.let { pm.getLaunchIntentForPackage(it.packageName) }
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

    /** Rough Cyrillic -> Latin transliteration for matching app names. */
    private fun translit(s: String): String = buildString {
        for (c in s.lowercase()) append(TRANSLIT[c] ?: c.toString())
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

        // Spoken Russian name -> substring found in the app's label/package.
        private val APP_ALIASES = mapOf(
            "телеграм" to "telegram", "телега" to "telegram", "тг" to "telegram",
            "ватсап" to "whatsapp", "вотсап" to "whatsapp",
            "вайбер" to "viber",
            "ютуб" to "youtube", "ютюб" to "youtube", "ютьюб" to "youtube",
            "вконтакте" to "vk", "вк" to "com.vkontakte",
            "инстаграм" to "instagram", "инста" to "instagram",
            "хром" to "chrome", "браузер" to "chrome",
            "почта" to "mail", "гмайл" to "gmail",
            "карты" to "maps", "стим" to "steam",
            "дискорд" to "discord", "спотифай" to "spotify",
            "тикток" to "tiktok", "плейстор" to "vending", "маркет" to "vending",
            "настройки" to "settings", "камера" to "camera",
            "галерея" to "gallery", "калькулятор" to "calcul",
            // --- user's apps (spoken RU -> label/package substring) ----------
            "эвэпэн" to "vpn", "впн" to "vpn",
            "запрет" to "zapret",
            "днс" to "dns",
            "хап" to "happ", "хапп" to "happ",
            "в2рейтун" to "v2raytun", "врейтун" to "v2raytun", "ви ту рей" to "v2raytun",
            "гугл плей" to "google play", "гугле плэй" to "google play", "плей маркет" to "google play",
            "ап сторе" to "store", "апп стор" to "store",
            "ашвид" to "hwid", "хвид" to "hwid",
            "коала клеш" to "koala", "коала" to "koala",
            "призрак бокс" to "prizrak", "призрак" to "prizrak",
            "некобокс" to "nekobox", "неко" to "neko",
            "хиддифай" to "hiddify", "каринг" to "karing",
            "флклеш" to "flclash", "клеш" to "clash",
            "мешцентрал" to "meshcentral", "миша" to "mesh",
            "рудесктоп" to "rudesktop", "рустор" to "rustore",
            "ютуб мьюзик" to "yt music", "рутуб" to "rutube",
            "твич" to "twitch", "уо мик" to "wo mic",
            "озон" to "ozon", "вайлдберриз" to "wildberries", "вб" to "wildberries",
            "сбербанк" to "сбер", "тбанк" to "т-банк", "тинькофф" to "т-банк",
            "пятерочка" to "пятёрочка", "яндекс го" to "яндексgo", "такси" to "яндексgo",
            "капкат" to "capcut", "капкут" to "capcut", "шазам" to "shazam",
            "брол старс" to "brawl", "бравл" to "brawl", "зона" to "zona",
        )

        // Russian numerals (cardinal + ordinal) 1..10 for "открой N чат".
        private val NUMBER_WORDS = mapOf(
            "один" to 1, "первый" to 1, "первую" to 1,
            "два" to 2, "две" to 2, "второй" to 2, "вторую" to 2,
            "три" to 3, "третий" to 3, "третью" to 3,
            "четыре" to 4, "четвёртый" to 4, "четвертый" to 4,
            "пять" to 5, "пятый" to 5,
            "шесть" to 6, "шестой" to 6,
            "семь" to 7, "седьмой" to 7,
            "восемь" to 8, "восьмой" to 8,
            "девять" to 9, "девятый" to 9,
            "десять" to 10, "десятый" to 10,
        )

        private val TRANSLIT: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya", ' ' to "",
        )
    }
}
