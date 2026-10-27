package com.a340.technicalassistant

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class V8MainActivity : AppCompatActivity() {
    private enum class PhotoMode { PFR, TECHLOG, COMPONENT }

    private lateinit var txtStatus: TextView
    private lateinit var settingsPanel: View
    private lateinit var txtModel: TextView
    private lateinit var txtManuals: TextView
    private lateinit var txtLog: TextView
    private lateinit var editAircraft: EditText
    private lateinit var editQuery: EditText
    private lateinit var ocrContainer: LinearLayout
    private lateinit var txtSummaryHeading: TextView
    private lateinit var txtSummary: TextView
    private lateinit var txtSourcesHeading: TextView
    private lateinit var sourceContainer: LinearLayout
    private lateinit var txtAiHeading: TextView
    private lateinit var aiCard: View
    private lateinit var btnAi: MaterialButton
    private lateinit var aiProgress: ProgressBar
    private lateinit var txtAiState: TextView
    private lateinit var txtAiAnswer: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dbm by lazy { DatabaseManager(this) }
    private val pdfs by lazy { PdfIndex(this) }
    private val models by lazy { ModelStore(this) }
    private val llm by lazy { LocalLlmEngine(this) }
    private val appLog by lazy { AppLog(this) }
    private var repo: SearchRepository? = null
    private var evidence: EvidenceRepository? = null
    private var workflow: LineMaintenanceWorkflow? = null
    private var hits: List<SearchHit> = emptyList()
    private var currentQuery = ""
    private var currentSafety = ""
    private var currentSummary = ""
    private var currentLineMode = true
    private var currentMelStatus = MelDispatchStatus.NO_MATCH
    private var photoMode = PhotoMode.PFR
    private var photoUri: Uri? = null
    private var aiGenerationId = 0

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDatabase(uri)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        status("INDEXING PDF LIBRARY…")
        appLog.i("PDF_INDEX_START", uri.toString())
        io.execute {
            runCatching { pdfs.rebuild(this, uri) }
                .onSuccess { n -> ui { status("PDF LIBRARY READY • $n files"); appLog.i("PDF_INDEX_OK", "$n files") } }
                .onFailure { e -> ui { status("PDF INDEX FAILED • CHECK LOG"); appLog.e("PDF_INDEX_FAIL", e) } }
        }
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ocr(uri)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::ocr)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v8_main)
        bindViews()
        wireActions()
        appLog.i("APP_START", "version 0.8.0")
        openDb()
        updateModelStatus()
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        settingsPanel = findViewById(R.id.settingsPanel)
        txtModel = findViewById(R.id.txtModel)
        txtManuals = findViewById(R.id.txtManuals)
        txtLog = findViewById(R.id.txtLog)
        editAircraft = findViewById(R.id.editAircraft)
        editQuery = findViewById(R.id.editQuery)
        ocrContainer = findViewById(R.id.ocrContainer)
        txtSummaryHeading = findViewById(R.id.txtSummaryHeading)
        txtSummary = findViewById(R.id.txtEngineeringSummary)
        txtSourcesHeading = findViewById(R.id.txtSourcesHeading)
        sourceContainer = findViewById(R.id.sourceContainer)
        txtAiHeading = findViewById(R.id.txtAiHeading)
        aiCard = findViewById(R.id.aiCard)
        btnAi = findViewById(R.id.btnAi)
        aiProgress = findViewById(R.id.aiProgress)
        txtAiState = findViewById(R.id.txtAiState)
        txtAiAnswer = findViewById(R.id.txtAiAnswer)
    }

    private fun wireActions() {
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (settingsPanel.visibility == View.VISIBLE) refreshLog()
        }
        findViewById<MaterialButton>(R.id.btnImportDb).setOnClickListener { dbPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        findViewById<MaterialButton>(R.id.btnPdfFolder).setOnClickListener { treePicker.launch(null) }
        findViewById<MaterialButton>(R.id.btnModel).setOnClickListener { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
        findViewById<MaterialButton>(R.id.btnRefreshLog).setOnClickListener { refreshLog() }
        findViewById<MaterialButton>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener { appLog.clear(); refreshLog() }
        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener { retrieve(editQuery.text.toString(), true, "QUERY") }
        findViewById<MaterialButton>(R.id.btnPfr).setOnClickListener { choosePhoto(PhotoMode.PFR) }
        findViewById<MaterialButton>(R.id.btnTechLog).setOnClickListener { choosePhoto(PhotoMode.TECHLOG) }
        findViewById<MaterialButton>(R.id.btnComponent).setOnClickListener { choosePhoto(PhotoMode.COMPONENT) }
        btnAi.setOnClickListener { runOptionalAi() }
    }

    private fun openDb() {
        val file = dbm.databaseFile()
        if (!file.exists()) {
            status("SETUP REQUIRED • IMPORT DATABASE IN ⚙")
            return
        }
        runCatching {
            workflow?.close(); repo?.close(); evidence?.close()
            repo = SearchRepository(file)
            evidence = EvidenceRepository(file)
            workflow = LineMaintenanceWorkflow(file, repo!!)
            val manuals = repo!!.manuals()
            val integrated = manuals.count { it.status == "INTEGRATED" }
            status("READY • DB v${repo!!.architectureVersion()} • $integrated MANUALS • OFFLINE")
            txtManuals.text = buildString {
                append("TECHNICAL LIBRARY • $integrated manuals")
                manuals.forEach { append("\n${it.code}: ${it.pages} pages") }
            }
            appLog.i("DATABASE_OPEN_OK", "${file.length() / 1048576} MB | $integrated manuals")
        }.onFailure { e -> status("DATABASE OPEN FAILED • CHECK LOG"); appLog.e("DATABASE_OPEN_FAIL", e) }
    }

    private fun importDatabase(uri: Uri) {
        status("IMPORTING DATABASE…")
        io.execute {
            runCatching { dbm.importDatabase(uri) { bytes -> ui { status("IMPORTING DATABASE • ${bytes / 1048576} MB") } } }
                .onSuccess { ui { appLog.i("DATABASE_IMPORT_OK", "${it.length() / 1048576} MB"); openDb() } }
                .onFailure { e -> ui { status("DATABASE IMPORT FAILED • CHECK LOG"); appLog.e("DATABASE_IMPORT_FAIL", e) } }
        }
    }

    private fun importModel(uri: Uri) {
        txtModel.text = "Importing model…"
        io.execute {
            runCatching { models.importModel(uri) { bytes -> ui { txtModel.text = "Importing model • ${bytes / 1048576} MB" } } }
                .onSuccess { f ->
                    appLog.i("MODEL_IMPORT_OK", f.name)
                    ui { txtModel.text = "Model installed • ${f.name} • loads on demand" }
                }
                .onFailure { e -> ui { txtModel.text = "Model import failed: ${e.message}"; appLog.e("MODEL_IMPORT_FAIL", e) } }
        }
    }

    private fun updateModelStatus() {
        val f = models.installedModel()
        txtModel.text = when {
            llm.isLoaded() -> "LOCAL AI READY • ${llm.loadedModelName()} • CPU"
            f != null -> "LOCAL AI INSTALLED • ${f.name} • ${f.length() / 1048576} MB • optional"
            else -> "LOCAL AI NOT INSTALLED • core workflow still fully usable"
        }
    }

    private fun retrieve(raw: String, lineMode: Boolean, label: String) {
        val q = raw.trim()
        if (q.isBlank()) return toast("Enter a defect or technical request")
        val r = repo ?: return toast("Import database first")
        currentQuery = q
        currentLineMode = lineMode
        editQuery.setText(q)
        clearResults()
        txtSummaryHeading.visibility = View.VISIBLE
        txtSummary.visibility = View.VISIBLE
        txtSummary.text = "Searching approved technical sources…"
        status("$label • RETRIEVING…")
        appLog.i("RETRIEVAL_START", "$label | ${q.take(180)}")
        val profile = editAircraft.text.toString().trim()

        if (lineMode) {
            val wf = workflow ?: return toast("Line-maintenance workflow unavailable")
            io.execute {
                runCatching { wf.run(q, profile) }
                    .onSuccess { result -> ui { showLineResult(result, profile, label) } }
                    .onFailure { e -> ui { txtSummary.text = "RETRIEVAL ERROR\n${e.message}"; status("RETRIEVAL FAILED • CHECK LOG"); appLog.e("WORKFLOW_FAIL", e) } }
            }
        } else {
            io.execute {
                runCatching { r.searchAi(q, profile) }
                    .onSuccess { bundle -> ui {
                        hits = bundle.hits.take(12)
                        currentSafety = bundle.safetySummary
                        currentMelStatus = MelDispatchStatus.NO_MATCH
                        currentSummary = buildComponentSummary(bundle.analysis.summary(), hits)
                        txtSummary.text = currentSummary
                        renderSourcePacket(hits)
                        showAiOption()
                        status("$label • ${hits.size} SOURCE RESULT(S)")
                    } }
                    .onFailure { e -> ui { txtSummary.text = "SEARCH ERROR\n${e.message}"; appLog.e("SEARCH_FAIL", e) } }
            }
        }
    }

    private fun showLineResult(result: LineMaintenanceResult, profile: String, label: String) {
        hits = result.hits
        currentSafety = result.safetySummary + "\nMEL DISPATCH STATUS: ${result.dispatchStatus.name}"
        currentMelStatus = result.dispatchStatus
        currentSummary = buildEngineeringSummary(result, profile)
        txtSummary.text = currentSummary
        renderSourcePacket(hits)
        showAiOption()
        status("$label • MEL-FIRST • ${hits.size} SOURCE(S)")
        appLog.i("WORKFLOW_OK", "MEL=${result.melHits.size} | ${result.dispatchStatus} | sources=${hits.size}")
    }

    private fun buildEngineeringSummary(result: LineMaintenanceResult, profile: String): String {
        fun refs(manual: String, max: Int = 3): String {
            val list = result.hits.filter { it.manual.equals(manual, true) }.take(max)
            return if (list.isEmpty()) "No relevant source returned" else list.joinToString("\n") {
                "• ${it.reference.ifBlank { "Reference not indexed" }} — ${cleanTitle(it.title)}"
            }
        }
        val melText = when (result.dispatchStatus) {
            MelDispatchStatus.GO_EXPLICIT -> "GO wording found in matched MEL evidence. Apply only after verifying all MEL conditions, (M)/(O), limitations and aircraft applicability in the original MEL."
            MelDispatchStatus.NO_GO_EXPLICIT -> "NO-GO / operative requirement wording found in matched MEL evidence. Verify the original MEL before dispatch."
            MelDispatchStatus.VERIFY_REQUIRED -> "MEL candidate found, but dispatch status is NOT safe to infer automatically. Verify the original MEL entry and effectivity."
            MelDispatchStatus.NO_MATCH -> "No relevant MEL item was confirmed. This does NOT mean GO."
        }
        return buildString {
            append("MEL / DISPATCH\n").append(melText)
            result.melHits.firstOrNull()?.let { append("\nPrimary MEL: ${it.reference} — ${cleanTitle(it.title)}") }
            append("\n\nTSM / FAULT ISOLATION\n").append(refs("TSM"))
            append("\n\nAMM / MAINTENANCE\n").append(refs("AMM"))
            append("\n\nSCHEMATIC / WIRING / PARTS\n")
            append("ASM:\n").append(refs("ASM", 2)).append("\nAWM:\n").append(refs("AWM", 2)).append("\nIPC:\n").append(refs("IPC", 2))
            if (result.includeEspm) append("\nESPM:\n").append(refs("ESPM", 2))
            append("\n\nEFFECTIVITY\n")
            append(if (profile.isBlank()) "NOT VERIFIED — enter REG/MSN/effectivity before applying a task." else "Aircraft profile: $profile — verify applicability in each original source.")
            append("\n\nOriginal document pages are listed below. This summary is deterministic retrieval output and does not depend on Local AI.")
        }
    }

    private fun buildComponentSummary(analysis: String, sourceHits: List<SearchHit>): String = buildString {
        append("COMPONENT / IDENTIFIER SEARCH\n").append(analysis)
        append("\n\nTOP SOURCES\n")
        sourceHits.take(5).forEach { append("• [${it.manual}] ${it.reference} — ${cleanTitle(it.title)}\n") }
        append("\nOpen the original source cards below for authoritative data.")
    }

    private fun clearResults() {
        hits = emptyList()
        sourceContainer.removeAllViews()
        sourceContainer.visibility = View.GONE
        txtSourcesHeading.visibility = View.GONE
        txtAiHeading.visibility = View.GONE
        aiCard.visibility = View.GONE
        aiProgress.visibility = View.GONE
        txtAiState.visibility = View.GONE
        txtAiAnswer.visibility = View.GONE
    }

    private fun renderSourcePacket(sourceHits: List<SearchHit>) {
        sourceContainer.removeAllViews()
        if (sourceHits.isEmpty()) return
        txtSourcesHeading.visibility = View.VISIBLE
        sourceContainer.visibility = View.VISIBLE
        var lastStage = ""
        sourceHits.forEach { hit ->
            val stage = stageLabel(hit)
            if (stage != lastStage) {
                sourceContainer.addView(sectionHeader(stage))
                lastStage = stage
            }
            sourceContainer.addView(sourceCard(hit))
        }
    }

    private fun stageLabel(hit: SearchHit): String {
        val prefix = hit.title.substringBefore(" • ").trim()
        return if (prefix.firstOrNull()?.isDigit() == true) prefix else hit.manual
    }

    private fun cleanTitle(title: String): String = if (title.contains(" • ") && title.substringBefore(" • ").firstOrNull()?.isDigit() == true)
        title.substringAfter(" • ").trim() else title.trim()

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#78B7E8"))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun sourceCard(hit: SearchHit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#294257")
            setCardBackgroundColor(Color.parseColor("#0E1822"))
            useCompatPadding = false
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        val badge = TextView(this).apply {
            text = hit.manual + if (hit.manual == "ESPM") " • INFO" else ""
            setTextColor(Color.parseColor(if (hit.manual == "MEL") "#FFD479" else "#7CC8FF"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        val ref = TextView(this).apply {
            text = hit.reference.ifBlank { "Reference not indexed" }
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }
        val title = TextView(this).apply {
            text = cleanTitle(hit.title).ifBlank { hit.entityType }
            setTextColor(Color.parseColor("#DDE8F1"))
            textSize = 13f
            setPadding(0, dp(3), 0, 0)
        }
        val snippet = TextView(this).apply {
            text = hit.snippet.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            setTextColor(Color.parseColor("#9FB2C3"))
            textSize = 11f
            maxLines = 5
            setPadding(0, dp(7), 0, 0)
        }
        val open = MaterialButton(this).apply {
            text = "OPEN ORIGINAL PDF PAGE"
            textSize = 10f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(43))
            lp.topMargin = dp(9)
            layoutParams = lp
            setOnClickListener { openSource(hit) }
        }
        box.addView(badge); box.addView(ref); box.addView(title); box.addView(snippet); box.addView(open)
        card.addView(box)
        card.setOnClickListener { openSource(hit) }
        return card
    }

    private fun showAiOption() {
        if (hits.isEmpty()) return
        txtAiHeading.visibility = View.VISIBLE
        aiCard.visibility = View.VISIBLE
        btnAi.isEnabled = true
        txtAiAnswer.visibility = View.VISIBLE
        txtAiAnswer.text = "Core engineering results above are already available. Local AI is optional and may be slow on this device."
    }

    private fun runOptionalAi() {
        val r = repo ?: return
        val er = evidence ?: return
        if (hits.isEmpty()) return toast("Run retrieval first")
        val model = models.installedModel() ?: return toast("No Local AI model installed")
        val generation = ++aiGenerationId
        btnAi.isEnabled = false
        aiProgress.visibility = View.VISIBLE
        aiProgress.isIndeterminate = true
        txtAiState.visibility = View.VISIBLE
        txtAiState.text = "Loading / generating locally… Core engineering summary remains usable."
        txtAiAnswer.visibility = View.VISIBLE
        txtAiAnswer.text = "Local AI processing…"
        appLog.i("AI_START", currentQuery.take(180))

        val timedOut = Runnable {
            if (generation != aiGenerationId) return@Runnable
            aiGenerationId++
            aiProgress.isIndeterminate = false
            aiProgress.progress = 100
            txtAiState.text = "LOCAL AI TIMEOUT • using deterministic engineering summary"
            txtAiAnswer.text = currentSummary + "\n\nLocal AI did not return within 60 seconds. The source-based engineering summary above remains available."
            btnAi.isEnabled = true
            appLog.i("AI_TIMEOUT", "60s")
        }
        main.postDelayed(timedOut, 60_000L)

        io.execute {
            runCatching {
                if (!llm.isLoaded()) llm.load(model)
                val pack = if (currentLineMode) er.buildLineMaintenanceEvidencePack(hits, r) else er.buildEvidencePack(hits, r)
                require(pack.isNotBlank()) { "No approved evidence pack" }
                pack
            }.onSuccess { pack ->
                val stream = StringBuilder()
                llm.answerStreaming(
                    question = currentQuery,
                    evidencePack = pack,
                    safetySummary = currentSafety,
                    onChunk = { chunk -> ui {
                        if (generation != aiGenerationId) return@ui
                        if (chunk.isNotBlank()) {
                            stream.setLength(0); stream.append(chunk)
                            txtAiAnswer.text = chunk
                            txtAiState.text = "LOCAL AI • streaming answer"
                        }
                    } },
                    onDone = { ui {
                        if (generation != aiGenerationId) return@ui
                        main.removeCallbacks(timedOut)
                        aiProgress.isIndeterminate = false; aiProgress.progress = 100
                        txtAiState.text = "LOCAL AI COMPLETE"
                        if (stream.isBlank()) txtAiAnswer.text = currentSummary + "\n\nLocal AI returned no additional text."
                        btnAi.isEnabled = true
                        appLog.i("AI_DONE", "${stream.length} chars")
                    } },
                    onError = { error -> ui {
                        if (generation != aiGenerationId) return@ui
                        main.removeCallbacks(timedOut)
                        aiProgress.isIndeterminate = false; aiProgress.progress = 100
                        txtAiState.text = "LOCAL AI FAILED • source workflow unaffected"
                        txtAiAnswer.text = currentSummary + "\n\nLocal AI error: ${error.message ?: error.javaClass.simpleName}"
                        btnAi.isEnabled = true
                        appLog.e("AI_FAIL", error)
                    } }
                )
            }.onFailure { e -> ui {
                if (generation != aiGenerationId) return@ui
                main.removeCallbacks(timedOut)
                aiProgress.isIndeterminate = false; aiProgress.progress = 100
                txtAiState.text = "LOCAL AI FAILED • source workflow unaffected"
                txtAiAnswer.text = currentSummary + "\n\nLocal AI error: ${e.message}"
                btnAi.isEnabled = true
                appLog.e("AI_PREP_FAIL", e)
            } }
        }
    }

    private fun openSource(hit: SearchHit) {
        val r = repo ?: return
        io.execute {
            val src = runCatching { r.resolve(hit) }.getOrNull()
            val uri = src?.let { pdfs.find(it.filename) }
            ui {
                when {
                    src == null -> toast("No source page mapping")
                    uri == null -> toast("PDF not indexed: ${src.filename}")
                    else -> startActivity(Intent(this, PdfPageActivity::class.java).apply {
                        putExtra("uri", uri.toString())
                        putExtra("page", src.pdfPage)
                        putExtra("title", "[${src.manual}] ${src.reference} — ${src.title}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        }
    }

    private fun choosePhoto(mode: PhotoMode) {
        photoMode = mode
        AlertDialog.Builder(this)
            .setTitle("Image source")
            .setItems(arrayOf("CAMERA", "CHOOSE FROM PHONE / GALLERY")) { _, which ->
                if (which == 0) capture() else galleryPicker.launch("image/*")
            }.show()
    }

    private fun capture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "v8_${photoMode.name.lowercase()}.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        takePhoto.launch(photoUri)
    }

    private fun ocr(uri: Uri) {
        status("OCR • READING IMAGE…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image -> recognizer.process(image)
                .addOnSuccessListener { result -> handleOcrText(result.text.trim()) }
                .addOnFailureListener { e -> appLog.e("OCR_FAIL", e); status("OCR FAILED • CHECK LOG") }
                .addOnCompleteListener { recognizer.close() }
            }
            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }

    private fun handleOcrText(text: String) {
        if (text.isBlank()) return toast("No readable text detected")
        editQuery.setText(text)
        ocrContainer.removeAllViews(); ocrContainer.visibility = View.VISIBLE
        when (photoMode) {
            PhotoMode.PFR -> {
                val result = PostFlightAnalyzer.analyze(text)
                if (result.faults.isEmpty()) retrieve(text, true, "PFR OCR") else {
                    addOcrLabel("PFR • ${result.faults.size} fault(s) detected")
                    result.faults.forEach { fault -> addOcrButton("${fault.category} ${fault.number} • ATA ${fault.ata ?: "?"}\n${fault.text}") {
                        retrieve(augment(fault.text, fault.ata, fault.identifiers), true, "PFR FAULT ${fault.number}")
                    } }
                    result.faults.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), true, "PFR FAULT ${it.number}") }
                }
            }
            PhotoMode.TECHLOG -> {
                val result = CaptainReportAnalyzer.analyze(text)
                if (result.defects.isEmpty()) retrieve(text, true, "TECH LOG OCR") else {
                    addOcrLabel("TECH LOG • ${result.defects.size} defect(s) detected")
                    result.defects.forEach { defect -> addOcrButton("DEFECT ${defect.number} • ATA ${defect.ata ?: "?"}\n${defect.text}") {
                        retrieve(augment(defect.text, defect.ata, defect.identifiers), true, "TECH LOG DEFECT ${defect.number}")
                    } }
                    result.defects.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), true, "TECH LOG DEFECT ${it.number}") }
                }
            }
            PhotoMode.COMPONENT -> {
                val result = ComponentAnalyzer.analyze(text)
                val q = result.query.ifBlank { text }
                addOcrLabel("COMPONENT OCR • ${result.identifiers.size} identifier(s)")
                retrieve(q, false, "COMPONENT")
            }
        }
    }

    private fun addOcrLabel(text: String) {
        ocrContainer.addView(TextView(this).apply {
            this.text = text; setTextColor(Color.parseColor("#78B7E8")); textSize = 11f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(3), dp(6), dp(3), dp(4))
        })
    }

    private fun addOcrButton(text: String, action: () -> Unit) {
        ocrContainer.addView(MaterialButton(this).apply {
            this.text = text; textSize = 10f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(5); layoutParams = lp
            setOnClickListener { action() }
        })
    }

    private fun augment(text: String, ata: String?, ids: List<String>) = buildString {
        append(text); if (!ata.isNullOrBlank()) append(" ATA ").append(ata); if (ids.isNotEmpty()) append(' ').append(ids.joinToString(" "))
    }

    private fun refreshLog() { txtLog.text = appLog.tail(160) }
    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("A340 Technical Assistant Log", appLog.tail(300)))
        toast("Log copied")
    }

    private fun status(text: String) { txtStatus.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        aiGenerationId++
        workflow?.close(); repo?.close(); evidence?.close(); pdfs.close(); llm.close(); io.shutdown()
        super.onDestroy()
    }
}
