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
    private enum class PhotoMode { POST_FLIGHT, COMPONENT }

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val dbManager by lazy { DatabaseManager(this) }
    private val pdfIndex by lazy { PdfIndex(this) }
    private var repo: SearchRepository? = null
    private var hits: List<SearchHit> = emptyList()
    private var postFlightFaults: List<PostFlightAnalyzer.Fault> = emptyList()
    private var photoUri: Uri? = null
    private var photoMode: PhotoMode = PhotoMode.POST_FLIGHT

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
        if (ok) photoUri?.let { runOcr(it, photoMode) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        openExistingDb()

        b.btnImportDb.setOnClickListener {
            dbPicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*"))
        }
        b.btnPdfFolder.setOnClickListener { treePicker.launch(null) }
        b.btnCamera.setOnClickListener { capture(PhotoMode.POST_FLIGHT) }
        b.btnComponentPhoto.setOnClickListener { capture(PhotoMode.COMPONENT) }
        b.btnSearch.setOnClickListener { performSearch() }
        b.listFaults.setOnItemClickListener { _, _, position, _ ->
            if (position in postFlightFaults.indices) performPostFlightSearch(postFlightFaults[position])
        }
        b.listResults.setOnItemClickListener { _, _, position, _ -> openHit(hits[position]) }
    }

    private fun openExistingDb() {
        val f = dbManager.databaseFile()
        if (f.exists()) {
            repo?.close()
            repo = SearchRepository(f)
            refreshStatus()
        }
    }

    private fun importDb(uri: Uri) {
        b.txtStatus.text = "Copying database…"
        io.execute {
            try {
                val f = dbManager.importDatabase(uri) { bytes ->
                    runOnUiThread { b.txtStatus.text = "Copying database: ${bytes / (1024 * 1024)} MB" }
                }
                repo?.close()
                repo = SearchRepository(f)
                runOnUiThread { refreshStatus() }
            } catch (t: Throwable) {
                runOnUiThread { b.txtStatus.text = "Database import failed: ${t.message}" }
            }
        }
    }

    private fun refreshStatus() {
        val r = repo ?: return
        val manuals = r.manuals()
        b.txtStatus.text = "A340 Master v${r.architectureVersion()} • App V4 • ${manuals.count { it.status == "INTEGRATED" }} manuals"
        b.txtManuals.text = manuals.joinToString("\n") {
            val note = if (it.code == "ESPM") " • INFORMATION ONLY" else ""
            "${it.code}: ${it.status} • ${it.pages} pages • ${it.images} images$note"
        }
    }

    private fun performSearch() {
        val r = repo ?: return toast("Import database first")
        val q = b.editQuery.text.toString().trim()
        if (q.isBlank()) return
        b.txtStatus.text = "Searching all integrated manuals…"
        io.execute {
            try {
                hits = r.search(q)
                runOnUiThread {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtStatus.text = "Search complete: ${hits.size} results • dynamic manual ranking"
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("Search failed: ${t.message}") }
            }
        }
    }

    private fun performPostFlightSearch(fault: PostFlightAnalyzer.Fault) {
        val r = repo ?: return toast("Import database first")
        b.editQuery.setText(fault.text)
        val ataLabel = fault.ata?.let { " • ATA $it" } ?: ""
        b.txtStatus.text = "Searching FAULT ${fault.number}$ataLabel • TSM first…"

        io.execute {
            try {
                hits = r.searchPostFlightFault(fault.text, fault.ata, fault.identifiers)
                runOnUiThread {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtStatus.text = if (hits.isEmpty()) {
                        "No match for FAULT ${fault.number}. Edit OCR text and SEARCH manually."
                    } else {
                        "FAULT ${fault.number}$ataLabel • ${hits.size} related results • TSM ranked first"
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { b.txtStatus.text = "Post-flight search failed: ${t.message}" }
            }
        }
    }

    private fun performComponentSearch(analysis: ComponentAnalyzer.Result) {
        val r = repo ?: return toast("Import database first")
        val raw = analysis.rawText.trim()

        if (analysis.identifiers.isEmpty() && raw.isBlank()) {
            b.txtStatus.text = "No readable label text found. Retake a closer photo with the nameplate/FIN/P/N visible."
            return
        }

        b.editQuery.setText(if (analysis.identifiers.isNotEmpty()) analysis.query else raw)
        b.txtStatus.text = if (analysis.identifiers.isNotEmpty()) {
            "Detected IDs: ${analysis.identifiers.joinToString(", ")} • searching ALL manuals…"
        } else {
            "No structured ID detected • searching OCR text across ALL manuals…"
        }

        io.execute {
            try {
                hits = if (analysis.identifiers.isNotEmpty()) {
                    r.searchComponentIdentifiers(analysis.identifiers, raw)
                } else {
                    r.search(raw)
                }
                runOnUiThread {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtStatus.text = if (hits.isEmpty()) {
                        "No database match. Retake the label closer or edit OCR text and SEARCH manually."
                    } else {
                        "Component search complete: ${hits.size} results • all manuals included"
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { b.txtStatus.text = "Component search failed: ${t.message}" }
            }
        }
    }

    private fun openHit(hit: SearchHit) {
        val r = repo ?: return
        io.execute {
            val src = r.resolve(hit)
            val uri = src?.let { pdfIndex.find(it.filename) }
            runOnUiThread {
                if (src == null) {
                    toast("No source page mapping for this result")
                } else if (uri == null) {
                    toast("PDF not indexed: ${src.filename}")
                } else {
                    if (src.manual == "ESPM") {
                        toast("ESPM is INFORMATION ONLY / NOT PRIMARY REFERENCE")
                    }
                    startActivity(Intent(this, PdfPageActivity::class.java).apply {
                        putExtra("uri", uri.toString())
                        putExtra("page", src.pdfPage)
                        val manualLabel = if (src.manual == "ESPM") "ESPM • INFO ONLY" else src.manual
                        putExtra("title", "[$manualLabel] ${src.reference} — ${src.title}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        }
    }

    private fun capture(mode: PhotoMode) {
        photoMode = mode
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val filename = if (mode == PhotoMode.COMPONENT) "component.jpg" else "postflight.jpg"
        val f = File(dir, filename)
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        takePhoto.launch(photoUri)
    }

    private fun runOcr(uri: Uri, mode: PhotoMode) {
        b.txtStatus.text = if (mode == PhotoMode.COMPONENT) "Reading component label…" else "Reading post-flight report…"
        val image = InputImage.fromFilePath(this, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val cleaned = text.text.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
                if (mode == PhotoMode.COMPONENT) {
                    val analysis = ComponentAnalyzer.analyze(text.text)
                    if (analysis.identifiers.isEmpty()) b.editQuery.setText(cleaned)
                    performComponentSearch(analysis)
                } else {
                    val analysis = PostFlightAnalyzer.analyze(text.text)
                    postFlightFaults = analysis.faults
                    b.editQuery.setText(cleaned)
                    b.listFaults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, postFlightFaults)
                    if (postFlightFaults.isEmpty()) {
                        b.txtPostFlightSummary.text = "No separate fault lines detected. Verify OCR text and use SEARCH TECHNICAL MASTER."
                        b.txtStatus.text = "Post-flight OCR complete • manual search available"
                    } else {
                        b.txtPostFlightSummary.text = "${postFlightFaults.size} fault(s) detected • tap any fault below • TSM is ranked first"
                        performPostFlightSearch(postFlightFaults.first())
                    }
                }
            }
            .addOnFailureListener { b.txtStatus.text = "OCR failed: ${it.message}" }
            .addOnCompleteListener { recognizer.close() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        repo?.close()
        pdfIndex.close()
        io.shutdown()
        super.onDestroy()
    }
}
