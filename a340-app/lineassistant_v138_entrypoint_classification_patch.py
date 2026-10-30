from pathlib import Path

# Line Assistant 1.3.8
# Airbus-style first-entry-point PFR editor:
# - ECAM Warning is independent from Cockpit Effect / Maintenance Status
# - CMS Fault Message is independent and uses ATA CH/SE/SU + Source + Message + Class
# - OCR fills only the section supported by the PFR row; no cross-copying or guessing
# - Flight information is removed from the troubleshooting entry form
# - user reviews/corrects all fields before search/troubleshooting

# -----------------------------------------------------------------------------
# 1) Preserve the cockpit effect TYPE in the structured PFR model.
# -----------------------------------------------------------------------------
post = Path('app/src/main/java/com/a340/technicalassistant/PostFlightAnalyzer.kt')
p = post.read_text()
old = '''        val cockpitEffect: String? = null,\n        val cockpitAta: String? = null,\n        val flightPhaseCode: String? = null\n    ) {'''
new = '''        val cockpitEffect: String? = null,\n        val cockpitAta: String? = null,\n        val flightPhaseCode: String? = null,\n        val cockpitType: String? = null\n    ) {'''
if old not in p:
    raise SystemExit('1.3.8: PostFlightAnalyzer Fault fields anchor not found')
p = p.replace(old, new, 1)

old = '''            cockpitEffect?.let { append("COCKPIT EFFECT: ").append(it).append('\\n') }\n            cockpitAta?.let { append("COCKPIT ATA: ").append(it).append('\\n') }\n'''
new = '''            cockpitType?.let { append("COCKPIT TYPE: ").append(it).append('\\n') }\n            cockpitEffect?.let { append("COCKPIT EFFECT: ").append(it).append('\\n') }\n            cockpitAta?.let { append("COCKPIT ATA: ").append(it).append('\\n') }\n'''
if old not in p:
    raise SystemExit('1.3.8: PostFlightAnalyzer display summary anchor not found')
p = p.replace(old, new, 1)
post.write_text(p)

# -----------------------------------------------------------------------------
# 2) Geometry parser: extract the row-local cockpit type instead of inferring it
#    from the entire OCR image.
# -----------------------------------------------------------------------------
parser = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
s = parser.read_text()
old = '''            val cockpitMessageRaw = parseCockpitMessage(cockpitLines, cockpitAtaDigits)\n            val cockpitMessage = repairSingleWordAgainstSource(cockpitMessageRaw, source)\n\n            val utc = middleLines.firstNotNullOfOrNull { parseUtc(it.text) }\n'''
new = '''            val cockpitType = parseCockpitType(cockpitLines)\n            val cockpitMessageRaw = parseCockpitMessage(cockpitLines, cockpitAtaDigits)\n            val cockpitMessage = repairSingleWordAgainstSource(cockpitMessageRaw, source)\n\n            val utc = middleLines.firstNotNullOfOrNull { parseUtc(it.text) }\n'''
if old not in s:
    raise SystemExit('1.3.8: parser cockpit message anchor not found')
s = s.replace(old, new, 1)

needle = '''                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\n                        flightPhaseCode = phaseCode\n                    )'''
replacement = '''                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\n                        flightPhaseCode = phaseCode,\n                        cockpitType = cockpitType\n                    )'''
count = s.count(needle)
if count != 2:
    raise SystemExit(f'1.3.8: expected 2 parser Fault constructors, found {count}')
s = s.replace(needle, replacement)

marker = '    private fun parseCockpitMessage(lines: List<VisualLine>, ataDigits: String?): String? {\n'
if marker not in s:
    raise SystemExit('1.3.8: parseCockpitMessage marker not found')
