package forge.media.pocket.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import forge.media.pocket.config.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExistingSubtitleRemovalMode(val label: String) {
    OFF("Выкл (Не трогать старые субтитры)"),
    AUTO_CROP_ABOVE_SUBS("Авто-Кроп выше старых субтитров (Без мыла, 100% чётко)"),
    AUTO_DELOGO_INPAINT("Авто-Затирка delogo (Интерполяция пикселей вокруг букв)"),
    COVER_WITH_DARK_PLATE("Матовая плашка поверх старых (Под свои новые субтитры)")
}

data class DetectedHardsubRegion(
    val detected: Boolean,
    val xNorm: Double = 0.10,
    val yNorm: Double = 0.72,
    val widthNorm: Double = 0.80,
    val heightNorm: Double = 0.16,
    val confidencePct: Int = 0,
    val summary: String = "Вшитые субтитры не обнаружены"
)

sealed class UpperBoxFramingPlan {
    abstract val ffmpegVideoFilter: String
    abstract val summary: String
    abstract val hasBottomFaceRisk: Boolean

    data class TightCrop(
        val cropWidth: Int,
        val cropHeight: Int,
        val cropX: Int,
        val cropY: Int,
        val speakerCount: Int
    ) : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = "crop=${cropWidth}:${cropHeight}:${cropX}:${cropY},scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos,crop=1080:960,setsar=1"

        override val summary: String
            get() = "Авто-центровка (спикеров: $speakerCount)"

        override val hasBottomFaceRisk: Boolean
            get() = true
    }

    data class WideFitMultiSpeaker(
        val spanX: Int,
        val spanWidth: Int,
        val sourceHeight: Int
    ) : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = buildString {
                append("crop=${spanWidth}:${sourceHeight}:${spanX}:0,")
                append("scale=1080:960:force_original_aspect_ratio=decrease:flags=lanczos,")
                append("pad=1080:960:(ow-iw)/2:(oh-ih)/2:color=#101014,setsar=1")
            }

        override val summary: String
            get() = "Широкий кадр (2+ спикера без обрезки)"

        override val hasBottomFaceRisk: Boolean
            get() = false
    }

    data object UncroppedFullFrame : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = "scale=1080:960:force_original_aspect_ratio=decrease:flags=lanczos,pad=1080:960:(ow-iw)/2:(oh-ih)/2:color=#101014,setsar=1"

        override val summary: String
            get() = "Без кропа (весь кадр целиком)"

        override val hasBottomFaceRisk: Boolean
            get() = false
    }
}

class FaceCenteringHelper(private val context: Context) {

