from pathlib import Path

# Line Assistant 1.3.7
# - editable PFR form populated by geometry OCR
# - manual PFR entry without OCR
# - no automatic troubleshooting until the technician reviews/edits the fields
# - support cockpit-only Maintenance Status events (no right-side maintenance message)

activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()

# Add editor data holder after PhotoMode.
anchor = '    private enum class PhotoMode { PFR, TECHLOG, COMPONENT }\n'
if anchor not in a:
    raise SystemExit('1.3.7: PhotoMode anchor not found')
editor_data = '''    private enum class PhotoMode { PFR, TECHLOG, COMPONENT }\n\n    private data class PfrEditorFields(\n        val cockpitAta: EditText,\n        val cockpitType: EditText,\n        val cockpitEffect: EditText,\n        val utc: EditText,\n        val flightPhase: EditText,\n        val phaseCode: EditText,\n        val maintenanceAta: EditText,\n        val faultClass: EditText,\n        val persistence: EditText,\n        val maintenanceMessage: EditText,\n        val source: EditText,\n        val identifiers: EditText\n    )\n'''
a = a.replace(anchor, editor_data, 1)

# Wire manual entry button.
wire_anchor = '        findViewById<MaterialButton>(R.id.btnPfr).setOnClickListener { choosePhoto(PhotoMode.PFR) }\n'
if wire_anchor not in a:
    raise SystemExit('1.3.7: PFR button anchor not found')
a = a.replace(
    wire_anchor,
    wire_anchor + '        findViewById<MaterialButton>(R.id.btnPfrManual).setOnClickListener { showManualPfrEntry() }\n',
    1
)

# Replace the geometry-PFR UI path inserted by v1.2/v1.3.x.
start = a.find('    private fun handlePfrOcr(result: Text) {')
end = a.find('    private fun handleOcrText(text: String) {', start)
if start < 0 or end < 0:
    raise SystemExit('1.3.7: handlePfrOcr boundaries not found')

