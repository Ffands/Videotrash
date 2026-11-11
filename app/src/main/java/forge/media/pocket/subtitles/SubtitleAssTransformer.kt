package forge.media.pocket.subtitles

import android.content.Context
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class SubtitleDensityMode {
    PUNCH_3_TO_4_WORDS,
    CLASSIC_6_TO_8_WORDS
}

enum class SubtitlePlacementZone(val defaultY: Int, val label: String) {
    ADAPTIVE_AUTO(960, "Auto (Avoids Faces)"),
    TOP_SAFE(240, "Top (Y=240)"),
    UPPER_THIRD(760, "Upper Box Bottom (Y=760)"),
    CENTER_SEAM(960, "Center Seam (Y=960)"),
    LOWER_BOX_MID(1380, "Lower Box Mid (Y=1380)"),
    BOTTOM_SAFE(1640, "Bottom Safe (Y=1640)"),
    CUSTOM_XY(960, "Custom X/Y")
}

data class SubtitleLayerConfig(
    val enabled: Boolean = true,
    val densityMode: SubtitleDensityMode = SubtitleDensityMode.PUNCH_3_TO_4_WORDS,
    val placementZone: SubtitlePlacementZone = SubtitlePlacementZone.ADAPTIVE_AUTO,
    val customX: Int = 540,
    val customY: Int = 960,
    val fontSizePx: Int = 68,
    val preferredLanguage: String = "ru,en"
) {
    fun resolveCoordinates(framingPlan: UpperBoxFramingPlan?): Pair<Int, Int> {
        val x = customX.coerceIn(140, 940)
        val y = when (placementZone) {
            SubtitlePlacementZone.ADAPTIVE_AUTO -> {
                if (framingPlan?.hasBottomFaceRisk == true) 1100 else 940
            }
            SubtitlePlacementZone.CUSTOM_XY -> customY.coerceIn(120, 1800)
            else -> placementZone.defaultY
        }
        return Pair(x, y)
    }
}

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class SubtitlePreparationResult(
    val assFile: File?,
    val plainTranscriptText: String
)

