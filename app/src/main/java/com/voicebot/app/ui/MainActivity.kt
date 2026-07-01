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
import com.voicebot.app.data.Prefs
import com.voicebot.app.databinding.ActivityMainBinding
import com.voicebot.app.recognition.ModelManager
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
            toast("Ключ сохранён")
        }

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        renderTemplates()
    }

    override fun onResume() {
        super.onResume()
        binding.a11yStatus.text = if (BotAccessibilityService.isRunning())
            "Служба управления: включена ✅"
        else
            "Служба управления: выключена ⚠️ (нажмите кнопку ниже)"
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