replacement = r'''    private fun handlePfrOcr(result: Text) {
        val text = result.text.trim()
        if (text.isBlank()) return toast("No readable text detected")
        clearResults()
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE

        val parsed = PfrGeometryAnalyzerV2.analyze(result)
        addOcrLabel("PFR STRUCTURED INPUT • OCR VALUES ARE EDITABLE")
        addOcrInfo("Review every field before troubleshooting. OCR is an input aid only; the values in these boxes are the values sent to MEL/TSM.")

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

    private fun showManualPfrEntry() {
        photoMode = PhotoMode.PFR
        clearResults()
        ocrContainer.removeAllViews()
        ocrContainer.visibility = View.VISIBLE
        addOcrLabel("PFR MANUAL ENTRY")
        addOcrInfo("Enter the PFR row exactly as printed. Fields may be left blank when that column is blank on the PFR.")
        renderPfrEditor(null, "", 1)
        status("PFR • MANUAL STRUCTURED ENTRY")
    }

    private fun renderPfrEditor(seed: PostFlightAnalyzer.Fault?, rawOcr: String, eventNumber: Int) {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#31536D")
            setCardBackgroundColor(Color.parseColor("#0C1721"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10); bottomMargin = dp(4) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "PFR EVENT $eventNumber • REVIEW / CORRECT"
            setTextColor(Color.parseColor("#86C8F4"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })

        val cockpit = pfrSection(root, "COCKPIT EFFECT")
        val cockpitAta = pfrField(cockpit, "COCKPIT ATA", seed?.cockpitAta.orEmpty(), "e.g. 27-90")
        val cockpitType = pfrField(cockpit, "TYPE", inferCockpitType(rawOcr), "e.g. MAINTENANCE STATUS")
        val seedCockpit = seed?.cockpitEffect ?: if (seed?.category == "WARNING/MALFUNCTION") seed.text else ""
        val cockpitEffect = pfrField(cockpit, "COCKPIT EFFECT / STATUS", seedCockpit, "e.g. EFCS 1")

        val flight = pfrSection(root, "FLIGHT DATA")
        val utc = pfrField(flight, "UTC", seed?.utc.orEmpty(), "HHMM")
        val flightPhase = pfrField(flight, "FLIGHT PHASE", seed?.flightPhase.orEmpty(), "e.g. CRUISE")
        val phaseCode = pfrField(flight, "PHASE CODE", seed?.flightPhaseCode.orEmpty(), "e.g. 06")

        val maintenance = pfrSection(root, "MAINTENANCE MESSAGE")
        val maintenanceAta = pfrField(maintenance, "MAINTENANCE ATA", seed?.ataFull ?: seed?.ata.orEmpty(), "e.g. 31-36-34; blank if none")
        val faultClass = pfrField(maintenance, "CLASS", seed?.faultClass.orEmpty(), "1 / 2 / 3; blank if none")
        val persistence = pfrField(maintenance, "STATUS", seed?.persistence.orEmpty(), "HARD / INTERMITTENT; blank if none")
        val maintenanceMessage = pfrField(
            maintenance,
            "MAINTENANCE MESSAGE",
            if (seed?.category == "CMS FAULT MESSAGE") seed.text else "",
            "Leave blank when the right-side PFR fault column is blank",
            multiline = true
        )
        val source = pfrField(maintenance, "SOURCE", seed?.source.orEmpty(), "blank if none")
        val identifiers = pfrField(maintenance, "IDENTIFIERS", seed?.identifiers?.joinToString(", ").orEmpty(), "comma or space separated", multiline = true)

        val fields = PfrEditorFields(
            cockpitAta, cockpitType, cockpitEffect, utc, flightPhase, phaseCode,
            maintenanceAta, faultClass, persistence, maintenanceMessage, source, identifiers
        )

        root.addView(MaterialButton(this).apply {
            text = "RESOLVE USING THESE VERIFIED / CORRECTED FIELDS"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                val fault = buildFaultFromEditor(fields, eventNumber) ?: return@setOnClickListener
                resolvePfrFault(fault, "PFR EVENT $eventNumber • USER VERIFIED")
            }
        })
        root.addView(TextView(this).apply {
            text = "OCR fields remain editable. Empty maintenance fields are valid for cockpit-only / Maintenance Status rows."
            setTextColor(Color.parseColor("#8298AA"))
            textSize = 9f
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(root)
        ocrContainer.addView(card)
    }

    private fun pfrSection(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#79B6DE"))
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(3))
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(7))
            setBackgroundColor(Color.parseColor("#101E29"))
            parent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) })
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
            setTextColor(Color.parseColor("#8EA7BA"))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        })
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#657C90"))
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

    private fun buildFaultFromEditor(fields: PfrEditorFields, eventNumber: Int): PostFlightAnalyzer.Fault? {
        val cockpitAtaRaw = fields.cockpitAta.text.toString().trim()
        val maintenanceAtaRaw = fields.maintenanceAta.text.toString().trim()
        val cockpitAta = parsePfrAta(cockpitAtaRaw, 4)
        val maintenanceAta = parsePfrAta(maintenanceAtaRaw, 6)
        if (cockpitAtaRaw.isNotBlank() && cockpitAta == null) {
            toast("Cockpit ATA must contain 4 digits, for example 27-90")
            return null
        }
        if (maintenanceAtaRaw.isNotBlank() && maintenanceAta == null) {
            toast("Maintenance ATA must contain 6 digits, for example 31-36-34")
            return null
        }

        val cockpitEffect = fields.cockpitEffect.text.toString().trim().ifBlank { null }
        val maintenanceMessage = fields.maintenanceMessage.text.toString().trim()
        if (cockpitEffect == null && maintenanceMessage.isBlank()) {
            toast("Enter at least a Cockpit Effect/Status or a Maintenance Message")
            return null
        }

        val utcRaw = fields.utc.text.toString().trim()
        val utc = utcRaw.filter(Char::isDigit).takeIf { it.length == 4 }
        if (utcRaw.isNotBlank() && utc == null) {
            toast("UTC must contain 4 digits, for example 1828")
            return null
        }
        val phaseCodeRaw = fields.phaseCode.text.toString().trim()
        val phaseCode = phaseCodeRaw.filter(Char::isDigit).takeIf { it.length == 2 }
        if (phaseCodeRaw.isNotBlank() && phaseCode == null) {
            toast("Flight phase code must contain 2 digits, for example 06")
            return null
        }

        val identifiers = fields.identifiers.text.toString()
            .uppercase()
            .split(Regex("[/,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        val cockpitOnly = maintenanceAta == null && maintenanceMessage.isBlank()
        val effectiveText = if (cockpitOnly) cockpitEffect.orEmpty() else maintenanceMessage.ifBlank { cockpitEffect.orEmpty() }
        val category = if (cockpitOnly) "WARNING/MALFUNCTION" else "CMS FAULT MESSAGE"
        val fault = PostFlightAnalyzer.Fault(
            number = eventNumber,
            category = category,
            text = effectiveText,
            ata = maintenanceAta?.take(2),
            identifiers = identifiers,
            ataFull = maintenanceAta,
            utc = utc,
            flightPhase = fields.flightPhase.text.toString().trim().ifBlank { null },
            faultClass = fields.faultClass.text.toString().trim().ifBlank { null },
            persistence = fields.persistence.text.toString().trim().uppercase().ifBlank { null },
            source = fields.source.text.toString().trim().uppercase().trimStart('*').ifBlank { null },
            cockpitEffect = cockpitEffect,
            cockpitAta = cockpitAta,
            flightPhaseCode = phaseCode
        )
        editQuery.setText(fault.text)
        appLog.i(
            "PFR_USER_VERIFIED",
            "event=$eventNumber cockpitAta=${cockpitAta ?: ""} cockpit=${cockpitEffect ?: ""} maintAta=${maintenanceAta ?: ""} maint=$maintenanceMessage type=${fields.cockpitType.text.toString().trim()}"
        )
        return fault
    }

    private fun parsePfrAta(value: String, digitsExpected: Int): String? {
        val digits = value.filter(Char::isDigit)
        if (digits.length != digitsExpected) return null
        return if (digitsExpected == 4) "${digits.take(2)}-${digits.drop(2)}"
        else "${digits.take(2)}-${digits.substring(2, 4)}-${digits.drop(4)}"
    }

    private fun inferCockpitType(raw: String): String {
        val u = raw.uppercase()
        return when {
            "MAINTENANCE STATUS" in u || "MAINT STATUS" in u -> "MAINTENANCE STATUS"
            "STS-INOP SYSTEM" in u -> "STS-INOP SYSTEM"
            "EWD WARNING" in u -> "EWD WARNING(S)"
            "PFD FLAG" in u -> "PFD FLAG(S)"
            "ND FLAG" in u -> "ND FLAG(S)"
            else -> ""
        }
    }

'''
a = a[:start] + replacement + a[end:]
activity.write_text(a)

