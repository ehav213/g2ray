from pathlib import Path

# Line Assistant 1.4.2
# - One persistent first-entry form on the home screen: ECAM + Maintenance/Cockpit Status + CMS
# - Any valid subset can be searched; no separate ECAM/STATUS/CMS pages/buttons
# - PFR is the only photo/OCR workflow on the home screen
# - Camera/Gallery always crop before OCR; crop action is short and explicit: USE
# - Light, iOS-inspired visual system with white cards, grouped backgrounds and blue accent

activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()

# Render the unified form on first launch.
oncreate_old = '''        openDatabase()\n        updateModelStatus()\n    }\n'''
oncreate_new = '''        openDatabase()\n        updateModelStatus()\n        showUnifiedFirstEntryPoint()\n        window.statusBarColor = Color.parseColor("#F2F2F7")\n        window.navigationBarColor = Color.WHITE\n        @Suppress("DEPRECATION")\n        run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR }\n    }\n'''
if oncreate_old not in a:
    raise SystemExit('1.4.2: onCreate anchor not found')
a = a.replace(oncreate_old, oncreate_new, 1)

# Insert unified-home renderer before manual entry function.
manual_marker = '    private fun showManualPfrEntry() = showManualPfrEntry(ManualEntryMode.FULL)\n'
if manual_marker not in a:
    raise SystemExit('1.4.2: manual marker not found')
unified_fn = r'''    private fun showUnifiedFirstEntryPoint() {
        photoMode = PhotoMode.PFR
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE
        addOcrLabel("FIRST ENTRY POINT")
        addOcrInfo("Enter ECAM, Maintenance/Cockpit Status, CMS fault data, or any valid combination. PFR OCR fills these same fields; review/correct them before troubleshooting.")
        renderPfrEditor(null, "", 1, ManualEntryMode.FULL)
    }

'''
a = a.replace(manual_marker, unified_fn + manual_marker, 1)

# Crop screen: make the confirmation action unmistakable and short enough to remain visible.
old_crop_title = 'activityTitle = if (photoMode == PhotoMode.PFR) "CROP PFR ROW" else "CROP IMAGE FOR OCR",\n                    cropMenuCropButtonTitle = "USE THIS AREA",\n'
new_crop_title = 'activityTitle = if (photoMode == PhotoMode.PFR) "CROP PFR • TAP USE TO CONFIRM" else "CROP IMAGE • TAP USE",\n                    cropMenuCropButtonTitle = "USE",\n                    activityMenuIconColor = Color.parseColor("#0A84FF"),\n'
if old_crop_title not in a:
    raise SystemExit('1.4.2: crop title anchor not found')
a = a.replace(old_crop_title, new_crop_title, 1)

# Home wording: one page, not separate entry modes.
a = a.replace('"PFR EVENT $eventNumber • FIRST ENTRY POINT"', '"ECAM • MAINTENANCE • CMS"')
a = a.replace('"COCKPIT EFFECT / STATUS"', '"MAINTENANCE / COCKPIT STATUS"')
a = a.replace(
    '"OCR fills only fields actually identified in this PFR row. Correct any field before SEARCH / TROUBLESHOOT."',
    '"Use any section independently or together. PFR OCR fills only what it detects; you can correct every field before Search."'
)
a = a.replace(
    '"Independent entry modes: ECAM • Cockpit/Maintenance Status • CMS • Full PFR. Camera/Gallery images are cropped before OCR. Flight phase/UTC are not troubleshooting search inputs."',
    '"One-page search: ECAM • Maintenance/Cockpit Status • CMS. PFR photos are cropped before OCR. UTC/flight phase are not troubleshooting search inputs."'
)

# iOS-inspired dynamic cards and form fields.
replacements = {
    '#0C1721': '#FFFFFF',
    '#31536D': '#D1D1D6',
    '#86C8F4': '#0A84FF',
    '#8298AA': '#8E8E93',
    '#79B6DE': '#0A84FF',
    '#101E29': '#F2F2F7',
    '#8EA7BA': '#6E6E73',
    '#657C90': '#8E8E93',
    '#0E1822': '#FFFFFF',
    '#294257': '#D1D1D6',
    '#2C7DB0': '#0A84FF',
    '#7A5A24': '#FFD60A',
    '#DDE8F1': '#1C1C1E',
    '#9FB2C3': '#6E6E73',
    '#78B7E8': '#0A84FF',
    '#DCE8F2': '#1C1C1E',
    '#0D1721': '#F2F2F7',
}
for old, new in replacements.items():
    a = a.replace(old, new)

# White text in dynamically-created cards/fields becomes dark in light mode.
a = a.replace('setTextColor(Color.WHITE)', 'setTextColor(Color.parseColor("#1C1C1E"))')

