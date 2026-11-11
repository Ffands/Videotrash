package forge.media.pocket.telegram

import android.content.Context
import android.os.Environment
import android.util.Log
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.AdInsertionMode
import forge.media.pocket.compositor.AdLayerConfig
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.AutopilotTagSubscription
import forge.media.pocket.discovery.ContentSortOrder
import forge.media.pocket.discovery.HuntPlatform
import forge.media.pocket.discovery.SearchTimePeriod
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleDensityMode
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TimedWord
import forge.media.pocket.vision.FaceCenteringHelper
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class InteractiveJobSession(
    val jobId: String,
    val chatId: Long,
    val sourceUrl: String,
    val originalTitle: String,
    val originalAuthor: String,
    val sourceTags: List<String>,
    val primaryVideoFile: File,
    val clutterReelFile: File,
    var framingPlan: UpperBoxFramingPlan,
    var cachedWords: List<TimedWord>,
    var latestAssFile: File?,
    var transcriptText: String,
    var config: ModularRenderConfig
)

data class PendingHuntFilterState(
    val queryTags: String,
    var platform: HuntPlatform = HuntPlatform.ALL,
    var sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    var period: SearchTimePeriod = SearchTimePeriod.WEEK,
    var count: Int = 3
)

class TelegramBotWorker(
    private val context: Context,
    private val configStore: NodeConfigStore,
    private val clutterFilter: ClutterEntropyFilter,
    private val topicHunter: TopicHunterEngine,
    private val faceHelper: FaceCenteringHelper,
    private val subtitleTransformer: SubtitleAssTransformer,
    private val hardwareEngine: FFmpegHardwareEngine,
    private val geminiClient: GeminiMetadataClient,
    private val enqueueHardwareTask: (jobId: String, stage: String, block: suspend () -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "TelegramBotWorker"
        private const val SUBTITLE_NUDGE_STEP_PX = 120

        private const val BTN_MENU_HUNT = "🔎 Найти видео по теме"
        private const val BTN_MENU_LINK = "🎬 Собрать по ссылке"
        private const val BTN_MENU_AUTO = "🤖 Автопилот"
        private const val BTN_MENU_STATUS = "📊 Статус и Фон"
    }

    private val isPolling = AtomicBoolean(false)
    private var pollingJob: Job? = null
    private var lastUpdateOffset: Long = 0L

    private val isAutopilotEnabled = AtomicBoolean(false)
    private var autopilotJob: Job? = null
    private var autopilotIntervalMinutes: Long = 60L
    private var autopilotChatId: Long = configStore.getAllowedChatId()

    private val sessions = ConcurrentHashMap<String, InteractiveJobSession>()
    private val huntFilters = ConcurrentHashMap<String, PendingHuntFilterState>()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 25_000L
            socketTimeoutMillis = 120_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class TgUpdatesResponse(
        val ok: Boolean = false,
        val result: List<TgUpdate> = emptyList()
    )

    @Serializable
    private data class TgUpdate(
        @SerialName("update_id") val updateId: Long,
        val message: TgMessage? = null,
        @SerialName("channel_post") val channelPost: TgMessage? = null,
        @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
    )

    @Serializable
    private data class TgMessage(
        @SerialName("message_id") val messageId: Long,
        val chat: TgChat,
        val text: String? = null
    )

    @Serializable
    private data class TgChat(
        val id: Long
    )

    @Serializable
    private data class TgCallbackQuery(
        val id: String,
        val message: TgMessage? = null,
        val data: String? = null
    )

    @Serializable
    private data class InlineKeyboardMarkup(
        @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>
    )

    @Serializable
    private data class InlineKeyboardButton(
        val text: String,
        @SerialName("callback_data") val callbackData: String
    )

    @Serializable
    private data class ReplyKeyboardMarkup(
        val keyboard: List<List<KeyboardButton>>,
        @SerialName("resize_keyboard") val resizeKeyboard: Boolean = true,
        @SerialName("is_persistent") val isPersistent: Boolean = true
    )

    @Serializable
    private data class KeyboardButton(
        val text: String
    )

    private val persistentMainMenuKeyboard = ReplyKeyboardMarkup(
        keyboard = listOf(
            listOf(KeyboardButton(BTN_MENU_HUNT), KeyboardButton(BTN_MENU_LINK)),
            listOf(KeyboardButton(BTN_MENU_AUTO), KeyboardButton(BTN_MENU_STATUS))
        ),
        resizeKeyboard = true,
        isPersistent = true
    )

    private fun baseUrl(): String =
        "https://api.telegram.org/bot${configStore.getTelegramBotToken()}"

    private fun isChatAuthorized(chatId: Long): Boolean {
        val allowed = configStore.getAllowedChatId()
        return allowed == 0L || allowed == chatId
    }

    fun startPolling(scope: CoroutineScope) {
        if (configStore.getTelegramBotToken().isBlank()) {
            Log.w(TAG, "TELEGRAM_BOT_TOKEN is empty; waiting for user input in MainActivity.")
            return
        }
        if (!isPolling.compareAndSet(false, true)) return

        // Start warming up the background clutter pool immediately in the background
        clutterFilter.triggerBackgroundPoolWarmupIfNeeded()

        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Started Telegram Long-Polling controller")
            while (isActive && isPolling.get()) {
                try {
                    val response: TgUpdatesResponse = httpClient.get("${baseUrl()}/getUpdates") {
                        parameter("offset", lastUpdateOffset)
                        parameter("timeout", 25)
                    }.body()

                    if (response.ok) {
                        for (update in response.result) {
                            lastUpdateOffset = update.updateId + 1
                            processUpdate(scope, update)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Long-polling network retry in 4s: ${e.message}")
                    delay(4000L)
                }
            }
        }
    }

    fun stopPolling() {
        isPolling.set(false)
        pollingJob?.cancel()
        pollingJob = null
        isAutopilotEnabled.set(false)
        autopilotJob?.cancel()
        autopilotJob = null
    }

    private suspend fun processUpdate(scope: CoroutineScope, update: TgUpdate) {
        val incomingMsg = update.message ?: update.channelPost
        incomingMsg?.let { msg ->
            val chatId = msg.chat.id
            if (!isChatAuthorized(chatId)) {
                Log.w(TAG, "Ignored message from unauthorized chatId=$chatId")
                return
            }

            val text = msg.text?.trim() ?: return
            if (autopilotChatId == 0L) autopilotChatId = chatId

            when {
                // 1. Start / Help / Bottom Menu buttons
                text == "/start" || text == "/help" || text == "/menu" -> {
                    sendWelcomeMenu(chatId)
                }

                text == BTN_MENU_STATUS || text.startsWith("/status") -> {
                    sendNodeStatusCard(chatId)
                }

                text == BTN_MENU_HUNT -> {
                    sendQuickTopicPicker(chatId)
                }

                text == BTN_MENU_LINK -> {
                    sendMessage(
                        chatId = chatId,
                        text = "🎬 Просто вставь сюда любую ссылку на ролик (YouTube Shorts, YouTube, Reddit, Пикабу, TikTok) — я сразу скачаю его и пришлю 5-секундное превью с кнопками настройки!"
                    )
                }

                text == BTN_MENU_AUTO || text.startsWith("/autotags") -> {
                    handleAutotagsCommand(scope, chatId, text)
                }

                // 2. Direct URL anywhere in the message -> immediately process it!
                text.contains("http://") || text.contains("https://") ||
                    text.startsWith("/clip") || text.startsWith("/process") -> {
                    handleIncomingJobCommand(chatId, text)
                }

                // 3. Explicit /hunt command
                text.startsWith("/hunt") -> {
                    handleHuntCommand(chatId, text)
                }

                // 4. Plain text or hashtags (e.g. "cs2", "#юмор", "магическая битва") -> automatically treat as Tag Search!
                !text.startsWith("/") -> {
                    handleHuntCommand(chatId, text)
                }

                else -> {
                    sendWelcomeMenu(chatId)
                }
            }
        }

        update.callbackQuery?.let { cb ->
            answerCallbackQuery(cb.id)
            val chatId = cb.message?.chat?.id ?: return
            if (!isChatAuthorized(chatId)) return
            val data = cb.data ?: return
            handleInlineButtonCallback(scope, chatId, data)
        }
    }

    private suspend fun sendWelcomeMenu(chatId: Long) {
        sendMessage(
            chatId = chatId,
            text = "⚡ Пульт Pocket Media Forge готов!\n\n" +
                "Никаких сложных команд учить не нужно — работай прямо так:\n\n" +
                "1️⃣ Хочешь смонтировать конкретное видео?\n" +
                "👉 Просто кинь сюда ссылку (YouTube Shorts / Клип / Пикабу / Reddit).\n\n" +
                "2️⃣ Хочешь, чтобы я сам нашёл ролики по теме?\n" +
                "👉 Просто напиши в чат тему или теги (например: cs2, юмор, стендап, магическая битва) или нажми кнопку «🔎 Найти видео по теме» внизу экрана!"
        )
    }

    private suspend fun sendNodeStatusCard(chatId: Long) {
        val poolSize = clutterFilter.listVerifiedPoolClips().size
        val subs = topicHunter.listAutopilotSubscriptions()
        val svcState = PipelineForegroundService.statusFlow.value

        val text = "📊 Статус узла (OnePlus Snapdragon):\n\n" +
            "• Ваш Chat ID: $chatId\n" +
            "• Конвейер: $svcState\n" +
            "• Готовых залипалок в пуле (1080x960): $poolSize шт.\n" +
            "• Активных задач на сборке: ${sessions.size}\n" +
            "• Фоновый Автопилот: ${if (isAutopilotEnabled.get()) "🟢 ВКЛ (каждые $autopilotIntervalMinutes мин)" else "⏸ НА ПАУЗЕ"} (тем: ${subs.size})"

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🔄 Докачать залипалки в пул сейчас", "pool_warmup:0")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun sendQuickTopicPicker(chatId: Long) {
        val text = "🔎 Напиши в чат любую тему/теги (например: cs2, стендап, аниме эдит, физика) ИЛИ выбери готовую тему кнопкой ниже:"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🎮 CS2 / Киберспорт", "quick_hunt:cs2 moments shorts"),
                    InlineKeyboardButton("😂 Юмор / Стендап", "quick_hunt:стендап юмор нарезка")
                ),
                listOf(
                    InlineKeyboardButton("⚔️ Аниме Эдиты", "quick_hunt:anime edit 4k shorts"),
                    InlineKeyboardButton("🧪 Залипашки / Физика", "quick_hunt:oddly satisfying physics")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun handleHuntCommand(chatId: Long, rawText: String) {
        val queryRaw = rawText.removePrefix("/hunt").trim()
        if (queryRaw.isBlank()) {
            sendQuickTopicPicker(chatId)
            return
        }

        val huntId = UUID.randomUUID().toString().take(6)
        val state = parseHuntArguments(queryRaw)
        huntFilters[huntId] = state

        sendHuntConfiguratorMessage(chatId, huntId, state)
    }

    private fun parseHuntArguments(rawQuery: String): PendingHuntFilterState {
        val tokens = rawQuery.split(Regex("""\s+"""))
        var sort = ContentSortOrder.POPULAR
        var period = SearchTimePeriod.WEEK
        var platform = HuntPlatform.ALL
        var count = 3
        val tagWords = mutableListOf<String>()

        for (t in tokens) {
            val low = t.lowercase()
            when {
                low == "sort=new" || low == "new" -> sort = ContentSortOrder.NEWEST
                low == "sort=top" || low == "top" -> sort = ContentSortOrder.POPULAR
                low == "sort=hot" || low == "hot" -> sort = ContentSortOrder.VIRAL_VELOCITY
                low == "period=day" || low == "day" -> period = SearchTimePeriod.DAY
                low == "period=week" || low == "week" -> period = SearchTimePeriod.WEEK
                low == "period=month" || low == "month" -> period = SearchTimePeriod.MONTH
                low == "period=year" || low == "year" -> period = SearchTimePeriod.YEAR
                low == "period=all" || low == "all" -> period = SearchTimePeriod.ALL_TIME
                low == "yt" || low == "youtube" -> platform = HuntPlatform.YOUTUBE
                low == "reddit" -> platform = HuntPlatform.REDDIT
                low == "pikabu" || low == "пикабу" -> platform = HuntPlatform.PIKABU
                low.startsWith("count=") -> count = low.removePrefix("count=").toIntOrNull()?.coerceIn(1, 8) ?: 3
                else -> tagWords.add(t)
            }
        }

        val cleanQuery = tagWords.joinToString(" ").replace("#", "").trim().ifBlank {
            rawQuery.replace("#", "").trim()
        }
        return PendingHuntFilterState(
            queryTags = cleanQuery,
            platform = platform,
            sortOrder = sort,
            period = period,
            count = count
        )
    }

    private suspend fun sendHuntConfiguratorMessage(
        chatId: Long,
        huntId: String,
        state: PendingHuntFilterState
    ) {
        val text = buildString {
            append("🎯 Тема для поиска: «").append(state.queryTags).append("»\n\n")
            append("• Площадка: ").append(state.platform.label).append("\n")
            append("• Сортировка: ").append(state.sortOrder.label).append("\n")
            append("• Период: ").append(state.period.label).append("\n")
            append("• Сколько роликов найти: ").append(state.count).append(" шт.\n\n")
            append("Жми зелёную кнопку «🚀 Найти и собрать превью» (или переключи фильтры):")
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🚀 Найти и собрать превью (${state.count} шт.)", "hunt_go:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Сорт: ${state.sortOrder.label}", "hunt_sort:$huntId"),
                    InlineKeyboardButton("Период: ${state.period.label}", "hunt_period:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Источник: ${state.platform.label}", "hunt_plat:$huntId"),
                    InlineKeyboardButton("Кол-во: ${state.count} шт.", "hunt_cnt:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("🤖 Добавить эту тему в Автопилот", "hunt_save_auto:$huntId")
                )
            )
        )

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun executeTagHuntBatch(chatId: Long, state: PendingHuntFilterState) {
        sendMessage(
            chatId,
            "🔎 Ищу ${state.count} роликов по теме «${state.queryTags}» (${state.sortOrder.label})..."
        )

        enqueueHardwareTask("hunt", "Hunting videos for '${state.queryTags}'") {
            try {
                val candidates = topicHunter.huntByTags(
                    queryTags = state.queryTags,
                    platform = state.platform,
                    sortOrder = state.sortOrder,
                    period = state.period,
                    limitCount = state.count
                )

                if (candidates.isEmpty()) {
                    sendMessage(
                        chatId,
                        "⚠️ По запросу «${state.queryTags}» не удалось получить список видео. Проверь интернет/VPN на телефоне или попробуй другое слово."
                    )
                    return@enqueueHardwareTask
                }

                val listPreview = candidates.mapIndexed { i, c ->
                    "${i + 1}) ${c.title.take(55)} (${c.durationSec}с)"
                }.joinToString("\n")

                sendMessage(
                    chatId,
                    "✅ Найдено ${candidates.size} роликов:\n$listPreview\n\n⏳ Начинаю сборку 5-сек превью по очереди..."
                )

                for ((index, cand) in candidates.withIndex()) {
                    try {
                        sendMessage(
                            chatId,
                            "🎬 [${index + 1}/${candidates.size}] Собираю превью: «${cand.title.take(50)}»..."
                        )
                        ingestAndCreateInteractiveSession(
                            chatId = chatId,
                            url = cand.url,
                            initialConfig = null,
                            overrideTitle = cand.title,
                            overrideAuthor = cand.author
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed candidate ${cand.url}", e)
                        sendMessage(
                            chatId,
                            "⚠️ Пропущен ролик «${cand.title.take(40)}»: ${e.message?.take(120)}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hunt batch error", e)
                sendMessage(chatId, "❌ Ошибка поиска: ${e.message}")
            }
        }
    }

    private suspend fun handleAutotagsCommand(scope: CoroutineScope, chatId: Long, rawText: String) {
        val arg = rawText.removePrefix("/autotags").removePrefix(BTN_MENU_AUTO).trim()
        when {
            arg.startsWith("add ") -> {
                val queryPart = arg.removePrefix("add ").trim()
                val parsed = parseHuntArguments(queryPart)
                val sub = AutopilotTagSubscription(
                    queryTags = parsed.queryTags,
                    platform = parsed.platform,
                    sortOrder = parsed.sortOrder,
                    period = parsed.period
                )
                val all = topicHunter.addAutopilotSubscription(sub)
                startAutopilotLoopIfNeeded(scope, chatId)
                sendAutopilotDashboard(chatId, all)
            }

            arg.startsWith("del ") -> {
                val target = arg.removePrefix("del ").trim()
                val all = topicHunter.removeAutopilotSubscription(target)
                sendAutopilotDashboard(chatId, all)
            }

            else -> {
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            }
        }
    }

    private suspend fun sendAutopilotDashboard(
        chatId: Long,
        subs: List<AutopilotTagSubscription>
    ) {
        val statusStr = if (isAutopilotEnabled.get()) "🟢 АКТИВЕН (каждые $autopilotIntervalMinutes мин)" else "⏸ НА ПАУЗЕ"
        val sb = StringBuilder("🤖 Фоновый Автопилот по тегам: $statusStr\n\n")
        if (subs.isEmpty()) {
            sb.append("Список подписок пока пуст.\nПросто напиши любую тему в чат (например: cs2 или юмор) и нажми кнопку «🤖 Добавить эту тему в Автопилот»!")
        } else {
            subs.forEachIndexed { idx, s ->
                sb.append("${idx + 1}. «${s.queryTags}» [${s.platform.label} | ${s.sortOrder.label}]\n")
            }
        }

        val rows = mutableListOf(
            listOf(
                InlineKeyboardButton(
                    if (isAutopilotEnabled.get()) "⏸ Поставить на паузу" else "▶️ Включить Автопилот",
                    "auto_toggle:0"
                ),
                InlineKeyboardButton("⚡ Запустить проверку сейчас", "auto_run_now:0")
            )
        )
        if (subs.isNotEmpty()) {
            rows.add(
                listOf(
                    InlineKeyboardButton("🗑 Очистить последнюю тему (#${subs.size})", "auto_del:${subs.size}")
                )
            )
        }

        sendMessageWithKeyboard(chatId, sb.toString(), InlineKeyboardMarkup(rows))
    }

    private fun startAutopilotLoopIfNeeded(scope: CoroutineScope, chatId: Long) {
        autopilotChatId = chatId
        if (!isAutopilotEnabled.compareAndSet(false, true)) return

        autopilotJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Autopilot background tag hunter started")
            while (isActive && isAutopilotEnabled.get()) {
                runSingleAutopilotSweep(autopilotChatId)
                delay(autopilotIntervalMinutes * 60_000L)
            }
        }
    }

    private suspend fun runSingleAutopilotSweep(chatId: Long) {
        val subs = topicHunter.listAutopilotSubscriptions()
        if (subs.isEmpty()) return

        for (sub in subs) {
            enqueueHardwareTask("auto", "Autopilot hunting '${sub.queryTags}'") {
                try {
                    val found = topicHunter.huntByTags(
                        queryTags = sub.queryTags,
                        platform = sub.platform,
                        sortOrder = sub.sortOrder,
                        period = sub.period,
                        limitCount = 1
                    )
                    for (cand in found) {
                        ingestAndCreateInteractiveSession(
                            chatId = chatId,
                            url = cand.url,
                            initialConfig = null,
                            overrideTitle = cand.title,
                            overrideAuthor = cand.author
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Autopilot sweep failed for ${sub.queryTags}", e)
                }
            }
        }
    }

    private suspend fun handleIncomingJobCommand(chatId: Long, rawCommand: String) {
        val tokens = rawCommand.split(Regex("""\s+"""))
        val url = tokens.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            sendMessage(chatId, "❌ Не найдена ссылка. Просто вставь ссылку https://... или напиши тему для поиска!")
            return
        }

        val hasExplicitFlags = tokens.size > 1 && rawCommand.startsWith("/")
        val initialConfig = if (hasExplicitFlags) parseCommandFlagsToConfig(tokens) else null
        val jobId = UUID.randomUUID().toString().take(6)

        sendMessage(
            chatId = chatId,
            text = "⏳ Задача #$jobId принята! Начинаю скачивание и сборку 5-сек превью..."
        )

        enqueueHardwareTask(jobId, "Ingesting & building 5s preview") {
            try {
                ingestAndCreateInteractiveSession(
                    chatId = chatId,
                    url = url,
                    initialConfig = initialConfig,
                    fixedJobId = jobId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Job #$jobId failed", e)
                sendMessage(
                    chatId = chatId,
                    text = "❌ Задача #$jobId завершилась с ошибкой:\n${e.message ?: "Неизвестная ошибка при обработке видео"}\n\n" +
                        "💡 Если это YouTube, убедись, что на телефоне включён VPN."
                )
            }
        }
    }

    private suspend fun ingestAndCreateInteractiveSession(
        chatId: Long,
        url: String,
        initialConfig: ModularRenderConfig?,
        fixedJobId: String? = null,
        overrideTitle: String? = null,
        overrideAuthor: String? = null
    ) {
        val jobId = fixedJobId ?: UUID.randomUUID().toString().take(6)

        sendMessage(chatId, "📥 [#$jobId • Шаг 1/4] Скачиваю исходное видео...")
        val bundle = clutterFilter.downloadMediaWithMetadata(url, maxDurationSec = 75)
        val durationSec = hardwareEngine.probeDurationSeconds(bundle.videoFile)

        // Smart auto-layout: if input is already vertical 9:16 (like a YouTube Short), default to FULLSCREEN_EDIT
        // so it isn't squashed; if input is horizontal 16:9, default to SPLIT_VSTACK (50/50 split)!
        val isVertical = faceHelper.isVerticalSource(bundle.videoFile)
        val resolvedConfig = initialConfig ?: buildDefaultConfigWithSavedAdAssets(isVerticalInput = isVertical)

        sendMessage(chatId, "🎯 [#$jobId • Шаг 2/4] Анализирую кадр (ML Kit) и готовлю нижний фон...")
        val clutterFile = clutterFilter.buildShuffledClutterReel(
            targetDurationSec = durationSec,
            fallbackPrimaryVideo = bundle.videoFile
        )

        val framingPlan = faceHelper.computeFramingPlan(
            videoFile = bundle.videoFile,
            enableSmartFaceCrop = resolvedConfig.enableSmartFaceCrop
        )

        sendMessage(chatId, "💬 [#$jobId • Шаг 3/4] Готовлю субтитры...")
        val (subResult, words) = subtitleTransformer.prepareAssSubtitles(
            sourceUrl = url,
            localVideoFile = bundle.videoFile,
            config = resolvedConfig.subtitleLayer,
            framingPlan = framingPlan
        )

        val session = InteractiveJobSession(
            jobId = jobId,
            chatId = chatId,
            sourceUrl = url,
            originalTitle = overrideTitle?.ifBlank { bundle.originalTitle } ?: bundle.originalTitle,
            originalAuthor = overrideAuthor?.ifBlank { bundle.uploaderAuthor } ?: bundle.uploaderAuthor,
            sourceTags = bundle.sourceTags,
            primaryVideoFile = bundle.videoFile,
            clutterReelFile = clutterFile,
            framingPlan = framingPlan,
            cachedWords = words,
            latestAssFile = subResult.assFile,
            transcriptText = subResult.plainTranscriptText,
            config = resolvedConfig
        )
        sessions[jobId] = session

        sendMessage(chatId, "⚡ [#$jobId • Шаг 4/4] Рендерю 5-сек превью и отправляю в чат...")
        renderAndSendPreviewWithControls(session)
    }

    private suspend fun renderAndSendPreviewWithControls(session: InteractiveJobSession) {
        val previewFile = hardwareEngine.renderPipeline(
            primaryVideoFile = session.primaryVideoFile,
            clutterReelFile = session.clutterReelFile,
            framingPlan = session.framingPlan,
            assSubtitleFile = session.latestAssFile,
            config = session.config,
            isPreviewPass = true
        )

        val (_, resolvedSubY) = session.config.subtitleLayer.resolveCoordinates(session.framingPlan)
        val caption = buildString {
            append("🎬 Превью #").append(session.jobId).append(" (5 сек)\n")
            if (session.originalTitle.isNotBlank()) {
                append("📌 ").append(session.originalTitle.take(60))
                if (session.originalAuthor.isNotBlank()) {
                    append(" (").append(session.originalAuthor).append(")")
                }
                append("\n")
            }
            append("• Формат: ").append(session.config.layoutLayer.label).append("\n")
            append("• Центровка: ").append(session.framingPlan.summary).append("\n")
            append("• Субтитры: ").append(
                if (session.config.subtitleLayer.enabled) {
                    "ВКЛ (выс. Y=$resolvedSubY)"
                } else {
                    "ВЫКЛ"
                }
            ).append("\n")
            append("• Защита от Content ID: ").append(if (session.config.dspLayer.enabled) "ВКЛ" else "ВЫКЛ").append("\n")
            append("• Реклама: ").append(session.config.adLayer.mode).append("\n\n")
            append("👇 Настраивай слои кнопками ниже (превью пересоберётся) или жми «✅ Одобрить»:")
        }

        val keyboard = buildInteractiveLayerKeyboard(session)
        sendVideoWithKeyboard(
            chatId = session.chatId,
            videoFile = previewFile,
            caption = caption,
            keyboard = keyboard
        )
        previewFile.delete()
    }

    private fun buildInteractiveLayerKeyboard(session: InteractiveJobSession): InlineKeyboardMarkup {
        val id = session.jobId
        val cfg = session.config

        val layoutBtnText = "📐 Режим: ${cfg.layoutLayer.label.take(20)}"
        val faceCropBtnText = if (cfg.enableSmartFaceCrop) "🎯 Лицо ML: ВКЛ" else "🎯 Лицо ML: ВЫКЛ"

        val subsToggleText = if (cfg.subtitleLayer.enabled) "💬 Сабы: ВКЛ" else "💬 Сабы: ВЫКЛ"
        val subsDensityText = when (cfg.subtitleLayer.densityMode) {
            SubtitleDensityMode.PUNCH_3_TO_4_WORDS -> "Стиль: 3-4 слова"
            SubtitleDensityMode.CLASSIC_6_TO_8_WORDS -> "Стиль: 6-8 слов"
        }

        val subsZoneText = "📍 Поз: ${cfg.subtitleLayer.placementZone.label}"
        val dspText = if (cfg.dspLayer.enabled) "🛡 Анти-Детект: ВКЛ" else "🛡 Анти-Детект: ВЫКЛ"

        val adText = when (cfg.adLayer.mode) {
            AdInsertionMode.NONE -> "💰 Реклама: ВЫКЛ"
            AdInsertionMode.BANNER_OVERLAY -> "💰 Реклама: БАННЕР"
            AdInsertionMode.MIDROLL_SPLICE -> "💰 Реклама: ВРЕЗКА 3с"
            AdInsertionMode.MIDROLL_PIP_OVERLAY -> "💰 Реклама: PiP 3с"
            AdInsertionMode.PINNED_COMMENT_ONLY -> "💰 Реклама: В ЗАКРЕП"
        }

        val aiMetaText = if (cfg.enableAiMetadataGeneration) "🤖 AI-Описание: ВКЛ" else "🤖 AI-Описание: ВЫКЛ"

        return InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(layoutBtnText, "layout:$id"),
                    InlineKeyboardButton(faceCropBtnText, "face:$id")
                ),
                listOf(
                    InlineKeyboardButton(subsToggleText, "sub_on:$id"),
                    InlineKeyboardButton(subsDensityText, "sub_den:$id")
                ),
                listOf(
                    InlineKeyboardButton(subsZoneText, "sub_zone:$id"),
                    InlineKeyboardButton("⬆️ Сабы Выше", "sub_up:$id"),
                    InlineKeyboardButton("⬇️ Сабы Ниже", "sub_dn:$id")
                ),
                listOf(
                    InlineKeyboardButton(dspText, "dsp:$id"),
                    InlineKeyboardButton(adText, "ad_mode:$id")
                ),
                listOf(
                    InlineKeyboardButton(aiMetaText, "ai_meta:$id")
                ),
                listOf(
                    InlineKeyboardButton("✅ Одобрить (Готовый MP4)", "approve:$id"),
                    InlineKeyboardButton("📦 Скинуть исходники", "raw_pack:$id")
                ),
                listOf(
                    InlineKeyboardButton("🗑 В мусор", "reject:$id")
                )
            )
        )
    }

    private suspend fun handleInlineButtonCallback(
        scope: CoroutineScope,
        chatId: Long,
        data: String
    ) {
        val parts = data.split(":", limit = 2)
        if (parts.size < 2) return
        val action = parts[0]
        val targetId = parts[1]

        if (action == "pool_warmup") {
            clutterFilter.triggerBackgroundPoolWarmupIfNeeded()
            sendMessage(chatId, "🔄 Запущена фоновая докачка и проверка залипалок (1080x960) в локальный пул!")
            return
        }

        if (action == "quick_hunt") {
            handleHuntCommand(chatId, targetId)
            return
        }

        if (action.startsWith("hunt_")) {
            val filterState = huntFilters[targetId]
            if (filterState == null) {
                sendMessage(chatId, "⚠️ Сессия поиска истекла. Напиши тему заново.")
                return
            }
            when (action) {
                "hunt_sort" -> {
                    val values = ContentSortOrder.entries
                    filterState.sortOrder = values[(filterState.sortOrder.ordinal + 1) % values.size]
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_period" -> {
                    val values = SearchTimePeriod.entries
                    filterState.period = values[(filterState.period.ordinal + 1) % values.size]
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_plat" -> {
                    val values = HuntPlatform.entries
                    filterState.platform = values[(filterState.platform.ordinal + 1) % values.size]
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_cnt" -> {
                    filterState.count = when (filterState.count) {
                        1 -> 3
                        3 -> 5
                        else -> 1
                    }
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_save_auto" -> {
                    val sub = AutopilotTagSubscription(
                        queryTags = filterState.queryTags,
                        platform = filterState.platform,
                        sortOrder = filterState.sortOrder,
                        period = filterState.period
                    )
                    val all = topicHunter.addAutopilotSubscription(sub)
                    startAutopilotLoopIfNeeded(scope, chatId)
                    sendAutopilotDashboard(chatId, all)
                }
                "hunt_go" -> {
                    huntFilters.remove(targetId)
                    executeTagHuntBatch(chatId, filterState)
                }
            }
            return
        }

        if (action == "auto_toggle") {
            if (isAutopilotEnabled.get()) {
                isAutopilotEnabled.set(false)
                autopilotJob?.cancel()
                autopilotJob = null
            } else {
                startAutopilotLoopIfNeeded(scope, chatId)
            }
            sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            return
        }

        if (action == "auto_run_now") {
            sendMessage(chatId, "⚡ Запускаю внеочередной обход всех подписок Автопилота...")
            runSingleAutopilotSweep(chatId)
            return
        }

        if (action == "auto_del") {
            val all = topicHunter.removeAutopilotSubscription(targetId)
            sendAutopilotDashboard(chatId, all)
            return
        }

        val session = sessions[targetId]
        if (session == null) {
            sendMessage(chatId, "⚠️ Сессия #$targetId уже завершена или удалена.")
            return
        }

        when (action) {
            "reject" -> {
                cleanupSession(targetId)
                sendMessage(chatId, "🗑 Задача #$targetId удалена, временные файлы очищены.")
            }

            "approve" -> {
                sendMessage(
                    chatId,
                    "🚀 Одобрено! Собираю полный ролик #$targetId в максимальном качестве + генерирую 3 названия и описание..."
                )
                enqueueHardwareTask(targetId, "Rendering Final Master MP4") {
                    try {
                        renderFinalAndDeliver(session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Final render failed for #$targetId", e)
                        sendMessage(chatId, "❌ Ошибка финального рендера #$targetId: ${e.message}")
                    }
                }
            }

            "raw_pack" -> {
                sendMessage(
                    chatId,
                    "📦 Готовлю пакет исходников #$targetId (чистое видео, фон 1080x960, .ASS субтитры и .M4A звук)..."
                )
                enqueueHardwareTask(targetId, "Exporting Raw Source Pack") {
                    try {
                        exportAndDeliverRawSourcePack(session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Raw pack failed for #$targetId", e)
                        sendMessage(chatId, "❌ Ошибка выгрузки исходников #$targetId: ${e.message}")
                    }
                }
            }

            "layout" -> {
                val values = VideoLayoutLayer.entries
                val nextLayout = values[(session.config.layoutLayer.ordinal + 1) % values.size]
                session.config = session.config.copy(layoutLayer = nextLayout)
                rebuildPreviewAsync(session, "Переключаю режим на ${nextLayout.label}")
            }

            "face" -> {
                val nextFace = !session.config.enableSmartFaceCrop
                session.config = session.config.copy(enableSmartFaceCrop = nextFace)
                enqueueHardwareTask(targetId, "Recomputing face crop & 5s preview") {
                    try {
                        session.framingPlan = faceHelper.computeFramingPlan(
                            videoFile = session.primaryVideoFile,
                            enableSmartFaceCrop = nextFace
                        )
                        regenerateAssForSession(session)
                        renderAndSendPreviewWithControls(session)
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Ошибка пересборки превью: ${e.message}")
                    }
                }
            }

            "sub_on" -> {
                val nextSub = !session.config.subtitleLayer.enabled
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(enabled = nextSub)
                )
                rebuildPreviewAsync(session, "Переключаю субтитры")
            }

            "sub_den" -> {
                val nextDen = when (session.config.subtitleLayer.densityMode) {
                    SubtitleDensityMode.PUNCH_3_TO_4_WORDS -> SubtitleDensityMode.CLASSIC_6_TO_8_WORDS
                    SubtitleDensityMode.CLASSIC_6_TO_8_WORDS -> SubtitleDensityMode.PUNCH_3_TO_4_WORDS
                }
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(densityMode = nextDen)
                )
                rebuildPreviewWithAssRegenAsync(session, "Меняю плотность субтитров")
            }

            "sub_zone" -> {
                val zones = listOf(
                    SubtitlePlacementZone.ADAPTIVE_AUTO,
                    SubtitlePlacementZone.TOP_SAFE,
                    SubtitlePlacementZone.UPPER_THIRD,
                    SubtitlePlacementZone.CENTER_SEAM,
                    SubtitlePlacementZone.LOWER_BOX_MID,
                    SubtitlePlacementZone.BOTTOM_SAFE
                )
                val currentIdx = zones.indexOf(session.config.subtitleLayer.placementZone).coerceAtLeast(0)
                val nextZone = zones[(currentIdx + 1) % zones.size]
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(
                        placementZone = nextZone,
                        customY = nextZone.defaultY
                    )
                )
                rebuildPreviewWithAssRegenAsync(session, "Двигаю зону субтитров: ${nextZone.label}")
            }

            "sub_up" -> {
                val (_, curY) = session.config.subtitleLayer.resolveCoordinates(session.framingPlan)
                val newY = (curY - SUBTITLE_NUDGE_STEP_PX).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                rebuildPreviewWithAssRegenAsync(session, "Сдвигаю субтитры выше (Y=$newY)")
            }

            "sub_dn" -> {
                val (_, curY) = session.config.subtitleLayer.resolveCoordinates(session.framingPlan)
                val newY = (curY + SUBTITLE_NUDGE_STEP_PX).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                rebuildPreviewWithAssRegenAsync(session, "Сдвигаю субтитры ниже (Y=$newY)")
            }

            "dsp" -> {
                val nextDsp = !session.config.dspLayer.enabled
                session.config = session.config.copy(
                    dspLayer = session.config.dspLayer.copy(enabled = nextDsp)
                )
                rebuildPreviewAsync(session, "Переключаю защиту от Content ID")
            }

            "ad_mode" -> {
                val modes = AdInsertionMode.entries
                val nextMode = modes[(session.config.adLayer.mode.ordinal + 1) % modes.size]
                session.config = session.config.copy(
                    adLayer = session.config.adLayer.copy(
                        mode = nextMode,
                        bannerText = configStore.getDefaultBannerText(),
                        promoLinkForComment = configStore.getDefaultPromoUrl(),
                        midrollClipFile = configStore.getSavedMidrollFile()
                    )
                )
                rebuildPreviewAsync(session, "Переключаю режим рекламы: $nextMode")
            }

            "ai_meta" -> {
                val nextAi = !session.config.enableAiMetadataGeneration
                session.config = session.config.copy(enableAiMetadataGeneration = nextAi)
                rebuildPreviewAsync(session, "Переключаю генерацию AI-описания")
            }
        }
    }

    private fun rebuildPreviewAsync(session: InteractiveJobSession, stageLabel: String) {
        enqueueHardwareTask(session.jobId, stageLabel) {
            try {
                renderAndSendPreviewWithControls(session)
            } catch (e: Exception) {
                sendMessage(session.chatId, "❌ Ошибка пересборки превью: ${e.message}")
            }
        }
    }

    private fun rebuildPreviewWithAssRegenAsync(session: InteractiveJobSession, stageLabel: String) {
        enqueueHardwareTask(session.jobId, stageLabel) {
            try {
                regenerateAssForSession(session)
                renderAndSendPreviewWithControls(session)
            } catch (e: Exception) {
                sendMessage(session.chatId, "❌ Ошибка обновления субтитров: ${e.message}")
            }
        }
    }

    private suspend fun renderFinalAndDeliver(session: InteractiveJobSession) {
        val finalMp4 = hardwareEngine.renderPipeline(
            primaryVideoFile = session.primaryVideoFile,
            clutterReelFile = session.clutterReelFile,
            framingPlan = session.framingPlan,
            assSubtitleFile = session.latestAssFile,
            config = session.config,
            isPreviewPass = false
        )

        val phoneExportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketMediaForge/Job_${session.jobId}"
        ).apply { mkdirs() }
        val localSavedCopy = File(phoneExportDir, "final_${session.jobId}.mp4")
        finalMp4.copyTo(localSavedCopy, overwrite = true)

        val metadata = geminiClient.generateMetadataIfEnabled(
            enabled = session.config.enableAiMetadataGeneration,
            sourceUrl = session.sourceUrl,
            originalTitle = session.originalTitle,
            originalAuthor = session.originalAuthor,
            sourceTags = session.sourceTags,
            transcriptText = session.transcriptText,
            adConfig = session.config.adLayer
        )

        val caption = buildString {
            append("✅ Готовый рендер #").append(session.jobId).append(" (Без сжатия)\n")
            append("📁 На телефоне: Downloads/PocketMediaForge/Job_").append(session.jobId).append("\n\n")
            if (metadata != null) {
                append("🎯 3 варианта названия:\n")
                metadata.titles.forEachIndexed { idx, t ->
                    append("  ").append(idx + 1).append(") ").append(t).append("\n")
                }
                append("\n🎬 Источник / Тайтл: ").append(metadata.sourceAttribution).append("\n")
                append("📝 Описание: ").append(metadata.description).append("\n")
                append("🏷 Хэштеги: ").append(metadata.hashtags.joinToString(" ")).append("\n\n")
                append("📌 Закреплённый комментарий:\n").append(metadata.pinnedComment)
            }
        }

        sendUncompressedDocument(
            chatId = session.chatId,
            file = finalMp4,
            caption = caption
        )

        finalMp4.delete()
        cleanupSession(session.jobId)
    }

    private suspend fun exportAndDeliverRawSourcePack(session: InteractiveJobSession) {
        val rawExportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketMediaForge/Job_${session.jobId}_RAW"
        ).apply { mkdirs() }

        val rawPrimary = File(rawExportDir, "1_primary_source_${session.jobId}.mp4")
        session.primaryVideoFile.copyTo(rawPrimary, overwrite = true)

        val rawClutter = File(rawExportDir, "2_clutter_1080x960_${session.jobId}.mp4")
        session.clutterReelFile.copyTo(rawClutter, overwrite = true)

        val rawAudio = File(rawExportDir, "4_audio_48k_${session.jobId}.m4a")
        hardwareEngine.extractCleanAudioTrack(session.primaryVideoFile, rawAudio)

        var rawAss: File? = null
        session.latestAssFile?.let { ass ->
            if (ass.exists()) {
                rawAss = File(rawExportDir, "3_subtitles_${session.jobId}.ass")
                ass.copyTo(rawAss!!, overwrite = true)
            }
        }

        sendMessage(
            chatId = session.chatId,
            text = "📂 Все исходники для ручной сборки сохранены на телефоне в:\n" +
                "Downloads/PocketMediaForge/Job_${session.jobId}_RAW/\n" +
                "Выгружаю файлы без сжатия..."
        )

        sendUncompressedDocument(session.chatId, rawPrimary, "1️⃣ Исходник речи (Primary Video)")
        sendUncompressedDocument(session.chatId, rawClutter, "2️⃣ Проверенный монотонный фон (1080x960 @ 30fps)")
        rawAss?.let { sendUncompressedDocument(session.chatId, it, "3️⃣ Субтитры (.ASS Karaoke)") }
        if (rawAudio.exists()) {
            sendUncompressedDocument(session.chatId, rawAudio, "4️⃣ Чистая аудиодорожка (48kHz AAC)")
        }
    }

    private suspend fun regenerateAssForSession(session: InteractiveJobSession) {
        session.latestAssFile?.delete()
        val (subRes, _) = subtitleTransformer.prepareAssSubtitles(
            sourceUrl = session.sourceUrl,
            localVideoFile = session.primaryVideoFile,
            config = session.config.subtitleLayer,
            framingPlan = session.framingPlan,
            cachedWords = session.cachedWords
        )
        session.latestAssFile = subRes.assFile
    }

    private fun buildDefaultConfigWithSavedAdAssets(isVerticalInput: Boolean = false): ModularRenderConfig {
        val base = if (isVerticalInput) {
            // If source is already a 9:16 vertical Short, keep it full-screen by default (user can switch to Split 50/50 in 1 tap)
            ModularRenderConfig.presetFullscreenEdit().copy(
                subtitleLayer = forge.media.pocket.subtitles.SubtitleLayerConfig(enabled = true)
            )
        } else {
            ModularRenderConfig.presetPodcastSplit()
        }
        return base.copy(
            adLayer = AdLayerConfig(
                mode = AdInsertionMode.NONE,
                bannerText = configStore.getDefaultBannerText(),
                promoLinkForComment = configStore.getDefaultPromoUrl(),
                midrollClipFile = configStore.getSavedMidrollFile()
            )
        )
    }

    private fun parseCommandFlagsToConfig(tokens: List<String>): ModularRenderConfig {
        var config = buildDefaultConfigWithSavedAdAssets(isVerticalInput = false)

        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower == "preset=podcast" -> config = ModularRenderConfig.presetPodcastSplit()
                lower == "preset=edit" -> config = ModularRenderConfig.presetFullscreenEdit()
                lower == "preset=brainrot" -> config = ModularRenderConfig.presetBrainrotStory()
                lower == "preset=original" -> config = ModularRenderConfig.presetDistortedOriginal()

                lower == "subs=off" -> config = config.copy(
                    subtitleLayer = config.subtitleLayer.copy(enabled = false)
                )
                lower == "subs=on" -> config = config.copy(
                    subtitleLayer = config.subtitleLayer.copy(enabled = true)
                )
                lower == "substyle=classic" -> config = config.copy(
                    subtitleLayer = config.subtitleLayer.copy(densityMode = SubtitleDensityMode.CLASSIC_6_TO_8_WORDS)
                )
                lower == "substyle=punch" -> config = config.copy(
                    subtitleLayer = config.subtitleLayer.copy(densityMode = SubtitleDensityMode.PUNCH_3_TO_4_WORDS)
                )
                lower.startsWith("subpos=") -> {
                    val value = lower.removePrefix("subpos=")
                    val zone = when (value) {
                        "top" -> SubtitlePlacementZone.TOP_SAFE
                        "upper" -> SubtitlePlacementZone.UPPER_THIRD
                        "center" -> SubtitlePlacementZone.CENTER_SEAM
                        "lower" -> SubtitlePlacementZone.LOWER_BOX_MID
                        "bottom" -> SubtitlePlacementZone.BOTTOM_SAFE
                        else -> SubtitlePlacementZone.ADAPTIVE_AUTO
                    }
                    config = config.copy(
                        subtitleLayer = config.subtitleLayer.copy(
                            placementZone = zone,
                            customY = zone.defaultY
                        )
                    )
                }

                lower == "face=off" -> config = config.copy(enableSmartFaceCrop = false)
                lower == "face=on" -> config = config.copy(enableSmartFaceCrop = true)
                lower == "dsp=off" -> config = config.copy(
                    dspLayer = config.dspLayer.copy(enabled = false)
                )
                lower == "dsp=on" -> config = config.copy(
                    dspLayer = config.dspLayer.copy(enabled = true)
                )
                lower == "ai=off" -> config = config.copy(enableAiMetadataGeneration = false)
                lower == "ai=on" -> config = config.copy(enableAiMetadataGeneration = true)

                lower.startsWith("ad=") -> {
                    val mode = when (lower.removePrefix("ad=")) {
                        "banner" -> AdInsertionMode.BANNER_OVERLAY
                        "midroll" -> AdInsertionMode.MIDROLL_SPLICE
                        "pip" -> AdInsertionMode.MIDROLL_PIP_OVERLAY
                        "comment" -> AdInsertionMode.PINNED_COMMENT_ONLY
                        else -> AdInsertionMode.NONE
                    }
                    config = config.copy(adLayer = config.adLayer.copy(mode = mode))
                }
            }
        }
        return config
    }

    private fun cleanupSession(jobId: String) {
        sessions.remove(jobId)?.let { s ->
            s.primaryVideoFile.delete()
            s.latestAssFile?.delete()
        }
    }

    private suspend fun sendMessage(chatId: Long, text: String) {
        try {
            val markupJson = jsonFormat.encodeToString(persistentMainMenuKeyboard)
            httpClient.post("${baseUrl()}/sendMessage") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("reply_markup", markupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram message", e)
        }
    }

    private suspend fun sendMessageWithKeyboard(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) {
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("${baseUrl()}/sendMessage") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("reply_markup", markupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram message with keyboard", e)
        }
    }

    private suspend fun answerCallbackQuery(callbackQueryId: String) {
        try {
            httpClient.post("${baseUrl()}/answerCallbackQuery") {
                parameter("callback_query_id", callbackQueryId)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun sendVideoWithKeyboard(
        chatId: Long,
        videoFile: File,
        caption: String,
        keyboard: InlineKeyboardMarkup
    ) {
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("${baseUrl()}/sendVideo") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append("supports_streaming", "true")
                            append("reply_markup", markupJson)
                            append(
                                key = "video",
                                value = ChannelProvider(videoFile.length()) {
                                    videoFile.inputStream().toByteReadChannel()
                                },
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "video/mp4")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"${videoFile.name}\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload preview video to Telegram", e)
            sendMessage(chatId, "❌ Не удалось отправить видео-превью в Telegram: ${e.message}")
        }
    }

    private suspend fun sendUncompressedDocument(
        chatId: Long,
        file: File,
        caption: String
    ) {
        try {
            httpClient.post("${baseUrl()}/sendDocument") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append(
                                key = "document",
                                value = ChannelProvider(file.length()) {
                                    file.inputStream().toByteReadChannel()
                                },
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"${file.name}\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload uncompressed document to Telegram", e)
            sendMessage(chatId, "❌ Не удалось отправить файл ${file.name}: ${e.message}")
        }
    }
}
