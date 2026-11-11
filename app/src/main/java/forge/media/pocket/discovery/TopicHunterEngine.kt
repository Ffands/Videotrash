package forge.media.pocket.discovery

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

enum class ContentSortOrder(val label: String) {
    POPULAR("🔥 По популярности"),
    NEWEST("🆕 По новизне (Свежие)"),
    VIRAL_VELOCITY("📈 Взлетающие"),
    RELEVANCE("🧠 Умный AI-Отбор (Gemini)")
}

enum class SearchTimePeriod(val label: String, val daysBack: Int?, val redditParam: String) {
    DAY("За 24 часа", 1, "day"),
    WEEK("За неделю", 7, "week"),
    MONTH("За месяц", 30, "month"),
    YEAR("За год", 365, "year"),
    ALL_TIME("За всё время", null, "all")
}

enum class HuntPlatform(val label: String) {
    YOUTUBE("▶️ YouTube"),
    ALL("🌐 Все площадки"),
    REDDIT("👽 Reddit Video")
}

enum class SearchDurationMode(
    val label: String,
    val minSec: Int,
    val maxSec: Int,
    val appendShortsKeyword: Boolean
) {
    ANY_UP_TO_6M("🌐 Любые (до 6 мин)", 8, 360, false),
    ORIGINALS_1_TO_6M("🎬 Исходники 1–6 мин (Без чужих эдитов)", 55, 360, false),
    SHORTS_ONLY("⚡ Только Shorts (до 75 сек)", 8, 75, true)
}

enum class SearchLanguageMode(val label: String) {
    RUSSIAN_ONLY("🇷🇺 Только Русский (Без других языков)"),
    ENGLISH_FOR_DUB("🇺🇸 Английский (Под переозвучку EN->RU)"),
    ANY_LANGUAGE("🌐 Любой язык (RU + EN)")
}

@Serializable
data class DiscoveredMediaCandidate(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val platform: HuntPlatform,
    val durationSec: Int,
    val viewOrScoreCount: Long,
    val uploadDateYmd: String = "",
    val descriptionSnippet: String = "",
    val aiWhatsInside: String = "",
    val cloutStatusLabel: String = "",
    val entityPopularityNote: String = "",
    val conversionScore: Int = 0
)

@Serializable
data class VideoHighlightMoment(
    val startSec: Int = 0,
    val endSec: Int = 45,
    val title: String = "",
    val summary: String = ""
)

@Serializable
data class VideoInsideDigest(
    val overallSummary: String = "",
    val keyTopics: List<String> = emptyList(),
    val moments: List<VideoHighlightMoment> = emptyList(),
    val hasTranscript: Boolean = false
)

@Serializable
data class DiscoveredSpamChannel(
    val channelName: String,
    val channelUrl: String,
    val topicQuery: String,
    val spamScore: Int,
    val videosFoundCount: Int,
    val estimatedUploadsPerWeek: Double,
    val titleRandomnessPct: Int,
    val patternSummary: String,
    val sampleVideos: List<DiscoveredMediaCandidate> = emptyList()
)

class TopicHunterEngine(private val context: Context) {

    private val configStore = NodeConfigStore(context)
    private val seenIdsFile = File(context.filesDir, "seen_video_ids.txt")
    private val cookiesFile = File(context.filesDir, "youtube_cookies.txt")
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val stateMutex = Mutex()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 55_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 55_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class YtDlpFlatEntry(
        val id: String = "",
        val url: String? = null,
        @SerialName("webpage_url") val webpageUrl: String? = null,
        val title: String = "",
        val description: String? = null,
        val uploader: String? = null,
        val channel: String? = null,
        val duration: Double? = null,
        @SerialName("view_count") val viewCount: Long? = null,
        @SerialName("upload_date") val uploadDate: String? = null
    )

    @Serializable
    private data class RedditSearchListing(
        val data: RedditSearchData? = null
    )

    @Serializable
    private data class RedditSearchData(
        val children: List<RedditSearchChild> = emptyList()
    )

    @Serializable
    private data class RedditSearchChild(
        val data: RedditSearchPost? = null
    )

    @Serializable
    private data class RedditSearchPost(
        val id: String = "",
        val title: String = "",
        val author: String = "",
        @SerialName("is_video") val isVideo: Boolean = false,
        val over18: Boolean = false,
        val score: Long = 0L,
        @SerialName("created_utc") val createdUtc: Double = 0.0,
        val media: RedditSearchMedia? = null
    )

    @Serializable
    private data class RedditSearchMedia(
        @SerialName("reddit_video") val redditVideo: RedditSearchVideo? = null
    )

    @Serializable
    private data class RedditSearchVideo(
        @SerialName("fallback_url") val fallbackUrl: String? = null,
        val duration: Int = 0
    )

    @Serializable
    private data class PipedSearchResponse(
        val items: List<PipedSearchItem> = emptyList()
    )

    @Serializable
    private data class PipedSearchItem(
        val url: String? = null,
        val title: String? = null,
        val uploaderName: String? = null,
        val duration: Int? = null,
        val views: Long? = null,
        val shortDescription: String? = null
    )