    companion object {
        private const val TARGET_UPPER_W = 1080
        private const val TARGET_UPPER_H = 960
        private const val TARGET_ASPECT = TARGET_UPPER_W.toDouble() / TARGET_UPPER_H.toDouble()
        private const val THUMB_WIDTH = 480
        private const val MULTI_FACE_MARGIN_RATIO = 0.12
        private const val EMA_ALPHA = 0.35
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.06f)
            .build()
        FaceDetection.getClient(options)
    }

    private data class FrameFaceObservation(
        val faceCount: Int,
        val primaryCenterXNorm: Double,
        val primaryCenterYNorm: Double,
        val minLeftNorm: Double,
        val maxRightNorm: Double
    )

    suspend fun computeFramingPlan(
        videoFile: File,
        enableSmartFaceCrop: Boolean = true,
        maxSampleSeconds: Int = 30,
        hardsubRegion: DetectedHardsubRegion? = null,
        hardsubRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF
    ): UpperBoxFramingPlan = withContext(Dispatchers.IO) {
        if (!enableSmartFaceCrop && hardsubRemovalMode != ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS) {
            return@withContext UpperBoxFramingPlan.UncroppedFullFrame
        }

        val (srcWidth, srcHeight) = probeVideoDimensions(videoFile)

        // Safe ceiling Y (in pixels) if we want to crop strictly above burned-in lower subtitles
        val safeMaxBottomPx = if (hardsubRemovalMode == ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS &&
            hardsubRegion?.detected == true &&
            hardsubRegion.yNorm in 0.48..0.94
        ) {
            ((hardsubRegion.yNorm - 0.02).coerceAtLeast(0.45) * srcHeight).roundToInt().let { it - (it % 2) }
        } else {
            srcHeight
        }

        // If the source is ALREADY vertical (e.g. 1080x1920 YouTube Short), crop a 9:8 window vertically without squashing!
        if (srcHeight > srcWidth) {
            val cropW = srcWidth - (srcWidth % 2)
            val maxAllowedH = safeMaxBottomPx.coerceAtLeast((srcWidth * 0.55).roundToInt())
            val cropH = ((srcWidth / TARGET_ASPECT).roundToInt().coerceAtMost(maxAllowedH)).let { it - (it % 2) }
            val defaultCropY = if (safeMaxBottomPx < srcHeight) {
                // Shift window upward so its bottom edge stays above the burned-in subtitles
                ((safeMaxBottomPx - cropH).coerceAtLeast(0)).let { it - (it % 2) }
            } else {
                (((srcHeight - cropH) * 0.35).roundToInt().coerceIn(0, srcHeight - cropH)).let { it - (it % 2) }
            }
            return@withContext UpperBoxFramingPlan.TightCrop(
                cropWidth = cropW,
                cropHeight = cropH,
                cropX = 0,
                cropY = defaultCropY,
                speakerCount = 1
            )
        }

        val effectiveSrcHeight = safeMaxBottomPx.coerceIn((srcHeight * 0.62).roundToInt(), srcHeight)
        val baseCropHeight = effectiveSrcHeight - (effectiveSrcHeight % 2)
        val baseCropWidth = ((baseCropHeight * TARGET_ASPECT).roundToInt().coerceAtMost(srcWidth)).let { it - (it % 2) }
        val defaultCenterX = (((srcWidth - baseCropWidth) / 2).coerceAtLeast(0)).let { it - (it % 2) }

        val framesDir = File(context.cacheDir, "face_samples_${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            val extractedFrames = extractBatchThumbnails1Fps(
                videoFile = videoFile,
                outputDir = framesDir,
                maxDurationSec = maxSampleSeconds
            )

            if (extractedFrames.isEmpty()) {
                return@withContext UpperBoxFramingPlan.TightCrop(
                    cropWidth = baseCropWidth,
                    cropHeight = baseCropHeight,
                    cropX = defaultCenterX,
                    cropY = 0,
                    speakerCount = 0
                )
            }

            val observations = mutableListOf<FrameFaceObservation>()

            for (frameFile in extractedFrames) {
                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: continue
                val bmpW = bitmap.width.toDouble()
                val bmpH = bitmap.height.toDouble()

                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val faces = detector.process(inputImage).await()

                    val validFaces = faces.filter { face ->
                        val areaRatio = (face.boundingBox.width() * face.boundingBox.height()) / (bmpW * bmpH)
                        areaRatio >= 0.012
                    }

                    if (validFaces.isNotEmpty()) {
                        val dominant = validFaces.maxByOrNull {
                            it.boundingBox.width() * it.boundingBox.height()
                        }!!

                        val activeSpeakers = validFaces.filter {
                            val domArea = dominant.boundingBox.width() * dominant.boundingBox.height()
                            val curArea = it.boundingBox.width() * it.boundingBox.height()
                            curArea >= domArea * 0.35
                        }

                        val minLeft = activeSpeakers.minOf {
                            (it.boundingBox.left.toDouble() / bmpW).coerceIn(0.0, 1.0)
                        }
                        val maxRight = activeSpeakers.maxOf {
                            (it.boundingBox.right.toDouble() / bmpW).coerceIn(0.0, 1.0)
                        }
                        val domCenterXNorm =
                            (dominant.boundingBox.centerX().toDouble() / bmpW).coerceIn(0.0, 1.0)
                        val domCenterYNorm =
                            (dominant.boundingBox.centerY().toDouble() / bmpH).coerceIn(0.0, 1.0)

                        observations.add(
                            FrameFaceObservation(
                                faceCount = activeSpeakers.size,
                                primaryCenterXNorm = domCenterXNorm,
                                primaryCenterYNorm = domCenterYNorm,
                                minLeftNorm = minLeft,
                                maxRightNorm = maxRight
                            )
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            if (observations.isEmpty()) {
                return@withContext UpperBoxFramingPlan.TightCrop(
                    cropWidth = baseCropWidth,
                    cropHeight = baseCropHeight,
                    cropX = defaultCenterX,
                    cropY = 0,
                    speakerCount = 0
                )
            }

            val multiSpeakerFrames = observations.filter { it.faceCount >= 2 }
            val multiSpeakerRatio = multiSpeakerFrames.size.toDouble() / observations.size.toDouble()

            if (multiSpeakerFrames.isNotEmpty() && multiSpeakerRatio >= 0.25) {
                val medianLeftNorm = median(multiSpeakerFrames.map { it.minLeftNorm })
                val medianRightNorm = median(multiSpeakerFrames.map { it.maxRightNorm })

                val paddedLeftPx = ((medianLeftNorm - MULTI_FACE_MARGIN_RATIO) * srcWidth)
                    .roundToInt()
                    .coerceIn(0, srcWidth - 2)
                val paddedRightPx = ((medianRightNorm + MULTI_FACE_MARGIN_RATIO) * srcWidth)
                    .roundToInt()
                    .coerceIn(paddedLeftPx + 2, srcWidth)

                val requiredSpanWidth = paddedRightPx - paddedLeftPx

                return@withContext if (requiredSpanWidth <= baseCropWidth) {
                    val groupCenterPx = (paddedLeftPx + paddedRightPx) / 2
                    val alignedCropX = (groupCenterPx - baseCropWidth / 2)
                        .coerceIn(0, srcWidth - baseCropWidth)
                        .let { it - (it % 2) }

                    UpperBoxFramingPlan.TightCrop(
                        cropWidth = baseCropWidth,
                        cropHeight = baseCropHeight,
                        cropX = alignedCropX,
                        cropY = 0,
                        speakerCount = 2
                    )
                } else {
                    val evenSpanW = requiredSpanWidth - (requiredSpanWidth % 2)
                    val evenSpanX = paddedLeftPx - (paddedLeftPx % 2)
                    UpperBoxFramingPlan.WideFitMultiSpeaker(
                        spanX = evenSpanX,
                        spanWidth = evenSpanW.coerceAtLeast(baseCropWidth),
                        sourceHeight = baseCropHeight
                    )
                }
            }

            val centers = observations.map { it.primaryCenterXNorm }
            var emaCenterNorm = median(centers)
            for (c in centers) {
                if (abs(c - emaCenterNorm) < 0.25) {
                    emaCenterNorm = EMA_ALPHA * c + (1.0 - EMA_ALPHA) * emaCenterNorm
                }
            }

            val targetCenterPx = (emaCenterNorm * srcWidth).roundToInt()
            val rawCropX = (targetCenterPx - baseCropWidth / 2).coerceIn(0, srcWidth - baseCropWidth)
            val evenCropX = rawCropX - (rawCropX % 2)

            UpperBoxFramingPlan.TightCrop(
                cropWidth = baseCropWidth,
                cropHeight = baseCropHeight,
                cropX = evenCropX,
                cropY = 0,
                speakerCount = 1
            )
        } finally {
            framesDir.deleteRecursively()
        }
    }

    private suspend fun extractBatchThumbnails1Fps(
        videoFile: File,
        outputDir: File,
        maxDurationSec: Int
    ): List<File> = suspendCancellableCoroutine { cont ->
        val outPattern = File(outputDir, "sample_%03d.jpg").absolutePath
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin ")
            append("-t $maxDurationSec ")
            append("-i \"${videoFile.absolutePath}\" ")
            append("-vf \"fps=1,scale=$THUMB_WIDTH:-2:flags=fast_bilinear\" ")
            append("-q:v 5 \"$outPattern\"")
        }

        FFmpegKit.executeAsync(cmd) { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                val files = outputDir.listFiles { f -> f.extension == "jpg" }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (cont.isActive) cont.resume(files)
            } else {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    fun isVerticalSource(videoFile: File): Boolean {
        val (w, h) = probeVideoDimensions(videoFile)
        return h > w
    }

    private fun probeVideoDimensions(videoFile: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                Pair(h, w)
            } else {
                Pair(w, h)
            }
        } catch (_: Exception) {
            Pair(1920, 1080)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.5
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    /**
     * Авто-детектор вшитых субтитров (Hardsub Scanner):
     * Сканирует серию кадров выбранного фрагмента на наличие динамических текстовых контуров
     * (высококонтрастный глиф: яркое ядро >195 рядом с чёрной обводкой/тенью <55, которое меняется
     * от кадра к кадру — в отличие от статичного логотипа в углу).
     */
    suspend fun detectBurnedInSubtitleRegion(
        videoFile: File,
        startSec: Double = 0.0,
        durationSec: Double = 25.0
    ): DetectedHardsubRegion = withContext(Dispatchers.IO) {
        val sampleDir = File(context.cacheDir, "hardsub_scan_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val outPattern = File(sampleDir, "sub_%03d.jpg").absolutePath
            val seekArg = if (startSec > 0.0) String.format(Locale.US, "-ss %.2f ", startSec) else ""
            val span = durationSec.coerceIn(4.0, 24.0)
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin ")
                append("$seekArg-t ${String.format(Locale.US, "%.2f", span)} ")
                append("-i \"${videoFile.absolutePath}\" ")
                append("-vf \"fps=1.5,scale=360:-2:flags=fast_bilinear\" ")
                append("-q:v 4 \"$outPattern\"")
            }

            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                FFmpegKit.executeAsync(cmd) { session ->
                    if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
                }
            }

            val frameFiles = if (ok) {
                sampleDir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
            } else {
                emptyList()
            }

            if (frameFiles.size < 2) {
                return@withContext DetectedHardsubRegion(
                    detected = true,
                    xNorm = 0.08,
                    yNorm = 0.72,
                    widthNorm = 0.84,
                    heightNorm = 0.16,
                    confidencePct = 65,
                    summary = "Стандартная зона субтитров (нижняя треть: Y=72%..88%)"
                )
            }

            val bitmaps = frameFiles.take(10).mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
            if (bitmaps.size < 2) {
                bitmaps.forEach { it.recycle() }
                return@withContext DetectedHardsubRegion(
                    detected = true,
                    xNorm = 0.08,
                    yNorm = 0.72,
                    widthNorm = 0.84,
                    heightNorm = 0.16,
                    confidencePct = 65,
                    summary = "Стандартная зона субтитров (нижняя треть: Y=72%..88%)"
                )
            }

            val w = bitmaps.first().width
            val h = bitmaps.first().height
            val numBands = 20
            val bandScores = DoubleArray(numBands)
            val bandMinX = DoubleArray(numBands) { 0.15 }
            val bandMaxX = DoubleArray(numBands) { 0.85 }

            try {
                for (b in 0 until numBands) {
                    val yStartNorm = b.toDouble() / numBands.toDouble()
                    val yEndNorm = (b + 1).toDouble() / numBands.toDouble()
                    // Burned-in subtitles almost always live between 32% and 94% of frame height
                    if (yStartNorm < 0.30 || yEndNorm > 0.96) continue

                    val y0 = (yStartNorm * h).toInt().coerceIn(2, h - 3)
                    val y1 = (yEndNorm * h).toInt().coerceIn(y0 + 1, h - 2)
                    val x0 = (w * 0.08).toInt().coerceAtLeast(2)
                    val x1 = (w * 0.92).toInt().coerceAtMost(w - 3)

                    var strokeTransitions = 0
                    var temporalDiffHits = 0
                    var minHitX = x1
                    var maxHitX = x0

                    for (fIdx in 0 until bitmaps.size) {
                        val bmp = bitmaps[fIdx]
                        val nextBmp = bitmaps[(fIdx + 1) % bitmaps.size]

                        for (y in y0 until y1 step 2) {
                            for (x in x0 until x1 step 2) {
                                val c = bmp.getPixel(x, y)
                                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                                if (lum >= 195) {
                                    // Check 2px neighbors for dark stroke/shadow outline (< 58 lum)
                                    val cRight = bmp.getPixel(x + 2, y)
                                    val cDown = bmp.getPixel(x, y + 2)
                                    val lumRight = (Color.red(cRight) * 299 + Color.green(cRight) * 587 + Color.blue(cRight) * 114) / 1000
                                    val lumDown = (Color.red(cDown) * 299 + Color.green(cDown) * 587 + Color.blue(cDown) * 114) / 1000

                                    if (lumRight <= 62 || lumDown <= 62) {
                                        strokeTransitions++
                                        val cNext = nextBmp.getPixel(x, y)
                                        val lumNext = (Color.red(cNext) * 299 + Color.green(cNext) * 587 + Color.blue(cNext) * 114) / 1000
                                        if (abs(lum - lumNext) >= 45) {
                                            temporalDiffHits++
                                            if (x < minHitX) minHitX = x
                                            if (x > maxHitX) maxHitX = x
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Favor lower-third and center-seam bands where subtitles typically sit
                    val positionWeight = when {
                        yStartNorm in 0.65..0.88 -> 1.35
                        yStartNorm in 0.45..0.65 -> 1.15
                        else -> 0.90
                    }
                    bandScores[b] = (strokeTransitions * 0.4 + temporalDiffHits * 1.6) * positionWeight
                    if (maxHitX > minHitX + (w * 0.18)) {
                        bandMinX[b] = ((minHitX.toDouble() / w) - 0.05).coerceIn(0.04, 0.30)
                        bandMaxX[b] = ((maxHitX.toDouble() / w) + 0.05).coerceIn(0.70, 0.96)
                    }
                }
            } finally {
                bitmaps.forEach { it.recycle() }
            }

            // Find best 2-band window (approx 10-16% height)
            var bestBandIdx = 14 // Default ~70% Y
            var bestScore = 0.0
            for (b in 6 until (numBands - 2)) {
                val windowScore = bandScores[b] + bandScores[b + 1]
                if (windowScore > bestScore) {
                    bestScore = windowScore
                    bestBandIdx = b
                }
            }

            val detected = bestScore >= 28.0
            val finalYNorm = if (detected) {
                ((bestBandIdx.toDouble() / numBands.toDouble()) - 0.02).coerceIn(0.32, 0.84)
            } else {
                0.72
            }
            val finalHNorm = if (detected) 0.16 else 0.15
            val finalXNorm = if (detected) bandMinX[bestBandIdx] else 0.08
            val finalWNorm = if (detected) (bandMaxX[bestBandIdx] - finalXNorm).coerceIn(0.50, 0.90) else 0.84
            val conf = if (detected) (65 + (bestScore / 15.0).roundToInt()).coerceIn(68, 97) else 60

            val zoneLabel = when {
                finalYNorm >= 0.65 -> "нижняя треть кадра"
                finalYNorm >= 0.45 -> "центр / средняя зона"
                else -> "верхне-средняя зона"
            }
            val yStartPct = (finalYNorm * 100).roundToInt()
            val yEndPct = ((finalYNorm + finalHNorm) * 100).roundToInt()

            val summaryText = if (detected) {
                "Найдены вшитые субтитры ($zoneLabel): Y=${yStartPct}%..${yEndPct}% (точность $conf%)"
            } else {
                "Явных динамических субтитров не найдено — выбрана стандартная зона Y=${yStartPct}%..${yEndPct}%"
            }

            DiagnosticLogger.log("HARDSUB-SCAN", "🔍 $summaryText")

            DetectedHardsubRegion(
                detected = true,
                xNorm = finalXNorm,
                yNorm = finalYNorm,
                widthNorm = finalWNorm,
                heightNorm = finalHNorm,
                confidencePct = conf,
                summary = summaryText
            )
        } finally {
            sampleDir.deleteRecursively()
        }
    }
}
