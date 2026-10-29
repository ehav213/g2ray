from pathlib import Path

activity_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity_path.read_text()

old = '        val parsed = PfrGeometryAnalyzer.analyze(result)\n'
new = '        val parsed = PfrGeometryAnalyzerV2.analyze(result)\n'
if old not in a:
    raise SystemExit('1.3 patch: PfrGeometryAnalyzer call not found')
a = a.replace(old, new, 1)

a = a.replace(
    'PFR TABLE EVENTS • ${parsed.faults.size} • GEOMETRY VERIFIED',
    'PFR TABLE EVENTS • ${parsed.faults.size} • UTC-ROW GEOMETRY VERIFIED'
)

old = '''        // Resolve the first real CMS fault message first. If the PFR only has Warning/Malfunction entries, use the first one.
        val primary = parsed.faults.firstOrNull { it.category == "CMS FAULT MESSAGE" } ?: parsed.faults.first()
        resolvePfrFault(primary, "PFR EVENT ${primary.number}")
'''
new = '''        // Never choose an arbitrary event from a multi-event PFR.
        val cmsFaults = parsed.faults.filter { it.category == "CMS FAULT MESSAGE" }
        when {
            cmsFaults.size == 1 -> resolvePfrFault(cmsFaults.first(), "PFR EVENT ${cmsFaults.first().number}")
            cmsFaults.isEmpty() && parsed.faults.size == 1 -> resolvePfrFault(parsed.faults.first(), "PFR EVENT ${parsed.faults.first().number}")
            else -> status("PFR OCR • ${parsed.faults.size} VERIFIED ROWS • SELECT EVENT")
        }
'''
if old not in a:
    raise SystemExit('1.3 patch: PFR auto-resolve block not found')
a = a.replace(old, new, 1)
activity_path.write_text(a)

engine_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = engine_path.read_text()

old = '        val matched = candidates.filter { acceptableIndexMatch(it, input) }\n'
new = '        val matched = candidates.filter { acceptableIndexMatch(it, input) && pfrPhotoMatchIsStrong(it, input) }\n'
if old not in s:
    raise SystemExit('1.3 patch: matched candidates anchor not found')
s = s.replace(old, new, 1)

marker = '    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {\n'
if marker not in s:
    raise SystemExit('1.3 patch: acceptableIndexMatch marker not found')

helper = '''    private fun pfrPhotoMatchIsStrong(record: TsmIndexRecord, input: FaultInput): Boolean {
        if (input.parsedFault == null) return true

        if (!record.hasFaultMessage) {
            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            return ocrMessageSimilarity(q, record.cockpitMessage) >= 0.94
        }

        val requestedAta = input.maintenanceAta?.let(::normalizeAta).orEmpty()
        if (requestedAta.length >= 6 && normalizeAta(record.maintenanceAta).take(6) != requestedAta.take(6)) return false

        if (!input.source.isNullOrBlank() && record.source.isNotBlank() &&
            normalizeToken(input.source) != normalizeToken(record.source)) return false

        if (!input.faultClass.isNullOrBlank() && record.faultClass.isNotBlank() &&
            input.faultClass.trim() != record.faultClass.trim()) return false

        val inputIds = extractIdentifiers(input.maintenanceMessage)
            .map(::normalizeToken)
            .filter { it.length >= 3 }
            .distinct()
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        if (inputIds.isNotEmpty() && inputIds.any { !containsWholeToken(recordText, it) }) return false

        return ocrMessageSimilarity(input.maintenanceMessage, record.maintenanceMessage) >= 0.94
    }

    private fun ocrMessageSimilarity(aRaw: String, bRaw: String): Double {
        val a = normalizeText(aRaw).replace(Regex("[^A-Z0-9]"), "")
        val b = normalizeText(bRaw).replace(Regex("[^A-Z0-9]"), "")
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (minOf(a.length, b.length) < 8) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length).toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val temp = previous
            previous = current
            current = temp
        }
        return previous[b.length]
    }

'''
s = s.replace(marker, helper + marker, 1)
s = s.replace(
    'LINE ASSISTANT 1.1.1 • DATABASE-NATIVE WORKFLOW',
    'LINE ASSISTANT 1.3 • PFR-FIRST EXACT TSM WORKFLOW'
)
engine_path.write_text(s)
print('Line Assistant 1.3 strict PFR/TSM patch applied')
