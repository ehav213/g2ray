package com.a340.technicalassistant

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.a340.technicalassistant.databinding.ActivityPdfPageBinding

class PdfPageActivity : AppCompatActivity() {
    private lateinit var b: ActivityPdfPageBinding
    private var renderer: PdfRenderer? = null
    private var page: PdfRenderer.Page? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPdfPageBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.txtPdfTitle.text = intent.getStringExtra("title") ?: "PDF"
        val raw = intent.getStringExtra("uri") ?: return
        val uri = android.net.Uri.parse(raw)
        val requested = intent.getIntExtra("page", 1)
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
        renderer = PdfRenderer(pfd)
        if (renderer!!.pageCount <= 0) return
        val index = (requested - 1).coerceIn(0, renderer!!.pageCount - 1)
        page = renderer!!.openPage(index)
        val scale = 1.5f
        val bmp = Bitmap.createBitmap((page!!.width*scale).toInt(), (page!!.height*scale).toInt(), Bitmap.Config.ARGB_8888)
        page!!.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        b.pdfImage.setImageBitmap(bmp)
        b.txtPdfTitle.append(" • PDF page ${index+1}/${renderer!!.pageCount}")
    }

    override fun onDestroy() {
        page?.close(); renderer?.close(); super.onDestroy()
    }
}
