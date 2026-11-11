package forge.media.pocket.clutter

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.ceil

data class EntropyAuditResult(
    val isMonotonousAndValid: Boolean,
    val hardSceneCutsDetected: Int,
    val freezeIntervalsDetected: Int,
    val rejectionReason: String? = null
)

data class IngestedMediaBundle(
    val videoFile: File,
    val originalTitle: String,
    val uploaderAuthor: String,
    val sourceTags: List<String>
)

class ClutterEntropyFilter(private val context: Context) {

    companion object {
        private const val TAG = "ClutterEntropyFilter"
        private const val SCENE_CUT_THRESHOLD = 0.45
        private const val FREEZE_NOISE_TOLERANCE = 0.003
        private const val FREEZE_MIN_DURATION_SEC = 3.0
        private const val MAX_POOL_CLIPS = 16
        private const val SEGMENT_DURATION_SEC = 60
    }

    private val poolDir = File(context.filesDir, "clutter_pool").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val cookiesFile = File(context.filesDir, "youtube_cookies.txt")
    private val poolMutex = Mutex()
    private val backgroundWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isWarmingUpPool = AtomicBoolean(false)

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 60_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class RedditListingResponse(
        val data: RedditListingData? = null
    )

    @Serializable
    private data class RedditListingData(
        val children: List<RedditPostWrapper> = emptyList()
    )

    @Serializable
    private data class RedditPostWrapper(
        val data: RedditPostData? = null
    )

    @Serializable
    private data class RedditPostData(
        val title: String = "",
        @SerialName("is_video") val isVideo: Boolean = false,
        val over18: Boolean = false,
        val media: RedditMedia? = null
    )

    @Serializable
    private data class RedditMedia(
        @SerialName("reddit_video") val redditVideo: RedditVideoPayload? = null
    )

    @Serializable
    private data class RedditVideoPayload(
        @SerialName("fallback_url") val fallbackUrl: String? = null,
        val duration: Int = 0
    )

    @Serializable
    private data class PipedStreamsResponse(
        val title: String? = null,
        val uploader: String? = null,
        val videoStreams: List<PipedVideoStream> = emptyList()
    )

    @Serializable
    private data class PipedVideoStream(
        val url: String? = null,
        val format: String? = null,
        val quality: String? = null,
        val videoOnly: Boolean = true
    )

    @Serializable
    private data class InvidiousVideoResponse(
        val title: String? = null,
        val author: String? = null,
        val formatStreams: List<InvidiousFormatStream> = emptyList()
    )

    @Serializable
    private data class InvidiousFormatStream(
        val url: String? = null,
        val container: String? = null,
        val qualityLabel: String? = null
    )

    suspend fun auditClipEntropy(candidateFile: File): EntropyAuditResult =
        suspendCancellableCoroutine { cont ->
            val filterChain = buildString {
                append("scale=320:180:flags=fast_bilinear,")
                append("freezedetect=n=$FREEZE_NOISE_TOLERANCE:d=$FREEZE_MIN_DURATION_SEC,")
                append("select='gt(scene,$SCENE_CUT_THRESHOLD)',showinfo")
            }

            val cmd = buildString {
                append("-hide_banner -nostdin ")
                append("-i \"${candidateFile.absolutePath}\" ")
                append("-vf \"$filterChain\" ")
                append("-an -f null -")
            }

            FFmpegKit.executeAsync(cmd) { session ->
                val logs = session.allLogsAsString ?: ""
                val hardCutCount = Regex("""\[Parsed_showinfo.*pts_time:""").findAll(logs).count()
                val freezeCount = Regex("""lavfi\.freezedetect\.freeze_start""").findAll(logs).count()

                val isClean = (hardCutCount <= 4) && (freezeCount == 0)
                val reason = when {
                    hardCutCount > 4 && freezeCount > 0 ->
                        "Отклонено: $hardCutCount резких склеек и $freezeCount зависаний"
                    hardCutCount > 4 ->
                        "Отклонено: $hardCutCount резких склеек (>4)"
                    freezeCount > 0 ->
                        "Отклонено: $freezeCount статичных пауз"
                    else -> null
                }

                DiagnosticLogger.log(
                    "ENTROPY",
                    "Файл ${candidateFile.name}: склеек=$hardCutCount, зависаний=$freezeCount -> годен=$isClean"
                )

                if (cont.isActive) {
                    cont.resume(
                        EntropyAuditResult(
                            isMonotonousAndValid = isClean,
                            hardSceneCutsDetected = hardCutCount,
                            freezeIntervalsDetected = freezeCount,
                            rejectionReason = reason
                        )
                    )
                }
            }
        }