# Layout: add a dedicated manual-entry button below the image-mode row.
layout = Path('app/src/main/res/layout/activity_line_assistant.xml')
x = layout.read_text()
marker = '''        <LinearLayout\n            android:id="@+id/ocrContainer"\n'''
if marker not in x:
    raise SystemExit('1.3.7: ocrContainer layout anchor not found')
manual_button = '''        <com.google.android.material.button.MaterialButton\n            android:id="@+id/btnPfrManual"\n            style="@style/Widget.MaterialComponents.Button.OutlinedButton"\n            android:layout_width="match_parent"\n            android:layout_height="44dp"\n            android:layout_marginTop="5dp"\n            android:text="PFR MANUAL ENTRY / CORRECTION"\n            android:textSize="10sp"\n            app:cornerRadius="9dp"\n            app:strokeColor="#31536D" />\n\n'''
x = x.replace(marker, manual_button + marker, 1)
layout.write_text(x)

# TSM index: recognize Maintenance Status as a cockpit-effect type in no-fault-message index records.
engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()
old_types = '                val knownTypes = listOf("OBSERVATIONS", "EWD WARNING(S)", "PFD FLAG(S)", "ND FLAG(S)", "STS-INOP SYSTEM")\n'
new_types = '                val knownTypes = listOf("OBSERVATIONS", "EWD WARNING(S)", "PFD FLAG(S)", "ND FLAG(S)", "STS-INOP SYSTEM", "MAINTENANCE STATUS", "MAINT STATUS")\n'
if old_types not in e:
    raise SystemExit('1.3.7: TSM knownTypes anchor not found')
e = e.replace(old_types, new_types, 1)
e = e.replace(
    'LINE ASSISTANT 1.3.6 • UNIVERSAL PFR GEOMETRY + EXACT TSM WORKFLOW',
    'LINE ASSISTANT 1.3.7 • EDITABLE PFR FORM + EXACT TSM WORKFLOW'
)
engine.write_text(e)

# Version bump after 1.3.6.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 34' not in g or 'versionName = "1.3.6"' not in g:
    raise SystemExit('1.3.7: expected 1.3.6 version metadata not found')
g = g.replace('versionCode = 34', 'versionCode = 35', 1)
g = g.replace('versionName = "1.3.6"', 'versionName = "1.3.7"', 1)
gradle.write_text(g)

print('Line Assistant 1.3.7 editable structured PFR form applied')
