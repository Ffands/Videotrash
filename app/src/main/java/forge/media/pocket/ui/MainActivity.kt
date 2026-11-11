package forge.media.pocket.ui

import android.Manifest
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
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.service.ServiceStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Programmatic Zero-XML Dark Control Panel for OnePlus / Snapdragon Node (Step 6 - Option 6C).
 *
 * Why programmatic UI?
 * - Eliminates XML layout inflation issues and R-class resource mismatches in headless CI builds.
 * - Provides direct input fields for Telegram Bot Token, Gemini API Key, Allowed Chat ID,
 *   Ad Banner Text, and Midroll video picker, plus OxygenOS battery whitelist buttons.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configStore: NodeConfigStore
    private lateinit var powerHelper: PowerOptimizationHelper

    private lateinit var statusBadgeView: TextView
    private lateinit var poolStatsView: TextView
    private lateinit var tokenInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var chatIdInput: EditText
    private lateinit var bannerTextInput: EditText
    private lateinit var promoUrlInput: EditText
    private lateinit var midrollStatusView: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val pickMidrollVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            copyMidrollToInternalStorage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = NodeConfigStore(applicationContext)
        powerHelper = PowerOptimizationHelper(applicationContext)

        requestNotificationPermissionIfNeeded()
        setContentView(buildEngineeringDashboardView())
        observeServiceStatus()
        refreshPoolAndAdStats()
    }

    private fun buildEngineeringDashboardView(): ScrollView {
        val scrollView = ScrollView(this).apply {
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

        // Header Title
        root.addView(TextView(this).apply {
            text = "POCKET MEDIA FORGE"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Android 14 (API 34) • Qualcomm h264_mediacodec • OxygenOS Node"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(16))
        })

        // Live Service Status Card
        statusBadgeView = TextView(this).apply {
            text = "STATUS: CHECKING..."
            setTextColor(Color.parseColor("#10B981"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        root.addView(statusBadgeView)

        poolStatsView = TextView(this).apply {
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(poolStatsView)

        // Start / Stop Service Row
        val serviceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val startBtn = createStyledButton("▶️ ЗАПУСТИТЬ УЗЕЛ", "#10B981", "#052E16").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                saveInputsToStore()
                PipelineForegroundService.startService(this@MainActivity)
                Toast.makeText(this@MainActivity, "Foreground Service запущен", Toast.LENGTH_SHORT).show()
            }
        }

        val stopBtn = createStyledButton("⏹ ОСТАНОВИТЬ", "#EF4444", "#450A0A").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                PipelineForegroundService.stopService(this@MainActivity)
                Toast.makeText(this@MainActivity, "Узел остановлен", Toast.LENGTH_SHORT).show()
            }
        }

        serviceRow.addView(startBtn)
        serviceRow.addView(stopBtn)
        root.addView(serviceRow)

        // Section: Credentials (Hybrid 6C)
        root.addView(sectionHeader("1. КЛЮЧИ И ДОСТУП (СОХРАНЯЮТСЯ НА ТЕЛЕФОНЕ)"))

        tokenInput = createInputField(
            hint = "Telegram Bot Token (123456:ABC-DEF...)",
            initial = configStore.getTelegramBotToken(),
            isSecret = true
        )
        root.addView(fieldLabel("Telegram Bot Token:"))
        root.addView(tokenInput)

        geminiKeyInput = createInputField(
            hint = "Gemini API Key (AIzaSy...)",
            initial = configStore.getGeminiApiKey(),
            isSecret = true
        )
        root.addView(fieldLabel("Gemini 2.5 Flash API Key:"))
        root.addView(geminiKeyInput)

        chatIdInput = createInputField(
            hint = "Твой Telegram Chat ID или ID канала (пусто = доступен всем)",
            initial = configStore.getAllowedChatId(),
            isSecret = false
        )
        root.addView(fieldLabel("Разрешённый Chat ID (Белый список):"))
        root.addView(chatIdInput)

        // Section: Ad Engine Assets
        root.addView(sectionHeader("2. РЕКЛАМНЫЙ ДВИЖОК (БАННЕР / МИДРОЛЛ / ССЫЛКА)"))

        bannerTextInput = createInputField(
            hint = "Текст рекламной плашки",
            initial = configStore.getCustomBannerText(),
            isSecret = false
        )
        root.addView(fieldLabel("Текст для баннера (Strategy A):"))
        root.addView(bannerTextInput)

        promoUrlInput = createInputField(
            hint = "Промо-хук и ссылка для закрепа (Strategy C)",
            initial = configStore.getSponsorPromoUrl(),
            isSecret = false
        )
        root.addView(fieldLabel("Промо-текст и ссылка в закреп (Strategy C):"))
        root.addView(promoUrlInput)

        midrollStatusView = TextView(this).apply {
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(6))
        }
        root.addView(midrollStatusView)

        root.addView(createStyledButton("📂 ВЫБРАТЬ 3-СЕК МИДРОЛЛ (.MP4) С ТЕЛЕФОНА", "#E4E4E7", "#27272A").apply {
            setOnClickListener {
                pickMidrollVideoLauncher.launch("video/mp4")
            }
        })

        root.addView(createStyledButton("💾 СОХРАНИТЬ КЛЮЧИ И ПРИМЕНИТЬ НА ЛЕТУ", "#F59E0B", "#451A03").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            layoutParams = lp
            setOnClickListener {
                saveInputsToStore()
                PipelineForegroundService.reloadCredentials(this@MainActivity)
                Toast.makeText(this@MainActivity, "Ключи сохранены и применены!", Toast.LENGTH_SHORT).show()
            }
        })

        // Section: OxygenOS / ColorOS Power Exemptions
        root.addView(sectionHeader("3. ЗАЩИТА ОТ УБИЙСТВА ПРОЦЕССА (OXYGENOS / COLOROS)"))

        root.addView(createStyledButton("🔋 1. ОТКЛЮЧИТЬ КОНТРОЛЬ БАТАРЕИ (DOZE)", "#38BDF8", "#082F49").apply {
            setOnClickListener {
                startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
            }
        })

        root.addView(createStyledButton("🛡 2. ОТКРЫТЬ АВТОЗАПУСК OXYGENOS / COLOROS", "#A78BFA", "#2E1065").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener {
                val intent = powerHelper.buildOxygenOsAutoLaunchIntent()
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Спец-меню ColorOS не найдено, достаточно стандартного отключения Doze",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(createStyledButton("🧹 ОЧИСТИТЬ ВРЕМЕННЫЙ КЭШ СБОРКИ", "#A1A1AA", "#18181B").apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            layoutParams = lp
            setOnClickListener {
                File(cacheDir, "work_staging").deleteRecursively()
                File(cacheDir, "work_staging").mkdirs()
                refreshPoolAndAdStats()
                Toast.makeText(this@MainActivity, "Временный кэш очищен", Toast.LENGTH_SHORT).show()
            }
        })

        scrollView.addView(root)
        return scrollView
    }

    private fun saveInputsToStore() {
        configStore.setTelegramBotToken(tokenInput.text.toString())
        configStore.setGeminiApiKey(geminiKeyInput.text.toString())
        configStore.setAllowedChatId(chatIdInput.text.toString())
        configStore.setCustomBannerText(bannerTextInput.text.toString())
        configStore.setSponsorPromoUrl(promoUrlInput.text.toString())
        refreshPoolAndAdStats()
    }

    private fun copyMidrollToInternalStorage(uri: Uri) {
        try {
            val adDir = File(filesDir, "ad_assets").apply { mkdirs() }
            val dest = File(adDir, "midroll.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            refreshPoolAndAdStats()
            Toast.makeText(this, "Мидролл сохранён (${dest.length() / 1024} КБ)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка копирования мидролла: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPoolAndAdStats() {
        val poolCount = File(filesDir, "clutter_pool").listFiles { f -> f.extension == "mp4" }?.size ?: 0
        val batteryOk = powerHelper.isIgnoringBatteryOptimizations()
        poolStatsView.text = "Clutter Pool: $poolCount clips | Doze Exemption: ${if (batteryOk) "GRANTED" else "NOT GRANTED"}"

        val midroll = configStore.getMidrollVideoFile()
        midrollStatusView.text = if (midroll != null) {
            "Мидролл (Strategy B): Загружен (${midroll.length() / 1024} KB)"
        } else {
            "Мидролл (Strategy B): Не выбран"
        }
    }

    private fun observeServiceStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineForegroundService.statusFlow.collect { status ->
                    val (text, colorHex) = when (status) {
                        is ServiceStatus.Stopped -> "⏹ УЗЕЛ ОСТАНОВЛЕН" to "#A1A1AA"
                        is ServiceStatus.Initializing -> "⏳ ИНИЦИАЛИЗАЦИЯ YT-DLP & ARIA2C..." to "#F59E0B"
                        is ServiceStatus.IdleListening -> "🟢 ОНЛАЙН • СЛУШАЮ КОМАНДЫ В TELEGRAM" to "#10B981"
                        is ServiceStatus.Processing -> "⚙️ РЕНДЕР #${status.jobId}: ${status.stage}" to "#38BDF8"
                        is ServiceStatus.Error -> "⚠️ ${status.reason}" to "#EF4444"
                    }
                    statusBadgeView.text = text
                    statusBadgeView.setTextColor(Color.parseColor(colorHex))
                    refreshPoolAndAdStats()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
