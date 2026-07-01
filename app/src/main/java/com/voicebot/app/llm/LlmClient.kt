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
    }
}
