package com.a340.technicalassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Non-destructive pre-OCR editor. The original Camera/Gallery URI is never overwritten.
 * All controls apply to the same bitmap; Apply exports the actual processed pixels.
 */
class ImageEditorActivity : AppCompatActivity() {
    private lateinit var original: Bitmap
    private lateinit var preview: ImageView
    private lateinit var rotation: SeekBar
    private lateinit var brightness: SeekBar
    private lateinit var contrast: SeekBar
    private lateinit var crop: SeekBar
    private lateinit var grayscale: CheckBox
    private lateinit var angleLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let(Uri::parse)
        if (source == null) { finish(); return }
        try {
            original = decodeBounded(source, 2200)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open image: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.rgb(22, 28, 35))
        }
        val scroll = ScrollView(this)
        scroll.addView(panel)
        setContentView(scroll)
        panel.addView(TextView(this).apply {
            text = "EDIT IMAGE BEFORE OCR"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(original)
            adjustViewBounds = true
        }
        panel.addView(preview, LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 3))
        angleLabel = label(panel, "Rotation / straightening: 0°")
        rotation = slider(panel, 359, 0) { updatePreview() }
        label(panel, "Brightness")
        brightness = slider(panel, 200, 100) { updatePreview() }
        label(panel, "Contrast")
        contrast = slider(panel, 200, 100) { updatePreview() }
        label(panel, "Center crop (0 = no crop)")
        crop = slider(panel, 80, 0) { updatePreview() }
        grayscale = CheckBox(this).apply {
            text = "Black & white"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, _ -> updatePreview() }
        }
        panel.addView(grayscale)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, fn: () -> Unit) {
            row.addView(Button(this).apply { text = label; setOnClickListener { fn() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        action("Rotate 90°") {
            rotation.progress = (rotation.progress + 90) % 360
            updatePreview()
        }
        action("Reset") {
            rotation.progress = 0; brightness.progress = 100
            contrast.progress = 100; crop.progress = 0; grayscale.isChecked = false
            updatePreview()
        }
        panel.addView(row)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "Apply & OCR"
            setOnClickListener {
                isEnabled = false
                try {
                    val result = render()
                    val dir = File(cacheDir, "edited").apply { mkdirs() }
                    val file = File.createTempFile("ocr_", ".jpg", dir)
                    FileOutputStream(file).use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    if (result !== original) result.recycle()
                    val uri = FileProvider.getUriForFile(this@ImageEditorActivity, "$packageName.fileprovider", file)
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, uri.toString()))
                    finish()
                } catch (e: Exception) {
                    isEnabled = true
                    Toast.makeText(this@ImageEditorActivity, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(bottom)
        updatePreview()
    }

    private fun label(container: LinearLayout, title: String): TextView =
        TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            container.addView(this)
        }

    private fun slider(container: LinearLayout, maximum: Int, initial: Int, changed: () -> Unit): SeekBar =
        SeekBar(this).apply {
            max = maximum
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = changed()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            container.addView(this)
        }

    private fun matrix(): ColorMatrix {
        val contrastValue = max(0.0f, contrast.progress / 100f)
        val brightnessOffset = (brightness.progress - 100) * 1.5f
        val color = ColorMatrix(floatArrayOf(
            contrastValue, 0f, 0f, 0f, brightnessOffset,
            0f, contrastValue, 0f, 0f, brightnessOffset,
            0f, 0f, contrastValue, 0f, brightnessOffset,
            0f, 0f, 0f, 1f, 0f
        ))
        if (grayscale.isChecked) color.postConcat(ColorMatrix().apply { setSaturation(0f) })
        return color
    }

    private fun updatePreview() {
        if (!::preview.isInitialized || !::rotation.isInitialized ||
            !::brightness.isInitialized || !::contrast.isInitialized ||
            !::crop.isInitialized || !::grayscale.isInitialized) return
        angleLabel.text = "Rotation / straightening: ${rotation.progress}°"
        preview.rotation = rotation.progress.toFloat()
        preview.setColorFilter(ColorMatrixColorFilter(matrix()))
        val factor = 1f - crop.progress / 100f
        val w = max(1, (original.width * factor).toInt())
        val h = max(1, (original.height * factor).toInt())
        // Preview crops through a new lightweight viewport; original is untouched.
        val bounded = Bitmap.createBitmap(original, (original.width - w) / 2, (original.height - h) / 2, w, h)
        preview.setImageBitmap(bounded)
    }

    private fun render(): Bitmap {
        val factor = 1f - crop.progress / 100f
        val w = max(1, (original.width * factor).toInt())
        val h = max(1, (original.height * factor).toInt())
        val src = Bitmap.createBitmap(original, (original.width - w) / 2, (original.height - h) / 2, w, h)
        val rotate = Matrix().apply { postRotate(rotation.progress.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, rotate, true)
        val dest = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix())
        }
        Canvas(dest).drawBitmap(rotated, 0f, 0f, paint)
        if (rotated !== original) rotated.recycle()
        if (src !== original && src !== rotated) src.recycle()
        return dest
    }

    private fun decodeBounded(uri: Uri, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri).use {
            requireNotNull(BitmapFactory.decodeStream(it, null, options)) { "Unsupported image" }
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source_uri"
        const val EXTRA_RESULT = "edited_uri"
    }
}