    suspend fun downloadMediaWithMetadata(
        sourceUrl: String,
        maxDurationSec: Int = 75
    ): IngestedMediaBundle = withContext(Dispatchers.IO) {
        DiagnosticLogger.log("DOWNLOAD", "Начало загрузки медиа и метаданных: $sourceUrl")
        var origTitle = ""
        var origUploader = ""
        var origTags = emptyList<String>()

        try {
            val req = YoutubeDLRequest(sourceUrl).apply {
                addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }
            val info = YoutubeDL.getInstance().getInfo(req)
            origTitle = info.title ?: ""
            origUploader = info.uploader ?: ""
            origTags = info.tags ?: emptyList()
            DiagnosticLogger.log("METADATA", "Получены метаданные: title='$origTitle', author='$origUploader'")
        } catch (e: Exception) {
            DiagnosticLogger.log("METADATA", "Предупреждение getInfo (не фатально): ${e.message?.take(140)}")
        }

        val file = downloadMediaFromUrl(sourceUrl, maxDurationSec)
        IngestedMediaBundle(
            videoFile = file,
            originalTitle = origTitle,
            uploaderAuthor = origUploader,
            sourceTags = origTags
        )
    }

    suspend fun downloadMediaFromUrl(
        sourceUrl: String,
        maxDurationSec: Int = SEGMENT_DURATION_SEC
    ): File = withContext(Dispatchers.IO) {
        val rawBase = File(stagingDir, "ingest_${UUID.randomUUID()}")
        val finalMp4 = File(stagingDir, "${rawBase.name}.mp4")

        // Strategy 0: Direct HTTP download if URL is already a direct .mp4 stream (e.g. Reddit v.redd.it DASH_720.mp4)
        if (sourceUrl.contains(".mp4", ignoreCase = true) || sourceUrl.contains("v.redd.it", ignoreCase = true)) {
            DiagnosticLogger.log("DOWNLOAD", "Стратегия 0 (Прямой HTTP поток): $sourceUrl")
            if (tryDirectHttpDownload(sourceUrl, finalMp4)) {
                DiagnosticLogger.log("DOWNLOAD", "✅ Успешно скачано по прямому HTTP (${finalMp4.length() / 1024} KB)")
                return@withContext trimIfNeeded(finalMp4, rawBase.name, maxDurationSec)
            }
        }

        val errors = mutableListOf<String>()

        // Strategy 1: yt-dlp with android_vr,ios,tv (Bypasses YouTube HTTP 400 Precondition check & Please sign in!)
        val clientProfiles = listOf(
            "youtube:player_client=android_vr,ios,tv",
            "youtube:player_client=tv_embedded,mweb,web_creator",
            "youtube:player_client=ios,web"
        )

        for ((idx, profile) in clientProfiles.withIndex()) {
            try {
                DiagnosticLogger.log("DOWNLOAD", "Попытка yt-dlp #${idx + 1} ($profile) для $sourceUrl")
                val request = YoutubeDLRequest(sourceUrl).apply {
                    addOption(
                        "-f",
                        "best[height<=1080][ext=mp4]/bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]/best"
                    )
                    addOption("--extractor-args", profile)
                    addOption("--merge-output-format", "mp4")
                    addOption("--no-playlist")
                    addOption("--no-check-certificates")
                    addOption("--geo-bypass")
                    if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                        addOption("--cookies", cookiesFile.absolutePath)
                        DiagnosticLogger.log("DOWNLOAD", "Используется файл youtube_cookies.txt")
                    }
                    addOption("-o", finalMp4.absolutePath)
                }

                val res = YoutubeDL.getInstance().execute(request)
                DiagnosticLogger.log("DOWNLOAD", "yt-dlp #${idx + 1} завершился (out=${res.out.take(120)})")

                val located = locateDownloadedFile(finalMp4, rawBase.name)
                if (located != null) {
                    DiagnosticLogger.log(
                        "DOWNLOAD",
                        "✅ Успешно скачано через yt-dlp ($profile): ${located.length() / 1024} KB"
                    )
                    return@withContext trimIfNeeded(located, rawBase.name, maxDurationSec)
                }
            } catch (e: Exception) {
                val msg = e.message?.take(220) ?: "unknown error"
                errors.add("Попытка #${idx + 1} ($profile): $msg")
                DiagnosticLogger.logError("DOWNLOAD", "Сбой попытки #${idx + 1} ($profile)", e)
            }
        }

