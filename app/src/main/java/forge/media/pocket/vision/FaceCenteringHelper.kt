package forge.media.pocket.vision

import android.content.Context
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

    data class ReplacedExistingSplit(
        val cropWidth: Int,
        val cropHeight: Int,
        val cropX: Int,
        val cropY: Int,
        val seamPercent: Int,
        val keptTopHalf: Boolean,
        val speakerCount: Int
    ) : UpperBoxFramingPlan() {
        override val ffmpegVideoFilter: String
            get() = "crop=${cropWidth}:${cropHeight}:${cropX}:${cropY}," +
                "scale=1080:960:force_original_aspect_ratio=increase:flags=lanczos," +
                "crop=1080:960:(iw-1080)/2:(ih-960)/2,setsar=1"

        override val summary: String
            get() {
                val actionDesc = if (keptTopHalf) {
                    "верх оставлен, старый низ заменён"
                } else {
                    "низ со спикером поднят, старый верх заменён"
                }
                return "Авто-замена 50/50 (шов ${seamPercent}%: $actionDesc)"
            }

        override val hasBottomFaceRisk: Boolean
            get() = true
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

    private data class DetectedSplitSeam(
        val detected: Boolean,
        val seamYNorm: Double = 0.50,
        val cleanTopLimitNorm: Double = 0.485,
        val cleanBottomStartNorm: Double = 0.515,
        val confidencePct: Int = 0
    )

    suspend fun computeFramingPlan(
        videoFile: File,
        enableSmartFaceCrop: Boolean = true,
        maxSampleSeconds: Int = 30,
        hardsubRegion: DetectedHardsubRegion? = null,
        hardsubRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF
    ): UpperBoxFramingPlan = withContext(Dispatchers.IO) {
        val (srcWidth, srcHeight) = probeVideoDimensions(videoFile)

        // Safe ceiling Y (in pixels) if we want to crop strictly above burned-in lower subtitles
        val safeMaxBottomPx = if (hardsubRemovalMode == ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS &&
            hardsubRegion?.detected == true &&
            hardsubRegion.yNorm in 0.42..0.94
        ) {
            ((hardsubRegion.yNorm - 0.02).coerceAtLeast(0.38) * srcHeight).roundToInt().let { it - (it % 2) }
        } else {
            srcHeight
        }

        // Vertical source (e.g. 1080x1920 Short / Reel):
        // ALWAYS inspect for an existing 50/50 split boundary so we automatically cut off the old background half
        // and replace it with our own background reel (even if smart face crop is toggled off).
        if (srcHeight > srcWidth) {
            return@withContext computeVerticalSourceFramingPlan(
                videoFile = videoFile,
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                safeMaxBottomPx = safeMaxBottomPx,
                enableSmartFaceCrop = enableSmartFaceCrop,
                maxSampleSeconds = maxSampleSeconds.coerceIn(6, 20)
            )
        }

        if (!enableSmartFaceCrop && hardsubRemovalMode != ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS) {
            return@withContext UpperBoxFramingPlan.UncroppedFullFrame
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

            val observations = collectFaceObservationsFromFiles(extractedFrames)

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

    /**
     * Анализирует уже вертикальный исходник (например, 1080x1920 YouTube Short):
     * 1) Ищет горизонтальную границу (шов) существующего сплита 50/50 (в диапазоне 34%..66% высоты).
     * 2) Если ролик УЖЕ склеен из двух половин — определяет, в какой половине находится спикер/контент
     *    (обычно сверху, но если лицо снизу — поднимает нижнюю половину!), чисто отрезает старую залипалку
     *    выше/ниже шва с защитным отступом и подготавливает верхний блок 1080x960 под замену фона.
     * 3) Если ролик цельный (не 50/50) — центрует окно 9:8 по лицу спикера через ML Kit.
     */
    private suspend fun computeVerticalSourceFramingPlan(
        videoFile: File,
        srcWidth: Int,
        srcHeight: Int,
        safeMaxBottomPx: Int,
        enableSmartFaceCrop: Boolean,
        maxSampleSeconds: Int
    ): UpperBoxFramingPlan {
        val evenSrcW = (srcWidth - (srcWidth % 2)).coerceAtLeast(2)
        val idealBoxH = ((evenSrcW / TARGET_ASPECT).roundToInt()).let { it - (it % 2) }.coerceIn(2, srcHeight)

        val framesDir = File(context.cacheDir, "vsplit_samples_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val frameFiles = extractBatchThumbnails1Fps(
                videoFile = videoFile,
                outputDir = framesDir,
                maxDurationSec = maxSampleSeconds
            )

            val bitmaps = frameFiles.take(10).mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
            val splitSeam = if (bitmaps.size >= 2) {
                detectVerticalSplitSeam(bitmaps)
            } else {
                DetectedSplitSeam(detected = false)
            }

            val faceObservations = if (bitmaps.isNotEmpty()) {
                collectFaceObservationsFromBitmaps(bitmaps)
            } else {
                emptyList()
            }
            bitmaps.forEach { it.recycle() }

            if (splitSeam.detected) {
                val seamPct = (splitSeam.seamYNorm * 100.0).roundToInt()
                val topFaces = faceObservations.filter { it.primaryCenterYNorm < splitSeam.seamYNorm - 0.03 }
                val bottomFaces = faceObservations.filter { it.primaryCenterYNorm > splitSeam.seamYNorm + 0.03 }

                // Keep top half by default; only switch to bottom half if speakers clearly live in the bottom half
                val keepTopHalf = !(bottomFaces.size >= 2 && topFaces.isEmpty())
                val activeFaces = if (keepTopHalf) topFaces else bottomFaces

                val halfStartPx: Int
                val halfEndPx: Int
                if (keepTopHalf) {
                    halfStartPx = 0
                    val rawSeamLimit = (splitSeam.cleanTopLimitNorm * srcHeight).roundToInt()
                    halfEndPx = rawSeamLimit.coerceAtMost(safeMaxBottomPx).coerceIn((srcHeight * 0.30).roundToInt(), srcHeight)
                } else {
                    halfStartPx = (splitSeam.cleanBottomStartNorm * srcHeight).roundToInt()
                        .coerceIn(0, (srcHeight * 0.70).roundToInt())
                        .let { it + (it % 2) }
                    halfEndPx = srcHeight
                }

                val availableHalfH = ((halfEndPx - halfStartPx).let { it - (it % 2) }).coerceAtLeast(120)

                // Fit cleanly inside the isolated half so ZERO pixels from the other half ever leak
                val finalCropH: Int
                val finalCropW: Int
                val finalCropX: Int
                val finalCropY: Int

                if (availableHalfH >= idealBoxH) {
                    finalCropW = evenSrcW
                    finalCropH = idealBoxH
                    finalCropX = 0
                    finalCropY = if (enableSmartFaceCrop && activeFaces.isNotEmpty()) {
                        val medianFaceY = median(activeFaces.map { it.primaryCenterYNorm }) * srcHeight
                        val desiredTop = (medianFaceY - finalCropH * 0.42).roundToInt()
                        desiredTop.coerceIn(halfStartPx, halfEndPx - finalCropH).let { it - (it % 2) }
                    } else if (keepTopHalf) {
                        // Anchor slightly above the seam or centered in top half, never crossing halfEndPx
                        ((halfEndPx - finalCropH) / 2).coerceIn(halfStartPx, halfEndPx - finalCropH).let { it - (it % 2) }
                    } else {
                        halfStartPx
                    }
                } else {
                    // Half is shorter than 9:8 (e.g. 42%/58% split or cropped above seam subtitles):
                    // take full height of the clean half and match 9:8 aspect horizontally centered on speaker!
                    finalCropH = availableHalfH
                    finalCropW = ((finalCropH * TARGET_ASPECT).roundToInt().coerceIn(120, evenSrcW)).let { it - (it % 2) }
                    finalCropY = halfStartPx
                    finalCropX = if (enableSmartFaceCrop && activeFaces.isNotEmpty()) {
                        val medianFaceX = median(activeFaces.map { it.primaryCenterXNorm }) * srcWidth
                        (medianFaceX - finalCropW / 2.0).roundToInt()
                            .coerceIn(0, (srcWidth - finalCropW).coerceAtLeast(0))
                            .let { it - (it % 2) }
                    } else {
                        (((srcWidth - finalCropW) / 2).coerceAtLeast(0)).let { it - (it % 2) }
                    }
                }

                DiagnosticLogger.log(
                    "SPLIT-DETECT",
                    "✂️ Обнаружен готовый сплит в исходнике! Граница Y=${seamPct}% (точность ${splitSeam.confidencePct}%), " +
                        "keepTop=$keepTopHalf -> чистый кроп ${finalCropW}x${finalCropH} (x=$finalCropX, y=$finalCropY), старый фон отрезан!"
                )

                return UpperBoxFramingPlan.ReplacedExistingSplit(
                    cropWidth = finalCropW,
                    cropHeight = finalCropH,
                    cropX = finalCropX,
                    cropY = finalCropY,
                    seamPercent = seamPct,
                    keptTopHalf = keepTopHalf,
                    speakerCount = activeFaces.maxOfOrNull { it.faceCount } ?: 1
                )
            }

            // Not a pre-split video: regular full-screen vertical Short -> frame 9:8 window on speaker's face
            val maxAllowedH = safeMaxBottomPx.coerceAtLeast((srcWidth * 0.55).roundToInt())
            val cropH = idealBoxH.coerceAtMost(maxAllowedH).let { it - (it % 2) }
            val cropW = ((cropH * TARGET_ASPECT).roundToInt().coerceAtMost(evenSrcW)).let { it - (it % 2) }
            val cropX = if (enableSmartFaceCrop && faceObservations.isNotEmpty() && cropW < evenSrcW) {
                val medianFaceX = median(faceObservations.map { it.primaryCenterXNorm }) * srcWidth
                (medianFaceX - cropW / 2.0).roundToInt().coerceIn(0, srcWidth - cropW).let { it - (it % 2) }
            } else {
                (((srcWidth - cropW) / 2).coerceAtLeast(0)).let { it - (it % 2) }
            }

            val maxCropY = (safeMaxBottomPx - cropH).coerceIn(0, (srcHeight - cropH).coerceAtLeast(0))
            val cropY = when {
                enableSmartFaceCrop && faceObservations.isNotEmpty() -> {
                    val medianFaceY = median(faceObservations.map { it.primaryCenterYNorm }) * srcHeight
                    // Place speaker's face at ~40% from the top of the upper box so forehead & shoulders fit naturally
                    (medianFaceY - cropH * 0.40).roundToInt().coerceIn(0, maxCropY).let { it - (it % 2) }
                }
                safeMaxBottomPx < srcHeight -> {
                    maxCropY - (maxCropY % 2)
                }
                else -> {
                    (((srcHeight - cropH) * 0.28).roundToInt().coerceIn(0, maxCropY)).let { it - (it % 2) }
                }
            }

            DiagnosticLogger.log(
                "SPLIT-DETECT",
                "Вертикальный исходник цельный (шва 50/50 нет) -> выбран кроп спикера ${cropW}x${cropH} (y=$cropY, лиц=${faceObservations.size})"
            )

            return UpperBoxFramingPlan.TightCrop(
                cropWidth = cropW,
                cropHeight = cropH,
                cropX = cropX,
                cropY = cropY,
                speakerCount = if (faceObservations.isNotEmpty()) 1 else 0
            )
        } finally {
            framesDir.deleteRecursively()
        }
    }

    /**
     * Детектор горизонтальной границы сплита (50/50, 45/55, 40/60 и т.д.) по серии кадров:
     * Ищет строку Y (34%..66% высоты), где на протяжении всего видео держится стационарный горизонтальный
     * разрыв между двумя разными видеорядами ИЛИ разделительная полоса/плашка.
     */
    private fun detectVerticalSplitSeam(bitmaps: List<android.graphics.Bitmap>): DetectedSplitSeam {
        if (bitmaps.size < 2) return DetectedSplitSeam(detected = false)
        val w = bitmaps.first().width
        val h = bitmaps.first().height
        if (w < 120 || h < 200) return DetectedSplitSeam(detected = false)

        val yScanStart = (h * 0.34).roundToInt().coerceAtLeast(8)
        val yScanEnd = (h * 0.66).roundToInt().coerceAtMost(h - 9)
        val xStart = (w * 0.06).roundToInt().coerceAtLeast(2)
        val xEnd = (w * 0.94).roundToInt().coerceAtMost(w - 3)
        val sampleCols = ((xEnd - xStart) / 3).coerceAtLeast(1)

        val rowMeanEdgeCoverage = DoubleArray(h)
        val rowPersistentFrameRatio = DoubleArray(h)
        val rowFlatBarRatio = DoubleArray(h)

        for (y in (yScanStart - 6)..(yScanEnd + 6)) {
            if (y < 4 || y >= h - 4) continue
            var sumCoverage = 0.0
            var strongFrames = 0
            var flatBarFrames = 0

            for (bmp in bitmaps) {
                var edgeHits = 0
                var lumSum = 0.0
                var lumSqSum = 0.0
                var count = 0

                var x = xStart
                while (x < xEnd) {
                    val cAbove = bmp.getPixel(x, y - 2)
                    val cBelow = bmp.getPixel(x, y + 2)
                    val diff2 = (
                        abs(Color.red(cAbove) - Color.red(cBelow)) +
                            abs(Color.green(cAbove) - Color.green(cBelow)) +
                            abs(Color.blue(cAbove) - Color.blue(cBelow))
                        ) / 3

                    val cAbove3 = bmp.getPixel(x, y - 3)
                    val cBelow3 = bmp.getPixel(x, y + 3)
                    val diff3 = (
                        abs(Color.red(cAbove3) - Color.red(cBelow3)) +
                            abs(Color.green(cAbove3) - Color.green(cBelow3)) +
                            abs(Color.blue(cAbove3) - Color.blue(cBelow3))
                        ) / 3

                    if (maxOf(diff2, diff3) >= 20) {
                        edgeHits++
                    }

                    val cMid = bmp.getPixel(x, y)
                    val lum = (Color.red(cMid) * 299 + Color.green(cMid) * 587 + Color.blue(cMid) * 114) / 1000.0
                    lumSum += lum
                    lumSqSum += lum * lum
                    count++
                    x += 3
                }

                val coverage = edgeHits.toDouble() / sampleCols.toDouble()
                sumCoverage += coverage
                if (coverage >= 0.48) {
                    strongFrames++
                }

                if (count > 4) {
                    val meanLum = lumSum / count
                    val variance = (lumSqSum / count) - (meanLum * meanLum)
                    if (variance <= 140.0) {
                        flatBarFrames++
                    }
                }
            }

            rowMeanEdgeCoverage[y] = sumCoverage / bitmaps.size.toDouble()
            rowPersistentFrameRatio[y] = strongFrames.toDouble() / bitmaps.size.toDouble()
            rowFlatBarRatio[y] = flatBarFrames.toDouble() / bitmaps.size.toDouble()
        }

        // Find peak seam candidate with high local prominence over neighboring rows (y ± 10..20px)
        var bestY = -1
        var bestScore = 0.0
        var bestProminence = 1.0

        for (y in yScanStart..yScanEnd) {
            var neighborSum = 0.0
            var neighborCnt = 0
            for (dy in listOf(-20, -16, -12, 12, 16, 20)) {
                val ny = y + dy
                if (ny in 5 until (h - 5)) {
                    neighborSum += rowMeanEdgeCoverage[ny]
                    neighborCnt++
                }
            }
            val neighborAvg = (if (neighborCnt > 0) neighborSum / neighborCnt else 0.18).coerceAtLeast(0.08)
            val prominence = rowMeanEdgeCoverage[y] / neighborAvg

            // Slight natural prior toward 50/50 center (0.45..0.55) when multiple rows compete
            val yNorm = y.toDouble() / h.toDouble()
            val centerBonus = if (yNorm in 0.44..0.56) 1.12 else 1.0

            val compositeScore = rowMeanEdgeCoverage[y] *
                (0.55 + 0.45 * rowPersistentFrameRatio[y]) *
                prominence.coerceAtMost(5.0) *
                centerBonus

            val passesDirectSeam = rowMeanEdgeCoverage[y] >= 0.46 &&
                rowPersistentFrameRatio[y] >= 0.65 &&
                prominence >= 1.58

            val passesDividerBar = rowFlatBarRatio[y] >= 0.75 &&
                (rowMeanEdgeCoverage[(y - 3).coerceAtLeast(4)] >= 0.42 ||
                    rowMeanEdgeCoverage[(y + 3).coerceAtMost(h - 5)] >= 0.42) &&
                prominence >= 1.35

            if ((passesDirectSeam || passesDividerBar) && compositeScore > bestScore) {
                bestScore = compositeScore
                bestY = y
                bestProminence = prominence
            }
        }

        if (bestY < 0) {
            return DetectedSplitSeam(detected = false)
        }

        // Verify the two halves aren't just a uniform static letterbox or single continuous gradient:
        // check independent motion OR distinct color distribution above vs below bestY
        var distinctHalfEvidence = 0
        for (i in 0 until bitmaps.size) {
            val bmp = bitmaps[i]
            val nextBmp = bitmaps[(i + 1) % bitmaps.size]
            var topLum = 0L
            var botLum = 0L
            var topMotion = 0L
            var botMotion = 0L
            var samples = 0

            val topSampleY1 = (bestY * 0.25).roundToInt().coerceAtLeast(4)
            val topSampleY2 = (bestY * 0.80).roundToInt().coerceAtLeast(topSampleY1 + 2)
            val botSampleY1 = (bestY + (h - bestY) * 0.20).roundToInt().coerceAtMost(h - 6)
            val botSampleY2 = (bestY + (h - bestY) * 0.78).roundToInt().coerceAtLeast(botSampleY1 + 2)

            var sx = xStart
            while (sx < xEnd) {
                val ty = (topSampleY1 + topSampleY2) / 2
                val by = (botSampleY1 + botSampleY2) / 2
                val tc = bmp.getPixel(sx, ty)
                val bc = bmp.getPixel(sx, by)
                val tcNext = nextBmp.getPixel(sx, ty)
                val bcNext = nextBmp.getPixel(sx, by)

                topLum += (Color.red(tc) + Color.green(tc) + Color.blue(tc)) / 3
                botLum += (Color.red(bc) + Color.green(bc) + Color.blue(bc)) / 3
                topMotion += abs(Color.green(tc) - Color.green(tcNext))
                botMotion += abs(Color.green(bc) - Color.green(bcNext))
                samples++
                sx += 6
            }
            if (samples > 0) {
                val lumDiff = abs((topLum / samples) - (botLum / samples))
                val motionDiff = abs((topMotion / samples) - (botMotion / samples))
                if (lumDiff >= 12 || motionDiff >= 5 || bestProminence >= 2.15) {
                    distinctHalfEvidence++
                }
            }
        }

        if (distinctHalfEvidence * 2 < bitmaps.size) {
            return DetectedSplitSeam(detected = false)
        }

        // Expand outward across any divider bar / border stroke + 1.5% safety margin so zero seam artifacts remain
        var cleanTopY = bestY - 2
        while (cleanTopY > (bestY - (h * 0.045).roundToInt()) &&
            (rowFlatBarRatio[cleanTopY] >= 0.70 || rowMeanEdgeCoverage[cleanTopY] >= 0.55)
        ) {
            cleanTopY--
        }
        val safetyPadPx = (h * 0.014).roundToInt().coerceAtLeast(4)
        cleanTopY = (cleanTopY - safetyPadPx).coerceAtLeast((h * 0.28).roundToInt())

        var cleanBotY = bestY + 2
        while (cleanBotY < (bestY + (h * 0.045).roundToInt()) &&
            (rowFlatBarRatio[cleanBotY] >= 0.70 || rowMeanEdgeCoverage[cleanBotY] >= 0.55)
        ) {
            cleanBotY++
        }
        cleanBotY = (cleanBotY + safetyPadPx).coerceAtMost((h * 0.72).roundToInt())

        val conf = (68 + (bestProminence * 9.0).roundToInt()).coerceIn(72, 99)
        return DetectedSplitSeam(
            detected = true,
            seamYNorm = bestY.toDouble() / h.toDouble(),
            cleanTopLimitNorm = cleanTopY.toDouble() / h.toDouble(),
            cleanBottomStartNorm = cleanBotY.toDouble() / h.toDouble(),
            confidencePct = conf
        )
    }

    private suspend fun collectFaceObservationsFromFiles(frameFiles: List<File>): List<FrameFaceObservation> {
        val observations = mutableListOf<FrameFaceObservation>()
        for (frameFile in frameFiles) {
            val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: continue
            try {
                detectFaceInBitmap(bitmap)?.let { observations.add(it) }
            } finally {
                bitmap.recycle()
            }
        }
        return observations
    }

    private suspend fun collectFaceObservationsFromBitmaps(bitmaps: List<android.graphics.Bitmap>): List<FrameFaceObservation> {
        val observations = mutableListOf<FrameFaceObservation>()
        for (bitmap in bitmaps) {
            detectFaceInBitmap(bitmap)?.let { observations.add(it) }
        }
        return observations
    }

    private suspend fun detectFaceInBitmap(bitmap: android.graphics.Bitmap): FrameFaceObservation? {
        val bmpW = bitmap.width.toDouble()
        val bmpH = bitmap.height.toDouble()
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(inputImage).await()
            val validFaces = faces.filter { face ->
                val areaRatio = (face.boundingBox.width() * face.boundingBox.height()) / (bmpW * bmpH)
                areaRatio >= 0.008
            }
            if (validFaces.isEmpty()) return null

            val dominant = validFaces.maxByOrNull {
                it.boundingBox.width() * it.boundingBox.height()
            } ?: return null

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
            val domCenterXNorm = (dominant.boundingBox.centerX().toDouble() / bmpW).coerceIn(0.0, 1.0)
            val domCenterYNorm = (dominant.boundingBox.centerY().toDouble() / bmpH).coerceIn(0.0, 1.0)

            FrameFaceObservation(
                faceCount = activeSpeakers.size,
                primaryCenterXNorm = domCenterXNorm,
                primaryCenterYNorm = domCenterYNorm,
                minLeftNorm = minLeft,
                maxRightNorm = maxRight
            )
        } catch (_: Exception) {
            null
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
