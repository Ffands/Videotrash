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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    }

    private val botToken: String
        get() = configStore.getTelegramBotToken()

    private val sessions = ConcurrentHashMap<String, InteractiveJobSession>()
    private val huntFilters = ConcurrentHashMap<String, PendingHuntFilterState>()

    private var pollingJob: Job? = null
    private var autopilotJob: Job? = null
    private val isAutopilotEnabled = AtomicBoolean(false)
    @Volatile
    private var autopilotChatId: Long = 0L
    @Volatile
    private var autopilotIntervalMinutes: Long = 60L

    private var lastUpdateId: Long = 0L

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonFormat)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 20_000L
            socketTimeoutMillis = 120_000L
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

    fun startPolling(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Started Telegram Long-Polling loop")
            while (isActive) {
                val activeToken = botToken
                if (activeToken.isBlank()) {
                    delay(5000L)
                    continue
                }
                try {
                    val response: TgUpdatesResponse = httpClient.get(
                        "https://api.telegram.org/bot$activeToken/getUpdates"
                    ) {
                        parameter("offset", lastUpdateId + 1)
                        parameter("timeout", 25)
                    }.body()

                    if (response.ok) {
                        for (update in response.result) {
                            lastUpdateId = maxOf(lastUpdateId, update.updateId)
                            handleUpdate(scope, update)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Telegram long-poll cycle warning: ${e.message}")
                    delay(3000L)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        autopilotJob?.cancel()
        pollingJob = null
        autopilotJob = null
    }

    private fun isChatAuthorized(chatId: Long): Boolean {
        val allowedRaw = configStore.getAllowedChatId().trim()
        if (allowedRaw.isEmpty()) return true
        val allowedLong = allowedRaw.toLongOrNull() ?: return true
        return chatId == allowedLong
    }

    private suspend fun handleUpdate(scope: CoroutineScope, update: TgUpdate) {
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
                text.startsWith("/status") -> {
                    val poolSize = clutterFilter.listVerifiedPoolClips().size
                    val subs = topicHunter.listAutopilotSubscriptions()
                    val svcState = PipelineForegroundService.statusFlow.value
                    sendMessage(
                        chatId = chatId,
                        text = "⚡ Pocket Media Forge (Snapdragon ARM64)
" +
                            "• Ваш Chat ID: $chatId
" +
                            "• Статус: $svcState
" +
                            "• Проверенных фонов в пуле (3.3A): $poolSize шт.
" +
                            "• Активных сессий: ${sessions.size}
" +
                            "• Фоновый Автопилот по тегам: ${if (isAutopilotEnabled.get()) "ВКЛ (каждые $autopilotIntervalMinutes мин)" else "ПАУЗА"} (подписок: ${subs.size})"
                    )
                }

                text.startsWith("/hunt") -> {
                    handleHuntCommand(chatId, text)
                }

                text.startsWith("/autotags") -> {
                    handleAutotagsCommand(scope, chatId, text)
                }

                text.startsWith("/clip") || text.startsWith("/process") || text.startsWith("http") -> {
                    handleIncomingJobCommand(chatId, text)
                }

                else -> {
                    sendMessage(
                        chatId = chatId,
                        text = "🛠 Команды Pocket Media Forge:

" +
                            "1️⃣ Поиск видео по твоим тегам и тематикам:
" +
                            "• /hunt <теги/тема> — откроет пульт выбора сортировки (Популярное / Новизна / Взлетающее) и периода (24ч / Неделя / Месяц / Всё время)

" +
                            "2️⃣ Фоновый Автопилот по тегам:
" +
                            "• /autotags add <теги> [sort=top|new|hot] [period=day|week|month]
" +
                            "• /autotags list — список подписок и пульт Автопилота
" +
                            "• /autotags del <номер>

" +
                            "3️⃣ Сборка по прямой ссылке:
" +
                            "• /process <url> [preset=podcast|edit|brainrot|original]
" +
                            "• /status"
                    )
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

    private suspend fun handleHuntCommand(chatId: Long, rawText: String) {
        val queryRaw = rawText.removePrefix("/hunt").trim()
        if (queryRaw.isBlank()) {
            sendMessage(
                chatId,
                "🔎 Укажи теги или тематику после /hunt.
Примеры:
" +
                    "• /hunt аниме эдит магическая битва
" +
                    "• /hunt стендап отношения
" +
                    "• /hunt пикабу физика залипашка"
            )
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

        val cleanQuery = tagWords.joinToString(" ").ifBlank { rawQuery }
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
            append("🎯 Поиск контента по тегам: «").append(state.queryTags).append("»

")
            append("• Площадка: ").append(state.platform.label).append("
")
            append("• Сортировка: ").append(state.sortOrder.label).append("
")
            append("• Период: ").append(state.period.label).append("
")
            append("• Количество роликов: ").append(state.count).append(" шт.

")
            append("Настрой фильтры кнопками ниже или сразу жми «🚀 Найти и собрать превью»:")
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🚀 Найти и собрать превью (${state.count} шт.)", "hunt_go:$huntId"),
                    InlineKeyboardButton("🤖 В Автопилот", "hunt_save_auto:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Сорт: ${state.sortOrder.label.take(18)}", "hunt_sort:$huntId"),
                    InlineKeyboardButton("Период: ${state.period.label}", "hunt_period:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Источник: ${state.platform.label}", "hunt_plat:$huntId"),
                    InlineKeyboardButton("Кол-во: ${state.count} шт.", "hunt_cnt:$huntId")
                )
            )
        )

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun executeTagHuntBatch(chatId: Long, state: PendingHuntFilterState) {
        sendMessage(
            chatId,
            "🔎 Ищу ${state.count} небаянистых видео по теме «${state.queryTags}» (${state.sortOrder.label}, ${state.period.label})..."
        )

        enqueueHardwareTask("hunt", "Hunting videos for '${state.queryTags}'") {
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
                    "⚠️ По запросу «${state.queryTags}» за период «${state.period.label}» новых видео (10–75 сек) не найдено. Попробуй расширить период (За месяц / За всё время)."
                )
                return@enqueueHardwareTask
            }

            sendMessage(
                chatId,
                "✅ Найдено ${candidates.size} роликов! Начинаю конвейерную сборку 5-сек превью для каждого..."
            )

            for (cand in candidates) {
                try {
                    ingestAndCreateInteractiveSession(
                        chatId = chatId,
                        url = cand.url,
                        initialConfig = buildDefaultConfigWithSavedAdAssets(),
                        overrideTitle = cand.title,
                        overrideAuthor = cand.author
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed candidate ${cand.url}", e)
                }
            }
        }
    }

    private suspend fun handleAutotagsCommand(scope: CoroutineScope, chatId: Long, rawText: String) {
        val arg = rawText.removePrefix("/autotags").trim()
        when {
            arg.startsWith("add ") -> {
                val queryPart = arg.removePrefix("add ").trim()
                val parsed = parseHuntArguments(queryPart)
                val updated = topicHunter.addAutopilotSubscription(
                    AutopilotTagSubscription(
                        queryTags = parsed.queryTags,
                        platform = parsed.platform,
                        sortOrder = parsed.sortOrder,
                        period = parsed.period
                    )
                )
                sendAutopilotDashboard(chatId, updated)
            }

            arg.startsWith("del ") -> {
                val target = arg.removePrefix("del ").trim()
                val updated = topicHunter.removeAutopilotSubscription(target)
                sendAutopilotDashboard(chatId, updated)
            }

            arg == "on" -> {
                startAutopilotLoopIfNeeded(scope, chatId)
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
            }

            arg == "off" -> {
                isAutopilotEnabled.set(false)
                autopilotJob?.cancel()
                sendAutopilotDashboard(chatId, topicHunter.listAutopilotSubscriptions())
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
        val sb = StringBuilder("🤖 Фоновый Автопилот по тегам: $statusStr

")
        if (subs.isEmpty()) {
            sb.append("Список тегов пуст. Добавь через:
/autotags add аниме эдит sort=top period=week")
        } else {
            subs.forEachIndexed { idx, s ->
                sb.append("${idx + 1}. «${s.queryTags}» [${s.platform.name} | ${s.sortOrder.name} | ${s.period.label}]
")
            }
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(
                        if (isAutopilotEnabled.get()) "⏸ Поставить Автопилот на паузу" else "▶️ Включить Автопилот",
                        "auto_toggle:0"
                    ),
                    InlineKeyboardButton("⚡ Проверить все теги сейчас", "auto_run_now:0")
                )
            )
        )
        sendMessageWithKeyboard(chatId, sb.toString(), keyboard)
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
                        initialConfig = buildDefaultConfigWithSavedAdAssets(),
                        overrideTitle = cand.title,
                        overrideAuthor = cand.author
                    )
                }
            }
        }
    }

    private suspend fun handleIncomingJobCommand(chatId: Long, rawCommand: String) {
        val tokens = rawCommand.split(Regex("""\s+"""))
        val url = tokens.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            sendMessage(chatId, "❌ Не найдена ссылка. Используй /process <url> или /hunt <теги>.")
            return
        }

        val initialConfig = parseCommandFlagsToConfig(tokens)
        val jobId = UUID.randomUUID().toString().take(6)

        sendMessage(
            chatId = chatId,
            text = "⏳ Задача #$jobId в очереди Snapdragon MediaCodec.
" +
                "Качаю исходник, проверяю фон (3.3A), центрирую лицо (ML Kit) и собираю 5-сек превью..."
        )

        enqueueHardwareTask(jobId, "Ingesting & building 5s preview") {
            ingestAndCreateInteractiveSession(
                chatId = chatId,
                url = url,
                initialConfig = initialConfig,
                fixedJobId = jobId
            )
        }
    }

    private suspend fun ingestAndCreateInteractiveSession(
        chatId: Long,
        url: String,
        initialConfig: ModularRenderConfig,
        fixedJobId: String? = null,
        overrideTitle: String? = null,
        overrideAuthor: String? = null
    ) {
        val jobId = fixedJobId ?: UUID.randomUUID().toString().take(6)
        val bundle = clutterFilter.downloadMediaWithMetadata(url, maxDurationSec = 75)
        val durationSec = hardwareEngine.probeDurationSeconds(bundle.videoFile)
        val clutterFile = clutterFilter.buildShuffledClutterReel(durationSec)

        val framingPlan = faceHelper.computeFramingPlan(
            videoFile = bundle.videoFile,
            enableSmartFaceCrop = initialConfig.enableSmartFaceCrop
        )

        val (subResult, words) = subtitleTransformer.prepareAssSubtitles(
            sourceUrl = url,
            localVideoFile = bundle.videoFile,
            config = initialConfig.subtitleLayer,
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
            config = initialConfig
        )
        sessions[jobId] = session

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
            append("🎬 Превью #").append(session.jobId).append(" (5 сек)
")
            if (session.originalTitle.isNotBlank()) {
                append("📌 Исходник: ").append(session.originalTitle.take(60))
                if (session.originalAuthor.isNotBlank()) {
                    append(" (").append(session.originalAuthor).append(")")
                }
                append("
")
            }
            append("• Режим: ").append(session.config.layoutLayer.label).append("
")
            append("• Кроп лица: ").append(session.framingPlan.summary).append("
")
            append("• Субтитры: ").append(
                if (session.config.subtitleLayer.enabled) {
                    "ВКЛ (${session.config.subtitleLayer.placementZone.label}, Y=$resolvedSubY)"
                } else {
                    "ВЫКЛ"
                }
            ).append("
")
            append("• Обход Content ID (Зум+Питч+Паузы): ").append(if (session.config.dspLayer.enabled) "ВКЛ" else "ВЫКЛ").append("
")
            append("• Реклама: ").append(session.config.adLayer.mode).append("
")
            append("• AI-Описание (3 названия + тайтл): ").append(if (session.config.enableAiMetadataGeneration) "ВКЛ" else "ВЫКЛ")
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
        val (_, subY) = cfg.subtitleLayer.resolveCoordinates(session.framingPlan)

        val subToggleLabel = if (cfg.subtitleLayer.enabled) "💬 Сабы: ВКЛ (Y=$subY)" else "💬 Сабы: ВЫКЛ"
        val subStyleLabel = if (cfg.subtitleLayer.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
            "🔤 Стиль: Панч 3-4 сл."
        } else {
            "🔤 Стиль: Строка 6-8 сл."
        }
        val cropLabel = if (cfg.enableSmartFaceCrop) "👤 Кроп: Авто ML" else "👤 Кроп: ВЫКЛ (16:9)"
        val layoutLabel = "📐 Режим: ${cfg.layoutLayer.name.take(12)}"
        val dspLabel = if (cfg.dspLayer.enabled) "🛡 Анти-ID: ВКЛ" else "🛡 Анти-ID: ВЫКЛ"
        val adLabel = "📢 Реклама: ${cfg.adLayer.mode.name.take(10)}"
        val aiMetaLabel = if (cfg.enableAiMetadataGeneration) "📝 AI (3 назв.): ВКЛ" else "📝 AI: ВЫКЛ"

        return InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("✅ Одобрить (MP4 без сжатия)", "approve:$id"),
                    InlineKeyboardButton("📦 Скинуть все исходники", "rawpack:$id")
                ),
                listOf(
                    InlineKeyboardButton(subToggleLabel, "toggle_sub:$id"),
                    InlineKeyboardButton(subStyleLabel, "cycle_sub_style:$id")
                ),
                listOf(
                    InlineKeyboardButton("⬆️ Сабы выше (-120px)", "sub_y_up:$id"),
                    InlineKeyboardButton("⬇️ Сабы ниже (+120px)", "sub_y_down:$id"),
                    InlineKeyboardButton("📍 Зона", "cycle_sub_zone:$id")
                ),
                listOf(
                    InlineKeyboardButton(cropLabel, "toggle_crop:$id"),
                    InlineKeyboardButton(layoutLabel, "cycle_layout:$id")
                ),
                listOf(
                    InlineKeyboardButton(dspLabel, "toggle_dsp:$id"),
                    InlineKeyboardButton(adLabel, "cycle_ad:$id"),
                    InlineKeyboardButton(aiMetaLabel, "toggle_ai:$id")
                ),
                listOf(
                    InlineKeyboardButton("🔄 Обновить 5с превью", "repreview:$id"),
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
        val parts = data.split(":")
        if (parts.size != 2) return
        val action = parts[0]
        val targetId = parts[1]

        if (action.startsWith("hunt_")) {
            val state = huntFilters[targetId] ?: return
            when (action) {
                "hunt_sort" -> {
                    val orders = ContentSortOrder.entries
                    state.sortOrder = orders[(state.sortOrder.ordinal + 1) % orders.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_period" -> {
                    val periods = SearchTimePeriod.entries
                    state.period = periods[(state.period.ordinal + 1) % periods.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_plat" -> {
                    val plats = HuntPlatform.entries
                    state.platform = plats[(state.platform.ordinal + 1) % plats.size]
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_cnt" -> {
                    state.count = if (state.count >= 5) 1 else state.count + 2
                    sendHuntConfiguratorMessage(chatId, targetId, state)
                }
                "hunt_save_auto" -> {
                    val subs = topicHunter.addAutopilotSubscription(
                        AutopilotTagSubscription(
                            queryTags = state.queryTags,
                            platform = state.platform,
                            sortOrder = state.sortOrder,
                            period = state.period
                        )
                    )
                    sendAutopilotDashboard(chatId, subs)
                }
                "hunt_go" -> {
                    huntFilters.remove(targetId)
                    executeTagHuntBatch(chatId, state)
                }
            }
            return
        }

        if (action == "auto_toggle") {
            if (isAutopilotEnabled.get()) {
                isAutopilotEnabled.set(false)
                autopilotJob?.cancel()
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

        val session = sessions[targetId]
        if (session == null) {
            sendMessage(chatId, "⚠️ Сессия #$targetId уже закрыта или очищена.")
            return
        }

        when (action) {
            "toggle_sub" -> {
                val cur = session.config.subtitleLayer
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(enabled = !cur.enabled)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "💬 Субтитры: ${if (session.config.subtitleLayer.enabled) "ВКЛ" else "ВЫКЛ"}")
            }

            "cycle_sub_style" -> {
                val cur = session.config.subtitleLayer
                val nextMode = if (cur.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
                    SubtitleDensityMode.CLASSIC_6_TO_8_WORDS
                } else {
                    SubtitleDensityMode.PUNCH_3_TO_4_WORDS
                }
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(densityMode = nextMode)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "🔤 Стиль субтитров: $nextMode")
            }

            "sub_y_up" -> {
                val cur = session.config.subtitleLayer
                val (_, currentY) = cur.resolveCoordinates(session.framingPlan)
                val newY = (currentY - 120).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "⬆️ Блок субтитров сдвинут вверх: Y=$newY px")
            }

            "sub_y_down" -> {
                val cur = session.config.subtitleLayer
                val (_, currentY) = cur.resolveCoordinates(session.framingPlan)
                val newY = (currentY + 120).coerceIn(140, 1780)
                session.config = session.config.copy(
                    subtitleLayer = cur.copy(
                        placementZone = SubtitlePlacementZone.CUSTOM_XY,
                        customY = newY
                    )
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "⬇️ Блок субтитров сдвинут вниз: Y=$newY px")
            }

            "cycle_sub_zone" -> {
                val zones = SubtitlePlacementZone.entries
                val curZone = session.config.subtitleLayer.placementZone
                val nextZone = zones[(curZone.ordinal + 1) % zones.size]
                session.config = session.config.copy(
                    subtitleLayer = session.config.subtitleLayer.copy(placementZone = nextZone)
                )
                regenerateAssForSession(session)
                sendMessage(chatId, "📍 Зона субтитров: ${nextZone.label}")
            }

            "toggle_crop" -> {
                val nextCrop = !session.config.enableSmartFaceCrop
                session.config = session.config.copy(enableSmartFaceCrop = nextCrop)
                enqueueHardwareTask(targetId, "Recomputing face crop") {
                    session.framingPlan = faceHelper.computeFramingPlan(
                        videoFile = session.primaryVideoFile,
                        enableSmartFaceCrop = nextCrop
                    )
                    regenerateAssForSession(session)
                    sendMessage(chatId, "👤 Кроп лица: ${session.framingPlan.summary}")
                }
            }

            "cycle_layout" -> {
                val layouts = VideoLayoutLayer.entries
                val nextLayout = layouts[(session.config.layoutLayer.ordinal + 1) % layouts.size]
                session.config = session.config.copy(layoutLayer = nextLayout)
                sendMessage(chatId, "📐 Режим сборки: ${nextLayout.label}")
            }

            "toggle_dsp" -> {
                val nextDsp = !session.config.dspLayer.enabled
                session.config = session.config.copy(
                    dspLayer = session.config.dspLayer.copy(enabled = nextDsp)
                )
                sendMessage(chatId, "🛡 Обход Content ID (Зум+Питч+Паузы): ${if (nextDsp) "ВКЛ" else "ВЫКЛ"}")
            }

            "cycle_ad" -> {
                val modes = AdInsertionMode.entries
                val nextAd = modes[(session.config.adLayer.mode.ordinal + 1) % modes.size]
                session.config = session.config.copy(
                    adLayer = session.config.adLayer.copy(mode = nextAd)
                )
                sendMessage(chatId, "📢 Режим рекламы: $nextAd")
            }

            "toggle_ai" -> {
                val nextAi = !session.config.enableAiMetadataGeneration
                session.config = session.config.copy(enableAiMetadataGeneration = nextAi)
                sendMessage(chatId, "📝 Генерация AI-описания (3 названия + тайтл): ${if (nextAi) "ВКЛ" else "ВЫКЛ"}")
            }

            "repreview" -> {
                sendMessage(chatId, "🔄 Пересобираю 5-сек превью для #$targetId...")
                enqueueHardwareTask(targetId, "Re-rendering 5s preview") {
                    renderAndSendPreviewWithControls(session)
                }
            }

            "approve" -> {
                sendMessage(chatId, "🚀 Запущен полный рендер #$targetId на Snapdragon (h264_mediacodec)...")
                enqueueHardwareTask(targetId, "Full hardware render") {
                    executeFullRenderAndDeliver(session)
                }
            }

            "rawpack" -> {
                sendMessage(
                    chatId,
                    "📦 Выгружаю все раздельные исходники по задаче #$targetId (в память телефона Downloads и сюда в чат)..."
                )
                enqueueHardwareTask(targetId, "Exporting raw source pack") {
                    exportAndDeliverRawSourcePack(session)
                }
            }

            "reject" -> {
                cleanupSession(targetId)
                sendMessage(chatId, "🗑 Задача #$targetId отправлена в мусор, кэш очищен.")
            }
        }
    }

    private suspend fun executeFullRenderAndDeliver(session: InteractiveJobSession) {
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
            append("✅ Готовый рендер #").append(session.jobId).append(" (Без сжатия)
")
            append("📁 На телефоне: Downloads/PocketMediaForge/Job_").append(session.jobId).append("

")
            if (metadata != null) {
                append("🎯 3 варианта названия:
")
                metadata.titles.forEachIndexed { idx, t ->
                    append("  ").append(idx + 1).append(") ").append(t).append("
")
                }
                append("
🎬 Источник / Тайтл: ").append(metadata.sourceAttribution).append("
")
                append("📝 Описание: ").append(metadata.description).append("
")
                append("🏷 Хэштеги: ").append(metadata.hashtags.joinToString(" ")).append("

")
                append("📌 Закреплённый комментарий:
").append(metadata.pinnedComment)
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
            text = "📂 Все исходники для ручной сборки сохранены на телефоне в:
" +
                "Downloads/PocketMediaForge/Job_${session.jobId}_RAW/
" +
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

    private fun buildDefaultConfigWithSavedAdAssets(): ModularRenderConfig {
        val bannerText = configStore.getCustomBannerText()
        val promoUrl = configStore.getSponsorPromoUrl()
        val midrollFile = configStore.getMidrollVideoFile()
        return ModularRenderConfig(
            adLayer = AdLayerConfig(
                mode = AdInsertionMode.NONE,
                bannerText = bannerText,
                midrollClipFile = midrollFile,
                sponsorTrackingUrl = promoUrl
            )
        )
    }

    private fun parseCommandFlagsToConfig(tokens: List<String>): ModularRenderConfig {
        var config = buildDefaultConfigWithSavedAdAssets()
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower == "preset=edit" || lower == "layout=full" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.FULLSCREEN_EDIT)
                lower == "preset=brainrot" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.BRAINROT_VOICEOVER)
                lower == "preset=original" ->
                    config = config.copy(layoutLayer = VideoLayoutLayer.DISTORTED_ORIGINAL)
                lower == "ad=banner" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.BANNER_OVERLAY))
                lower == "ad=midroll" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.MIDROLL_SPLICE))
                lower == "ad=comment" ->
                    config = config.copy(adLayer = config.adLayer.copy(mode = AdInsertionMode.PINNED_COMMENT_ONLY))
                lower == "sub=off" ->
                    config = config.copy(subtitleLayer = config.subtitleLayer.copy(enabled = false))
                lower == "crop=off" ->
                    config = config.copy(enableSmartFaceCrop = false)
                lower == "dsp=off" ->
                    config = config.copy(dspLayer = config.dspLayer.copy(enabled = false))
                lower == "ai=off" ->
                    config = config.copy(enableAiMetadataGeneration = false)
                lower.startsWith("sub_y=") -> {
                    val yVal = lower.removePrefix("sub_y=").toIntOrNull()
                    if (yVal != null) {
                        config = config.copy(
                            subtitleLayer = config.subtitleLayer.copy(
                                placementZone = SubtitlePlacementZone.CUSTOM_XY,
                                customY = yVal
                            )
                        )
                    }
                }
            }
        }
        return config
    }

    private fun cleanupSession(jobId: String) {
        val removed = sessions.remove(jobId) ?: return
        removed.primaryVideoFile.delete()
        removed.latestAssFile?.delete()
    }

    private suspend fun sendMessage(chatId: Long, text: String) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot$activeToken/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram message", e)
        }
    }

    private suspend fun sendMessageWithKeyboard(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("https://api.telegram.org/bot$activeToken/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("reply_markup", markupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message with keyboard", e)
        }
    }

    private suspend fun answerCallbackQuery(callbackQueryId: String) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot$activeToken/answerCallbackQuery") {
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
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            httpClient.post("https://api.telegram.org/bot$activeToken/sendVideo") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append("reply_markup", markupJson)
                            append(
                                key = "video",
                                value = videoFile.readBytes(),
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
            Log.e(TAG, "Failed to upload preview video with keyboard", e)
        }
    }

    private suspend fun sendUncompressedDocument(
        chatId: Long,
        file: File,
        caption: String
    ) = withContext(Dispatchers.IO) {
        val activeToken = botToken
        if (activeToken.isBlank()) return@withContext
        try {
            httpClient.post("https://api.telegram.org/bot$activeToken/sendDocument") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", caption.take(1020))
                            append(
                                key = "document",
                                value = file.readBytes(),
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
            Log.e(TAG, "Failed to upload uncompressed document ${file.name}", e)
        }
    }
}