        // Strategy 2: If YouTube still blocked yt-dlp, try public Piped / Invidious API fallback by video ID
        val ytVideoId = extractYoutubeVideoId(sourceUrl)
        if (ytVideoId != null) {
            DiagnosticLogger.log("DOWNLOAD", "Пробую обходной API (Piped/Invidious) для YouTube ID=$ytVideoId...")
            if (tryDownloadYoutubeViaFallbackApis(ytVideoId, finalMp4)) {
                DiagnosticLogger.log(
                    "DOWNLOAD",
                    "✅ Успешно скачано через резервный Piped/Invidious API (${finalMp4.length() / 1024} KB)"
                )
                return@withContext trimIfNeeded(finalMp4, rawBase.name, maxDurationSec)
            } else {
                errors.add("Резервные зеркала Piped/Invidious не отдали поток для $ytVideoId")
            }
        }

        val combinedError = errors.joinToString("\n")
        throw IllegalStateException(
            "Не удалось скачать видео.\n$combinedError\n\n" +
                "💡 Совет: нажми «📜 Полный лог» -> «🔄 Обновить yt-dlp» или скинь в этот чат файл cookies.txt от YouTube."
        )
    }

    private fun locateDownloadedFile(expectedMp4: File, prefix: String): File? {
        if (expectedMp4.exists() && expectedMp4.length() > 10_000L) return expectedMp4
        return stagingDir.listFiles { f -> f.name.startsWith(prefix) && f.length() > 10_000L }
            ?.firstOrNull()
    }

    private suspend fun trimIfNeeded(downloaded: File, baseName: String, maxDurationSec: Int): File {
        val trimmedFile = File(stagingDir, "${baseName}_trimmed.mp4")
        val trimSuccess = trimVideoLocally(downloaded, trimmedFile, maxDurationSec)
        if (trimSuccess && trimmedFile.exists() && trimmedFile.length() > 10_000L) {
            downloaded.delete()
            return trimmedFile
        }
        return downloaded
    }

    private suspend fun tryDirectHttpDownload(url: String, targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = httpClient.get(url) {
                    header(
                        HttpHeaders.UserAgent,
                        "Mozilla/5.0 (Linux; Android 14; OnePlus) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                }
                if (!response.status.isSuccess()) return@withContext false
                targetFile.outputStream().use { out ->
                    response.bodyAsChannel().copyTo(out)
                }
                targetFile.exists() && targetFile.length() > 20_000L
            } catch (e: Exception) {
                DiagnosticLogger.log("DOWNLOAD", "Прямой HTTP не удался для $url: ${e.message}")
                false
            }
        }

    private fun extractYoutubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private suspend fun tryDownloadYoutubeViaFallbackApis(videoId: String, targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val pipedInstances = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.tokhmi.xyz",
                "https://api.piped.private.coffee"
            )
            for (instance in pipedInstances) {
                try {
                    DiagnosticLogger.log("PIPED-API", "Запрос потока с $instance/streams/$videoId")
                    val resp: PipedStreamsResponse = httpClient.get("$instance/streams/$videoId") {
                        header(HttpHeaders.UserAgent, "PocketMediaForge/1.2")
                    }.body()

                    val streamUrl = resp.videoStreams
                        .firstOrNull { !it.videoOnly && (it.format?.contains("MPEG_4") == true || it.url != null) }
                        ?.url
                        ?: resp.videoStreams.firstOrNull { it.url != null }?.url

                    if (!streamUrl.isNullOrBlank() && tryDirectHttpDownload(streamUrl, targetFile)) {
                        return@withContext true
                    }
                } catch (e: Exception) {
                    DiagnosticLogger.log("PIPED-API", "Зеркало $instance недоступно: ${e.message?.take(80)}")
                }
            }

            val invidiousInstances = listOf(
                "https://inv.tux.pizza",
                "https://invidious.nerdvpn.de",
                "https://invidious.privacyredirect.com"
            )
            for (inv in invidiousInstances) {
                try {
                    DiagnosticLogger.log("INVIDIOUS-API", "Запрос потока с $inv/api/v1/videos/$videoId")
                    val resp: InvidiousVideoResponse = httpClient.get("$inv/api/v1/videos/$videoId").body()
                    val streamUrl = resp.formatStreams.firstOrNull { !it.url.isNullOrBlank() }?.url
                    if (!streamUrl.isNullOrBlank() && tryDirectHttpDownload(streamUrl, targetFile)) {
                        return@withContext true
                    }
                } catch (e: Exception) {
                    DiagnosticLogger.log("INVIDIOUS-API", "Зеркало $inv недоступно: ${e.message?.take(80)}")
                }
            }

            false
        }

    private suspend fun trimVideoLocally(
        inputFile: File,
        outputFile: File,
        maxDurationSec: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("-t $maxDurationSec ")
            append("-i \"${inputFile.absolutePath}\" ")
            append("-c copy \"${outputFile.absolutePath}\"")
        }
        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    suspend fun fetchRedditOddlySatisfyingUrls(limit: Int = 10): List<String> =
        withContext(Dispatchers.IO) {
            val subreddits = listOf("oddlysatisfying", "satisfying", "simulated")
            val urls = mutableListOf<String>()
            for (sub in subreddits) {
                try {
                    DiagnosticLogger.log("POOL-WARMUP", "Запрос ленты Reddit r/$sub/hot.json...")
                    val response: RedditListingResponse = httpClient.get(
                        "https://www.reddit.com/r/$sub/hot.json?limit=$limit"
                    ) {
                        header(
                            HttpHeaders.UserAgent,
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
                        )
                    }.body()

                    val found = response.data?.children
                        ?.mapNotNull { it.data }
                        ?.filter { it.isVideo && !it.over18 }
                        ?.mapNotNull { it.media?.redditVideo?.fallbackUrl }
                        ?: emptyList()

                    DiagnosticLogger.log("POOL-WARMUP", "Из r/$sub получено ${found.size} прямых MP4 ссылок")
                    urls.addAll(found)
                    if (urls.size >= limit) break
                } catch (e: Exception) {
                    DiagnosticLogger.logError("POOL-WARMUP", "Не удалось получить r/$sub", e)
                }
            }
            urls.distinct()
        }

    suspend fun ingestAndVerifyClutterCandidate(sourceUrl: String): File? =
        withContext(Dispatchers.IO) {
            var rawFile: File? = null
            try {
                DiagnosticLogger.log("POOL-WARMUP", "Скачиваю кандидата в пул фонов: $sourceUrl")
                rawFile = downloadMediaFromUrl(sourceUrl, maxDurationSec = SEGMENT_DURATION_SEC)
                val audit = auditClipEntropy(rawFile)

                if (!audit.isMonotonousAndValid) {
                    DiagnosticLogger.log("POOL-WARMUP", "Кандидат отклонён фильтром 3.3A: ${audit.rejectionReason}")
                    return@withContext null
                }

                val normalizedFile = File(poolDir, "clutter_${System.currentTimeMillis()}.mp4")
                val standardized = standardizeClipTo1080x960(rawFile, normalizedFile)
                if (!standardized) {
                    DiagnosticLogger.log("POOL-WARMUP", "Ошибка нормализации кандидата в 1080x960")
                    normalizedFile.delete()
                    return@withContext null
                }

                DiagnosticLogger.log(
                    "POOL-WARMUP",
                    "✅ Кандидат добавлен в пул фонов: ${normalizedFile.name} (${normalizedFile.length() / 1024} KB)"
                )
                enforceCircularBufferQuota()
                normalizedFile
            } catch (e: Exception) {
                DiagnosticLogger.logError("POOL-WARMUP", "Ошибка загрузки кандидата $sourceUrl", e)
                null
            } finally {
                rawFile?.delete()
            }
        }

    fun triggerBackgroundPoolWarmupIfNeeded() {
        val currentCount = listVerifiedPoolClips().size
        if (currentCount >= 3) {
            DiagnosticLogger.log("POOL-WARMUP", "В пуле уже $currentCount проверенных фонов (>=3), докачка не требуется.")
            return
        }
        if (!isWarmingUpPool.compareAndSet(false, true)) {
            DiagnosticLogger.log("POOL-WARMUP", "Фоновая докачка пула уже выполняется...")
            return
        }

        backgroundWarmupScope.launch {
            try {
                DiagnosticLogger.log("POOL-WARMUP", "▶️ Старт фоновой докачки залипалок (сейчас в пуле: $currentCount шт.)")

                // 1. Try direct MP4 streams from Reddit r/oddlysatisfying, r/satisfying, r/simulated (downloaded via OkHttp!)
                val redditUrls = fetchRedditOddlySatisfyingUrls(limit = 8)
                for (url in redditUrls) {
                    ingestAndVerifyClutterCandidate(url)
                    if (listVerifiedPoolClips().size >= 3) break
                }

                // 2. If still < 3, try YouTube satisfying Shorts with android_vr extractor
                if (listVerifiedPoolClips().size < 3) {
                    val ytQueries = listOf(
                        "ytsearch3:oddly satisfying kinetic sand #shorts",
                        "ytsearch3:soap cutting ASMR no talking #shorts"
                    )
                    for (q in ytQueries) {
                        try {
                            DiagnosticLogger.log("POOL-WARMUP", "Ищу залипалки на YouTube: $q")
                            val req = YoutubeDLRequest(q).apply {
                                addOption("--flat-playlist")
                                addOption("--get-id")
                                addOption("--skip-download")
                                addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv")
                            }
                            val ids = YoutubeDL.getInstance().execute(req).out.lines()
                                .map { it.trim() }
                                .filter { it.length == 11 }
                            for (id in ids) {
                                ingestAndVerifyClutterCandidate("https://www.youtube.com/watch?v=$id")
                                if (listVerifiedPoolClips().size >= 3) break
                            }
                        } catch (e: Exception) {
                            DiagnosticLogger.logError("POOL-WARMUP", "Сбой поиска залипалок YouTube ($q)", e)
                        }
                        if (listVerifiedPoolClips().size >= 3) break
                    }
                }

                // 3. Guaranteed fallback: if both Reddit and YouTube are blocked by ISP/VPN, generate 2 synthetic motion clips into the pool!
                if (listVerifiedPoolClips().isEmpty()) {
                    DiagnosticLogger.log(
                        "POOL-WARMUP",
                        "⚠️ Внешние площадки недоступны (блок сети/VPN). Генерирую резервный локальный фон 1080x960 в пул..."
                    )
                    val synthFile = File(poolDir, "clutter_synth_${System.currentTimeMillis()}.mp4")
                    if (createProceduralAnimatedClutter(20.0, synthFile)) {
                        DiagnosticLogger.log("POOL-WARMUP", "✅ Создан резервный локальный фон: ${synthFile.name}")
                    }
                }

                DiagnosticLogger.log(
                    "POOL-WARMUP",
                    "🏁 Завершена докачка пула. Сейчас готовых фонов: ${listVerifiedPoolClips().size} шт."
                )
            } catch (e: Exception) {
                DiagnosticLogger.logError("POOL-WARMUP", "Общая ошибка фоновой докачки", e)
            } finally {
                isWarmingUpPool.set(false)
            }
        }
    }

    suspend fun buildShuffledClutterReel(
        targetDurationSec: Double,
        fallbackPrimaryVideo: File? = null
    ): File = withContext(Dispatchers.IO) {
        val verifiedPool = listVerifiedPoolClips()

        if (verifiedPool.isEmpty()) {
            DiagnosticLogger.log("CLUTTER", "Пул фонов пуст — создаю быстрый размытый motion-фон из основного видео...")
            triggerBackgroundPoolWarmupIfNeeded()
            val fallbackOut = File(stagingDir, "fallback_clutter_${System.currentTimeMillis()}.mp4")
            if (fallbackPrimaryVideo != null && fallbackPrimaryVideo.exists()) {
                val created = createStylizedFallbackClutterFromPrimary(
                    primaryVideo = fallbackPrimaryVideo,
                    targetDurationSec = targetDurationSec,
                    outputFile = fallbackOut
                )
                if (created && fallbackOut.exists()) {
                    return@withContext fallbackOut
                }
            }
            createProceduralAnimatedClutter(targetDurationSec, fallbackOut)
            return@withContext fallbackOut
        }

        val neededClipsCount = ceil(targetDurationSec / 20.0).toInt().coerceAtLeast(1)
        val selectedClips = (1..neededClipsCount).map {
            verifiedPool.random()
        }

        if (selectedClips.size == 1) {
            return@withContext selectedClips.first()
        }

        val stitchedOutput = File(stagingDir, "stitched_clutter_${System.currentTimeMillis()}.mp4")
        val success = stitchNormalizedFragments(
            fragments = selectedClips,
            targetDurationSec = targetDurationSec,
            outputFile = stitchedOutput
        )

        if (!success || !stitchedOutput.exists()) {
            return@withContext selectedClips.first()
        }
        stitchedOutput
    }

    private suspend fun createStylizedFallbackClutterFromPrimary(
        primaryVideo: File,
        targetDurationSec: Double,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val vf = buildString {
            append("fps=30,")
            append("scale=1080:960:force_original_aspect_ratio=increase:flags=fast_bilinear,")
            append("crop=1080:960:(iw-1080)/2:(ih-960)/2,")
            append("boxblur=16:2,eq=saturation=1.35:contrast=1.1,setsar=1,format=yuv420p")
        }
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("-stream_loop -1 -t ${targetDurationSec.coerceIn(5.0, 75.0)} ")
            append("-i \"${primaryVideo.absolutePath}\" ")
            append("-vf \"$vf\" -an ")
            append("-c:v libx264 -preset ultrafast -crf 25 -pix_fmt yuv420p ")
            append("\"${outputFile.absolutePath}\"")
        }
        FFmpegKit.executeAsync(cmd) { session ->
            if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
        }
    }

    private suspend fun createProceduralAnimatedClutter(
        targetDurationSec: Double,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val dur = targetDurationSec.coerceIn(5.0, 75.0)
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("-f lavfi -i \"testsrc2=s=1080x960:r=30:d=$dur,boxblur=25:2,eq=brightness=-0.15:saturation=1.4\" ")
            append("-c:v libx264 -preset ultrafast -pix_fmt yuv420p ")
            append("\"${outputFile.absolutePath}\"")
        }
        FFmpegKit.executeAsync(cmd) { session ->
            if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
        }
    }

    private suspend fun standardizeClipTo1080x960(
        inputFile: File,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val vf = buildString {
            append("fps=30,")
            append("scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos,")
            append("crop=1080:960:(iw-1080)/2:(ih-960)/2,")
            append("setsar=1,format=yuv420p")
        }

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("-i \"${inputFile.absolutePath}\" ")
            append("-vf \"$vf\" ")
            append("-an ")
            append("-c:v libx264 -preset ultrafast -crf 22 -pix_fmt yuv420p ")
            append("\"${outputFile.absolutePath}\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    private suspend fun stitchNormalizedFragments(
        fragments: List<File>,
        targetDurationSec: Double,
        outputFile: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val inputsArg = fragments.joinToString(" ") { "-i \"${it.absolutePath}\"" }
        val streamLabels = fragments.indices.joinToString("") { "[$it:v:0]" }
        val filterComplex = "${streamLabels}concat=n=${fragments.size}:v=1:a=0,format=yuv420p[vout]"

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("$inputsArg ")
            append("-filter_complex \"$filterComplex\" ")
            append("-map \"[vout]\" ")
            append("-t ${targetDurationSec + 1.0} ")
            append("-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p ")
            append("\"${outputFile.absolutePath}\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    fun listVerifiedPoolClips(): List<File> {
        return poolDir.listFiles { f -> f.isFile && f.extension == "mp4" && f.length() > 50_000L }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    private suspend fun enforceCircularBufferQuota() = poolMutex.withLock {
        val current = listVerifiedPoolClips()
        if (current.size > MAX_POOL_CLIPS) {
            val toEvict = current.take(current.size - MAX_POOL_CLIPS)
            toEvict.forEach { oldFile -> oldFile.delete() }
        }
    }
}
