package com.a340.technicalassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.a340.technicalassistant.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val dbManager by lazy { DatabaseManager(this) }
    private val pdfIndex by lazy { PdfIndex(this) }
    private var repo: SearchRepository? = null
    private var hits: List<SearchHit> = emptyList()
    private var photoUri: Uri? = null

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDb(uri)
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            b.txtStatus.text = "Indexing PDF folder…"
            io.execute {
                val n = pdfIndex.rebuild(this, uri)
                runOnUiThread { b.txtStatus.text = "PDF index ready: $n files" }
            }
        }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { runOcr(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        openExistingDb()

        b.btnImportDb.setOnClickListener { dbPicker.launch(arrayOf("application/octet-stream","application/x-sqlite3","*/*")) }
        b.btnPdfFolder.setOnClickListener { treePicker.launch(null) }
        b.btnCamera.setOnClickListener { capture() }
        b.btnSearch.setOnClickListener { performSearch() }
        b.listResults.setOnItemClickListener { _,_,position,_ -> openHit(hits[position]) }
    }

    private fun openExistingDb() {
        val f = dbManager.databaseFile()
        if (f.exists()) {
            repo?.close(); repo = SearchRepository(f); refreshStatus()
        }
    }

    private fun importDb(uri: Uri) {
        b.txtStatus.text = "Copying database…"
        io.execute {
            try {
                val f = dbManager.importDatabase(uri) { bytes -> runOnUiThread { b.txtStatus.text = "Copying database: ${bytes / (1024*1024)} MB" } }
                repo?.close(); repo = SearchRepository(f)
                runOnUiThread { refreshStatus() }
            } catch (t: Throwable) {
                runOnUiThread { b.txtStatus.text = "Database import failed: ${t.message}" }
            }
        }
    }

    private fun refreshStatus() {
        val r = repo ?: return
        val manuals = r.manuals()
        b.txtStatus.text = "A340 Master v${r.architectureVersion()} • ${r.buildTimestamp()}"
        b.txtManuals.text = manuals.joinToString("\n") { "${it.code}: ${it.status} • ${it.pages} pages • ${it.images} images" }
    }

    private fun performSearch() {
        val r = repo ?: return toast("Import database first")
        val q = b.editQuery.text.toString().trim()
        if (q.isBlank()) return
        io.execute {
            try {
                hits = r.search(q)
                runOnUiThread { b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits) }
            } catch (t: Throwable) {
                runOnUiThread { toast("Search failed: ${t.message}") }
            }
        }
    }

    private fun openHit(hit: SearchHit) {
        val r = repo ?: return
        io.execute {
            val src = r.resolve(hit)
            val uri = src?.let { pdfIndex.find(it.filename) }
            runOnUiThread {
                if (src == null) toast("No source page mapping for this result")
                else if (uri == null) toast("PDF not indexed: ${src.filename}")
                else startActivity(Intent(this, PdfPageActivity::class.java).apply {
                    putExtra("uri", uri.toString())
                    putExtra("page", src.pdfPage)
                    putExtra("title", "[${src.manual}] ${src.reference} — ${src.title}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
    }

    private fun capture() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "postflight.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        takePhoto.launch(photoUri)
    }

    private fun runOcr(uri: Uri) {
        b.txtStatus.text = "OCR…"
        val image = InputImage.fromFilePath(this, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val cleaned = text.text.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
                b.editQuery.setText(cleaned)
                b.txtStatus.text = "OCR ready — verify text, then SEARCH"
            }
            .addOnFailureListener { b.txtStatus.text = "OCR failed: ${it.message}" }
            .addOnCompleteListener { recognizer.close() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        repo?.close(); pdfIndex.close(); io.shutdown(); super.onDestroy()
    }
}
