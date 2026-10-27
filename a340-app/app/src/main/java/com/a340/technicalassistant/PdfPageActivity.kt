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
        if (renderer!!.pageCount <= 0) return finish()
        currentIndex = (requested - 1).coerceIn(0, renderer!!.pageCount - 1)

        b.btnPrevPage.setOnClickListener { if (currentIndex > 0) { currentIndex--; renderCurrentPage() } }
        b.btnNextPage.setOnClickListener { if (currentIndex < renderer!!.pageCount - 1) { currentIndex++; renderCurrentPage() } }
        b.btnFitPage.setOnClickListener { b.pdfImage.resetZoom() }
        b.btnZoomIn.setOnClickListener { b.pdfImage.zoomIn() }
        b.btnZoomOut.setOnClickListener { b.pdfImage.zoomOut() }
        b.btnShareSourcePage.setOnClickListener { shareSourcePagePdf() }

        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        val r = renderer ?: return
        page?.close()
        page = r.openPage(currentIndex)
        val p = page ?: return

        renderedBitmap?.recycle()
        renderedBitmap = null

        // Render substantially above screen resolution so small maintenance-manual
        // text remains readable after zooming, while capping memory use on 4 GB phones.
        val targetWidth = min(2400, (resources.displayMetrics.widthPixels * 3).coerceAtLeast(1600))
        val scale = targetWidth.toFloat() / p.width.toFloat()
        val targetHeight = (p.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        renderedBitmap = bmp
        b.pdfImage.setBackgroundColor(Color.WHITE)
        b.pdfImage.setImageBitmap(bmp)
        b.pdfImage.post { b.pdfImage.resetZoom() }

        b.txtPdfTitle.text = "$sourceTitle\nPDF file page ${currentIndex + 1}/${r.pageCount}"
        b.btnPrevPage.isEnabled = currentIndex > 0
        b.btnNextPage.isEnabled = currentIndex < r.pageCount - 1
        b.btnShareSourcePage.isEnabled = true
    }

    private fun shareSourcePagePdf() {
        val bmp = renderedBitmap ?: return
        runCatching {
            val dir = File(cacheDir, "source_packet").apply { mkdirs() }
            val safe = sourceTitle.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(70).ifBlank { "A340_source" }
            val out = File(dir, "${safe}_PDF_page_${currentIndex + 1}.pdf")
            val doc = PdfDocument()
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
            val pdfPage = doc.startPage(info)
            pdfPage.canvas.drawColor(Color.WHITE)
            pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(pdfPage)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            val shareUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, "$sourceTitle • PDF page ${currentIndex + 1}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share original source page"))
        }.onFailure {
            Toast.makeText(this, "Cannot create source page PDF: ${it.message}", Toast.LENGTH_LONG).show()
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
