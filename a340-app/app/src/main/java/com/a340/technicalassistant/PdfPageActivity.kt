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

class PdfPageActivity : AppCompatActivity() {
    private lateinit var b: ActivityPdfPageBinding
    private var renderer: PdfRenderer? = null
    private var page: PdfRenderer.Page? = null
    private var renderedBitmap: Bitmap? = null
    private var sourceTitle: String = "A340 source"
    private var sourcePage: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPdfPageBinding.inflate(layoutInflater)
        setContentView(b.root)
        sourceTitle = intent.getStringExtra("title") ?: "PDF"
        b.txtPdfTitle.text = sourceTitle
        b.btnShareSourcePage.isEnabled = false
        b.btnShareSourcePage.setOnClickListener { shareSourcePagePdf() }

        val raw = intent.getStringExtra("uri") ?: return
        val uri = android.net.Uri.parse(raw)
        val requested = intent.getIntExtra("page", 1)
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
        renderer = PdfRenderer(pfd)
        if (renderer!!.pageCount <= 0) return
        val index = (requested - 1).coerceIn(0, renderer!!.pageCount - 1)
        sourcePage = index + 1
        page = renderer!!.openPage(index)
        val scale = 1.5f
        val bmp = Bitmap.createBitmap(
            (page!!.width * scale).toInt(),
            (page!!.height * scale).toInt(),
            Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(Color.WHITE)
        page!!.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        renderedBitmap = bmp
        b.pdfImage.setBackgroundColor(Color.WHITE)
        b.pdfImage.setImageBitmap(bmp)
        b.txtPdfTitle.append(" • PDF file page ${index + 1}/${renderer!!.pageCount}")
        b.btnShareSourcePage.isEnabled = true
    }

    private fun shareSourcePagePdf() {
        val bmp = renderedBitmap ?: return
        runCatching {
            val dir = File(cacheDir, "source_packet").apply { mkdirs() }
            val safe = sourceTitle
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .take(70)
                .ifBlank { "A340_source" }
            val out = File(dir, "${safe}_PDF_page_$sourcePage.pdf")

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
                putExtra(Intent.EXTRA_SUBJECT, "$sourceTitle • PDF page $sourcePage")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share source page PDF"))
        }.onFailure {
            Toast.makeText(this, "Cannot create source page PDF: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        page?.close()
        renderer?.close()
        renderedBitmap = null
        super.onDestroy()
    }
}
