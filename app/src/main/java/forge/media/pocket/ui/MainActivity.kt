package forge.media.pocket.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.service.ServiceStatus
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var configStore: NodeConfigStore
    private lateinit var powerHelper: PowerOptimizationHelper

    private lateinit var statusBadgeView: TextView
    private lateinit var poolCountView: TextView
    private lateinit var midrollStatusView: TextView
    private lateinit var liveLogTextView: TextView

    private lateinit var botTokenInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var allowedChatIdInput: EditText
    private lateinit var bannerTextInput: EditText
    private lateinit var promoUrlInput: EditText

    private val midrollPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            copySelectedMidrollToInternalStorage(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticLogger.init(applicationContext)
        DiagnosticLogger.log("UI", "Открыт главный экран MainActivity")

        configStore = NodeConfigStore(applicationContext)
        powerHelper = PowerOptimizationHelper(applicationContext)

        requestNotificationPermissionIfNeeded()
        setContentView(buildProgrammaticDarkDashboard())
        observePipelineStatus()
        observeDiagnosticLogs()
        refreshPoolAndAdStats()

        if (configStore.getTelegramBotToken().isNotBlank()) {
            PipelineForegroundService.startService(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPoolAndAdStats()
    }

    private fun buildProgrammaticDarkDashboard(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(28), pad, dp(40))
        }

        val titleView = TextView(this).apply {
            text = "POCKET MEDIA FORGE"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Qualcomm Snapdragon ARM64 • Autonomous Shorts Node"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(subtitleView)

        statusBadgeView = TextView(this).apply {
            text = "● СТАТУС: ПРОВЕРКА..."
            setTextColor(Color.parseColor("#FBBF24"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        root.addView(statusBadgeView)

        poolCountView = TextView(this).apply {
            text = "Фоновых залипалок в пуле: 0 шт."
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(poolCountView)

        midrollStatusView = TextView(this).apply {
            text = "3-сек видео-врезка (Midroll): не загружена"
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(midrollStatusView)

        // Live Diagnostic Log section right at the top so user immediately sees what is happening!
        root.addView(sectionHeader("0. ПОЛНЫЙ ЛОГ ДЕЙСТВИЙ С ЗАПУСКА (LIVE)"))

        liveLogTextView = TextView(this).apply {
            text = DiagnosticLogger.getRecentLinesText(30)
            setTextColor(Color.parseColor("#34D399"))
            setBackgroundColor(Color.parseColor("#111115"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        root.addView(liveLogTextView)

        val copyLogBtn = createStyledButton(
            text = "📋 Скопировать весь лог в буфер обмена",
            textColorHex = "#E4E4E7",
            bgHex = "#27272A"
        ).apply {
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val fullText = DiagnosticLogger.getFullLogFile(applicationContext).readText()
                cm.setPrimaryClip(ClipData.newPlainText("PocketForgeLog", fullText))
                Toast.makeText(this@MainActivity, "✅ Весь лог скопирован в буфер!", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyLogBtn, buttonLayoutParams())

        root.addView(sectionHeader("1. КЛЮЧИ УПРАВЛЕНИЯ (СОХРАНЯЮТСЯ В ПАМЯТИ ТЕЛЕФОНА)"))

        root.addView(fieldLabel("Telegram Bot Token (от @BotFather):"))
        botTokenInput = createInputField(
            hint = "123456789:AAH...",
            initial = configStore.getTelegramBotToken(),
            isSecret = false
        )
        root.addView(botTokenInput)

        root.addView(fieldLabel("Gemini API Key (для 3 названий и AI-субтитров):"))
        geminiKeyInput = createInputField(
            hint = "AIzaSy...",
            initial = configStore.getGeminiApiKey(),
            isSecret = false
        )
        root.addView(geminiKeyInput)

        root.addView(fieldLabel("Разрешённый Chat ID (оставь пустым для любого чата):"))
        allowedChatIdInput = createInputField(
            hint = "Например: -100192837465 или твой ID",
            initial = configStore.getAllowedChatId(),
            isSecret = false
        )
        root.addView(allowedChatIdInput)

        root.addView(sectionHeader("2. НАСТРОЙКИ РЕКЛАМЫ ПО УМОЛЧАНИЮ (ДЛЯ СЛОЯ 4)"))

        root.addView(fieldLabel("Текст всплывающей плашки (5–15 сек):"))
        bannerTextInput = createInputField(
            hint = "SPONSORED • ССЫЛКА В ЗАКРЕПЕ",
            initial = configStore.getCustomBannerText(),
            isSecret = false
        )
        root.addView(bannerTextInput)

        root.addView(fieldLabel("Промо-ссылка / промокод для закреплённого комментария:"))
        promoUrlInput = createInputField(
            hint = "https://t.me/+...",
            initial = configStore.getSponsorPromoUrl(),
            isSecret = false
        )
        root.addView(promoUrlInput)

        val pickMidrollBtn = createStyledButton(
            text = "📂 Выбрать MP4 с телефона для 3-сек видео-врезки",
            textColorHex = "#F4F4F5",
            bgHex = "#27272A"
        ).apply {
            setOnClickListener {
                midrollPickerLauncher.launch("video/mp4")
            }
        }
        root.addView(pickMidrollBtn, buttonLayoutParams())

        root.addView(sectionHeader("3. ЗАЩИТА ОТ СНА OXYGENOS (ONEPLUS)"))

        val batteryBtn = createStyledButton(
            text = "🔋 Отключить оптимизацию батареи для узла",
            textColorHex = "#FBBF24",
            bgHex = "#27272A"
        ).apply {
            setOnClickListener {
                if (!powerHelper.isIgnoringBatteryOptimizations()) {
                    startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
                } else {
                    val oplusIntent = powerHelper.buildOxygenOsAutoLaunchIntent()
                    if (oplusIntent != null) {
                        startActivity(oplusIntent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Оптимизация батареи уже отключена ✅",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        root.addView(batteryBtn, buttonLayoutParams())

        root.addView(sectionHeader("4. ЗАПУСК И ПЕРЕЗАПУСК КОНВЕЙЕРА"))

        val saveAndStartBtn = createStyledButton(
            text = "🚀 СОХРАНИТЬ НАСТРОЙКИ И ЗАПУСТИТЬ УЗЕЛ 24/7",
            textColorHex = "#09090B",
            bgHex = "#10B981"
        ).apply {
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                saveInputsToStore()
                PipelineForegroundService.reloadCredentials(applicationContext)
                Toast.makeText(
                    this@MainActivity,
                    "Настройки сохранены! Узел и Telegram-бот запущены.",
                    Toast.LENGTH_LONG
                ).show()
                refreshPoolAndAdStats()
            }
        }
        root.addView(saveAndStartBtn, buttonLayoutParams())

        val stopBtn = createStyledButton(
            text = "🛑 ОСТАНОВИТЬ ФОНОВЫЙ УЗЕЛ",
            textColorHex = "#FCA5A5",
            bgHex = "#3F1D1D"
        ).apply {
            setOnClickListener {
                PipelineForegroundService.stopService(applicationContext)
                Toast.makeText(this@MainActivity, "Узел остановлен", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(stopBtn, buttonLayoutParams())

        scroll.addView(root)
        return scroll
    }

    private fun saveInputsToStore() {
        configStore.setTelegramBotToken(botTokenInput.text.toString())
        configStore.setGeminiApiKey(geminiKeyInput.text.toString())
        configStore.setAllowedChatId(allowedChatIdInput.text.toString())
        configStore.setCustomBannerText(bannerTextInput.text.toString())
        configStore.setSponsorPromoUrl(promoUrlInput.text.toString())
        DiagnosticLogger.log("UI", "Пользователь сохранил настройки и перезапустил узел.")
    }

    private fun copySelectedMidrollToInternalStorage(uri: Uri) {
        try {
            val adDir = File(filesDir, "ad_assets").apply { mkdirs() }
            val target = File(adDir, "midroll.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            refreshPoolAndAdStats()
            Toast.makeText(
                this,
                "✅ Видео-врезка midroll.mp4 сохранена (${target.length() / 1024} KB)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка копирования видео: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPoolAndAdStats() {
        val poolDir = File(filesDir, "clutter_pool")
        val count = poolDir.listFiles { f -> f.isFile && f.extension == "mp4" }?.size ?: 0
        val batteryOk = if (powerHelper.isIgnoringBatteryOptimizations()) "Да ✅" else "Нет ⚠️"
        poolCountView.text = "Фоновых залипалок в пуле: $count шт. • Игнор батареи: $batteryOk"

        val midroll = configStore.getMidrollVideoFile()
        midrollStatusView.text = if (midroll != null) {
            "3-сек видео-врезка (Midroll): загружена ✅ (${midroll.length() / 1024} KB)"
        } else {
            "3-сек видео-врезка (Midroll): не выбрана (можно выбрать кнопкой ниже)"
        }
    }

    private fun observePipelineStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineForegroundService.statusFlow.collect { status ->
                    val (text, colorHex) = when (status) {
                        is ServiceStatus.Stopped ->
                            "● СТАТУС: ОСТАНОВЛЕН (Нажми зелёную кнопку запуска)" to "#F87171"
                        is ServiceStatus.Initializing ->
                            "● СТАТУС: ИНИЦИАЛИЗАЦИЯ И ОБНОВЛЕНИЕ YT-DLP..." to "#FBBF24"
                        is ServiceStatus.IdleListening ->
                            "● СТАТУС: ОНЛАЙН • СЛУШАЮ КОМАНДЫ TELEGRAM (24/7)" to "#34D399"
                        is ServiceStatus.Processing ->
                            "● В РАБОТЕ [#${status.jobId}]: ${status.stage}" to "#38BDF8"
                        is ServiceStatus.Error ->
                            "● ВНИМАНИЕ: ${status.reason}" to "#FB923C"
                    }
                    statusBadgeView.text = text
                    statusBadgeView.setTextColor(Color.parseColor(colorHex))
                    refreshPoolAndAdStats()
                }
            }
        }
    }

    private fun observeDiagnosticLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticLogger.logFlow.collect { lines ->
                    liveLogTextView.text = if (lines.isEmpty()) {
                        "Лог пока пуст..."
                    } else {
                        lines.takeLast(30).joinToString("\n")
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun fieldLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#A1A1AA"))
        textSize = 12f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun createInputField(hint: String, initial: String, isSecret: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(initial)
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.parseColor("#F4F4F5"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(12)
            setPadding(p, p, p, p)
            inputType = if (isSecret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }

    private fun createStyledButton(text: String, textColorHex: String, bgHex: String): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColorHex))
            setBackgroundColor(Color.parseColor(bgHex))
            textSize = 12f
            isAllCaps = false
            gravity = Gravity.CENTER
            val p = dp(12)
            setPadding(p, p, p, p)
        }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
