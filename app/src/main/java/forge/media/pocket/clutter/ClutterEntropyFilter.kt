package forge.media.pocket.clutter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.encodeToString
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

@Serializable
data class StudioInboxItem(
    val id: String,
    val videoFileName: String,
    val title: String,
    val author: String,
    val sourceUrl: String,
    val durationSec: Double,
    val sizeKb: Long,
    val addedAtMs: Long,
    val sourceTags: List<String> = emptyList(),
    val recommendedStartSec: Double = 0.0,
    val recommendedEndSec: Double = 0.0,
    val aiSummary: String = ""
)

@Serializable
data class PoolClipMetadata(
    val fileName: String,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val sizeKb: Long = 0L,
    val addedAtMs: Long = System.currentTimeMillis()
)

class ClutterEntropyFilter(private val context: Context) {

    companion object {
        private const val SCENE_CUT_THRESHOLD = 0.45
        private const val FREEZE_NOISE_TOLERANCE = 0.003
        private const val FREEZE_MIN_DURATION_SEC = 3.0
        private const val MAX_POOL_CLIPS = 16
        private const val SEGMENT_DURATION_SEC = 60
    }

    private val poolDir = File(context.filesDir, "clutter_pool").apply { mkdirs() }
    private val inboxDir = File(context.filesDir, "studio_inbox").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val cookiesFile = File(context.filesDir, "youtube_cookies.txt")
    private val poolMutex = Mutex()
    private val inboxMutex = Mutex()
    private val backgroundWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isWarmingUpPool = AtomicBoolean(false)

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
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

    fun getInboxVideoFile(item: StudioInboxItem): File = File(inboxDir, item.videoFileName)

