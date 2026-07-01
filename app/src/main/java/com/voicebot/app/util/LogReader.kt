package com.voicebot.app.util

/**
 * Reads the app's own logcat output. On modern Android a normal app can only
 * see its own process logs — which is exactly what we want for diagnostics.
 */
object LogReader {

    enum class Level { ALL, WARN, ERROR }

    fun read(level: Level): String {
        val spec = when (level) {
            Level.ERROR -> "*:E"
            Level.WARN -> "*:W"
            Level.ALL -> "*:V"
        }
        return try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-v", "time", spec))
            val out = process.inputStream.bufferedReader().readText()
            process.destroy()
            when {
                out.isBlank() -> "— записей нет —"
                out.length > MAX_CHARS -> "…\n" + out.takeLast(MAX_CHARS)
                else -> out
            }
        } catch (e: Exception) {
            "Не удалось прочитать логи: ${e.message}"
        }
    }

    private const val MAX_CHARS = 40000
}
