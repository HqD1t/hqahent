package com.voicebot.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for persisted settings:
 * - master on/off toggle
 * - Claude API key
 * - trigger keywords -> command mapping
 * - saved text templates
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("voicebot", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** Whether to run recognized dictation through Claude for grammar/style fixes. */
    var grammarFix: Boolean
        get() = sp.getBoolean(KEY_GRAMMAR, true)
        set(value) = sp.edit().putBoolean(KEY_GRAMMAR, value).apply()

    /** Wake word: the bot only reacts to phrases starting with this name. */
    var wakeWord: String
        get() = sp.getString(KEY_WAKE, "боб") ?: "боб"
        set(value) = sp.edit().putString(KEY_WAKE, value.trim().lowercase()).apply()

    // ---- Templates: name -> body -------------------------------------------

    fun templates(): Map<String, String> {
        val raw = sp.getString(KEY_TEMPLATES, null) ?: return defaultTemplates()
        val obj = JSONObject(raw)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    fun saveTemplate(name: String, body: String) {
        val obj = JSONObject()
        templates().forEach { (k, v) -> obj.put(k, v) }
        obj.put(name.trim().lowercase(), body)
        sp.edit().putString(KEY_TEMPLATES, obj.toString()).apply()
    }

    fun deleteTemplate(name: String) {
        val obj = JSONObject()
        templates().filterKeys { it != name }.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString(KEY_TEMPLATES, obj.toString()).apply()
    }

    // ---- Links: name -> url -------------------------------------------------

    fun links(): Map<String, String> {
        val raw = sp.getString(KEY_LINKS, null) ?: return emptyMap()
        val obj = JSONObject(raw)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    fun saveLink(name: String, url: String) {
        val obj = JSONObject()
        links().forEach { (k, v) -> obj.put(k, v) }
        obj.put(name.trim().lowercase(), url.trim())
        sp.edit().putString(KEY_LINKS, obj.toString()).apply()
    }

    fun deleteLink(name: String) {
        val obj = JSONObject()
        links().filterKeys { it != name }.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString(KEY_LINKS, obj.toString()).apply()
    }

    // ---- Trigger keywords ---------------------------------------------------

    /** Extra user-defined phrase -> template-name links. */
    fun customTriggers(): List<String> {
        val raw = sp.getString(KEY_TRIGGERS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun defaultTemplates(): Map<String, String> = mapOf(
        "приветствие" to "Здравствуйте! Спасибо, что связались со мной.",
        "подпись" to "С уважением,\n"
    )

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_GRAMMAR = "grammar_fix"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_LINKS = "links"
        private const val KEY_TRIGGERS = "triggers"
        private const val KEY_WAKE = "wake_word"
    }
}
