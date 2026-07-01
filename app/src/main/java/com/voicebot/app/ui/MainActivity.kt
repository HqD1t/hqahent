package com.voicebot.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicebot.app.R
import com.voicebot.app.data.Prefs
import com.voicebot.app.databinding.ActivityMainBinding
import com.voicebot.app.recognition.ModelManager
import com.voicebot.app.recognition.VoskRecognizer
import com.voicebot.app.service.BotAccessibilityService
import com.voicebot.app.service.VoiceService
import kotlin.concurrent.thread

/**
 * Control panel: master on/off toggle, permission shortcuts, API key,
 * grammar-fix switch and quick template management.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var testRecognizer: VoskRecognizer? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            ensureModelThenStart()
        } else {
            binding.toggle.isChecked = false
            toast("Нужен доступ к микрофону")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.apiKey.setText(prefs.apiKey)
        binding.wakeWord.setText(prefs.wakeWord)
        binding.grammarSwitch.isChecked = prefs.grammarFix
        binding.toggle.isChecked = prefs.enabled

        binding.toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) requestStart() else stop()
        }

        binding.grammarSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.grammarFix = checked
        }

        binding.saveKey.setOnClickListener {
            prefs.apiKey = binding.apiKey.text.toString()
            val name = binding.wakeWord.text.toString().trim()
            if (name.isNotBlank()) prefs.wakeWord = name
            toast("Сохранено. Имя бота: «${prefs.wakeWord}»")
        }

        binding.checkKey.setOnClickListener {
            val key = binding.apiKey.text.toString().trim()
            if (key.isBlank()) {
                toast("Сначала введите ключ")
                return@setOnClickListener
            }
            toast("Проверяю ключ…")
            thread {
                val err = com.voicebot.app.llm.LlmClient(key).check()
                runOnUiThread {
                    toast(if (err == null) "✅ Ключ работает" else "❌ $err")
                }
            }
        }

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.testMic.setOnClickListener { runMicTest() }

        binding.clearCrash.setOnClickListener {
            com.voicebot.app.App.clearCrash(this)
            showLastCrash()
            toast("Лог очищен")
        }

        binding.saveTemplate.setOnClickListener {
            val name = binding.templateName.text.toString().trim()
            val body = binding.templateBody.text.toString()
            if (name.isBlank() || body.isBlank()) {
                toast("Заполните имя и текст шаблона")
            } else {
                prefs.saveTemplate(name, body)
                binding.templateName.text?.clear()
                binding.templateBody.text?.clear()
                toast("Шаблон «$name» сохранён")
                renderTemplates()
            }
        }

        binding.deleteTemplate.setOnClickListener {
            val name = binding.templateDelete.text.toString().trim().lowercase()
            if (name.isBlank()) {
                toast("Введите имя шаблона")
            } else {
                prefs.deleteTemplate(name)
                binding.templateDelete.text?.clear()
                toast("Шаблон «$name» удалён")
                renderTemplates()
            }
        }

        binding.saveLink.setOnClickListener {
            val name = binding.linkName.text.toString().trim()
            val url = binding.linkBody.text.toString().trim()
            if (name.isBlank() || url.isBlank()) {
                toast("Заполните имя и ссылку")
            } else {
                prefs.saveLink(name, url)
                binding.linkName.text?.clear()
                binding.linkBody.text?.clear()
                toast("Ссылка «$name» сохранена")
                renderLinks()
            }
        }

        binding.deleteLink.setOnClickListener {
            val name = binding.linkDelete.text.toString().trim().lowercase()
            if (name.isBlank()) {
                toast("Введите имя ссылки")
            } else {
                prefs.deleteLink(name)
                binding.linkDelete.text?.clear()
                toast("Ссылка «$name» удалена")
                renderLinks()
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.pager.displayedChild = when (item.itemId) {
                R.id.nav_templates -> 1
                R.id.nav_links -> 2
                else -> 0
            }
            true
        }

        renderTemplates()
        renderLinks()
    }

    override fun onResume() {
        super.onResume()
        binding.a11yStatus.text = if (BotAccessibilityService.isRunning())
            "Служба управления: включена ✅"
        else
            "Служба управления: выключена ⚠️ (нажмите кнопку ниже)"
        refreshStatus()
        showLastCrash()
    }

    private fun showLastCrash() {
        val crash = com.voicebot.app.App.readLastCrash(this)
        val visible = if (crash.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.crashTitle.visibility = visible
        binding.crashLog.visibility = visible
        binding.clearCrash.visibility = visible
        binding.crashLog.text = crash ?: ""
    }

    private fun refreshStatus() {
        val mic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val model = ModelManager.isInstalled(this)
        binding.statusInfo.text = buildString {
            append(if (mic) "Микрофон: разрешён ✅" else "Микрофон: НЕТ доступа ❌")
            append("\n")
            append(if (model) "Модель Vosk: установлена ✅" else "Модель Vosk: НЕ установлена ❌")
            append("\n")
            append("Имя бота: «${prefs.wakeWord}»")
        }
    }

    /**
     * Isolated recognition test — bypasses the service and wake-word logic to
     * prove whether the mic + Vosk model actually produce text on this device.
     */
    private fun runMicTest() {
        if (prefs.enabled) {
            toast("Сначала выключите бота (верхний тумблер) — микрофон занят")
            return
        }
        val mic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!mic) {
            micPermission.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            )
            return
        }
        if (!ModelManager.isInstalled(this)) {
            binding.micResult.text = "Скачиваю модель…"
            thread {
                val ok = ModelManager.download(this) {}
                runOnUiThread {
                    refreshStatus()
                    if (ok) runMicTest()
                    else binding.micResult.text = "❌ Модель не скачалась. Проверьте интернет."
                }
            }
            return
        }

        testRecognizer?.stop()
        binding.micResult.text = "🎤 Говорите…"
        testRecognizer = VoskRecognizer(
            context = this,
            emitPartial = { p -> runOnUiThread { if (p.isNotBlank()) binding.micResult.text = "Слышу: $p" } },
            emitResult = { t -> runOnUiThread { binding.micResult.text = "Распознано: $t" } },
            emitError = { e -> runOnUiThread { binding.micResult.text = "❌ Ошибка: $e" } },
        )
        val started = testRecognizer?.start() == true
        if (!started) {
            binding.micResult.text = "❌ Не удалось запустить распознавание"
            return
        }
        // Auto-stop after 10 seconds.
        binding.testMic.postDelayed({
            testRecognizer?.stop()
            testRecognizer = null
            if (binding.micResult.text.toString().startsWith("🎤")) {
                binding.micResult.text = "Тишина — микрофон не дал звука. Проверьте доступ к микрофону в фоне."
            }
        }, 10_000)
    }

    override fun onPause() {
        super.onPause()
        testRecognizer?.stop()
        testRecognizer = null
    }

    private fun requestStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) ensureModelThenStart()
        else micPermission.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    private fun ensureModelThenStart() {
        if (ModelManager.isInstalled(this)) {
            actuallyStart()
            return
        }
        toast("Скачиваю модель распознавания (~45 МБ)…")
        binding.toggle.isEnabled = false
        thread {
            val ok = ModelManager.download(this) { /* progress */ }
            runOnUiThread {
                binding.toggle.isEnabled = true
                if (ok) {
                    actuallyStart()
                } else {
                    binding.toggle.isChecked = false
                    toast("Не удалось скачать модель. Проверьте интернет.")
                }
            }
        }
    }

    private fun actuallyStart() {
        prefs.enabled = true
        VoiceService.start(this)
        toast("Бот включён")
        if (!BotAccessibilityService.isRunning()) {
            toast("Для управления включите службу спец. возможностей")
        }
    }

    private fun stop() {
        prefs.enabled = false
        VoiceService.stop(this)
        toast("Бот выключен")
    }

    private fun renderTemplates() {
        val list = prefs.templates()
        binding.templatesList.text = if (list.isEmpty()) "— пусто —"
        else list.entries.joinToString("\n") { "• ${it.key}: ${it.value.take(40)}" }
    }

    private fun renderLinks() {
        val list = prefs.links()
        binding.linksList.text = if (list.isEmpty()) "— пусто —"
        else list.entries.joinToString("\n") { "• ${it.key}: ${it.value.take(50)}" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