helper = r'''    private fun parseCockpitType(lines: List<VisualLine>): String? {
        val candidate = lines.map { normalizeSpacing(it.text) }.firstOrNull(::isCockpitTypeLine) ?: return null
        val compact = candidate.replace(Regex("[^A-Z]"), "")
        return when {
            compact.contains("MAINTENANCESTATUS") || editDistance(compact, "MAINTENANCESTATUS") <= 3 -> "MAINTENANCE STATUS"
            compact.contains("STSINOPSYSTEM") -> "STS-INOP SYSTEM"
            compact.contains("EWDWARNING") || compact.contains("ECAMWARNING") -> "EWD WARNING(S)"
            compact.contains("PFD") && compact.contains("FLAG") -> "PFD FLAG(S)"
            compact.contains("ND") && compact.contains("FLAG") -> "ND FLAG(S)"
            compact.contains("SD") && compact.contains("FLAG") -> "SD FLAG(S)"
            compact.contains("OBSERVATION") -> "OBSERVATIONS"
            compact.contains("WARNINGLIGHT") -> "WARNING LIGHT(S)"
            compact.contains("INDICATOR") -> "INDICATOR(S)"
            else -> candidate
        }
    }

'''
s = s.replace(marker, helper + marker, 1)
parser.write_text(s)

# -----------------------------------------------------------------------------
# 3) Replace the 1.3.7 editor with Airbus-style first entry point fields.
# -----------------------------------------------------------------------------
activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()

start = a.find('    private data class PfrEditorFields(')
end = a.find('    private lateinit var txtStatus', start)
if start < 0 or end < 0:
    raise SystemExit('1.3.8: PfrEditorFields boundaries not found')
new_data = r'''    private data class PfrEditorFields(
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

'''
a = a[:start] + new_data + a[end:]

# Update the explanatory OCR copy.
a = a.replace(
    'addOcrLabel("PFR STRUCTURED INPUT • OCR VALUES ARE EDITABLE")',
    'addOcrLabel("FIRST ENTRY POINT • OCR VALUES ARE EDITABLE")'
)
a = a.replace(
    'addOcrInfo("Review every field before troubleshooting. OCR is an input aid only; the values in these boxes are the values sent to MEL/TSM.")',
    'addOcrInfo("OCR separates ECAM / Cockpit Status / CMS fields. Empty sections stay empty. Review or correct the fields before troubleshooting.")'
)

start = a.find('    private fun renderPfrEditor(')
end = a.find('    private fun handleOcrText(text: String) {', start)
if start < 0 or end < 0:
    raise SystemExit('1.3.8: editor function boundaries not found')

replacement = r'''    private fun renderPfrEditor(seed: PostFlightAnalyzer.Fault?, rawOcr: String, eventNumber: Int) {
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
            text = "PFR EVENT $eventNumber • FIRST ENTRY POINT"
            setTextColor(Color.parseColor("#86C8F4"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "OCR fills only fields actually identified in this PFR row. Correct any field before SEARCH / TROUBLESHOOT."
            setTextColor(Color.parseColor("#8298AA"))
            textSize = 9f
            setPadding(0, dp(3), 0, dp(6))
        })

        val seedType = seed?.cockpitType.orEmpty()
        val ecamSeed = if (isEcamCockpitType(seedType)) seed?.cockpitEffect.orEmpty() else ""
        val statusSeed = if (ecamSeed.isBlank()) seed?.cockpitEffect.orEmpty() else ""

        // Airbus first-entry-point: ECAM warning is independent from CMS.
        val ecam = pfrSection(root, "ECAM WARNING SEARCH")
        val ecamMessage = pfrField(ecam, "MESSAGE", ecamSeed, "Leave blank when no ECAM warning is present", multiline = true)

        // Some PFR rows are Maintenance Status / flags / observations rather than ECAM warnings.
        val cockpit = pfrSection(root, "COCKPIT EFFECT / STATUS")
        val cockpitAta = pfrField(cockpit, "ATA REF", seed?.cockpitAta.orEmpty(), "e.g. 27-90; blank if not printed")
        val cockpitType = pfrField(cockpit, "TYPE", seedType, "e.g. MAINTENANCE STATUS")
        val cockpitStatus = pfrField(cockpit, "MESSAGE / STATUS", statusSeed, "e.g. EFCS 1; blank when ECAM field above is used", multiline = true)

        // CMS Fault Message search mirrors Airbus CH / SE / SU entry fields.
        val cms = pfrSection(root, "CMS FAULT MESSAGE SEARCH")
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
            text = "SEARCH / TROUBLESHOOT VERIFIED FIELDS"
            textSize = 11f
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
            text = "Supported independently: ECAM + CMS • ECAM only • CMS only • Cockpit/Maintenance Status with or without CMS. Flight phase/UTC are not search inputs."
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
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(8))
            setBackgroundColor(Color.parseColor("#101E29"))
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
            setTextColor(Color.parseColor("#8EA7BA"))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
        })
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#657C90"))
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

'''
a = a[:start] + replacement + a[end:]
activity.write_text(a)