# Primary Search / Troubleshoot action uses iOS system blue.
button_anchor = '''            text = "SEARCH / TROUBLESHOOT VERIFIED FIELDS"\n            textSize = 11f\n'''
button_repl = '''            text = "SEARCH / TROUBLESHOOT"\n            textSize = 12f\n            setTextColor(Color.WHITE)\n            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0A84FF"))\n'''
if button_anchor not in a:
    raise SystemExit('1.4.2: search button anchor not found')
a = a.replace(button_anchor, button_repl, 1)

# Slightly larger, softer card corners.
a = a.replace('radius = dp(14).toFloat()', 'radius = dp(20).toFloat()')

activity.write_text(a)

# -----------------------------------------------------------------------------
# Replace the screen layout with a light, iOS-inspired grouped layout.
# Keep legacy IDs hidden so existing wiring remains safe.
# -----------------------------------------------------------------------------
layout = Path('app/src/main/res/layout/activity_line_assistant.xml')
layout.write_text(r'''<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F2F2F7"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="16dp"
        android:paddingTop="18dp"
        android:paddingEnd="16dp"
        android:paddingBottom="34dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="A340 Technical Assistant"
                    android:textColor="#111111"
                    android:textSize="27sp"
                    android:textStyle="bold" />
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="First Entry Point • Offline Line Maintenance"
                    android:textColor="#8E8E93"
                    android:textSize="12sp" />
            </LinearLayout>

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnSettings"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:minWidth="0dp"
                android:text="⚙"
                android:textColor="#0A84FF"
                android:textSize="23sp"
                app:cornerRadius="24dp" />
        </LinearLayout>

        <TextView
            android:id="@+id/txtStatus"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:background="#E9E9EF"
            android:padding="10dp"
            android:text="Opening technical master…"
            android:textColor="#636366"
            android:textSize="11sp" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/settingsPanel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:visibility="gone"
            app:cardBackgroundColor="#FFFFFF"
            app:cardCornerRadius="20dp"
            app:strokeColor="#E5E5EA"
            app:strokeWidth="1dp">
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="14dp">
                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Settings & Data"
                    android:textColor="#1C1C1E"
                    android:textSize="16sp"
                    android:textStyle="bold" />
                <TextView android:id="@+id/txtModel" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:padding="10dp" android:background="#F2F2F7" android:textColor="#636366" android:textSize="11sp" />
                <com.google.android.material.button.MaterialButton android:id="@+id/btnImportDb" style="@style/Widget.MaterialComponents.Button.OutlinedButton" android:layout_width="match_parent" android:layout_height="46dp" android:layout_marginTop="8dp" android:text="Import / Update Technical Master" android:textColor="#0A84FF" android:textSize="10sp" app:cornerRadius="12dp" app:strokeColor="#D1D1D6" />
                <com.google.android.material.button.MaterialButton android:id="@+id/btnPdfFolder" style="@style/Widget.MaterialComponents.Button.OutlinedButton" android:layout_width="match_parent" android:layout_height="46dp" android:layout_marginTop="5dp" android:text="Select Original PDF Folder" android:textColor="#0A84FF" android:textSize="10sp" app:cornerRadius="12dp" app:strokeColor="#D1D1D6" />
                <com.google.android.material.button.MaterialButton android:id="@+id/btnModel" style="@style/Widget.MaterialComponents.Button.OutlinedButton" android:layout_width="match_parent" android:layout_height="46dp" android:layout_marginTop="5dp" android:text="Import Local AI Model" android:textColor="#0A84FF" android:textSize="10sp" app:cornerRadius="12dp" app:strokeColor="#D1D1D6" />
                <TextView android:id="@+id/txtManuals" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:padding="10dp" android:background="#F2F2F7" android:textColor="#636366" android:textSize="10sp" />
                <TextView android:id="@+id/txtLog" android:layout_width="match_parent" android:layout_height="130dp" android:layout_marginTop="8dp" android:padding="9dp" android:background="#F2F2F7" android:textColor="#3A3A3C" android:textIsSelectable="true" android:textSize="9sp" android:typeface="monospace" />
                <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="6dp" android:orientation="horizontal">
                    <com.google.android.material.button.MaterialButton android:id="@+id/btnRefreshLog" style="@style/Widget.MaterialComponents.Button.TextButton" android:layout_width="0dp" android:layout_height="42dp" android:layout_weight="1" android:text="Refresh" android:textColor="#0A84FF" />
                    <com.google.android.material.button.MaterialButton android:id="@+id/btnCopyLog" style="@style/Widget.MaterialComponents.Button.TextButton" android:layout_width="0dp" android:layout_height="42dp" android:layout_weight="1" android:text="Copy" android:textColor="#0A84FF" />
                    <com.google.android.material.button.MaterialButton android:id="@+id/btnClearLog" style="@style/Widget.MaterialComponents.Button.TextButton" android:layout_width="0dp" android:layout_height="42dp" android:layout_weight="1" android:text="Clear" android:textColor="#FF3B30" />
                </LinearLayout>
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="14dp"
            app:cardBackgroundColor="#FFFFFF"
            app:cardCornerRadius="20dp"
            app:strokeColor="#E5E5EA"
            app:strokeWidth="1dp">
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="14dp">
                <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Aircraft" android:textColor="#1C1C1E" android:textSize="14sp" android:textStyle="bold" />
                <EditText
                    android:id="@+id/editAircraft"
                    android:layout_width="match_parent"
                    android:layout_height="50dp"
                    android:layout_marginTop="4dp"
                    android:backgroundTint="#D1D1D6"
                    android:hint="REG / MSN / aircraft effectivity (optional)"
                    android:singleLine="true"
                    android:textColor="#111111"
                    android:textColorHint="#8E8E93"
                    android:textSize="14sp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">
            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Troubleshooting Search"
                android:textColor="#1C1C1E"
                android:textSize="19sp"
                android:textStyle="bold" />
            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnPfr"
                android:layout_width="wrap_content"
                android:layout_height="44dp"
                android:text="Scan PFR"
                android:textColor="#FFFFFF"
                android:textSize="11sp"
                app:backgroundTint="#0A84FF"
                app:cornerRadius="14dp" />
        </LinearLayout>

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="3dp"
            android:text="Camera / Gallery → Crop → USE → OCR → Review → Search"
            android:textColor="#8E8E93"
            android:textSize="10sp" />

        <LinearLayout
            android:id="@+id/ocrContainer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:orientation="vertical"
            android:visibility="visible" />

        <TextView android:id="@+id/txtResultHeading" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="22dp" android:text="Verified Line Maintenance Result" android:textColor="#1C1C1E" android:textSize="17sp" android:textStyle="bold" android:visibility="gone" />
        <TextView android:id="@+id/txtResult" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:background="#FFFFFF" android:lineSpacingExtra="3dp" android:padding="16dp" android:textColor="#1C1C1E" android:textIsSelectable="true" android:textSize="13sp" android:visibility="gone" />
        <TextView android:id="@+id/txtSourcesHeading" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="20dp" android:text="Original Source Packet" android:textColor="#1C1C1E" android:textSize="17sp" android:textStyle="bold" android:visibility="gone" />
        <LinearLayout android:id="@+id/sourceContainer" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="7dp" android:orientation="vertical" android:visibility="gone" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/aiCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:visibility="gone"
            app:cardBackgroundColor="#FFFFFF"
            app:cardCornerRadius="20dp"
            app:strokeColor="#E5E5EA"
            app:strokeWidth="1dp">
            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:padding="14dp">
                <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Local AI • Verified Evidence Only" android:textColor="#636366" android:textSize="10sp" android:textStyle="bold" />
                <com.google.android.material.button.MaterialButton android:id="@+id/btnAi" android:layout_width="match_parent" android:layout_height="48dp" android:layout_marginTop="7dp" android:text="Explain Verified MEL + TSM" android:textColor="#FFFFFF" app:backgroundTint="#34C759" app:cornerRadius="14dp" />
                <ProgressBar android:id="@+id/aiProgress" style="?android:attr/progressBarStyleHorizontal" android:layout_width="match_parent" android:layout_height="6dp" android:layout_marginTop="10dp" android:max="100" android:visibility="gone" />
                <TextView android:id="@+id/txtAiState" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="6dp" android:textColor="#636366" android:textSize="10sp" />
                <TextView android:id="@+id/txtAiAnswer" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:lineSpacingExtra="3dp" android:textColor="#1C1C1E" android:textIsSelectable="true" android:textSize="13sp" android:visibility="gone" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- Legacy controls retained hidden because existing activity wiring references these IDs. -->
        <LinearLayout android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone" android:orientation="vertical">
            <EditText android:id="@+id/editQuery" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnRun" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnTechLog" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnComponent" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnPfrManual" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnManualEcam" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnManualStatus" android:layout_width="1dp" android:layout_height="1dp" />
            <com.google.android.material.button.MaterialButton android:id="@+id/btnManualCms" android:layout_width="1dp" android:layout_height="1dp" />
        </LinearLayout>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
''')

# Version metadata.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 39' not in g or 'versionName = "1.4.1"' not in g:
    raise SystemExit('1.4.2: expected 1.4.1 version metadata not found')
g = g.replace('versionCode = 39', 'versionCode = 40', 1)
g = g.replace('versionName = "1.4.1"', 'versionName = "1.4.2"', 1)
gradle.write_text(g)

engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()
e = e.replace(
    'LINE ASSISTANT 1.4.1 • MULTI-ENTRY + CROP-FIRST PFR WORKFLOW',
    'LINE ASSISTANT 1.4.2 • UNIFIED FIRST ENTRY + CROP-CONFIRMED PFR WORKFLOW'
)
engine.write_text(e)

print('Line Assistant 1.4.2 unified iOS-inspired first-entry screen applied')
