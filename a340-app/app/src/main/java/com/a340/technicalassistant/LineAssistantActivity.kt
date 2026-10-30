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
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

class LineAssistantActivity : AppCompatActivity() {
    private enum class PhotoMode { PFR, TECHLOG, COMPONENT }
    private enum class ManualEntryMode { FULL, ECAM, COCKPIT, CMS }

    private data class PfrEditorFields(
        val ecamMessage: EditText,
        val cockpitAta: EditText,
        val cockpitType: EditText,
        val cockpitStatus: EditText,
        val cmsCh: EditText,
        val cmsSe: EditText,
        val cmsSu: EditText,
        val source: EditText,
        val cmsMessage: EditText,
        val faultClass: EditText,
        val persistence: EditText,
        val identifiers: EditText
    )

    private lateinit var txtStatus: TextView
    private lateinit var settingsPanel: View
    private lateinit var txtModel: TextView
    private lateinit var txtManuals: TextView
    private lateinit var txtLog: TextView
    private lateinit var editAircraft: EditText
    private lateinit var editQuery: EditText
    private lateinit var ocrContainer: LinearLayout
    private lateinit var txtResultHeading: TextView
    private lateinit var txtResult: TextView
    private lateinit var txtSourcesHeading: TextView
    private lateinit var sourceContainer: LinearLayout
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
    private var engine: LineAssistantEngine? = null
    private var currentResult: LineAssistantResult? = null
    private var currentHits: List<SearchHit> = emptyList()
    private var currentQuestion: String = ""
    private var aiGeneration = 0
    private var photoMode = PhotoMode.PFR
    private var photoUri: Uri? = null

