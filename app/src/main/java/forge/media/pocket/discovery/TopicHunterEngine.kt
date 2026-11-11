package forge.media.pocket.discovery

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
    RELEVANCE("🎯 По совпадению")
}

enum class SearchTimePeriod(val label: String, val daysBack: Int?, val redditParam: String) {
    DAY("За 24 часа", 1, "day"),
    WEEK("За неделю", 7, "week"),
    MONTH("За месяц", 30, "month"),
    YEAR("За год", 365, "year"),
    ALL_TIME("За всё время", null, "all")
}

enum class HuntPlatform(val label: String) {
    ALL("🌐 Все площадки"),
    YOUTUBE("▶️ YouTube Shorts/Clips"),
    REDDIT("👽 Reddit Video"),
    PIKABU("🟠 Пикабу Видео")
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
    val uploadDateYmd: String = ""
)

@Serializable
data class AutopilotTagSubscription(
    val queryTags: String,
    val platform: HuntPlatform = HuntPlatform.ALL,
    val sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    val period: SearchTimePeriod = SearchTimePeriod.WEEK,
    val maxDurationSec: Int = 65
)

class TopicHunterEngine(private val context: Context) {

    private val seenIdsFile = File(context.filesDir, "seen_video_ids.txt")
    private val subscriptionsFile = File(context.filesDir, "autopilot_subscriptions.json")
    private val cookiesFile = File(context.filesDir, "youtube_cookies.txt")
    private val stateMutex = Mutex()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 45_000L
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
        val isShort: Boolean? = null
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
        platform: HuntPlatform = HuntPlatform.ALL,
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.WEEK,
        limitCount: Int = 3,
        minDurationSec: Int = 5,
        maxDurationSec: Int = 90
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeQuery(queryTags)
        DiagnosticLogger.log(
            "HUNT",
            "Запуск поиска по тегам: '$cleanQuery' (площадка=${platform.name}, сорт=${sortOrder.name}, период=${period.name})"
        )

