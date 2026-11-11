package forge.media.pocket.compositor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.vision.DetectedHardsubRegion
import forge.media.pocket.vision.ExistingSubtitleRemovalMode
import forge.media.pocket.vision.UpperBoxFramingPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

enum class VideoLayoutLayer(val label: String) {
    SPLIT_VSTACK("Сплит 50/50 (Верх + Залипалка)"),
    BLURRED_BG_FRAME("Кино-рамка 9:16 (Размытый фон)"),
    FULLSCREEN_EDIT("На весь экран 9:16 (Кроп)"),
    BRAINROT_VOICEOVER("Залипалка 9:16 + Звук исходника"),
    DISTORTED_ORIGINAL("Оригинал + Анти-Детект")
}

enum class AdInsertionMode {
    NONE,
    BANNER_OVERLAY,
    MIDROLL_SPLICE,
    MIDROLL_PIP_OVERLAY,
    PINNED_COMMENT_ONLY
}

data class AdLayerConfig(
    val mode: AdInsertionMode = AdInsertionMode.NONE,
    val bannerImageFile: File? = null,
    val bannerText: String = "Подпишись • Ссылка в закрепе",
    val bannerStartSec: Int = 5,
    val bannerEndSec: Int = 15,
    val midrollClipFile: File? = null,
    val promoLinkForComment: String = ""
)

enum class MusicRemovalMode(val label: String) {
    OFF("Выкл (Оригинальный звук)"),
    VOICE_BANDPASS_GATE("Мягкий фильтр речи (140Hz–4.2kHz + FFT шумодав)"),
    STEREO_CENTER_VOCAL("Выделение голоса из центра (Mid-канал + срез музыки)"),
    AGGRESSIVE_DE_MUSIC("Жёсткий Анти-Музыка (Узкий спектр речи + Gate пауз)")
}

enum class AudioStemRoutingMode(val label: String) {
    FULL_ORIGINAL_MIX("Дорожки: Весь звук слитно (Без разделения)"),
    DUB_OVER_CLEAN_AMBIENCE("Дорожки: 🎙 Переозвучить верхний голос (Старый голос 0% + Интершум видео 90%)"),
    AMBIENCE_ONLY_NO_VOICE("Дорожки: 🏍 Только Интершум видео (Стереть наложенный сверху голос!)"),
    VOCAL_PLUS_AMBIENCE_NO_MUSIC("Дорожки: 🗣 Голос + Интершум (Вырезать дорожку музыки)"),
    ISOLATED_VOCAL_ONLY("Дорожки: 🗣 Только выделенный Голос (Без интершума и музыки)")
}

data class SeparatedAudioStems(
    val vocalsOverlayFile: File,
    val ambienceSfxFile: File,
    val musicHarmonicsFile: File,
    val sourceStartSec: Double,
    val sourceDurationSec: Double
)

enum class VoiceoverDubMode(val label: String) {
    OFF("Выкл (Родной голос)"),
    TIMBRE_DEEP_BARITONE("Смена тембра: Глубокий баритон (-8% тон, сохр. ритм)"),
    TIMBRE_ENERGETIC_HIGH("Смена тембра: Высокий бодрый (+8% тон, сохр. ритм)"),
    TIMBRE_RADIO_COMPRESSOR("Смена тембра: Радио-диктор (Плотный компрессор)"),
    DUB_VOICEOVER_15PCT_BG("Закадр/Даббинг: Новая озвучка 100% + Оригинал 15%"),
    DUB_REPLACE_100PCT("Полная переозвучка: Новая озвучка 100% (Оригинал 0%)")
}

data class AntiDetectDspLayerConfig(
    val enabled: Boolean = true,
    val enableMicroZoom: Boolean = true,
    val enableHorizontalFlip: Boolean = false,
    val enableVideoNoiseInjection: Boolean = true,
    val enableMicroColorEqShift: Boolean = true,
    val enableAudioPitchTempoMicroJitter: Boolean = true,
    val enableAudioEqVoicePresence: Boolean = true,
    val enableSilenceTrimming350ms: Boolean = false,
    val musicRemovalMode: MusicRemovalMode = MusicRemovalMode.OFF,
    val voiceoverDubMode: VoiceoverDubMode = VoiceoverDubMode.OFF,
    val stemRoutingMode: AudioStemRoutingMode = AudioStemRoutingMode.FULL_ORIGINAL_MIX
)

