from pathlib import Path

# Line Assistant 1.4.1
# - Persistent manual entry sections: ECAM, Cockpit Status, CMS, Full PFR
# - Camera/Gallery always pass through an adjustable crop screen before OCR
# - OCR still populates the full structured first-entry-point editor
# - No troubleshooting is started from the crop/OCR path until user verifies fields

activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()

# Cropper imports.
import_anchor = 'import com.google.mlkit.vision.common.InputImage\n'
if import_anchor not in a:
    raise SystemExit('1.4.1: ML Kit import anchor not found')
a = a.replace(
    import_anchor,
    'import com.canhub.cropper.CropImageContract\n'
    'import com.canhub.cropper.CropImageContractOptions\n'
    'import com.canhub.cropper.CropImageOptions\n'
    'import com.canhub.cropper.CropImageView\n' + import_anchor,
    1
)

# Manual entry modes.
mode_anchor = '    private enum class PhotoMode { PFR, TECHLOG, COMPONENT }\n'
if mode_anchor not in a:
    raise SystemExit('1.4.1: PhotoMode anchor not found')
a = a.replace(
    mode_anchor,
    mode_anchor + '    private enum class ManualEntryMode { FULL, ECAM, COCKPIT, CMS }\n',
    1
)

# Gallery and camera must crop before OCR.
old_gallery = '''    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->\n        if (uri != null) ocr(uri)\n    }\n    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->\n        if (ok) photoUri?.let(::ocr)\n    }\n'''
new_gallery = '''    private val galleryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->\n        if (uri != null) startCrop(uri)\n    }\n    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->\n        if (ok) photoUri?.let(::startCrop)\n    }\n    private val cropImage = registerForActivityResult(CropImageContract()) { result ->\n        if (result.isSuccessful) {\n            val cropped = result.uriContent\n            if (cropped != null) {\n                status("CROP CONFIRMED • OCR READING SELECTED AREA…")\n                ocr(cropped)\n            } else {\n                toast("Crop returned no image")\n            }\n        } else {\n            val error = result.error\n            if (error != null) {\n                appLog.e("IMAGE_CROP_FAIL", error)\n                status("CROP FAILED • ORIGINAL IMAGE NOT SENT TO OCR")\n            } else {\n                status("CROP CANCELLED • OCR NOT STARTED")\n            }\n        }\n    }\n'''
if old_gallery not in a:
    raise SystemExit('1.4.1: gallery/camera launcher anchor not found')
a = a.replace(old_gallery, new_gallery, 1)

# Manual buttons.
wire_anchor = '        findViewById<MaterialButton>(R.id.btnPfrManual).setOnClickListener { showManualPfrEntry() }\n'
if wire_anchor not in a:
    raise SystemExit('1.4.1: manual PFR wire anchor not found')
a = a.replace(
    wire_anchor,
    wire_anchor +
    '        findViewById<MaterialButton>(R.id.btnManualEcam).setOnClickListener { showManualPfrEntry(ManualEntryMode.ECAM) }\n'
    '        findViewById<MaterialButton>(R.id.btnManualStatus).setOnClickListener { showManualPfrEntry(ManualEntryMode.COCKPIT) }\n'
    '        findViewById<MaterialButton>(R.id.btnManualCms).setOnClickListener { showManualPfrEntry(ManualEntryMode.CMS) }\n',
    1
)

# Add crop entry point before OCR.
ocr_anchor = '    private fun ocr(uri: Uri) {\n'
if ocr_anchor not in a:
    raise SystemExit('1.4.1: OCR function anchor not found')
crop_fn = r'''    private fun startCrop(uri: Uri) {
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
                    activityTitle = if (photoMode == PhotoMode.PFR) "CROP PFR ROW" else "CROP IMAGE FOR OCR",
                    cropMenuCropButtonTitle = "USE THIS AREA",
                    outputCompressQuality = 100
                )
            )
        )
    }

'''
a = a.replace(ocr_anchor, crop_fn + ocr_anchor, 1)