# -----------------------------------------------------------------------------
# 4) Exact TSM index matching: for cockpit-only rows, use cockpit TYPE when it
#    is available so Maintenance Status is not confused with an unrelated entry.
# -----------------------------------------------------------------------------
engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()

old = '''            val qAta = input.cockpitAta ?: input.maintenanceAta\n            val ataOk = qAta.isNullOrBlank() || record.cockpitAta.isBlank() || normalizeAta(qAta).take(4) == normalizeAta(record.cockpitAta).take(4)\n            return msgOk && ataOk\n'''
new = '''            val qAta = input.cockpitAta ?: input.maintenanceAta\n            val ataOk = qAta.isNullOrBlank() || record.cockpitAta.isBlank() || normalizeAta(qAta).take(4) == normalizeAta(record.cockpitAta).take(4)\n            val typeOk = cockpitTypeMatches(input, record)\n            return msgOk && ataOk && typeOk\n'''
if old not in e:
    raise SystemExit('1.3.8: acceptable no-fault index anchor not found')
e = e.replace(old, new, 1)

old = '''        if (!record.hasFaultMessage) {\n            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage\n            return normalizeText(q) == normalizeText(record.cockpitMessage)\n        }\n'''
new = '''        if (!record.hasFaultMessage) {\n            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage\n            return normalizeText(q) == normalizeText(record.cockpitMessage) && cockpitTypeMatches(input, record)\n        }\n'''
if old not in e:
    raise SystemExit('1.3.8: exact no-fault index anchor not found')
e = e.replace(old, new, 1)

marker = '    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {\n'
if marker not in e:
    raise SystemExit('1.3.8: acceptableIndexMatch marker not found')
helper = r'''    private fun cockpitTypeMatches(input: FaultInput, record: TsmIndexRecord): Boolean {
        val requested = input.parsedFault?.cockpitType.orEmpty().trim()
        if (requested.isBlank() || record.cockpitType.isBlank()) return true
        fun canonical(value: String): String {
            val compact = normalizeText(value).replace(Regex("[^A-Z]"), "")
            return when {
                compact.contains("MAINTENANCESTATUS") || compact.contains("MAINTSTATUS") -> "MAINTENANCESTATUS"
                compact.contains("EWDWARNING") || compact.contains("ECAMWARNING") -> "EWDWARNING"
                compact.contains("STSINOPSYSTEM") -> "STSINOPSYSTEM"
                compact.contains("PFDFLAG") -> "PFDFLAG"
                compact.contains("NDFLAG") -> "NDFLAG"
                compact.contains("SDFLAG") -> "SDFLAG"
                else -> compact
            }
        }
        return canonical(requested) == canonical(record.cockpitType)
    }

'''
e = e.replace(marker, helper + marker, 1)

# Strong PFR photo gate inserted by 1.3.1: type is also evidence for cockpit-only records.
old = '''        if (!record.hasFaultMessage) {\n            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage\n            return ocrMessageSimilarity(q, record.cockpitMessage) >= 0.94\n        }\n'''
new = '''        if (!record.hasFaultMessage) {\n            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage\n            return ocrMessageSimilarity(q, record.cockpitMessage) >= 0.94 && cockpitTypeMatches(input, record)\n        }\n'''
if old not in e:
    raise SystemExit('1.3.8: strong PFR no-fault gate anchor not found')
e = e.replace(old, new, 1)

e = e.replace(
    'LINE ASSISTANT 1.3.7 • EDITABLE PFR FORM + EXACT TSM WORKFLOW',
    'LINE ASSISTANT 1.3.8 • AIRBUS ENTRY POINT + EXACT TSM WORKFLOW'
)
engine.write_text(e)

# -----------------------------------------------------------------------------
# 5) Version bump.
# -----------------------------------------------------------------------------
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 35' not in g or 'versionName = "1.3.7"' not in g:
    raise SystemExit('1.3.8: expected 1.3.7 version metadata not found')
g = g.replace('versionCode = 35', 'versionCode = 36', 1)
g = g.replace('versionName = "1.3.7"', 'versionName = "1.3.8"', 1)
gradle.write_text(g)

print('Line Assistant 1.3.8 Airbus first-entry-point ECAM/CMS classification applied')
