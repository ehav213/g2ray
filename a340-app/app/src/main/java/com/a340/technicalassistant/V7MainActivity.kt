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
import com.a340.technicalassistant.databinding.ActivityV7MainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class V7MainActivity : AppCompatActivity() {
    private enum class PhotoMode { PFR, CAPTAIN, COMPONENT }
    private lateinit var b: ActivityV7MainBinding
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
            ui { status("PDF index ready: $n files") }
        }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::ocr) else status("Photo capture cancelled")
    }
    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status("Camera permission is required")
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        b = ActivityV7MainBinding.inflate(layoutInflater)
        setContentView(b.root)
        openDb()
        modelStatus()

        b.btnImportDb.setOnClickListener { dbPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        b.btnPdfFolder.setOnClickListener { treePicker.launch(null) }
        b.btnModel.setOnClickListener { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        b.btnPfr.setOnClickListener { capture(PhotoMode.PFR) }
        b.btnCaptain.setOnClickListener { capture(PhotoMode.CAPTAIN) }
        b.btnComponent.setOnClickListener { capture(PhotoMode.COMPONENT) }
        b.btnRetrieve.setOnClickListener { retrieve(b.editQuery.text.toString(), "QUERY") }
        b.btnAnswer.setOnClickListener { answer() }
        b.btnConfirm.setOnClickListener { confirm() }
        b.btnLegacy.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.listCards.setOnItemClickListener { _, _, p, _ -> cardClicked(p) }
        b.listResults.setOnItemClickListener { _, _, p, _ -> if (p in hits.indices) open(hits[p]) }
    }

    private fun openDb() {
        val f = dbm.databaseFile()
        if (!f.exists()) return status("Import A340 Technical Master v5 database")
        runCatching {
            repo?.close(); evidence?.close()
            repo = SearchRepository(f); evidence = EvidenceRepository(f)
            val ms = repo!!.manuals()
            status("A340 Master v${repo!!.architectureVersion()} • V7 Local LLM • ${ms.count { it.status == "INTEGRATED" }} manuals")
            b.txtManuals.text = ms.joinToString("\n") {
                "${it.code}: ${it.status} • ${it.pages} pages" + if (it.code == "ESPM") " • INFO ONLY" else ""
            }
        }.onFailure { status("Database open failed: ${it.message}") }
    }

    private fun importDb(uri: Uri) {
        status("Copying database…")
        io.execute {
            runCatching {
                dbm.importDatabase(uri) { bytes -> ui { status("Copying DB: ${bytes / 1048576} MB") } }
            }.onSuccess { ui { openDb() } }
             .onFailure { ui { status("Database import failed: ${it.message}") } }
        }
    }

    private fun importModel(uri: Uri) {
        b.txtModel.text = "Importing local model…"
        io.execute {
            runCatching {
                models.importModel(uri) { bytes -> ui { b.txtModel.text = "Importing model: ${bytes / 1048576} MB" } }
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
            llm.isLoaded() -> "LOCAL LLM READY • ${llm.loadedModelName()} • CPU • OFFLINE"
            f != null -> "Model installed: ${f.name} • ${f.length() / 1048576} MB • loads on ASK"
            else -> "No .litertlm model installed. Retrieval works without a model."
        }
    }

    private fun retrieve(raw: String, label: String) {
        val r = repo ?: return toast("Import database first")
        val q = raw.trim()
        if (q.isBlank()) return
        b.editQuery.setText(q)
        status("$label • grounded retrieval…")
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
                    status("$label • ${hits.size} approved evidence results")
                }
            }.onFailure { ui { status("Retrieval failed: ${it.message}") } }
        }
    }

    private fun answer() {
        val r = repo ?: return toast("Import database first")
        val er = evidence ?: return toast("Evidence engine unavailable")
        if (hits.isEmpty()) return toast("Run GROUNDED RETRIEVAL first")
        val q = queryForLearning.ifBlank { b.editQuery.text.toString().trim() }
        b.txtAnswer.text = "Preparing evidence and local inference…"
        io.execute {
            runCatching {
                val model = models.installedModel() ?: error("Select a compatible .litertlm model first")
                if (!llm.isLoaded()) llm.load(model)
                val pack = er.buildEvidencePack(hits, r)
                require(pack.isNotBlank()) { "No approved evidence could be assembled" }
                llm.answer(q, pack, safety)
            }.onSuccess { result ->
                ui { b.txtAnswer.text = result; modelStatus() }
            }.onFailure {
                ui {
                    b.txtAnswer.text = "LOCAL AI UNAVAILABLE\n${it.message}\n\nRetrieved source results remain usable."
                    modelStatus()
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
        status("Reading image/OCR…")
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromFilePath(this, uri))
            .addOnSuccessListener { t ->
                val text = t.text
                b.editQuery.setText(text)
                when (mode) {
                    PhotoMode.PFR -> {
                        val a = PostFlightAnalyzer.analyze(text)
                        cards = a.faults
                        b.listCards.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, a.faults)
                        b.txtCards.text = "PFR • ${a.faults.size} fault(s). Tap a fault."
                        a.faults.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "PFR FAULT") }
                    }
                    PhotoMode.CAPTAIN -> {
                        val a = CaptainReportAnalyzer.analyze(text)
                        cards = a.defects
                        b.listCards.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, a.defects)
                        b.txtCards.text = "CAPTAIN/TECH LOG • ${a.defects.size} defect(s). Tap a defect."
                        a.defects.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "CREW DEFECT") }
                    }
                    PhotoMode.COMPONENT -> {
                        val a = ComponentAnalyzer.analyze(text)
                        cards = emptyList()
                        b.listCards.adapter = null
                        val q = listOf(a.rawText, a.identifiers.joinToString(" "), "PART NUMBER FIN CONNECTOR COMPONENT")
                            .filter { it.isNotBlank() }.joinToString(" ")
                        retrieve(q, "COMPONENT PHOTO")
                    }
                }
            }
            .addOnFailureListener { status("OCR failed: ${it.message}") }
            .addOnCompleteListener { rec.close() }
    }

    private fun cardClicked(p: Int) {
        if (p !in cards.indices) return
        when (val c = cards[p]) {
            is PostFlightAnalyzer.Fault -> retrieve(augment(c.text, c.ata, c.identifiers), "PFR FAULT ${c.number}")
            is CaptainReportAnalyzer.Defect -> retrieve(augment(c.text, c.ata, c.identifiers), "CREW DEFECT ${c.number}")
        }
    }

    private fun augment(text: String, ata: String?, ids: List<String>) =
        buildString {
            append(text)
            ata?.let { append(" ATA ").append(it) }
            if (ids.isNotEmpty()) append(' ').append(ids.joinToString(" "))
        }

    private fun open(hit: SearchHit) {
        openedHit = hit
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

    private fun status(s: String) { b.txtStatus.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ui(f: () -> Unit) = runOnUiThread(f)

    override fun onDestroy() {
        repo?.close(); evidence?.close(); pdfs.close(); llm.close(); learning.close(); io.shutdown()
        super.onDestroy()
    }
}
