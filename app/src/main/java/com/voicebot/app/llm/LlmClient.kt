package com.voicebot.app.llm

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cleans up dictated Russian text via the DeepSeek API (OpenAI-compatible
 * chat/completions endpoint): fixes recognition errors, punctuation and grammar
 * while keeping the meaning.
 *
 * Blocking — call from a background thread only.
 */
class LlmClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a corrected version of [raw]. On any failure returns [raw]
     * unchanged so dictation is never lost to a network hiccup.
     */
    fun correctText(raw: String): String {
        if (apiKey.isBlank()) return raw
        return try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("stream", false)
                put("messages", JSONArray()
                    .put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    .put(JSONObject().apply {
                        put("role", "user")
                        put("content", raw)
                    })
                )
            }
            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "DeepSeek error ${resp.code}: $body")
                    return raw
                }
                val choices = JSONObject(body).optJSONArray("choices") ?: return raw
                if (choices.length() == 0) return raw
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", raw)
                    .trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DeepSeek request failed", e)
            raw
        }
    }

    /**
     * Interprets a free-form Russian phrase as one device action. Returns
     * (action, arg) or null if the model couldn't decide / request failed.
     * Blocking — call off the main thread.
     */
    fun interpretCommand(phrase: String): Pair<String, String>? {
        if (apiKey.isBlank()) return null
        return try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("stream", false)
                put("max_tokens", 120)
                put("temperature", 0)
                put("messages", JSONArray()
                    .put(JSONObject().apply {
                        put("role", "system"); put("content", COMMAND_PROMPT)
                    })
                    .put(JSONObject().apply {
                        put("role", "user"); put("content", phrase)
                    })
                )
            }
            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return null
                val content = JSONObject(body).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").optString("content", "")
                // The model may wrap JSON in prose/fences — extract the object.
                val start = content.indexOf('{')
                val end = content.lastIndexOf('}')
                if (start < 0 || end <= start) return null
                val obj = JSONObject(content.substring(start, end + 1))
                obj.optString("action", "none") to obj.optString("arg", "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "interpretCommand failed", e)
            null
        }
    }

    /**
     * Verifies the key with a tiny request. Returns null on success, or a
     * human-readable error string on failure. Blocking — call off the main thread.
     */
    fun check(): String? {
        if (apiKey.isBlank()) return "Ключ не задан"
        return try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("stream", false)
                put("max_tokens", 1)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ok")
                }))
            }
            val request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) null
                else "Ошибка ${resp.code}: ${resp.body?.string()?.take(120)}"
            }
        } catch (e: Exception) {
            "Нет связи: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "LlmClient"
        private const val MODEL = "deepseek-chat"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val SYSTEM_PROMPT =
            "Ты — редактор русского текста, надиктованного голосом. " +
            "Исправь ошибки распознавания, орфографию, пунктуацию и грамматику. " +
            "Сохрани исходный смысл и стиль. Не добавляй ничего от себя, " +
            "не отвечай на текст, не давай комментариев. " +
            "Верни ТОЛЬКО исправленный текст без кавычек и пояснений."

        private const val COMMAND_PROMPT =
            "Ты — интерпретатор голосовых команд для управления Android-телефоном. " +
            "Пользователь говорит фразу по-русски. Выбери РОВНО ОДНО действие из списка " +
            "и верни СТРОГО JSON вида {\"action\":\"...\",\"arg\":\"...\"} без пояснений и без markdown.\n" +
            "Список действий (action):\n" +
            "go_home — на главный экран;\n" +
            "go_back — назад/выйти;\n" +
            "recents — недавние приложения;\n" +
            "notifications — открыть шторку уведомлений;\n" +
            "scroll_up — прокрутить вверх;\n" +
            "scroll_down — прокрутить вниз;\n" +
            "scroll_left — прокрутить влево;\n" +
            "scroll_right — прокрутить вправо;\n" +
            "volume_up — громче;\n" +
            "volume_down — тише;\n" +
            "quick_settings — быстрые настройки (Wi-Fi, фонарик);\n" +
            "all_apps — открыть меню всех приложений;\n" +
            "power_menu — меню питания (выключение);\n" +
            "screenshot — сделать скриншот;\n" +
            "lock_screen — заблокировать экран;\n" +
            "tap_center — просто нажать/клик по центру;\n" +
            "tap_text — нажать по надписи/кнопке на экране (arg = текст, ищет прокруткой);\n" +
            "long_press — долгое нажатие по надписи (arg = текст);\n" +
            "open_app — открыть приложение (arg = название);\n" +
            "focus_field — поставить курсор в поле ввода;\n" +
            "type_text — впечатать текст (arg = что написать);\n" +
            "clear_all — стереть/удалить весь текст в поле;\n" +
            "delete_substring — удалить из поля конкретный фрагмент (arg = что удалить);\n" +
            "none — если непонятно.\n" +
            "Если arg не нужен — оставь пустую строку. Примеры: " +
            "«сотри всё» -> {\"action\":\"clear_all\",\"arg\":\"\"}; " +
            "«удали слово привет» -> {\"action\":\"delete_substring\",\"arg\":\"привет\"}; " +
            "«свернись» -> {\"action\":\"go_home\",\"arg\":\"\"}."
    }
}