    // Gemini DTOs
    @Serializable
    private data class GeminiGenerateRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double = 0.25,
        val responseMimeType: String = "application/json"
    )

    @Serializable
    private data class GeminiGenerateResponse(
        val candidates: List<GeminiCandidate> = emptyList()
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null
    )

    @Serializable
    private data class AdaptiveNichePlanDto(
        val activeMetaDescription: String = "",
        val queries: List<String> = emptyList(),
        val tierOneEntities: List<String> = emptyList(),
        val outdatedBlacklist: List<String> = emptyList()
    )

    @Serializable
    private data class AiRerankedItemDto(
        val index: Int = 0,
        val cloutTier: String = "TOP_FANBASE",
        val conversionScore: Int = 85,
        val entityWhoIs: String = "",
        val whatsInside: String = ""
    )

    @Serializable
    private data class AiRerankResponseDto(
        val selected: List<AiRerankedItemDto> = emptyList()
    )

    private fun sanitizeQuery(rawQuery: String): String {
        return rawQuery
            .replace("#", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawQuery.trim() }
    }

    suspend fun huntByTags(
        queryTags: String,
        platform: HuntPlatform = HuntPlatform.YOUTUBE,
        sortOrder: ContentSortOrder = ContentSortOrder.RELEVANCE,
        period: SearchTimePeriod = SearchTimePeriod.MONTH,
        durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
        languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
        limitCount: Int = 6,
        minDurationSec: Int = durationMode.minSec,
        maxDurationSec: Int = durationMode.maxSec
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeQuery(queryTags)

        DiagnosticLogger.log(
            "HUNT",
            "Адаптивный поиск фан-базы: '$cleanQuery' (язык=${languageMode.name}, период=${period.name}, формат=${durationMode.name}, сорт=${sortOrder.name})"
        )

        // Stage 1: Build 100% adaptive Niche & Fanbase Plan (for ANY topic: CS, Standup, Podcasts, Anime, Cars, UFC, etc.):
        // - Determines current living meta with active fanbase (e.g. CS2 Premier/Major instead of dead CS 1.6)
        // - Identifies Tier-1 recognizable personalities/creators/shows that drive likes & comments
        // - Identifies dead/outdated trap keywords for this specific niche to ban
        // - Combines AI adaptive queries + live YouTube Autocomplete suggestions
        val nichePlan = buildAdaptiveFanbaseSearchPlan(
            userPrompt = cleanQuery,
            durationMode = durationMode,
            languageMode = languageMode
        )
        val searchQueries = nichePlan.queries.ifEmpty { listOf(cleanQuery) }

        DiagnosticLogger.log(
            "HUNT:META",
            "Актуальная мета ниши: ${nichePlan.activeMetaDescription.ifBlank { cleanQuery }} | Топ-триггеры фанбазы: ${nichePlan.tierOneEntities.joinToString(", ")} | Бан старья: ${nichePlan.outdatedBlacklist.joinToString(", ")}"
        )
        DiagnosticLogger.log("HUNT:QUERIES", "Адаптивные запросы (${searchQueries.size} шт.): ${searchQueries.joinToString(" | ")}")

        val seenIds = loadSeenIds()
        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            val perQueryMax = when {
                searchQueries.size >= 5 -> 10
                searchQueries.size >= 3 -> 14
                else -> 25
            }
            for (q in searchQueries) {
                rawPool.addAll(
                    searchYouTubeByTags(
                        queryTags = q,
                        nichePlan = nichePlan,
                        sortOrder = sortOrder,
                        period = period,
                        languageMode = languageMode,
                        appendShortsKeyword = durationMode.appendShortsKeyword,
                        minDurationSec = minDurationSec,
                        maxDurationSec = maxDurationSec,
                        maxFetchCount = perQueryMax
                    )
                )
            }
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.REDDIT) {
            rawPool.addAll(
                searchRedditVideosByTags(
                    queryTags = searchQueries.first(),
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        val deduplicatedPool = rawPool.distinctBy { it.id }

        var unseenCandidates = deduplicatedPool.filter { candidate ->
            candidate.id.isNotBlank() && !seenIds.contains(candidate.id)
        }

        if (unseenCandidates.isEmpty() && deduplicatedPool.isNotEmpty()) {
            DiagnosticLogger.log("HUNT", "Все найденные ролики уже показывались ранее — показываю заново")
            unseenCandidates = deduplicatedPool
        }

        val preSorted = when (sortOrder) {
            ContentSortOrder.POPULAR ->
                unseenCandidates.sortedByDescending { it.viewOrScoreCount }
            ContentSortOrder.NEWEST ->
                unseenCandidates.sortedByDescending { it.uploadDateYmd }
            ContentSortOrder.VIRAL_VELOCITY ->
                unseenCandidates.sortedByDescending { computeViralVelocityScore(it) }
            ContentSortOrder.RELEVANCE ->
                unseenCandidates.sortedByDescending { scoreCandidateRelevance(it, cleanQuery, nichePlan) }
        }

        // Stage 2: Clout & Popularity Inspector ("Ноунейм vs Топ Фан-База") + "Что внутри" summary
        val finalPicked = if (preSorted.isNotEmpty()) {
            rerankAndSummarizeViaGemini(
                userFreeformPrompt = cleanQuery,
                nichePlan = nichePlan,
                languageMode = languageMode,
                candidates = preSorted.take(24),
                limitCount = limitCount.coerceIn(1, 10)
            )
        } else {
            emptyList()
        }

        DiagnosticLogger.log("HUNT", "Итого отобрано кандидатов с проверкой популярности: ${finalPicked.size} из ${deduplicatedPool.size}")
        markCandidatesAsSeen(finalPicked.map { it.id })
        finalPicked
    }

    /**
     * Полностью адаптивный планировщик ниши и фан-базы (без зашитых списков):
     * Для ЛЮБОЙ темы пользователя (будь то КС, стендап, подкасты, аниме, UFC, авто или наука)
     * сам определяет:
     * 1) Какая версия/формат этой темы сейчас жива и имеет активную фан-базу (например, CS2 вместо мёртвого CS 1.6;
     *    современные стендап-разгоны и монологи о жизни вместо архивов Жванецкого/Петросяна).
     * 2) Какие конкретно медийные лица / комики / про-игроки / шоу в этой нише сейчас дают максимальную конверсию в лайки и комменты.
     * 3) Какие популярные смежные запросы сейчас реально ищут зрители на YouTube (через AI + живой YouTube Autocomplete).
     * 4) Какие слова-ловушки старья нужно заблокировать именно для этой темы.
     */
    private suspend fun buildAdaptiveFanbaseSearchPlan(
        userPrompt: String,
        durationMode: SearchDurationMode,
        languageMode: SearchLanguageMode
    ): AdaptiveNichePlanDto {
        val hl = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "en" else "ru"
        val liveAutocomplete = fetchYouTubeAutocompleteSuggestions(userPrompt, hl)

        val modeHint = when (durationMode) {
            SearchDurationMode.ORIGINALS_1_TO_6M ->
                "Ищем цельные оригинальные ролики/выступления/хаlайты 1–6 минут (не короткие чужие эдиты)."
            SearchDurationMode.SHORTS_ONLY ->
                "Ищем динамичные вертикальные Shorts / клипы до 75 секунд."
            SearchDurationMode.ANY_UP_TO_6M ->
                "Ищем вовлекающие ролики до 6 минут с высокой плотностью смысла/эмоций."
        }

        val langRule = when (languageMode) {
            SearchLanguageMode.RUSSIAN_ONLY ->
                "Генерируй запросы и имена СТРОГО НА РУССКОМ ЯЗЫКЕ (для русскоязычного YouTube)."
            SearchLanguageMode.ENGLISH_FOR_DUB ->
                "Генерируй запросы и имена СТРОГО НА АНГЛИЙСКОМ ЯЗЫКЕ (даже если запрос написан по-русски!), чтобы найти виральные англоязычные исходники с огромной фан-базой под русскую переозвучку."
            SearchLanguageMode.ANY_LANGUAGE ->
                "Сгенерируй 3 запроса на русском и 2 на английском."
        }

        val suggestContext = if (liveAutocomplete.isNotEmpty()) {
            "Живые автоподсказки YouTube по этой теме прямо сейчас: ${liveAutocomplete.joinToString(", ")}"
        } else {
            ""
        }

        val prompt = """
            Ты — аналитик вирального контента и фан-сообществ YouTube/Shorts/Reels (2025–2026 год).
            Пользователь ввёл тему поиска: "$userPrompt"
            Формат: $modeHint
            Язык поиска: $langRule
            $suggestContext

            Твоя главная цель — найти такой контент по этой теме, у которого ПРЯМО СЕЙЧАС есть живая, активная фан-база, чтобы ролики давали максимум просмотров, лайков, комментариев и конверсии.
            Проанализируй нишу АДАПТИВНО:
            1. "activeMetaDescription": Какая актуальная версия/мета этой ниши сейчас жива и собирает трафик?
               - Например, если запрос про "КС / КСку" — актуальная мета это СТРОГО CS2 (Counter-Strike 2, Premier, Faceit, Major, про-сцена), а НЕ мёртвый CS 1.6 или CS:Source.
               - Если запрос про "стендап рассказы / диалоги" — актуальная мета это современные стендап-монологи, стендапы о жизни, стендап истории и живое общение с залом (импровизация), а НЕ старая советская/телевизионная эстрада.
               - Примени этот же принцип к ЛЮБОЙ теме пользователя.
            2. "tierOneEntities": Назови 6–8 самых популярных прямо сейчас персон / комиков / игроков / авторов / шоу / команд в этой нише, у которых огромная фан-база (чьё имя или лицо в кадре гарантирует лайки и комментарии).
            3. "outdatedBlacklist": Укажи 6–10 слов-паразитов устаревшего/мёртвого контента именно для этой ниши, которые нужно жёстко отсечь (например для КС: ["1.6", "кс 1.6", "cs 1.6", "source", "condition zero"]; для стендапа: ["жванецкий", "петросян", "аншлаг", "одесский юмор", "услышано в одессе", "смехопанорама", "кривое зеркало"]).
            4. "queries": Составь 5 поисковых запросов для YouTube (по 3–6 слов каждый), которые:
               - Включают популярные смежные формулировки, которые реально ищут фанаты этой темы (например для стендапа: "стендап монологи", "стендапы о жизни", "стендап истории", "стендап импровизация с залом"; для игр/спорта/науки — их главные хайповые форматы);
               - Включают имена/названия из "tierOneEntities";
               - Отражают только актуальную мету 2025–2026 года.

            Верни СТРОГО валидный JSON без markdown-обёрток:
            {
              "activeMetaDescription": "...",
              "tierOneEntities": ["...", "..."],
              "outdatedBlacklist": ["...", "..."],
              "queries": ["запрос 1", "запрос 2", "запрос 3", "запрос 4", "запрос 5"]
            }
        """.trimIndent()

        try {
            val rawJson = callGeminiJson(configStore.getGeminiApiKey(), prompt)
            if (!rawJson.isNullOrBlank()) {
                val parsed = jsonFormat.decodeFromString<AdaptiveNichePlanDto>(rawJson)
                val mergedQueries = linkedSetOf<String>()
                for (q in parsed.queries) {
                    val clean = sanitizeQuery(q)
                    if (clean.isNotBlank()) mergedQueries.add(clean)
                }
                for (sug in liveAutocomplete) {
                    if (mergedQueries.size >= 6) break
                    val cleanSug = sanitizeQuery(sug)
                    if (cleanSug.isNotBlank() && !containsBlacklistedTerm(cleanSug, parsed.outdatedBlacklist)) {
                        mergedQueries.add(cleanSug)
                    }
                }
                if (mergedQueries.isNotEmpty()) {
                    return parsed.copy(
                        queries = mergedQueries.take(5),
                        outdatedBlacklist = (parsed.outdatedBlacklist + universalDeadContentBlacklist(userPrompt)).distinct()
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:PLAN", "Предупреждение AI-планировщика ниши: ${e.message?.take(80)}")
        }

        // Universal adaptive fallback if AI is offline: uses live YouTube Autocomplete + smart version normalization
        val fallbackBlacklist = universalDeadContentBlacklist(userPrompt)
        val normalizedBase = normalizeLegacyNicheTerms(userPrompt, languageMode)
        val fallbackQueries = linkedSetOf<String>()
        fallbackQueries.add(normalizedBase)
        for (sug in liveAutocomplete) {
            if (!containsBlacklistedTerm(sug, fallbackBlacklist)) {
                fallbackQueries.add(sanitizeQuery(sug))
            }
        }
        // Fetch second-hop autocomplete from the first suggestion to discover adjacent popular queries automatically
        if (liveAutocomplete.isNotEmpty()) {
            val secondHop = fetchYouTubeAutocompleteSuggestions(liveAutocomplete.first(), hl)
            for (sug2 in secondHop) {
                if (fallbackQueries.size >= 5) break
                if (!containsBlacklistedTerm(sug2, fallbackBlacklist)) {
                    fallbackQueries.add(sanitizeQuery(sug2))
                }
            }
        }

        return AdaptiveNichePlanDto(
            activeMetaDescription = normalizedBase,
            queries = fallbackQueries.filter { it.isNotBlank() }.take(5),
            tierOneEntities = emptyList(),
            outdatedBlacklist = fallbackBlacklist
        )
    }

    /**
     * Запрашивает живые популярные похожие запросы из официального автодополнения YouTube.
     */
    private suspend fun fetchYouTubeAutocompleteSuggestions(seedQuery: String, hl: String = "ru"): List<String> {
        return try {
            val httpResp = httpClient.get("https://suggestqueries.google.com/complete/search") {
                parameter("client", "firefox")
                parameter("ds", "yt")
                parameter("hl", hl)
                parameter("q", seedQuery)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            if (!httpResp.status.isSuccess()) return emptyList()
            val raw = httpResp.bodyAsText()
            val suggestionsBlock = raw.substringAfter("[", "").substringAfter("[", "").substringBefore("]", "")
            val parsed = Regex(""""([^"]+)"""").findAll(suggestionsBlock)
                .map { it.groupValues[1].trim() }
                .filter { it.length in 4..65 && !it.equals(seedQuery, ignoreCase = true) }
                .toList()
            if (parsed.isNotEmpty()) {
                DiagnosticLogger.log("YT-SUGGEST", "Живые популярные запросы YouTube: ${parsed.take(4).joinToString(" | ")}")
            }
            parsed.take(4)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Stage 2: Проверка медийности/популярности автора или комика («Ноунейм / Стрём» vs «Топ Фан-База / Конверсия»)
     * + отбор лучших кандидатов и генерация выжимки «Что внутри».
     */
    private suspend fun rerankAndSummarizeViaGemini(
        userFreeformPrompt: String,
        nichePlan: AdaptiveNichePlanDto,
        languageMode: SearchLanguageMode,
        candidates: List<DiscoveredMediaCandidate>,
        limitCount: Int
    ): List<DiscoveredMediaCandidate> {
        if (candidates.isEmpty()) return emptyList()

        return try {
            val catalogText = candidates.mapIndexed { idx, c ->
                val descPart = if (c.descriptionSnippet.isNotBlank()) " | desc: ${c.descriptionSnippet.take(150)}" else ""
                "[$idx] title=\"${c.title}\" | channel=\"${c.author}\" | duration=${c.durationSec}s | views=${c.viewOrScoreCount}$descPart"
            }.joinToString("\n")

            val langNote = when (languageMode) {
                SearchLanguageMode.RUSSIAN_ONLY -> "Выбирай ТОЛЬКО видео на русском языке (отсекай украинские и любые иностранные ролики)."
                SearchLanguageMode.ENGLISH_FOR_DUB -> "Выбирай ТОЛЬКО англоязычные видео с высокой динамикой и фан-базой, идеальные под русскую переозвучку."
                SearchLanguageMode.ANY_LANGUAGE -> "Можно выбирать видео на русском или английском языке."
            }

            val prompt = """
                Ты — главный продюсер вирального контента и скаут фан-базы.
                Тема пользователя: "$userFreeformPrompt"
                Актуальная мета ниши: "${nichePlan.activeMetaDescription}"
                Топовые медийные лица/триггеры в этой нише: ${nichePlan.tierOneEntities.joinToString(", ")}
                Правило языка: $langNote

                Вот список найденных видео-кандидатов (${candidates.size} шт.):
                $catalogText

                Твоя задача — проверить КАЖДОГО кандидата на наличие фан-базы и медийного веса (кто этот комик / игрок / автор / персонаж, популярен ли он или это никому не известный ноунейм / устаревший кринж):
                1. Отсей откровенных ноунеймов с нулевой медийностью, старые версии игр (например CS 1.6 вместо CS2) и архивный нафталин.
                2. Выбери до $limitCount лучших роликов, которые дадут максимум просмотров, лайков и конверсии.
                3. Для каждого выбранного ролика укажи:
                   - "index": номер кандидата из списка.
                   - "cloutTier":
                     * "TOP_FANBASE" — если в ролике известный комик/игрок/медийная личность/крупное шоу или горячий мейнстрим-тренд с мощной фан-базой.
                     * "RISING_NICHE" — если автор/тема живая, набирает хорошие просмотры и цепляет аудиторию, хоть и не звезда первой величины.
                     * "NONAME_LOW" — если это малоизвестный локальный автор без фан-базы (выбирай только если других нет).
                   - "conversionScore": оценка потенциала конверсии в лайки/комменты от 1 до 100.
                   - "entityWhoIs": Честная справка (до 95 символов) — КТО это в кадре / что за канал или шоу, насколько он популярен и почему у него есть (или нет) фанаты.
                   - "whatsInside": Выжимка сути ролика на русском языке (до 130 символов) — о чём конкретно речь/шутка/момент внутри.

                Верни СТРОГО валидный JSON:
                {
                  "selected": [
                    {
                      "index": 0,
                      "cloutTier": "TOP_FANBASE",
                      "conversionScore": 92,
                      "entityWhoIs": "Популярный стендап-комик (резидент Outside Stand Up), огромная живая фан-база",
                      "whatsInside": "Разгоняет жизненную историю про..."
                    }
                  ]
                }
            """.trimIndent()

            val rawJson = callGeminiJson(configStore.getGeminiApiKey(), prompt)
            if (!rawJson.isNullOrBlank()) {
                val parsed = jsonFormat.decodeFromString<AiRerankResponseDto>(rawJson)
                val result = mutableListOf<DiscoveredMediaCandidate>()

                for (sel in parsed.selected) {
                    if (sel.index in candidates.indices) {
                        val orig = candidates[sel.index]
                        val tierBadge = when (sel.cloutTier.uppercase(Locale.US)) {
                            "TOP_FANBASE" -> "🔥 ТОП ФАН-БАЗА (Высокая конверсия)"
                            "RISING_NICHE" -> "📈 ЖИВАЯ ТЕМА / СРЕДНЯЯ МЕДИЙКА"
                            else -> "⚠️ ЛОКАЛЬНЫЙ / МАЛОИЗВЕСТНЫЙ"
                        }
                        val score = sel.conversionScore.coerceIn(10, 99)
                        val summary = sel.whatsInside.trim().ifBlank { buildLocalWhatsInsideFallback(orig, languageMode) }
                        val entityNote = sel.entityWhoIs.trim().ifBlank {
                            estimateLocalCloutNote(orig, nichePlan)
                        }
                        result.add(
                            orig.copy(
                                aiWhatsInside = summary,
                                cloutStatusLabel = "$tierBadge • 🎯 Конверсия: $score%",
                                entityPopularityNote = entityNote,
                                conversionScore = score
                            )
                        )
                    }
                }

                if (result.isNotEmpty()) {
                    return result
                        .sortedByDescending { it.conversionScore }
                        .distinctBy { it.id }
                        .take(limitCount)
                }
            }
            candidates.take(limitCount).map { enrichWithLocalCloutFallback(it, nichePlan, languageMode) }
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:AI-RERANK", "Предупреждение AI-ранжирования: ${e.message?.take(100)}")
            candidates.take(limitCount).map { enrichWithLocalCloutFallback(it, nichePlan, languageMode) }
        }
    }

    private fun enrichWithLocalCloutFallback(
        c: DiscoveredMediaCandidate,
        nichePlan: AdaptiveNichePlanDto,
        languageMode: SearchLanguageMode
    ): DiscoveredMediaCandidate {
        val combined = "${c.title} ${c.author}".lowercase(Locale.getDefault())
        val matchedEntity = nichePlan.tierOneEntities.firstOrNull { ent ->
            ent.length >= 3 && combined.contains(ent.lowercase(Locale.getDefault()))
        }
        val views = c.viewOrScoreCount
        val (badge, score) = when {
            matchedEntity != null || views >= 100_000L -> "🔥 ТОП ФАН-БАЗА (Высокая конверсия)" to 90
            views >= 20_000L -> "📈 ЖИВАЯ ТЕМА / ХОРОШИЙ ОХВАТ" to 76
            views >= 5_000L -> "📊 СРЕДНЯЯ МЕДИЙКА" to 62
            else -> "⚠️ МАЛОИЗВЕСТНЫЙ / НОУНЕЙМ" to 40
        }
        return c.copy(
            aiWhatsInside = buildLocalWhatsInsideFallback(c, languageMode),
            cloutStatusLabel = "$badge • 🎯 Конверсия: $score%",
            entityPopularityNote = estimateLocalCloutNote(c, nichePlan),
            conversionScore = score
        )
    }

    private fun estimateLocalCloutNote(c: DiscoveredMediaCandidate, nichePlan: AdaptiveNichePlanDto): String {
        val combined = "${c.title} ${c.author}".lowercase(Locale.getDefault())
        val matchedEntity = nichePlan.tierOneEntities.firstOrNull { ent ->
            ent.length >= 3 && combined.contains(ent.lowercase(Locale.getDefault()))
        }
        return when {
            matchedEntity != null -> "Узнаваемый триггер ниши («$matchedEntity») на канале ${c.author} — высокая лояльность фанатов"
            c.viewOrScoreCount >= 100_000L -> "Крупный охват (${c.viewOrScoreCount} просм.) у канала «${c.author}» — проверенная фан-база"
            c.viewOrScoreCount >= 15_000L -> "Активная аудитория канала «${c.author}» (${c.viewOrScoreCount} просм.)"
            else -> "Нишевый автор «${c.author}» (${c.viewOrScoreCount} просм.) — проверь момент через «Что внутри»"
        }
    }

    private fun buildLocalWhatsInsideFallback(c: DiscoveredMediaCandidate, languageMode: SearchLanguageMode): String {
        val langTag = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "[EN под переозвучку] " else ""
        val snippet = c.descriptionSnippet
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(110)
        return if (snippet.length >= 15) {
            "$langTag${c.author}: $snippet"
        } else {
            "${langTag}Ролик на канале «${c.author}» (${c.durationSec} сек, ${c.viewOrScoreCount} просм.) — жми «🧠 Что внутри» для разбора по субтитрам с таймкодами"
        }
    }

    /**
     * Stage 3: Deep "Что внутри" inspection of a single video by fetching its YouTube subtitles
     * (without downloading the video!) + full description and asking Gemini for a summary + exact timecodes.
     */
    suspend fun inspectVideoInsideViaSubtitles(
        videoUrl: String,
        videoTitle: String,
        userTopic: String
    ): VideoInsideDigest = withContext(Dispatchers.IO) {
        DiagnosticLogger.log("WHATS-INSIDE", "Анализ «Что внутри» (субтитры + таймкоды) для $videoUrl")

        var fullDescription = ""
        var durationSec = 60
        try {
            val infoReq = YoutubeDLRequest(videoUrl).apply {
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }
            val info = YoutubeDL.getInstance().getInfo(infoReq)
            fullDescription = info.description ?: ""
            durationSec = info.duration
        } catch (_: Exception) {
        }

        val timestampedTranscript = fetchTimestampedVttTranscript(videoUrl)
        val hasSubs = timestampedTranscript.isNotBlank()
        DiagnosticLogger.log(
            "WHATS-INSIDE",
            "Субтитры получены: $hasSubs (${timestampedTranscript.length} симв.), описание: ${fullDescription.length} симв."
        )

        try {
            val sourceContext = if (hasSubs) {
                "ПОЛНАЯ РАСШИФРОВКА СУБТИТРОВ С ТАЙМКОДАМИ:\n${timestampedTranscript.take(12000)}"
            } else {
                "Субтитры отсутствуют. ОПИСАНИЕ И МЕТАДАННЫЕ РОЛИКА:\n${fullDescription.take(4000)}"
            }

            val prompt = """
                Ты — главный редактор видеоконтента. Пользователь ищет материал по теме: "$userTopic".
                Название ролика: "$videoTitle" (общая длительность: ${durationSec} сек).

                $sourceContext

                Сделай честную выжимку «ЧТО ВНУТРИ» на русском языке (даже если видео на английском — переведи суть на русский!):
                1. "overallSummary": 2–3 предложения, о чём конкретно говорится в видео, какие шутки/тезисы внутри и подходит ли оно под запрос пользователя (без воды).
                2. "keyTopics": массив из 3–4 ключевых тем/тегов внутри видео.
                3. "moments": выдели от 1 до 3 лучших цельных фрагментов (от 15 до 65 секунд каждый), где мысль или шутка начинается и полностью заканчивается (чтобы не обрезать фразу!). Укажи точные секунды startSec и endSec.

                Верни строго JSON:
                {
                  "overallSummary": "...",
                  "keyTopics": ["...", "..."],
                  "moments": [
                    {"startSec": 12, "endSec": 54, "title": "Короткий заголовок момента", "summary": "О чём именно этот кусок и чем заканчивается фраза"}
                  ],
                  "hasTranscript": $hasSubs
                }
            """.trimIndent()

            val rawJson = callGeminiJson(configStore.getGeminiApiKey(), prompt)
            if (!rawJson.isNullOrBlank()) {
                val digest = jsonFormat.decodeFromString<VideoInsideDigest>(rawJson)
                return@withContext digest.copy(hasTranscript = hasSubs)
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("WHATS-INSIDE", "Ошибка анализа «Что внутри»", e)
        }

        val fallbackText = when {
            hasSubs -> "Расшифровка речи из субтитров:\n" + timestampedTranscript.take(650)
            fullDescription.isNotBlank() -> fullDescription.take(450)
            else -> "Ролик «$videoTitle» ($durationSec сек)."
        }

        VideoInsideDigest(
            overallSummary = fallbackText,
            moments = listOf(
                VideoHighlightMoment(
                    startSec = 0,
                    endSec = durationSec.coerceIn(15, 60),
                    title = "Первая часть ролика",
                    summary = "Фрагмент 0:00 – ${durationSec.coerceIn(15, 60)}с"
                ),
                VideoHighlightMoment(
                    startSec = (durationSec / 3).coerceAtLeast(10),
                    endSec = ((durationSec / 3) + 50).coerceAtMost(durationSec),
                    title = "Основной разгон в середине",
                    summary = "Цельный кусок из середины выступления"
                )
            ),
            hasTranscript = hasSubs
        )
    }

    private fun fetchTimestampedVttTranscript(videoUrl: String): String {
        val baseName = "inside_vtt_${System.currentTimeMillis()}"
        val outTemplate = File(stagingDir, "$baseName.%(ext)s").absolutePath
        return try {
            val req = YoutubeDLRequest(videoUrl).apply {
                addOption("--skip-download")
                addOption("--write-auto-sub")
                addOption("--write-sub")
                addOption("--sub-langs", "ru.*,ru-orig,ru,en.*,en")
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(req)

            val vttFile = stagingDir.listFiles { f ->
                f.name.startsWith(baseName) && f.extension.equals("vtt", ignoreCase = true)
            }?.firstOrNull() ?: return ""

            val rawVtt = vttFile.readText()
            vttFile.delete()
            compressVttToTimestampedLines(rawVtt)
        } catch (_: Exception) {
            ""
        } finally {
            stagingDir.listFiles { f -> f.name.startsWith(baseName) }?.forEach { it.delete() }
        }
    }

    private fun compressVttToTimestampedLines(rawVtt: String): String {
        val lines = rawVtt.lines()
        val cueHeaderRegex = Regex("""(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->""")
        val htmlTagStripRegex = Regex("""<[^>]+>""")
        val sb = StringBuilder()
        var lastLineText = ""
        var currentTimeLabel = ""

        for (line in lines) {
            val trimmed = line.trim()
            val m = cueHeaderRegex.find(trimmed)
            if (m != null) {
                currentTimeLabel = m.groupValues[1].substringBefore(".")
                continue
            }
            if (trimmed.isBlank() || trimmed.startsWith("WEBVTT") || trimmed.startsWith("Kind:") || trimmed.startsWith("Language:")) {
                continue
            }
            val clean = trimmed.replace(htmlTagStripRegex, "").replace(Regex("""\s+"""), " ").trim()
            if (clean.isNotBlank() && clean != lastLineText) {
                sb.append("[").append(currentTimeLabel).append("] ").append(clean).append("\n")
                lastLineText = clean
            }
        }
        return sb.toString()
    }

    /**
     * Вызов AI с поддержкой нескольких моделей Gemini (2.5-flash -> 2.0-flash) + резервного JSON AI-шлюза.
     */
    private suspend fun callGeminiJson(apiKey: String, prompt: String): String? {
        if (apiKey.isNotBlank()) {
            val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
            for (modelName in models) {
                try {
                    val endpoint =
                        "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val payload = GeminiGenerateRequest(
                        contents = listOf(
                            GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                        ),
                        generationConfig = GeminiGenerationConfig(
                            temperature = 0.25,
                            responseMimeType = "application/json"
                        )
                    )
                    val httpResp = httpClient.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                    val rawBody = httpResp.bodyAsText()
                    if (httpResp.status.isSuccess()) {
                        val parsed = jsonFormat.decodeFromString<GeminiGenerateResponse>(rawBody)
                        val text = parsed.candidates.firstOrNull()
                            ?.content?.parts?.firstOrNull()?.text
                            ?.replace("```json", "")
                            ?.replace("```", "")
                            ?.trim()
                        if (!text.isNullOrBlank()) {
                            val firstBrace = text.indexOf('{')
                            val lastBrace = text.lastIndexOf('}')
                            return if (firstBrace >= 0 && lastBrace > firstBrace) {
                                text.substring(firstBrace, lastBrace + 1)
                            } else {
                                text
                            }
                        }
                    } else {
                        DiagnosticLogger.log(
                            "AI-CALL",
                            "Модель $modelName вернула HTTP ${httpResp.status.value} -> пробуем следующую модель..."
                        )
                    }
                } catch (e: Exception) {
                    DiagnosticLogger.log("AI-CALL", "Предупреждение вызова $modelName: ${e.message?.take(60)}")
                }
            }
        }

        // Резервный AI-шлюз на случай лимитов квоты или отсутствия ключа
        return try {
            val fallbackBody = kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive("openai"))
                put("jsonMode", kotlinx.serialization.json.JsonPrimitive(true))
                put(
                    "messages",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("system"))
                                put("content", kotlinx.serialization.json.JsonPrimitive("You are a helpful JSON API. Reply strictly with valid JSON only, no markdown fences."))
                            }
                        )
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(prompt))
                            }
                        )
                    }
                )
            }
            val resp = httpClient.post("https://text.pollinations.ai/") {
                contentType(ContentType.Application.Json)
                setBody(fallbackBody.toString())
            }
            if (!resp.status.isSuccess()) return null
            val raw = resp.bodyAsText()
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val firstBrace = raw.indexOf('{')
            val lastBrace = raw.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                raw.substring(firstBrace, lastBrace + 1)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun containsBlacklistedTerm(text: String, blacklist: List<String>): Boolean {
        if (blacklist.isEmpty()) return false
        val lower = text.lowercase(Locale.getDefault())
        return blacklist.any { term ->
            val t = term.trim().lowercase(Locale.getDefault())
            t.length >= 2 && lower.contains(t)
        }
    }

    /**
     * Базовый фильтр мёртвых версий игр (например CS 1.6 вместо CS2, если пользователь сам не просил 1.6)
     * и старой телевизионной эстрады, дополняющий динамический AI-список.
     */
    private fun universalDeadContentBlacklist(userPrompt: String): List<String> {
        val lowerPrompt = userPrompt.lowercase(Locale.getDefault())
        val list = mutableListOf(
            "жванецкий", "петросян", "аншлаг", "одесский юмор", "услышано в одессе",
            "анекдот по поводу", "кривое зеркало", "смехопанорама", "шифрин", "винокур"
        )
        if (!lowerPrompt.contains("1.6") && !lowerPrompt.contains("source")) {
            list.addAll(listOf("cs 1.6", "кс 1.6", "counter-strike 1.6", "cs source", "кс соурс", "condition zero"))
        }
        if (!lowerPrompt.contains("dota 1") && !lowerPrompt.contains("дота 1")) {
            list.addAll(listOf("dota 1", "дота 1", "allstars"))
        }
        return list
    }

    /**
     * Нормализует разговорные названия ниш в актуальную современную мету (например "по КСке" -> "CS2").
     */
    private fun normalizeLegacyNicheTerms(userPrompt: String, languageMode: SearchLanguageMode): String {
        var normalized = userPrompt
        val lower = normalized.lowercase(Locale.getDefault())
        if ((lower.contains("кск") || Regex("""\bкс\b""").containsMatchIn(lower) || Regex("""\bcs\b""").containsMatchIn(lower)) &&
            !lower.contains("cs2") && !lower.contains("кс2") && !lower.contains("1.6")
        ) {
            normalized = normalized
                .replace(Regex("""(?i)по\s+кске"""), "CS2")
                .replace(Regex("""(?i)в\s+кске"""), "CS2")
                .replace(Regex("""(?i)\bкска\b"""), "CS2")
                .replace(Regex("""(?i)\bкс\b"""), "CS2")
                .replace(Regex("""(?i)\bcs\b"""), "CS2")
        }
        if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB && !normalized.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            normalized = "$normalized english"
        }
        return sanitizeQuery(normalized)
    }

    /**
     * Проверяет язык ролика по заголовку и автору:
     * - RUSSIAN_ONLY: требует русскую кириллицу, отсекает украинские буквы (і, ї, є, ґ), казахские и азиатские/арабские скрипты.
     * - ENGLISH_FOR_DUB: требует латиницу без кириллицы, чтобы находить чистые англоязычные исходники под переозвучку.
     */
    private fun matchesLanguageFilter(
        title: String,
        author: String,
        languageMode: SearchLanguageMode
    ): Boolean {
        val combined = "$title $author"
        val hasCyrillic = Regex("""[а-яА-ЯёЁ]""").containsMatchIn(title)
        val hasUkrainianSpecific = Regex("""[іїєґІЇЄҐ]""").containsMatchIn(combined)
        val hasOtherCyrillicSpecific = Regex("""[әңғүұқөһӘҢҒҮҰҚӨҺ]""").containsMatchIn(combined)
        val hasNonLatinNonCyrillicScript = Regex("""[\u0600-\u06FF\u0900-\u097F\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]""").containsMatchIn(combined)

        if (hasNonLatinNonCyrillicScript) return false

        return when (languageMode) {
            SearchLanguageMode.RUSSIAN_ONLY -> {
                hasCyrillic && !hasUkrainianSpecific && !hasOtherCyrillicSpecific
            }
            SearchLanguageMode.ENGLISH_FOR_DUB -> {
                val hasLatin = Regex("""[a-zA-Z]{3,}""").containsMatchIn(title)
                hasLatin && !hasCyrillic && !hasUkrainianSpecific
            }
            SearchLanguageMode.ANY_LANGUAGE -> {
                !hasUkrainianSpecific && !hasOtherCyrillicSpecific
            }
        }
    }

    private fun scoreCandidateRelevance(
        c: DiscoveredMediaCandidate,
        userQuery: String,
        nichePlan: AdaptiveNichePlanDto
    ): Double {
        val lowerTitle = c.title.lowercase(Locale.getDefault())
        val lowerAuthor = c.author.lowercase(Locale.getDefault())
        var score = kotlin.math.ln((c.viewOrScoreCount.coerceAtLeast(100L)).toDouble()) * 12.0

        // Адаптивный бонус за попадание в главных медийных персон / триггеров фан-базы этой ниши
        for (entity in nichePlan.tierOneEntities) {
            val eLow = entity.trim().lowercase(Locale.getDefault())
            if (eLow.length >= 3 && (lowerTitle.contains(eLow) || lowerAuthor.contains(eLow))) {
                score += 75.0
            }
        }

        val queryWords = userQuery.lowercase(Locale.getDefault())
            .split(Regex("""\s+"""))
            .filter { it.length >= 4 }
        for (w in queryWords) {
            val stem = w.take(5)
            if (lowerTitle.contains(stem)) score += 25.0
        }
        return score
    }

    private suspend fun searchYouTubeByTags(
        queryTags: String,
        nichePlan: AdaptiveNichePlanDto = AdaptiveNichePlanDto(),
        sortOrder: ContentSortOrder = ContentSortOrder.RELEVANCE,
        period: SearchTimePeriod = SearchTimePeriod.MONTH,
        languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
        appendShortsKeyword: Boolean = false,
        minDurationSec: Int = 8,
        maxDurationSec: Int = 360,
        maxFetchCount: Int = 20
    ): List<DiscoveredMediaCandidate> {
        val finalQuery = if (appendShortsKeyword && !queryTags.lowercase().contains("short")) {
            "$queryTags shorts"
        } else {
            queryTags
        }

        // Порог просмотров, чтобы мёртвые ролики от ноунеймов (225 просмотров) не пролезали в выдачу
        val minRequiredViews = when (period) {
            SearchTimePeriod.DAY -> 200L
            SearchTimePeriod.WEEK -> 900L
            else -> 3000L
        }

        val effectiveBlacklist = (nichePlan.outdatedBlacklist + universalDeadContentBlacklist(queryTags)).distinct()
        val results = mutableListOf<DiscoveredMediaCandidate>()

        try {
            // Серверный фильтр даты загрузки YouTube (sp=):
            // EgQIAhAB = За 24 часа, EgQIAxAB = За неделю, EgQIBBAB = За месяц, EgQIBRAB = За год
            val spParam = when {
                period == SearchTimePeriod.DAY -> "EgQIAhAB"
                period == SearchTimePeriod.WEEK -> "EgQIAxAB"
                period == SearchTimePeriod.MONTH -> "EgQIBBAB"
                period == SearchTimePeriod.YEAR -> "EgQIBRAB"
                sortOrder == ContentSortOrder.NEWEST -> "CAI%3D"
                else -> null
            }

            val encodedQ = java.net.URLEncoder.encode(finalQuery, "UTF-8")
            val searchTarget = if (spParam != null) {
                "https://www.youtube.com/results?search_query=$encodedQ&sp=$spParam"
            } else {
                "ytsearch$maxFetchCount:$finalQuery"
            }

            val langCode = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "en" else "ru"

            val request = YoutubeDLRequest(searchTarget).apply {
                addOption("--flat-playlist")
                addOption("--playlist-end", maxFetchCount.toString())
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--ignore-errors")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                addOption("--extractor-args", "youtube:lang=$langCode")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }

            for (line in lines) {
                try {
                    val lowerLine = line.lowercase(Locale.getDefault())
                    // Жёсткий контроль свежести по сырому ответу YouTube
                    if (period == SearchTimePeriod.DAY || period == SearchTimePeriod.WEEK || period == SearchTimePeriod.MONTH) {
                        if (lowerLine.contains("years ago") || lowerLine.contains("year ago") ||
                            lowerLine.contains("лет назад") || lowerLine.contains("года назад") || lowerLine.contains("год назад")
                        ) {
                            continue
                        }
                    }
                    if (period == SearchTimePeriod.DAY || period == SearchTimePeriod.WEEK) {
                        if (lowerLine.contains("months ago") || lowerLine.contains("month ago") ||
                            lowerLine.contains("месяцев назад") || lowerLine.contains("месяца назад") || lowerLine.contains("месяц назад")
                        ) {
                            continue
                        }
                    }

                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    if (item.id.isBlank() || item.id.length != 11) continue

                    val title = item.title.ifBlank { "YouTube #${item.id}" }
                    val author = item.uploader ?: item.channel ?: "YouTube"
                    val views = item.viewCount ?: 0L

                    // 1. Отсев мёртвых роликов с мизерными просмотрами
                    if (views in 1 until minRequiredViews) continue

                    // 2. Адаптивный отсев устаревших версий (например CS 1.6) и старой эстрады
                    if (containsBlacklistedTerm("$title $author", effectiveBlacklist)) continue

                    // 3. Проверка языка (🇷🇺 Только Русский vs 🇺🇸 Английский под переозвучку)
                    if (!matchesLanguageFilter(title, author, languageMode)) continue

                    val rawUrl = item.webpageUrl ?: item.url ?: ""
                    val resolvedUrl = when {
                        rawUrl.startsWith("http") -> rawUrl
                        else -> "https://www.youtube.com/watch?v=${item.id}"
                    }

                    val dur = item.duration?.toInt() ?: 60
                    if (dur in minDurationSec..maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "yt_${item.id}",
                                url = resolvedUrl,
                                title = title,
                                author = author,
                                platform = HuntPlatform.YOUTUBE,
                                durationSec = dur,
                                viewOrScoreCount = if (views > 0L) views else 5000L,
                                uploadDateYmd = item.uploadDate ?: "",
                                descriptionSnippet = item.description?.take(200) ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            DiagnosticLogger.log("HUNT:YT", "YouTube нашёл ${results.size} подходящих роликов по запросу '$finalQuery' (период=${period.name}, язык=${languageMode.name})")

            // Если за узкий период (например за месяц) по конкретному имени 0 роликов — расширяем до года, но по-прежнему баним многолетнее старьё и blacklist!
            if (results.isEmpty() && (period == SearchTimePeriod.DAY || period == SearchTimePeriod.WEEK || period == SearchTimePeriod.MONTH)) {
                val fallbackUrl = "https://www.youtube.com/results?search_query=$encodedQ&sp=EgQIBRAB"
                val fbReq = YoutubeDLRequest(fallbackUrl).apply {
                    addOption("--flat-playlist")
                    addOption("--playlist-end", "15")
                    addOption("--dump-json")
                    addOption("--skip-download")
                    addOption("--ignore-errors")
                    addOption("--no-check-certificates")
                    addOption("--extractor-args", "youtube:lang=$langCode")
                }
                val fbLines = YoutubeDL.getInstance().execute(fbReq).out.lines().filter { it.trim().startsWith("{") }
                for (line in fbLines) {
                    try {
                        val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                        if (item.id.length != 11) continue
                        val title = item.title
                        val author = item.uploader ?: item.channel ?: "YouTube"
                        val views = item.viewCount ?: 0L
                        if (views in 1 until 3500L) continue
                        if (containsBlacklistedTerm("$title $author", effectiveBlacklist)) continue
                        if (!matchesLanguageFilter(title, author, languageMode)) continue
                        val dur = item.duration?.toInt() ?: 60
                        if (dur in minDurationSec..maxDurationSec) {
                            results.add(
                                DiscoveredMediaCandidate(
                                    id = "yt_${item.id}",
                                    url = "https://www.youtube.com/watch?v=${item.id}",
                                    title = title,
                                    author = author,
                                    platform = HuntPlatform.YOUTUBE,
                                    durationSec = dur,
                                    viewOrScoreCount = if (views > 0L) views else 5000L,
                                    uploadDateYmd = item.uploadDate ?: "",
                                    descriptionSnippet = item.description?.take(200) ?: ""
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("HUNT:YT", "Ошибка поиска YouTube (yt-dlp) для '$finalQuery'", e)
        }

        if (results.isEmpty()) {
            val pipedMirrors = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.drgns.space"
            )
            for (mirror in pipedMirrors) {
                try {
                    val httpResp = httpClient.get("$mirror/search") {
                        parameter("q", finalQuery)
                        parameter("filter", "videos")
                        header(HttpHeaders.UserAgent, "PocketMediaForge/1.2")
                    }
                    if (!httpResp.status.isSuccess()) continue
                    val rawJson = httpResp.bodyAsText()
                    if (!rawJson.trimStart().startsWith("{")) continue

                    val parsed = jsonFormat.decodeFromString<PipedSearchResponse>(rawJson)
                    for (item in parsed.items) {
                        val relUrl = item.url ?: continue
                        val vidId = relUrl.substringAfter("v=", "").take(11)
                        if (vidId.length != 11) continue
                        val title = item.title ?: continue
                        val author = item.uploaderName ?: "YouTube"
                        val views = item.views ?: 0L
                        if (views in 1 until minRequiredViews) continue
                        if (containsBlacklistedTerm("$title $author", effectiveBlacklist)) continue
                        if (!matchesLanguageFilter(title, author, languageMode)) continue
                        val dur = item.duration ?: 45
                        if (dur in minDurationSec..maxDurationSec) {
                            results.add(
                                DiscoveredMediaCandidate(
                                    id = "yt_$vidId",
                                    url = "https://www.youtube.com/watch?v=$vidId",
                                    title = title,
                                    author = author,
                                    platform = HuntPlatform.YOUTUBE,
                                    durationSec = dur,
                                    viewOrScoreCount = if (views > 0L) views else 5000L,
                                    descriptionSnippet = item.shortDescription ?: ""
                                )
                            )
                        }
                    }
                    if (results.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
        }

        return results
    }

    private suspend fun searchRedditVideosByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val redditSort = when (sortOrder) {
            ContentSortOrder.POPULAR -> "top"
            ContentSortOrder.NEWEST -> "new"
            ContentSortOrder.VIRAL_VELOCITY -> "hot"
            ContentSortOrder.RELEVANCE -> "relevance"
        }
        val hosts = listOf("https://www.reddit.com", "https://api.reddit.com")
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)

        for (host in hosts) {
            try {
                val httpResp = httpClient.get("$host/search.json") {
                    header(
                        HttpHeaders.UserAgent,
                        "android:forge.media.pocket:v1.2.0 (by /u/pocketmediaforge)"
                    )
                    header(HttpHeaders.Accept, "application/json")
                    parameter("q", queryTags)
                    parameter("sort", redditSort)
                    parameter("t", period.redditParam)
                    parameter("limit", 25)
                    parameter("raw_json", 1)
                }

                if (!httpResp.status.isSuccess()) continue

                val bodyText = httpResp.bodyAsText()
                if (!bodyText.trimStart().startsWith("{")) continue

                val listing = jsonFormat.decodeFromString<RedditSearchListing>(bodyText)
                val found = listing.data?.children
                    ?.mapNotNull { it.data }
                    ?.filter { it.isVideo && !it.over18 }
                    ?.mapNotNull { post ->
                        val video = post.media?.redditVideo ?: return@mapNotNull null
                        val url = video.fallbackUrl ?: return@mapNotNull null
                        val dur = if (video.duration > 0) video.duration else 25
                        if (dur > maxDurationSec) return@mapNotNull null

                        val dateStr = if (post.createdUtc > 0) {
                            sdf.format(java.util.Date((post.createdUtc * 1000).toLong()))
                        } else {
                            ""
                        }

                        DiscoveredMediaCandidate(
                            id = "rd_${post.id}",
                            url = url,
                            title = post.title,
                            author = "u/${post.author}",
                            platform = HuntPlatform.REDDIT,
                            durationSec = dur,
                            viewOrScoreCount = post.score,
                            uploadDateYmd = dateStr
                        )
                    } ?: emptyList()

                if (found.isNotEmpty()) return@withContext found
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    private fun computeViralVelocityScore(item: DiscoveredMediaCandidate): Double {
        if (item.uploadDateYmd.length != 8) return item.viewOrScoreCount.toDouble()
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val uploadTime = sdf.parse(item.uploadDateYmd)?.time ?: return item.viewOrScoreCount.toDouble()
            val daysOld = ((System.currentTimeMillis() - uploadTime) / (1000.0 * 3600.0 * 24.0)).coerceAtLeast(0.5)
            item.viewOrScoreCount.toDouble() / daysOld
        } catch (_: Exception) {
            item.viewOrScoreCount.toDouble()
        }
    }

    private suspend fun loadSeenIds(): Set<String> = stateMutex.withLock {
        if (!seenIdsFile.exists()) return@withLock emptySet()
        seenIdsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun markCandidatesAsSeen(ids: List<String>) = stateMutex.withLock {
        if (ids.isEmpty()) return@withLock
        val existing = if (seenIdsFile.exists()) {
            seenIdsFile.readLines().toMutableSet()
        } else {
            mutableSetOf()
        }
        existing.addAll(ids)
        val trimmed = existing.toList().takeLast(3000)
        seenIdsFile.writeText(trimmed.joinToString("\n"))
    }

    /**
     * Охотник на «Спам-Фермы / Конвейерные Каналы» (Spam-Farm & Content-Mill Hunter):
     * Ищет каналы в заданной тематике, которые заливают ролики пачками/потоком, имеют высокую
     * хаотичность/рандомность заголовков или конвейерные паттерны (#shorts, часть N, перезаливы),
     * и возвращает рейтинг таких каналов вместе с их лучшими видео для скачивания в 1080p.
     */
    suspend fun huntSpamFarmChannels(
        topicOrSeedTitle: String,
        maxChannels: Int = 5
    ): List<DiscoveredSpamChannel> = withContext(Dispatchers.IO) {
        val cleanTopic = sanitizeQuery(topicOrSeedTitle).take(75)
        DiagnosticLogger.log("SPAM-HUNTER", "📡 Старт поиска спам-каналов и контент-ферм по теме: «$cleanTopic»...")

        // Step 1: Generate targeted "content-mill / shorts-farm" search queries
        val farmQueries = mutableListOf(
            "$cleanTopic #shorts",
            "$cleanTopic нарезка моменты",
            cleanTopic
        )

        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isNotBlank()) {
            try {
                val prompt = """
                    Пользователь ищет на YouTube «спам-каналы / контент-фермы / сетки перезаливов» в тематике:
                    "$cleanTopic"
                    Такие каналы заливают много коротких или нарезанных роликов подряд, часто используют шаблонные или случайные кликбейт-названия, #shorts, "часть 1/2", перезаливы стримов/подкастов/ситуаций.
                    Придумай 3 поисковых запроса (на языке запроса пользователя), которые лучше всего вытащат именно такие массовые конвейерные каналы в этой нише.
                    Верни строго JSON:
                    {"queries": ["запрос 1", "запрос 2", "запрос 3"]}
                """.trimIndent()
                val rawJson = callGeminiJson(apiKey, prompt)
                if (!rawJson.isNullOrBlank()) {
                    val parsed = jsonFormat.decodeFromString<AdaptiveNichePlanDto>(rawJson)
                    val aiQueries = parsed.queries.map { sanitizeQuery(it) }.filter { it.isNotBlank() }
                    if (aiQueries.isNotEmpty()) {
                        farmQueries.clear()
                        farmQueries.addAll(aiQueries.take(3))
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Step 2: Gather wide pool of recent & relevant videos across farm queries
        val widePool = mutableListOf<DiscoveredMediaCandidate>()
        for (q in farmQueries.take(3)) {
            widePool.addAll(
                searchYouTubeByTags(
                    queryTags = q,
                    sortOrder = ContentSortOrder.NEWEST,
                    appendShortsKeyword = false,
                    minDurationSec = 8,
                    maxDurationSec = 240,
                    maxFetchCount = 25
                )
            )
            widePool.addAll(
                searchYouTubeByTags(
                    queryTags = q,
                    sortOrder = ContentSortOrder.RELEVANCE,
                    appendShortsKeyword = true,
                    minDurationSec = 8,
                    maxDurationSec = 180,
                    maxFetchCount = 20
                )
            )
        }

        val uniqueVideos = widePool.distinctBy { it.id }
        if (uniqueVideos.isEmpty()) {
            return@withContext emptyList()
        }

        // Group by channel author and also probe top single-hit channels if needed
        val groupedByAuthor = uniqueVideos
            .filter { it.author.isNotBlank() && !it.author.equals("YouTube", ignoreCase = true) }
            .groupBy { it.author.trim() }

        // Pick candidate channels to inspect deeply
        val candidateAuthors = groupedByAuthor.entries
            .sortedByDescending { entry ->
                val countBonus = entry.value.size * 10
                val spamTitleHits = entry.value.count { v -> hasSpamFarmTitleMarkers(v.title) } * 6
                countBonus + spamTitleHits
            }
            .take(maxChannels + 3)

        val discoveredChannels = mutableListOf<DiscoveredSpamChannel>()

        for ((authorName, initialVideos) in candidateAuthors) {
            if (discoveredChannels.size >= maxChannels) break

            // Fetch additional recent videos specifically from this author + topic to measure their upload stream
            val channelVideos = if (initialVideos.size >= 4) {
                initialVideos
            } else {
                val extra = searchYouTubeByTags(
                    queryTags = "\"$authorName\" ${cleanTopic.split(" ").take(2).joinToString(" ")}",
                    sortOrder = ContentSortOrder.NEWEST,
                    appendShortsKeyword = false,
                    minDurationSec = 8,
                    maxDurationSec = 300,
                    maxFetchCount = 12
                ).filter { it.author.equals(authorName, ignoreCase = true) || it.title.isNotBlank() }
                (initialVideos + extra).distinctBy { it.id }
            }

            if (channelVideos.isEmpty()) continue

            val titleRandomnessPct = computeTitleRandomnessPercent(channelVideos.map { it.title })
            val spamMarkerRatio = channelVideos.count { hasSpamFarmTitleMarkers(it.title) }.toDouble() / channelVideos.size.toDouble()
            val estimatedPerWeek = estimateUploadsPerWeek(channelVideos)
            val maxViews = channelVideos.maxOfOrNull { it.viewOrScoreCount } ?: 1000L
            val minViews = (channelVideos.minOfOrNull { it.viewOrScoreCount } ?: 500L).coerceAtLeast(100L)
            val spikeRatio = (maxViews.toDouble() / minViews.toDouble()).coerceIn(1.0, 200.0)

            val rawSpamScore = (
                (titleRandomnessPct * 0.42) +
                    (spamMarkerRatio * 28.0) +
                    ((estimatedPerWeek.coerceAtMost(25.0) / 25.0) * 20.0) +
                    (if (spikeRatio > 10.0) 12.0 else 5.0)
                ).toInt().coerceIn(48, 98)

            val encodedChannelSearch = java.net.URLEncoder.encode("$authorName $cleanTopic", "UTF-8")
            val channelUrl = "https://www.youtube.com/results?search_query=$encodedChannelSearch"

            val patternNotes = mutableListOf<String>()
            if (titleRandomnessPct >= 68) patternNotes.add("хаотичный/случайный поток тем ($titleRandomnessPct%)")
            if (spamMarkerRatio >= 0.35) patternNotes.add("шаблонные теги/нарезки (#shorts/часть)")
            if (estimatedPerWeek >= 5.0) patternNotes.add("массовый залив (~${String.format(Locale.US, "%.1f", estimatedPerWeek)} рол./нед)")
            if (spikeRatio >= 12.0) patternNotes.add("разброс просмотров x${spikeRatio.toInt()}")
            if (patternNotes.isEmpty()) patternNotes.add("потоковый канал по теме «$cleanTopic»")

            discoveredChannels.add(
                DiscoveredSpamChannel(
                    channelName = authorName,
                    channelUrl = channelUrl,
                    topicQuery = cleanTopic,
                    spamScore = rawSpamScore,
                    videosFoundCount = channelVideos.size,
                    estimatedUploadsPerWeek = estimatedPerWeek,
                    titleRandomnessPct = titleRandomnessPct,
                    patternSummary = patternNotes.joinToString(" • "),
                    sampleVideos = channelVideos.sortedByDescending { it.viewOrScoreCount }.take(5)
                )
            )
        }

        val sortedChannels = discoveredChannels.sortedByDescending { it.spamScore }.take(maxChannels)
        DiagnosticLogger.log("SPAM-HUNTER", "✅ Найдено ${sortedChannels.size} спам-каналов / контент-ферм по теме «$cleanTopic»")
        sortedChannels
    }

    private fun hasSpamFarmTitleMarkers(title: String): Boolean {
        val lower = title.lowercase(Locale.getDefault())
        val markers = listOf(
            "#shorts", "#short", "#viral", "#fyp", "#рек", "#тренды",
            "часть ", "part ", "момент", "нарезк", "история", "случай",
            "😱", "😂", "🔥", "💀", "❗️", "?!"
        )
        val capsCount = title.count { it.isUpperCase() }
        val highCaps = title.length >= 10 && (capsCount.toDouble() / title.length.toDouble()) >= 0.38
        return highCaps || markers.any { lower.contains(it) }
    }

    /**
     * Вычисляет процент случайности/разнобоя заголовков на канале (100% = абсолютно случайные несвязанные заголовки,
     * как у спам-сетки; низкий % = все видео строго по одному шаблону).
     */
    private fun computeTitleRandomnessPercent(titles: List<String>): Int {
        if (titles.size < 2) return 75
        val tokenSets = titles.map { t ->
            t.lowercase(Locale.getDefault())
                .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
                .split(Regex("""\s+"""))
                .filter { it.length >= 3 && it !in setOf("shorts", "short", "видео", "про", "как", "что", "для", "the", "and") }
                .toSet()
        }.filter { it.isNotEmpty() }

        if (tokenSets.size < 2) return 78

        var pairCount = 0
        var similaritySum = 0.0
        for (i in 0 until tokenSets.size) {
            for (j in i + 1 until tokenSets.size) {
                val a = tokenSets[i]
                val b = tokenSets[j]
                val intersection = a.intersect(b).size.toDouble()
                val union = a.union(b).size.toDouble().coerceAtLeast(1.0)
                similaritySum += (intersection / union)
                pairCount++
            }
        }
        val avgSimilarity = if (pairCount > 0) similaritySum / pairCount else 0.15
        return ((1.0 - avgSimilarity) * 96.0).toInt().coerceIn(45, 97)
    }

    private fun estimateUploadsPerWeek(videos: List<DiscoveredMediaCandidate>): Double {
        val dates = videos.mapNotNull { v ->
            if (v.uploadDateYmd.length == 8) {
                try {
                    SimpleDateFormat("yyyyMMdd", Locale.US).parse(v.uploadDateYmd)?.time
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }.sorted()

        if (dates.size >= 2) {
            val spanDays = ((dates.last() - dates.first()) / (1000.0 * 3600.0 * 24.0)).coerceAtLeast(1.0)
            return ((dates.size.toDouble() / spanDays) * 7.0).coerceIn(2.5, 45.0)
        }
        return (videos.size * 2.2).coerceIn(3.5, 28.0)
    }
}
