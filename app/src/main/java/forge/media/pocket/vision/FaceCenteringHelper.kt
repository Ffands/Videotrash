package forge.media.pocket.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

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
        maxSampleSeconds: Int = 30
    ): UpperBoxFramingPlan = withContext(Dispatchers.IO) {
        if (!enableSmartFaceCrop) {
            return@withContext UpperBoxFramingPlan.UncroppedFullFrame
        }

        val (srcWidth, srcHeight) = probeVideoDimensions(videoFile)

        // If the source is ALREADY vertical (e.g. 1080x1920 YouTube Short), crop a 9:8 window vertically without squashing!
        if (srcHeight > srcWidth) {
            val cropW = srcWidth - (srcWidth % 2)
            val cropH = ((srcWidth / TARGET_ASPECT).roundToInt().coerceAtMost(srcHeight)).let { it - (it % 2) }
            val defaultCropY = (((srcHeight - cropH) * 0.35).roundToInt().coerceIn(0, srcHeight - cropH)).let { it - (it % 2) }
            return@withContext UpperBoxFramingPlan.TightCrop(
                cropWidth = cropW,
                cropHeight = cropH,
                cropX = 0,
                cropY = defaultCropY,
                speakerCount = 1
            )
        }

        val baseCropHeight = srcHeight - (srcHeight % 2)
        val baseCropWidth = ((srcHeight * TARGET_ASPECT).roundToInt().coerceAtMost(srcWidth)).let { it - (it % 2) }
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
}
