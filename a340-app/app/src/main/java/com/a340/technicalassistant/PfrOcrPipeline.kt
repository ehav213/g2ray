package com.a340.technicalassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PFR OCR pipeline for photographed/cropped line-maintenance records.
 *
 * The crop URI is the source of truth. We first OCR that exact rendered crop,
 * estimate residual text-line skew, then run a second OCR pass on a deskewed,
 * upscaled grayscale/contrast image. The better structured result is returned.
 * No field values are invented; OCR output remains user-editable in the UI.
 */
object PfrOcrPipeline {
    data class Outcome(
        val text: Text,
        val skewDegrees: Float,
        val usedEnhancedPass: Boolean,
        val rawScore: Int,
        val enhancedScore: Int
    ) {
        val note: String
            get() = buildString {
                append("OCR source: confirmed crop")
                if (abs(skewDegrees) >= 0.20f) append(" • auto-deskew ").append(String.format(Locale.US, "%.1f°", skewDegrees))
                append(if (usedEnhancedPass) " • enhanced contrast pass selected" else " • original crop pass selected")
            }
    }

    fun recognize(context: Context, uri: Uri): Outcome {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var decoded: Bitmap? = null
        var enhancedBitmap: Bitmap? = null
        try {
            val rawImage = InputImage.fromFilePath(context, uri)
            val rawText = Tasks.await(recognizer.process(rawImage))
            val rawScore = structureScore(rawText)

            decoded = decodeSampled(context, uri) ?: return Outcome(rawText, 0f, false, rawScore, Int.MIN_VALUE)
            val skew = estimateSkewDegrees(rawText)
            enhancedBitmap = enhance(decoded, skew)
            val enhancedText = Tasks.await(recognizer.process(InputImage.fromBitmap(enhancedBitmap, 0)))
            val enhancedScore = structureScore(enhancedText)

            // Prefer the enhanced pass when it preserves nearly all raw evidence,
            // or when it improves PFR-specific structure. This avoids sacrificing
            // valid text solely because a contrast transform changed spacing.
            val useEnhanced = when {
                enhancedText.text.isBlank() -> false
                rawText.text.isBlank() -> true
                enhancedScore >= rawScore -> true
                enhancedScore >= (rawScore * 0.94f).roundToInt() && keyTokenCount(enhancedText.text) >= keyTokenCount(rawText.text) -> true
                else -> false
            }
            return Outcome(if (useEnhanced) enhancedText else rawText, skew, useEnhanced, rawScore, enhancedScore)
        } finally {
            if (enhancedBitmap != null && enhancedBitmap !== decoded && !enhancedBitmap.isRecycled) enhancedBitmap.recycle()
            if (decoded != null && !decoded.isRecycled) decoded.recycle()
            recognizer.close()
        }
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxEdge = 3200
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun estimateSkewDegrees(text: Text): Float {
        val angles = mutableListOf<Float>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                if (line.text.trim().length < 4) return@forEach
                val p = line.cornerPoints ?: return@forEach
                if (p.size < 2) return@forEach
                val dx = (p[1].x - p[0].x).toFloat()
                val dy = (p[1].y - p[0].y).toFloat()
                if (abs(dx) < 12f) return@forEach
                val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg in -12f..12f) angles += deg
            }
        }
        if (angles.size < 2) return 0f
        angles.sort()
        val median = if (angles.size % 2 == 1) angles[angles.size / 2]
        else (angles[angles.size / 2 - 1] + angles[angles.size / 2]) / 2f
        return if (abs(median) < 0.20f) 0f else median.coerceIn(-12f, 12f)
    }

    private fun enhance(source: Bitmap, skewDegrees: Float): Bitmap {
        var working = source
        val longest = max(source.width, source.height)
        val targetLongest = min(2600, max(1800, longest))
        if (longest != targetLongest) {
            val scale = targetLongest.toFloat() / longest.toFloat()
            working = Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * scale).roundToInt()),
                max(1, (source.height * scale).roundToInt()),
                true
            )
        }

        var rotated = working
        if (abs(skewDegrees) >= 0.20f) {
            val matrix = Matrix().apply { postRotate(-skewDegrees) }
            rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
            if (working !== source && working !== rotated && !working.isRecycled) working.recycle()
        }

        val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.42f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        Canvas(output).drawBitmap(rotated, 0f, 0f, paint)
        if (rotated !== source && rotated !== output && !rotated.isRecycled) rotated.recycle()
        return output
    }

    private fun structureScore(text: Text): Int {
        val raw = text.text
        if (raw.isBlank()) return 0
        val upper = raw.uppercase(Locale.US)
        var score = raw.count { it.isLetterOrDigit() }
        score += text.textBlocks.sumOf { it.lines.size } * 8
        score += keyTokenCount(upper) * 90
        score += Regex("\\b\\d{2}[- /]?\\d{2}(?:[- /]?\\d{2})?\\b").findAll(upper).count() * 45
        score += Regex("\\b[A-Z]{2,6}[ -]?\\d{1,4}(?:-\\d+)?\\b").findAll(upper).count() * 18
        return score
    }

    private fun keyTokenCount(text: String): Int {
        val upper = text.uppercase(Locale.US)
        return listOf(
            "MAINTENANCE", "STATUS", "ECAM", "ATA", "SOURCE", "CLASS",
            "MESSAGE", "COCKPIT", "WARNING", "CH", "SE", "SU"
        ).count { upper.contains(it) }
    }
}
