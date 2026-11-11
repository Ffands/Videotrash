package forge.media.pocket.discovery

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
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
    POPULAR("🔥 По популярности (Просмотры/Рейтинг)"),
    NEWEST("🆕 По новизне (Свежие)"),
    VIRAL_VELOCITY("📈 Взлетающие (Просмотры в день)"),
    RELEVANCE("🎯 По точному совпадению тегов")
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
    YOUTUBE("▶️ YouTube Clips/Shorts"),
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

    companion object {
        private const val TAG = "TopicHunterEngine"
    }

    private val seenIdsFile = File(context.filesDir, "seen_video_ids.txt")
    private val subscriptionsFile = File(context.filesDir, "autopilot_subscriptions.json")
    private val stateMutex = Mutex()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
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

    suspend fun huntByTags(
        queryTags: String,
        platform: HuntPlatform = HuntPlatform.ALL,
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.WEEK,
        limitCount: Int = 3,
        minDurationSec: Int = 10,
        maxDurationSec: Int = 75
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val seenIds = loadSeenIds()
        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            rawPool.addAll(
                searchYouTubeByTags(
                    queryTags = queryTags,
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
                    queryTags = queryTags,
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        if (platform == HuntPlatform.ALL || platform == HuntPlatform.PIKABU) {
            rawPool.addAll(
                searchPikabuByTags(
                    queryTags = queryTags,
                    sortOrder = sortOrder,
                    period = period,
                    maxDurationSec = maxDurationSec
                )
            )
        }

        val unseenCandidates = rawPool.filter { candidate ->
            candidate.id.isNotBlank() && !seenIds.contains(candidate.id)
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
        markCandidatesAsSeen(picked.map { it.id })
        picked
    }

    private fun searchYouTubeByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val searchPrefix = when (sortOrder) {
                ContentSortOrder.NEWEST -> "ytsearchdate20"
                else -> "ytsearch25"
            }

            val request = YoutubeDLRequest("$searchPrefix:$queryTags").apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--match-filter", "duration >= $minDurationSec & duration <= $maxDurationSec")
                period.daysBack?.let { days ->
                    addOption("--dateafter", "now-${days}days")
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val lines = response.out.lines().filter { it.trim().startsWith("{") }
            val results = mutableListOf<DiscoveredMediaCandidate>()

            for (line in lines) {
                try {
                    val item = jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                    val resolvedUrl = item.webpageUrl ?: item.url ?: continue
                    val dur = (item.duration ?: 30.0).toInt()
                    if (dur in minDurationSec..maxDurationSec) {
                        results.add(
                            DiscoveredMediaCandidate(
                                id = "yt_${item.id}",
                                url = resolvedUrl,
                                title = item.title,
                                author = item.uploader ?: "YouTube Creator",
                                platform = HuntPlatform.YOUTUBE,
                                durationSec = dur,
                                viewOrScoreCount = item.viewCount ?: 0L,
                                uploadDateYmd = item.uploadDate ?: ""
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "YouTube tag search warning for '$queryTags': ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchRedditVideosByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        try {
            val redditSort = when (sortOrder) {
                ContentSortOrder.POPULAR -> "top"
                ContentSortOrder.NEWEST -> "new"
                ContentSortOrder.VIRAL_VELOCITY -> "hot"
                ContentSortOrder.RELEVANCE -> "relevance"
            }

            val listing: RedditSearchListing = httpClient.get("https://www.reddit.com/search.json") {
                header(HttpHeaders.UserAgent, "android:forge.media.pocket:v1.2.0 (by /u/pocketforge)")
                parameter("q", "$queryTags site:v.redd.it")
                parameter("sort", redditSort)
                parameter("t", period.redditParam)
                parameter("limit", 25)
            }.body()

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)

            listing.data?.children
                ?.mapNotNull { it.data }
                ?.filter { it.isVideo && !it.over18 }
                ?.mapNotNull { post ->
                    val video = post.media?.redditVideo ?: return@mapNotNull null
                    val url = video.fallbackUrl ?: return@mapNotNull null
                    if (video.duration <= 0 || video.duration > maxDurationSec) return@mapNotNull null

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
                        durationSec = video.duration,
                        viewOrScoreCount = post.score,
                        uploadDateYmd = dateStr
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Reddit tag search warning for '$queryTags': ${e.message}")
            emptyList()
        }
    }

    private fun searchPikabuByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val encodedTag = java.net.URLEncoder.encode(queryTags.trim(), "UTF-8")
            val sortPath = when (sortOrder) {
                ContentSortOrder.NEWEST -> "?n=4"
                ContentSortOrder.POPULAR -> "?r=4"
                else -> ""
            }
            val pikabuSearchUrl = "https://pikabu.ru/tag/$encodedTag/video$sortPath"

            val request = YoutubeDLRequest(pikabuSearchUrl).apply {
                addOption("--dump-json")
                addOption("--playlist-end", "12")
                addOption("--skip-download")
                period.daysBack?.let { days ->
                    addOption("--dateafter", "now-${days}days")
                }
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
            results
        } catch (e: Exception) {
            Log.w(TAG, "Pikabu tag search warning for '$queryTags': ${e.message}")
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