# Manual full / section-specific entry.
old_manual = '''    private fun showManualPfrEntry() {\n        photoMode = PhotoMode.PFR\n        clearResults()\n        ocrContainer.removeAllViews()\n        ocrContainer.visibility = View.VISIBLE\n        addOcrLabel("PFR MANUAL ENTRY")\n        addOcrInfo("Enter the PFR row exactly as printed. Fields may be left blank when that column is blank on the PFR.")\n        renderPfrEditor(null, "", 1)\n        status("PFR • MANUAL STRUCTURED ENTRY")\n    }\n'''
new_manual = '''    private fun showManualPfrEntry() = showManualPfrEntry(ManualEntryMode.FULL)\n\n    private fun showManualPfrEntry(mode: ManualEntryMode) {\n        photoMode = PhotoMode.PFR\n        clearResults()\n        ocrContainer.removeAllViews()\n        ocrContainer.visibility = View.VISIBLE\n        val title = when (mode) {\n            ManualEntryMode.FULL -> "FULL PFR MANUAL ENTRY"\n            ManualEntryMode.ECAM -> "ECAM WARNING • MANUAL ENTRY"\n            ManualEntryMode.COCKPIT -> "COCKPIT EFFECT / STATUS • MANUAL ENTRY"\n            ManualEntryMode.CMS -> "CMS FAULT MESSAGE • MANUAL ENTRY"\n        }\n        addOcrLabel(title)\n        addOcrInfo("Enter only what is actually printed/known. Empty sections stay empty. You can edit every field before troubleshooting.")\n        renderPfrEditor(null, "", 1, mode)\n        status("$title • READY")\n    }\n'''
if old_manual not in a:
    raise SystemExit('1.4.1: showManualPfrEntry anchor not found')
a = a.replace(old_manual, new_manual, 1)

# Editor mode parameter. OCR keeps FULL by default.
sig_old = '    private fun renderPfrEditor(seed: PostFlightAnalyzer.Fault?, rawOcr: String, eventNumber: Int) {\n'
sig_new = '    private fun renderPfrEditor(seed: PostFlightAnalyzer.Fault?, rawOcr: String, eventNumber: Int, mode: ManualEntryMode = ManualEntryMode.FULL) {\n'
if sig_old not in a:
    raise SystemExit('1.4.1: renderPfrEditor signature not found')
a = a.replace(sig_old, sig_new, 1)

# Dynamic editor heading.
heading_old = '''        root.addView(TextView(this).apply {\n            text = "PFR EVENT $eventNumber • FIRST ENTRY POINT"\n'''
heading_new = '''        val entryHeading = when (mode) {\n            ManualEntryMode.FULL -> "PFR EVENT $eventNumber • FIRST ENTRY POINT"\n            ManualEntryMode.ECAM -> "ECAM WARNING SEARCH"\n            ManualEntryMode.COCKPIT -> "COCKPIT EFFECT / STATUS SEARCH"\n            ManualEntryMode.CMS -> "CMS FAULT MESSAGE SEARCH"\n        }\n        root.addView(TextView(this).apply {\n            text = entryHeading\n'''
if heading_old not in a:
    raise SystemExit('1.4.1: editor heading anchor not found')
a = a.replace(heading_old, heading_new, 1)

# Section visibility: fields are still instantiated, but irrelevant sections are hidden in direct-manual modes.
ecam_anchor = '        val ecam = pfrSection(root, "ECAM WARNING SEARCH")\n'
if ecam_anchor not in a:
    raise SystemExit('1.4.1: ECAM section anchor not found')
a = a.replace(
    ecam_anchor,
    ecam_anchor + '        ecam.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.ECAM) View.VISIBLE else View.GONE\n',
    1
)

cockpit_anchor = '        val cockpit = pfrSection(root, "COCKPIT EFFECT / STATUS")\n'
if cockpit_anchor not in a:
    raise SystemExit('1.4.1: cockpit section anchor not found')
a = a.replace(
    cockpit_anchor,
    cockpit_anchor + '        cockpit.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.COCKPIT) View.VISIBLE else View.GONE\n',
    1
)

cms_anchor = '        val cms = pfrSection(root, "CMS FAULT MESSAGE SEARCH")\n'
if cms_anchor not in a:
    raise SystemExit('1.4.1: CMS section anchor not found')
a = a.replace(
    cms_anchor,
    cms_anchor + '        cms.visibility = if (mode == ManualEntryMode.FULL || mode == ManualEntryMode.CMS) View.VISIBLE else View.GONE\n',
    1
)

# More accurate helper note.
a = a.replace(
    'Supported independently: ECAM + CMS • ECAM only • CMS only • Cockpit/Maintenance Status with or without CMS. Flight phase/UTC are not search inputs.',
    'Independent entry modes: ECAM • Cockpit/Maintenance Status • CMS • Full PFR. Camera/Gallery images are cropped before OCR. Flight phase/UTC are not troubleshooting search inputs.'
)

