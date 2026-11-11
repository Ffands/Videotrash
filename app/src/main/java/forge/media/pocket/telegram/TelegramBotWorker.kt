package forge.media.pocket.telegram

import android.content.Context
import android.util.Log
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.clutter.StudioInboxItem
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.ContentSortOrder
import forge.media.pocket.discovery.DiscoveredMediaCandidate
import forge.media.pocket.discovery.DiscoveredSpamChannel
import forge.media.pocket.discovery.HuntPlatform
import forge.media.pocket.discovery.SearchDurationMode
import forge.media.pocket.discovery.SearchLanguageMode
import forge.media.pocket.discovery.SearchTimePeriod
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.vision.FaceCenteringHelper
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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.copyTo
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

data class PendingHuntFilterState(
    val queryTags: String,
    var platform: HuntPlatform = HuntPlatform.YOUTUBE,
    var sortOrder: ContentSortOrder = ContentSortOrder.RELEVANCE,
    var period: SearchTimePeriod = SearchTimePeriod.MONTH,
    var durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
    var languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
    var count: Int = 6
)

data class PendingDirectUrlState(
    val url: String
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

        private const val BTN_MENU_HUNT = "🧠 Умный поиск по теме"
        private const val BTN_MENU_SPAM_FARMS = "📡 Спам-каналы / Фермы"
        private const val BTN_MENU_LINK = "📥 Скачать по ссылке (1080p)"
        private const val BTN_MENU_INBOX = "📂 Мои исходники в Студии"
        private const val BTN_MENU_STATUS = "📊 Статус и Фон"
        private const val BTN_MENU_LOG = "📜 Полный лог"
    }

    private val isPolling = AtomicBoolean(false)
    private var pollingJob: Job? = null
    private var lastUpdateOffset: Long = 0L

    private val huntFilters = ConcurrentHashMap<String, PendingHuntFilterState>()
    private val discoveredBatches = ConcurrentHashMap<String, List<DiscoveredMediaCandidate>>()
    private val discoveredSpamBatches = ConcurrentHashMap<String, List<DiscoveredSpamChannel>>()
    private val batchTopics = ConcurrentHashMap<String, String>()
    private val downloadedInboxItems = ConcurrentHashMap<String, StudioInboxItem>()
    private val pendingLinks = ConcurrentHashMap<String, PendingDirectUrlState>()

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
        val channelPost: TgMessage? = null,
        @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
    )

    @Serializable
    private data class TgMessage(
        @SerialName("message_id") val messageId: Long,
        val chat: TgChat,
        val text: String? = null,
        val document: TgDocument? = null
    )

    @Serializable
    private data class TgDocument(
        @SerialName("file_id") val fileId: String,
        @SerialName("file_name") val fileName: String? = null
    )

    @Serializable
    private data class TgGetFileResponse(
        val ok: Boolean = false,
        val result: TgFilePathObj? = null
    )

    @Serializable
    private data class TgFilePathObj(
        @SerialName("file_path") val filePath: String? = null
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
            listOf(KeyboardButton(BTN_MENU_HUNT), KeyboardButton(BTN_MENU_SPAM_FARMS)),
            listOf(KeyboardButton(BTN_MENU_LINK), KeyboardButton(BTN_MENU_INBOX)),
            listOf(KeyboardButton(BTN_MENU_STATUS), KeyboardButton(BTN_MENU_LOG))
        ),
        resizeKeyboard = true,
        isPersistent = true
    )

    private fun baseUrl(): String =
        "https://api.telegram.org/bot${configStore.getTelegramBotToken()}"

    private fun isChatAuthorized(chatId: Long): Boolean {
        val allowed = configStore.getAllowedChatIdLong()
        return allowed == 0L || allowed == chatId
    }

    fun startPolling(scope: CoroutineScope) {
        if (configStore.getTelegramBotToken().isBlank()) {
            DiagnosticLogger.log("TELEGRAM", "TELEGRAM_BOT_TOKEN пуст — поллинг не запущен.")
            return
        }
        if (!isPolling.compareAndSet(false, true)) return

        clutterFilter.triggerBackgroundPoolWarmupIfNeeded()

        pollingJob = scope.launch(Dispatchers.IO) {
            DiagnosticLogger.log("TELEGRAM", "Запущен цикл Long-Polling Telegram API")
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
    }

    private suspend fun processUpdate(scope: CoroutineScope, update: TgUpdate) {
        val incomingMsg = update.message ?: update.channelPost
        incomingMsg?.let { msg ->
            val chatId = msg.chat.id
            if (!isChatAuthorized(chatId)) {
                DiagnosticLogger.log("TELEGRAM", "Игнорировано сообщение от чужого chatId=$chatId")
                return
            }

            msg.document?.let { doc ->
                val fName = doc.fileName ?: "file"
                if (fName.endsWith(".txt", ignoreCase = true)) {
                    handleCookiesDocumentUpload(chatId, doc)
                    return
                }
            }

            val text = msg.text?.trim() ?: return
            DiagnosticLogger.log("TELEGRAM:IN", "Получено сообщение от $chatId: '$text'")

            when {
                text == "/start" || text == "/help" || text == "/menu" -> {
                    sendWelcomeMenu(chatId)
                }

                text == BTN_MENU_LOG || text.startsWith("/log") -> {
                    sendFullDiagnosticLog(chatId)
                }

                text == BTN_MENU_STATUS || text.startsWith("/status") -> {
                    sendNodeStatusCard(chatId)
                }

                text == BTN_MENU_INBOX || text.startsWith("/inbox") -> {
                    sendStudioInboxSummary(chatId)
                }

                text == BTN_MENU_HUNT || text == "🔎 Найти видео по теме" -> {
                    sendQuickTopicPicker(chatId)
                }

                text == BTN_MENU_SPAM_FARMS || text.startsWith("/spam") -> {
                    val arg = text.removePrefix(BTN_MENU_SPAM_FARMS).removePrefix("/spam").trim()
                    if (arg.isNotBlank()) {
                        executeSpamFarmChannelHunt(chatId, arg)
                    } else {
                        sendSpamFarmPickerMessage(chatId)
                    }
                }

                text == BTN_MENU_LINK -> {
                    sendMessage(
                        chatId = chatId,
                        text = "📥 Вставь сюда любую ссылку на YouTube / Shorts / Reddit — ты сможешь либо сразу посмотреть выжимку «🧠 Что внутри + Таймкоды» по субтитрам без скачивания, либо скачать исходник в честном FullHD 1080p в Студию!"
                    )
                }

                text.contains("http://") || text.contains("https://") -> {
                    handleDirectLinkMessage(chatId, text)
                }

                text.startsWith("/hunt") -> {
                    handleHuntCommand(chatId, text)
                }

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
            DiagnosticLogger.log("TELEGRAM:BTN", "Нажата кнопка: '$data'")
            handleInlineButtonCallback(scope, chatId, data)
        }
    }

    private suspend fun handleCookiesDocumentUpload(chatId: Long, doc: TgDocument) {
        try {
            DiagnosticLogger.log("COOKIES", "Загрузка файла ${doc.fileName} из Telegram...")
            val fileResp: TgGetFileResponse = httpClient.get("${baseUrl()}/getFile") {
                parameter("file_id", doc.fileId)
            }.body()
            val filePath = fileResp.result?.filePath
            if (filePath.isNullOrBlank()) {
                sendMessage(chatId, "❌ Не удалось получить путь к файлу в Telegram.")
                return
            }
            val downloadUrl = "https://api.telegram.org/file/bot${configStore.getTelegramBotToken()}/$filePath"
            val targetCookies = File(context.filesDir, "youtube_cookies.txt")
            val response = httpClient.get(downloadUrl)
            targetCookies.outputStream().use { out ->
                response.bodyAsChannel().copyTo(out)
            }
            DiagnosticLogger.log("COOKIES", "✅ Файл youtube_cookies.txt сохранён (${targetCookies.length()} байт)")
            sendMessage(
                chatId,
                "✅ Файл cookies сохранён (${targetCookies.length() / 1024} KB)!\nТеперь все запросы yt-dlp к YouTube идут с твоими куками."
            )
        } catch (e: Exception) {
            DiagnosticLogger.logError("COOKIES", "Ошибка сохранения cookies.txt", e)
            sendMessage(chatId, "❌ Ошибка сохранения cookies.txt: ${e.message}")
        }
    }

    private suspend fun sendWelcomeMenu(chatId: Long) {
        val aiReady = configStore.getGeminiApiKey().isNotBlank()
        sendMessage(
            chatId = chatId,
            text = "⚡ Умный AI-Скаут Pocket Media Forge!\n" +
                "• Gemini AI-поиск и выжимка «Что внутри»: ${if (aiReady) "🟢 АКТИВЕН" else "⚪ Выкл (введи ключ в приложении)"}\n\n" +
                "Как пользоваться:\n" +
                "1️⃣ Опиши тему В СВОБОДНОЙ ФОРМЕ прямо в этот чат (например: «смешные истории комиков про собеседования и работу в офисе» или «разбор жестких клатчей в кс2»).\n" +
                "2️⃣ Gemini переведёт твоё описание в точные запросы, отсеет мусор и пришлёт СПИСОК ССЫЛОК с краткой выжимкой «🧠 Что внутри» по каждому ролику.\n" +
                "3️⃣ Под любым роликом жми:\n" +
                "   • «🧠 Что внутри #N» — бот за 3 секунды скачает субтитры с YouTube (без скачивания видео!) и распишет о чём видео + точные таймкоды лучших шуток/моментов!\n" +
                "   • «✅ Взять #N (1080p)» — бот скачает исходник в честном FullHD 1080p прямо в Студию приложения на телефоне (с уже выставленным таймкодом лучшего момента!)."
        )
    }

    private suspend fun sendStudioInboxSummary(chatId: Long) {
        val items = clutterFilter.listStudioInboxItems()
        if (items.isEmpty()) {
            sendMessage(
                chatId,
                "📂 В Студии приложения пока нет загруженных исходников.\nОпиши любую тему в чат или скинь ссылку, чтобы скачать исходники в 1080p!"
            )
            return
        }

        val sb = StringBuilder("📂 Загруженные исходники в Студии телефона (${items.size} шт.):\n\n")
        items.take(10).forEachIndexed { idx, item ->
            val mb = String.format("%.1f", item.sizeKb / 1024.0)
            sb.append("${idx + 1}. «${item.title.take(50)}» (${item.durationSec.toInt()}с • $mb МБ)\n")
            if (item.recommendedEndSec > item.recommendedStartSec) {
                sb.append("   ✂️ Рекомендованный момент: ${formatDurationMmSs(item.recommendedStartSec.toInt())} – ${formatDurationMmSs(item.recommendedEndSec.toInt())}\n")
            }
            if (item.aiSummary.isNotBlank()) {
                sb.append("   🧠 Суть: ${item.aiSummary.take(100)}\n")
            }
            if (item.sourceUrl.startsWith("http")) {
                sb.append("   🔗 ${item.sourceUrl}\n")
            }
            sb.append("\n")
        }
        sb.append("📱 Открой приложение Pocket Media Forge на телефоне -> вкладка «🎛 Студия», чтобы смешать их по шаблону с Анти-Детектом!")
        sendMessage(chatId, sb.toString())
    }

    private suspend fun sendFullDiagnosticLog(chatId: Long) {
        val ytdlpVer = PipelineForegroundService.queryRealYtDlpVersion()
        val cookiesFile = configStore.getYoutubeCookiesFile()
        val cookiesStatus = if (cookiesFile != null) "ВКЛ (${cookiesFile.length() / 1024} KB)" else "НЕТ"

        val recentLog = DiagnosticLogger.getRecentLinesText(38)
        val header = "📜 Журнал действий с запуска узла:\n" +
            "• Версия yt-dlp: $ytdlpVer\n" +
            "• YouTube Cookies: $cookiesStatus\n" +
            "• Исходников в Студии: ${clutterFilter.listStudioInboxItems().size} шт.\n" +
            "• Фонов в пуле: ${clutterFilter.listVerifiedPoolClips().size} шт.\n" +
            "────────────────────\n"

        val bodyText = (header + recentLog).takeLast(3950)

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🔄 Обновить лог", "log_refresh:0"),
                    InlineKeyboardButton("📄 Скачать .log файл", "log_file:0")
                ),
                listOf(
                    InlineKeyboardButton("⬆️ Обновить ядро yt-dlp", "ytdlp_update:0"),
                    InlineKeyboardButton("🧹 Очистить лог", "log_clear:0")
                )
            )
        )

        sendMessageWithKeyboard(chatId, bodyText, keyboard)
    }

    private suspend fun sendNodeStatusCard(chatId: Long) {
        val poolSize = clutterFilter.listVerifiedPoolClips().size
        val inboxSize = clutterFilter.listStudioInboxItems().size
        val svcState = PipelineForegroundService.statusFlow.value
        val ytdlpVer = PipelineForegroundService.queryRealYtDlpVersion()
        val cookiesLoaded = configStore.getYoutubeCookiesFile() != null
        val geminiLoaded = configStore.getGeminiApiKey().isNotBlank()

        val text = "📊 Статус узла (OnePlus Snapdragon):\n\n" +
            "• Конвейер: $svcState\n" +
            "• Версия ядра yt-dlp: $ytdlpVer\n" +
            "• Gemini 2.5 («Что внутри» и AI-поиск): ${if (geminiLoaded) "🟢 Подключён" else "⚪ Не задан"}\n" +
            "• Куки YouTube: ${if (cookiesLoaded) "✅ Загружены" else "Работа через tv_samsung"}\n" +
            "• Загруженных исходников в Студии (1080p): $inboxSize шт.\n" +
            "• Готовых залипалок в пуле (1080x960): $poolSize шт."

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("📂 Показать исходники в Студии ($inboxSize)", "show_inbox:0")
                ),
                listOf(
                    InlineKeyboardButton("🔄 Докачать залипалки в пул", "pool_warmup:0"),
                    InlineKeyboardButton("📜 Открыть лог", "log_refresh:0")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun sendQuickTopicPicker(chatId: Long) {
        val text = "🧠 Опиши в чат ЛЮБУЮ тематику своими словами (в свободной форме)!\n\n" +
            "• При запросе стендапа (рассказы / диалоги / истории) бот АВТОМАТИЧЕСКИ подключит смежные запросы («стендап монологи», «стендапы о жизни», «стендап истории», «импровизация с залом») + популярных современных комиков + живые подсказки YouTube.\n" +
            "• Можно искать строго на русском 🇷🇺 или переключить на 🇺🇸 Английский специально под переозвучку в Студии!\n\n" +
            "Или нажми на одну из готовых кнопок ниже:"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("😂 Стендап: монологи и истории о жизни", "quick_hunt:стендап рассказы монологи о жизни истории комиков"),
                    InlineKeyboardButton("🇺🇸 EN Стендап (под переозвучку)", "quick_hunt_en:standup comedy stories crowd work monologue")
                ),
                listOf(
                    InlineKeyboardButton("🎙 Интересные мысли из подкастов", "quick_hunt:интересная мысль история из подкаста интервью"),
                    InlineKeyboardButton("🎮 Эпичные моменты CS2", "quick_hunt:лучшие моменты клатчи cs2 киберспорт")
                ),
                listOf(
                    InlineKeyboardButton("🧪 Научпоп: космос и физика", "quick_hunt:научпоп интересные факты космос физика простыми словами")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun handleHuntCommand(
        chatId: Long,
        rawText: String,
        defaultLanguage: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY
    ) {
        val queryRaw = rawText.removePrefix("/hunt").trim()
        if (queryRaw.isBlank()) {
            sendQuickTopicPicker(chatId)
            return
        }

        val huntId = UUID.randomUUID().toString().take(6)
        val cleanQuery = queryRaw.replace("#", " ").replace(Regex("""\s+"""), " ").trim()
        val autoDetectedLang = if (
            defaultLanguage == SearchLanguageMode.RUSSIAN_ONLY &&
            cleanQuery.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            !cleanQuery.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        ) {
            SearchLanguageMode.ENGLISH_FOR_DUB
        } else {
            defaultLanguage
        }
        val state = PendingHuntFilterState(
            queryTags = cleanQuery,
            languageMode = autoDetectedLang
        )
        huntFilters[huntId] = state

        sendHuntConfiguratorMessage(chatId, huntId, state)
    }

    private suspend fun sendHuntConfiguratorMessage(
        chatId: Long,
        huntId: String,
        state: PendingHuntFilterState
    ) {
        val text = buildString {
            append("🧠 Запрос в свободной форме:\n«").append(state.queryTags).append("»\n\n")
            append("• Авто-расширение темы: 🟢 ВКЛ (Синонимы + Комики + Автоподсказки YouTube + AI)\n")
            append("• Язык видео: ").append(state.languageMode.label).append("\n")
            append("• Тип видео: ").append(state.durationMode.label).append("\n")
            append("• Сортировка: ").append(state.sortOrder.label).append("\n")
            append("• Период загрузки: ").append(state.period.label).append(" (жесткий фильтр YouTube)\n")
            append("• Сколько роликов отобрать: ").append(state.count).append(" шт.\n\n")
            append("👇 Жми зелёную кнопку — я найду свежие видео по теме, отсею старые архивы и чужие языки, сделаю выжимку «Что внутри» и пришлю ссылки:")
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🚀 Найти и сделать выжимку (${state.count} шт.)", "hunt_go:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Язык: ${state.languageMode.label}", "hunt_lang:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Тип: ${state.durationMode.label}", "hunt_dur:$huntId")
                ),
                listOf(
                    InlineKeyboardButton("Сорт: ${state.sortOrder.label}", "hunt_sort:$huntId"),
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

    private fun formatDurationMmSs(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun formatViewsCount(views: Long): String {
        return when {
            views >= 1_000_000L -> String.format("%.1fM", views / 1_000_000.0)
            views >= 1_000L -> String.format("%.1fK", views / 1_000.0)
            else -> views.toString()
        }
    }

    private suspend fun executeTagSearchAndSendLinks(chatId: Long, state: PendingHuntFilterState) {
        sendMessage(
            chatId,
            "🔎 Расширяю тему «${state.queryTags}» (подключаю популярные похожие запросы, комиков/авторов и автоподсказки YouTube), язык: ${state.languageMode.label}, период: ${state.period.label}..."
        )

        enqueueHardwareTask("search", "AI Smart Search for '${state.queryTags}'") {
            try {
                val candidates = topicHunter.huntByTags(
                    queryTags = state.queryTags,
                    platform = state.platform,
                    sortOrder = state.sortOrder,
                    period = state.period,
                    durationMode = state.durationMode,
                    languageMode = state.languageMode,
                    limitCount = state.count
                )

                if (candidates.isEmpty()) {
                    sendMessage(
                        chatId,
                        "⚠️ По теме «${state.queryTags}» за период «${state.period.label}» подходящих видео не найдено. Попробуй переключить «Период» (например на За год / За всё время) или «Язык»."
                    )
                    return@enqueueHardwareTask
                }

                val batchId = UUID.randomUUID().toString().take(6)
                discoveredBatches[batchId] = candidates
                batchTopics[batchId] = state.queryTags

                val msgText = buildString {
                    append("🎯 Найдено ${candidates.size} видео по теме:\n«${state.queryTags}»\n\n")
                    append("• Нажми «🧠 Что внутри #N», чтобы за 3 сек прочитать полную выжимку по субтитрам и узнать точные таймкоды лучших моментов (без скачивания видео!).\n")
                    append("• Или сразу жми «✅ Взять #N (1080p)», чтобы закинуть исходник в Студию телефона:\n\n")

                    candidates.forEachIndexed { idx, c ->
                        val num = idx + 1
                        val durStr = formatDurationMmSs(c.durationSec)
                        val viewsStr = formatViewsCount(c.viewOrScoreCount)
                        append("$num. 📌 ${c.title}\n")
                        append("   ⏱ $durStr • 👁 $viewsStr • 👤 ${c.author}\n")
                        if (c.aiWhatsInside.isNotBlank()) {
                            append("   🧠 Что внутри: ${c.aiWhatsInside}\n")
                        }
                        append("   🔗 ${c.url}\n\n")
                    }
                }

                // For each video #N, add a row: [🧠 Что внутри #N] [✅ Взять #N (1080p)]
                val buttonRows = mutableListOf<List<InlineKeyboardButton>>()
                for (idx in candidates.indices) {
                    val num = idx + 1
                    buttonRows.add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🧠 Что внутри #$num (Таймкоды)",
                                callbackData = "inside_one:${batchId}_$idx"
                            ),
                            InlineKeyboardButton(
                                text = "✅ Взять #$num (1080p)",
                                callbackData = "take_one:${batchId}_$idx"
                            )
                        )
                    )
                }

                buttonRows.add(
                    listOf(
                        InlineKeyboardButton(
                            text = "📦 Скачать ВСЕ (${candidates.size} шт.) в 1080p",
                            callbackData = "take_all:$batchId"
                        )
                    )
                )

                buttonRows.add(
                    listOf(
                        InlineKeyboardButton(
                            text = "📡 Найти такие же спам-каналы / фермы по этой теме",
                            callbackData = "spam_topic:$batchId"
                        )
                    )
                )

                sendMessageWithKeyboard(chatId, msgText.take(4000), InlineKeyboardMarkup(buttonRows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("HUNT", "Ошибка умного поиска", e)
                sendMessage(chatId, "❌ Ошибка поиска: ${e.message}")
            }
        }
    }

    private suspend fun sendSpamFarmPickerMessage(chatId: Long) {
        val text = "📡 Охотник на «Спам-Каналы / Контент-Фермы»!\n\n" +
            "Ищет каналы в выбранной нише, которые заливают ролики пачками/потоком (высокая частота заливов, случайный разброс тем в одной нише, шаблонные #shorts/нарезки и резкие прострелы по просмотрам).\n\n" +
            "• Напиши команду `/spam твоя тема` (например: `/spam мото ситуации на дороге` или `/spam истории стендап`)\n" +
            "• Или выбери одну из ниш кнопкой ниже:"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("📡 Стендап / Комики нарезки", "spam_quick:стендап комики истории нарезка"),
                    InlineKeyboardButton("📡 Мото / Авто ситуации", "spam_quick:мотоцикл дрифт ситуации на дороге")
                ),
                listOf(
                    InlineKeyboardButton("📡 Подкасты / Мысли / Факты", "spam_quick:подкаст интересный факт мысли"),
                    InlineKeyboardButton("📡 CS2 / Игровые нарезки", "spam_quick:cs2 клатч моменты нарезка")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun executeSpamFarmChannelHunt(chatId: Long, topicQuery: String) {
        sendMessage(
            chatId,
            "📡 Сканирую YouTube на предмет спам-каналов и контент-ферм в нише:\n«$topicQuery»\nАнализирую частоту заливов, рандомность заголовков и всплески просмотров..."
        )

        enqueueHardwareTask("spam_hunt", "Hunting spam-farm channels for '$topicQuery'") {
            try {
                val channels = topicHunter.huntSpamFarmChannels(topicQuery, maxChannels = 5)
                if (channels.isEmpty()) {
                    sendMessage(
                        chatId,
                        "⚠️ По теме «$topicQuery» спам-каналов не обнаружено. Попробуй задать тему чуть шире."
                    )
                    return@enqueueHardwareTask
                }

                val spamBatchId = UUID.randomUUID().toString().take(6)
                discoveredSpamBatches[spamBatchId] = channels

                val msgText = buildString {
                    append("📡 Найдено ${channels.size} потоковых/спам-каналов по теме:\n«$topicQuery»\n\n")
                    channels.forEachIndexed { idx, ch ->
                        val num = idx + 1
                        val perWeek = String.format(java.util.Locale.US, "%.1f", ch.estimatedUploadsPerWeek)
                        append("$num. 🏭 Канал: ${ch.channelName}\n")
                        append("   🔥 Спам-индекс: ${ch.spamScore}/100 • 📈 ~$perWeek рол./нед • 🎲 Рандом тем: ${ch.titleRandomnessPct}%\n")
                        append("   📋 Паттерн: ${ch.patternSummary}\n")
                        append("   🔗 Поток канала: ${ch.channelUrl}\n")
                        if (ch.sampleVideos.isNotEmpty()) {
                            append("   🎬 Примеры роликов канала:\n")
                            ch.sampleVideos.take(2).forEach { v ->
                                append("      • «${v.title.take(48)}» (${formatViewsCount(v.viewOrScoreCount)} просм.) — ${v.url}\n")
                            }
                        }
                        append("\n")
                    }
                    append("👇 Нажми кнопку ниже, чтобы сразу выкачать топ-ролики с выбранного спам-канала в FullHD 1080p в Студию телефона:")
                }

                val rows = mutableListOf<List<InlineKeyboardButton>>()
                channels.forEachIndexed { idx, ch ->
                    val num = idx + 1
                    rows.add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📥 Взять топ-2 с канала #$num (${ch.channelName.take(18)}) в 1080p",
                                callbackData = "spam_dl:${spamBatchId}_$idx"
                            )
                        )
                    )
                }

                sendMessageWithKeyboard(chatId, msgText.take(4000), InlineKeyboardMarkup(rows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("SPAM-HUNTER", "Ошибка поиска спам-каналов", e)
                sendMessage(chatId, "❌ Ошибка поиска спам-каналов: ${e.message}")
            }
        }
    }

    private suspend fun inspectCandidateWhatsInside(
        chatId: Long,
        cand: DiscoveredMediaCandidate,
        userTopic: String,
        batchId: String,
        candIdx: Int
    ) {
        sendMessage(
            chatId,
            "🧠 Читаю субтитры и описание ролика «${cand.title.take(50)}» (без скачивания самого видео)... Сейчас пришлю выжимку «Что внутри» и таймкоды!"
        )

        enqueueHardwareTask("inside", "Inspecting 'What's Inside' for ${cand.id}") {
            try {
                val digest = topicHunter.inspectVideoInsideViaSubtitles(
                    videoUrl = cand.url,
                    videoTitle = cand.title,
                    userTopic = userTopic
                )

                val text = buildString {
                    append("🧠 ЧТО ВНУТРИ РОЛИКА:\n")
                    append("📌 ${cand.title} (${formatDurationMmSs(cand.durationSec)})\n")
                    append("🔗 ${cand.url}\n")
                    append("📝 Источник анализа: ").append(
                        if (digest.hasTranscript) "✅ Полные субтитры YouTube + Описание" else "ℹ️ Описание и метаданные (субтитры у автора отключены)"
                    ).append("\n\n")

                    append("💡 Выжимка сути:\n")
                    append(digest.overallSummary).append("\n\n")

                    if (digest.keyTopics.isNotEmpty()) {
                        append("🏷 Темы внутри: ").append(digest.keyTopics.joinToString(", ")).append("\n\n")
                    }

                    if (digest.moments.isNotEmpty()) {
                        append("🎯 Рекомендованные цельные моменты (без обрезки фраз):\n")
                        digest.moments.forEachIndexed { mIdx, m ->
                            val span = (m.endSec - m.startSec).coerceAtLeast(1)
                            append("${mIdx + 1}) ⏱ ${formatDurationMmSs(m.startSec)} – ${formatDurationMmSs(m.endSec)} ($span сек) — ${m.title}\n")
                            if (m.summary.isNotBlank()) {
                                append("   💬 ${m.summary}\n")
                            }
                        }
                        append("\n👇 Нажми кнопку с нужным моментом ниже — я скачаю исходник в 1080p и АВТОМАТИЧЕСКИ выставлю этот таймкод в Студии на телефоне:")
                    }
                }

                val rows = mutableListOf<List<InlineKeyboardButton>>()
                digest.moments.take(3).forEachIndexed { mIdx, m ->
                    val labelTime = "${formatDurationMmSs(m.startSec)}–${formatDurationMmSs(m.endSec)}"
                    rows.add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✂️ Взять момент #${mIdx + 1} ($labelTime) в 1080p",
                                callbackData = "take_cut:${batchId}_${candIdx}_${m.startSec}_${m.endSec}"
                            )
                        )
                    )
                }

                rows.add(
                    listOf(
                        InlineKeyboardButton(
                            text = "📥 Скачать ВЕСЬ исходник целиком в 1080p",
                            callbackData = "take_one:${batchId}_$candIdx"
                        )
                    )
                )

                sendMessageWithKeyboard(chatId, text.take(4000), InlineKeyboardMarkup(rows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("WHATS-INSIDE", "Ошибка анализа ролика", e)
                sendMessage(chatId, "❌ Не удалось получить выжимку: ${e.message}")
            }
        }
    }

    private suspend fun handleDirectLinkMessage(chatId: Long, rawText: String) {
        val tokens = rawText.split(Regex("""\s+"""))
        val url = tokens.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            sendMessage(chatId, "❌ Ссылка не распознана.")
            return
        }

        val linkId = UUID.randomUUID().toString().take(6)
        pendingLinks[linkId] = PendingDirectUrlState(url = url)

        val text = "🎬 Ссылка получена:\n$url\n\nЧто сделать с этим роликом?"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton("🧠 Узнать «Что внутри» + Таймкоды (без скачивания)", "link_inside:$linkId")
                ),
                listOf(
                    InlineKeyboardButton("📥 Сразу скачать исходник в FullHD 1080p в Студию", "link_dl:$linkId")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun downloadCandidateIntoStudioInbox(
        chatId: Long,
        url: String,
        titleHint: String?,
        authorHint: String?,
        recommendedStartSec: Double = 0.0,
        recommendedEndSec: Double = 0.0,
        aiSummary: String = ""
    ) {
        val taskId = UUID.randomUUID().toString().take(6)
        enqueueHardwareTask(taskId, "Downloading 1080p source to Studio") {
            try {
                val timeHintStr = if (recommendedEndSec > recommendedStartSec) {
                    " (выбран момент ${formatDurationMmSs(recommendedStartSec.toInt())}–${formatDurationMmSs(recommendedEndSec.toInt())})"
                } else {
                    ""
                }
                sendMessage(
                    chatId,
                    "📥 Качаю исходник в честном FullHD 1080p$timeHintStr:\n${titleHint ?: url}..."
                )
                val inboxItem = clutterFilter.downloadSourceToStudioInbox(
                    sourceUrl = url,
                    overrideTitle = titleHint,
                    overrideAuthor = authorHint,
                    maxDurationSec = 360,
                    recommendedStartSec = recommendedStartSec,
                    recommendedEndSec = recommendedEndSec,
                    aiSummary = aiSummary
                )
                downloadedInboxItems[inboxItem.id] = inboxItem

                val sizeMb = String.format("%.1f", inboxItem.sizeKb / 1024.0)
                val replyText = buildString {
                    append("✅ Исходник загружен в FullHD 1080p!\n\n")
                    append("📌 Название: ").append(inboxItem.title).append("\n")
                    append("👤 Автор: ").append(inboxItem.author).append("\n")
                    append("⏱ Длительность файла: ").append(inboxItem.durationSec.toInt()).append(" сек • 📦 Вес: ").append(sizeMb).append(" МБ\n")
                    if (recommendedEndSec > recommendedStartSec) {
                        append("✂️ Авто-таймкод для Студии: ")
                            .append(formatDurationMmSs(recommendedStartSec.toInt()))
                            .append(" – ")
                            .append(formatDurationMmSs(recommendedEndSec.toInt()))
                            .append(" (уже выставлен в плеере!)\n")
                    }
                    if (inboxItem.sourceTags.isNotEmpty()) {
                        append("🏷 Авто-теги темы (для подбора залипалки): ")
                            .append(inboxItem.sourceTags.joinToString(" "))
                            .append("\n")
                    }
                    if (aiSummary.isNotBlank()) {
                        append("🧠 Что внутри: ").append(aiSummary).append("\n")
                    }
                    append("🔗 Оригинал: ").append(inboxItem.sourceUrl).append("\n\n")
                    append("📱 Видео уже ждёт тебя во вкладке «🎛 Студия» внутри приложения на телефоне (и сохранено в Галерею телефона в папку PocketMediaForge/Sources)!")
                }

                val topTag = inboxItem.sourceTags.firstOrNull() ?: "#тема"
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            InlineKeyboardButton("🎬 Докачать залипалку под тег $topTag", "fetch_tag_bg:${inboxItem.id}")
                        ),
                        listOf(
                            InlineKeyboardButton("📡 Найти такие же спам-каналы по этой теме", "spam_item:${inboxItem.id}")
                        ),
                        listOf(
                            InlineKeyboardButton("📤 Скинуть этот исходник файлом сюда в чат", "send_raw:${inboxItem.id}")
                        ),
                        listOf(
                            InlineKeyboardButton("⚡ Быстро собрать (Сплит + Тег-Фон + Анти-Детект)", "quick_render:${inboxItem.id}")
                        )
                    )
                )

                sendMessageWithKeyboard(chatId, replyText, keyboard)
            } catch (e: Exception) {
                DiagnosticLogger.logError("INBOX-DL", "Ошибка скачивания исходника $url", e)
                sendMessage(chatId, "❌ Не удалось скачать исходник:\n${e.message}")
            }
        }
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

        when (action) {
            "log_refresh" -> {
                sendFullDiagnosticLog(chatId)
                return
            }
            "log_file" -> {
                val logFile = DiagnosticLogger.getFullLogFile(context)
                sendUncompressedDocument(chatId, logFile, "📜 Полный лог (diagnostic_boot.log)")
                return
            }
            "log_clear" -> {
                DiagnosticLogger.clear(context)
                sendFullDiagnosticLog(chatId)
                return
            }
            "show_inbox" -> {
                sendStudioInboxSummary(chatId)
                return
            }
            "ytdlp_update" -> {
                sendMessage(chatId, "⬆️ Проверяю и обновляю ядро yt-dlp напрямую с GitHub...")
                scope.launch(Dispatchers.IO) {
                    try {
                        val ver = PipelineForegroundService.ensureModernYtDlpBinary(context, forceDownload = true)
                        sendMessage(chatId, "✅ Ядро yt-dlp готово!\n• Рабочая версия: $ver")
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Ошибка обновления yt-dlp: ${e.message}")
                    }
                }
                return
            }
            "pool_warmup" -> {
                clutterFilter.triggerBackgroundPoolWarmupIfNeeded(forceAddMore = true)
                sendMessage(
                    chatId,
                    "🔄 Запущена докачка коротких залипалок (<70с, 720p) в пул! Посмотреть и почистить их можно прямо в приложении во вкладке «🗂 Фоны»."
                )
                return
            }
            "quick_hunt" -> {
                handleHuntCommand(chatId, targetId, SearchLanguageMode.RUSSIAN_ONLY)
                return
            }
            "quick_hunt_en" -> {
                handleHuntCommand(chatId, targetId, SearchLanguageMode.ENGLISH_FOR_DUB)
                return
            }
            "spam_quick" -> {
                executeSpamFarmChannelHunt(chatId, targetId)
                return
            }
            "spam_topic" -> {
                val topic = batchTopics[targetId] ?: "популярные нарезки shorts"
                executeSpamFarmChannelHunt(chatId, topic)
                return
            }
            "spam_item" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                val seedTopic = if (item != null) {
                    "${item.title} ${item.sourceTags.joinToString(" ")}"
                } else {
                    "нарезки истории shorts"
                }
                executeSpamFarmChannelHunt(chatId, seedTopic)
                return
            }
            "spam_dl" -> {
                val subParts = targetId.split("_", limit = 2)
                if (subParts.size < 2) return
                val spamBatchId = subParts[0]
                val chIdx = subParts[1].toIntOrNull() ?: return
                val channels = discoveredSpamBatches[spamBatchId]
                if (channels == null || chIdx !in channels.indices) {
                    sendMessage(chatId, "⚠️ Список спам-каналов устарел. Запусти поиск заново.")
                    return
                }
                val ch = channels[chIdx]
                val vids = ch.sampleVideos.take(2)
                if (vids.isEmpty()) {
                    sendMessage(chatId, "⚠️ У канала нет доступных роликов для скачивания.")
                    return
                }
                sendMessage(chatId, "📥 Выкачиваю ${vids.size} лучших ролика со спам-канала «${ch.channelName}» в 1080p...")
                for (v in vids) {
                    downloadCandidateIntoStudioInbox(
                        chatId = chatId,
                        url = v.url,
                        titleHint = v.title,
                        authorHint = ch.channelName,
                        aiSummary = "Спам-ферма «${ch.channelName}» (спам-индекс ${ch.spamScore}/100)"
                    )
                }
                return
            }
            "link_inside" -> {
                val linkState = pendingLinks[targetId]
                if (linkState == null) {
                    sendMessage(chatId, "⚠️ Ссылка устарела, отправь её ещё раз.")
                    return
                }
                val tempBatchId = "lnk_$targetId"
                val dummyCand = DiscoveredMediaCandidate(
                    id = "direct_$targetId",
                    url = linkState.url,
                    title = linkState.url,
                    author = "YouTube",
                    platform = HuntPlatform.YOUTUBE,
                    durationSec = 120,
                    viewOrScoreCount = 0L
                )
                discoveredBatches[tempBatchId] = listOf(dummyCand)
                inspectCandidateWhatsInside(
                    chatId = chatId,
                    cand = dummyCand,
                    userTopic = "Анализ ключевых моментов и сути видео",
                    batchId = tempBatchId,
                    candIdx = 0
                )
                return
            }
            "link_dl" -> {
                val linkState = pendingLinks[targetId]
                if (linkState == null) {
                    sendMessage(chatId, "⚠️ Ссылка устарела, отправь её ещё раз.")
                    return
                }
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = linkState.url,
                    titleHint = null,
                    authorHint = null
                )
                return
            }
            "inside_one" -> {
                val subParts = targetId.split("_", limit = 2)
                if (subParts.size < 2) return
                val batchId = subParts[0]
                val idx = subParts[1].toIntOrNull() ?: return
                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                val topic = batchTopics[batchId] ?: chosen.title
                inspectCandidateWhatsInside(
                    chatId = chatId,
                    cand = chosen,
                    userTopic = topic,
                    batchId = batchId,
                    candIdx = idx
                )
                return
            }
            "take_cut" -> {
                // Format: batchId_candIdx_startSec_endSec
                val subParts = targetId.split("_")
                if (subParts.size < 4) return
                val endSec = subParts.last().toDoubleOrNull() ?: 0.0
                val startSec = subParts[subParts.size - 2].toDoubleOrNull() ?: 0.0
                val idx = subParts[subParts.size - 3].toIntOrNull() ?: return
                val batchId = subParts.dropLast(3).joinToString("_")

                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = chosen.url,
                    titleHint = chosen.title.takeIf { !it.startsWith("http") },
                    authorHint = chosen.author,
                    recommendedStartSec = startSec,
                    recommendedEndSec = endSec,
                    aiSummary = chosen.aiWhatsInside
                )
                return
            }
            "take_one" -> {
                val idx = targetId.substringAfterLast("_").toIntOrNull() ?: return
                val batchId = targetId.substringBeforeLast("_")
                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = chosen.url,
                    titleHint = chosen.title.takeIf { !it.startsWith("http") },
                    authorHint = chosen.author,
                    aiSummary = chosen.aiWhatsInside
                )
                return
            }
            "take_all" -> {
                val list = discoveredBatches[targetId]
                if (list.isNullOrEmpty()) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                sendMessage(chatId, "📦 Запускаю скачивание всех ${list.size} исходников в FullHD 1080p по очереди в Студию телефона...")
                for (chosen in list) {
                    downloadCandidateIntoStudioInbox(
                        chatId = chatId,
                        url = chosen.url,
                        titleHint = chosen.title,
                        authorHint = chosen.author,
                        aiSummary = chosen.aiWhatsInside
                    )
                }
                return
            }
            "send_raw" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Файл не найден в Студии.")
                    return
                }
                val file = clutterFilter.getInboxVideoFile(item)
                if (!file.exists()) {
                    sendMessage(chatId, "⚠️ Видеофайл был удалён.")
                    return
                }
                scope.launch(Dispatchers.IO) {
                    sendMessage(chatId, "📤 Отправляю исходник «${item.title.take(45)}» файлом без сжатия...")
                    sendUncompressedDocument(
                        chatId = chatId,
                        file = file,
                        caption = "🎬 Исходник 1080p: ${item.title}\n🔗 ${item.sourceUrl}"
                    )
                }
                return
            }
            "fetch_tag_bg" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Исходник не найден.")
                    return
                }
                val tags = item.sourceTags.ifEmpty { clutterFilter.extractSemanticTopicTags(item.title) }
                val query = tags.firstOrNull()?.removePrefix("#") ?: item.title.take(25)
                scope.launch(Dispatchers.IO) {
                    sendMessage(
                        chatId,
                        "🔎 Ищу и качаю в пул тематическую залипалку под теги ${tags.joinToString(" ")}..."
                    )
                    val added = clutterFilter.downloadTaggedClutterByTopic(query, maxClipsToAdd = 1)
                    if (added.isNotEmpty()) {
                        sendMessage(
                            chatId,
                            "✅ Тематическая залипалка скачана в пул с тегами ${tags.joinToString(" ")}! Теперь при сборке этого исходника она подставится автоматически."
                        )
                    } else {
                        sendMessage(
                            chatId,
                            "⚠️ С первого раза короткий фон по теме «$query» не скачался. Можно задать свой тег во вкладке «🗂 Фоны»."
                        )
                    }
                }
                return
            }
            "quick_render" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Исходник не найден в Студии.")
                    return
                }
                val videoFile = clutterFilter.getInboxVideoFile(item)
                if (!videoFile.exists()) {
                    sendMessage(chatId, "⚠️ Исходник удалён.")
                    return
                }
                sendMessage(
                    chatId,
                    "⚡ Запускаю быструю сборку FullHD (Сплит 50/50 + подбор фона по тегам ${item.sourceTags.joinToString(" ")} + Анти-Детект)..."
                )
                enqueueHardwareTask(item.id, "Quick FullHD Render & Gallery Save") {
                    try {
                        val fullDur = hardwareEngine.probeDurationSeconds(videoFile)
                        val startS = item.recommendedStartSec.coerceIn(0.0, (fullDur - 2.0).coerceAtLeast(0.0))
                        val endS = if (item.recommendedEndSec > startS + 2.0) {
                            item.recommendedEndSec.coerceAtMost(fullDur)
                        } else {
                            (startS + 65.0).coerceAtMost(fullDur)
                        }
                        val spanS = (endS - startS).coerceAtLeast(5.0)
                        val clutterFile = clutterFilter.buildShuffledClutterReel(
                            targetDurationSec = spanS,
                            fallbackPrimaryVideo = videoFile,
                            preferredTopicTags = item.sourceTags
                        )
                        val framing = faceHelper.computeFramingPlan(videoFile, enableSmartFaceCrop = true)
                        val cfg = ModularRenderConfig.presetPodcastSplit().copy(
                            trimStartSec = startS,
                            trimEndSec = endS,
                            preferredTopicTags = item.sourceTags,
                            videoBitrateKbps = 12000,
                            capBitrateForTelegram50Mb = true
                        )
                        val rendered = hardwareEngine.renderPipeline(
                            primaryVideoFile = videoFile,
                            clutterReelFile = clutterFile,
                            framingPlan = framing,
                            assSubtitleFile = null,
                            config = cfg,
                            isPreviewPass = false
                        )
                        val galleryFile = hardwareEngine.saveRenderedVideoToGallery(rendered, item.title)
                        sendUncompressedDocument(
                            chatId = chatId,
                            file = rendered,
                            caption = "✅ Готовый ролик сохранён в Галерею телефона (${galleryFile.name})!\n🔗 Оригинал: ${item.sourceUrl}"
                        )
                        rendered.delete()
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Ошибка сборки: ${e.message}")
                    }
                }
                return
            }
        }

        if (action.startsWith("hunt_")) {
            val filterState = huntFilters[targetId]
            if (filterState == null) {
                sendMessage(chatId, "⚠️ Сессия поиска истекла. Напиши тему заново.")
                return
            }
            when (action) {
                "hunt_lang" -> {
                    val values = SearchLanguageMode.entries
                    filterState.languageMode = values[(filterState.languageMode.ordinal + 1) % values.size]
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_dur" -> {
                    val values = SearchDurationMode.entries
                    filterState.durationMode = values[(filterState.durationMode.ordinal + 1) % values.size]
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
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
                        4 -> 6
                        6 -> 10
                        else -> 4
                    }
                    sendHuntConfiguratorMessage(chatId, targetId, filterState)
                }
                "hunt_go" -> {
                    huntFilters.remove(targetId)
                    executeTagSearchAndSendLinks(chatId, filterState)
                }
            }
        }
    }

    private suspend fun sendMessage(chatId: Long, text: String) {
        try {
            val replyMarkupJson = jsonFormat.encodeToString(persistentMainMenuKeyboard)
            httpClient.post("${baseUrl()}/sendMessage") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("disable_web_page_preview", true)
                parameter("reply_markup", replyMarkupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
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
                parameter("disable_web_page_preview", true)
                parameter("reply_markup", markupJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageWithKeyboard failed: ${e.message}")
        }
    }

    private suspend fun sendUncompressedDocument(
        chatId: Long,
        file: File,
        caption: String
    ) {
        try {
            val safeCaption = if (caption.length > 1000) caption.take(1000) else caption
            httpClient.post("${baseUrl()}/sendDocument") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", safeCaption)
                            append(
                                key = "document",
                                value = ChannelProvider(file.length()) { file.inputStream().toByteReadChannel() },
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendUncompressedDocument failed: ${e.message}")
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
}
