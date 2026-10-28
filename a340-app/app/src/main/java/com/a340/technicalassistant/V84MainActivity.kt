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

class V84MainActivity : AppCompatActivity() {
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
    private var smartResolver: SmartTechnicalResolver? = null
    private var hits: List<SearchHit> = emptyList()
    private var currentResult: LineMaintenanceResult? = null
    private var currentQuery = ""
    private var currentSafety = ""
    private var currentSummary = ""
    private var currentLineMode = true
    private var currentPfrFault: PostFlightAnalyzer.Fault? = null
    private var photoMode = PhotoMode.PFR
    private var photoUri: Uri? = null
    private var aiGenerationId = 0
    private var resolverGenerationId = 0

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importDatabase(uri) }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importModel(uri) }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        status("INDEXING PDF LIBRARY…")
        io.execute {
            runCatching { pdfs.rebuild(this, uri) }
                .onSuccess { n -> ui { status("PDF LIBRARY READY • $n files"); appLog.i("PDF_INDEX_OK", "$n files") } }
                .onFailure { e -> ui { status("PDF INDEX FAILED • CHECK LOG"); appLog.e("PDF_INDEX_FAIL", e) } }
        }
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ocr(uri) }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let(::ocr) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startCamera() else toast("Camera permission is required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v8_main)
        bindViews()
        wireActions()
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        appLog.i("APP_START", "version $version")
        openDb()
        updateModelStatus()
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus); settingsPanel = findViewById(R.id.settingsPanel)
        txtModel = findViewById(R.id.txtModel); txtManuals = findViewById(R.id.txtManuals); txtLog = findViewById(R.id.txtLog)
        editAircraft = findViewById(R.id.editAircraft); editQuery = findViewById(R.id.editQuery); ocrContainer = findViewById(R.id.ocrContainer)
        txtSummaryHeading = findViewById(R.id.txtSummaryHeading); txtSummary = findViewById(R.id.txtEngineeringSummary)
        txtSourcesHeading = findViewById(R.id.txtSourcesHeading); sourceContainer = findViewById(R.id.sourceContainer)
        txtAiHeading = findViewById(R.id.txtAiHeading); aiCard = findViewById(R.id.aiCard); btnAi = findViewById(R.id.btnAi)
        aiProgress = findViewById(R.id.aiProgress); txtAiState = findViewById(R.id.txtAiState); txtAiAnswer = findViewById(R.id.txtAiAnswer)
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
        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener { currentPfrFault = null; retrieve(editQuery.text.toString(), true, "QUERY") }
        findViewById<MaterialButton>(R.id.btnPfr).setOnClickListener { choosePhoto(PhotoMode.PFR) }
        findViewById<MaterialButton>(R.id.btnTechLog).setOnClickListener { choosePhoto(PhotoMode.TECHLOG) }
        findViewById<MaterialButton>(R.id.btnComponent).setOnClickListener { choosePhoto(PhotoMode.COMPONENT) }
        btnAi.setOnClickListener { runOptionalAi() }
    }

    private fun openDb() {
        val file = dbm.databaseFile()
        if (!file.exists()) { status("SETUP REQUIRED • IMPORT DATABASE IN ⚙"); return }
        runCatching {
            smartResolver?.close(); workflow?.close(); repo?.close(); evidence?.close()
            repo = SearchRepository(file)
            evidence = EvidenceRepository(file)
            workflow = LineMaintenanceWorkflow(file, repo!!)
            smartResolver = SmartTechnicalResolver(file, repo!!)
            val manuals = repo!!.manuals(); val integrated = manuals.count { it.status == "INTEGRATED" }
            status("READY • DB v${repo!!.architectureVersion()} • $integrated MANUALS • OFFLINE")
            txtManuals.text = buildString { append("TECHNICAL LIBRARY • $integrated manuals"); manuals.forEach { append("\n${it.code}: ${it.pages} pages") } }
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
                .onSuccess { f -> ui { txtModel.text = "LOCAL AI INSTALLED • ${f.name} • automatic resolver + explanation"; appLog.i("MODEL_IMPORT_OK", f.name) } }
                .onFailure { e -> ui { txtModel.text = "Model import failed: ${e.message}"; appLog.e("MODEL_IMPORT_FAIL", e) } }
        }
    }

    private fun updateModelStatus() {
        val f = models.installedModel()
        txtModel.text = when {
            llm.isLoaded() -> "LOCAL AI READY • ${llm.loadedModelName()} • CPU • AUTO RESOLVER ENABLED"
            f != null -> "LOCAL AI INSTALLED • ${f.name} • AUTO RESOLVER ENABLED"
            else -> "LOCAL AI NOT INSTALLED • deterministic resolver remains available"
        }
    }

    private fun retrieve(raw: String, lineMode: Boolean, label: String, displayQuery: String? = null) {
        val q = raw.trim(); if (q.isBlank()) return toast("Enter a defect or technical request")
        val r = repo ?: return toast("Import database first")
        currentQuery = q; currentLineMode = lineMode; editQuery.setText(displayQuery ?: q)
        clearResults(); txtSummaryHeading.visibility = View.VISIBLE; txtSummary.visibility = View.VISIBLE
        txtSummary.text = "Searching approved technical sources…"; status("$label • RETRIEVING…")
        appLog.i("RETRIEVAL_START", "$label | ${q.take(180)}")
        val profile = editAircraft.text.toString().trim()

        if (lineMode) {
            val wf = workflow ?: return toast("Line-maintenance workflow unavailable")
            val sr = smartResolver ?: return toast("Technical resolver unavailable")
            io.execute {
                runCatching { sr.enhance(wf.run(q, profile), q, profile) }
                    .onSuccess { result -> ui { showLineResult(result, profile, label, true) } }
                    .onFailure { e -> ui { txtSummary.text = "RETRIEVAL ERROR\n${e.message}"; status("RETRIEVAL FAILED • CHECK LOG"); appLog.e("WORKFLOW_FAIL", e) } }
            }
        } else {
            io.execute {
                runCatching { r.searchAi(q, profile) }.onSuccess { bundle -> ui {
                    hits = bundle.hits.take(12); currentResult = null; currentSafety = bundle.safetySummary
                    currentSummary = "COMPONENT / IDENTIFIER SEARCH\n${bundle.analysis.summary()}\n\nOpen original source cards below for authoritative data."
                    txtSummary.text = currentSummary; renderSourcePacket(hits); showAiOption("Optional Local AI explanation")
                    status("$label • ${hits.size} SOURCE RESULT(S)")
                } }.onFailure { e -> ui { txtSummary.text = "SEARCH ERROR\n${e.message}"; appLog.e("SEARCH_FAIL", e) } }
            }
        }
    }

    private fun showLineResult(result: LineMaintenanceResult, profile: String, label: String, allowAutoResolver: Boolean) {
        currentResult = result; hits = result.hits
        currentSafety = result.safetySummary + "\nMEL DISPATCH STATUS: ${result.dispatchStatus.name}"
        currentSummary = buildEngineeringSummary(result, profile); txtSummary.text = currentSummary
        renderSourcePacket(hits)
        val exact = result.hits.any { it.manual.equals("TSM", true) && it.title.startsWith("2 TSM EXACT TASK") }
        if (exact) {
            showAiOption("Exact TSM verified. AI explanation available.")
            status("$label • VERIFIED EXACT TSM • ${hits.size} SOURCE(S)")
        } else {
            showAiOption("Exact TSM not deterministic. Automatic AI candidate resolver will run when a model is installed.")
            status("$label • CANDIDATE RESOLUTION REQUIRED")
            if (allowAutoResolver) runAutomaticTechnicalResolver(result, profile, label)
        }
        appLog.i("WORKFLOW_OK", "MEL=${result.melHits.size} | exactTSM=$exact | sources=${hits.size}")
    }

    private fun runAutomaticTechnicalResolver(base: LineMaintenanceResult, profile: String, label: String) {
        val sr = smartResolver ?: return
        val model = models.installedModel() ?: return
        val candidates = sr.candidateTasks(currentQuery).take(8)
        if (candidates.isEmpty()) {
            txtAiState.visibility = View.VISIBLE; txtAiState.text = "AI RESOLVER • no verified TSM candidates to rank"
            return
        }

        val generation = ++resolverGenerationId
        txtAiHeading.visibility = View.VISIBLE; aiCard.visibility = View.VISIBLE
        aiProgress.visibility = View.VISIBLE; aiProgress.isIndeterminate = true
        txtAiState.visibility = View.VISIBLE
        txtAiState.text = "AI TECHNICAL RESOLVER • ranking ${candidates.size} verified TSM candidate(s)…"
        txtAiAnswer.visibility = View.VISIBLE
        txtAiAnswer.text = "AI may select only a reference already present in the database candidate set. Selection will be database-verified before use."
        appLog.i("AI_RESOLVER_START", "${candidates.size} candidates | ${currentQuery.take(160)}")

        val timeout = Runnable {
            if (generation != resolverGenerationId) return@Runnable
            resolverGenerationId++
            aiProgress.isIndeterminate = false; aiProgress.progress = 100
            txtAiState.text = "AI RESOLVER TIMEOUT • deterministic result retained"
            appLog.i("AI_RESOLVER_TIMEOUT", "45s")
        }
        main.postDelayed(timeout, 45_000L)

        io.execute {
            runCatching { if (!llm.isLoaded()) llm.load(model) }
                .onSuccess {
                    llm.selectCandidate(
                        fault = currentQuery,
                        candidates = candidates,
                        onResult = { selected -> ui {
                            if (generation != resolverGenerationId) return@ui
                            main.removeCallbacks(timeout)
                            if (selected == null) {
                                aiProgress.isIndeterminate = false; aiProgress.progress = 100
                                txtAiState.text = "AI RESOLVER • ambiguous — no task selected"
                                txtAiAnswer.text = "No candidate was accepted. Broad TSM results remain suppressed."
                                appLog.i("AI_RESOLVER_NONE", "no confident candidate")
                                return@ui
                            }
                            val verified = sr.applyVerifiedCandidate(base, currentQuery, profile, selected)
                            if (verified == null) {
                                aiProgress.isIndeterminate = false; aiProgress.progress = 100
                                txtAiState.text = "AI SELECTION REJECTED • database verification failed"
                                appLog.i("AI_RESOLVER_REJECT", selected)
                                return@ui
                            }
                            resolverGenerationId++
                            aiProgress.isIndeterminate = false; aiProgress.progress = 100
                            txtAiState.text = "AI RESOLVER VERIFIED • $selected"
                            txtAiAnswer.text = "Selected task exists in the verified candidate set and was re-checked against task_entries."
                            appLog.i("AI_RESOLVER_OK", selected)
                            showLineResult(verified, profile, "$label • AI VERIFIED", false)
                        } },
                        onError = { e -> ui {
                            if (generation != resolverGenerationId) return@ui
                            main.removeCallbacks(timeout); aiProgress.isIndeterminate = false; aiProgress.progress = 100
                            txtAiState.text = "AI RESOLVER FAILED • deterministic result retained"
                            appLog.e("AI_RESOLVER_FAIL", e)
                        } }
                    )
                }
                .onFailure { e -> ui {
                    if (generation != resolverGenerationId) return@ui
                    main.removeCallbacks(timeout); aiProgress.isIndeterminate = false; aiProgress.progress = 100
                    txtAiState.text = "AI RESOLVER LOAD FAILED • deterministic result retained"
                    appLog.e("AI_RESOLVER_LOAD_FAIL", e)
                } }
        }
    }

    private fun buildEngineeringSummary(result: LineMaintenanceResult, profile: String): String {
        fun refs(manual: String, max: Int): String {
            val list = result.hits.filter { it.manual.equals(manual, true) }.take(max)
            return if (list.isEmpty()) "No verified source returned" else list.joinToString("\n") { "• ${it.reference.ifBlank { "Reference not indexed" }} — ${cleanTitle(it.title)}" }
        }
        val mel = when (result.dispatchStatus) {
            MelDispatchStatus.GO_EXPLICIT -> "GO wording found; verify original MEL conditions/effectivity before dispatch."
            MelDispatchStatus.NO_GO_EXPLICIT -> "NO-GO/operative requirement wording found; verify original MEL."
            MelDispatchStatus.VERIFY_REQUIRED -> "MEL candidate found; VERIFY ORIGINAL MEL ENTRY, (M)/(O), limitations and effectivity."
            MelDispatchStatus.NO_MATCH -> "No MEL item confirmed. This does NOT mean GO."
        }
        return buildString {
            currentPfrFault?.let { append("PFR STRUCTURED RECORD\n").append(it.displaySummary()).append("\n\n") }
            append("MEL / DISPATCH\n").append(mel)
            result.melHits.firstOrNull()?.let { append("\nPrimary MEL candidate: ${it.reference} — ${cleanTitle(it.title)}") }
            append("\n\nTSM / FAULT ISOLATION\n").append(refs("TSM", 2))
            append("\n\nAMM / MAINTENANCE\n").append(refs("AMM", 8))
            append("\n\nASM\n").append(refs("ASM", 4))
            append("\n\nAWM\n").append(refs("AWM", 4))
            append("\n\nIPC\n").append(refs("IPC", 4))
            if (result.includeEspm) append("\n\nESPM\n").append(refs("ESPM", 4))
            append("\n\nEFFECTIVITY\n")
            append(if (profile.isBlank()) "NOT VERIFIED — enter REG/MSN/effectivity before applying a task." else "Aircraft profile: $profile — verify applicability in each original source.")
            append("\n\n").append(result.workflowSummary.substringAfterLast("VERIFIED TECHNICAL RESOLUTION", ""))
        }
    }

    private fun clearResults() {
        resolverGenerationId++; aiGenerationId++; hits = emptyList(); currentResult = null
        sourceContainer.removeAllViews(); sourceContainer.visibility = View.GONE; txtSourcesHeading.visibility = View.GONE
        txtAiHeading.visibility = View.GONE; aiCard.visibility = View.GONE; aiProgress.visibility = View.GONE
        txtAiState.visibility = View.GONE; txtAiAnswer.visibility = View.GONE
    }

    private fun renderSourcePacket(sourceHits: List<SearchHit>) {
        sourceContainer.removeAllViews(); if (sourceHits.isEmpty()) return
        txtSourcesHeading.visibility = View.VISIBLE; sourceContainer.visibility = View.VISIBLE
        var last = ""
        sourceHits.forEach { hit ->
            val stage = stageLabel(hit)
            if (stage != last) { sourceContainer.addView(sectionHeader(stage)); last = stage }
            sourceContainer.addView(sourceCard(hit))
        }
    }

    private fun sourceCard(hit: SearchHit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat(); strokeWidth = dp(1)
            strokeColor = Color.parseColor(if (hit.title.startsWith("2 TSM EXACT")) "#38BDF8" else "#294257")
            setCardBackgroundColor(Color.parseColor("#0E1822"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)) }
        box.addView(TextView(this).apply {
            text = hit.manual + when { hit.title.startsWith("2 TSM EXACT") -> " • VERIFIED EXACT TASK"; hit.manual == "ESPM" -> " • INFO"; else -> "" }
            setTextColor(Color.parseColor(if (hit.manual == "MEL") "#FFD479" else "#7CC8FF")); textSize = 11f; setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply { text = hit.reference.ifBlank { "Reference not indexed" }; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply { text = cleanTitle(hit.title).ifBlank { hit.entityType }; setTextColor(Color.parseColor("#DDE8F1")); textSize = 13f })
        box.addView(TextView(this).apply {
            text = hit.snippet.replace("\n", " ").replace(Regex("\\s+"), " ").trim(); setTextColor(Color.parseColor("#9FB2C3")); textSize = 11f; maxLines = 7; setPadding(0, dp(7), 0, 0)
        })
        box.addView(MaterialButton(this).apply {
            text = when { hit.entityType.equals("task", true) -> "OPEN COMPLETE ORIGINAL TASK"; hit.manual == "MEL" -> "OPEN COMPLETE ORIGINAL MEL ITEM"; else -> "OPEN ORIGINAL SOURCE" }
            textSize = 10f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(43)).apply { topMargin = dp(9) }
            setOnClickListener { openSource(hit) }
        })
        card.addView(box); card.setOnClickListener { openSource(hit) }; return card
    }

    private fun stageLabel(hit: SearchHit): String {
        val p = hit.title.substringBefore(" • ").trim(); return if (p.firstOrNull()?.isDigit() == true) p else hit.manual
    }
    private fun cleanTitle(title: String): String = if (title.contains(" • ") && title.substringBefore(" • ").trim().firstOrNull()?.isDigit() == true) title.substringAfter(" • ").trim() else title.trim()
    private fun sectionHeader(text: String) = TextView(this).apply { this.text = text; setTextColor(Color.parseColor("#78B7E8")); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), dp(14), dp(4), dp(6)) }

    private fun showAiOption(message: String) {
        txtAiHeading.visibility = View.VISIBLE; aiCard.visibility = View.VISIBLE; btnAi.isEnabled = hits.isNotEmpty()
        txtAiAnswer.visibility = View.VISIBLE; txtAiAnswer.text = message
    }

    private fun runOptionalAi() {
        val r = repo ?: return; val er = evidence ?: return
        if (hits.isEmpty()) return toast("Run retrieval first")
        val model = models.installedModel() ?: return toast("No Local AI model installed")
        val generation = ++aiGenerationId
        btnAi.isEnabled = false; aiProgress.visibility = View.VISIBLE; aiProgress.isIndeterminate = true
        txtAiState.visibility = View.VISIBLE; txtAiState.text = "LOCAL AI • generating grounded engineering explanation…"
        txtAiAnswer.visibility = View.VISIBLE; txtAiAnswer.text = "Processing verified source evidence…"
        val timeout = Runnable {
            if (generation != aiGenerationId) return@Runnable
            aiGenerationId++; aiProgress.isIndeterminate = false; aiProgress.progress = 100
            txtAiState.text = "LOCAL AI TIMEOUT • verified source workflow remains available"; btnAi.isEnabled = true
        }
        main.postDelayed(timeout, 60_000L)
        io.execute {
            runCatching {
                if (!llm.isLoaded()) llm.load(model)
                if (currentLineMode) er.buildLineMaintenanceEvidencePack(hits, r) else er.buildEvidencePack(hits, r)
            }.onSuccess { pack ->
                llm.answerStreaming(currentQuery, pack, currentSafety,
                    onChunk = { chunk -> ui { if (generation == aiGenerationId && chunk.isNotBlank()) { txtAiAnswer.text = chunk; txtAiState.text = "LOCAL AI • streaming grounded answer" } } },
                    onDone = { ui { if (generation == aiGenerationId) { main.removeCallbacks(timeout); aiProgress.isIndeterminate = false; aiProgress.progress = 100; txtAiState.text = "LOCAL AI COMPLETE"; btnAi.isEnabled = true } } },
                    onError = { e -> ui { if (generation == aiGenerationId) { main.removeCallbacks(timeout); aiProgress.isIndeterminate = false; aiProgress.progress = 100; txtAiState.text = "LOCAL AI FAILED • source workflow unaffected"; txtAiAnswer.text = e.message ?: "AI error"; btnAi.isEnabled = true; appLog.e("AI_FAIL", e) } } }
                )
            }.onFailure { e -> ui { if (generation == aiGenerationId) { main.removeCallbacks(timeout); aiProgress.isIndeterminate = false; aiProgress.progress = 100; txtAiState.text = "LOCAL AI FAILED"; txtAiAnswer.text = e.message ?: "AI error"; btnAi.isEnabled = true; appLog.e("AI_PREP_FAIL", e) } } }
        }
    }

    private fun openSource(hit: SearchHit) {
        val r = repo ?: return
        io.execute {
            val src = runCatching { r.resolve(hit) }.getOrNull(); val uri = src?.let { pdfs.find(it.filename) }
            ui {
                when {
                    src == null -> toast("No source mapping")
                    uri == null -> toast("PDF not indexed: ${src.filename}")
                    else -> startActivity(Intent(this, PdfPageActivity::class.java).apply {
                        putExtra("uri", uri.toString()); putExtra("page", src.pdfPage); putExtra("title", "[${src.manual}] ${src.reference} — ${src.title}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        }
    }

    private fun choosePhoto(mode: PhotoMode) {
        photoMode = mode
        AlertDialog.Builder(this).setTitle("Image source").setItems(arrayOf("CAMERA", "CHOOSE FROM PHONE / GALLERY")) { _, which -> if (which == 0) capture() else galleryPicker.launch("image/*") }.show()
    }
    private fun capture() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA) }
    private fun startCamera() {
        val f = File(File(cacheDir, "camera").apply { mkdirs() }, "v84_${photoMode.name.lowercase()}.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f); takePhoto.launch(photoUri)
    }
    private fun ocr(uri: Uri) {
        status("OCR • READING IMAGE…"); appLog.i("OCR_START", "$photoMode | $uri")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }.onSuccess { image ->
            recognizer.process(image).addOnSuccessListener { result -> handleOcrText(result.text.trim()) }
                .addOnFailureListener { e -> appLog.e("OCR_FAIL", e); status("OCR FAILED • CHECK LOG") }
                .addOnCompleteListener { recognizer.close() }
        }.onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }

    private fun handleOcrText(text: String) {
        if (text.isBlank()) return toast("No readable text detected")
        ocrContainer.removeAllViews(); ocrContainer.visibility = View.VISIBLE
        when (photoMode) {
            PhotoMode.PFR -> {
                val result = PostFlightAnalyzer.analyze(text); currentPfrFault = null
                if (result.faults.isEmpty()) {
                    addOcrLabel("PFR • structured fault not confidently detected"); addOcrInfo("Raw OCR retained only as fallback; exact troubleshooting will not be claimed without a structured fault.")
                    retrieve(text, true, "PFR OCR FALLBACK")
                } else {
                    addOcrLabel("PFR STRUCTURED RECORD • ${result.faults.size} maintenance fault(s)")
                    result.faults.forEach { fault ->
                        addOcrInfo(fault.displaySummary())
                        addOcrButton("RUN MEL → VERIFIED TSM → REFERENCED SOURCES") { currentPfrFault = fault; retrieve(fault.searchQuery(), true, "PFR FAULT ${fault.number}", fault.text) }
                    }
                    currentPfrFault = result.faults.first(); retrieve(result.faults.first().searchQuery(), true, "PFR FAULT ${result.faults.first().number}", result.faults.first().text)
                }
            }
            PhotoMode.TECHLOG -> {
                currentPfrFault = null; val result = CaptainReportAnalyzer.analyze(text)
                if (result.defects.isEmpty()) retrieve(text, true, "TECH LOG OCR") else {
                    addOcrLabel("TECH LOG • ${result.defects.size} defect(s) detected")
                    result.defects.forEach { d -> addOcrButton("DEFECT ${d.number} • ATA ${d.ata ?: "?"}\n${d.text}") { retrieve(augment(d.text, d.ata, d.identifiers), true, "TECH LOG DEFECT ${d.number}", d.text) } }
                    result.defects.firstOrNull()?.let { retrieve(augment(it.text, it.ata, it.identifiers), true, "TECH LOG DEFECT ${it.number}", it.text) }
                }
            }
            PhotoMode.COMPONENT -> { currentPfrFault = null; val r = ComponentAnalyzer.analyze(text); val q = r.query.ifBlank { text }; addOcrLabel("COMPONENT OCR • ${r.identifiers.size} identifier(s)"); retrieve(q, false, "COMPONENT", q) }
        }
    }

    private fun addOcrLabel(text: String) { ocrContainer.addView(TextView(this).apply { this.text = text; setTextColor(Color.parseColor("#78B7E8")); textSize = 11f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(3), dp(8), dp(3), dp(5)) }) }
    private fun addOcrInfo(text: String) {
        val card = MaterialCardView(this).apply { radius = dp(10).toFloat(); strokeWidth = dp(1); strokeColor = Color.parseColor("#294257"); setCardBackgroundColor(Color.parseColor("#0E1822")); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } }
        card.addView(TextView(this).apply { this.text = text; setTextColor(Color.parseColor("#E3ECF3")); textSize = 12f; setLineSpacing(0f, 1.15f); setPadding(dp(12), dp(10), dp(12), dp(10)) }); ocrContainer.addView(card)
    }
    private fun addOcrButton(text: String, action: () -> Unit) { ocrContainer.addView(MaterialButton(this).apply { this.text = text; textSize = 10f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }; setOnClickListener { action() } }) }
    private fun augment(text: String, ata: String?, ids: List<String>) = buildString { append(text); if (!ata.isNullOrBlank()) append(" ATA ").append(ata); if (ids.isNotEmpty()) append(' ').append(ids.joinToString(" ")) }

    private fun refreshLog() { txtLog.text = appLog.tail(180) }
    private fun copyLog() { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("A340 Technical Assistant Log", appLog.tail(350))); toast("Log copied") }
    private fun status(text: String) { txtStatus.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        aiGenerationId++; resolverGenerationId++
        smartResolver?.close(); workflow?.close(); repo?.close(); evidence?.close(); pdfs.close(); llm.close(); io.shutdown()
        super.onDestroy()
    }
}