class SubtitleAssTransformer(
    private val context: Context,
    private val configStore: NodeConfigStore
) {

    companion object {
        private const val TAG = "SubtitleAssTransformer"
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

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
        val text: String? = null,
        val inlineData: GeminiInlineData? = null
    )

    @Serializable
    private data class GeminiInlineData(
        val mimeType: String,
        val data: String
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double = 0.1,
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
    private data class TranscribedSegmentDto(
        val startSec: Double,
        val endSec: Double,
        val text: String
    )

    suspend fun prepareAssSubtitles(
        sourceUrl: String,
        localVideoFile: File,
        config: SubtitleLayerConfig,
        framingPlan: UpperBoxFramingPlan?,
        cachedWords: List<TimedWord>? = null
    ): Pair<SubtitlePreparationResult, List<TimedWord>> = withContext(Dispatchers.IO) {
        val timedWords: List<TimedWord> = if (!cachedWords.isNullOrEmpty()) {
            cachedWords
        } else {
            val vttFile = tryDownloadYoutubeVtt(sourceUrl, config.preferredLanguage)
            if (vttFile != null && vttFile.exists()) {
                val parsed = parseWebVttToTimedWords(vttFile.readText())
                vttFile.delete()
                parsed
            } else {
                transcribeViaGeminiAudioFallback(localVideoFile)
            }
        }

        val transcriptPlain = timedWords.joinToString(" ") { it.text }

        if (!config.enabled || timedWords.isEmpty()) {
            return@withContext Pair(
                SubtitlePreparationResult(assFile = null, plainTranscriptText = transcriptPlain),
                timedWords
            )
        }

        val (posX, posY) = config.resolveCoordinates(framingPlan)
        val assFile = File(stagingDir, "subs_${System.currentTimeMillis()}.ass")
        val assContent = buildKaraokeAssDocument(
            words = timedWords,
            config = config,
            posX = posX,
            posY = posY
        )
        assFile.writeText(assContent, Charsets.UTF_8)
        Pair(
            SubtitlePreparationResult(assFile = assFile, plainTranscriptText = transcriptPlain),
            timedWords
        )
    }

    private fun tryDownloadYoutubeVtt(sourceUrl: String, langPref: String): File? {
        return try {
            val baseName = "vtt_${System.currentTimeMillis()}"
            val outTemplate = File(stagingDir, "$baseName.%(ext)s").absolutePath
            val request = YoutubeDLRequest(sourceUrl).apply {
                addOption("--skip-download")
                addOption("--write-auto-sub")
                addOption("--write-sub")
                addOption("--sub-langs", langPref)
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                configStore.getYoutubeCookiesFile()?.let { cookies ->
                    addOption("--cookies", cookies.absolutePath)
                }
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(request)

            stagingDir.listFiles { f ->
                f.name.startsWith(baseName) && f.extension.equals("vtt", ignoreCase = true)
            }?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Native VTT download unavailable for $sourceUrl: ${e.message}")
            null
        }
    }

    fun parseWebVttToTimedWords(vttRaw: String): List<TimedWord> {
        val lines = vttRaw.lines()
        val cueHeaderRegex = Regex(
            """(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})"""
        )
        val inlineTimeTagRegex = Regex("""<(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})>""")
        val htmlTagStripRegex = Regex("""<[^>]+>""")

        val extractedWords = mutableListOf<TimedWord>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val headerMatch = cueHeaderRegex.find(line)
            if (headerMatch != null) {
                val cueStartMs = parseVttTimestampMs(headerMatch.groupValues[1])
                val cueEndMs = parseVttTimestampMs(headerMatch.groupValues[2])
                i++

                val cueLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !cueHeaderRegex.containsMatchIn(lines[i])) {
                    cueLines.add(lines[i].trim())
                    i++
                }

                val joinedCue = cueLines.joinToString(" ")
                if (inlineTimeTagRegex.containsMatchIn(joinedCue)) {
                    val tokens = joinedCue.split(Regex("""(?=<\d{2}:)"""))
                    var currentWordStart = cueStartMs

                    for (token in tokens) {
                        val timeMatch = inlineTimeTagRegex.find(token)
                        val tokenTimeMs = if (timeMatch != null) {
                            parseVttTimestampMs(timeMatch.groupValues[1])
                        } else {
                            currentWordStart
                        }
                        val cleanWord = token.replace(htmlTagStripRegex, "").trim()
                        if (cleanWord.isNotEmpty()) {
                            val endEst = (tokenTimeMs + 280L).coerceAtMost(cueEndMs.coerceAtLeast(tokenTimeMs + 120L))
                            extractedWords.add(
                                TimedWord(
                                    text = cleanWord,
                                    startMs = currentWordStart,
                                    endMs = endEst
                                )
                            )
                            currentWordStart = tokenTimeMs
                        }
                    }
                } else {
                    val cleanText = joinedCue.replace(htmlTagStripRegex, "").trim()
                    val words = cleanText.split(Regex("""\s+""")).filter { it.isNotBlank() }
                    if (words.isNotEmpty()) {
                        val totalDur = (cueEndMs - cueStartMs).coerceAtLeast(words.size * 120L)
                        val step = totalDur / words.size
                        words.forEachIndexed { idx, w ->
                            val wStart = cueStartMs + idx * step
                            val wEnd = if (idx == words.lastIndex) cueEndMs else wStart + step
                            extractedWords.add(TimedWord(w, wStart, wEnd))
                        }
                    }
                }
            } else {
                i++
            }
        }

        return deduplicateOverlappingWords(extractedWords)
    }

    private fun deduplicateOverlappingWords(raw: List<TimedWord>): List<TimedWord> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<TimedWord>()
        for (word in raw) {
            val prev = result.lastOrNull()
            if (prev != null &&
                prev.text.equals(word.text, ignoreCase = true) &&
                kotlin.math.abs(word.startMs - prev.startMs) < 450L
            ) {
                continue
            }
            result.add(word)
        }
        return result
    }

    private suspend fun transcribeViaGeminiAudioFallback(videoFile: File): List<TimedWord> =
        withContext(Dispatchers.IO) {
            val apiKey = configStore.getGeminiApiKey()
            if (apiKey.isBlank()) return@withContext emptyList()

            val tempAudio = File(stagingDir, "stt_${System.currentTimeMillis()}.ogg")
            try {
                val extracted = extractCompactOggAudio(videoFile, tempAudio)
                if (!extracted || !tempAudio.exists()) return@withContext emptyList()

                val base64Audio = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
                val prompt = """
                    Transcribe the speech in this audio into short phrases of 3 to 5 words.
                    Return strictly a JSON array of objects with fields:
                    [{"startSec": 0.0, "endSec": 1.4, "text": "phrase text"}]
                """.trimIndent()

                val requestPayload = GeminiGenerateRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = prompt),
                                GeminiPart(
                                    inlineData = GeminiInlineData(
                                        mimeType = "audio/ogg",
                                        data = base64Audio
                                    )
                                )
                            )
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = 0.1,
                        responseMimeType = "application/json"
                    )
                )

                val endpoint =
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

                val response: GeminiGenerateResponse = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }.body()

                val rawJson = response.candidates.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext emptyList()

                val segments = jsonFormat.decodeFromString<List<TranscribedSegmentDto>>(rawJson)
                val words = mutableListOf<TimedWord>()

                for (seg in segments) {
                    val tokens = seg.text.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
                    if (tokens.isEmpty()) continue
                    val startMs = (seg.startSec * 1000.0).roundToInt().toLong()
                    val endMs = (seg.endSec * 1000.0).roundToInt().toLong().coerceAtLeast(startMs + 200L)
                    val step = ((endMs - startMs) / tokens.size).coerceAtLeast(80L)

                    tokens.forEachIndexed { idx, token ->
                        val wStart = startMs + idx * step
                        val wEnd = if (idx == tokens.lastIndex) endMs else wStart + step
                        words.add(TimedWord(token, wStart, wEnd))
                    }
                }
                words
            } catch (e: Exception) {
                Log.e(TAG, "Gemini audio fallback transcription failed", e)
                emptyList()
            } finally {
                tempAudio.delete()
            }
        }

    private suspend fun extractCompactOggAudio(inputVideo: File, outputOgg: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-t 65 ")
                append("-i \"${inputVideo.absolutePath}\" ")
                append("-vn -ac 1 -ar 16000 -c:a libopus -b:a 24k ")
                append("\"${outputOgg.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    private fun buildKaraokeAssDocument(
        words: List<TimedWord>,
        config: SubtitleLayerConfig,
        posX: Int,
        posY: Int
    ): String {
        val wordsPerCard = when (config.densityMode) {
            SubtitleDensityMode.PUNCH_3_TO_4_WORDS -> 4
            SubtitleDensityMode.CLASSIC_6_TO_8_WORDS -> 7
        }

        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: KaraokeStyle,Sans-Serif,${config.fontSizePx},&H0000FFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,1,0,1,5,2,5,40,40,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val sb = StringBuilder(header).append("\n")
        val chunks = words.chunked(wordsPerCard)

        for (chunk in chunks) {
            if (chunk.isEmpty()) continue
            val chunkStartMs = chunk.first().startMs
            val chunkEndMs = chunk.last().endMs.coerceAtLeast(chunkStartMs + 250L)

            val startAss = formatAssTime(chunkStartMs)
            val endAss = formatAssTime(chunkEndMs)

            val karaokeText = buildString {
                append("{\\an5\\pos($posX,$posY)}")
                for (w in chunk) {
                    val durCentiseconds = ((w.endMs - w.startMs) / 10L).coerceIn(8L, 250L)
                    val tag = if (config.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
                        "\\k$durCentiseconds"
                    } else {
                        "\\kf$durCentiseconds"
                    }
                    val sanitized = w.text
                        .replace("{", "")
                        .replace("}", "")
                        .replace("\\", "")
                    append("{$tag}$sanitized ")
                }
            }.trim()

            sb.append("Dialogue: 0,$startAss,$endAss,KaraokeStyle,,0,0,0,,$karaokeText\n")
        }

        return sb.toString()
    }

    private fun parseVttTimestampMs(raw: String): Long {
        val parts = raw.trim().split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (h * 3600 + m * 60 + s) * 1000L + ms
            } else {
                val m = parts[0].toLong()
                val secParts = parts[1].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (m * 60 + s) * 1000L + ms
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatAssTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        val cs = (clamped % 1000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }
}