activity.write_text(a)

# -----------------------------------------------------------------------------
# Layout: persistent manual entry choices.
# -----------------------------------------------------------------------------
layout = Path('app/src/main/res/layout/activity_line_assistant.xml')
x = layout.read_text()

x = x.replace('android:text="PFR MANUAL ENTRY / CORRECTION"', 'android:text="FULL PFR MANUAL ENTRY"', 1)

manual_button_end = '''            app:cornerRadius="9dp"\n            app:strokeColor="#31536D" />\n\n        <LinearLayout\n            android:id="@+id/ocrContainer"\n'''
if manual_button_end not in x:
    raise SystemExit('1.4.1: manual button / OCR container layout anchor not found')
manual_sections = '''            app:cornerRadius="9dp"\n            app:strokeColor="#31536D" />\n\n        <TextView\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:layout_marginTop="10dp"\n            android:text="MANUAL TROUBLESHOOT ENTRY"\n            android:textColor="#78B7E8"\n            android:textSize="10sp"\n            android:textStyle="bold" />\n\n        <LinearLayout\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:layout_marginTop="4dp"\n            android:orientation="horizontal"\n            android:weightSum="3">\n\n            <com.google.android.material.button.MaterialButton\n                android:id="@+id/btnManualEcam"\n                style="@style/Widget.MaterialComponents.Button.OutlinedButton"\n                android:layout_width="0dp"\n                android:layout_height="46dp"\n                android:layout_marginEnd="3dp"\n                android:layout_weight="1"\n                android:text="ECAM"\n                android:textSize="9sp"\n                app:cornerRadius="9dp" />\n\n            <com.google.android.material.button.MaterialButton\n                android:id="@+id/btnManualStatus"\n                style="@style/Widget.MaterialComponents.Button.OutlinedButton"\n                android:layout_width="0dp"\n                android:layout_height="46dp"\n                android:layout_marginStart="3dp"\n                android:layout_marginEnd="3dp"\n                android:layout_weight="1"\n                android:text="STATUS"\n                android:textSize="9sp"\n                app:cornerRadius="9dp" />\n\n            <com.google.android.material.button.MaterialButton\n                android:id="@+id/btnManualCms"\n                style="@style/Widget.MaterialComponents.Button.OutlinedButton"\n                android:layout_width="0dp"\n                android:layout_height="46dp"\n                android:layout_marginStart="3dp"\n                android:layout_weight="1"\n                android:text="CMS FAULT"\n                android:textSize="9sp"\n                app:cornerRadius="9dp" />\n        </LinearLayout>\n\n        <TextView\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:layout_marginTop="5dp"\n            android:text="Photo flow: Camera / Gallery → Crop selected PFR row → OCR → Review/Edit → Troubleshoot"\n            android:textColor="#7F96A8"\n            android:textSize="9sp" />\n\n        <LinearLayout\n            android:id="@+id/ocrContainer"\n'''
x = x.replace(manual_button_end, manual_sections, 1)
layout.write_text(x)

# -----------------------------------------------------------------------------
# Dependency + version metadata.
# -----------------------------------------------------------------------------
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'implementation("com.vanniktech:android-image-cropper:4.7.0")' not in g:
    dep_anchor = '    implementation("com.google.mlkit:text-recognition:16.0.1")\n'
    if dep_anchor not in g:
        raise SystemExit('1.4.1: dependency anchor not found')
    g = g.replace(dep_anchor, dep_anchor + '    implementation("com.vanniktech:android-image-cropper:4.7.0")\n', 1)
if 'versionCode = 38' not in g or 'versionName = "1.4.0"' not in g:
    raise SystemExit('1.4.1: expected 1.4.0 version metadata not found')
g = g.replace('versionCode = 38', 'versionCode = 39', 1)
g = g.replace('versionName = "1.4.0"', 'versionName = "1.4.1"', 1)
gradle.write_text(g)

# Version text in deterministic result header.
engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()
e = e.replace(
    'LINE ASSISTANT 1.4.0 • STRUCTURED CMS + ROBUST EXACT-ATA TSM WORKFLOW',
    'LINE ASSISTANT 1.4.1 • MULTI-ENTRY + CROP-FIRST PFR WORKFLOW'
)
engine.write_text(e)

print('Line Assistant 1.4.1 multi-section manual entry + crop-before-OCR applied')
