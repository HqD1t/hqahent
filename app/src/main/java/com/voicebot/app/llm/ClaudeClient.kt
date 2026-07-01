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
 * Thin wrapper over the Anthropic Messages API. Used to clean up dictated text:
 * fix recognition errors, restore punctuation and grammar, keep the meaning.
 *
 * Runs on a background thread only (blocking call).
 */
class ClaudeClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a corrected version of [raw]. On any failure returns [raw] unchanged
     * so dictation never gets lost because the network hiccuped.
     */
    fun correctText(raw: String): String {
        if (apiKey.isBlank()) return raw
        return try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 1024)
                put("system", SYSTEM_PROMPT)
                put("messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", raw)
                    }
                ))
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Claude error ${resp.code}: $body")
                    return raw
                }
                val content = JSONObject(body).getJSONArray("content")
                if (content.length() == 0) return raw
                content.getJSONObject(0).optString("text", raw).trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Claude request failed", e)
            raw
        }
    }

    companion object {
        private const val TAG = "ClaudeClient"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val SYSTEM_PROMPT =
            "Ты — редактор русского текста, надиктованного голосом. " +
            "Исправь ошибки распознавания, орфографию, пунктуацию и грамматику. " +
            "Сохрани исходный смысл и стиль. Не добавляй ничего от себя, " +
            "не отвечай на текст, не давай комментариев. " +
            "Верни ТОЛЬКО исправленный текст без кавычек и пояснений."
    }
}
