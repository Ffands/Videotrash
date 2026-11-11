package forge.media.pocket.subtitles

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class TtsVoiceProfile(val geminiVoiceName: String, val pitch: Float, val rate: Float, val label: String) {
    CHARON_DEEP("Charon", 0.88f, 1.04f, "Мужской баритон (Charon)"),
    FENRIR_DYNAMIC("Fenrir", 1.0f, 1.10f, "Мужской бодрый (Fenrir)"),
    KORE_FEMALE("Kore", 1.08f, 1.05f, "Женский чёткий (Kore)"),
    PUCK_STORYTELLER("Puck", 0.96f, 1.06f, "Рассказчик (Puck)")
}

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
    val preferredLanguage: String = "ru.*,ru-orig,ru,en.*,en"
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

data class MusicShazamMatchResult(
    val detectedTrackAndGenre: String,
    val moodAndBpm: String,
    val searchQueries: List<String>,
    val candidateTracks: List<Pair<String, String>> = emptyList() // Pair(url, title)
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
            if (apiKey.isBlank()) {
                DiagnosticLogger.log("SUBS", "Ключ Gemini пуст, а VTT субтитры на YouTube отсутствуют -> слов=0")
                return@withContext emptyList()
            }

            val tempAudio = File(stagingDir, "stt_${System.currentTimeMillis()}.m4a")
            try {
                val extracted = extractCompactM4aAudio(videoFile, tempAudio)
                if (!extracted || !tempAudio.exists()) {
                    DiagnosticLogger.log("SUBS", "Не удалось извлечь аудиодорожку .m4a для субтитров")
                    return@withContext emptyList()
                }

                DiagnosticLogger.log("SUBS", "Отправка аудио (${tempAudio.length() / 1024} KB) в Gemini 2.5 Flash для таймкодов...")
                val base64Audio = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
                val prompt = """
                    Transcribe the speech in this audio into short phrases of 3 to 5 words (keep the original language, e.g. Russian).
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
                                        mimeType = "audio/mp4",
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
                    ?.replace("```json", "")
                    ?.replace("```", "")
                    ?.trim()
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
                DiagnosticLogger.log("SUBS", "✅ Gemini распознал ${words.size} слов для караоке-субтитров")
                words
            } catch (e: Exception) {
                Log.e(TAG, "Gemini audio fallback transcription failed", e)
                DiagnosticLogger.logError("SUBS", "Ошибка распознавания речи через Gemini", e)
                emptyList()
            } finally {
                tempAudio.delete()
            }
        }

    private suspend fun extractCompactM4aAudio(inputVideo: File, outputM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-t 90 ")
                append("-i \"${inputVideo.absolutePath}\" ")
                append("-vn -ac 1 -ar 16000 -c:a aac -b:a 32k ")
                append("\"${outputM4a.absolutePath}\"")
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

    // =========================================================================
    // ИНСТРУМЕНТ ПЕРЕОЗВУЧКИ: ИИ-РЕРАЙТ / ПЕРЕВОД РЕЧИ + НЕЙРО-СИНТЕЗ (TTS)
    // =========================================================================

    suspend fun generateDubbingScriptFromVideo(
        sourceUrl: String,
        localVideoFile: File,
        startSec: Double,
        endSec: Double,
        isolatedVocalStemFile: File? = null
    ): String = withContext(Dispatchers.IO) {
        val durationSpan = ((if (endSec > startSec + 1.0) endSec - startSec else 30.0)).coerceIn(4.0, 90.0)
        val targetWordCount = (durationSpan * 2.3).toInt().coerceIn(8, 190)

        DiagnosticLogger.log(
            "DUB-SCRIPT",
            "Извлекаю речь из куска ${String.format(Locale.US, "%.1f..%.1fс", startSec, endSec)} (~$targetWordCount слов, только верхний голос=${isolatedVocalStemFile != null})..."
        )

        // 1. Try YouTube VTT first ONLY if user did not explicitly isolate the top vocal stem
        var rawTranscriptSegment = ""
        if (isolatedVocalStemFile == null && sourceUrl.startsWith("http")) {
            val vttFile = tryDownloadYoutubeVtt(sourceUrl, "ru.*,ru-orig,ru,en.*,en")
            if (vttFile != null && vttFile.exists()) {
                val words = parseWebVttToTimedWords(vttFile.readText())
                vttFile.delete()
                val startMs = (startSec * 1000).toLong()
                val endMs = if (endSec > startSec + 1.0) (endSec * 1000).toLong() else Long.MAX_VALUE
                val sliceWords = words.filter { it.startMs in startMs..endMs }.ifEmpty { words }
                rawTranscriptSegment = sliceWords.joinToString(" ") { it.text }.take(2800)
            }
        }

        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isBlank()) {
            return@withContext if (rawTranscriptSegment.isNotBlank()) {
                rawTranscriptSegment
            } else {
                "Укажите Gemini API Key во вкладке «⚙️ Ключи/Лог» для авто-расшифровки и рерайта речи, либо напишите свой текст переозвучки прямо в это поле."
            }
        }

        val tempAudio = File(stagingDir, "dub_slice_${System.currentTimeMillis()}.m4a")
        try {
            val partsList = mutableListOf<GeminiPart>()
            val promptText = buildString {
                append("Ты — профессиональный автор закадровой переозвучки для YouTube Shorts и Reels.\n")
                append("Хронометраж куска видео: ${durationSpan.toInt()} секунд (нужен текст примерно на $targetWordCount слов, чтобы диктор спокойно уложился во время ролика).\n")
                append("ЗАДАЧА:\n")
                append("1. Слушай ТОЛЬКО наложенный сверху голос диктора/рассказчика (игнорируй фоновые шумы и крики самого видео).\n")
                append("2. Если речь на английском или другом иностранном языке — сделай живой, динамичный русский перевод для закадровой озвучки.\n")
                append("3. Если речь уже на русском — сделай качественный рерайт своими словами (пересказ с сохранением всех панчей, фактов и динамики), чтобы текст стал 100% уникальным.\n")
                append("4. Верни ТОЛЬКО чистый готовый текст для диктора на русском языке (без таймкодов, без скобок и без пояснений).\n")
                if (rawTranscriptSegment.isNotBlank()) {
                    append("\nИсходная расшифровка куска:\n$rawTranscriptSegment")
                }
            }
            partsList.add(GeminiPart(text = promptText))

            if (isolatedVocalStemFile != null && isolatedVocalStemFile.exists()) {
                val b64 = Base64.encodeToString(isolatedVocalStemFile.readBytes(), Base64.NO_WRAP)
                partsList.add(GeminiPart(inlineData = GeminiInlineData(mimeType = "audio/mp4", data = b64)))
            } else if (rawTranscriptSegment.isBlank()) {
                val ok = extractSliceM4aAudio(localVideoFile, startSec, durationSpan, tempAudio)
                if (ok && tempAudio.exists()) {
                    val b64 = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
                    partsList.add(GeminiPart(inlineData = GeminiInlineData(mimeType = "audio/mp4", data = b64)))
                }
            }

            val reqPayload = GeminiGenerateRequest(
                contents = listOf(GeminiContent(parts = partsList)),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.35,
                    responseMimeType = "text/plain"
                )
            )

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val response: GeminiGenerateResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(reqPayload)
            }.body()

            val generatedScript = response.candidates.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?.trim()
                .orEmpty()

            if (generatedScript.isNotBlank()) {
                DiagnosticLogger.log("DUB-SCRIPT", "✅ Готов скрипт переозвучки (${generatedScript.length} симв.)")
                generatedScript
            } else {
                rawTranscriptSegment.ifBlank { "Не удалось извлечь речь — введите текст озвучки вручную." }
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("DUB-SCRIPT", "Прямой вызов Gemini недоступен (${e.message?.take(60)}), пробуем резервный перевод без VPN...", e)
            if (rawTranscriptSegment.isNotBlank()) {
                try {
                    val fallbackPayload = buildJsonObject {
                        put("model", "openai")
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "role",
                                            "system"
                                        )
                                        put(
                                            "content",
                                            "Ты — автор закадровой русской озвучки для Shorts/Reels. Переведи (если текст на английском) или перескажи своими словами на живом русском языке для диктора (~$targetWordCount слов). Верни ТОЛЬКО готовый русский текст без пояснений."
                                        )
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", rawTranscriptSegment.take(3500))
                                    }
                                )
                            }
                        )
                    }
                    val fallbackText = httpClient.post("https://text.pollinations.ai/") {
                        contentType(ContentType.Application.Json)
                        setBody(fallbackPayload.toString())
                    }.bodyAsText().trim()
                    if (fallbackText.length >= 15) {
                        DiagnosticLogger.log("DUB-SCRIPT", "✅ Скрипт переозвучки переведён через резервный AI-шлюз (${fallbackText.length} симв.)")
                        return@withContext fallbackText
                    }
                } catch (_: Exception) {
                }
            }
            rawTranscriptSegment.ifBlank { "Ошибка Gemini: ${e.message?.take(100)}" }
        } finally {
            tempAudio.delete()
        }
    }

    suspend fun synthesizeVoiceoverToAudioFile(
        scriptText: String,
        voiceProfile: TtsVoiceProfile = TtsVoiceProfile.CHARON_DEEP
    ): File = withContext(Dispatchers.IO) {
        val cleanText = scriptText.trim()
        if (cleanText.isBlank()) {
            throw IllegalArgumentException("Текст для озвучки пуст!")
        }

        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isNotBlank()) {
            try {
                DiagnosticLogger.log(
                    "DUB-TTS",
                    "🎙 Генерация нейросетевой озвучки через Gemini 2.5 Flash TTS (голос=${voiceProfile.geminiVoiceName})..."
                )
                val geminiFile = trySynthesizeViaGeminiNeuralTts(cleanText, voiceProfile, apiKey)
                if (geminiFile != null && geminiFile.exists() && geminiFile.length() > 1000L) {
                    DiagnosticLogger.log(
                        "DUB-TTS",
                        "✅ Нейро-озвучка Gemini готова: ${geminiFile.name} (${geminiFile.length() / 1024} KB)"
                    )
                    return@withContext geminiFile
                }
            } catch (e: Exception) {
                DiagnosticLogger.logError("DUB-TTS", "Предупреждение Gemini TTS (переключаюсь на встроенный синтезатор Android)", e)
            }
        }

        DiagnosticLogger.log("DUB-TTS", "🎙 Синтез озвучки через локальный движок Android TextToSpeech (${voiceProfile.label})...")
        val localWav = synthesizeViaAndroidSystemTts(cleanText, voiceProfile)
        if (localWav != null && localWav.exists() && localWav.length() > 500L) {
            DiagnosticLogger.log("DUB-TTS", "✅ Локальная озвучка готова: ${localWav.name} (${localWav.length() / 1024} KB)")
            return@withContext localWav
        }

        throw IllegalStateException("Не удалось синтезировать озвучку (проверьте ключ Gemini или голосовой движок Android).")
    }

    suspend fun importCustomVoiceoverUri(uri: Uri): File = withContext(Dispatchers.IO) {
        val outFile = File(stagingDir, "voiceover_custom_${System.currentTimeMillis()}.m4a")
        val rawTemp = File(stagingDir, "vo_raw_${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                rawTemp.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось открыть аудиофайл")

            val ok = convertAudioToNormalizedM4a(rawTemp, outFile)
            if (!ok || !outFile.exists()) {
                rawTemp.copyTo(outFile, overwrite = true)
            }
            DiagnosticLogger.log("DUB-IMPORT", "✅ Загружен пользовательский файл озвучки: ${outFile.name} (${outFile.length() / 1024} KB)")
            outFile
        } finally {
            rawTemp.delete()
        }
    }

    private suspend fun trySynthesizeViaGeminiNeuralTts(
        text: String,
        voiceProfile: TtsVoiceProfile,
        apiKey: String
    ): File? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", "Прочитай выразительно и чётко на русском языке для озвучки ролика: $text")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            putJsonObject("generationConfig") {
                put(
                    "responseModalities",
                    buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("AUDIO"))
                    }
                )
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceProfile.geminiVoiceName)
                        }
                    }
                }
            }
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
        val rawResp = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }.bodyAsText()

        val b64Match = Regex(""""data"\s*:\s*"([A-Za-z0-9+/=\r\n]+)"""")
            .find(rawResp)?.groupValues?.get(1)
            ?: return@withContext null

        val pcmBytes = Base64.decode(b64Match.replace("\n", "").replace("\r", ""), Base64.DEFAULT)
        if (pcmBytes.size < 1000) return@withContext null

        val rawPcmFile = File(stagingDir, "gemini_tts_${System.currentTimeMillis()}.pcm")
        val outM4aFile = File(stagingDir, "voiceover_gemini_${System.currentTimeMillis()}.m4a")
        try {
            rawPcmFile.writeBytes(pcmBytes)
            val converted = convertRawPcm24kToM4a(rawPcmFile, outM4aFile)
            if (converted && outM4aFile.exists()) outM4aFile else null
        } finally {
            rawPcmFile.delete()
        }
    }

    private suspend fun synthesizeViaAndroidSystemTts(
        text: String,
        voiceProfile: TtsVoiceProfile
    ): File? = withTimeoutOrNull(35_000L) {
        suspendCancellableCoroutine { cont ->
            val outWav = File(stagingDir, "voiceover_tts_${System.currentTimeMillis()}.wav")
            var ttsRef: TextToSpeech? = null
            ttsRef = TextToSpeech(context.applicationContext) { status ->
                val engine = ttsRef
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    if (cont.isActive) cont.resume(null)
                    return@TextToSpeech
                }
                try {
                    val ruResult = engine.setLanguage(Locale("ru", "RU"))
                    if (ruResult == TextToSpeech.LANG_MISSING_DATA || ruResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.getDefault()
                    }
                    engine.setPitch(voiceProfile.pitch)
                    engine.setSpeechRate(voiceProfile.rate)

                    val utteranceId = "forge_tts_${System.currentTimeMillis()}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            try {
                                engine.shutdown()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(outWav)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            try {
                                engine.shutdown()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(null)
                        }
                    })

                    val params = Bundle()
                    val res = engine.synthesizeToFile(text, params, outWav, utteranceId)
                    if (res != TextToSpeech.SUCCESS) {
                        engine.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                } catch (e: Exception) {
                    try {
                        engine.shutdown()
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun extractSliceM4aAudio(
        inputVideo: File,
        startSec: Double,
        durationSec: Double,
        outputM4a: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            if (startSec > 0.0) {
                append(String.format(Locale.US, "-ss %.2f ", startSec))
            }
            append(String.format(Locale.US, "-t %.2f ", durationSec.coerceIn(3.0, 90.0)))
            append("-i \"${inputVideo.absolutePath}\" ")
            append("-vn -ac 1 -ar 16000 -c:a aac -b:a 32k ")
            append("\"${outputM4a.absolutePath}\"")
        }
        FFmpegKit.executeAsync(cmd) { session ->
            if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
        }
    }

    private suspend fun convertRawPcm24kToM4a(rawPcm: File, outM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-f s16le -ar 24000 -ac 1 -i \"${rawPcm.absolutePath}\" ")
                append("-c:a aac -b:a 192k -ar 48000 ")
                append("\"${outM4a.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    private suspend fun convertAudioToNormalizedM4a(inputAudio: File, outM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-i \"${inputAudio.absolutePath}\" ")
                append("-vn -c:a aac -b:a 192k -ar 48000 ")
                append("\"${outM4a.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    /**
     * AI-Shazam & Music Matcher:
     * Слушает выделенную музыкальную дорожку (`musicStemFile`), определяет трек/жанр/инструменты/BPM/настроение
     * и находит на YouTube похожие инструментальные мелодии без АП (Royalty-Free / No Copyright) под этот же вайб.
     */
    suspend fun analyzeMusicStemAndFindSimilar(
        musicStemFile: File,
        videoTitle: String
    ): MusicShazamMatchResult = withContext(Dispatchers.IO) {
        val apiKey = configStore.getGeminiApiKey()
        var detectedTrackAndGenre = "Фоновый инструментал (Ambient / Lo-Fi / Electronic)"
        var moodAndBpm = "Атмосферный ритм ~105 BPM"
        val searchQueries = mutableListOf<String>()

        if (apiKey.isNotBlank() && musicStemFile.exists() && musicStemFile.length() > 1000L) {
            val compactSlice = File(stagingDir, "shazam_probe_${System.currentTimeMillis()}.m4a")
            try {
                // Compress up to 25s of the isolated music stem for fast Gemini 2.5 Flash audio analysis
                extractSliceM4aAudio(musicStemFile, 0.0, 25.0, compactSlice)
                val targetFile = if (compactSlice.exists() && compactSlice.length() > 500L) compactSlice else musicStemFile
                val base64Audio = Base64.encodeToString(targetFile.readBytes(), Base64.NO_WRAP)

                val prompt = """
                    Ты — музыкальный эксперт и AI-Shazam. Прослушай эту выделенную музыкальную дорожку из видео «$videoTitle».
                    1) Если узнаёшь конкретный трек/мелодию — назови её и укажи точный жанр и инструменты (пианино, синтезатор, гитара, фонк, лоу-фай, оркестр, эмбиент и т.д.).
                    2) Укажи настроение, темп (BPM) и характер звучания.
                    3) Придумай 3 поисковых запроса на английском языке для поиска максимально похожей инструментальной мелодии без авторских прав (no copyright instrumental background music) точно такого же настроения, темпа и инструментов.
                    
                    Верни СТРОГО в таком формате (без лишнего текста):
                    TRACK_GENRE: <название трека если известен, жанр и инструменты на русском>
                    MOOD_BPM: <настроение и примерный BPM на русском>
                    QUERY: <поисковый запрос 1 на английском + no copyright instrumental>
                    QUERY: <поисковый запрос 2 на английском + royalty free music>
                    QUERY: <поисковый запрос 3 на английском + background beat instrumental>
                """.trimIndent()

                val payload = buildJsonObject {
                    put(
                        "contents",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    putJsonObject("inlineData") {
                                                        put("mimeType", "audio/mp4")
                                                        put("data", base64Audio)
                                                    }
                                                }
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("text", prompt)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

                DiagnosticLogger.log("AI-SHAZAM", "🔎 Отправка музыкальной дорожки в Gemini 2.5 Flash для распознавания мелодии и жанра...")
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val rawResp = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }.body<GeminiGenerateResponse>()

                val text = rawResp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("TRACK_GENRE:", ignoreCase = true) -> {
                            detectedTrackAndGenre = trimmed.substringAfter(":").trim().ifBlank { detectedTrackAndGenre }
                        }
                        trimmed.startsWith("MOOD_BPM:", ignoreCase = true) -> {
                            moodAndBpm = trimmed.substringAfter(":").trim().ifBlank { moodAndBpm }
                        }
                        trimmed.startsWith("QUERY:", ignoreCase = true) -> {
                            val q = trimmed.substringAfter(":").trim()
                            if (q.length >= 5) searchQueries.add(q)
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.logError("AI-SHAZAM", "Ошибка анализа мелодии через Gemini", e)
            } finally {
                compactSlice.delete()
            }
        }

        if (searchQueries.isEmpty()) {
            val lower = videoTitle.lowercase(Locale.getDefault())
            when {
                lower.contains("мото") || lower.contains("дрифт") || lower.contains("авто") || lower.contains("car") || lower.contains("bike") -> {
                    detectedTrackAndGenre = "Драйвовый электронный бит / Melodic Phonk / Synthwave"
                    moodAndBpm = "Динамичный ночной драйв ~120 BPM"
                    searchQueries.add("melodic atmospheric synthwave phonk instrumental no copyright")
                    searchQueries.add("night drive electronic beat royalty free instrumental")
                }
                lower.contains("истори") || lower.contains("факт") || lower.contains("тайна") || lower.contains("космос") -> {
                    detectedTrackAndGenre = "Кинематографическое пианино и мягкий эмбиент-пад"
                    moodAndBpm = "Загадочный, глубокий, атмосферный ~90 BPM"
                    searchQueries.add("cinematic mystery ambient piano instrumental no copyright")
                    searchQueries.add("dark ambient documentary background music royalty free")
                }
                else -> {
                    detectedTrackAndGenre = "Приятный Chill Lo-Fi / Acoustic Lounge инструментал"
                    moodAndBpm = "Спокойный, тёплый фоновый грув ~95 BPM"
                    searchQueries.add("chill lofi aesthetic instrumental beat no copyright")
                    searchQueries.add("pleasant warm acoustic ambient background music royalty free")
                }
            }
        }

        DiagnosticLogger.log(
            "AI-SHAZAM",
            "✅ Распознано: $detectedTrackAndGenre ($moodAndBpm). Поиск похожих мелодий: ${searchQueries.joinToString(" | ")}"
        )

        val candidates = mutableListOf<Pair<String, String>>()
        for (query in searchQueries.take(2)) {
            if (candidates.size >= 6) break
            try {
                val request = YoutubeDLRequest("ytsearch6:$query").apply {
                    addOption("--flat-playlist")
                    addOption("--dump-json")
                    addOption("--no-warnings")
                    addOption("--socket-timeout", "15")
                }
                val response = YoutubeDL.getInstance().execute(request)
                for (line in response.out.lines()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{")) continue
                    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: continue
                    val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: "Similar Instrumental Track"
                    val dur = Regex(""""duration"\s*:\s*([0-9.]+)""").find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: 90.0
                    if (dur in 20.0..360.0) {
                        val url = "https://www.youtube.com/watch?v=$id"
                        if (candidates.none { it.first == url }) {
                            candidates.add(url to title)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Similar music search failed for '$query': ${e.message}")
            }
        }

        MusicShazamMatchResult(
            detectedTrackAndGenre = detectedTrackAndGenre,
            moodAndBpm = moodAndBpm,
            searchQueries = searchQueries,
            candidateTracks = candidates
        )
    }

    /**
     * Скачивает выбранную похожую мелодию и нормализует её в чистый `.m4a` (48kHz stereo) для подмены музыкальной дорожки.
     */
    suspend fun downloadReplacementMusicCandidate(
        sourceUrl: String,
        trackTitle: String = "SimilarMelody"
    ): File = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val rawDownload = File(stagingDir, "bgm_raw_$stamp.m4a")
        val finalM4a = File(stagingDir, "bgm_replace_$stamp.m4a")

        DiagnosticLogger.log("AI-SHAZAM", "⬇️ Скачиваю похожую мелодию для подмены: «$trackTitle» ($sourceUrl)...")

        val req = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--download-sections", "*0-95")
            addOption("--socket-timeout", "20")
            addOption("-o", rawDownload.absolutePath)
        }
        YoutubeDL.getInstance().execute(req)

        val actualDownloaded = stagingDir.listFiles()?.firstOrNull {
            it.name.startsWith("bgm_raw_$stamp") && it.length() > 5_000L
        } ?: throw IllegalStateException("Не удалось скачать аудиопоток мелодии")

        try {
            val ok = convertAudioToNormalizedM4a(actualDownloaded, finalM4a)
            if (!ok || !finalM4a.exists() || finalM4a.length() < 2_000L) {
                throw IllegalStateException("Ошибка нормализации новой мелодии в AAC")
            }
            DiagnosticLogger.log("AI-SHAZAM", "✅ Новая мелодия готова к подмене: ${finalM4a.name} (${finalM4a.length() / 1024} KB)")
            finalM4a
        } finally {
            actualDownloaded.delete()
        }
    }
}
