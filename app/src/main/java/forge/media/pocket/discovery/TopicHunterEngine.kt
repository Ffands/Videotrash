package forge.media.pocket.discovery

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.encodeToString
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
    val aiWhatsInside: String = ""
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
data class AutopilotTagSubscription(
    val queryTags: String,
    val platform: HuntPlatform = HuntPlatform.YOUTUBE,
    val sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    val period: SearchTimePeriod = SearchTimePeriod.WEEK,
    val maxDurationSec: Int = 180
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
    private val subscriptionsFile = File(context.filesDir, "autopilot_subscriptions.json")
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
    private data class AiQueryExpansionDto(
        val queries: List<String> = emptyList()
    )

    @Serializable
    private data class AiRerankedItemDto(
        val index: Int = 0,
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
        limitCount: Int = 6,
        minDurationSec: Int = durationMode.minSec,
        maxDurationSec: Int = durationMode.maxSec
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeQuery(queryTags)
        val hasGeminiKey = configStore.getGeminiApiKey().isNotBlank()

        DiagnosticLogger.log(
            "HUNT",
            "Умный поиск по запросу: '$cleanQuery' (AI=${hasGeminiKey}, формат=${durationMode.name}, сорт=${sortOrder.name})"
        )

        // Stage 1: If user wrote a free-form description and Gemini key is set, expand into 3 sharp YouTube search queries
        val searchQueries = if (hasGeminiKey) {
            expandFreeformPromptToSearchQueries(cleanQuery, durationMode)
        } else {
            listOf(cleanQuery)
        }
        DiagnosticLogger.log("HUNT:AI-QUERIES", "Поисковые запросы: ${searchQueries.joinToString(" | ")}")

        val seenIds = loadSeenIds()
        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            val perQueryMax = if (searchQueries.size > 1) 15 else 30
            for (q in searchQueries) {
                rawPool.addAll(
                    searchYouTubeByTags(
                        queryTags = q,
                        sortOrder = sortOrder,
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
                unseenCandidates
        }

        // Stage 2: If Gemini is configured, ask Gemini to pick the best matches for the user's free-form description
        // and write a short "Что внутри" summary for each video!
        val finalPicked = if (hasGeminiKey && preSorted.isNotEmpty()) {
            rerankAndSummarizeViaGemini(
                userFreeformPrompt = cleanQuery,
                candidates = preSorted.take(24),
                limitCount = limitCount.coerceIn(1, 10)
            )
        } else {
            preSorted.take(limitCount.coerceIn(1, 10))
        }

        DiagnosticLogger.log("HUNT", "Итого отобрано кандидатов: ${finalPicked.size} из ${deduplicatedPool.size}")
        markCandidatesAsSeen(finalPicked.map { it.id })
        finalPicked
    }

    private suspend fun expandFreeformPromptToSearchQueries(
        userPrompt: String,
        durationMode: SearchDurationMode
    ): List<String> {
        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isBlank()) return listOf(userPrompt)

        return try {
            val modeHint = when (durationMode) {
                SearchDurationMode.ORIGINALS_1_TO_6M ->
                    "User wants original unedited videos/interviews/standups (1-6 minutes), NOT short edits."
                SearchDurationMode.SHORTS_ONLY ->
                    "User wants short vertical clips/highlights."
                SearchDurationMode.ANY_UP_TO_6M ->
                    "User wants interesting clips, stories, or segments up to 6 minutes."
            }

            val prompt = """
                The user wants to find videos on YouTube based on this free-form description:
                "$userPrompt"
                Context: $modeHint

                Convert this free-form request into 3 concise, high-precision YouTube search queries (3 to 6 words each, in the same language as the user's request) that will surface exact videos matching this topic.
                Return strictly JSON:
                {"queries": ["query 1", "query 2", "query 3"]}
            """.trimIndent()

            val rawJson = callGeminiJson(apiKey, prompt) ?: return listOf(userPrompt)
            val parsed = jsonFormat.decodeFromString<AiQueryExpansionDto>(rawJson)
            val cleanList = parsed.queries.map { sanitizeQuery(it) }.filter { it.isNotBlank() }.distinct().take(3)
            if (cleanList.isEmpty()) listOf(userPrompt) else cleanList
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:AI", "Fallback без расширения запроса: ${e.message?.take(80)}")
            listOf(userPrompt)
        }
    }

    private suspend fun rerankAndSummarizeViaGemini(
        userFreeformPrompt: String,
        candidates: List<DiscoveredMediaCandidate>,
        limitCount: Int
    ): List<DiscoveredMediaCandidate> {
        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isBlank() || candidates.isEmpty()) return candidates.take(limitCount)

        return try {
            val catalogText = candidates.mapIndexed { idx, c ->
                val descPart = if (c.descriptionSnippet.isNotBlank()) " | desc: ${c.descriptionSnippet.take(140)}" else ""
                "[$idx] title=\"${c.title}\" | channel=\"${c.author}\" | duration=${c.durationSec}s | views=${c.viewOrScoreCount}$descPart"
            }.joinToString("\n")

            val prompt = """
                You are an expert video scout. The user described the topic they want in free form:
                "$userFreeformPrompt"

                Here are ${candidates.size} discovered candidate videos:
                $catalogText

                Task:
                1. Select up to $limitCount best videos that most accurately match the user's topic/vibe and are NOT spam/clickbait garbage.
                2. For each selected video, write a short, concrete 1-sentence Russian summary ("whatsInside", max 130 chars) explaining what is inside this video and why it fits.
                Return strictly JSON:
                {"selected": [{"index": 0, "whatsInside": "Комик разгоняет историю про..."}]}
            """.trimIndent()

            val rawJson = callGeminiJson(apiKey, prompt) ?: return candidates.take(limitCount)
            val parsed = jsonFormat.decodeFromString<AiRerankResponseDto>(rawJson)
            val result = mutableListOf<DiscoveredMediaCandidate>()

            for (sel in parsed.selected) {
                if (sel.index in candidates.indices) {
                    val orig = candidates[sel.index]
                    result.add(orig.copy(aiWhatsInside = sel.whatsInside.trim()))
                }
            }

            if (result.isEmpty()) {
                candidates.take(limitCount)
            } else {
                result.distinctBy { it.id }.take(limitCount)
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:AI-RERANK", "Предупреждение AI-ранжирования: ${e.message?.take(100)}")
            candidates.take(limitCount)
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

        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isBlank()) {
            val fallbackSummary = if (fullDescription.isNotBlank()) {
                fullDescription.take(350)
            } else {
                "Для умной выжимки «Что внутри» с таймкодами укажи Gemini API Key во вкладке «⚙️ Ключи/Лог»."
            }
            return@withContext VideoInsideDigest(
                overallSummary = fallbackSummary,
                moments = listOf(
                    VideoHighlightMoment(
                        startSec = 0,
                        endSec = durationSec.coerceIn(15, 60),
                        title = "Начало ролика",
                        summary = "Первые ${durationSec.coerceIn(15, 60)} секунд"
                    )
                ),
                hasTranscript = hasSubs
            )
        }

        try {
            val sourceContext = if (hasSubs) {
                "ПОЛНАЯ РАСШИФРОВКА СУБТИТРОВ С ТАЙМКОДАМИ:\n${timestampedTranscript.take(14000)}"
            } else {
                "Субтитры отсутствуют. ОПИСАНИЕ И МЕТАДАННЫЕ РОЛИКА:\n${fullDescription.take(4000)}"
            }

            val prompt = """
                Ты — главный редактор видеоконтента. Пользователь ищет материал по теме: "$userTopic".
                Название ролика: "$videoTitle" (общая длительность: ${durationSec} сек).

                $sourceContext

                Сделай честную выжимку «ЧТО ВНУТРИ» на русском языке:
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

            val rawJson = callGeminiJson(apiKey, prompt)
            if (!rawJson.isNullOrBlank()) {
                val digest = jsonFormat.decodeFromString<VideoInsideDigest>(rawJson)
                return@withContext digest.copy(hasTranscript = hasSubs)
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("WHATS-INSIDE", "Ошибка Gemini анализа «Что внутри»", e)
        }

        VideoInsideDigest(
            overallSummary = fullDescription.take(300).ifBlank { "Ролик «$videoTitle» ($durationSec сек)." },
            moments = listOf(
                VideoHighlightMoment(
                    startSec = 0,
                    endSec = durationSec.coerceIn(15, 60),
                    title = "Основной фрагмент",
                    summary = "Фрагмент с начала ролика"
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

    private suspend fun callGeminiJson(apiKey: String, prompt: String): String? {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val payload = GeminiGenerateRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = prompt)))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.25,
                responseMimeType = "application/json"
            )
        )
        val response: GeminiGenerateResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()

        return response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?.replace("```json", "")
            ?.replace("```", "")
            ?.trim()
    }

    private suspend fun searchYouTubeByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        appendShortsKeyword: Boolean,
        minDurationSec: Int,
        maxDurationSec: Int,
        maxFetchCount: Int = 25
    ): List<DiscoveredMediaCandidate> {
        val finalQuery = if (appendShortsKeyword && !queryTags.lowercase().contains("short")) {
            "$queryTags shorts"
        } else {
            queryTags
        }

        val results = mutableListOf<DiscoveredMediaCandidate>()

        try {
            val searchPrefix = when (sortOrder) {
                ContentSortOrder.NEWEST -> "ytsearchdate$maxFetchCount"
                else -> "ytsearch$maxFetchCount"
            }

            val request = YoutubeDLRequest("$searchPrefix:$finalQuery").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--ignore-errors")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }

            for (line in lines) {
                try {
                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    if (item.id.isBlank()) continue

                    val rawUrl = item.webpageUrl ?: item.url ?: ""
                    val resolvedUrl = when {
                        rawUrl.startsWith("http") -> rawUrl
                        else -> "https://www.youtube.com/watch?v=${item.id}"
                    }

                    val dur = item.duration?.toInt() ?: 45
                    if (dur in minDurationSec..maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "yt_${item.id}",
                                url = resolvedUrl,
                                title = item.title.ifBlank { "YouTube #${item.id}" },
                                author = item.uploader ?: item.channel ?: "YouTube",
                                platform = HuntPlatform.YOUTUBE,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 1000L,
                                uploadDateYmd = item.uploadDate ?: "",
                                descriptionSnippet = item.description?.take(200) ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            DiagnosticLogger.log("HUNT:YT", "YouTube (yt-dlp) нашёл ${results.size} роликов по запросу '$finalQuery'")
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
                        val dur = item.duration ?: 45
                        if (dur in minDurationSec..maxDurationSec) {
                            results.add(
                                DiscoveredMediaCandidate(
                                    id = "yt_$vidId",
                                    url = "https://www.youtube.com/watch?v=$vidId",
                                    title = item.title ?: "YouTube #$vidId",
                                    author = item.uploaderName ?: "YouTube",
                                    platform = HuntPlatform.YOUTUBE,
                                    durationSec = dur,
                                    viewOrScoreCount = item.views ?: 1000L,
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

    suspend fun listAutopilotSubscriptions(): List<AutopilotTagSubscription> = stateMutex.withLock {
        if (!subscriptionsFile.exists()) return@withLock emptyList()
        try {
            jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addAutopilotSubscription(sub: AutopilotTagSubscription): List<AutopilotTagSubscription> =
        stateMutex.withLock {
            val current = if (subscriptionsFile.exists()) {
                try {
                    jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
                        .toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }
            current.removeAll { it.queryTags.equals(sub.queryTags, ignoreCase = true) }
            current.add(sub)
            subscriptionsFile.writeText(jsonFormat.encodeToString(current))
            current
        }

    suspend fun removeAutopilotSubscription(indexOrTag: String): List<AutopilotTagSubscription> =
        stateMutex.withLock {
            if (!subscriptionsFile.exists()) return@withLock emptyList()
            val current = try {
                jsonFormat.decodeFromString<List<AutopilotTagSubscription>>(subscriptionsFile.readText())
                    .toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
            val idx = indexOrTag.toIntOrNull()
            if (idx != null && idx in 1..current.size) {
                current.removeAt(idx - 1)
            } else {
                current.removeAll { it.queryTags.equals(indexOrTag, ignoreCase = true) }
            }
            subscriptionsFile.writeText(jsonFormat.encodeToString(current))
            current
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
                    val parsed = jsonFormat.decodeFromString<AiQueryExpansionDto>(rawJson)
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