data class ModularRenderConfig(
    val layoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
    val enableSmartFaceCrop: Boolean = true,
    val trimStartSec: Double = 0.0,
    val trimEndSec: Double = 0.0,
    val selectedPoolClipName: String = "AUTO",
    val preferredTopicTags: List<String> = emptyList(),
    val customVoiceoverAudioFile: File? = null,
    val customReplacementMusicFile: File? = null,
    val enableReplacementMusicBed: Boolean = false,
    val separatedStems: SeparatedAudioStems? = null,
    val existingSubtitleRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF,
    val detectedHardsubRegion: DetectedHardsubRegion? = null,
    val subtitleLayer: SubtitleLayerConfig = SubtitleLayerConfig(enabled = false),
    val adLayer: AdLayerConfig = AdLayerConfig(mode = AdInsertionMode.NONE),
    val dspLayer: AntiDetectDspLayerConfig = AntiDetectDspLayerConfig(enabled = true),
    val enableAiMetadataGeneration: Boolean = true,
    val videoBitrateKbps: Int = 12000,
    val capBitrateForTelegram50Mb: Boolean = false
) {
    companion object {
        fun presetPodcastSplit() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
            enableSmartFaceCrop = true,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )

        fun presetBlurredFrame() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.BLURRED_BG_FRAME,
            enableSmartFaceCrop = false,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )

        fun presetFullscreenEdit() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.FULLSCREEN_EDIT,
            enableSmartFaceCrop = false,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )

        fun presetBrainrotStory() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.BRAINROT_VOICEOVER,
            enableSmartFaceCrop = false,
            subtitleLayer = SubtitleLayerConfig(enabled = true),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )

        fun presetDistortedOriginal() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.DISTORTED_ORIGINAL,
            enableSmartFaceCrop = false,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )
    }
}

class FFmpegHardwareEngine(private val context: Context) {

    companion object {
        private const val TAG = "FFmpegHwEngine"
        private const val PREVIEW_DURATION_SEC = 5.0
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

    init {
        try {
            FFmpegKitConfig.setFontDirectoryList(
                context,
                listOf("/system/fonts", "/system/font"),
                emptyMap()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Font directory registration warning: ${e.message}")
        }
    }

    suspend fun saveRenderedVideoToGallery(renderedFile: File, titlePrefix: String = "Forge"): File =
        withContext(Dispatchers.IO) {
            val galleryDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "PocketMediaForge"
            ).apply { mkdirs() }
            val cleanPrefix = titlePrefix.replace(Regex("""[^a-zA-Zа-яА-Я0-9_-]"""), "_").take(24).ifBlank { "Video" }
            val targetFile = File(galleryDir, "${cleanPrefix}_${System.currentTimeMillis()}.mp4")
            renderedFile.copyTo(targetFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            DiagnosticLogger.log(
                "GALLERY",
                "✅ Готовый ролик сохранён в Галерею телефона: ${targetFile.absolutePath} (${targetFile.length() / 1024} KB)"
            )
            targetFile
        }

    suspend fun renderPipeline(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        isPreviewPass: Boolean
    ): File = withContext(Dispatchers.IO) {
        if (!primaryVideoFile.exists() || primaryVideoFile.length() < 1000L) {
            throw IllegalStateException("Исходный видеофайл не найден (${primaryVideoFile.name})")
        }

        val totalDurationSec = probeDurationSeconds(primaryVideoFile)
        val effectiveStartSec = config.trimStartSec.coerceIn(0.0, (totalDurationSec - 1.0).coerceAtLeast(0.0))
        val effectiveEndSec = if (config.trimEndSec > effectiveStartSec + 1.0) {
            config.trimEndSec.coerceAtMost(totalDurationSec)
        } else {
            totalDurationSec
        }
        val customSpanSec = (effectiveEndSec - effectiveStartSec).coerceIn(2.0, 180.0)

        val renderDurationSec = if (isPreviewPass) {
            PREVIEW_DURATION_SEC.coerceAtMost(customSpanSec)
        } else {
            customSpanSec
        }

        val suffix = if (isPreviewPass) "preview_5s" else "final_master_1080p"
        val outFile = File(stagingDir, "render_${suffix}_${System.currentTimeMillis()}.mp4")

        val hwCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = effectiveStartSec,
            durationSec = renderDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = true,
            outputFile = outFile
        )

        DiagnosticLogger.log(
            "RENDER",
            "Запуск рендера (hw=true, preview=$isPreviewPass, от ${effectiveStartSec}с длит. ${renderDurationSec}с, битрейт=${config.videoBitrateKbps}k)"
        )

        val hwSuccess = executeFFmpegCommand(hwCommand)
        if (hwSuccess && outFile.exists() && outFile.length() > 10_000L) {
            DiagnosticLogger.log("RENDER", "✅ Аппаратный рендер готов: ${outFile.length() / 1024} KB")
            return@withContext outFile
        }

        outFile.delete()
        val swCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = effectiveStartSec,
            durationSec = renderDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = false,
            outputFile = outFile
        )
        DiagnosticLogger.log("RENDER", "Переключение на программный рендер libx264 High-Quality (CRF 18)...")

        val swSuccess = executeFFmpegCommand(swCommand)
        if (!swSuccess || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Ошибка рендера FFmpeg (проверьте исходное видео).")
        }

        DiagnosticLogger.log("RENDER", "✅ Программный рендер готов: ${outFile.length() / 1024} KB")
        outFile
    }