    private val dbPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDatabase(uri)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        status("INDEXING ORIGINAL PDF LIBRARY…")
        io.execute {
            runCatching { pdfs.rebuild(this, uri) }
                .onSuccess { count -> ui { status("PDF LIBRARY READY • $count files"); appLog.i("PDF_INDEX_OK", "$count files") } }
                .onFailure { e -> ui { status("PDF INDEX FAILED • CHECK LOG"); appLog.e("PDF_INDEX_FAIL", e) } }
        }
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) startCrop(uri)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::startCrop)
    }
    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val cropped = result.uriContent
            if (cropped != null) {
                status("CROP CONFIRMED • OCR READING SELECTED AREA…")
                ocr(cropped)
            } else {
                toast("Crop returned no image")
            }
        } else {
            val error = result.error
            if (error != null) {
                appLog.e("IMAGE_CROP_FAIL", error)
                status("CROP FAILED • ORIGINAL IMAGE NOT SENT TO OCR")
            } else {
                status("CROP CANCELLED • OCR NOT STARTED")
            }
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_assistant)
        bindViews()
        wireActions()
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        appLog.i("APP_START", "Line Assistant $version")
        openDatabase()
        updateModelStatus()
        showUnifiedFirstEntryPoint()
        window.statusBarColor = Color.parseColor("#F2F2F7")
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR }
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
        txtResultHeading = findViewById(R.id.txtResultHeading)
        txtResult = findViewById(R.id.txtResult)
        txtSourcesHeading = findViewById(R.id.txtSourcesHeading)
        sourceContainer = findViewById(R.id.sourceContainer)
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
        findViewById<MaterialButton>(R.id.btnRun).setOnClickListener { resolveTypedQuery() }
        findViewById<MaterialButton>(R.id.btnPfr).setOnClickListener { choosePhoto(PhotoMode.PFR) }
        findViewById<MaterialButton>(R.id.btnPfrManual).setOnClickListener { showManualPfrEntry() }
        findViewById<MaterialButton>(R.id.btnManualEcam).setOnClickListener { showManualPfrEntry(ManualEntryMode.ECAM) }
        findViewById<MaterialButton>(R.id.btnManualStatus).setOnClickListener { showManualPfrEntry(ManualEntryMode.COCKPIT) }
        findViewById<MaterialButton>(R.id.btnManualCms).setOnClickListener { showManualPfrEntry(ManualEntryMode.CMS) }
        findViewById<MaterialButton>(R.id.btnTechLog).setOnClickListener { choosePhoto(PhotoMode.TECHLOG) }
        findViewById<MaterialButton>(R.id.btnComponent).setOnClickListener { choosePhoto(PhotoMode.COMPONENT) }
        btnAi.setOnClickListener { runGroundedAi() }
    }

    private fun openDatabase() {
        val file = dbm.databaseFile()
        if (!file.exists()) {
            status("SETUP REQUIRED • IMPORT A340 TECHNICAL MASTER DATABASE")
            return
        }
        runCatching {
            engine?.close(); repo?.close()
            repo = SearchRepository(file)
            engine = LineAssistantEngine(file, repo!!)
            val manuals = repo!!.manuals()
            val integrated = manuals.count { it.status == "INTEGRATED" }
            status("READY • LINE ASSISTANT • DB v${repo!!.architectureVersion()} • $integrated MANUALS • OFFLINE")
            txtManuals.text = buildString {
                append("TECHNICAL MASTER • $integrated manuals")
                manuals.forEach { append("\n${it.code}: ${it.pages} pages") }
            }
            appLog.i("DATABASE_OPEN_OK", "${file.length() / 1048576} MB | $integrated manuals")
        }.onFailure { e ->
            status("DATABASE OPEN FAILED • CHECK LOG")
            appLog.e("DATABASE_OPEN_FAIL", e)
        }
    }

    private fun importDatabase(uri: Uri) {
        status("IMPORTING TECHNICAL MASTER…")
        io.execute {
            runCatching { dbm.importDatabase(uri) { bytes -> ui { status("IMPORTING DATABASE • ${bytes / 1048576} MB") } } }
                .onSuccess { ui { appLog.i("DATABASE_IMPORT_OK", "${it.length() / 1048576} MB"); openDatabase() } }
                .onFailure { e -> ui { status("DATABASE IMPORT FAILED • CHECK LOG"); appLog.e("DATABASE_IMPORT_FAIL", e) } }
        }
    }

    private fun importModel(uri: Uri) {
        txtModel.text = "Importing local AI model…"
        io.execute {
            runCatching { models.importModel(uri) { bytes -> ui { txtModel.text = "Importing model • ${bytes / 1048576} MB" } } }
                .onSuccess { f -> ui { txtModel.text = "LOCAL AI INSTALLED • ${f.name} • VERIFIED-EVIDENCE EXPLANATION"; appLog.i("MODEL_IMPORT_OK", f.name) } }
                .onFailure { e -> ui { txtModel.text = "Model import failed: ${e.message}"; appLog.e("MODEL_IMPORT_FAIL", e) } }
        }
    }

    private fun updateModelStatus() {
        val f = models.installedModel()
        txtModel.text = when {
            llm.isLoaded() -> "LOCAL AI READY • ${llm.loadedModelName()} • CPU"
            f != null -> "LOCAL AI INSTALLED • ${f.name}"
            else -> "LOCAL AI NOT INSTALLED • MEL/TSM resolver works without AI"
        }
    }

    private fun resolveTypedQuery() {
        val q = editQuery.text.toString().trim()
        if (q.isBlank()) return toast("Enter a maintenance message, ECAM alert or ATA fault")
        resolveText(q, "MANUAL DEFECT")
    }

    private fun resolveText(text: String, label: String) {
        val e = engine ?: return toast("Import database first")
        val profile = editAircraft.text.toString().trim()
        currentQuestion = text
        clearResults()
        txtResultHeading.visibility = View.VISIBLE
        txtResult.visibility = View.VISIBLE
        txtResult.text = "Resolving MEL entry and TSM Warnings/Malfunction index…"
        status("$label • RESOLVING MANUAL INDEXES…")
        appLog.i("LINE_RESOLVE_START", "$label | ${text.take(180)}")
        io.execute {
            runCatching { e.resolveText(text, profile) }
                .onSuccess { result -> ui { showResult(result, label) } }
                .onFailure { ex -> ui {
                    txtResult.text = "RESOLUTION ERROR\n${ex.message}"
                    status("RESOLUTION FAILED • CHECK LOG")
                    appLog.e("LINE_RESOLVE_FAIL", ex)
                } }
        }
    }

    private fun resolvePfrFault(fault: PostFlightAnalyzer.Fault, label: String) {
        val e = engine ?: return toast("Import database first")
        val profile = editAircraft.text.toString().trim()
        currentQuestion = fault.displaySummary()
        editQuery.setText(fault.text)
        clearResults(keepOcr = true)
        txtResultHeading.visibility = View.VISIBLE
        txtResult.visibility = View.VISIBLE
        txtResult.text = "Resolving exact MEL ECAM block and TSM index record…"
        status("$label • RESOLVING…")
        io.execute {
            runCatching { e.resolvePfr(fault, profile) }
                .onSuccess { result -> ui { showResult(result, label) } }
                .onFailure { ex -> ui { txtResult.text = "RESOLUTION ERROR\n${ex.message}"; status("RESOLUTION FAILED"); appLog.e("PFR_RESOLVE_FAIL", ex) } }
        }
    }

    private fun showResult(result: LineAssistantResult, label: String) {
        currentResult = result
        currentHits = result.hits
        txtResultHeading.visibility = View.VISIBLE
        txtResult.visibility = View.VISIBLE
        txtResult.text = result.summary
        renderSourcePacket(result.hits)
        if (result.tsmCandidates.isNotEmpty()) renderTsmCandidateChooser(result, label)
        aiCard.visibility = View.VISIBLE
        val verifiedEvidence = result.tsmCandidates.isEmpty() && (result.tsm != null ||
            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank()))
        btnAi.isEnabled = verifiedEvidence && models.installedModel() != null
        txtAiState.visibility = View.VISIBLE
        txtAiState.text = when {
            result.tsmCandidates.isNotEmpty() -> "LOCAL AI LOCKED • enter the actual verified CMS fault first"
            !verifiedEvidence -> "LOCAL AI LOCKED • no verified MEL/TSM evidence resolved"
            models.installedModel() == null -> "LOCAL AI NOT INSTALLED • deterministic result remains available"
            else -> "AI READY • verified MEL/TSM evidence packet prepared"
        }
        txtAiAnswer.visibility = View.GONE
        val melStatus = result.mel.status.name
        val tsm = when {
            result.tsm != null -> result.tsm.taskRef
            result.tsmCandidates.isNotEmpty() -> "CMS INPUT / VERIFICATION REQUIRED (${result.tsmCandidates.size})"
            else -> "NOT RESOLVED"
        }
        status("$label • MEL $melStatus • TSM $tsm")
        appLog.i("LINE_RESOLVE_OK", "MEL=$melStatus | TSM=$tsm | sources=${result.hits.size}")
    }

    private fun renderTsmCandidateChooser(result: LineAssistantResult, label: String) {
        val base = result.structuredFault
        txtSourcesHeading.visibility = View.VISIBLE
        txtSourcesHeading.text = when (result.tsmState) {
            LineTsmState.NEEDS_CMS -> "TSM CANDIDATES • ACTUAL CMS REQUIRED"
            LineTsmState.AMBIGUOUS -> "TSM CANDIDATES • ADDITIONAL VERIFICATION REQUIRED"
            else -> "TSM CANDIDATES"
        }
        sourceContainer.visibility = View.VISIBLE
        sourceContainer.addView(sectionHeader("TSM INDEX • POSSIBLE RECORDS — NOT SELECTED"), 0)

        result.tsmCandidates.asReversed().forEach { candidate ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#C7D7E5")
                setCardBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
            }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
            box.addView(TextView(this).apply {
                text = candidate.maintenanceMessage.ifBlank { candidate.cockpitMessage }
                setTextColor(Color.parseColor("#111827")); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = buildString {
                    if (candidate.maintenanceAta.isNotBlank()) append("ATA ").append(candidate.maintenanceAta)
                    if (candidate.source.isNotBlank()) append("  •  Source ").append(candidate.source)
                    if (candidate.faultClass.isNotBlank()) append("  •  Class ").append(candidate.faultClass)
                    if (candidate.applicability.isNotBlank()) append("\nApplies: ").append(candidate.applicability)
                    append("\nTSM ").append(candidate.taskRef)
                    append("\nIndex candidate only — not copied into aircraft CMS input.")
                }
                setTextColor(Color.parseColor("#667085")); textSize = 11f; setPadding(0, dp(5), 0, dp(8))
            })
            card.addView(box)
            sourceContainer.addView(card, 1)
        }

        if (base != null && result.tsmState == LineTsmState.NEEDS_CMS) {
            sourceContainer.addView(MaterialButton(this).apply {
                text = "ENTER ACTUAL CMS FROM AIRCRAFT / PFR"
                textSize = 11f
                setOnClickListener {
                    clearResults()
                    ocrContainer.removeAllViews()
                    ocrContainer.visibility = View.VISIBLE
                    addOcrLabel("VERIFY ACTUAL CMS — DO NOT CHOOSE A CANDIDATE")
                    addOcrInfo("Cockpit/STS entry is retained below. Enter only the CMS fault actually printed/observed on the aircraft.")
                    renderPfrEditor(base, "", base.number, ManualEntryMode.FULL)
                    status("$label • WAITING FOR VERIFIED CMS INPUT")
                }
            })
        }
    }

    private fun clearResults(keepOcr: Boolean = false) {
        aiGeneration++
        currentResult = null
        currentHits = emptyList()
        txtResultHeading.visibility = View.GONE
        txtResult.visibility = View.GONE
        sourceContainer.removeAllViews()
        sourceContainer.visibility = View.GONE
        txtSourcesHeading.visibility = View.GONE
        txtSourcesHeading.text = "VERIFIED ORIGINAL SOURCES"
        aiCard.visibility = View.GONE
        aiProgress.visibility = View.GONE
        txtAiAnswer.visibility = View.GONE
        if (!keepOcr) {
            ocrContainer.removeAllViews()
            ocrContainer.visibility = View.GONE
        }
    }

    private fun renderSourcePacket(hits: List<SearchHit>) {
        sourceContainer.removeAllViews()
        txtSourcesHeading.text = "VERIFIED ORIGINAL SOURCES"
        if (hits.isEmpty()) return
        txtSourcesHeading.visibility = View.VISIBLE
        sourceContainer.visibility = View.VISIBLE
        var lastStage = ""
        hits.forEach { hit ->
            val stage = stageLabel(hit)
            if (stage != lastStage) {
                sourceContainer.addView(sectionHeader(stage))
                lastStage = stage
            }
            sourceContainer.addView(sourceCard(hit))
        }
    }

    private fun stageLabel(hit: SearchHit): String = when {
        hit.manual == "MEL" && hit.entityType == "mel_entry" -> "1 • MEL EXACT ENTRY"
        hit.manual == "MEL" && hit.entityType == "mel_item" -> "1B • MEL REFERENCED ITEM"
        hit.manual == "MEL" && hit.entityType == "mel_procedure" -> "1C • MEL OPERATIONAL PROCEDURE"
        hit.manual == "TSM" && hit.entityType == "task" -> "2 • TSM EXACT TASK"
        hit.manual == "AMM" -> "3 • AMM REFERENCED TASK"
        hit.manual == "ASM" -> "4 • ASM REFERENCED SCHEMATIC"
        hit.manual == "AWM" -> "5 • AWM EXACT IDENTIFIER / DIAGRAM"
        hit.manual == "IPC" -> "6 • IPC PART / FIN"
        hit.manual == "ESPM" -> "7 • ESPM INFO ONLY"
        else -> hit.manual
    }

    private fun sourceCard(hit: SearchHit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor(if (hit.manual == "MEL") "#FFD60A" else if (hit.manual == "TSM") "#0A84FF" else "#D1D1D6")
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)) }
        box.addView(TextView(this).apply {
            text = hit.manual + if (hit.manual == "ESPM") " • INFORMATION ONLY" else ""
            setTextColor(Color.parseColor(if (hit.manual == "MEL") "#FFD479" else "#7CC8FF"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = hit.reference.ifBlank { "Reference not indexed" }
            setTextColor(Color.parseColor("#1C1C1E"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = hit.title.ifBlank { hit.entityType }
            setTextColor(Color.parseColor("#1C1C1E"))
            textSize = 13f
        })
        box.addView(TextView(this).apply {
            text = hit.snippet.replace(Regex("\\s+"), " ").trim()
            setTextColor(Color.parseColor("#6E6E73"))
            textSize = 11f
            maxLines = 7
            setPadding(0, dp(7), 0, 0)
        })
        box.addView(MaterialButton(this).apply {
            text = when {
                hit.entityType == "task" -> "OPEN COMPLETE ORIGINAL TASK"
                hit.manual == "MEL" -> "OPEN ORIGINAL MEL SOURCE"
                else -> "OPEN ORIGINAL SOURCE"
            }
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(43)).apply { topMargin = dp(9) }
            setOnClickListener { openSource(hit) }
        })
        card.addView(box)
        card.setOnClickListener { openSource(hit) }
        return card
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#0A84FF"))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun openSource(hit: SearchHit) {
        val r = repo ?: return
        io.execute {
            val src = runCatching { r.resolve(hit) }.getOrNull()
            val uri = src?.let { pdfs.find(it.filename) }
            ui {
                when {
                    src == null -> toast("No source mapping in database")
                    uri == null -> toast("Original PDF not indexed: ${src.filename}")
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

    private fun runGroundedAi() {
        val result = currentResult ?: return toast("Resolve a defect first")
        val verifiedEvidence = result.tsm != null ||
            (result.mel.status != LineDispatchStatus.NO_ENTRY && result.mel.exactBlock.isNotBlank())
        if (!verifiedEvidence) return toast("No verified MEL/TSM evidence to explain")
        val model = models.installedModel() ?: return toast("No local AI model installed")
        val generation = ++aiGeneration
        btnAi.isEnabled = false
        aiProgress.visibility = View.VISIBLE
        aiProgress.isIndeterminate = true
        txtAiState.text = "LOCAL AI • explaining verified MEL + TSM evidence…"
        txtAiAnswer.visibility = View.VISIBLE
        txtAiAnswer.text = "Processing exact source packet…"
        val timeout = Runnable {
            if (generation != aiGeneration) return@Runnable
            aiGeneration++
            aiProgress.isIndeterminate = false
            aiProgress.progress = 100
            txtAiState.text = "LOCAL AI TIMEOUT • deterministic MEL/TSM result is unchanged"
            btnAi.isEnabled = true
            appLog.i("AI_TIMEOUT", "75s")
        }
        main.postDelayed(timeout, 75_000L)

        io.execute {
            runCatching { if (!llm.isLoaded()) llm.load(model) }
                .onSuccess {
                    llm.answerStreaming(
                        question = currentQuestion,
                        evidencePack = result.evidencePack,
                        safetySummary = result.safetySummary,
                        onChunk = { chunk -> ui {
                            if (generation == aiGeneration && chunk.isNotBlank()) {
                                txtAiAnswer.text = chunk
                                txtAiState.text = "LOCAL AI • grounded explanation"
                            }
                        } },
                        onDone = { ui {
                            if (generation == aiGeneration) {
                                main.removeCallbacks(timeout)
                                aiProgress.isIndeterminate = false
                                aiProgress.progress = 100
                                txtAiState.text = "LOCAL AI COMPLETE • verified evidence preserved"
                                btnAi.isEnabled = true
                            }
                        } },
                        onError = { e -> ui {
                            if (generation == aiGeneration) {
                                main.removeCallbacks(timeout)
                                aiProgress.isIndeterminate = false
                                aiProgress.progress = 100
                                txtAiState.text = "LOCAL AI FAILED • deterministic result unaffected"
                                txtAiAnswer.text = e.message ?: "AI error"
                                btnAi.isEnabled = true
                                appLog.e("AI_FAIL", e)
                            }
                        } }
                    )
                }
                .onFailure { e -> ui {
                    if (generation == aiGeneration) {
                        main.removeCallbacks(timeout)
                        aiProgress.isIndeterminate = false
                        aiProgress.progress = 100
                        txtAiState.text = "LOCAL AI LOAD FAILED • deterministic result unaffected"
                        txtAiAnswer.text = e.message ?: "AI load error"
                        btnAi.isEnabled = true
                        appLog.e("AI_LOAD_FAIL", e)
                    }
                } }
        }
    }

    private fun choosePhoto(mode: PhotoMode) {
        photoMode = mode
        AlertDialog.Builder(this)
            .setTitle("Image source")
            .setItems(arrayOf("CAMERA", "CHOOSE FROM PHONE / GALLERY")) { _, which ->
                if (which == 0) capture() else galleryPicker.launch("image/*")
            }
            .show()
    }

    private fun capture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val f = File(File(cacheDir, "camera").apply { mkdirs() }, "line_assistant_${photoMode.name.lowercase()}.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        photoUri?.let { takePhoto.launch(it) }
    }

    private fun startCrop(uri: Uri) {
        status("CROP IMAGE • SELECT THE PFR ROW / AREA TO READ")
        cropImage.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    cropShape = CropImageView.CropShape.RECTANGLE,
                    fixAspectRatio = false,
                    multiTouchEnabled = true,
                    autoZoomEnabled = true,
                    initialCropWindowPaddingRatio = 0.02f,
                    activityTitle = if (photoMode == PhotoMode.PFR) "Crop PFR — tap USE" else "Crop image — tap USE",
                    cropMenuCropButtonTitle = "USE",
                    activityMenuIconColor = Color.parseColor("#0A84FF"),
                    outputCompressQuality = 100
                )
            )
        )
    }

    private fun ocr(uri: Uri) {
        if (photoMode == PhotoMode.PFR) {
            clearResults()
            ocrContainer.removeAllViews()
            ocrContainer.visibility = View.VISIBLE
            addOcrLabel("PFR OCR • READING CONFIRMED CROP")
            addOcrInfo("Previous manual entry fields were cleared. Only values detected from this cropped PFR image will be populated.")
        }
        status("OCR • READING IMAGE…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (photoMode == PhotoMode.PFR) handlePfrOcr(result)
                        else handleOcrText(result.text.trim())
                    }
                    .addOnFailureListener { e -> status("OCR FAILED • CHECK LOG"); appLog.e("OCR_FAIL", e) }
                    .addOnCompleteListener { recognizer.close() }
            }
            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }

    private fun handlePfrOcr(result: Text) {
        val text = result.text.trim()
        if (text.isBlank()) return toast("No readable text detected")
        clearResults()
        editQuery.setText("")
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE

        val parsed = PfrGeometryAnalyzerV2.analyze(result)
        addOcrLabel("FIRST ENTRY POINT • OCR VALUES ARE EDITABLE")
        addOcrInfo("OCR separates ECAM / Cockpit Status / CMS fields. Empty sections stay empty. Review or correct the fields before troubleshooting.")

        if (parsed.faults.isEmpty()) {
            addOcrLabel("OCR DID NOT BUILD A COMPLETE EVENT • MANUAL CORRECTION AVAILABLE")
            addOcrInfo(text.take(1400))
            renderPfrEditor(null, text, 1)
            status("PFR OCR • REVIEW / COMPLETE FIELDS MANUALLY")
        } else {
            parsed.faults.forEachIndexed { index, fault ->
                renderPfrEditor(fault, text, index + 1)
            }
            addOcrButton("+ ADD MANUAL PFR EVENT") {
                renderPfrEditor(null, text, ocrContainer.childCount + 1)
            }
            status("PFR OCR • ${parsed.faults.size} EVENT(S) • REVIEW FIELDS BEFORE RESOLVE")
        }
    }

    private fun showUnifiedFirstEntryPoint() {
        photoMode = PhotoMode.PFR
        clearResults()
        editQuery.setText("")
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE
        addOcrLabel("FIRST ENTRY POINT")
        addOcrInfo("Enter ECAM, Maintenance/Cockpit Status, CMS fault data, or any valid combination. PFR OCR fills these same fields; review/correct them before troubleshooting.")
        renderPfrEditor(null, "", 1, ManualEntryMode.FULL)
    }

    private fun showManualPfrEntry() = showManualPfrEntry(ManualEntryMode.FULL)

    private fun showManualPfrEntry(mode: ManualEntryMode) {
        photoMode = PhotoMode.PFR
        clearResults()
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE
        val title = when (mode) {
            ManualEntryMode.FULL -> "FULL PFR MANUAL ENTRY"
            ManualEntryMode.ECAM -> "ECAM WARNING • MANUAL ENTRY"
            ManualEntryMode.COCKPIT -> "COCKPIT EFFECT / STATUS • MANUAL ENTRY"
            ManualEntryMode.CMS -> "CMS FAULT MESSAGE • MANUAL ENTRY"
        }
        addOcrLabel(title)
        addOcrInfo("Enter only what is actually printed/known. Empty sections stay empty. You can edit every field before troubleshooting.")
        renderPfrEditor(null, "", 1, mode)
        status("$title • READY")
    }

    private fun renderPfrEditor(seed: PostFlightAnalyzer.Fault?, rawOcr: String, eventNumber: Int, mode: ManualEntryMode = ManualEntryMode.FULL) {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#D1D1D6")
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10); bottomMargin = dp(4) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val entryHeading = when (mode) {
            ManualEntryMode.FULL -> "ECAM • MAINTENANCE • CMS"
            ManualEntryMode.ECAM -> "ECAM WARNING SEARCH"
            ManualEntryMode.COCKPIT -> "COCKPIT EFFECT / STATUS SEARCH"
            ManualEntryMode.CMS -> "CMS FAULT MESSAGE SEARCH"
        }
        root.addView(TextView(this).apply {
            text = entryHeading
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Use any section independently or together. PFR OCR fills only what it detects; you can correct every field before Search."
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 9f
            setPadding(0, dp(3), 0, dp(6))
        })

        val printedType = seed?.cockpitType.orEmpty()
        val exactDbType = if (printedType.isBlank() && !seed?.cockpitEffect.isNullOrBlank()) {
            engine?.classifyCockpitEntryExact(seed?.cockpitEffect, seed?.cockpitAta)
        } else null
        val seedType = printedType.ifBlank { exactDbType.orEmpty() }
        val ecamSeed = if (isEcamCockpitType(seedType)) seed?.cockpitEffect.orEmpty() else ""
        val statusSeed = if (ecamSeed.isBlank()) seed?.cockpitEffect.orEmpty() else ""

        // Airbus first-entry-point: ECAM warning is independent from CMS.
        val ecam = pfrSection(root, "ECAM WARNING SEARCH")
        ecam.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.ECAM) View.VISIBLE else View.GONE
        val ecamMessage = pfrField(ecam, "MESSAGE", ecamSeed, "Leave blank when no ECAM warning is present", multiline = true)

        // Some PFR rows are Maintenance Status / flags / observations rather than ECAM warnings.
        val cockpit = pfrSection(root, "MAINTENANCE / COCKPIT STATUS")
        cockpit.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.COCKPIT) View.VISIBLE else View.GONE
        val cockpitAta = pfrField(cockpit, "ATA REF", seed?.cockpitAta.orEmpty(), "e.g. 27-90; blank if not printed")
        val cockpitType = pfrField(cockpit, "TYPE", seedType, "e.g. MAINTENANCE STATUS")
        val cockpitStatus = pfrField(cockpit, "MESSAGE / STATUS", statusSeed, "e.g. EFCS 1; blank when ECAM field above is used", multiline = true)

        // CMS Fault Message search mirrors Airbus CH / SE / SU entry fields.
        val cms = pfrSection(root, "CMS FAULT MESSAGE SEARCH")
        cms.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.CMS) View.VISIBLE else View.GONE
        val ataDigits = (seed?.ataFull ?: "").filter(Char::isDigit)
        val ataRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            cms.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val cmsCh = pfrCompactField(ataRow, "CH", if (ataDigits.length == 6) ataDigits.substring(0, 2) else "", "--")
        val cmsSe = pfrCompactField(ataRow, "SE", if (ataDigits.length == 6) ataDigits.substring(2, 4) else "", "--")
        val cmsSu = pfrCompactField(ataRow, "SU", if (ataDigits.length == 6) ataDigits.substring(4, 6) else "", "--")

        val source = pfrField(cms, "SOURCE", if (seed?.category == "CMS FAULT MESSAGE") seed.source.orEmpty() else "", "Leave blank if none")
        val cmsMessage = pfrField(
            cms,
            "MESSAGE",
            if (seed?.category == "CMS FAULT MESSAGE") seed.text else "",
            "Leave blank when no CMS fault message is present",
            multiline = true
        )
        val faultClass = pfrField(cms, "CLASS", if (seed?.category == "CMS FAULT MESSAGE") seed.faultClass.orEmpty() else "", "1 / 2 / 3")
        val persistence = pfrField(cms, "STATUS (OPTIONAL)", if (seed?.category == "CMS FAULT MESSAGE") seed.persistence.orEmpty() else "", "HARD / INTERMITTENT")
        val identifiers = pfrField(
            cms,
            "IDENTIFIERS (OPTIONAL)",
            if (seed?.category == "CMS FAULT MESSAGE") seed.identifiers.joinToString(", ") else "",
            "e.g. 39GA1, 39GA2",
            multiline = true
        )

        val fields = PfrEditorFields(
            ecamMessage, cockpitAta, cockpitType, cockpitStatus,
            cmsCh, cmsSe, cmsSu, source, cmsMessage, faultClass, persistence, identifiers
        )

        root.addView(MaterialButton(this).apply {
            text = "SEARCH / TROUBLESHOOT"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0A84FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                val fault = buildFaultFromEditor(fields, eventNumber) ?: return@setOnClickListener
                resolvePfrFault(fault, "PFR EVENT $eventNumber • USER VERIFIED")
            }
        })
        root.addView(TextView(this).apply {
            text = "One-page search: ECAM • Maintenance/Cockpit Status • CMS. PFR photos are cropped before OCR. UTC/flight phase are not troubleshooting search inputs."
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 9f
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(root)
        ocrContainer.addView(card)
    }

    private fun pfrSection(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(8))
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
    }

    private fun pfrField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        multiline: Boolean = false
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#6E6E73"))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        })
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            isSingleLine = !multiline
            if (multiline) minLines = 2
            setPadding(dp(5), dp(3), dp(5), dp(5))
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun pfrCompactField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String
    ): EditText {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(3), 0)
            parent.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#6E6E73"))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
        })
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            isSingleLine = true
            setPadding(dp(5), dp(3), dp(5), dp(5))
            column.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun buildFaultFromEditor(fields: PfrEditorFields, eventNumber: Int): PostFlightAnalyzer.Fault? {
        val ecam = fields.ecamMessage.text.toString().trim()
        val cockpitStatus = fields.cockpitStatus.text.toString().trim()
        val enteredType = fields.cockpitType.text.toString().trim().uppercase()

        // One PFR row has one cockpit effect. Do not silently merge two user-entered effects.
        if (ecam.isNotBlank() && cockpitStatus.isNotBlank()) {
            toast("This event has both ECAM and Cockpit Status filled. Keep only the cockpit effect actually printed on this PFR row.")
            return null
        }

        val cockpitAtaRaw = fields.cockpitAta.text.toString().trim()
        val cockpitAta = parsePfrAta(cockpitAtaRaw, 4)
        if (cockpitAtaRaw.isNotBlank() && cockpitAta == null) {
            toast("Cockpit ATA must contain 4 digits, for example 27-90")
            return null
        }

        val ch = fields.cmsCh.text.toString().filter(Char::isDigit)
        val se = fields.cmsSe.text.toString().filter(Char::isDigit)
        val su = fields.cmsSu.text.toString().filter(Char::isDigit)
        val ataParts = listOf(ch, se, su)
        val anyAta = ataParts.any { it.isNotBlank() }
        val fullAta = ataParts.all { it.length == 2 }
        if (anyAta && !fullAta) {
            toast("CMS ATA: enter all CH / SE / SU as two digits each, or leave all three blank")
            return null
        }
        val maintenanceAta = if (fullAta) "$ch-$se-$su" else null

        val cmsMessage = fields.cmsMessage.text.toString().trim()
        val source = fields.source.text.toString().trim().uppercase().trimStart('*')
        val faultClass = fields.faultClass.text.toString().trim()
        val persistence = fields.persistence.text.toString().trim().uppercase()
        val identifiersText = fields.identifiers.text.toString().trim()
        val cmsMetadataPresent = maintenanceAta != null || source.isNotBlank() || faultClass.isNotBlank() ||
            persistence.isNotBlank() || identifiersText.isNotBlank()
        if (cmsMetadataPresent && cmsMessage.isBlank()) {
            toast("CMS metadata is filled but CMS Message is blank. Enter/correct the CMS fault message, or clear the CMS fields.")
            return null
        }

        val cockpitEffect = ecam.ifBlank { cockpitStatus }.ifBlank { null }
        val cockpitType = when {
            ecam.isNotBlank() -> "EWD WARNING(S)"
            cockpitStatus.isNotBlank() -> enteredType.ifBlank { null }
            else -> null
        }
        if (cockpitEffect == null && cmsMessage.isBlank()) {
            toast("Enter at least an ECAM/Cockpit Effect or a CMS Fault Message")
            return null
        }

        val identifiers = identifiersText.uppercase()
            .split(Regex("[/,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        val hasCms = cmsMessage.isNotBlank()
        val effectiveText = if (hasCms) cmsMessage else cockpitEffect.orEmpty()
        val fault = PostFlightAnalyzer.Fault(
            number = eventNumber,
            category = if (hasCms) "CMS FAULT MESSAGE" else "WARNING/MALFUNCTION",
            text = effectiveText,
            ata = maintenanceAta?.take(2),
            identifiers = if (hasCms) identifiers else emptyList(),
            ataFull = maintenanceAta,
            utc = null,
            flightPhase = null,
            faultClass = if (hasCms) faultClass.ifBlank { null } else null,
            persistence = if (hasCms) persistence.ifBlank { null } else null,
            source = if (hasCms) source.ifBlank { null } else null,
            cockpitEffect = cockpitEffect,
            cockpitAta = cockpitAta,
            flightPhaseCode = null,
            cockpitType = cockpitType
        )
        editQuery.setText(fault.text)
        appLog.i(
            "PFR_USER_VERIFIED",
            "event=$eventNumber type=${cockpitType ?: ""} cockpit=${cockpitEffect ?: ""} cmsAta=${maintenanceAta ?: ""} cms=$cmsMessage source=$source class=$faultClass"
        )
        return fault
    }

    private fun parsePfrAta(value: String, digitsExpected: Int): String? {
        val digits = value.filter(Char::isDigit)
        if (digits.length != digitsExpected) return null
        return if (digitsExpected == 4) "${digits.take(2)}-${digits.drop(2)}"
        else "${digits.take(2)}-${digits.substring(2, 4)}-${digits.drop(4)}"
    }

    private fun isEcamCockpitType(type: String?): Boolean {
        val t = type.orEmpty().uppercase().replace(Regex("[^A-Z]"), "")
        return t.contains("EWDWARNING") || t.contains("ECAMWARNING")
    }

    private fun handleOcrText(text: String) {
        if (text.isBlank()) return toast("No readable text detected")
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE
        when (photoMode) {
            PhotoMode.PFR -> {
                val parsed = PostFlightAnalyzer.analyze(text)
                if (parsed.faults.isEmpty()) {
                    addOcrLabel("PFR • structured maintenance event not confidently detected")
                    addOcrInfo("Raw OCR is not treated as a troubleshooting fault. Use the text field to correct the maintenance message if needed.")
                    editQuery.setText(text)
                    status("PFR OCR • STRUCTURE NOT RESOLVED")
                } else {
                    addOcrLabel("PFR STRUCTURED EVENTS • ${parsed.faults.size}")
                    parsed.faults.forEach { fault ->
                        addOcrInfo(fault.displaySummary())
                        addOcrButton("RESOLVE THIS EVENT") { resolvePfrFault(fault, "PFR EVENT ${fault.number}") }
                    }
                    resolvePfrFault(parsed.faults.first(), "PFR EVENT ${parsed.faults.first().number}")
                }
            }
            PhotoMode.TECHLOG -> {
                val parsed = CaptainReportAnalyzer.analyze(text)
                if (parsed.defects.isEmpty()) {
                    editQuery.setText(text)
                    addOcrLabel("TECH LOG • no structured defect detected")
                    status("TECH LOG OCR READY • REVIEW TEXT")
                } else {
                    addOcrLabel("TECH LOG DEFECTS • ${parsed.defects.size}")
                    parsed.defects.forEach { defect ->
                        val q = buildString {
                            append(defect.text)
                            defect.ata?.let { append(" ATA ").append(it) }
                            if (defect.identifiers.isNotEmpty()) append(' ').append(defect.identifiers.joinToString(" "))
                        }
                        addOcrButton("DEFECT ${defect.number} • ATA ${defect.ata ?: "?"}\n${defect.text}") { resolveText(q, "TECH LOG DEFECT ${defect.number}") }
                    }
                    parsed.defects.firstOrNull()?.let { defect ->
                        val q = defect.text + (defect.ata?.let { " ATA $it" } ?: "") + " " + defect.identifiers.joinToString(" ")
                        resolveText(q.trim(), "TECH LOG DEFECT ${defect.number}")
                    }
                }
            }
            PhotoMode.COMPONENT -> componentSearch(text)
        }
    }

    private fun componentSearch(text: String) {
        val r = repo ?: return toast("Import database first")
        val parsed = ComponentAnalyzer.analyze(text)
        val q = parsed.query.ifBlank { text }
        currentQuestion = q
        editQuery.setText(q)
        clearResults(keepOcr = true)
        txtResultHeading.visibility = View.VISIBLE
        txtResult.visibility = View.VISIBLE
        txtResult.text = "Searching exact component / P/N / FIN identifiers…"
        status("COMPONENT • SEARCHING…")
        io.execute {
            runCatching { r.searchAi(q, editAircraft.text.toString().trim(), 20) }
                .onSuccess { bundle -> ui {
                    currentHits = bundle.hits.take(12)
                    txtResult.text = buildString {
                        append("COMPONENT / IDENTIFIER SEARCH\n")
                        append("Identifiers: ").append(parsed.identifiers.joinToString(", ").ifBlank { "not confidently detected" })
                        append("\n\nThis mode does not make a MEL dispatch decision. Open original IPC/AWM/AMM sources below.")
                    }
                    renderSourcePacket(currentHits)
                    status("COMPONENT • ${currentHits.size} SOURCE RESULT(S)")
                } }
                .onFailure { e -> ui { txtResult.text = "COMPONENT SEARCH ERROR\n${e.message}"; appLog.e("COMPONENT_SEARCH_FAIL", e) } }
        }
    }

    private fun addOcrLabel(text: String) {
        ocrContainer.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(3), dp(8), dp(3), dp(5))
        })
    }

    private fun addOcrInfo(text: String) {
        ocrContainer.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#1C1C1E"))
            textSize = 12f
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(Color.parseColor("#F2F2F7"))
        })
    }

    private fun addOcrButton(text: String, action: () -> Unit) {
        ocrContainer.addView(MaterialButton(this).apply {
            this.text = text
            textSize = 10f
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
        })
    }

    private fun refreshLog() { txtLog.text = appLog.read() }

    private fun copyLog() {
        val text = appLog.read()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Line Assistant log", text))
        toast("Log copied")
    }

    private fun status(text: String) { txtStatus.text = text }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun ui(block: () -> Unit) { main.post(block) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        aiGeneration++
        engine?.close()
        repo?.close()
        llm.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
