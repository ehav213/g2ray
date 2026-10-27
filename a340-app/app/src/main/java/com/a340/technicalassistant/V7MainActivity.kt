package com.a340.technicalassistant

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
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
        val btnSettings: Button = a.findViewById(R.id.btnSettings)
        val settingsPanel: View = a.findViewById(R.id.settingsPanel)
        val btnImportDb: Button = a.findViewById(R.id.btnImportDb)
        val btnPdfFolder: Button = a.findViewById(R.id.btnPdfFolder)
        val btnModel: Button = a.findViewById(R.id.btnModel)
        val txtModel: TextView = a.findViewById(R.id.txtModel)
        val txtManuals: TextView = a.findViewById(R.id.txtManuals)
        val txtLog: TextView = a.findViewById(R.id.txtLog)
        val btnRefreshLog: Button = a.findViewById(R.id.btnRefreshLog)
        val btnCopyLog: Button = a.findViewById(R.id.btnCopyLog)
        val btnClearLog: Button = a.findViewById(R.id.btnClearLog)
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
        val txtSourcePacketHeading: TextView = a.findViewById(R.id.txtSourcePacketHeading)
        val txtAiHeading: TextView = a.findViewById(R.id.txtAiHeading)
        val aiProgressBox: View = a.findViewById(R.id.aiProgressBox)
        val txtAiProgress: TextView = a.findViewById(R.id.txtAiProgress)
        val aiProgress: ProgressBar = a.findViewById(R.id.aiProgress)
        val txtAnswer: TextView = a.findViewById(R.id.txtAnswer)
        val listResults: ListView = a.findViewById(R.id.listResults)
        val btnLegacy: Button = a.findViewById(R.id.btnLegacy)
    }

    private lateinit var b: Views
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dbm by lazy { DatabaseManager(this) }
    private val pdfs by lazy { PdfIndex(this) }
    private val models by lazy { ModelStore(this) }
    private val llm by lazy { LocalLlmEngine(this) }
    private val learning by lazy { SearchLearningStore(this) }
    private val appLog by lazy { AppLog(this) }
    private var repo: SearchRepository? = null
    private var evidence: EvidenceRepository? = null
    private var workflow: LineMaintenanceWorkflow? = null
    private var hits = emptyList<SearchHit>()
    private var cards = emptyList<Any>()
    private var mode = PhotoMode.PFR
    private var photoUri: Uri? = null
    private var queryForLearning = ""
    private var safety = ""
    private var openedHit: SearchHit? = null
    private var lineMaintenanceMode = false
    private var melDispatchStatus = MelDispatchStatus.NO_MATCH
    private var aiRunning = false
    private var aiProgressValue = 0
    private var aiStage = "IDLE"
    private var aiStartedAt = 0L

    private val progressPulse = object : Runnable {
        override fun run() {
            if (!aiRunning) return
            val elapsed = ((SystemClock.elapsedRealtime() - aiStartedAt) / 1000L).coerceAtLeast(0L)
            if (aiProgressValue < 90) aiProgressValue += 1
            renderAiProgress(elapsed)
            mainHandler.postDelayed(this, 1500L)
        }
    }

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importDb) }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importModel) }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        status("Indexing PDF folder…")
        logInfo("PDF_INDEX_START", uri.toString())
        io.execute {
            runCatching { pdfs.rebuild(this, uri) }
                .onSuccess { n -> ui { status("PDF LIBRARY READY • $n files indexed"); logInfo("PDF_INDEX_OK", "$n files") } }
                .onFailure { e -> ui { status("PDF indexing failed • see Settings Log"); logError("PDF_INDEX_FAIL", e) } }
        }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            logInfo("CAMERA_CAPTURE_OK", mode.name)
            photoUri?.let(::ocr)
        } else {
            status("Photo capture cancelled")
            logInfo("CAMERA_CAPTURE_CANCEL", mode.name)
        }
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            status("IMAGE SELECTED • reading from phone…")
            logInfo("GALLERY_IMAGE_SELECTED", "${mode.name} | $uri")
            ocr(uri)
        } else {
            status("Image selection cancelled")
            logInfo("GALLERY_IMAGE_CANCEL", mode.name)
        }
    }
    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status("Camera permission is required")
            logInfo("CAMERA_PERMISSION_DENIED")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_v7_main)
        b = Views(this)
        logInfo("APP_START", "version 0.7.8")
        openDb()
        modelStatus()

        b.btnSettings.setOnClickListener { toggleSettings() }
        b.btnRefreshLog.setOnClickListener { refreshLog() }
        b.btnCopyLog.setOnClickListener { copyLog() }
        b.btnClearLog.setOnClickListener { confirmClearLog() }
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

    private fun toggleSettings() {
        val opening = b.settingsPanel.visibility != View.VISIBLE
        b.settingsPanel.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) {
            modelStatus()
            refreshLog()
            logInfo("SETTINGS_OPEN")
            scrollTo(b.settingsPanel)
        }
    }

    private fun refreshLog() {
        b.txtLog.text = appLog.tail(120)
    }

    private fun copyLog() {
        val text = appLog.tail(300)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("A340 Technical Assistant Log", text))
        toast("Diagnostic log copied")
        logInfo("LOG_COPIED")
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle("Clear diagnostic log?")
            .setMessage("This removes the saved app diagnostic history only.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CLEAR") { _, _ ->
                appLog.clear()
                refreshLog()
                toast("Diagnostic log cleared")
            }
            .show()
    }

    private fun openDb() {
        val f = dbm.databaseFile()
        if (!f.exists()) {
            status("SETUP REQUIRED • open ⚙ Settings and import database")
            logInfo("DATABASE_MISSING")
            return
        }
        runCatching {
            workflow?.close(); repo?.close(); evidence?.close()
            repo = SearchRepository(f)
            evidence = EvidenceRepository(f)
            workflow = LineMaintenanceWorkflow(f, repo!!)
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
            logInfo("DATABASE_OPEN_OK", "${f.length() / 1048576} MB | $integrated manuals")
        }.onFailure {
            status("Database open failed • see ⚙ Settings Log")
            logError("DATABASE_OPEN_FAIL", it)
        }
    }

    private fun importDb(uri: Uri) {
        status("Copying database…")
        logInfo("DATABASE_IMPORT_START", uri.toString())
        io.execute {
            runCatching {
                dbm.importDatabase(uri) { bytes -> ui { status("IMPORTING DATABASE • ${bytes / 1048576} MB") } }
            }.onSuccess { f -> ui { logInfo("DATABASE_IMPORT_OK", "${f.length() / 1048576} MB"); openDb() } }
                .onFailure { e -> ui { status("Database import failed • see Settings Log"); logError("DATABASE_IMPORT_FAIL", e) } }
        }
    }

    private fun importModel(uri: Uri) {
        b.txtModel.text = "Importing local model…"
        logInfo("MODEL_IMPORT_START", uri.toString())
        io.execute {
            runCatching {
                models.importModel(uri) { bytes -> ui { b.txtModel.text = "Importing model • ${bytes / 1048576} MB" } }
            }.onSuccess { f ->
                ui { b.txtModel.text = "Model installed: ${f.name} • loading…"; logInfo("MODEL_IMPORT_OK", "${f.name} | ${f.length() / 1048576} MB") }
                runCatching { llm.load(f) }
                    .onSuccess { ui { logInfo("MODEL_LOAD_OK", f.name); modelStatus() } }
                    .onFailure { e -> ui { b.txtModel.text = "Model installed but load failed: ${e.message}"; logError("MODEL_LOAD_FAIL", e, f.name) } }
            }.onFailure { e -> ui { b.txtModel.text = "Model import failed: ${e.message}"; logError("MODEL_IMPORT_FAIL", e) } }
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
        val r = repo ?: return toast("Open ⚙ Settings and import database first")
        val q = raw.trim()
        if (q.isBlank()) return toast("Enter a fault or technical question first")
        stopAiProgress(hide = true)
        b.editQuery.setText(q)
        status("$label • searching approved evidence…")
        logInfo("RETRIEVAL_START", "$label | ${q.take(180)}")
        b.btnAnswer.isEnabled = false
        b.btnConfirm.visibility = View.GONE
        b.txtAnswer.visibility = View.GONE
        b.txtAiHeading.visibility = View.GONE
        b.txtSourcePacketHeading.visibility = View.GONE
        b.txtEvidenceHeading.visibility = View.VISIBLE
        b.txtAnalysis.visibility = View.VISIBLE
        b.txtAnalysis.text = "Searching approved A340 technical evidence…"
        b.listResults.visibility = View.GONE
        scrollTo(b.txtEvidenceHeading)
        val profile = b.editAircraft.text.toString().trim()

        lineMaintenanceMode = !label.startsWith("COMPONENT PHOTO")
        if (lineMaintenanceMode) {
            val wf = workflow ?: return toast("Line-maintenance workflow unavailable")
            b.txtEvidenceHeading.text = "LINE MAINTENANCE GUIDANCE"
            io.execute {
                runCatching { wf.run(q, profile) }.onSuccess { bundle ->
                    hits = bundle.hits
                    queryForLearning = q
                    safety = bundle.safetySummary + "\nMEL DISPATCH STATUS: ${bundle.dispatchStatus.name}"
                    melDispatchStatus = bundle.dispatchStatus
                    openedHit = null
                    ui {
                        b.listResults.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hits)
                        b.txtAnalysis.text = bundle.workflowSummary
                        if (hits.isNotEmpty()) {
                            sizeList(b.listResults, hits.size, 82, 8)
                            b.txtSourcePacketHeading.visibility = View.VISIBLE
                            b.listResults.visibility = View.VISIBLE
                            b.btnAnswer.isEnabled = true
                            b.txtAiHeading.visibility = View.VISIBLE
                        } else {
                            b.listResults.visibility = View.GONE
                            b.txtSourcePacketHeading.visibility = View.GONE
                            b.txtAiHeading.visibility = View.GONE
                            b.txtAnalysis.append("\n\nNO APPROVED SOURCE RESULT — verify the technical library and refine the fault description.")
                        }
                        status("$label • MEL-first workflow • ${hits.size} source result(s)")
                        logInfo("LINE_WORKFLOW_OK", "$label | MEL=${bundle.melHits.size} | status=${bundle.dispatchStatus} | sources=${hits.size}")
                        scrollTo(b.txtEvidenceHeading)
                    }
                }.onFailure { error ->
                    ui {
                        b.txtAnalysis.text = "LINE MAINTENANCE RETRIEVAL ERROR\n${error.message ?: error.javaClass.simpleName}"
                        b.listResults.visibility = View.GONE
                        b.txtSourcePacketHeading.visibility = View.GONE
                        status("Retrieval failed • see ⚙ Settings Log")
                        logError("LINE_WORKFLOW_FAIL", error, label)
                        scrollTo(b.txtEvidenceHeading)
                    }
                }
            }
            return
        }

        b.txtEvidenceHeading.text = "APPROVED EVIDENCE"
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
                    if (hits.isNotEmpty()) {
                        sizeList(b.listResults, hits.size, 72, 5)
                        b.txtSourcePacketHeading.text = "SOURCE DOCUMENT RESULTS — TAP TO OPEN ORIGINAL PDF PAGE"
                        b.txtSourcePacketHeading.visibility = View.VISIBLE
                        b.listResults.visibility = View.VISIBLE
                        b.btnAnswer.isEnabled = true
                        b.txtAiHeading.visibility = View.VISIBLE
                    } else {
                        b.listResults.visibility = View.GONE
                        b.txtSourcePacketHeading.visibility = View.GONE
                        b.txtAiHeading.visibility = View.GONE
                        b.txtAnalysis.append("\n\nNO APPROVED EVIDENCE RESULT — refine the query or check the technical source.")
                    }
                    status("$label • ${hits.size} approved evidence result(s)")
                    logInfo("RETRIEVAL_OK", "$label | ${hits.size} hits | links=${bundle.graphLinksAdded}")
                    scrollTo(b.txtEvidenceHeading)
                }
            }.onFailure { error ->
                ui {
                    b.txtAnalysis.text = "RETRIEVAL ERROR\n${error.message ?: error.javaClass.simpleName}"
                    b.listResults.visibility = View.GONE
                    b.txtSourcePacketHeading.visibility = View.GONE
                    status("Retrieval failed • see ⚙ Settings Log")
                    logError("RETRIEVAL_FAIL", error, label)
                    scrollTo(b.txtEvidenceHeading)
                }
            }
        }
    }

    private fun answer() {
        val r = repo ?: return toast("Open ⚙ Settings and import database first")
        val er = evidence ?: return toast("Evidence engine unavailable")
        if (hits.isEmpty()) return toast("Search approved evidence first")
        val q = queryForLearning.ifBlank { b.editQuery.text.toString().trim() }
        b.txtAiHeading.visibility = View.VISIBLE
        b.txtAnswer.visibility = View.VISIBLE
        b.txtAnswer.text = "Starting Local AI on CPU…\nProgress is estimated because LiteRT-LM does not expose a true completion percentage."
        b.btnAnswer.isEnabled = false
        startAiProgress(5, "PREPARING EVIDENCE")
        status("LOCAL AI • preparing grounded answer…")
        logInfo("AI_START", q.take(180))
        scrollTo(b.txtAiHeading)

        io.execute {
            runCatching {
                ui { updateAiProgress(12, "CHECKING LOCAL MODEL") }
                val model = models.installedModel() ?: error("Select a compatible .litertlm model in ⚙ Settings")
                if (!llm.isLoaded()) {
                    ui { updateAiProgress(20, "LOADING MODEL ON CPU") }
                    val loadStart = SystemClock.elapsedRealtime()
                    llm.load(model)
                    logInfo("AI_MODEL_LOAD_OK", "${SystemClock.elapsedRealtime() - loadStart} ms | ${model.name}")
                }
                ui { updateAiProgress(35, "BUILDING APPROVED EVIDENCE") }
                val pack = if (lineMaintenanceMode) {
                    er.buildLineMaintenanceEvidencePack(hits, r)
                } else {
                    er.buildEvidencePack(hits, r)
                }
                require(pack.isNotBlank()) { "No approved evidence could be assembled" }
                logInfo("AI_EVIDENCE_READY", "${pack.length} chars | ${hits.size} hits | lineMode=$lineMaintenanceMode | MEL=$melDispatchStatus")
                Triple(model, pack, q)
            }.onSuccess { (_, pack, question) ->
                val stream = StringBuilder()
                var firstChunkLogged = false
                ui {
                    b.txtAnswer.text = "Waiting for first Local AI token…"
                    updateAiProgress(45, "GENERATING — WAITING FOR FIRST TOKEN")
                    status("LOCAL AI • generating grounded answer…")
                    modelStatus()
                }
                llm.answerStreaming(
                    question = question,
                    evidencePack = pack,
                    safetySummary = safety,
                    onChunk = { chunk ->
                        ui {
                            val incoming = chunk.trimStart()
                            if (incoming.isNotBlank()) {
                                if (incoming.startsWith(stream.toString()) && stream.isNotEmpty()) {
                                    stream.setLength(0)
                                    stream.append(incoming)
                                } else if (!stream.endsWith(incoming)) {
                                    stream.append(incoming)
                                }
                                b.txtAnswer.text = stream.toString()
                                val estimated = (48 + stream.length / 40).coerceAtMost(92)
                                updateAiProgress(estimated, "STREAMING ANSWER")
                                if (!firstChunkLogged) {
                                    firstChunkLogged = true
                                    logInfo("AI_FIRST_TOKEN", "${elapsedAiSeconds()} s")
                                }
                            }
                        }
                    },
                    onDone = {
                        ui {
                            if (stream.isBlank()) b.txtAnswer.text = "LOCAL AI returned no text. Use the retrieved evidence and check the diagnostic log."
                            b.btnAnswer.isEnabled = true
                            finishAiProgress(true, "ANSWER COMPLETE")
                            status("LOCAL AI • grounded answer ready")
                            logInfo("AI_DONE", "${elapsedAiSeconds()} s | ${stream.length} chars")
                            modelStatus()
                            scrollTo(b.txtAiHeading)
                        }
                    },
                    onError = { error ->
                        ui {
                            b.txtAnswer.text = "LOCAL AI ERROR\n${error.message ?: error.javaClass.simpleName}\n\nRetrieved source evidence remains usable."
                            b.btnAnswer.isEnabled = true
                            finishAiProgress(false, "AI ERROR — CHECK SETTINGS LOG")
                            status("LOCAL AI failed • see ⚙ Settings Log")
                            logError("AI_STREAM_FAIL", error, "${elapsedAiSeconds()} s")
                            modelStatus()
                            scrollTo(b.txtAiHeading)
                        }
                    }
                )
            }.onFailure { error ->
                ui {
                    b.txtAnswer.text = "LOCAL AI ERROR\n${error.message ?: error.javaClass.simpleName}\n\nRetrieved source evidence remains usable."
                    b.btnAnswer.isEnabled = true
                    finishAiProgress(false, "AI ERROR — CHECK SETTINGS LOG")
                    status("LOCAL AI failed • see ⚙ Settings Log")
                    logError("AI_PREP_FAIL", error, "${elapsedAiSeconds()} s")
                    modelStatus()
                    scrollTo(b.txtAiHeading)
                }
            }
        }
    }

    private fun startAiProgress(percent: Int, stage: String) {
        aiRunning = true
        aiStartedAt = SystemClock.elapsedRealtime()
        aiProgressValue = percent.coerceIn(0, 100)
        aiStage = stage
        b.aiProgressBox.visibility = View.VISIBLE
        renderAiProgress(0)
        mainHandler.removeCallbacks(progressPulse)
        mainHandler.postDelayed(progressPulse, 1500L)
    }

    private fun updateAiProgress(percent: Int, stage: String) {
        aiProgressValue = maxOf(aiProgressValue, percent.coerceIn(0, 95))
        aiStage = stage
        renderAiProgress(elapsedAiSeconds())
    }

    private fun finishAiProgress(success: Boolean, stage: String) {
        aiRunning = false
        mainHandler.removeCallbacks(progressPulse)
        aiProgressValue = if (success) 100 else aiProgressValue.coerceAtLeast(1)
        aiStage = stage
        b.aiProgressBox.visibility = View.VISIBLE
        renderAiProgress(elapsedAiSeconds())
    }

    private fun stopAiProgress(hide: Boolean) {
        aiRunning = false
        mainHandler.removeCallbacks(progressPulse)
        if (hide) b.aiProgressBox.visibility = View.GONE
    }

    private fun renderAiProgress(elapsedSeconds: Long) {
        b.aiProgress.progress = aiProgressValue
        b.txtAiProgress.text = "ESTIMATED AI PROGRESS • $aiProgressValue% • $aiStage • ${elapsedSeconds}s"
    }

    private fun elapsedAiSeconds(): Long = if (aiStartedAt == 0L) 0L else
        ((SystemClock.elapsedRealtime() - aiStartedAt) / 1000L).coerceAtLeast(0L)

    private fun confirm() {
        val h = openedHit ?: return toast("Open the correct result first")
        val q = queryForLearning.ifBlank { b.editQuery.text.toString().trim() }
        if (q.isBlank()) return
        learning.confirm(q, h)
        b.txtAnalysis.append("\nLEARNED: ${h.manual} ${h.reference} confirmed for ranking only. MEL-first workflow order is never changed.")
        logInfo("SAFE_LEARNING_CONFIRM", "${h.manual} | ${h.reference}")
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
                    1 -> { mode = m; galleryPicker.launch("image/*") }
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
        logInfo("CAMERA_OPEN", mode.name)
        takePhoto.launch(photoUri)
    }

    private fun ocr(uri: Uri) {
        status("Reading image / OCR…")
        logInfo("OCR_START", "${mode.name} | $uri")
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onFailure {
                status("Cannot open selected image • see Settings Log")
                logError("OCR_IMAGE_OPEN_FAIL", it, uri.toString())
                rec.close()
            }
            .onSuccess { image ->
                rec.process(image)
                    .addOnSuccessListener { t ->
                        val text = t.text.trim()
                        logInfo("OCR_OK", "${mode.name} | ${text.length} chars")
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
                                if (a.faults.isNotEmpty()) a.faults.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "PFR FAULT") }
                                else retrieve(text, "PFR OCR")
                            }
                            PhotoMode.CAPTAIN -> {
                                val a = CaptainReportAnalyzer.analyze(text)
                                cards = a.defects
                                b.listCards.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, a.defects)
                                showOcrCards("TECH LOG • ${a.defects.size} defect(s) detected", a.defects.size)
                                if (a.defects.isNotEmpty()) a.defects.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), "CREW DEFECT") }
                                else retrieve(text, "TECH LOG OCR")
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
                    .addOnFailureListener { e -> status("OCR failed • see ⚙ Settings Log"); logError("OCR_FAIL", e, mode.name) }
                    .addOnCompleteListener { rec.close() }
            }
    }

    private fun showOcrCards(label: String, count: Int) {
        b.txtCards.text = label
        b.txtCards.visibility = View.VISIBLE
        if (count > 0) {
            sizeList(b.listCards, count, 64, 4)
            b.listCards.visibility = View.VISIBLE
        } else b.listCards.visibility = View.GONE
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
        logInfo("RESULT_OPEN_REQUEST", "${hit.manual} | ${hit.reference}")
        val r = repo ?: return
        io.execute {
            val src = runCatching { r.resolve(hit) }.getOrElse {
                ui { logError("RESULT_RESOLVE_FAIL", it, "${hit.manual} | ${hit.reference}") }
                null
            }
            val uri = src?.let { pdfs.find(it.filename) }
            ui {
                when {
                    src == null -> { toast("No source page mapping"); logInfo("RESULT_NO_MAPPING", "${hit.manual} | ${hit.reference}") }
                    uri == null -> { toast("PDF not indexed: ${src.filename}"); logInfo("RESULT_PDF_NOT_INDEXED", src.filename) }
                    else -> {
                        logInfo("RESULT_OPEN_OK", "${src.filename} | page ${src.pdfPage}")
                        startActivity(Intent(this, PdfPageActivity::class.java).apply {
                            putExtra("uri", uri.toString()); putExtra("page", src.pdfPage)
                            putExtra("title", "[${src.manual}] ${src.reference} — ${src.title}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
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

    private fun logInfo(event: String, detail: String = "") {
        appLog.i(event, detail)
        if (::b.isInitialized && b.settingsPanel.visibility == View.VISIBLE) refreshLog()
    }

    private fun logError(event: String, error: Throwable?, detail: String = "") {
        appLog.e(event, error, detail)
        if (::b.isInitialized && b.settingsPanel.visibility == View.VISIBLE) refreshLog()
    }

    private fun status(s: String) { b.txtStatus.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ui(f: () -> Unit) = runOnUiThread(f)

    override fun onDestroy() {
        stopAiProgress(hide = false)
        logInfo("APP_DESTROY")
        workflow?.close(); repo?.close(); evidence?.close(); pdfs.close(); llm.close(); learning.close(); io.shutdown()
        super.onDestroy()
    }
}