    suspend fun extractCleanAudioTrack(primaryVideoFile: File, outputM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-i \"${primaryVideoFile.absolutePath}\" ")
                append("-vn -c:a aac -b:a 192k -ar 48000 ")
                append("\"${outputM4a.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    /**
     * Инструмент разделения звуковых дорожек (3 Stems):
     * 1. vocalsOverlayFile — наложенный сверху голос диктора (Mid-канал c0+c1 + голосовой фильтр + FFT шумодав)
     * 2. ambienceSfxFile — родной интершум/звуки видео БЕЗ голоса диктора (фазовое вычитание центра c0-c1 + режектор голосовых формант)
     * 3. musicHarmonicsFile — фоновая музыкальная подложка (бас + верхние гармоники вне речевого спектра)
     */
    suspend fun splitVideoAudioIntoStems(
        primaryVideoFile: File,
        startSec: Double,
        endSec: Double
    ): SeparatedAudioStems = withContext(Dispatchers.IO) {
        val totalDur = probeDurationSeconds(primaryVideoFile)
        val effStart = startSec.coerceIn(0.0, (totalDur - 1.0).coerceAtLeast(0.0))
        val effEnd = if (endSec > effStart + 1.0) endSec.coerceAtMost(totalDur) else totalDur
        val spanSec = (effEnd - effStart).coerceIn(2.0, 180.0)

        val stamp = System.currentTimeMillis()
        val vocalsFile = File(stagingDir, "stem_vocals_$stamp.m4a")
        val ambienceFile = File(stagingDir, "stem_ambience_$stamp.m4a")
        val musicFile = File(stagingDir, "stem_music_$stamp.m4a")

        val seekArg = if (effStart > 0.0) String.format(Locale.US, "-ss %.2f ", effStart) else ""
        val filterComplex = buildString {
            append("[0:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asplit=3[a_in1][a_in2][a_in3];")
            // Stem 1: Isolated Top Narrator / Voiceover (Mid-channel sum + bandpass 155..3900Hz + FFT floor reduction + gate)
            append("[a_in1]pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=155,lowpass=f=3900,afftdn=nf=-26,agate=threshold=0.018:ratio=2.4:attack=12:release=180,equalizer=f=2300:t=q:w=1.0:g=3.0[a_voc];")
            // Stem 2: Clean Video Ambience / SFX with Top Voiceover Cancelled (Side-channel phase subtraction c0-c1 + dual vocal formant notch)
            append("[a_in2]pan=stereo|c0=0.5*c0-0.5*c1|c1=0.5*c1-0.5*c0,bandreject=f=1050:width_type=h:w=1400,bandreject=f=2450:width_type=h:w=900,volume=1.65[a_amb];")
            // Stem 3: Music / Bass bed
            append("[a_in3]bandreject=f=1400:width_type=h:w=2200,lowpass=f=11000[a_mus]")
        }

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("$seekArg-t ${String.format(Locale.US, "%.2f", spanSec)} -i \"${primaryVideoFile.absolutePath}\" ")
            append("-filter_complex \"$filterComplex\" ")
            append("-map \"[a_voc]\" -c:a aac -b:a 192k \"${vocalsFile.absolutePath}\" ")
            append("-map \"[a_amb]\" -c:a aac -b:a 192k \"${ambienceFile.absolutePath}\" ")
            append("-map \"[a_mus]\" -c:a aac -b:a 192k \"${musicFile.absolutePath}\"")
        }

        DiagnosticLogger.log(
            "STEM-SPLIT",
            "🎚 Разделение звука на 3 дорожки (Голос / Интершум без диктора / Музыка) на участке ${effStart}..${effEnd}с..."
        )

        val ok = executeFFmpegCommand(cmd)
        if (!ok || !vocalsFile.exists() || !ambienceFile.exists()) {
            throw IllegalStateException("Не удалось разделить звуковые дорожки (проверьте наличие звука в исходнике).")
        }

        DiagnosticLogger.log(
            "STEM-SPLIT",
            "✅ Дорожки разделены: Голос=${vocalsFile.length() / 1024}KB, Интершум=${ambienceFile.length() / 1024}KB, Музыка=${musicFile.length() / 1024}KB"
        )

        SeparatedAudioStems(
            vocalsOverlayFile = vocalsFile,
            ambienceSfxFile = ambienceFile,
            musicHarmonicsFile = musicFile,
            sourceStartSec = effStart,
            sourceDurationSec = spanSec
        )
    }

