package forge.media.pocket.compositor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.vision.UpperBoxFramingPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

enum class VideoLayoutLayer(val label: String) {
    SPLIT_VSTACK("Сплит 50/50 (Верх + Фон)"),
    FULLSCREEN_EDIT("На весь экран 9:16 (Эдит)"),
    BRAINROT_VOICEOVER("Фон 9:16 + Озвучка из видео"),
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

data class AntiDetectDspLayerConfig(
    val enabled: Boolean = true,
    val enableMicroZoom: Boolean = true,
    val enableSilenceTrimming350ms: Boolean = true,
    val enableAudioPitchTempoMicroJitter: Boolean = true,
    val enableVideoNoiseInjection: Boolean = true,
    val enableMicroColorEqShift: Boolean = true
)

data class ModularRenderConfig(
    val layoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
    val enableSmartFaceCrop: Boolean = true,
    val subtitleLayer: SubtitleLayerConfig = SubtitleLayerConfig(enabled = true),
    val adLayer: AdLayerConfig = AdLayerConfig(mode = AdInsertionMode.NONE),
    val dspLayer: AntiDetectDspLayerConfig = AntiDetectDspLayerConfig(enabled = true),
    val enableAiMetadataGeneration: Boolean = true,
    val videoBitrateKbps: Int = 5800
) {
    companion object {
        fun presetPodcastSplit() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
            enableSmartFaceCrop = true,
            subtitleLayer = SubtitleLayerConfig(enabled = true),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )

        fun presetFullscreenEdit() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.FULLSCREEN_EDIT,
            enableSmartFaceCrop = false,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(
                enabled = true,
                enableSilenceTrimming350ms = false
            )
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
        private const val PREVIEW_DURATION_SEC = 5
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

    suspend fun renderPipeline(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        isPreviewPass: Boolean
    ): File = withContext(Dispatchers.IO) {
        val totalDurationSec = probeDurationSeconds(primaryVideoFile)
        val renderDurationSec = if (isPreviewPass) {
            PREVIEW_DURATION_SEC.toDouble().coerceAtMost(totalDurationSec)
        } else {
            totalDurationSec.coerceIn(5.0, 75.0)
        }

        val previewStartOffsetSec = if (isPreviewPass && totalDurationSec > 12.0) 3.0 else 0.0

        val suffix = if (isPreviewPass) "preview_5s" else "final_master"
        val outFile = File(stagingDir, "render_${suffix}_${System.currentTimeMillis()}.mp4")

        val hwCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = previewStartOffsetSec,
            durationSec = renderDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = true,
            outputFile = outFile
        )

        Log.i(TAG, "Executing Qualcomm h264_mediacodec command: $hwCommand")

        val hwSuccess = executeFFmpegCommand(hwCommand)
        if (hwSuccess && outFile.exists() && outFile.length() > 10_000L) {
            return@withContext outFile
        }

        // Automatic fallback to libx264 ultrafast if MediaCodec is busy (e.g. PiP active)
        outFile.delete()
        val swCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = previewStartOffsetSec,
            durationSec = renderDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = false,
            outputFile = outFile
        )
        Log.w(TAG, "h264_mediacodec busy/failed, falling back to libx264 ultrafast: $swCommand")

        val swSuccess = executeFFmpegCommand(swCommand)
        if (!swSuccess || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Ошибка рендера FFmpeg (проверьте исходное видео).")
        }

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

        if (config.dspLayer.enabled) {
            val videoDspFilters = mutableListOf<String>()
            if (config.dspLayer.enableMicroZoom) {
                videoDspFilters.add("scale=1118:1988:flags=bilinear,crop=1080:1920:18:34")
            }
            if (config.dspLayer.enableVideoNoiseInjection) {
                videoDspFilters.add("noise=alls=3:allf=t+u")
            }
            if (config.dspLayer.enableMicroColorEqShift) {
                val contrast = String.format(Locale.US, "%.3f", 1.012 + Random.nextDouble(0.0, 0.012))
                val saturation = String.format(Locale.US, "%.3f", 1.015 + Random.nextDouble(0.0, 0.015))
                videoDspFilters.add("eq=contrast=$contrast:saturation=$saturation")
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

        if (config.dspLayer.enabled) {
            if (config.dspLayer.enableSilenceTrimming350ms) {
                audioFilters.add(
                    "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-45dB:" +
                        "stop_periods=-1:stop_duration=0.35:stop_threshold=-45dB"
                )
            }
            if (config.dspLayer.enableAudioPitchTempoMicroJitter) {
                val pitchFactor = 1.015
                val tempoFactor = 1.008
                audioFilters.add("asetrate=48000*$pitchFactor,aresample=48000,atempo=$tempoFactor")
            }
        }

        filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")}[a_main]")
        currentAudioLabel = "[a_main]"

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

        val safeBitrateKbps = if (isPreviewPass) {
            3500
        } else {
            val maxAllowedKbpsFor48Mb = ((47.0 * 8192.0) / durationSec).toInt() - 160
            config.videoBitrateKbps.coerceAtMost(maxAllowedKbpsFor48Mb.coerceAtLeast(2200))
        }

        return buildString {
            append("-hide_banner -loglevel warning -nostdin -y ")
            append(inputs.joinToString(" "))
            append(" -filter_complex \"$filterComplex\" ")
            append("-map \"[v_out]\" -map \"$currentAudioLabel\" ")
            if (useHardwareCodec) {
                append("-c:v h264_mediacodec -b:v ${safeBitrateKbps}k ")
            } else {
                append("-c:v libx264 -preset ultrafast -crf 23 -maxrate ${safeBitrateKbps}k -bufsize ${safeBitrateKbps * 2}k ")
            }
            append("-c:a aac -b:a 160k -ar 48000 ")
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
