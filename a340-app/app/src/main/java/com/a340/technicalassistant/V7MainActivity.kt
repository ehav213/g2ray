package com.a340.technicalassistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class V7MainActivity : AppCompatActivity() {
    private enum class PhotoMode { PFR, CAPTAIN, COMPONENT }

    private class Views(a: AppCompatActivity) {
        val txtStatus: TextView = a.findViewById(R.id.txtStatus)
        val btnImportDb: Button = a.findViewById(R.id.btnImportDb)
        val btnPdfFolder: Button = a.findViewById(R.id.btnPdfFolder)
        val btnModel: Button = a.findViewById(R.id.btnModel)
        val txtModel: TextView = a.findViewById(R.id.txtModel)
        val btnPfr: Button = a.findViewById(R.id.btnPfr)
        val btnCaptain: Button = a.findViewById(R.id.btnCaptain)
        val btnComponent: Button = a.findViewById(R.id.btnComponent)
        val txtCards: TextView = a.findViewById(R.id.txtCards)
        val listCards: ListView = a.findViewById(R.id.listCards)
        val editAircraft: EditText = a.findViewById(R.id.editAircraft)
        val editQuery: EditText = a.findViewById(R.id.editQuery)
        val btnRetrieve: Button = a.findViewById(R.id.btnRetrieve)
        val btnAnswer: Button = a.findViewById(R.id.btnAnswer)
        val btnConfirm: Button = a.findViewById(R.id.btnConfirm)
        val txtEvidenceHeading: TextView = a.findViewById(R.id.txtEvidenceHeading)
        val txtAnalysis: TextView = a.findViewById(R.id.txtAnalysis)
        val txtAiHeading: TextView = a.findViewById(R.id.txtAiHeading)
        val txtAnswer: TextView = a.findViewById(R.id.txtAnswer)
        val txtManuals: TextView = a.findViewById(R.id.txtManuals)
        val listResults: ListView = a.findViewById(R.id.listResults)
        val btnLegacy: Button = a.findViewById(R.id.btnLegacy)
    }

    private lateinit var b: Views
    private val io = Executors.newSingleThreadExecutor()
    private val dbm by lazy { DatabaseManager(this) }
    private val pdfs by lazy { PdfIndex(this) }
    private val models by lazy { ModelStore(this) }
    private val llm by lazy { LocalLlmEngine(this) }
    private val learning by lazy { SearchLearningStore(this) }
    private var repo: SearchRepository? = null
    private var evidence: EvidenceRepository? = null
    private var hits = emptyList<SearchHit>()
    private var cards = emptyList<Any>()
    private var mode = PhotoMode.PFR
    private var photoUri: Uri? = null
    private var queryForLearning = ""
    private var safety = ""
    private var openedHit: SearchHit? = null

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importDb) }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importModel) }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        status("Indexing PDF folder…")
        io.execute {
            val n = pdfs.rebuild(this, uri)
            ui { status("PDF LIBRARY READY • $n files indexed") }
        }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::ocr) else status("Photo capture cancelled")
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            status("IMAGE SELECTED • reading from phone…")
            ocr(uri)
        } else {
            status("Image selection cancelled")
        }
    }
    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status("Camera permission is required")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_v7_main)
        b = Views(this)
        openDb()
        modelStatus()

        b.btnImportDb.setOnClickListener { dbPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        b.btnPdfFolder.setOnClickListener { treePicker.launch(null) }
        b.btnModel.setOnClickListener { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        b.btnPfr.setOnClickListener { choosePhotoSource(PhotoMode.PFR) }
        b.btnCaptain.setOnClickListener { choosePhotoSource(PhotoMode.CAPTAIN) }
        b.btnComponent.setOnClickListener { choosePhotoSource(PhotoMode.COMPONENT) }
        b.btnRetrieve.setOnClickListener { retrieve(b.editQuery.text.toString(), "QUERY") }
        b.btnAnswer.setOnClickListener { answer() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.btnLegacy.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.listCards.setOnItemClickListener { _, _, p, _ -> cardClicked(p) }
        b.listResults.setOnItemClickListener { _, _, p, _ -> if (p in hits.indices) open(hits[p]) }
    }

    private fun openDb() {
        val f = dbm.databaseFile()
        if (!f.exists()) return status("SETUP REQUIRED • Import A340 Technical Master database")
        runCatching {
            repo?.close(); evidence?.close()
            repo = SearchRepository(f); evidence = EvidenceRepository(f)
            val ms = repo!!.manuals()
            val integrated = ms.count { it.status == "INTEGRATED" }
            status("DATABASE READY • A340 Master v${repo!!.architectureVersion()} • $integrated manuals • OFFLINE")
            b.txtManuals.text = buildString {
                append("$integrated manuals ready")
                ms.forEach {
                    append("\n").append(it.code).append("  •  ").append(it.pages).append(" pages")
                    if (it.code == "ESPM") append("  •  INFO ONLY")
                }
            }
        }.onFailure { status("Database open failed: ${it.message}") }
    }

    private fun importDb(uri: Uri) {
        status("Copying database…")
        io.execute {
            runCatching {
                dbm.importDatabase(uri) { bytes -> ui { status("IMPORTING DATABASE • ${bytes / 1048576} MB") } }
            }.onSuccess { ui { openDb() } }
                .onFailure { ui { status("Database import failed: ${it.message}") } }
        }
    }

    private fun importModel(uri: Uri) {
        b.txtModel.text = "Importing local model…"
        io.execute {
            runCatching {
                models.importModel(uri) { bytes -> ui { b.txtModel.text = "Importing model • ${bytes / 1048576} MB" } }
            }.onSuccess { f ->
                ui { b.txtModel.text = "Model installed: ${f.name} • loading…" }
                runCatching { llm.load(f) }
                    .onSuccess { ui(::modelStatus) }
                    .onFailure { ui { b.txtModel.text = "Model installed but load failed: ${it.message}" } }
            }.onFailure { ui { b.txtModel.text = "Model import failed: ${it.message}" } }
        }
    }

    private fun modelStatus() {
        val f = models.installedModel()
        b.txtModel.text = when {
            llm.isLoaded() -> "LOCAL AI READY • ${llm.loadedModelName()} • CPU • OFFLINE"
            f != null -> "LOCAL AI INSTALLED • ${f.name} • ${f.length() / 1048576} MB • loads on demand"
            else -> "LOCAL AI MODEL NOT INSTALLED • Retrieval remains available"
        }
    }

    private fun retrieve(raw: String, label: String) {
        val r = repo ?: return toast("Import database first")
        val q = raw.trim()
        if (q.isBlank()) return toast("Enter a fault or technical question first")
        b.editQuery.setText(q)
        status("$label • searching approved evidence…")
        b.btnAnswer.isEnabled = false
        b.btnConfirm.visibility = View.GONE
        b.txtAnswer.visibility = View.GONE
        b.txtAiHeading.visibility = View.GONE
        b.txtEvidenceHeading.visibility = View.VISIBLE
        b.txtAnalysis.visibility = View.VISIBLE
        b.txtAnalysis.text = "Searching approved A340 technical evidence…"
        b.listResults.visibility = View.GONE
        scrollTo(b.txtEvidenceHeading)
        val profile = b.editAircraft.text.toString().trim()
        io.execute {
            runCatching { r.searchAi(q, profile) }.onSuccess { bundle ->
                val learned = learning.rerank(q, bundle.hits)
                hits = learned.first
                queryForLearning = q
                safety = bundle.safetySummary
                openedHit = null
                ui {
                    b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                    b.txtAnalysis.text = bundle.analysis.summary() +
                        "\nCross-manual links: ${bundle.graphLinksAdded}" +
                        "\nLearning boosts: ${learned.second}" +
                        "\n${bundle.safetySummary}"
                    b.txtEvidenceHeading.visibility = View.VISIBLE
                    b.txtAnalysis.visibility = View.VISIBLE
                    if (hits.isNotEmpty()) {
                        sizeList(b.listResults, hits.size, 72, 5)
                        b.listResults.visibility = View.VISIBLE
                        b.btnAnswer.isEnabled = true
                        b.txtAiHeading.visibility = View.VISIBLE
                    } else {
                        b.listResults.visibility = View.GONE
                        b.txtAiHeading.visibility = View.GONE
                        b.txtAnalysis.append("\n\nNO APPROVED EVIDENCE RESULT — refine the query or check the technical source.")
                    }
                    status("$label • ${hits.size} approved evidence result(s)")
                    scrollTo(b.txtEvidenceHeading)
                }
            }.onFailure { error ->
                ui {
                    b.txtEvidenceHeading.visibility = View.VISIBLE
                    b.txtAnalysis.visibility = View.VISIBLE
                    b.txtAnalysis.text = "RETRIEVAL ERROR\n${error.message ?: error.javaClass.simpleName}"
                    b.listResults.visibility = View.GONE
                    status("Retrieval failed • see error below")
                    scrollTo(b.txtEvidenceHeading)
                }
            }
        }
    }

    private fun answer() {
        val r = repo ?: return toast("Import database first")
        val er = evidence ?: return toast("Evidence engine unavailable")
        if (hits.isEmpty()) return toast("Search approved evidence first")
        val q = queryForLearning.ifBlank { b.editQuery.text.toString().trim() }
        b.txtAiHeading.visibility = View.VISIBLE
        b.txtAnswer.visibility = View.VISIBLE
        b.txtAnswer.text = "Starting Local AI on CPU…\nThe answer will appear here while it is generated."
        b.btnAnswer.isEnabled = false
        status("LOCAL AI • preparing evidence and loading model…")
        scrollTo(b.txtAiHeading)

        io.execute {
            runCatching {
                val model = models.installedModel() ?: error("Select a compatible .litertlm model first")
                if (!llm.isLoaded()) llm.load(model)
                val pack = er.buildEvidencePack(hits, r)
                require(pack.isNotBlank()) { "No approved evidence could be assembled" }
                Triple(model, pack, q)
            }.onSuccess { (_, pack, question) ->
                val stream = StringBuilder()
                ui {
                    b.txtAnswer.text = ""
                    status("LOCAL AI • generating grounded answer…")
                    modelStatus()
                }
                llm.answerStreaming(
                    question = question,
                    evidencePack = pack,
                    safetySummary = safety,
                    onChunk = { chunk ->
                        ui {
                            stream.append(chunk)
                            b.txtAnswer.text = stream.toString()
                        }
                    },
                    onDone = {
                        ui {
                            if (stream.isBlank()) {
                                b.txtAnswer.text = "LOCAL AI returned no text. Try the query again or use the retrieved evidence directly."
                            }
                            b.btnAnswer.isEnabled = true
                            status("LOCAL AI • grounded answer ready")
                            modelStatus()
                            scrollTo(b.txtAiHeading)
                        }
                    },
                    onError = { error ->
                        ui {
                            b.txtAnswer.text = "LOCAL AI ERROR\n${error.message ?: error.javaClass.simpleName}\n\nRetrieved source evidence remains usable."
                            b.btnAnswer.isEnabled = true
                            status("LOCAL AI failed • retrieved evidence remains available")
                            modelStatus()
                            scrollTo(b.txtAiHeading)
                        }
                    }
                )
            }.onFailure { error ->
                ui {
                    b.txtAnswer.text = "LOCAL AI ERROR\n${error.message ?: error.javaClass.simpleName}\n\nRetrieved source evidence remains usable."
                    b.btnAnswer.isEnabled = true
                    status("LOCAL AI failed • see error below")
                    modelStatus()
                    scrollTo(b.txtAiHeading)
                }
            }
        }
    }

    private fun confirm() {
        val h = openedHit ?: return toast("Open the correct result first")
        val q = queryForLearning.ifBlank { b.editQuery.text.toString().trim() }
        if (q.isBlank()) return
        learning.confirm(q, h)
        b.txtAnalysis.append("\nLEARNED: ${h.manual} ${h.reference} confirmed for ranking only.")
        toast("Safe learning confirmation saved")
    }

    private fun choosePhotoSource(m: PhotoMode) {
        mode = m
        val title = when (m) {
            PhotoMode.PFR -> "PFR image source"
            PhotoMode.CAPTAIN -> "Tech Log image source"
            PhotoMode.COMPONENT -> "Component image source"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("CAMERA", "CHOOSE FROM PHONE / GALLERY")) { _, which ->
                when (which) {
                    0 -> capture(m)
                    1 -> {
                        mode = m
                        galleryPicker.launch("image/*")
                    }
                }
            }
            .show()
    }

    private fun capture(m: PhotoMode) {
        mode = m
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, when (mode) {
            PhotoMode.PFR -> "pfr.jpg"
            PhotoMode.CAPTAIN -> "captain.jpg"
            PhotoMode.COMPONENT -> "component.jpg"
        })
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        takePhoto.launch(photoUri)
    }

    private fun ocr(uri: Uri) {
        status("Reading image / OCR…")
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onFailure {
                status("Cannot open selected image: ${it.message}")
                rec.close()
            }
            .onSuccess { image ->
                rec.process(image)
                    .addOnSuccessListener { t ->
                        val text = t.text.trim()
                        if (text.isBlank()) {
                            b.editQuery.setText("")
                            showOcrCards("OCR • no readable text detected", 0)
                            status("OCR completed • no readable text found")
                            return@addOnSuccessListener
                        }
                        b.editQuery.setText(text)
                        when (mode) {
                            PhotoMode.PFR -> {
                                val a = PostFlightAnalyzer.analyze(text)
                                cards = a.faults
                                b.listCards.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, a.faults)
                                showOcrCards("PFR • ${a.faults.size} fault(s) detected", a.faults.size)
                                if (a.faults.isNotEmpty()) {
                                    a.faults.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "PFR FAULT") }
                                } else {
                                    retrieve(text, "PFR OCR")
                                }
                            }
                            PhotoMode.CAPTAIN -> {
                                val a = CaptainReportAnalyzer.analyze(text)
                                cards = a.defects
                                b.listCards.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, a.defects)
                                showOcrCards("TECH LOG • ${a.defects.size} defect(s) detected", a.defects.size)
                                if (a.defects.isNotEmpty()) {
                                    a.defects.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "CREW DEFECT") }
                                } else {
                                    retrieve(text, "TECH LOG OCR")
                                }
                            }
                            PhotoMode.COMPONENT -> {
                                val a = ComponentAnalyzer.analyze(text)
                                cards = emptyList()
                                b.listCards.adapter = null
                                b.listCards.visibility = View.GONE
                                b.txtCards.visibility = View.GONE
                                val q = listOf(a.rawText, a.identifiers.joinToString(" "), "PART NUMBER FIN CONNECTOR COMPONENT")
                                    .filter { it.isNotBlank() }.joinToString(" ")
                                retrieve(q, "COMPONENT PHOTO")
                            }
                        }
                    }
                    .addOnFailureListener { status("OCR failed: ${it.message}") }
                    .addOnCompleteListener { rec.close() }
            }
    }

    private fun showOcrCards(label: String, count: Int) {
        b.txtCards.text = label
        b.txtCards.visibility = View.VISIBLE
        if (count > 0) {
            sizeList(b.listCards, count, 64, 4)
            b.listCards.visibility = View.VISIBLE
        } else {
            b.listCards.visibility = View.GONE
        }
        scrollTo(b.txtCards)
    }

    private fun cardClicked(p: Int) {
        if (p !in cards.indices) return
        when (val c = cards[p]) {
            is PostFlightAnalyzer.Fault -> retrieve(augment(c.text, c.ata, c.identifiers), "PFR FAULT ${c.number}")
            is CaptainReportAnalyzer.Defect -> retrieve(augment(c.text, c.ata, c.identifiers), "CREW DEFECT ${c.number}")
        }
    }

    private fun augment(text: String, ata: String?, ids: List<String>) = buildString {
        append(text)
        ata?.let { append(" ATA ").append(it) }
        if (ids.isNotEmpty()) append(' ').append(ids.joinToString(" "))
    }

    private fun open(hit: SearchHit) {
        openedHit = hit
        b.btnConfirm.visibility = View.VISIBLE
        val r = repo ?: return
        io.execute {
            val src = r.resolve(hit)
            val uri = src?.let { pdfs.find(it.filename) }
            ui {
                when {
                    src == null -> toast("No source page mapping")
                    uri == null -> toast("PDF not indexed: ${src.filename}")
                    else -> startActivity(Intent(this, PdfPageActivity::class.java).apply {
                        putExtra("uri", uri.toString()); putExtra("page", src.pdfPage)
                        putExtra("title", "[${src.manual}] ${src.reference} — ${src.title}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        }
    }

    private fun sizeList(list: ListView, itemCount: Int, rowDp: Int, maxRows: Int) {
        val rows = itemCount.coerceIn(1, maxRows)
        val px = (rowDp * resources.displayMetrics.density).toInt()
        list.layoutParams = list.layoutParams.apply { height = rows * px }
        list.requestLayout()
    }

    private fun scrollTo(view: View) {
        view.post {
            val rect = Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
            view.requestRectangleOnScreen(rect, true)
        }
    }

    private fun status(s: String) { b.txtStatus.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ui(f: () -> Unit) = runOnUiThread(f)

    override fun onDestroy() {
        repo?.close(); evidence?.close(); pdfs.close(); llm.close(); learning.close(); io.shutdown()
        super.onDestroy()
    }
}