    private fun buildModularFFmpegCommand(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        startOffsetSec: Double,
        durationSec: Double,
        isPreviewPass: Boolean,
        useHardwareCodec: Boolean,
        outputFile: File
    ): String {
        val inputs = mutableListOf<String>()
        val filterStages = mutableListOf<String>()

        val seekArg = if (startOffsetSec > 0.0) {
            String.format(Locale.US, "-ss %.2f ", startOffsetSec)
        } else {
            ""
        }
        inputs.add("$seekArg-t ${String.format(Locale.US, "%.2f", durationSec)} -i \"${primaryVideoFile.absolutePath}\"")

        var nextInputIndex = 1
        var clutterInputIndex = -1
        val needsClutterStream =
            (config.layoutLayer == VideoLayoutLayer.SPLIT_VSTACK ||
                config.layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER) &&
                clutterReelFile != null && clutterReelFile.exists()

        if (needsClutterStream) {
            clutterInputIndex = nextInputIndex++
            inputs.add("-stream_loop -1 -t ${String.format(Locale.US, "%.2f", durationSec)} -i \"${clutterReelFile!!.absolutePath}\"")
        }

        var bannerInputIndex = -1
        if (config.adLayer.mode == AdInsertionMode.BANNER_OVERLAY &&
            config.adLayer.bannerImageFile?.exists() == true
        ) {
            bannerInputIndex = nextInputIndex++
            inputs.add("-i \"${config.adLayer.bannerImageFile.absolutePath}\"")
        }

        var midrollInputIndex = -1
        val hasMidrollClip = (config.adLayer.mode == AdInsertionMode.MIDROLL_SPLICE ||
            config.adLayer.mode == AdInsertionMode.MIDROLL_PIP_OVERLAY) &&
            config.adLayer.midrollClipFile?.exists() == true &&
            !isPreviewPass
        if (hasMidrollClip) {
            midrollInputIndex = nextInputIndex++
            inputs.add("-t 3.0 -i \"${config.adLayer.midrollClipFile!!.absolutePath}\"")
        }

        var voiceoverInputIndex = -1
        val hasCustomVoiceover = (config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG ||
            config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_REPLACE_100PCT ||
            config.dspLayer.stemRoutingMode == AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE) &&
            config.customVoiceoverAudioFile?.exists() == true &&
            config.customVoiceoverAudioFile.length() > 500L
        if (hasCustomVoiceover) {
            voiceoverInputIndex = nextInputIndex++
            inputs.add("-t ${String.format(Locale.US, "%.2f", durationSec)} -i \"${config.customVoiceoverAudioFile!!.absolutePath}\"")
        }

        var replacementMusicInputIndex = -1
        val hasReplacementMusic = config.enableReplacementMusicBed &&
            config.customReplacementMusicFile?.exists() == true &&
            config.customReplacementMusicFile.length() > 500L
        if (hasReplacementMusic) {
            replacementMusicInputIndex = nextInputIndex++
            inputs.add("-stream_loop -1 -t ${String.format(Locale.US, "%.2f", durationSec)} -i \"${config.customReplacementMusicFile!!.absolutePath}\"")
        }

        when (config.layoutLayer) {
            VideoLayoutLayer.SPLIT_VSTACK -> {
                val upperFilter = framingPlan.ffmpegVideoFilter
                filterStages.add("[0:v:0]fps=30,$upperFilter[v_top]")

                if (clutterInputIndex >= 0) {
                    filterStages.add(
                        "[$clutterInputIndex:v:0]fps=30,scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:960:(iw-1080)/2:(ih-960)/2,setsar=1[v_bot]"
                    )
                    filterStages.add("[v_top][v_bot]vstack=inputs=2[v_base]")
                } else {
                    filterStages.add("[v_top]pad=1080:1920:0:0:color=#08080C[v_base]")
                }
            }

            VideoLayoutLayer.BLURRED_BG_FRAME -> {
                filterStages.add(
                    "[0:v:0]fps=30,split=2[v_bg_in][v_fg_in];" +
                        "[v_bg_in]scale=1080:1920:force_original_aspect_ratio=increase:flags=fast_bilinear," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,boxblur=24:2,eq=brightness=-0.16:saturation=1.25[v_bg];" +
                        "[v_fg_in]scale=1080:1520:force_original_aspect_ratio=decrease:flags=lanczos[v_fg];" +
                        "[v_bg][v_fg]overlay=(W-w)/2:(H-h)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.FULLSCREEN_EDIT -> {
                filterStages.add(
                    "[0:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.BRAINROT_VOICEOVER -> {
                val srcIdx = if (clutterInputIndex >= 0) clutterInputIndex else 0
                filterStages.add(
                    "[$srcIdx:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.DISTORTED_ORIGINAL -> {
                filterStages.add(
                    "[0:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=decrease:flags=lanczos," +
                        "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=#0A0A0E,setsar=1[v_base]"
                )
            }
        }

        var currentVideoLabel = "[v_base]"

        // Apply automatic existing burned-in subtitle removal if enabled
        if (config.existingSubtitleRemovalMode != ExistingSubtitleRemovalMode.OFF) {
            val reg = config.detectedHardsubRegion ?: DetectedHardsubRegion(detected = true)
            val (boxTopY, boxHeight) = when (config.layoutLayer) {
                VideoLayoutLayer.SPLIT_VSTACK -> 0 to 960
                VideoLayoutLayer.BLURRED_BG_FRAME -> 200 to 1520
                else -> 0 to 1920
            }
            val dx = (reg.xNorm * 1080).toInt().coerceIn(20, 840)
            val dw = (reg.widthNorm * 1080).toInt().coerceIn(200, 1060 - dx)
            val dy = (boxTopY + (reg.yNorm * boxHeight).toInt()).coerceIn(20, 1800)
            val dh = (reg.heightNorm * boxHeight).toInt().coerceIn(64, (boxTopY + boxHeight - 12 - dy).coerceAtLeast(64))

            when (config.existingSubtitleRemovalMode) {
                ExistingSubtitleRemovalMode.AUTO_DELOGO_INPAINT -> {
                    filterStages.add("$currentVideoLabel" + "delogo=x=$dx:y=$dy:w=$dw:h=$dh:show=0[v_nosub]")
                    currentVideoLabel = "[v_nosub]"
                }
                ExistingSubtitleRemovalMode.COVER_WITH_DARK_PLATE -> {
                    filterStages.add("$currentVideoLabel" + "drawbox=x=$dx:y=$dy:w=$dw:h=$dh:color=#09090B@0.92:t=fill[v_nosub]")
                    currentVideoLabel = "[v_nosub]"
                }
                ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS -> {
                    if (config.layoutLayer == VideoLayoutLayer.FULLSCREEN_EDIT ||
                        config.layoutLayer == VideoLayoutLayer.DISTORTED_ORIGINAL
                    ) {
                        // Zoom & shift top frame so bottom hardsub band is pushed below 1920px
                        filterStages.add("$currentVideoLabel" + "scale=1240:2204:flags=lanczos,crop=1080:1920:80:0[v_nosub]")
                        currentVideoLabel = "[v_nosub]"
                    }
                }
                ExistingSubtitleRemovalMode.OFF -> {}
            }
        }

        if (config.dspLayer.enabled) {
            val videoDspFilters = mutableListOf<String>()
            if (config.dspLayer.enableHorizontalFlip) {
                videoDspFilters.add("hflip")
            }
            if (config.dspLayer.enableMicroZoom) {
                videoDspFilters.add("scale=1124:1998:flags=lanczos,crop=1080:1920:22:39")
            }
            if (config.dspLayer.enableVideoNoiseInjection) {
                videoDspFilters.add("noise=alls=2:allf=t+u")
            }
            if (config.dspLayer.enableMicroColorEqShift) {
                val contrast = String.format(Locale.US, "%.3f", 1.018 + Random.nextDouble(0.0, 0.012))
                val saturation = String.format(Locale.US, "%.3f", 1.022 + Random.nextDouble(0.0, 0.015))
                val gamma = String.format(Locale.US, "%.3f", 1.010)
                videoDspFilters.add("eq=contrast=$contrast:saturation=$saturation:gamma=$gamma")
            }
            if (videoDspFilters.isNotEmpty()) {
                filterStages.add("$currentVideoLabel${videoDspFilters.joinToString(",")}[v_dsp]")
                currentVideoLabel = "[v_dsp]"
            }
        }

        if (config.subtitleLayer.enabled && assSubtitleFile != null && assSubtitleFile.exists()) {
            val escapedAssPath = assSubtitleFile.absolutePath
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'")
            filterStages.add("$currentVideoLabel" + "ass=filename='$escapedAssPath':fontsdir='/system/fonts'[v_sub]")
            currentVideoLabel = "[v_sub]"
        }

        var currentAudioLabel = "[0:a:0]"

        val audioFilters = mutableListOf<String>()
        audioFilters.add("aresample=48000")
        audioFilters.add("aformat=sample_fmts=fltp:channel_layouts=stereo")

        // Apply Stem Separation Routing BEFORE voiceover/anti-detect
        when (config.dspLayer.stemRoutingMode) {
            AudioStemRoutingMode.FULL_ORIGINAL_MIX -> {
                // If user enabled replacement music bed, automatically strip the old background music from the video!
                if (replacementMusicInputIndex >= 0 && config.dspLayer.musicRemovalMode == MusicRemovalMode.OFF) {
                    audioFilters.add("highpass=f=145,lowpass=f=4200,afftdn=nf=-26,equalizer=f=2300:t=q:w=1.0:g=2.8")
                }
            }
            AudioStemRoutingMode.AMBIENCE_ONLY_NO_VOICE,
            AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE -> {
                // Cancel center-panned top narrator voice (c0 - c1 + vocal formant notch), preserving video ambience/SFX!
                audioFilters.add(
                    "pan=stereo|c0=0.5*c0-0.5*c1|c1=0.5*c1-0.5*c0," +
                        "bandreject=f=1050:width_type=h:w=1400,bandreject=f=2450:width_type=h:w=900,volume=1.60"
                )
            }
            AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC -> {
                // Cut sub-bass (<145Hz) and music shimmer (>4200Hz), suppress steady musical bed
                audioFilters.add("highpass=f=145,lowpass=f=4200,afftdn=nf=-25,equalizer=f=2300:t=q:w=1.0:g=2.8")
            }
            AudioStemRoutingMode.ISOLATED_VOCAL_ONLY -> {
                // Isolate top narrator voice only (Mid-channel + narrow vocal bandpass + gate)
                audioFilters.add(
                    "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1," +
                        "highpass=f=160,lowpass=f=3800,afftdn=nf=-28,agate=threshold=0.020:ratio=2.6:attack=12:release=180,equalizer=f=2300:t=q:w=1.0:g=3.2"
                )
            }
        }

        when (config.dspLayer.musicRemovalMode) {
            MusicRemovalMode.OFF -> {
                // Keep full original frequency spectrum
            }
            MusicRemovalMode.VOICE_BANDPASS_GATE -> {
                audioFilters.add("highpass=f=140,lowpass=f=4200,afftdn=nf=-26,equalizer=f=2400:t=q:w=1.0:g=3.0")
            }
            MusicRemovalMode.STEREO_CENTER_VOCAL -> {
                audioFilters.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=150,lowpass=f=4000,afftdn=nf=-28,equalizer=f=2200:t=q:w=1.1:g=3.2")
            }
            MusicRemovalMode.AGGRESSIVE_DE_MUSIC -> {
                audioFilters.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=180,lowpass=f=3600,afftdn=nf=-32,agate=threshold=0.022:ratio=2.8:attack=15:release=220,equalizer=f=2300:t=q:w=1.0:g=3.5")
            }
        }

        when (config.dspLayer.voiceoverDubMode) {
            VoiceoverDubMode.TIMBRE_DEEP_BARITONE -> {
                audioFilters.add("asetrate=48000*0.92,aresample=48000,atempo=1.08695,equalizer=f=165:t=q:w=1.0:g=3.2,equalizer=f=2800:t=q:w=1.2:g=1.8")
            }
            VoiceoverDubMode.TIMBRE_ENERGETIC_HIGH -> {
                audioFilters.add("asetrate=48000*1.08,aresample=48000,atempo=0.9259,equalizer=f=3200:t=q:w=1.1:g=2.4")
            }
            VoiceoverDubMode.TIMBRE_RADIO_COMPRESSOR -> {
                audioFilters.add("asetrate=48000*0.96,aresample=48000,atempo=1.04167,highpass=f=85,acompressor=threshold=-18dB:ratio=3.2:attack=10:release=120:makeup=2.5,equalizer=f=210:t=q:w=1.1:g=2.6,extrastereo=m=1.16")
            }
            else -> {}
        }

        if (config.dspLayer.enabled) {
            if (config.dspLayer.enableSilenceTrimming350ms) {
                audioFilters.add(
                    "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-48dB:" +
                        "stop_periods=-1:stop_duration=0.45:stop_threshold=-48dB"
                )
            }
            if (config.dspLayer.enableAudioPitchTempoMicroJitter &&
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.OFF
            ) {
                val pitchFactor = 1.018
                val tempoFactor = 1.010
                audioFilters.add("asetrate=48000*$pitchFactor,aresample=48000,atempo=$tempoFactor")
            }
            if (config.dspLayer.enableAudioEqVoicePresence) {
                audioFilters.add("highpass=f=65,equalizer=f=3100:t=q:w=1.2:g=1.6,extrastereo=m=1.08")
            }
        }

        if (voiceoverInputIndex >= 0) {
            when {
                config.dspLayer.stemRoutingMode == AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE -> {
                    // Top narrator is already cancelled in [a_amb_clean]; keep clean ambience at 85% and overlay new voiceover at 125%!
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")},volume=0.85[a_amb_clean]")
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=1.25,apad=whole_dur=${String.format(Locale.US, "%.2f", durationSec)}[a_vo]"
                    )
                    filterStages.add("[a_amb_clean][a_vo]amix=inputs=2:duration=first:dropout_transition=0[a_main]")
                    currentAudioLabel = "[a_main]"
                }
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG -> {
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")},volume=0.15[a_orig_ducked]")
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=1.30,apad=whole_dur=${String.format(Locale.US, "%.2f", durationSec)}[a_vo]"
                    )
                    filterStages.add("[a_orig_ducked][a_vo]amix=inputs=2:duration=first:dropout_transition=0[a_main]")
                    currentAudioLabel = "[a_main]"
                }
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_REPLACE_100PCT -> {
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=1.20,apad=whole_dur=${String.format(Locale.US, "%.2f", durationSec)}[a_main]"
                    )
                    currentAudioLabel = "[a_main]"
                }
                else -> {
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")}[a_main]")
                    currentAudioLabel = "[a_main]"
                }
            }
        } else {
            filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")}[a_main]")
            currentAudioLabel = "[a_main]"
        }

        // Mix in the new matched replacement melody stem underneath speech/ambience if enabled
        if (replacementMusicInputIndex >= 0) {
            filterStages.add(
                "[$replacementMusicInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=0.24,afade=t=in:st=0:d=0.8[a_bgm_new]"
            )
            filterStages.add("$currentAudioLabel[a_bgm_new]amix=inputs=2:duration=first:dropout_transition=0[a_with_bgm]")
            currentAudioLabel = "[a_with_bgm]"
        }

        when (config.adLayer.mode) {
            AdInsertionMode.BANNER_OVERLAY -> {
                val startT = if (isPreviewPass) 0 else config.adLayer.bannerStartSec
                val endT = if (isPreviewPass) 5 else config.adLayer.bannerEndSec

                if (bannerInputIndex >= 0) {
                    filterStages.add(
                        "[$bannerInputIndex:v:0]scale=960:-2[v_banner];" +
                            "$currentVideoLabel[v_banner]overlay=(W-w)/2:120:enable='between(t,$startT,$endT)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                } else {
                    val safeBannerText = config.adLayer.bannerText
                        .replace(":", "\\:")
                        .replace("'", "")
                    filterStages.add(
                        "$currentVideoLabel" +
                            "drawbox=x=60:y=110:w=960:h=96:color=black@0.78:t=fill:enable='between(t,$startT,$endT)'," +
                            "drawtext=fontfile='/system/fonts/Roboto-Regular.ttf':text='$safeBannerText':fontsize=36:fontcolor=yellow:" +
                            "x=(w-text_w)/2:y=142:enable='between(t,$startT,$endT)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                }
            }

            AdInsertionMode.MIDROLL_PIP_OVERLAY -> {
                if (midrollInputIndex >= 0) {
                    val midPoint = String.format(Locale.US, "%.1f", (durationSec / 2.0).coerceAtLeast(2.0))
                    val midEnd = String.format(Locale.US, "%.1f", (durationSec / 2.0 + 3.0))
                    filterStages.add(
                        "[$midrollInputIndex:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase," +
                            "crop=1080:1920,setpts=PTS-STARTPTS+($midPoint/TB)[v_mid];" +
                            "$currentVideoLabel[v_mid]overlay=0:0:enable='between(t,$midPoint,$midEnd)'[v_ad]"
                    )
                    currentVideoLabel = "[v_ad]"
                }
            }

            AdInsertionMode.MIDROLL_SPLICE -> {
                if (midrollInputIndex >= 0 && durationSec >= 8.0) {
                    val splitSec = String.format(Locale.US, "%.2f", durationSec / 2.0)
                    filterStages.add(
                        "$currentVideoLabel" + "split=2[v_p1_raw][v_p2_raw];" +
                            "[v_p1_raw]trim=0:$splitSec,setpts=PTS-STARTPTS[v_p1];" +
                            "[v_p2_raw]trim=$splitSec,setpts=PTS-STARTPTS[v_p2];" +
                            "[$midrollInputIndex:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase," +
                            "crop=1080:1920,setsar=1,setpts=PTS-STARTPTS[v_mid];" +
                            "$currentAudioLabel" + "asplit=2[a_p1_raw][a_p2_raw];" +
                            "[a_p1_raw]atrim=0:$splitSec,asetpts=PTS-STARTPTS[a_p1];" +
                            "[a_p2_raw]atrim=$splitSec,asetpts=PTS-STARTPTS[a_p2];" +
                            "[$midrollInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a_mid];" +
                            "[v_p1][a_p1][v_mid][a_mid][v_p2][a_p2]concat=n=3:v=1:a=1[v_spliced][a_spliced]"
                    )
                    currentVideoLabel = "[v_spliced]"
                    currentAudioLabel = "[a_spliced]"
                }
            }

            AdInsertionMode.NONE, AdInsertionMode.PINNED_COMMENT_ONLY -> {
            }
        }

        val targetPixFmt = if (useHardwareCodec) "nv12" else "yuv420p"
        filterStages.add("$currentVideoLabel" + "format=$targetPixFmt[v_out]")

        val filterComplex = filterStages.joinToString(";")

        val targetBitrateKbps = when {
            isPreviewPass -> 6500
            config.capBitrateForTelegram50Mb -> {
                val maxAllowedKbpsFor48Mb = ((47.0 * 8192.0) / durationSec).toInt() - 192
                config.videoBitrateKbps.coerceAtMost(maxAllowedKbpsFor48Mb.coerceAtLeast(3500))
            }
            else -> config.videoBitrateKbps.coerceIn(6000, 18000)
        }

        return buildString {
            append("-hide_banner -loglevel warning -nostdin -y ")
            append(inputs.joinToString(" "))
            append(" -filter_complex \"$filterComplex\" ")
            append("-map \"[v_out]\" -map \"$currentAudioLabel\" ")
            append("-map_metadata -1 ")
            if (useHardwareCodec) {
                append("-c:v h264_mediacodec -b:v ${targetBitrateKbps}k ")
            } else {
                append("-c:v libx264 -preset superfast -crf 18 -maxrate ${targetBitrateKbps}k -bufsize ${targetBitrateKbps * 2}k ")
            }
            append("-c:a aac -b:a 192k -ar 48000 ")
            append("-movflags +faststart ")
            append("\"${outputFile.absolutePath}\"")
        }
    }

    private suspend fun executeFFmpegCommand(cmd: String): Boolean =
        suspendCancellableCoroutine { cont ->
            FFmpegKit.executeAsync(cmd) { session ->
                val ok = ReturnCode.isSuccess(session.returnCode)
                if (!ok) {
                    Log.e(TAG, "FFmpeg execution failed:\n${session.allLogsAsString}")
                    DiagnosticLogger.log("FFMPEG:ERR", "Ошибка FFmpeg: ${session.allLogsAsString?.takeLast(300)}")
                }
                if (cont.isActive) cont.resume(ok)
            }
        }

    fun probeDurationSeconds(videoFile: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 30_000L
            ms / 1000.0
        } catch (_: Exception) {
            30.0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
