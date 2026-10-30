package com.a340.technicalassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.a340.technicalassistant.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private enum class PhotoMode { POST_FLIGHT, CAPTAIN_REPORT, COMPONENT }
    private enum class FaultListMode { NONE, POST_FLIGHT, CAPTAIN_REPORT }

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val dbManager by lazy { DatabaseManager(this) }
    private val pdfIndex by lazy { PdfIndex(this) }
    private var repo: SearchRepository? = null
    private var hits: List<SearchHit> = emptyList()
    private var postFlightFaults: List<PostFlightAnalyzer.Fault> = emptyList()
    private var captainDefects: List<CaptainReportAnalyzer.Defect> = emptyList()
    private var listMode = FaultListMode.NONE
    private var photoUri: Uri? = null
    private var photoMode: PhotoMode = PhotoMode.POST_FLIGHT
    private var pendingPhotoMode: PhotoMode = PhotoMode.POST_FLIGHT

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
        else b.txtStatus.text = "Photo capture cancelled"
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera(pendingPhotoMode)
        else b.txtStatus.text = "Camera permission is required for photo/OCR functions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        openExistingDb()

        b.btnImportDb.setOnClickListener { dbPicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) }
        b.btnPdfFolder.setOnClickListener { treePicker.launch(null) }
        b.btnCamera.setOnClickListener { capture(PhotoMode.POST_FLIGHT) }
        b.btnCaptainReport.setOnClickListener { capture(PhotoMode.CAPTAIN_REPORT) }
        b.btnComponentPhoto.setOnClickListener { capture(PhotoMode.COMPONENT) }
        b.btnAiSearch.setOnClickListener { performAiSearch() }
        b.btnSearch.setOnClickListener { performSearch() }
        b.listFaults.setOnItemClickListener { _, _, position, _ ->
            when (listMode) {
                FaultListMode.POST_FLIGHT -> if (position in postFlightFaults.indices) performPostFlightSearch(postFlightFaults[position])
                FaultListMode.CAPTAIN_REPORT -> if (position in captainDefects.indices) performCaptainSearch(captainDefects[position])
                FaultListMode.NONE -> Unit
            }
        }
        b.listResults.setOnItemClickListener { _, _, position, _ -> if (position in hits.indices) openHit(hits[position]) }
    }

    private fun openExistingDb() {
        val f = dbManager.databaseFile()
        if (f.exists()) {
            runCatching {
                repo?.close()
                repo = SearchRepository(f)
                refreshStatus()
            }.onFailure {
                b.txtStatus.text = "Existing database could not be opened: ${it.message}"
            }
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
        b.txtStatus.text = "A340 Master v${r.architectureVersion()} • App V6 AI Retrieval • ${manuals.count { it.status == "INTEGRATED" }} manuals"
        b.txtManuals.text = manuals.joinToString("\n") {
            val note = if (it.code == "ESPM") " • INFORMATION ONLY" else ""
            "${it.code}: ${it.status} • ${it.pages} pages • ${it.images} images$note"
        }
        b.txtAiSummary.text = "AI retrieval ready • offline • evidence-only • source manuals remain authoritative"
    }

    private fun performSearch() {
        val r = repo ?: return toast("Import database first")
        val q = b.editQuery.text.toString().trim()
        if (q.isBlank()) return
        b.txtStatus.text = "Classic database search…"
        io.execute {
            try {
                hits = r.search(q)
                runOnUiThread {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtStatus.text = "Classic search complete: ${hits.size} results"
                    b.txtAiSummary.text = "Classic FTS mode • no semantic expansion"
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("Search failed: ${t.message}") }
            }
        }
    }

    private fun performAiSearch() {
        val q = b.editQuery.text.toString().trim()
        if (q.isBlank()) return
        runAiRetrieval(q, "AI query")
    }

    private fun runAiRetrieval(query: String, label: String) {
        val r = repo ?: return toast("Import database first")
        val profile = b.editAircraftProfile.text.toString().trim()
        b.txtStatus.text = "$label • AI understanding + evidence retrieval…"
        io.execute {
            try {
                val bundle = r.searchAi(query, profile)
                hits = bundle.hits
                runOnUiThread {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtAiSummary.text = buildString {
                        append(bundle.analysis.summary())
                        append("\nCross-manual exact-link expansion: ").append(bundle.graphLinksAdded)
                        append("\n").append(bundle.safetySummary)
                    }
                    b.txtStatus.text = if (hits.isEmpty()) {
                        "$label • no approved database evidence found"
                    } else {
                        "$label • ${hits.size} evidence results • AI ranked"
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { b.txtStatus.text = "AI retrieval failed: ${t.message}" }
            }
        }
    }

    private fun performPostFlightSearch(fault: PostFlightAnalyzer.Fault) {
        b.editQuery.setText(fault.text)
        val augmented = buildString {
            append(fault.text)
            fault.ata?.let { append(" ATA ").append(it) }
            if (fault.identifiers.isNotEmpty()) append(" ").append(fault.identifiers.joinToString(" "))
        }
        val ataLabel = fault.ata?.let { " • ATA $it" } ?: ""
        runAiRetrieval(augmented, "PFR FAULT ${fault.number}$ataLabel")
    }

    private fun performCaptainSearch(defect: CaptainReportAnalyzer.Defect) {
        b.editQuery.setText(defect.text)
        val augmented = buildString {
            append(defect.text)
            defect.ata?.let { append(" ATA ").append(it) }
            if (defect.identifiers.isNotEmpty()) append(" ").append(defect.identifiers.joinToString(" "))
        }
        val ataLabel = defect.ata?.let { " • ATA $it" } ?: ""
        runAiRetrieval(augmented, "CREW DEFECT ${defect.number}$ataLabel")
    }

    private fun performComponentSearch(analysis: ComponentAnalyzer.Result) {
        val raw = analysis.rawText.trim()
        if (analysis.identifiers.isEmpty() && raw.isBlank()) {
            b.txtStatus.text = "No readable label text found. Retake a closer photo with the nameplate/FIN/P/N visible."
            return
        }
        val q = if (analysis.identifiers.isNotEmpty()) {
            listOf(raw, analysis.identifiers.joinToString(" "), "PART NUMBER FIN CONNECTOR COMPONENT").filter { it.isNotBlank() }.joinToString(" ")
        } else raw
        b.editQuery.setText(if (analysis.identifiers.isNotEmpty()) analysis.query else raw)
        runAiRetrieval(q, "COMPONENT PHOTO")
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
                    if (src.manual == "ESPM") toast("ESPM is INFORMATION ONLY / NOT PRIMARY REFERENCE")
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
        pendingPhotoMode = mode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(mode)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera(mode: PhotoMode) {
        photoMode = mode
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val filename = when (mode) {
            PhotoMode.POST_FLIGHT -> "postflight.jpg"
            PhotoMode.CAPTAIN_REPORT -> "captain_report.jpg"
            PhotoMode.COMPONENT -> "component.jpg"
        }
        val f = File(dir, filename)
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        photoUri?.let { takePhoto.launch(it) }
    }

    private fun runOcr(uri: Uri, mode: PhotoMode) {
        b.txtStatus.text = when (mode) {
            PhotoMode.POST_FLIGHT -> "Reading post-flight report…"
            PhotoMode.CAPTAIN_REPORT -> "Reading captain / tech-log report…"
            PhotoMode.COMPONENT -> "Reading component label…"
        }
        val image = InputImage.fromFilePath(this, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val cleaned = text.text.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
                b.editQuery.setText(cleaned)
                when (mode) {
                    PhotoMode.COMPONENT -> {
                        listMode = FaultListMode.NONE
                        postFlightFaults = emptyList()
                        captainDefects = emptyList()
                        b.listFaults.adapter = null
                        b.txtReportSummary.text = "Component mode • OCR + ID extraction + AI retrieval"
                        performComponentSearch(ComponentAnalyzer.analyze(text.text))
                    }
                    PhotoMode.POST_FLIGHT -> {
                        val analysis = PostFlightAnalyzer.analyze(text.text)
                        listMode = FaultListMode.POST_FLIGHT
                        postFlightFaults = analysis.faults
                        captainDefects = emptyList()
                        b.listFaults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, postFlightFaults)
                        if (postFlightFaults.isEmpty()) {
                            b.txtReportSummary.text = "No separate PFR faults detected. Verify OCR text and use AI RETRIEVAL."
                            b.txtStatus.text = "Post-flight OCR complete • AI retrieval available"
                        } else {
                            b.txtReportSummary.text = "PFR: ${postFlightFaults.size} fault(s) detected • tap any fault • AI/TSM ranking"
                            performPostFlightSearch(postFlightFaults.first())
                        }
                    }
                    PhotoMode.CAPTAIN_REPORT -> {
                        val analysis = CaptainReportAnalyzer.analyze(text.text)
                        listMode = FaultListMode.CAPTAIN_REPORT
                        captainDefects = analysis.defects
                        postFlightFaults = emptyList()
                        b.listFaults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, captainDefects)
                        if (captainDefects.isEmpty()) {
                            b.txtReportSummary.text = "No separate crew defect detected. Edit OCR if needed, then use AI RETRIEVAL."
                            b.txtStatus.text = "Captain report OCR complete • AI retrieval available"
                        } else {
                            b.txtReportSummary.text = "CAPTAIN/TECH LOG: ${captainDefects.size} defect(s) detected • tap any defect • AI retrieval"
                            performCaptainSearch(captainDefects.first())
                        }
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
