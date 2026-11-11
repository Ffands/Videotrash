package forge.media.pocket.clutter

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
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
        private const val SCENE_CUT_THRESHOLD = 0.42
        private const val FREEZE_NOISE_TOLERANCE = 0.003
        private const val FREEZE_MIN_DURATION_SEC = 2.5
        private const val MAX_POOL_CLIPS = 16
        private const val SEGMENT_DURATION_SEC = 60
    }

    private val poolDir = File(context.filesDir, "clutter_pool").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val poolMutex = Mutex()
    private val backgroundWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isWarmingUpPool = AtomicBoolean(false)

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
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

                val isClean = (hardCutCount <= 2) && (freezeCount == 0)
                val reason = when {
                    hardCutCount > 2 && freezeCount > 0 ->
                        "Rejected: $hardCutCount hard scene cuts and $freezeCount static freezes"
                    hardCutCount > 2 ->
                        "Rejected: $hardCutCount hard scene cuts"
                    freezeCount > 0 ->
                        "Rejected: $freezeCount static/frozen screens"
                    else -> null
                }

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
        var origTitle = ""
        var origUploader = ""
        var origTags = emptyList<String>()

        try {
            val info = YoutubeDL.getInstance().getInfo(sourceUrl)
            origTitle = info.title ?: ""
            origUploader = info.uploader ?: ""
            origTags = info.tags ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Metadata pre-fetch warning for $sourceUrl: ${e.message}")
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

        // Prefer single-file MP4 first so yt-dlp doesn't fail spawning external muxers on Android
        val request = YoutubeDLRequest(sourceUrl).apply {
            addOption(
                "-f",
                "best[height<=1080][ext=mp4]/bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]/best"
            )
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--no-check-certificates")
            addOption("--geo-bypass")
            addOption("-o", finalMp4.absolutePath)
        }

        YoutubeDL.getInstance().execute(request)

        // Locate downloaded file even if yt-dlp appended another extension
        val downloaded = when {
            finalMp4.exists() && finalMp4.length() > 0L -> finalMp4
            else -> stagingDir.listFiles { f -> f.name.startsWith(rawBase.name) && f.length() > 0L }
                ?.firstOrNull()
        } ?: throw IllegalStateException("Не удалось скачать видео по ссылке: $sourceUrl")

        // Trim locally via FFmpegKit if needed
        val trimmedFile = File(stagingDir, "${rawBase.name}_trimmed.mp4")
        val trimSuccess = trimVideoLocally(downloaded, trimmedFile, maxDurationSec)
        if (trimSuccess && trimmedFile.exists() && trimmedFile.length() > 10_000L) {
            downloaded.delete()
            return@withContext trimmedFile
        }

        downloaded
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
            try {
                val response: RedditListingResponse = httpClient.get(
                    "https://www.reddit.com/r/oddlysatisfying/hot.json?limit=$limit"
                ) {
                    header(HttpHeaders.UserAgent, "android:forge.media.pocket:v1.2.0 (by /u/pocketforge)")
                }.body()

                response.data?.children
                    ?.mapNotNull { it.data }
                    ?.filter { it.isVideo && !it.over18 }
                    ?.mapNotNull { it.media?.redditVideo?.fallbackUrl }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Reddit r/oddlysatisfying JSON feed", e)
                emptyList()
            }
        }

    suspend fun ingestAndVerifyClutterCandidate(sourceUrl: String): File? =
        withContext(Dispatchers.IO) {
            var rawFile: File? = null
            try {
                rawFile = downloadMediaFromUrl(sourceUrl, maxDurationSec = SEGMENT_DURATION_SEC)
                val audit = auditClipEntropy(rawFile)

                if (!audit.isMonotonousAndValid) {
                    return@withContext null
                }

                val normalizedFile = File(poolDir, "clutter_${System.currentTimeMillis()}.mp4")
                val standardized = standardizeClipTo1080x960(rawFile, normalizedFile)
                if (!standardized) {
                    normalizedFile.delete()
                    return@withContext null
                }

                enforceCircularBufferQuota()
                normalizedFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed clutter ingestion for $sourceUrl", e)
                null
            } finally {
                rawFile?.delete()
            }
        }

    fun triggerBackgroundPoolWarmupIfNeeded() {
        if (listVerifiedPoolClips().size >= 3) return
        if (!isWarmingUpPool.compareAndSet(false, true)) return

        backgroundWarmupScope.launch {
            try {
                // 1. Try YouTube satisfying Shorts first (works without Reddit blocks)
                val ytQueries = listOf(
                    "ytsearch2:oddly satisfying kinetic sand #shorts",
                    "ytsearch2:soap cutting ASMR no talking #shorts"
                )
                for (q in ytQueries) {
                    try {
                        val req = YoutubeDLRequest(q).apply {
                            addOption("--flat-playlist")
                            addOption("--get-id")
                            addOption("--skip-download")
                        }
                        val ids = YoutubeDL.getInstance().execute(req).out.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        for (id in ids) {
                            ingestAndVerifyClutterCandidate("https://www.youtube.com/watch?v=$id")
                            if (listVerifiedPoolClips().size >= 3) break
                        }
                    } catch (_: Exception) {
                    }
                    if (listVerifiedPoolClips().size >= 3) break
                }

                // 2. Also try Reddit oddlysatisfying if still < 3
                if (listVerifiedPoolClips().size < 3) {
                    val redditUrls = fetchRedditOddlySatisfyingUrls(limit = 6)
                    for (url in redditUrls) {
                        ingestAndVerifyClutterCandidate(url)
                        if (listVerifiedPoolClips().size >= 3) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background clutter warmup warning: ${e.message}")
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

        // Never block the user's job for 5 minutes or crash if the pool is still warming up!
        if (verifiedPool.isEmpty()) {
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
            append("boxblur=16:2,eq=saturation=1.35:contrast=1.1,setsar=1,format=nv12")
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
            append("-f lavfi -i \"color=c=#12131A:s=1080x960:r=30:d=$dur\" ")
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
            append("setsar=1,format=nv12")
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
        val filterComplex = "${streamLabels}concat=n=${fragments.size}:v=1:a=0,format=nv12[vout]"

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