    fun listStudioInboxItems(): List<StudioInboxItem> {
        val metaFiles = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }
            ?: return emptyList()
        val items = mutableListOf<StudioInboxItem>()
        for (mf in metaFiles) {
            try {
                val item = jsonFormat.decodeFromString<StudioInboxItem>(mf.readText())
                val vf = File(inboxDir, item.videoFileName)
                if (vf.exists() && vf.length() > 10_000L) {
                    items.add(item)
                }
            } catch (_: Exception) {
            }
        }
        return items.sortedByDescending { it.addedAtMs }
    }

    fun deleteStudioInboxItem(id: String): Boolean {
        val metaFile = File(inboxDir, "src_$id.meta.json")
        val videoFile = File(inboxDir, "src_$id.mp4")
        metaFile.delete()
        return videoFile.delete()
    }

    suspend fun downloadSourceToStudioInbox(
        sourceUrl: String,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        maxDurationSec: Int = 360,
        recommendedStartSec: Double = 0.0,
        recommendedEndSec: Double = 0.0,
        aiSummary: String = ""
    ): StudioInboxItem = inboxMutex.withLock {
        withContext(Dispatchers.IO) {
            DiagnosticLogger.log("INBOX-DL", "Загрузка исходника в 1080p для Студии: $sourceUrl")
            val bundle = downloadMediaWithMetadata(
                sourceUrl = sourceUrl,
                maxDurationSec = maxDurationSec,
                preferHighBitrate1080p = true
            )
            val id = UUID.randomUUID().toString().take(8)
            val targetVideo = File(inboxDir, "src_$id.mp4")
            bundle.videoFile.copyTo(targetVideo, overwrite = true)
            bundle.videoFile.delete()

            // Also copy to public Movies/PocketMediaForge/Sources so user has it in Gallery / CapCut immediately
            try {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "PocketMediaForge/Sources"
                ).apply { mkdirs() }
                val publicCopy = File(publicDir, "Source_${id}.mp4")
                targetVideo.copyTo(publicCopy, overwrite = true)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(publicCopy.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (e: Exception) {
                DiagnosticLogger.log("INBOX-DL", "Предупреждение копирования в Галерею: ${e.message}")
            }

            val durSec = probeVideoDurationSec(targetVideo)
            val resolvedTitle = overrideTitle?.takeIf { it.isNotBlank() }
                ?: bundle.originalTitle.ifBlank { "Исходник #$id" }
            val resolvedAuthor = overrideAuthor?.takeIf { it.isNotBlank() }
                ?: bundle.uploaderAuthor.ifBlank { "Автор" }

            val semanticTags = extractSemanticTopicTags(
                text = "$resolvedTitle $aiSummary",
                rawTags = bundle.sourceTags
            )

            val item = StudioInboxItem(
                id = id,
                videoFileName = targetVideo.name,
                title = resolvedTitle,
                author = resolvedAuthor,
                sourceUrl = sourceUrl,
                durationSec = durSec,
                sizeKb = targetVideo.length() / 1024L,
                addedAtMs = System.currentTimeMillis(),
                sourceTags = semanticTags,
                recommendedStartSec = recommendedStartSec,
                recommendedEndSec = recommendedEndSec,
                aiSummary = aiSummary
            )
            val metaFile = File(inboxDir, "src_$id.meta.json")
            metaFile.writeText(jsonFormat.encodeToString(item))

            DiagnosticLogger.log(
                "INBOX-DL",
                "✅ Исходник #$id сохранён в Студию: '${item.title}' (${item.sizeKb} KB, ${item.durationSec}с)"
            )
            item
        }
    }

    suspend fun importLocalUriToInbox(uri: Uri, customTitle: String? = null): StudioInboxItem =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().take(8)
            val targetVideo = File(inboxDir, "src_$id.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetVideo.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось прочитать выбранный видеофайл")

            val durSec = probeVideoDurationSec(targetVideo)
            val item = StudioInboxItem(
                id = id,
                videoFileName = targetVideo.name,
                title = customTitle?.takeIf { it.isNotBlank() } ?: "Локальное видео #$id",
                author = "С телефона",
                sourceUrl = "local://$id",
                durationSec = durSec,
                sizeKb = targetVideo.length() / 1024L,
                addedAtMs = System.currentTimeMillis()
            )
            File(inboxDir, "src_$id.meta.json").writeText(jsonFormat.encodeToString(item))
            DiagnosticLogger.log("INBOX", "Добавлено локальное видео в Студию: ${targetVideo.name} (${item.sizeKb} KB)")
            item
        }

    suspend fun importLocalUriToPool(uri: Uri, rawTagsInput: String = ""): File = withContext(Dispatchers.IO) {
        val tempRaw = File(stagingDir, "pool_import_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempRaw.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось открыть выбранное видео")

            val normalizedFile = File(poolDir, "clutter_user_${System.currentTimeMillis()}.mp4")
            val ok = standardizeClipTo1080x960(tempRaw, normalizedFile)
            if (!ok || !normalizedFile.exists()) {
                tempRaw.copyTo(normalizedFile, overwrite = true)
            }
            val parsedTags = extractSemanticTopicTags(rawTagsInput).ifEmpty { listOf("#свой_фон", "#универсал") }
            savePoolClipMetadata(
                PoolClipMetadata(
                    fileName = normalizedFile.name,
                    title = "Свой фон (${parsedTags.joinToString(" ")})",
                    tags = parsedTags,
                    sizeKb = normalizedFile.length() / 1024L
                )
            )
            DiagnosticLogger.log(
                "POOL",
                "✅ Пользовательский фон добавлен в пул: ${normalizedFile.name} (теги: ${parsedTags.joinToString(", ")})"
            )
            normalizedFile
        } finally {
            tempRaw.delete()
        }
    }

    fun deletePoolClip(fileName: String): Boolean {
        val target = File(poolDir, fileName)
        File(poolDir, "$fileName.meta.json").delete()
        val deleted = target.exists() && target.delete()
        if (deleted) {
            DiagnosticLogger.log("POOL", "🗑 Удалён фон из пула: $fileName")
        }
        return deleted
    }

    fun getPoolClipMetadata(file: File): PoolClipMetadata {
        val metaFile = File(poolDir, "${file.name}.meta.json")
        if (metaFile.exists()) {
            try {
                return jsonFormat.decodeFromString<PoolClipMetadata>(metaFile.readText())
            } catch (_: Exception) {
            }
        }
        val fallbackTags = if (file.name.startsWith("clutter_synth_")) {
            listOf("#абстракция", "#универсал")
        } else {
            listOf("#антистресс", "#универсал")
        }
        return PoolClipMetadata(
            fileName = file.name,
            title = file.name,
            tags = fallbackTags,
            sizeKb = file.length() / 1024L,
            addedAtMs = file.lastModified()
        )
    }

    fun savePoolClipMetadata(meta: PoolClipMetadata) {
        try {
            File(poolDir, "${meta.fileName}.meta.json").writeText(jsonFormat.encodeToString(meta))
        } catch (_: Exception) {
        }
    }

    fun setPoolClipTags(fileName: String, rawTagsInput: String): PoolClipMetadata {
        val file = File(poolDir, fileName)
        val existing = getPoolClipMetadata(file)
        val newTags = extractSemanticTopicTags(rawTagsInput).ifEmpty { listOf("#универсал") }
        val updated = existing.copy(tags = newTags)
        savePoolClipMetadata(updated)
        DiagnosticLogger.log("POOL-TAGS", "Обновлены теги фона $fileName -> ${newTags.joinToString(" ")}")
        return updated
    }

    fun extractSemanticTopicTags(text: String, rawTags: List<String> = emptyList()): List<String> {
        val combined = (text + " " + rawTags.joinToString(" ")).lowercase()
        val result = linkedSetOf<String>()

        val semanticMap: List<Pair<String, List<String>>> = listOf(
            "#мото" to listOf("мото", "мотоцикл", "байк", "эндуро", "питбайк", "скутер", "moto", "motorcycle", "biker", "enduro", "yamaha", "kawasaki", "honda", "ducati", "suzuki"),
            "#авто" to listOf("авто", "машин", "тачк", "дрифт", "гонк", "руль", "бмв", "мерс", "ауди", "жигул", "car", "drift", "driving", "racing", "bmw", "mercedes", "beamng", "forza", "assetto"),
            "#cs2" to listOf("cs2", "csgo", "кс2", "ксго", "контр", "мираж", "фейсит", "faceit", "counter-strike", "surf", "bhop"),
            "#minecraft" to listOf("minecraft", "майнкрафт", "майн", "паркур", "parkour", "bedwars"),
            "#gta" to listOf("gta", "гта", "рп", "ramp"),
            "#игры" to listOf("игр", "геймплей", "стрим", "катка", "дота", "dota", "valorant", "валорант", "pubg", "роблокс", "roblox", "gameplay", "gaming"),
            "#механика" to listOf("пресс", "гидравл", "станок", "завод", "шредер", "металл", "сварк", "инструмент", "hydraulic", "press", "shredder", "machining", "factory"),
            "#антистресс" to listOf("мыло", "песок", "резка", "слайм", "асбо", "asmr", "satisfying", "kinetic", "sand", "soap", "slime", "oddly"),
            "#спорт" to listOf("спорт", "зал", "трениров", "бокс", "мма", "ufc", "футбол", "трюк", "gym", "workout", "boxing", "fitness"),
            "#еда" to listOf("еда", "готовк", "рецепт", "кухн", "торт", "шаурм", "food", "cooking", "streetfood", "baking"),
            "#уборка" to listOf("уборк", "мойк", "керхер", "чистк", "ковр", "детейлинг", "cleaning", "pressure", "washing", "detailing"),
            "#природа" to listOf("природ", "лес", "океан", "море", "гор", "космос", "животн", "кот", "собак", "nature", "ocean", "animals")
        )

        for ((canonicalTag, keywords) in semanticMap) {
            if (keywords.any { kw -> combined.contains(kw) }) {
                result.add(canonicalTag)
            }
        }

        // Also extract explicit words/hashtags entered by user
        val explicitWords = Regex("""#?([a-zA-Zа-яА-ЯёЁ0-9_]{3,18})""")
            .findAll(text.lowercase())
            .map { it.groupValues[1] }
            .filter { w ->
                w !in setOf(
                    "видео", "shorts", "short", "для", "про", "как", "что", "это", "или", "при",
                    "youtube", "http", "https", "www", "com", "watch", "исходник", "момент",
                    "автор", "сек", "раз", "все", "еще", "уже", "тут", "там", "где", "когда"
                )
            }
            .take(4)

        for (w in explicitWords) {
            if (result.size >= 6) break
            result.add("#$w")
        }

        return result.toList()
    }

    fun findMatchingPoolClipsByTags(desiredTags: List<String>): List<Pair<File, List<String>>> {
        if (desiredTags.isEmpty()) return emptyList()
        val normalizedDesired = desiredTags.map { if (it.startsWith("#")) it.lowercase() else "#${it.lowercase()}" }.toSet()
        val clips = listVerifiedPoolClips()
        val matched = mutableListOf<Pair<File, List<String>>>()

        for (clip in clips) {
            val meta = getPoolClipMetadata(clip)
            val clipTags = meta.tags.map { it.lowercase() }
            val common = clipTags.filter { ct ->
                ct in normalizedDesired || normalizedDesired.any { dt -> ct.contains(dt.removePrefix("#")) || dt.contains(ct.removePrefix("#")) }
            }
            if (common.isNotEmpty()) {
                matched.add(clip to common)
            }
        }
        return matched.sortedByDescending { it.second.size }
    }

    private fun probeVideoDurationSec(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 30_000L
            (ms / 100.0).toInt() / 10.0
        } catch (_: Exception) {
            30.0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

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
        maxDurationSec: Int = 75,
        preferHighBitrate1080p: Boolean = true
    ): IngestedMediaBundle = withContext(Dispatchers.IO) {
        DiagnosticLogger.log("DOWNLOAD", "Начало загрузки медиа и метаданных (1080p=$preferHighBitrate1080p): $sourceUrl")
        var origTitle = ""
        var origUploader = ""
        var origTags = emptyList<String>()

        try {
            val req = YoutubeDLRequest(sourceUrl).apply {
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
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

        val file = downloadMediaFromUrl(
            sourceUrl = sourceUrl,
            maxDurationSec = maxDurationSec,
            preferHighBitrate1080p = preferHighBitrate1080p
        )
        IngestedMediaBundle(
            videoFile = file,
            originalTitle = origTitle,
            uploaderAuthor = origUploader,
            sourceTags = origTags
        )
    }

    suspend fun downloadMediaFromUrl(
        sourceUrl: String,
        maxDurationSec: Int = SEGMENT_DURATION_SEC,
        preferHighBitrate1080p: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val rawBase = File(stagingDir, "ingest_${UUID.randomUUID()}")
        val finalMp4 = File(stagingDir, "${rawBase.name}.mp4")

        // Strategy 0: Direct HTTP download if URL is already a direct .mp4 stream
        if (
            sourceUrl.contains(".mp4", ignoreCase = true) ||
            sourceUrl.contains("v.redd.it", ignoreCase = true) ||
            sourceUrl.contains("wikimedia.org", ignoreCase = true)
        ) {
            DiagnosticLogger.log("DOWNLOAD", "Стратегия 0 (Прямой HTTP поток): $sourceUrl")
            if (tryDirectHttpDownload(sourceUrl, finalMp4)) {
                DiagnosticLogger.log("DOWNLOAD", "✅ Успешно скачано по прямому HTTP (${finalMp4.length() / 1024} KB)")
                return@withContext trimIfNeeded(finalMp4, rawBase.name, maxDurationSec)
            }
        }

        val errors = mutableListOf<String>()

        // CRITICAL FIX AGAINST 360p ("ШАКАЛЫ"):
        // On vertical 9:16 Shorts, resolution is 1080x1920 (width=1080, height=1920)!
        // Using height<=1080 blocked 1080x1920 and 720x1280, and putting best[ext=mp4] first picked 360p itag 18!
        // Now we explicitly prioritize bestvideo (up to 1080x1920 / 1920x1080) + bestaudio merged to MP4!
        val formatSelector = if (preferHighBitrate1080p) {
            "bestvideo[width<=1080][height<=1920][ext=mp4]+bestaudio[ext=m4a]/" +
                "bestvideo[height<=1080][width<=1920][ext=mp4]+bestaudio[ext=m4a]/" +
                "bestvideo[width<=1080][height<=1920]+bestaudio/" +
                "best[width<=1080][height<=1920]/best"
        } else {
            // For bottom background clutter pool (1080x960), 720p is sharp and 10x smaller than 4K compilations
            "bestvideo[width<=720][height<=1280][ext=mp4]+bestaudio[ext=m4a]/" +
                "best[width<=720][height<=1280][ext=mp4]/best"
        }

        val clientProfiles: List<Pair<String, String?>> = listOf(
            "default-2026 (tv_samsung/auto)" to null,
            "tv_samsung,android_vr,ios" to "youtube:player_client=tv_samsung,android_vr,ios",
            "ios,mweb,web_embedded" to "youtube:player_client=ios,mweb,web_embedded"
        )

        for ((idx, pair) in clientProfiles.withIndex()) {
            val (profileLabel, extractorArg) = pair
            try {
                DiagnosticLogger.log("DOWNLOAD", "Попытка yt-dlp #${idx + 1} [$profileLabel] для $sourceUrl")
                val request = YoutubeDLRequest(sourceUrl).apply {
                    addOption("-f", formatSelector)
                    if (extractorArg != null) {
                        addOption("--extractor-args", extractorArg)
                    }
                    addOption("--merge-output-format", "mp4")
                    addOption("--no-playlist")
                    addOption("--no-check-certificates")
                    addOption("--geo-bypass")
                    if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                        addOption("--cookies", cookiesFile.absolutePath)
                        DiagnosticLogger.log("DOWNLOAD", "Используется файл youtube_cookies.txt (${cookiesFile.length()} байт)")
                    }
                    addOption("-o", finalMp4.absolutePath)
                }

                val res = YoutubeDL.getInstance().execute(request)
                DiagnosticLogger.log("DOWNLOAD", "yt-dlp #${idx + 1} завершился (out=${res.out.take(120)})")

                val located = locateDownloadedFile(finalMp4, rawBase.name)
                if (located != null) {
                    DiagnosticLogger.log(
                        "DOWNLOAD",
                        "✅ Успешно скачано в 1080p через yt-dlp [$profileLabel]: ${located.length() / 1024} KB"
                    )
                    return@withContext trimIfNeeded(located, rawBase.name, maxDurationSec)
                }
            } catch (e: Exception) {
                val msg = e.message?.take(220) ?: "unknown error"
                errors.add("Попытка #${idx + 1} [$profileLabel]: $msg")
                DiagnosticLogger.logError("DOWNLOAD", "Сбой попытки #${idx + 1} [$profileLabel]", e)
            }
        }

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
        throw IllegalStateException("Не удалось скачать видео.\n$combinedError")
    }

    private fun locateDownloadedFile(expectedMp4: File, prefix: String): File? {
        if (expectedMp4.exists() && expectedMp4.length() > 10_000L) return expectedMp4
        return stagingDir.listFiles { f -> f.name.startsWith(prefix) && f.length() > 10_000L }
            ?.firstOrNull()
    }

    private suspend fun trimIfNeeded(downloaded: File, baseName: String, maxDurationSec: Int): File {
        val dur = probeVideoDurationSec(downloaded)
        if (dur <= maxDurationSec + 2.0) {
            return downloaded
        }
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
                if (!response.status.isSuccess()) {
                    return@withContext false
                }
                targetFile.outputStream().use { out ->
                    response.bodyAsChannel().copyTo(out)
                }
                targetFile.exists() && targetFile.length() > 20_000L
            } catch (_: Exception) {
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
                "https://pipedapi.drgns.space",
                "https://api.piped.private.coffee"
            )
            for (instance in pipedInstances) {
                try {
                    val httpResp = httpClient.get("$instance/streams/$videoId") {
                        header(HttpHeaders.UserAgent, "PocketMediaForge/1.2")
                    }
                    if (!httpResp.status.isSuccess()) continue
                    val rawJson = httpResp.bodyAsText()
                    if (!rawJson.trimStart().startsWith("{")) continue

                    val resp = jsonFormat.decodeFromString<PipedStreamsResponse>(rawJson)
                    val streamUrl = resp.videoStreams
                        .firstOrNull { !it.videoOnly && (it.format?.contains("MPEG_4") == true || it.url != null) }
                        ?.url
                        ?: resp.videoStreams.firstOrNull { it.url != null }?.url

                    if (!streamUrl.isNullOrBlank() && tryDirectHttpDownload(streamUrl, targetFile)) {
                        return@withContext true
                    }
                } catch (_: Exception) {
                }
            }

            val invidiousInstances = listOf(
                "https://inv.nadeko.net",
                "https://invidious.nerdvpn.de",
                "https://invidious.privacyredirect.com"
            )
            for (inv in invidiousInstances) {
                try {
                    val httpResp = httpClient.get("$inv/api/v1/videos/$videoId") {
                        header(HttpHeaders.UserAgent, "PocketMediaForge/1.2")
                    }
                    if (!httpResp.status.isSuccess()) continue
                    val rawJson = httpResp.bodyAsText()
                    if (!rawJson.trimStart().startsWith("{")) continue

                    val resp = jsonFormat.decodeFromString<InvidiousVideoResponse>(rawJson)
                    val streamUrl = resp.formatStreams.firstOrNull { !it.url.isNullOrBlank() }?.url
                    if (!streamUrl.isNullOrBlank() && tryDirectHttpDownload(streamUrl, targetFile)) {
                        return@withContext true
                    }
                } catch (_: Exception) {
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

    suspend fun ingestAndVerifyClutterCandidate(
        sourceUrl: String,
        assignedTags: List<String> = listOf("#антистресс", "#универсал"),
        customTitle: String = ""
    ): File? =
        withContext(Dispatchers.IO) {
            var rawFile: File? = null
            try {
                DiagnosticLogger.log("POOL-WARMUP", "Скачиваю короткого кандидата в пул фонов (720p): $sourceUrl")
                rawFile = downloadMediaFromUrl(
                    sourceUrl = sourceUrl,
                    maxDurationSec = SEGMENT_DURATION_SEC,
                    preferHighBitrate1080p = false
                )
                val audit = auditClipEntropy(rawFile)

                if (!audit.isMonotonousAndValid) {
                    DiagnosticLogger.log("POOL-WARMUP", "Кандидат отклонён фильтром 3.3A: ${audit.rejectionReason}")
                    return@withContext null
                }

                val normalizedFile = File(poolDir, "clutter_${System.currentTimeMillis()}.mp4")
                val standardized = standardizeClipTo1080x960(rawFile, normalizedFile)
                if (!standardized) {
                    normalizedFile.delete()
                    return@withContext null
                }

                val finalTags = assignedTags.ifEmpty { listOf("#антистресс", "#универсал") }
                savePoolClipMetadata(
                    PoolClipMetadata(
                        fileName = normalizedFile.name,
                        title = customTitle.ifBlank { "Фон ${finalTags.joinToString(" ")}" },
                        tags = finalTags,
                        sizeKb = normalizedFile.length() / 1024L
                    )
                )

                DiagnosticLogger.log(
                    "POOL-WARMUP",
                    "✅ Кандидат добавлен в пул фонов: ${normalizedFile.name} (${normalizedFile.length() / 1024} KB, теги: ${finalTags.joinToString(" ")})"
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

    suspend fun downloadTaggedClutterByTopic(
        topicQuery: String,
        maxClipsToAdd: Int = 2
    ): List<File> = withContext(Dispatchers.IO) {
        val cleanTopic = topicQuery.trim().removePrefix("#")
        if (cleanTopic.isBlank()) return@withContext emptyList()

        val computedTags = extractSemanticTopicTags(cleanTopic).ifEmpty { listOf("#$cleanTopic") }
        val searchTail = when {
            computedTags.contains("#мото") -> "$cleanTopic motorcycle pov ride satisfying shorts"
            computedTags.contains("#авто") -> "$cleanTopic car drift driving pov shorts"
            computedTags.contains("#cs2") -> "$cleanTopic cs2 surf bhop gameplay shorts"
            computedTags.contains("#minecraft") -> "$cleanTopic minecraft parkour gameplay no talking shorts"
            computedTags.contains("#механика") -> "$cleanTopic hydraulic press machining satisfying shorts"
            else -> "$cleanTopic satisfying gameplay pov shorts"
        }

        val ytSearchQuery = "ytsearch10:$searchTail"
        DiagnosticLogger.log(
            "POOL-TOPIC",
            "🔎 Ищу тематические залипалки по теме «$cleanTopic» (теги: ${computedTags.joinToString(" ")}) -> $ytSearchQuery"
        )

        val addedFiles = mutableListOf<File>()
        try {
            val req = YoutubeDLRequest(ytSearchQuery).apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--no-check-certificates")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }
            val lines = YoutubeDL.getInstance().execute(req).out.lines()
                .filter { it.trim().startsWith("{") }

            for (line in lines) {
                val idMatch = Regex(""""id"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(line)?.groupValues?.get(1)
                val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: cleanTopic
                val durMatch = Regex(""""duration"\s*:\s*(\d+(\.\d+)?)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                if (idMatch != null && (durMatch == null || durMatch in 8.0..85.0)) {
                    val tagsForClip = (computedTags + extractSemanticTopicTags(titleMatch)).distinct().take(6)
                    val downloaded = ingestAndVerifyClutterCandidate(
                        sourceUrl = "https://www.youtube.com/shorts/$idMatch",
                        assignedTags = tagsForClip,
                        customTitle = titleMatch.take(65)
                    )
                    if (downloaded != null) {
                        addedFiles.add(downloaded)
                        if (addedFiles.size >= maxClipsToAdd) break
                    }
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("POOL-TOPIC", "Ошибка поиска тематических залипалок ($cleanTopic)", e)
        }
        addedFiles
    }

    fun triggerBackgroundPoolWarmupIfNeeded(forceAddMore: Boolean = false) {
        val realClipsCount = listVerifiedPoolClips().count { !it.name.startsWith("clutter_synth_") }
        if (!forceAddMore && realClipsCount >= 3) {
            DiagnosticLogger.log("POOL-WARMUP", "В пуле уже $realClipsCount реальных проверенных фонов (>=3).")
            return
        }
        if (!isWarmingUpPool.compareAndSet(false, true)) {
            DiagnosticLogger.log("POOL-WARMUP", "Фоновая докачка пула уже выполняется...")
            return
        }

        val targetGoal = if (forceAddMore) (realClipsCount + 2).coerceAtMost(MAX_POOL_CLIPS) else 3

        backgroundWarmupScope.launch {
            try {
                DiagnosticLogger.log(
                    "POOL-WARMUP",
                    "▶️ Старт докачки коротких залипалок (<70 сек) в пул (сейчас: $realClipsCount шт., цель: $targetGoal шт.)"
                )

                val ytQueries: List<Pair<String, List<String>>> = listOf(
                    "ytsearch8:oddly satisfying kinetic sand asmr shorts" to listOf("#антистресс", "#песок", "#универсал"),
                    "ytsearch8:motorcycle pov ride night satisfying shorts" to listOf("#мото", "#мотоцикл", "#гонки"),
                    "ytsearch8:hydraulic press satisfying shorts" to listOf("#механика", "#пресс", "#антистресс"),
                    "ytsearch8:minecraft parkour satisfying no talking shorts" to listOf("#minecraft", "#игры", "#паркур")
                )
                for ((q, tags) in ytQueries) {
                    try {
                        DiagnosticLogger.log("POOL-WARMUP", "Ищу короткие залипалки (до 70с) на YouTube: $q")
                        val req = YoutubeDLRequest(q).apply {
                            addOption("--flat-playlist")
                            addOption("--dump-json")
                            addOption("--skip-download")
                            addOption("--no-check-certificates")
                            if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                                addOption("--cookies", cookiesFile.absolutePath)
                            }
                        }
                        val lines = YoutubeDL.getInstance().execute(req).out.lines()
                            .filter { it.trim().startsWith("{") }

                        for (line in lines) {
                            val idMatch = Regex(""""id"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(line)?.groupValues?.get(1)
                            val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""
                            val durMatch = Regex(""""duration"\s*:\s*(\d+(\.\d+)?)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                            if (idMatch != null && (durMatch == null || durMatch in 8.0..75.0)) {
                                ingestAndVerifyClutterCandidate(
                                    sourceUrl = "https://www.youtube.com/shorts/$idMatch",
                                    assignedTags = tags,
                                    customTitle = titleMatch.take(65)
                                )
                                if (listVerifiedPoolClips().count { !it.name.startsWith("clutter_synth_") } >= targetGoal) break
                            }
                        }
                    } catch (e: Exception) {
                        DiagnosticLogger.logError("POOL-WARMUP", "Сбой поиска залипалок YouTube ($q)", e)
                    }
                    if (listVerifiedPoolClips().count { !it.name.startsWith("clutter_synth_") } >= targetGoal) break
                }

                if (listVerifiedPoolClips().isEmpty()) {
                    val synthFile = File(poolDir, "clutter_synth_${System.currentTimeMillis()}.mp4")
                    createProceduralAnimatedClutter(20.0, synthFile)
                }

                DiagnosticLogger.log(
                    "POOL-WARMUP",
                    "🏁 Завершена докачка пула. Сейчас готовых фонов в пуле: ${listVerifiedPoolClips().size} шт."
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
        fallbackPrimaryVideo: File? = null,
        specificPoolClipName: String? = null,
        preferredTopicTags: List<String> = emptyList()
    ): File = withContext(Dispatchers.IO) {
        val verifiedPool = listVerifiedPoolClips()

        // 1. If the user explicitly selected a specific background video in the Studio UI:
        if (!specificPoolClipName.isNullOrBlank() && specificPoolClipName != "AUTO") {
            val chosen = File(poolDir, specificPoolClipName)
            if (chosen.exists() && chosen.length() > 20_000L) {
                DiagnosticLogger.log("CLUTTER", "Используется выбранный вручную фон: ${chosen.name}")
                return@withContext chosen
            }
        }

        // 2. If preferredTopicTags are passed (e.g. #мото from motorcycle source video), prioritize matching pool clips!
        val candidatePool: List<File> = if (preferredTopicTags.isNotEmpty()) {
            val tagMatched = findMatchingPoolClipsByTags(preferredTopicTags)
            if (tagMatched.isNotEmpty()) {
                DiagnosticLogger.log(
                    "CLUTTER-TAGS",
                    "🎯 Подобрано ${tagMatched.size} тематических фонов по тегам ${preferredTopicTags.joinToString(" ")}: " +
                        tagMatched.joinToString { "${it.first.name}(${it.second.joinToString("")})" }
                )
                tagMatched.map { it.first }
            } else {
                DiagnosticLogger.log(
                    "CLUTTER-TAGS",
                    "По тегам ${preferredTopicTags.joinToString(" ")} точного совпадения в пуле нет — беру универсальный фон из пула."
                )
                verifiedPool
            }
        } else {
            verifiedPool
        }

        if (candidatePool.isEmpty()) {
            DiagnosticLogger.log("CLUTTER", "Пул фонов пуст — создаю размытый motion-фон из основного видео...")
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
            candidatePool.random()
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
            append("-stream_loop -1 -t ${targetDurationSec.coerceIn(5.0, 120.0)} ")
            append("-i \"${primaryVideo.absolutePath}\" ")
            append("-vf \"$vf\" -an ")
            append("-c:v libx264 -preset ultrafast -crf 22 -pix_fmt yuv420p ")
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
            append("-c:v libx264 -preset ultrafast -crf 20 -pix_fmt yuv420p ")
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
            append("-c:v libx264 -preset ultrafast -crf 21 -pix_fmt yuv420p ")
            append("\"${outputFile.absolutePath}\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            val ok = ReturnCode.isSuccess(session.returnCode)
            if (cont.isActive) cont.resume(ok)
        }
    }

    fun listVerifiedPoolClips(): List<File> {
        return poolDir.listFiles { f -> f.isFile && f.extension == "mp4" && f.length() > 50_000L }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private suspend fun enforceCircularBufferQuota() = poolMutex.withLock {
        val current = listVerifiedPoolClips().sortedBy { it.lastModified() }
        if (current.size > MAX_POOL_CLIPS) {
            val toEvict = current.take(current.size - MAX_POOL_CLIPS)
            toEvict.forEach { oldFile -> oldFile.delete() }
        }
    }
}
