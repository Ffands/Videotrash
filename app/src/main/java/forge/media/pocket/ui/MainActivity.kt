package forge.media.pocket.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.clutter.StudioInboxItem
import forge.media.pocket.compositor.AntiDetectDspLayerConfig
import forge.media.pocket.compositor.AudioStemRoutingMode
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.MusicRemovalMode
import forge.media.pocket.compositor.SeparatedAudioStems
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.compositor.VoiceoverDubMode
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.DiscoveredSpamChannel
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.service.ServiceStatus
import forge.media.pocket.subtitles.MusicShazamMatchResult
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TtsVoiceProfile
import forge.media.pocket.vision.DetectedHardsubRegion
import forge.media.pocket.vision.ExistingSubtitleRemovalMode
import forge.media.pocket.vision.FaceCenteringHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var configStore: NodeConfigStore
    private lateinit var powerHelper: PowerOptimizationHelper
    private lateinit var clutterFilter: ClutterEntropyFilter
    private lateinit var topicHunter: TopicHunterEngine
    private lateinit var hardwareEngine: FFmpegHardwareEngine
    private lateinit var faceHelper: FaceCenteringHelper
    private lateinit var subtitleTransformer: SubtitleAssTransformer

    private lateinit var statusBadgeView: TextView
    private lateinit var liveLogTextView: TextView

    // Tab containers
    private lateinit var tabInboxContainer: LinearLayout
    private lateinit var tabStudioContainer: LinearLayout
    private lateinit var tabPoolContainer: LinearLayout
    private lateinit var tabSettingsContainer: LinearLayout

    private lateinit var tabBtnInbox: Button
    private lateinit var tabBtnStudio: Button
    private lateinit var tabBtnPool: Button
    private lateinit var tabBtnSettings: Button

    // Inbox & Spam-Farm Hunter UI views
    private lateinit var inboxListLayout: LinearLayout
    private lateinit var directUrlDownloadInput: EditText
    private lateinit var spamFarmTopicInput: EditText
    private lateinit var spamFarmStatusView: TextView
    private lateinit var spamFarmListLayout: LinearLayout

    // Studio UI views
    private lateinit var studioPlayerView: VideoView
    private lateinit var studioPlayerLabel: TextView
    private lateinit var studioSelectedSourceLabel: TextView
    private lateinit var trimStartInput: EditText
    private lateinit var trimEndInput: EditText
    private lateinit var templateSelectBtn: Button
    private lateinit var poolClipSelectBtn: Button
    private lateinit var stemRoutingModeBtn: Button
    private lateinit var stemStatusLabel: TextView
    private lateinit var shazamStatusLabel: TextView
    private lateinit var toggleReplaceMusicChk: CheckBox
    private lateinit var musicRemovalBtn: Button
    private lateinit var voiceoverDubModeBtn: Button
    private lateinit var ttsVoiceProfileBtn: Button
    private lateinit var dubScriptInput: EditText
    private lateinit var voiceoverStatusLabel: TextView
    private lateinit var chkSmartFace: CheckBox
    private lateinit var chkVideoZoom: CheckBox
    private lateinit var chkVideoColorNoise: CheckBox
    private lateinit var chkVideoFlip: CheckBox
    private lateinit var chkAudioPitchTempo: CheckBox
    private lateinit var chkAudioEqStereo: CheckBox
    private lateinit var chkAudioTrimSilence: CheckBox
    private lateinit var hardsubRemovalModeBtn: Button
    private lateinit var hardsubStatusLabel: TextView
    private lateinit var chkSubtitles: CheckBox
    private lateinit var subZoneBtn: Button
    private lateinit var studioRenderStatusView: TextView

    // Pool UI views
    private lateinit var poolListLayout: LinearLayout
    private lateinit var poolTopicTagInput: EditText

    // Settings UI views
    private lateinit var botTokenInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var allowedChatIdInput: EditText

    // Current Studio State
    private var selectedInboxItem: StudioInboxItem? = null
    private var currentLayoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK
    private var selectedPoolClipName: String = "AUTO"
    private var currentStemRoutingMode: AudioStemRoutingMode = AudioStemRoutingMode.FULL_ORIGINAL_MIX
    private var currentSeparatedStems: SeparatedAudioStems? = null
    private var stemPreviewPlayer: MediaPlayer? = null
    private var lastShazamResult: MusicShazamMatchResult? = null
    private var currentShazamCandidateIndex: Int = 0
    private var currentReplacementMusicFile: File? = null
    private var currentMusicRemovalMode: MusicRemovalMode = MusicRemovalMode.OFF
    private var currentVoiceoverDubMode: VoiceoverDubMode = VoiceoverDubMode.OFF
    private var currentTtsVoiceProfile: TtsVoiceProfile = TtsVoiceProfile.CHARON_DEEP
    private var currentCustomVoiceoverFile: File? = null
    private var currentHardsubRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF
    private var currentDetectedHardsubRegion: DetectedHardsubRegion? = null
    private var currentSubZone: SubtitlePlacementZone = SubtitlePlacementZone.ADAPTIVE_AUTO
    private var lastRenderedGalleryFile: File? = null

    private val importSourceLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val item = clutterFilter.importLocalUriToInbox(uri)
                    selectedInboxItem = item
                    refreshInboxTab()
                    bindSelectedSourceToStudio(item)
                    switchTab(1)
                    Toast.makeText(this@MainActivity, "✅ Видео загружено в Студию!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importPoolClipLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val enteredTags = if (::poolTopicTagInput.isInitialized) poolTopicTagInput.text.toString().trim() else ""
                    Toast.makeText(this@MainActivity, "⏳ Нормализую видео в 1080x960 для пула...", Toast.LENGTH_SHORT).show()
                    val added = clutterFilter.importLocalUriToPool(uri, enteredTags)
                    selectedPoolClipName = added.name
                    refreshPoolTab()
                    updatePoolSelectorButtonText()
                    Toast.makeText(this@MainActivity, "✅ Фон ${added.name} добавлен в пул!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importVoiceoverAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "⏳ Подключаю аудиофайл озвучки...", Toast.LENGTH_SHORT).show()
                    val voFile = subtitleTransformer.importCustomVoiceoverUri(uri)
                    currentCustomVoiceoverFile = voFile
                    if (currentVoiceoverDubMode != VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG &&
                        currentVoiceoverDubMode != VoiceoverDubMode.DUB_REPLACE_100PCT
                    ) {
                        currentVoiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                        voiceoverDubModeBtn.text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                    }
                    voiceoverStatusLabel.text = "✅ Аудио озвучки готово: ${voFile.name} (${voFile.length() / 1024} KB)"
                    Toast.makeText(this@MainActivity, "✅ Файл озвучки подключён!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importReplacementMusicLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "⏳ Подключаю свою мелодию для подмены музыкальной дорожки...", Toast.LENGTH_SHORT).show()
                    val bgmFile = subtitleTransformer.importCustomVoiceoverUri(uri)
                    currentReplacementMusicFile = bgmFile
                    toggleReplaceMusicChk.isChecked = true
                    shazamStatusLabel.text = "✅ Своя мелодия для подмены подключена: ${bgmFile.name} (${bgmFile.length() / 1024} KB)\nСтарая музыка в видео будет автоматически вырезана и заменена на эту дорожку!"
                    Toast.makeText(this@MainActivity, "✅ Мелодия подключена для подмены!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticLogger.init(applicationContext)
        DiagnosticLogger.log("UI", "Открыт главный экран MainActivity (Студия + Скаут)")

        configStore = NodeConfigStore(applicationContext)
        powerHelper = PowerOptimizationHelper(applicationContext)
        clutterFilter = ClutterEntropyFilter(applicationContext)
        topicHunter = TopicHunterEngine(applicationContext)
        hardwareEngine = FFmpegHardwareEngine(applicationContext)
        faceHelper = FaceCenteringHelper(applicationContext)
        subtitleTransformer = SubtitleAssTransformer(applicationContext, configStore)

        requestNotificationPermissionIfNeeded()
        setContentView(buildMainTabbedInterface())
        observePipelineStatus()
        observeDiagnosticLogs()

        refreshInboxTab()
        refreshPoolTab()

        val existingInbox = clutterFilter.listStudioInboxItems()
        if (existingInbox.isNotEmpty()) {
            bindSelectedSourceToStudio(existingInbox.first())
        }

        if (configStore.getTelegramBotToken().isNotBlank()) {
            PipelineForegroundService.startService(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInboxTab()
        refreshPoolTab()
    }

    private fun buildMainTabbedInterface(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(22), pad, dp(36))
        }

        val titleView = TextView(this).apply {
            text = "POCKET MEDIA FORGE • STUDIO"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleView)

        statusBadgeView = TextView(this).apply {
            text = "● СТАТУС: ПРОВЕРКА..."
            setTextColor(Color.parseColor("#FBBF24"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            val p = dp(10)
            setPadding(p, p, p, p)
        }
        root.addView(statusBadgeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(10) })

        // 4-Tab Navigation Bar
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        tabBtnInbox = createTabButton("📥 Исходники") { switchTab(0) }
        tabBtnStudio = createTabButton("🎛 Студия") { switchTab(1) }
        tabBtnPool = createTabButton("🗂 Фоны") { switchTab(2) }
        tabBtnSettings = createTabButton("⚙️ Ключи/Лог") { switchTab(3) }

        tabBar.addView(tabBtnInbox, tabButtonLayoutParams())
        tabBar.addView(tabBtnStudio, tabButtonLayoutParams())
        tabBar.addView(tabBtnPool, tabButtonLayoutParams())
        tabBar.addView(tabBtnSettings, tabButtonLayoutParams())
        root.addView(tabBar)

        // Build all 4 tab containers
        tabInboxContainer = buildInboxTabView()
        tabStudioContainer = buildStudioTabView()
        tabPoolContainer = buildPoolTabView()
        tabSettingsContainer = buildSettingsTabView()

        root.addView(tabInboxContainer)
        root.addView(tabStudioContainer)
        root.addView(tabPoolContainer)
        root.addView(tabSettingsContainer)

        // Default tab: if token is empty, open Settings; otherwise open Studio/Inbox
        if (configStore.getTelegramBotToken().isBlank()) {
            switchTab(3)
        } else {
            switchTab(1)
        }

        scroll.addView(root)
        return scroll
    }

    private fun switchTab(index: Int) {
        tabInboxContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabStudioContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabPoolContainer.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabSettingsContainer.visibility = if (index == 3) View.VISIBLE else View.GONE

        val buttons = listOf(tabBtnInbox, tabBtnStudio, tabBtnPool, tabBtnSettings)
        buttons.forEachIndexed { idx, btn ->
            if (idx == index) {
                btn.setBackgroundColor(Color.parseColor("#10B981"))
                btn.setTextColor(Color.parseColor("#09090B"))
            } else {
                btn.setBackgroundColor(Color.parseColor("#27272A"))
                btn.setTextColor(Color.parseColor("#E4E4E7"))
            }
        }

        if (index == 0) refreshInboxTab()
        if (index == 2) refreshPoolTab()
    }

    // =========================================================================
    // ВКЛАДКА 0: 📥 ИСХОДНИКИ (INBOX) + 📡 ОХОТНИК НА СПАМ-КАНАЛЫ
    // =========================================================================
    private fun buildInboxTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("📥 ЗАГРУЖЕННЫЕ ИСХОДНИКИ (ИЗ TELEGRAM ИЛИ С ТЕЛЕФОНА)"))

            val hint = TextView(this@MainActivity).apply {
                text = "Ищи видео по темам в Telegram-боте -> смотри ссылки -> жми «✅ Взять #... (1080p)». Все выбранные исходники сразу появляются здесь в FullHD качестве!"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 12f
                setPadding(0, 0, 0, dp(10))
            }
            addView(hint)

            val actionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val importPhoneBtn = createStyledButton("📂 Взять видео из Галереи", "#09090B", "#10B981").apply {
                setOnClickListener { importSourceLauncher.launch("video/*") }
            }
            val refreshBtn = createStyledButton("🔄 Обновить список", "#F4F4F5", "#27272A").apply {
                setOnClickListener { refreshInboxTab() }
            }

            actionRow.addView(importPhoneBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            actionRow.addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionRow)

            // Direct link download bar right inside the app
            addView(fieldLabel("Или вставь ссылку YouTube/Shorts/Reddit прямо сюда для скачивания в 1080p:"))
            directUrlDownloadInput = createInputField("https://www.youtube.com/watch?v=...", "", false)
            addView(directUrlDownloadInput)

            val dlLinkBtn = createStyledButton("⬇️ Скачать по ссылке в 1080p", "#F4F4F5", "#3B82F6").apply {
                setOnClickListener {
                    val url = directUrlDownloadInput.text.toString().trim()
                    if (!url.startsWith("http")) {
                        Toast.makeText(this@MainActivity, "Вставь корректную ссылку https://...", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Качаю исходник в 1080p...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        try {
                            val item = clutterFilter.downloadSourceToStudioInbox(url)
                            directUrlDownloadInput.setText("")
                            refreshInboxTab()
                            bindSelectedSourceToStudio(item)
                            switchTab(1)
                            Toast.makeText(this@MainActivity, "✅ Скачано в 1080p и открыто в Студии!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            addView(dlLinkBtn, buttonLayoutParams())

            // Spam-Farm Channel Hunter Section
            addView(sectionHeader("📡 ОХОТНИК НА СПАМ-КАНАЛЫ И ФЕРМЫ ПЕРЕЗАЛИВОВ"))

            val spamHint = TextView(this@MainActivity).apply {
                text = "Находит каналы в твоей тематике, где видео идут потоком/спамом (частые заливы, случайный разброс тем в одной нише, #shorts/нарезки). Из найденных каналов можно сразу выкачивать лучшие ролики в 1080p!"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 11.5f
                setPadding(0, 0, 0, dp(6))
            }
            addView(spamHint)

            spamFarmTopicInput = createInputField("Тема ниши (например: мото ситуации, стендап истории, кс2)...", "", false)
            addView(spamFarmTopicInput)

            val spamBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val huntSpamByTopicBtn = createStyledButton("📡 Найти спам-каналы", "#09090B", "#F59E0B").apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val query = spamFarmTopicInput.text.toString().trim().ifBlank {
                        selectedInboxItem?.let { "${it.title} ${it.sourceTags.joinToString(" ")}" } ?: ""
                    }
                    if (query.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи тему ниши или выбери исходник ниже", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    runSpamFarmSearchInUi(query)
                }
            }

            val huntSpamFromSelectedBtn = createStyledButton("🕵️ Похожие на исходник", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери любой исходник в списке", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val seed = "${cur.title} ${cur.sourceTags.joinToString(" ")}"
                    spamFarmTopicInput.setText(seed.take(45))
                    runSpamFarmSearchInUi(seed)
                }
            }

            spamBtnRow.addView(huntSpamByTopicBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(6) })
            spamBtnRow.addView(huntSpamFromSelectedBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(spamBtnRow)

            spamFarmStatusView = TextView(this@MainActivity).apply {
                text = "📡 Введи нишу выше, чтобы найти потоковые спам-каналы с подходящими исходниками."
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(spamFarmStatusView)

            spamFarmListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(spamFarmListLayout)

            addView(sectionHeader("📂 МОИ ЗАГРУЖЕННЫЕ ИСХОДНИКИ В СТУДИИ"))

            inboxListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(inboxListLayout)
        }
    }

    private fun runSpamFarmSearchInUi(topicQuery: String) {
        spamFarmStatusView.text = "⏳ Ищу потоковые спам-каналы и контент-фермы по теме «$topicQuery»..."
        spamFarmListLayout.removeAllViews()
        lifecycleScope.launch {
            try {
                val channels = topicHunter.huntSpamFarmChannels(topicQuery, maxChannels = 4)
                if (channels.isEmpty()) {
                    spamFarmStatusView.text = "⚠️ По запросу «$topicQuery» спам-каналов не найдено. Попробуй сформулировать тему короче."
                    return@launch
                }
                spamFarmStatusView.text = "✅ Найдено ${channels.size} потоковых/спам-каналов по теме «$topicQuery»:"
                renderSpamChannelsCards(channels)
            } catch (e: Exception) {
                spamFarmStatusView.text = "❌ Ошибка поиска спам-каналов: ${e.message}"
            }
        }
    }

    private fun renderSpamChannelsCards(channels: List<DiscoveredSpamChannel>) {
        spamFarmListLayout.removeAllViews()
        for (ch in channels) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#141419"))
                val p = dp(10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val perWeek = String.format(Locale.US, "%.1f", ch.estimatedUploadsPerWeek)
            val headerTv = TextView(this).apply {
                text = "🏭 ${ch.channelName} • 🔥 Спам-индекс: ${ch.spamScore}/100\n" +
                    "📈 ~$perWeek рол./нед • 🎲 Разброс тем: ${ch.titleRandomnessPct}%\n" +
                    "📋 ${ch.patternSummary}"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(headerTv)

            val topVideo = ch.sampleVideos.firstOrNull()
            if (topVideo != null) {
                val sampleTv = TextView(this).apply {
                    text = "🎬 Топ-ролик: «${topVideo.title.take(55)}» (${topVideo.durationSec}с)"
                    setTextColor(Color.parseColor("#A7F3D0"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, dp(6))
                }
                card.addView(sampleTv)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val dlTopBtn = createStyledButton("⬇️ Скачать топ-ролик (1080p)", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val targetVid = ch.sampleVideos.firstOrNull()
                    if (targetVid == null) {
                        Toast.makeText(this@MainActivity, "Нет доступного ролика", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Качаю «${targetVid.title.take(30)}» в 1080p...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        try {
                            val item = clutterFilter.downloadSourceToStudioInbox(
                                sourceUrl = targetVid.url,
                                overrideTitle = targetVid.title,
                                overrideAuthor = ch.channelName,
                                aiSummary = "Из спам-канала «${ch.channelName}» (спам-индекс ${ch.spamScore}/100)"
                            )
                            refreshInboxTab()
                            bindSelectedSourceToStudio(item)
                            switchTab(1)
                            Toast.makeText(this@MainActivity, "✅ Скачано со спам-канала в Студию!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            val openChannelBtn = createStyledButton("🔗 Открыть поток на YouTube", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.channelUrl)))
                    } catch (_: Exception) {
                    }
                }
            }

            row.addView(dlTopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
            row.addView(openChannelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
            card.addView(row)
            spamFarmListLayout.addView(card)
        }
    }

    private fun refreshInboxTab() {
        inboxListLayout.removeAllViews()
        val items = clutterFilter.listStudioInboxItems()
        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Список исходников пока пуст.\nНажми «🔎 Найти видео по теме» в Telegram-боте и выбери ролики кнопкой «✅ Взять (1080p)», либо выбери видео из Галереи кнопкой выше."
                setTextColor(Color.parseColor("#71717A"))
                textSize = 12.5f
                setPadding(dp(12), dp(18), dp(12), dp(18))
                setBackgroundColor(Color.parseColor("#18181B"))
            }
            inboxListLayout.addView(emptyView)
            return
        }

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#18181B"))
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val sizeMb = String.format(Locale.US, "%.1f", item.sizeKb / 1024.0)
            val cutNote = if (item.recommendedEndSec > item.recommendedStartSec) {
                " • ✂️ Момент: ${String.format(Locale.US, "%.0f–%.0fс", item.recommendedStartSec, item.recommendedEndSec)}"
            } else {
                ""
            }
            val titleTv = TextView(this).apply {
                text = "🎬 ${item.title}\n👤 ${item.author} • ⏱ ${item.durationSec} сек • 📦 $sizeMb МБ$cutNote"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(titleTv)

            val tagsLine = clutterFilter.extractSemanticTopicTags(
                text = "${item.title} ${item.aiSummary}",
                rawTags = item.sourceTags
            )
            if (tagsLine.isNotEmpty()) {
                val tagsTv = TextView(this).apply {
                    text = "🏷 Теги для подбора залипалки: ${tagsLine.joinToString(" ")}"
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 11.5f
                    setPadding(0, dp(3), 0, dp(2))
                }
                card.addView(tagsTv)
            }

            if (item.aiSummary.isNotBlank()) {
                val aiTv = TextView(this).apply {
                    text = "🧠 Что внутри: ${item.aiSummary}"
                    setTextColor(Color.parseColor("#A7F3D0"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, dp(2))
                }
                card.addView(aiTv)
            }

            if (item.sourceUrl.startsWith("http")) {
                val urlTv = TextView(this).apply {
                    text = "🔗 ${item.sourceUrl}"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, dp(8))
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                        } catch (_: Exception) {
                        }
                    }
                }
                card.addView(urlTv)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }

            val loadStudioBtn = createStyledButton("🎛 В Студию", "#09090B", "#10B981").apply {
                setOnClickListener {
                    bindSelectedSourceToStudio(item)
                    switchTab(1)
                }
            }

            val openOrigBtn = createStyledButton("🔗 Оригинал", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    if (item.sourceUrl.startsWith("http")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                    } else {
                        Toast.makeText(this@MainActivity, "Это локальный файл с телефона", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val delBtn = createStyledButton("🗑 Удалить", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    clutterFilter.deleteStudioInboxItem(item.id)
                    refreshInboxTab()
                }
            }

            btnRow.addView(loadStudioBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = dp(6) })
            btnRow.addView(openOrigBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            btnRow.addView(delBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))

            card.addView(btnRow)
            inboxListLayout.addView(card)
        }
    }

    // =========================================================================
    // ВКЛАДКА 1: 🎛 СТУДИЯ (ШАБЛОНЫ, ТАЙМКОД, АНТИ-ДЕТЕКТ И РЕНДЕР В ГАЛЕРЕЮ)
    // =========================================================================
    private fun buildStudioTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("1. ВСТРОЕННЫЙ ПЛЕЕР И ВЫБОР МОМЕНТА (БЕЗ ОБРЕЗКИ ФРАЗ)"))

            studioSelectedSourceLabel = TextView(this@MainActivity).apply {
                text = "Исходник не выбран — выбери во вкладке «📥 Исходники»"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(6))
            }
            addView(studioSelectedSourceLabel)

            studioPlayerLabel = TextView(this@MainActivity).apply {
                text = "📺 Предпросмотр видео:"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 11.5f
            }
            addView(studioPlayerLabel)

            val playerContainer = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#000000"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(280)
                ).apply { topMargin = dp(6); bottomMargin = dp(8) }
            }

            studioPlayerView = VideoView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            playerContainer.addView(studioPlayerView)
            addView(playerContainer)

            // Player Transport & Timecode Capture Row
            val transportRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
            }

            val playPauseBtn = createStyledButton("⏯ Старт/Пауза", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    if (studioPlayerView.isPlaying) {
                        studioPlayerView.pause()
                    } else {
                        studioPlayerView.start()
                    }
                }
            }

            val back3sBtn = createStyledButton("⏪ -3 сек", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val pos = (studioPlayerView.currentPosition - 3000).coerceAtLeast(0)
                    studioPlayerView.seekTo(pos)
                }
            }

            val markStartBtn = createStyledButton("📍 Старт тут", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val sec = studioPlayerView.currentPosition / 1000.0
                    trimStartInput.setText(String.format(Locale.US, "%.1f", sec))
                    Toast.makeText(this@MainActivity, "Старт установлен на ${String.format(Locale.US, "%.1f", sec)} сек", Toast.LENGTH_SHORT).show()
                }
            }

            val markEndBtn = createStyledButton("🏁 Конец тут", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val sec = studioPlayerView.currentPosition / 1000.0
                    trimEndInput.setText(String.format(Locale.US, "%.1f", sec))
                    Toast.makeText(this@MainActivity, "Конец установлен на ${String.format(Locale.US, "%.1f", sec)} сек", Toast.LENGTH_SHORT).show()
                }
            }

            transportRow.addView(playPauseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            transportRow.addView(back3sBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            transportRow.addView(markStartBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            transportRow.addView(markEndBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(transportRow)

            // Exact Start / End Seconds inputs
            val trimInputsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(8), 0, 0)
            }

            val startCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            }
            startCol.addView(fieldLabel("Начало куска (сек):"))
            trimStartInput = createInputField("0.0", "0.0", false)
            startCol.addView(trimStartInput)

            val endCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            endCol.addView(fieldLabel("Конец куска (сек, 0 = до конца):"))
            trimEndInput = createInputField("0.0", "0.0", false)
            endCol.addView(trimEndInput)

            trimInputsRow.addView(startCol)
            trimInputsRow.addView(endCol)
            addView(trimInputsRow)

            // Section 2: Template & Background Picker
            addView(sectionHeader("2. ШАБЛОН СМЕШИВАНИЯ И ВЫБОР НИЖНЕГО ВИДЕО"))

            templateSelectBtn = createStyledButton(
                "📐 Шаблон: ${currentLayoutLayer.label} (нажми для смены)",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val values = VideoLayoutLayer.entries
                    currentLayoutLayer = values[(currentLayoutLayer.ordinal + 1) % values.size]
                    text = "📐 Шаблон: ${currentLayoutLayer.label} (нажми для смены)"
                }
            }
            addView(templateSelectBtn, buttonLayoutParams())

            val poolSelectRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(8), 0, 0)
            }

            poolClipSelectBtn = createStyledButton(
                "🎬 Нижний фон: Авто-микс из пула",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    cyclePoolClipSelection()
                }
            }

            val previewBgBtn = createStyledButton("👁 Посмотреть фон", "#38BDF8", "#18181B").apply {
                setOnClickListener {
                    previewSelectedBackgroundClipInPlayer()
                }
            }

            poolSelectRow.addView(poolClipSelectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { rightMargin = dp(6) })
            poolSelectRow.addView(previewBgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(poolSelectRow)

            val autoFetchTopicBgBtn = createStyledButton(
                "⚡ Докачать залипалку под тему выбранного исходника",
                "#F4F4F5",
                "#3B82F6"
            ).apply {
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val tags = clutterFilter.extractSemanticTopicTags("${cur.title} ${cur.aiSummary}", cur.sourceTags)
                    val query = tags.firstOrNull()?.removePrefix("#") ?: cur.title.take(25)
                    Toast.makeText(this@MainActivity, "⏳ Ищу и качаю залипалку по теме «$query» (${tags.joinToString(" ")})...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        val added = clutterFilter.downloadTaggedClutterByTopic(query, maxClipsToAdd = 1)
                        if (added.isNotEmpty()) {
                            selectedPoolClipName = "AUTO"
                            updatePoolSelectorButtonText()
                            refreshPoolTab()
                            Toast.makeText(this@MainActivity, "✅ Тематическая залипалка (${tags.joinToString(" ")}) скачана и привязана!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ Не удалось скачать с первого раза — попробуй задать тег во вкладке «🗂 Фоны»", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            addView(autoFetchTopicBgBtn, buttonLayoutParams())

            chkSmartFace = createCheckBox("🎯 Авто-центровка говорящего лица (Google ML Kit)", true)
            addView(chkSmartFace)

            // Section 3: Audio Stem Separation (Vocals Overlay vs Video Ambience vs Music)
            addView(sectionHeader("3. РАЗДЕЛЕНИЕ ЗВУКОВЫХ ДОРОЖЕК + AI-SHAZAM ПОДМЕНА МУЗЫКИ"))

            val stemHint = TextView(this@MainActivity).apply {
                text = "Раздели звук исходника на 3 дорожки: 1) Наложенный сверху голос, 2) Чистый интершум видео (без диктора), 3) Музыка. Здесь же AI-Shazam узнаёт мелодию в дорожке и подменяет её на похожую без АП!"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 11.5f
                setPadding(0, 0, 0, dp(6))
            }
            addView(stemHint)

            val splitStemsBtn = createStyledButton(
                "🎚 Разделить звук куска на 3 дорожки (Голос / Интершум / Музыка)",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val sSec = trimStartInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    val eSec = trimEndInput.text.toString().replace(",", ".").toDoubleOrNull() ?: cur.durationSec
                    stemStatusLabel.text = "⏳ Разделяю звук на 3 дорожки: 1) Наложенный Голос, 2) Интершум видео (без диктора), 3) Музыка..."
                    lifecycleScope.launch {
                        try {
                            val stems = hardwareEngine.splitVideoAudioIntoStems(srcFile, sSec, eSec)
                            currentSeparatedStems = stems
                            currentStemRoutingMode = AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE
                            stemRoutingModeBtn.text = "🎛 ${currentStemRoutingMode.label}"
                            stemStatusLabel.text = "✅ Дорожки разделены!\n" +
                                "• 🗣 Голос сверху: ${stems.vocalsOverlayFile.length() / 1024} KB\n" +
                                "• 🏍 Интершум видео (без диктора): ${stems.ambienceSfxFile.length() / 1024} KB\n" +
                                "• 🎵 Музыкальный подклад: ${stems.musicHarmonicsFile.length() / 1024} KB"
                            Toast.makeText(this@MainActivity, "✅ Звук разделён на 3 дорожки!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            stemStatusLabel.text = "❌ Ошибка разделения дорожек: ${e.message}"
                        }
                    }
                }
            }
            addView(splitStemsBtn, buttonLayoutParams())

            stemRoutingModeBtn = createStyledButton(
                "🎛 ${currentStemRoutingMode.label}",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val modes = AudioStemRoutingMode.entries
                    currentStemRoutingMode = modes[(currentStemRoutingMode.ordinal + 1) % modes.size]
                    text = "🎛 ${currentStemRoutingMode.label}"
                }
            }
            addView(stemRoutingModeBtn, buttonLayoutParams())

            val listenStemsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
                setPadding(0, dp(6), 0, 0)
            }

            val listenVocalBtn = createStyledButton("▶️ Голос", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val voc = currentSeparatedStems?.vocalsOverlayFile
                    if (voc == null || !voc.exists()) {
                        Toast.makeText(this@MainActivity, "Сначала нажми «🎚 Разделить звук куска на 3 дорожки»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    playStemAudioPreview(voc, "Дорожка 1: Только наложенный сверху Голос")
                }
            }

            val listenAmbienceBtn = createStyledButton("▶️ Интершум", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val amb = currentSeparatedStems?.ambienceSfxFile
                    if (amb == null || !amb.exists()) {
                        Toast.makeText(this@MainActivity, "Сначала нажми «🎚 Разделить звук куска на 3 дорожки»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    playStemAudioPreview(amb, "Дорожка 2: Интершум видео (голос диктора стёрт)")
                }
            }

            val listenMusicStemBtn = createStyledButton("▶️ Музыка", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val mus = currentSeparatedStems?.musicHarmonicsFile
                    if (mus == null || !mus.exists()) {
                        Toast.makeText(this@MainActivity, "Сначала нажми «🎚 Разделить звук куска на 3 дорожки»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    playStemAudioPreview(mus, "Дорожка 3: Выделенная музыкальная подложка исходника")
                }
            }

            val stopStemBtn = createStyledButton("⏹ Стоп", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    stopStemAudioPreview()
                }
            }

            listenStemsRow.addView(listenVocalBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(listenAmbienceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(listenMusicStemBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(stopStemBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(listenStemsRow)

            stemStatusLabel = TextView(this@MainActivity).apply {
                text = "🎚 Статус дорожек: Звук ещё не разделён (нажми кнопку выше, чтобы отделить верхний голос от звуков видео)"
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(stemStatusLabel)

            // AI-Shazam & Music Stem Replacement Sub-Block
            val aiShazamBtn = createStyledButton(
                "🔎 AI-Shazam: Узнать музыку в дорожке и заменить на похожую",
                "#09090B",
                "#34D399"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val sSec = trimStartInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    val eSec = trimEndInput.text.toString().replace(",", ".").toDoubleOrNull() ?: cur.durationSec

                    shazamStatusLabel.text = "⏳ AI-Shazam слушает музыкальную дорожку видео, определяет жанр/мелодию и ищет похожую без АП..."
                    lifecycleScope.launch {
                        try {
                            val stems = currentSeparatedStems ?: hardwareEngine.splitVideoAudioIntoStems(srcFile, sSec, eSec).also {
                                currentSeparatedStems = it
                                stemStatusLabel.text = "✅ Дорожки разделены автоматически для AI-Shazam!"
                            }

                            val match = subtitleTransformer.analyzeMusicStemAndFindSimilar(
                                musicStemFile = stems.musicHarmonicsFile,
                                videoTitle = "${cur.title} ${cur.aiSummary}"
                            )
                            lastShazamResult = match
                            currentShazamCandidateIndex = 0

                            if (match.candidateTracks.isNotEmpty()) {
                                val (firstUrl, firstTitle) = match.candidateTracks.first()
                                shazamStatusLabel.text = "🎵 Узнано: ${match.detectedTrackAndGenre}\n" +
                                    "⚡ Характер: ${match.moodAndBpm}\n" +
                                    "⬇️ Скачиваю похожую мелодию #1: «$firstTitle»..."
                                val bgmFile = subtitleTransformer.downloadReplacementMusicCandidate(firstUrl, firstTitle)
                                currentReplacementMusicFile = bgmFile
                                toggleReplaceMusicChk.isChecked = true
                                if (currentStemRoutingMode == AudioStemRoutingMode.FULL_ORIGINAL_MIX) {
                                    currentStemRoutingMode = AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC
                                    stemRoutingModeBtn.text = "🎛 ${currentStemRoutingMode.label}"
                                }
                                shazamStatusLabel.text = "🎵 AI-Shazam определил: ${match.detectedTrackAndGenre} (${match.moodAndBpm})\n" +
                                    "✅ Подставлена похожая мелодия [1/${match.candidateTracks.size}]: «$firstTitle» (${bgmFile.length() / 1024} KB)"
                                Toast.makeText(this@MainActivity, "✅ Похожая мелодия найдена и подставлена!", Toast.LENGTH_LONG).show()
                            } else {
                                shazamStatusLabel.text = "🎵 AI-Shazam определил: ${match.detectedTrackAndGenre} (${match.moodAndBpm})\n" +
                                    "⚠️ Не удалось автоматически скачать трек — выбери свой файл кнопкой «📂 Своя музыка»."
                            }
                        } catch (e: Exception) {
                            shazamStatusLabel.text = "❌ Ошибка AI-Shazam: ${e.message}"
                        }
                    }
                }
            }
            addView(aiShazamBtn, buttonLayoutParams())

            val shazamActionsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(6), 0, 0)
            }

            val nextSimilarTrackBtn = createStyledButton("🔄 Другая похожая", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val match = lastShazamResult
                    if (match == null || match.candidateTracks.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Сначала нажми «🔎 AI-Shazam: Узнать музыку...»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    currentShazamCandidateIndex = (currentShazamCandidateIndex + 1) % match.candidateTracks.size
                    val (url, title) = match.candidateTracks[currentShazamCandidateIndex]
                    shazamStatusLabel.text = "⏳ Качаю вариант [${currentShazamCandidateIndex + 1}/${match.candidateTracks.size}]: «$title»..."
                    lifecycleScope.launch {
                        try {
                            val bgmFile = subtitleTransformer.downloadReplacementMusicCandidate(url, title)
                            currentReplacementMusicFile = bgmFile
                            toggleReplaceMusicChk.isChecked = true
                            shazamStatusLabel.text = "🎵 AI-Shazam: ${match.detectedTrackAndGenre}\n" +
                                "✅ Активна мелодия [${currentShazamCandidateIndex + 1}/${match.candidateTracks.size}]: «$title» (${bgmFile.length() / 1024} KB)"
                            playStemAudioPreview(bgmFile, "Новая похожая мелодия: $title")
                        } catch (e: Exception) {
                            shazamStatusLabel.text = "❌ Не удалось скачать вариант: ${e.message} (нажми «🔄 Другая похожая» ещё раз)"
                        }
                    }
                }
            }

            val previewNewMusicBtn = createStyledButton("▶️ Слушать новую", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val bgm = currentReplacementMusicFile
                    if (bgm == null || !bgm.exists()) {
                        Toast.makeText(this@MainActivity, "Сначала найди мелодию через AI-Shazam или загрузи свою", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    playStemAudioPreview(bgm, "Новая мелодия для подмены: ${bgm.name}")
                }
            }

            val importOwnMusicBtn = createStyledButton("📂 Своя музыка", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    importReplacementMusicLauncher.launch("audio/*")
                }
            }

            shazamActionsRow.addView(nextSimilarTrackBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            shazamActionsRow.addView(previewNewMusicBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply { rightMargin = dp(4) })
            shazamActionsRow.addView(importOwnMusicBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(shazamActionsRow)

            toggleReplaceMusicChk = createCheckBox("🎵 Подменить старую музыку в видео на новую подобранную мелодию", false)
            addView(toggleReplaceMusicChk)

            shazamStatusLabel = TextView(this@MainActivity).apply {
                text = "🎵 AI-Shazam: Нажми кнопку выше, чтобы распознать мелодию/жанр в видео и автоматически заменить музыкальную дорожку на похожую"
                setTextColor(Color.parseColor("#A7F3D0"))
                textSize = 11.5f
                setPadding(0, dp(2), 0, dp(6))
            }
            addView(shazamStatusLabel)

            // Section 4: Voiceover / AI Dubbing / Voice Timbre Changer
            addView(sectionHeader("4. ИНСТРУМЕНТ ПЕРЕОЗВУЧКИ И СМЕНЫ ГОЛОСА (ПОВЕРХ ИНТЕРШУМА)"))

            voiceoverDubModeBtn = createStyledButton(
                "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}",
                "#09090B",
                "#A855F7"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val modes = VoiceoverDubMode.entries
                    currentVoiceoverDubMode = modes[(currentVoiceoverDubMode.ordinal + 1) % modes.size]
                    text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                }
            }
            addView(voiceoverDubModeBtn, buttonLayoutParams())

            ttsVoiceProfileBtn = createStyledButton(
                "🗣 Голос диктора TTS: ${currentTtsVoiceProfile.label}",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val voices = TtsVoiceProfile.entries
                    currentTtsVoiceProfile = voices[(currentTtsVoiceProfile.ordinal + 1) % voices.size]
                    text = "🗣 Голос диктора TTS: ${currentTtsVoiceProfile.label}"
                }
            }
            addView(ttsVoiceProfileBtn, buttonLayoutParams())

            addView(fieldLabel("Текст для новой озвучки (из выделенной дорожки Голоса или свой):"))
            dubScriptInput = EditText(this@MainActivity).apply {
                hint = "Нажми «✍️ Вытащить речь диктора» ниже или введи свой текст..."
                setHintTextColor(Color.parseColor("#52525B"))
                setTextColor(Color.parseColor("#F4F4F5"))
                setBackgroundColor(Color.parseColor("#18181B"))
                textSize = 13f
                minLines = 3
                maxLines = 6
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            addView(dubScriptInput)

            val dubActionsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(6), 0, 0)
            }

            val aiRewriteScriptBtn = createStyledButton("✍️ ИИ-Рерайт/Перевод", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val sSec = trimStartInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    val eSec = trimEndInput.text.toString().replace(",", ".").toDoubleOrNull() ?: cur.durationSec
                    val isolatedVoc = currentSeparatedStems?.vocalsOverlayFile?.takeIf { it.exists() }
                    voiceoverStatusLabel.text = if (isolatedVoc != null) {
                        "⏳ Слушаю ТОЛЬКО выделенную дорожку верхнего голоса (${isolatedVoc.name}) и делаю ИИ-рерайт/перевод..."
                    } else {
                        "⏳ Извлекаю речь из куска и делаю ИИ-перевод/рерайт через Gemini..."
                    }
                    lifecycleScope.launch {
                        val script = subtitleTransformer.generateDubbingScriptFromVideo(
                            sourceUrl = cur.sourceUrl,
                            localVideoFile = srcFile,
                            startSec = sSec,
                            endSec = eSec,
                            isolatedVocalStemFile = isolatedVoc
                        )
                        dubScriptInput.setText(script)
                        voiceoverStatusLabel.text = "✅ Скрипт готов! Отредактируй при необходимости и жми «🗣 Озвучить (TTS)»."
                    }
                }
            }

            val synthTtsBtn = createStyledButton("🗣 Озвучить (TTS)", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val txt = dubScriptInput.text.toString().trim()
                    if (txt.isBlank()) {
                        Toast.makeText(this@MainActivity, "Сначала введи текст или нажми «✍️ ИИ-Рерайт/Перевод»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    voiceoverStatusLabel.text = "⏳ Синтезирую голос (${currentTtsVoiceProfile.label})..."
                    lifecycleScope.launch {
                        try {
                            val voFile = subtitleTransformer.synthesizeVoiceoverToAudioFile(txt, currentTtsVoiceProfile)
                            currentCustomVoiceoverFile = voFile
                            if (currentSeparatedStems != null) {
                                currentStemRoutingMode = AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE
                                stemRoutingModeBtn.text = "🎛 ${currentStemRoutingMode.label}"
                            }
                            if (currentVoiceoverDubMode != VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG &&
                                currentVoiceoverDubMode != VoiceoverDubMode.DUB_REPLACE_100PCT
                            ) {
                                currentVoiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                                voiceoverDubModeBtn.text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                            }
                            voiceoverStatusLabel.text = "✅ Озвучка готова: ${voFile.name} (${voFile.length() / 1024} KB). Сведение: ${currentStemRoutingMode.label}"
                            Toast.makeText(this@MainActivity, "✅ Озвучка синтезирована и положена поверх чистого интершума!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            voiceoverStatusLabel.text = "❌ Ошибка синтеза: ${e.message}"
                        }
                    }
                }
            }

            val importAudioBtn = createStyledButton("📂 Свой аудио", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    importVoiceoverAudioLauncher.launch("audio/*")
                }
            }

            dubActionsRow.addView(aiRewriteScriptBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            dubActionsRow.addView(synthTtsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            dubActionsRow.addView(importAudioBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
            addView(dubActionsRow)

            voiceoverStatusLabel = TextView(this@MainActivity).apply {
                text = "🎙 Статус озвучки: Используется родной голос (или выбери смену тембра / сгенерируй TTS выше)"
                setTextColor(Color.parseColor("#A7F3D0"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(voiceoverStatusLabel)

            // Section 5: Music Removal & Video/Audio Anti-Detect
            addView(sectionHeader("5. ДОП. ФИЛЬТР МУЗЫКИ И АНТИ-ДЕТЕКТ (ВИДЕО + АУДИО)"))

            musicRemovalBtn = createStyledButton(
                "🎵 Удаление музыки: ${currentMusicRemovalMode.label}",
                "#09090B",
                "#38BDF8"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val modes = MusicRemovalMode.entries
                    currentMusicRemovalMode = modes[(currentMusicRemovalMode.ordinal + 1) % modes.size]
                    text = "🎵 Удаление музыки: ${currentMusicRemovalMode.label}"
                }
            }
            addView(musicRemovalBtn, buttonLayoutParams())

            chkVideoZoom = createCheckBox("🛡 Видео: Микро-зум 1.04x и сдвиг пиксельной сетки", true)
            chkVideoColorNoise = createCheckBox("🛡 Видео: Сдвиг гаммы/контраста + микро-зерно + очистка метаданных", true)
            chkVideoFlip = createCheckBox("🪞 Видео: Отзеркалить кадр по горизонтали (Flip)", false)
            chkAudioPitchTempo = createCheckBox("🔊 Аудио: Сдвиг тона и темпа (+1.8% Pitch/Tempo)", true)
            chkAudioEqStereo = createCheckBox("🔊 Аудио: Сдвиг АЧХ голоса (EQ 3.1kHz + Стерео-расширение)", true)
            chkAudioTrimSilence = createCheckBox("✂️ Аудио: Вырезать паузы (не включай, если режет конец фраз)", false)

            addView(chkVideoZoom)
            addView(chkVideoColorNoise)
            addView(chkVideoFlip)
            addView(chkAudioPitchTempo)
            addView(chkAudioEqStereo)
            addView(chkAudioTrimSilence)

            // Section 6: Hardsub Removal & New Subtitles
            addView(sectionHeader("6. АВТО-УДАЛЕНИЕ ВШИТЫХ (СТАРЫХ) СУБТИТРОВ + НОВЫЕ СУБТИТРЫ"))

            val scanHardsubBtn = createStyledButton(
                "🔍 Авто-поиск старых вшитых субтитров на видеодорожке",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val sSec = trimStartInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                    val eSec = trimEndInput.text.toString().replace(",", ".").toDoubleOrNull() ?: cur.durationSec
                    val span = (eSec - sSec).coerceIn(5.0, 25.0)
                    hardsubStatusLabel.text = "⏳ Сканирую кадры видео на наличие вшитых субтитров (анализ глифов и обводки)..."
                    lifecycleScope.launch {
                        try {
                            val reg = faceHelper.detectBurnedInSubtitleRegion(srcFile, sSec, span)
                            currentDetectedHardsubRegion = reg
                            if (currentHardsubRemovalMode == ExistingSubtitleRemovalMode.OFF) {
                                currentHardsubRemovalMode = ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS
                                hardsubRemovalModeBtn.text = "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}"
                            }
                            hardsubStatusLabel.text = "✅ ${reg.summary}\nАктивный метод удаления: ${currentHardsubRemovalMode.label}"
                            Toast.makeText(this@MainActivity, "✅ Координаты старых субтитров найдены!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            hardsubStatusLabel.text = "❌ Ошибка сканера субтитров: ${e.message}"
                        }
                    }
                }
            }
            addView(scanHardsubBtn, buttonLayoutParams())

            hardsubRemovalModeBtn = createStyledButton(
                "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val modes = ExistingSubtitleRemovalMode.entries
                    currentHardsubRemovalMode = modes[(currentHardsubRemovalMode.ordinal + 1) % modes.size]
                    text = "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}"
                }
            }
            addView(hardsubRemovalModeBtn, buttonLayoutParams())

            hardsubStatusLabel = TextView(this@MainActivity).apply {
                text = "🧹 Статус: Выбери режим удаления выше или нажми «🔍 Авто-поиск старых вшитых субтитров»"
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(hardsubStatusLabel)

            chkSubtitles = createCheckBox("💬 Генерировать свои караоке-субтитры сверху (YouTube VTT / Gemini)", false)
            addView(chkSubtitles)

            subZoneBtn = createStyledButton(
                "📍 Позиция своих субтитров: ${currentSubZone.label}",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val zones = listOf(
                        SubtitlePlacementZone.ADAPTIVE_AUTO,
                        SubtitlePlacementZone.CENTER_SEAM,
                        SubtitlePlacementZone.TOP_SAFE,
                        SubtitlePlacementZone.BOTTOM_SAFE
                    )
                    val idx = zones.indexOf(currentSubZone).coerceAtLeast(0)
                    currentSubZone = zones[(idx + 1) % zones.size]
                    text = "📍 Позиция своих субтитров: ${currentSubZone.label}"
                }
            }
            addView(subZoneBtn, buttonLayoutParams())

            // Section 7: Render & Save to Gallery
            addView(sectionHeader("7. ОБРАБОТКА И СОХРАНЕНИЕ В ГАЛЕРЕЮ (FULL-HD 12 МБИТ/С)"))

            studioRenderStatusView = TextView(this@MainActivity).apply {
                text = "Готов к сборке. Выбери таймкод и нажми «⚡ Быстрый тест (5 сек)» или «💾 Собрать FullHD в Галерею»."
                setTextColor(Color.parseColor("#FBBF24"))
                setBackgroundColor(Color.parseColor("#18181B"))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                val p = dp(12)
                setPadding(p, p, p, p)
            }
            addView(studioRenderStatusView)

            val preview5sBtn = createStyledButton(
                "⚡ БЫСТРЫЙ ТЕСТ (5 СЕК В ПЛЕЕР СВЕРХУ)",
                "#F4F4F5",
                "#3B82F6"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    runStudioRender(isPreviewPass = true)
                }
            }
            addView(preview5sBtn, buttonLayoutParams())

            val masterGalleryBtn = createStyledButton(
                "💾 СОБРАТЬ FULL-HD (12 МБИТ/С) И СОХРАНИТЬ В ГАЛЕРЕЮ",
                "#09090B",
                "#10B981"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    runStudioRender(isPreviewPass = false)
                }
            }
            addView(masterGalleryBtn, buttonLayoutParams())
        }
    }

    private fun bindSelectedSourceToStudio(item: StudioInboxItem) {
        selectedInboxItem = item
        val file = clutterFilter.getInboxVideoFile(item)
        val sizeMb = String.format(Locale.US, "%.1f", item.sizeKb / 1024.0)
        val detectedTags = clutterFilter.extractSemanticTopicTags("${item.title} ${item.aiSummary}", item.sourceTags)
        val tagsLine = if (detectedTags.isNotEmpty()) "\n🏷 Теги темы: ${detectedTags.joinToString(" ")}" else ""
        val summaryLine = if (item.aiSummary.isNotBlank()) "\n🧠 Суть: ${item.aiSummary}" else ""
        studioSelectedSourceLabel.text = "✅ Выбран: «${item.title}» (${item.durationSec}с • $sizeMb МБ)$tagsLine$summaryLine"

        val startSec = if (item.recommendedEndSec > item.recommendedStartSec) item.recommendedStartSec else 0.0
        val endSec = if (item.recommendedEndSec > item.recommendedStartSec) item.recommendedEndSec else item.durationSec

        trimStartInput.setText(String.format(Locale.US, "%.1f", startSec))
        trimEndInput.setText(String.format(Locale.US, "%.1f", endSec))
        updatePoolSelectorButtonText()

        if (file.exists()) {
            playFileInStudioPlayer(file, "Исходник: ${item.title}", seekToMs = (startSec * 1000).toInt())
        }
    }

    private fun playFileInStudioPlayer(file: File, label: String, seekToMs: Int = 0) {
        try {
            studioPlayerLabel.text = "📺 Сейчас в плеере: $label"
            studioPlayerView.stopPlayback()
            studioPlayerView.setVideoPath(file.absolutePath)
            studioPlayerView.setOnPreparedListener { mp ->
                mp.isLooping = true
                if (seekToMs > 0) {
                    studioPlayerView.seekTo(seekToMs)
                }
                studioPlayerView.start()
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("PLAYER", "Ошибка воспроизведения файла ${file.name}", e)
        }
    }

    private fun cyclePoolClipSelection() {
        val clips = clutterFilter.listVerifiedPoolClips()
        if (clips.isEmpty()) {
            selectedPoolClipName = "AUTO"
            updatePoolSelectorButtonText()
            Toast.makeText(this, "Пул фонов пока пуст — перейди во вкладку «🗂 Фоны»", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf("AUTO") + clips.map { it.name }
        val curIdx = options.indexOf(selectedPoolClipName).coerceAtLeast(0)
        selectedPoolClipName = options[(curIdx + 1) % options.size]
        updatePoolSelectorButtonText()
    }

    private fun updatePoolSelectorButtonText() {
        val clips = clutterFilter.listVerifiedPoolClips()
        val curItem = selectedInboxItem
        val sourceTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }

        if (selectedPoolClipName == "AUTO" || clips.none { it.name == selectedPoolClipName }) {
            selectedPoolClipName = "AUTO"
            if (sourceTags.isNotEmpty()) {
                val matched = clutterFilter.findMatchingPoolClipsByTags(sourceTags)
                if (matched.isNotEmpty()) {
                    poolClipSelectBtn.text = "🎬 Фон: Авто по тегу ${sourceTags.take(2).joinToString(" ")} (найдено: ${matched.size} шт.)"
                } else {
                    poolClipSelectBtn.text = "🎬 Фон: Авто-микс (нет тега ${sourceTags.first()} из ${clips.size} шт.)"
                }
            } else {
                poolClipSelectBtn.text = "🎬 Нижний фон: Авто-микс (${clips.size} шт. в пуле)"
            }
        } else {
            val chosenFile = clips.first { it.name == selectedPoolClipName }
            val meta = clutterFilter.getPoolClipMetadata(chosenFile)
            val idx = clips.indexOfFirst { it.name == selectedPoolClipName } + 1
            poolClipSelectBtn.text = "🎬 Фон #$idx: ${meta.tags.take(2).joinToString(" ")} ($selectedPoolClipName)"
        }
    }

    private fun previewSelectedBackgroundClipInPlayer() {
        val clips = clutterFilter.listVerifiedPoolClips()
        if (clips.isEmpty()) {
            Toast.makeText(this, "В пуле нет фонов. Перейди во вкладку «🗂 Фоны», чтобы добавить или скачать!", Toast.LENGTH_LONG).show()
            return
        }
        val curItem = selectedInboxItem
        val sourceTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }
        val target = if (selectedPoolClipName != "AUTO") {
            clips.firstOrNull { it.name == selectedPoolClipName } ?: clips.first()
        } else {
            clutterFilter.findMatchingPoolClipsByTags(sourceTags).firstOrNull()?.first ?: clips.first()
        }
        val meta = clutterFilter.getPoolClipMetadata(target)
        playFileInStudioPlayer(target, "Нижний фон: ${target.name} (${meta.tags.joinToString(" ")})")
    }

    private fun playStemAudioPreview(audioFile: File, label: String) {
        try {
            stopStemAudioPreview()
            studioPlayerView.pause()
            stemPreviewPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
            }
            stemStatusLabel.text = "🔊 Сейчас играет отдельно: $label (${audioFile.name})"
            Toast.makeText(this, "🔊 Играет: $label", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка прослушивания дорожки: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStemAudioPreview() {
        try {
            stemPreviewPlayer?.stop()
            stemPreviewPlayer?.release()
        } catch (_: Exception) {
        }
        stemPreviewPlayer = null
    }

    private fun buildCurrentStudioRenderConfig(): ModularRenderConfig {
        val startSec = trimStartInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
        val endSec = trimEndInput.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0

        val curItem = selectedInboxItem
        val preferredTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }

        val dsp = AntiDetectDspLayerConfig(
            enabled = chkVideoZoom.isChecked || chkVideoColorNoise.isChecked || chkVideoFlip.isChecked ||
                chkAudioPitchTempo.isChecked || chkAudioEqStereo.isChecked || chkAudioTrimSilence.isChecked ||
                currentMusicRemovalMode != MusicRemovalMode.OFF ||
                currentVoiceoverDubMode != VoiceoverDubMode.OFF ||
                currentStemRoutingMode != AudioStemRoutingMode.FULL_ORIGINAL_MIX,
            enableMicroZoom = chkVideoZoom.isChecked,
            enableHorizontalFlip = chkVideoFlip.isChecked,
            enableVideoNoiseInjection = chkVideoColorNoise.isChecked,
            enableMicroColorEqShift = chkVideoColorNoise.isChecked,
            enableAudioPitchTempoMicroJitter = chkAudioPitchTempo.isChecked,
            enableAudioEqVoicePresence = chkAudioEqStereo.isChecked,
            enableSilenceTrimming350ms = chkAudioTrimSilence.isChecked,
            musicRemovalMode = currentMusicRemovalMode,
            voiceoverDubMode = currentVoiceoverDubMode,
            stemRoutingMode = currentStemRoutingMode
        )

        val subs = SubtitleLayerConfig(
            enabled = chkSubtitles.isChecked,
            placementZone = currentSubZone,
            customY = currentSubZone.defaultY
        )

        return ModularRenderConfig(
            layoutLayer = currentLayoutLayer,
            enableSmartFaceCrop = chkSmartFace.isChecked,
            trimStartSec = startSec,
            trimEndSec = endSec,
            selectedPoolClipName = selectedPoolClipName,
            preferredTopicTags = preferredTags,
            customVoiceoverAudioFile = currentCustomVoiceoverFile,
            customReplacementMusicFile = currentReplacementMusicFile,
            enableReplacementMusicBed = toggleReplaceMusicChk.isChecked,
            separatedStems = currentSeparatedStems,
            existingSubtitleRemovalMode = currentHardsubRemovalMode,
            detectedHardsubRegion = currentDetectedHardsubRegion,
            subtitleLayer = subs,
            dspLayer = dsp,
            videoBitrateKbps = 12000,
            capBitrateForTelegram50Mb = false
        )
    }

    private fun runStudioRender(isPreviewPass: Boolean) {
        val item = selectedInboxItem
        if (item == null) {
            Toast.makeText(this, "Сначала выбери исходник во вкладке «📥 Исходники»!", Toast.LENGTH_LONG).show()
            switchTab(0)
            return
        }
        val sourceFile = clutterFilter.getInboxVideoFile(item)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "Файл исходника не найден!", Toast.LENGTH_LONG).show()
            return
        }

        val initialConfig = buildCurrentStudioRenderConfig()
        studioRenderStatusView.text = if (isPreviewPass) {
            "⏳ Собираю 5-секундный тест (от ${initialConfig.trimStartSec} сек)..."
        } else {
            "⏳ Идёт сборка FullHD 1080×1920 (12 Мбит/с) + Анти-Детект..."
        }

        lifecycleScope.launch {
            try {
                val renderedFile = withContext(Dispatchers.IO) {
                    val spanSec = if (initialConfig.trimEndSec > initialConfig.trimStartSec + 1.0) {
                        initialConfig.trimEndSec - initialConfig.trimStartSec
                    } else {
                        item.durationSec - initialConfig.trimStartSec
                    }.coerceAtLeast(5.0)

                    // Automatically scan for burned-in subtitles if removal mode is enabled and not scanned yet
                    val activeHardsubRegion = if (initialConfig.existingSubtitleRemovalMode != ExistingSubtitleRemovalMode.OFF) {
                        initialConfig.detectedHardsubRegion ?: faceHelper.detectBurnedInSubtitleRegion(
                            videoFile = sourceFile,
                            startSec = initialConfig.trimStartSec,
                            durationSec = spanSec.coerceAtMost(20.0)
                        ).also { currentDetectedHardsubRegion = it }
                    } else {
                        null
                    }

                    val config = initialConfig.copy(detectedHardsubRegion = activeHardsubRegion)

                    val clutterFile = clutterFilter.buildShuffledClutterReel(
                        targetDurationSec = if (isPreviewPass) 5.0 else spanSec,
                        fallbackPrimaryVideo = sourceFile,
                        specificPoolClipName = config.selectedPoolClipName,
                        preferredTopicTags = config.preferredTopicTags
                    )

                    val framing = faceHelper.computeFramingPlan(
                        videoFile = sourceFile,
                        enableSmartFaceCrop = config.enableSmartFaceCrop,
                        hardsubRegion = activeHardsubRegion,
                        hardsubRemovalMode = config.existingSubtitleRemovalMode
                    )

                    val assFile = if (config.subtitleLayer.enabled) {
                        subtitleTransformer.prepareAssSubtitles(
                            sourceUrl = item.sourceUrl,
                            localVideoFile = sourceFile,
                            config = config.subtitleLayer,
                            framingPlan = framing
                        ).first.assFile
                    } else {
                        null
                    }

                    hardwareEngine.renderPipeline(
                        primaryVideoFile = sourceFile,
                        clutterReelFile = clutterFile,
                        framingPlan = framing,
                        assSubtitleFile = assFile,
                        config = config,
                        isPreviewPass = isPreviewPass
                    )
                }

                if (isPreviewPass) {
                    val kb = renderedFile.length() / 1024
                    studioRenderStatusView.text = "✅ 5-сек тест готов ($kb KB)! Воспроизвожу в плеере сверху."
                    playFileInStudioPlayer(renderedFile, "5-сек Тест (${config.layoutLayer.label})")
                } else {
                    val savedGalleryFile = hardwareEngine.saveRenderedVideoToGallery(renderedFile, item.title)
                    lastRenderedGalleryFile = savedGalleryFile
                    renderedFile.delete()
                    val mb = String.format(Locale.US, "%.1f", savedGalleryFile.length() / (1024.0 * 1024.0))
                    studioRenderStatusView.text = "✅ ГОТОВО! Ролик сохранён в Галерею телефона:\nMovies/PocketMediaForge/${savedGalleryFile.name} ($mb МБ)"
                    playFileInStudioPlayer(savedGalleryFile, "Готовый Мастер: ${savedGalleryFile.name}")
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Сохранено в Галерею! Теперь можно загружать на YouTube.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                studioRenderStatusView.text = "❌ Ошибка сборки: ${e.message}"
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================================
    // ВКЛАДКА 2: 🗂 МОИ ФОНЫ (ПУЛ ЗАЛИПАЛОК С ТЕГАМИ И АВТО-ПОДБОРОМ)
    // =========================================================================
    private fun buildPoolTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("🗂 МЕНЕДЖЕР ФОНОВ (ТЕГИ ДЛЯ АВТО-ПОДБОРА ПОД ИСХОДНИК)"))

            val desc = TextView(this@MainActivity).apply {
                text = "У каждого фона есть теги (например: #мото, #авто, #cs2, #minecraft, #механика, #антистресс). Если исходное видео про мотоцикл — Студия автоматически выберет фон с тегом #мото!"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            }
            addView(desc)

            addView(fieldLabel("Введи тему или теги (например: мотоцикл, дрифт бмв, cs2 surf, пресс):"))
            poolTopicTagInput = createInputField("мотоцикл #мото #гонки", "", false)
            addView(poolTopicTagInput)

            val topicFetchBtn = createStyledButton(
                "⬇️ Скачать с YouTube залипалки по этой теме (с тегами)",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val query = poolTopicTagInput.text.toString().trim()
                    if (query.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи тему в поле выше (например: мотоцикл)", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Ищу и качаю залипалки по теме «$query»...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        val added = clutterFilter.downloadTaggedClutterByTopic(query, maxClipsToAdd = 2)
                        refreshPoolTab()
                        if (added.isNotEmpty()) {
                            Toast.makeText(this@MainActivity, "✅ Добавлено ${added.size} шт. по теме «$query»!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ Подходящих коротких роликов не нашлось — попробуй другое слово", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            addView(topicFetchBtn, buttonLayoutParams())

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val addOwnBgBtn = createStyledButton("➕ Свой фон с телефона (с тегами выше)", "#09090B", "#10B981").apply {
                setOnClickListener {
                    importPoolClipLauncher.launch("video/*")
                }
            }

            val fetchMoreBtn = createStyledButton("🔄 Докачать базовые фоны", "#F4F4F5", "#3B82F6").apply {
                setOnClickListener {
                    clutterFilter.triggerBackgroundPoolWarmupIfNeeded(forceAddMore = true)
                    Toast.makeText(this@MainActivity, "🔄 Запущена докачка фонов в пул!", Toast.LENGTH_LONG).show()
                }
            }

            btnRow.addView(addOwnBgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            btnRow.addView(fetchMoreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnRow)

            poolListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            addView(poolListLayout)
        }
    }

    private fun refreshPoolTab() {
        poolListLayout.removeAllViews()
        val clips = clutterFilter.listVerifiedPoolClips()
        updatePoolSelectorButtonText()

        if (clips.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Пул фонов пуст.\nВведи тему выше (например: «мотоцикл») и нажми «⬇️ Скачать с YouTube залипалки по этой теме» или добавь своё видео с телефона."
                setTextColor(Color.parseColor("#71717A"))
                textSize = 12.5f
                setPadding(dp(12), dp(18), dp(12), dp(18))
                setBackgroundColor(Color.parseColor("#18181B"))
            }
            poolListLayout.addView(emptyTv)
            return
        }

        clips.forEachIndexed { idx, file ->
            val meta = clutterFilter.getPoolClipMetadata(file)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#18181B"))
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val sizeMb = String.format(Locale.US, "%.1f", file.length() / (1024.0 * 1024.0))
            val isSelected = selectedPoolClipName == file.name
            val labelTv = TextView(this).apply {
                text = "Фон #${idx + 1}: ${meta.title.ifBlank { file.name }} ($sizeMb МБ)" +
                    if (isSelected) " ✅ [ВЫБРАН ВРУЧНУЮ]" else ""
                setTextColor(if (isSelected) Color.parseColor("#34D399") else Color.parseColor("#F4F4F5"))
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            }
            card.addView(labelTv)

            val tagsEditRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, 0, 0, dp(6))
            }

            val tagEditInput = createInputField("#мото #гонки", meta.tags.joinToString(" "), false)
            val saveTagBtn = createStyledButton("💾 Сохранить теги", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    clutterFilter.setPoolClipTags(file.name, tagEditInput.text.toString())
                    refreshPoolTab()
                    Toast.makeText(this@MainActivity, "✅ Теги обновлены!", Toast.LENGTH_SHORT).show()
                }
            }

            tagsEditRow.addView(tagEditInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { rightMargin = dp(6) })
            tagsEditRow.addView(saveTagBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(tagsEditRow)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }

            val watchBtn = createStyledButton("▶️ Смотреть", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    switchTab(1)
                    playFileInStudioPlayer(file, "Фон #${idx + 1}: ${file.name} (${meta.tags.joinToString(" ")})")
                }
            }

            val selectBtn = createStyledButton("✅ Выбрать вручную", "#09090B", "#10B981").apply {
                setOnClickListener {
                    selectedPoolClipName = file.name
                    updatePoolSelectorButtonText()
                    refreshPoolTab()
                    Toast.makeText(this@MainActivity, "Выбран фон ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }

            val deleteBtn = createStyledButton("🗑 Удалить", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    clutterFilter.deletePoolClip(file.name)
                    if (selectedPoolClipName == file.name) {
                        selectedPoolClipName = "AUTO"
                    }
                    refreshPoolTab()
                }
            }

            row.addView(watchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            row.addView(selectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = dp(6) })
            row.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))

            card.addView(row)
            poolListLayout.addView(card)
        }
    }

    // =========================================================================
    // ВКЛАДКА 3: ⚙️ КЛЮЧИ И ЛОГ
    // =========================================================================
    private fun buildSettingsTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("1. КЛЮЧИ TELEGRAM-СКАУТА И GEMINI"))

            addView(fieldLabel("Telegram Bot Token (от @BotFather):"))
            botTokenInput = createInputField("123456789:AAH...", configStore.getTelegramBotToken(), false)
            addView(botTokenInput)

            addView(fieldLabel("Gemini API Key (необязательно, для авто-субтитров):"))
            geminiKeyInput = createInputField("AIzaSy...", configStore.getGeminiApiKey(), false)
            addView(geminiKeyInput)

            addView(fieldLabel("Разрешённый Chat ID (оставь пустым для любого):"))
            allowedChatIdInput = createInputField("Например: 7820650522", configStore.getAllowedChatId(), false)
            addView(allowedChatIdInput)

            val saveAndStartBtn = createStyledButton(
                "🚀 СОХРАНИТЬ КЛЮЧИ И ПЕРЕЗАПУСТИТЬ УЗЕЛ",
                "#09090B",
                "#10B981"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    configStore.setTelegramBotToken(botTokenInput.text.toString())
                    configStore.setGeminiApiKey(geminiKeyInput.text.toString())
                    configStore.setAllowedChatId(allowedChatIdInput.text.toString())
                    DiagnosticLogger.log("UI", "Пользователь сохранил настройки и перезапустил узел.")
                    PipelineForegroundService.reloadCredentials(applicationContext)
                    Toast.makeText(this@MainActivity, "✅ Ключи сохранены! Telegram-скаут запущен.", Toast.LENGTH_LONG).show()
                }
            }
            addView(saveAndStartBtn, buttonLayoutParams())

            val batteryBtn = createStyledButton(
                "🔋 Отключить оптимизацию батареи OxygenOS",
                "#FBBF24",
                "#27272A"
            ).apply {
                setOnClickListener {
                    if (!powerHelper.isIgnoringBatteryOptimizations()) {
                        startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
                    } else {
                        Toast.makeText(this@MainActivity, "Оптимизация батареи уже отключена ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(batteryBtn, buttonLayoutParams())

            addView(sectionHeader("2. ПОЛНЫЙ ЛОГ ДЕЙСТВИЙ С ЗАПУСКА (LIVE)"))

            liveLogTextView = TextView(this@MainActivity).apply {
                text = DiagnosticLogger.getRecentLinesText(35)
                setTextColor(Color.parseColor("#34D399"))
                setBackgroundColor(Color.parseColor("#111115"))
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                val p = dp(12)
                setPadding(p, p, p, p)
            }
            addView(liveLogTextView)

            val copyLogBtn = createStyledButton(
                "📋 Скопировать весь лог в буфер обмена",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val fullText = DiagnosticLogger.getFullLogFile(applicationContext).readText()
                    cm.setPrimaryClip(ClipData.newPlainText("PocketForgeLog", fullText))
                    Toast.makeText(this@MainActivity, "✅ Лог скопирован!", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyLogBtn, buttonLayoutParams())
        }
    }

    private fun observePipelineStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineForegroundService.statusFlow.collect { status ->
                    val inboxCount = clutterFilter.listStudioInboxItems().size
                    val poolCount = clutterFilter.listVerifiedPoolClips().size
                    val (text, colorHex) = when (status) {
                        is ServiceStatus.Stopped ->
                            "● ОСТАНОВЛЕН • Исходников: $inboxCount • Фонов: $poolCount" to "#F87171"
                        is ServiceStatus.Initializing ->
                            "● ИНИЦИАЛИЗАЦИЯ И ПРОВЕРКА YT-DLP..." to "#FBBF24"
                        is ServiceStatus.IdleListening ->
                            "● СКАУТ ОНЛАЙН • Исходников в Студии: $inboxCount • Фонов: $poolCount" to "#34D399"
                        is ServiceStatus.Processing ->
                            "● ЗАГРУЗКА/РАБОТА [#${status.jobId}]: ${status.stage}" to "#38BDF8"
                        is ServiceStatus.Error ->
                            "● ВНИМАНИЕ: ${status.reason}" to "#FB923C"
                    }
                    statusBadgeView.text = text
                    statusBadgeView.setTextColor(Color.parseColor(colorHex))
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
                        lines.takeLast(35).joinToString("\n")
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

    private fun createTabButton(title: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 11f
            isAllCaps = false
            val p = dp(8)
            setPadding(p, dp(10), p, dp(10))
            setOnClickListener { onClick() }
        }

    private fun tabButtonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        }

    private fun createCheckBox(label: String, checked: Boolean): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 12.5f
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun fieldLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#A1A1AA"))
        textSize = 11.5f
        setPadding(0, dp(6), 0, dp(4))
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
            val p = dp(10)
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
            textSize = 11.5f
            isAllCaps = false
            gravity = Gravity.CENTER
            val p = dp(10)
            setPadding(p, p, p, p)
        }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