        val seenIds = loadSeenIds()
        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            rawPool.addAll(
                searchYouTubeByTags(
                    queryTags = cleanQuery,
                    sortOrder = sortOrder,
                    period = period,
                    minDurationSec = minDurationSec,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.REDDIT) {
            rawPool.addAll(
                searchRedditVideosByTags(
                    queryTags = cleanQuery,
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.PIKABU) {
            rawPool.addAll(
                searchPikabuByTags(
                    queryTags = cleanQuery,
                    sortOrder = sortOrder,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        val deduplicatedPool = rawPool.distinctBy { it.id }

        var unseenCandidates = deduplicatedPool.filter { candidate ->
            candidate.id.isNotBlank() && !seenIds.contains(candidate.id)
        }

        if (unseenCandidates.isEmpty() && deduplicatedPool.isNotEmpty()) {
            DiagnosticLogger.log("HUNT", "Все найденные ролики уже были в seen_ids — сбрасываю фильтр повторов для этой выдачи")
            unseenCandidates = deduplicatedPool
        }

        val sortedCandidates = when (sortOrder) {
            ContentSortOrder.POPULAR ->
                unseenCandidates.sortedByDescending { it.viewOrScoreCount }
            ContentSortOrder.NEWEST ->
                unseenCandidates.sortedByDescending { it.uploadDateYmd }
            ContentSortOrder.VIRAL_VELOCITY ->
                unseenCandidates.sortedByDescending { computeViralVelocityScore(it) }
            ContentSortOrder.RELEVANCE ->
                unseenCandidates
        }

        val picked = sortedCandidates.take(limitCount.coerceIn(1, 10))
        DiagnosticLogger.log("HUNT", "Итого выбрано кандидатов: ${picked.size} из ${deduplicatedPool.size}")
        markCandidatesAsSeen(picked.map { it.id })
        picked
    }

    private suspend fun searchYouTubeByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        val queryWithShortsHint = if (
            queryTags.lowercase().contains("short") ||
            queryTags.lowercase().contains("клип") ||
            queryTags.lowercase().contains("эдит") ||
            queryTags.lowercase().contains("edit")
        ) {
            queryTags
        } else {
            "$queryTags shorts"
        }

        val results = mutableListOf<DiscoveredMediaCandidate>()

        try {
            val searchPrefix = when (sortOrder) {
                ContentSortOrder.NEWEST -> "ytsearchdate20"
                else -> "ytsearch25"
            }

            val request = YoutubeDLRequest("$searchPrefix:$queryWithShortsHint").apply {
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

                    val dur = item.duration?.toInt() ?: 30
                    if (dur in minDurationSec..maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "yt_${item.id}",
                                url = resolvedUrl,
                                title = item.title.ifBlank { "YouTube Short #${item.id}" },
                                author = item.uploader ?: item.channel ?: "YouTube Creator",
                                platform = HuntPlatform.YOUTUBE,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 1000L,
                                uploadDateYmd = item.uploadDate ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            DiagnosticLogger.log("HUNT:YT", "YouTube (yt-dlp) выдал ${results.size} роликов по запросу '$queryWithShortsHint'")
        } catch (e: Exception) {
            DiagnosticLogger.logError("HUNT:YT", "Ошибка поиска YouTube (yt-dlp) для '$queryTags'", e)
        }

        if (results.isEmpty()) {
            // Fallback to Piped search API if YouTube blocked yt-dlp search on VPN IP
            val pipedMirrors = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.drgns.space"
            )
            for (mirror in pipedMirrors) {
                try {
                    DiagnosticLogger.log("HUNT:YT-PIPED", "Резервный поиск YouTube через $mirror/search?q=$queryWithShortsHint")
                    val httpResp = httpClient.get("$mirror/search") {
                        parameter("q", queryWithShortsHint)
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
                        val dur = item.duration ?: 30
                        if (dur in minDurationSec..maxDurationSec) {
                            results.add(
                                DiscoveredMediaCandidate(
                                    id = "yt_$vidId",
                                    url = "https://www.youtube.com/watch?v=$vidId",
                                    title = item.title ?: "YouTube #$vidId",
                                    author = item.uploaderName ?: "YouTube Creator",
                                    platform = HuntPlatform.YOUTUBE,
                                    durationSec = dur,
                                    viewOrScoreCount = item.views ?: 1000L
                                )
                            )
                        }
                    }
                    if (results.isNotEmpty()) {
                        DiagnosticLogger.log("HUNT:YT-PIPED", "Через $mirror найдено ${results.size} роликов")
                        break
                    }
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
        val hosts = listOf("https://old.reddit.com", "https://api.reddit.com")
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

                if (!httpResp.status.isSuccess()) {
                    DiagnosticLogger.log("HUNT:REDDIT", "Reddit ($host) вернул HTTP ${httpResp.status.value}")
                    continue
                }

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

                DiagnosticLogger.log("HUNT:REDDIT", "Reddit ($host) выдал ${found.size} видео по запросу '$queryTags'")
                if (found.isNotEmpty()) return@withContext found
            } catch (e: Exception) {
                DiagnosticLogger.log("HUNT:REDDIT", "Предупреждение поиска Reddit ($host): ${e.message?.take(100)}")
            }
        }
        emptyList()
    }

    private fun searchPikabuByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val firstTag = queryTags.split(" ").firstOrNull { it.isNotBlank() } ?: queryTags
            val encodedTag = java.net.URLEncoder.encode(firstTag.trim(), "UTF-8")
            val sortPath = when (sortOrder) {
                ContentSortOrder.NEWEST -> "?n=4"
                ContentSortOrder.POPULAR -> "?r=4"
                else -> ""
            }
            val pikabuSearchUrl = "https://pikabu.ru/tag/$encodedTag/video$sortPath"

            val request = YoutubeDLRequest(pikabuSearchUrl).apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--playlist-end", "12")
                addOption("--skip-download")
                addOption("--ignore-errors")
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }
            val results = mutableListOf<DiscoveredMediaCandidate>()

            for (line in lines) {
                try {
                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    val resolvedUrl = item.webpageUrl ?: item.url ?: continue
                    val dur = (item.duration ?: 30.0).toInt()
                    if (dur <= maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "pk_${item.id}",
                                url = resolvedUrl,
                                title = item.title,
                                author = item.uploader ?: "Pikabu Author",
                                platform = HuntPlatform.PIKABU,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 0L,
                                uploadDateYmd = item.uploadDate ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            DiagnosticLogger.log("HUNT:PIKABU", "Пикабу выдал ${results.size} видео по тегу '$firstTag'")
            results
        } catch (e: Exception) {
            DiagnosticLogger.logError("HUNT:PIKABU", "Ошибка поиска Пикабу для '$queryTags'", e)
            emptyList()
        }
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
}
