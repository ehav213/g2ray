package com.a340.technicalassistant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.a340.technicalassistant.databinding.ActivityPdfPageBinding
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class PdfPageActivity : AppCompatActivity() {
    private lateinit var b: ActivityPdfPageBinding
    private var renderer: PdfRenderer? = null
    private var page: PdfRenderer.Page? = null
    private var renderedBitmap: Bitmap? = null
    private var sourceTitle: String = "A340 source"
    private var currentIndex = 0
    private var rangeStartIndex = 0
    private var rangeEndIndex = 0
    private var rangeLabel = "SOURCE PAGE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPdfPageBinding.inflate(layoutInflater)
        setContentView(b.root)

        sourceTitle = intent.getStringExtra("title") ?: "PDF source"
        val raw = intent.getStringExtra("uri") ?: return finish()
        val requested = intent.getIntExtra("page", 1)
        val uri = android.net.Uri.parse(raw)
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return finish()
        renderer = PdfRenderer(pfd)
        val r = renderer ?: return finish()
        if (r.pageCount <= 0) return finish()

        val (manual, reference) = parseSourceTitle(sourceTitle)
        val dbFile = DatabaseManager(this).databaseFile()
        val resolvedRange = if (dbFile.exists()) {
            runCatching { SourceRangeResolver(dbFile).use { it.resolve(manual, reference, requested) } }.getOrNull()
        } else null

        val startPage = (resolvedRange?.startPage ?: requested).coerceIn(1, r.pageCount)
        val endPage = (resolvedRange?.endPage ?: startPage).coerceIn(startPage, r.pageCount)
        rangeStartIndex = startPage - 1
        rangeEndIndex = endPage - 1
        rangeLabel = resolvedRange?.label ?: "SOURCE PAGE"
        currentIndex = rangeStartIndex

        b.btnPrevPage.setOnClickListener {
            if (currentIndex > rangeStartIndex) {
                currentIndex--
                renderCurrentPage()
            }
        }
        b.btnNextPage.setOnClickListener {
            if (currentIndex < rangeEndIndex) {
                currentIndex++
                renderCurrentPage()
            }
        }
        b.btnFitPage.setOnClickListener { b.pdfImage.resetZoom() }
        b.btnZoomIn.setOnClickListener { b.pdfImage.zoomIn() }
        b.btnZoomOut.setOnClickListener { b.pdfImage.zoomOut() }
        b.btnShareSourcePage.setOnClickListener { shareSourceRangePdf() }

        val count = rangeEndIndex - rangeStartIndex + 1
        b.btnShareSourcePage.text = if (count > 1) "SAVE / SHARE COMPLETE SOURCE ($count PAGES)" else "SAVE / SHARE SOURCE PAGE PDF"
        renderCurrentPage()
    }

    private fun parseSourceTitle(title: String): Pair<String, String> {
        val manual = Regex("^\\[([^]]+)]").find(title)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val reference = Regex("^\\[[^]]+]\\s*([^—\\n]+)").find(title)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return manual to reference
    }

    private fun renderCurrentPage() {
        val r = renderer ?: return
        page?.close()
        page = r.openPage(currentIndex)
        val p = page ?: return

        renderedBitmap?.recycle()
        renderedBitmap = null

        val targetWidth = min(2600, (resources.displayMetrics.widthPixels * 3).coerceAtLeast(1800))
        val scale = targetWidth.toFloat() / p.width.toFloat()
        val targetHeight = (p.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        renderedBitmap = bmp
        b.pdfImage.setBackgroundColor(Color.WHITE)
        b.pdfImage.setImageBitmap(bmp)
        b.pdfImage.post { b.pdfImage.resetZoom() }

        val count = rangeEndIndex - rangeStartIndex + 1
        val pos = currentIndex - rangeStartIndex + 1
        b.txtPdfTitle.text = buildString {
            append(sourceTitle)
            append("\n").append(rangeLabel)
            if (count > 1) {
                append(" • task/item page ").append(pos).append('/').append(count)
                append(" • PDF pages ").append(rangeStartIndex + 1).append('-').append(rangeEndIndex + 1)
            } else {
                append(" • PDF file page ").append(currentIndex + 1).append('/').append(r.pageCount)
            }
        }
        b.btnPrevPage.isEnabled = currentIndex > rangeStartIndex
        b.btnNextPage.isEnabled = currentIndex < rangeEndIndex
        b.btnShareSourcePage.isEnabled = true
    }

    private fun renderBitmap(index: Int, targetWidth: Int): Bitmap {
        val r = renderer ?: error("PDF renderer unavailable")
        val p = r.openPage(index)
        try {
            val scale = targetWidth.toFloat() / p.width.toFloat()
            val targetHeight = (p.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            p.close()
        }
    }

    private fun shareSourceRangePdf() {
        val r = renderer ?: return
        val savedIndex = currentIndex
        runCatching {
            // PdfRenderer is safest when the display page is closed before rendering the export range.
            page?.close()
            page = null

            val dir = File(cacheDir, "source_packet").apply { mkdirs() }
            val safe = sourceTitle.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(70).ifBlank { "A340_source" }
            val count = rangeEndIndex - rangeStartIndex + 1
            val out = File(dir, if (count > 1) "${safe}_COMPLETE_SOURCE.pdf" else "${safe}_PDF_page_${rangeStartIndex + 1}.pdf")
            val doc = PdfDocument()
            try {
                var outputPage = 1
                for (index in rangeStartIndex..rangeEndIndex) {
                    val bmp = renderBitmap(index, 1800)
                    try {
                        val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, outputPage++).create()
                        val pdfPage = doc.startPage(info)
                        pdfPage.canvas.drawColor(Color.WHITE)
                        pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                        doc.finishPage(pdfPage)
                    } finally {
                        bmp.recycle()
                    }
                }
                FileOutputStream(out).use { doc.writeTo(it) }
            } finally {
                doc.close()
            }

            currentIndex = savedIndex.coerceIn(rangeStartIndex, rangeEndIndex)
            renderCurrentPage()

            val shareUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, "$sourceTitle • complete source range")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share complete original source"))
        }.onFailure {
            currentIndex = savedIndex.coerceIn(rangeStartIndex, rangeEndIndex)
            runCatching { renderCurrentPage() }
            Toast.makeText(this, "Cannot create source PDF: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        page?.close()
        renderer?.close()
        renderedBitmap?.recycle()
        renderedBitmap = null
        super.onDestroy()
    }
}
