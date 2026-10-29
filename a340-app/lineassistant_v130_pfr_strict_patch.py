from pathlib import Path

activity_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity_path.read_text()

old = '        val parsed = PfrGeometryAnalyzer.analyze(result)\n'
new = '        val parsed = PfrGeometryAnalyzerV2.analyze(result)\n'
if old not in a:
    raise SystemExit('1.3.1 patch: PfrGeometryAnalyzer call not found')
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
    raise SystemExit('1.3.1 patch: PFR auto-resolve block not found')
a = a.replace(old, new, 1)
activity_path.write_text(a)

engine_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = engine_path.read_text()

old = '        val matched = candidates.filter { acceptableIndexMatch(it, input) }\n'
new = '        val matched = candidates.filter { acceptableIndexMatch(it, input) && pfrPhotoMatchIsStrong(it, input) }\n'
if old not in s:
    raise SystemExit('1.3.1 patch: matched candidates anchor not found')
s = s.replace(old, new, 1)

marker = '    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {\n'
if marker not in s:
    raise SystemExit('1.3.1 patch: acceptableIndexMatch marker not found')

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

    // DB-aware OCR comparison. The reference side is the trusted TSM index text.
    // A slash may be misread by OCR as I/L/|/1, but those characters are never
    // globally rewritten. They are treated as equivalent only when aligned to
    // an actual '/' in the verified TSM reference.
    private fun ocrMessageSimilarity(observedRaw: String, referenceRaw: String): Double {
        val observed = ocrComparable(observedRaw)
        val reference = ocrComparable(referenceRaw)
        if (observed.isBlank() || reference.isBlank()) return 0.0
        if (observed == reference) return 1.0
        if (minOf(observed.length, reference.length) < 8) return 0.0
        val distance = referenceAwareLevenshtein(observed, reference)
        return 1.0 - distance.toDouble() / maxOf(observed.length, reference.length).toDouble()
    }

    private fun ocrComparable(raw: String): String = normalizeText(raw)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[^A-Z0-9/|]"), "")

    private fun referenceAwareLevenshtein(observed: String, reference: String): Int {
        var previous = IntArray(reference.length + 1) { it }
        var current = IntArray(reference.length + 1)
        for (i in 1..observed.length) {
            current[0] = i
            for (j in 1..reference.length) {
                val o = observed[i - 1]
                val r = reference[j - 1]
                val separatorAlias = r == '/' && (o == 'I' || o == 'L' || o == '|' || o == '1')
                val cost = if (o == r || separatorAlias) 0 else 1
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
        return previous[reference.length]
    }

'''
s = s.replace(marker, helper + marker, 1)

# The legacy word-by-word gate would reject a PFR message before the DB-aware
# slash correction had a chance to run. For photo/PFR input, use the same
# reference-aware similarity against the actual TSM record; manual text input
# keeps the stricter original behavior.
old_message_gate = '''        val messageOk = a == b || (words.isNotEmpty() && words.all { containsWholeToken(normalizeText(b), it) })\n'''
new_message_gate = '''        val messageOk = if (input.parsedFault != null) {\n            ocrMessageSimilarity(a, b) >= 0.94\n        } else {\n            a == b || (words.isNotEmpty() && words.all { containsWholeToken(normalizeText(b), it) })\n        }\n'''
if old_message_gate not in s:
    raise SystemExit('1.3.1 patch: acceptableIndex message gate not found')
s = s.replace(old_message_gate, new_message_gate, 1)

# Once a real TSM index row is verified, use its canonical message/identifiers
# for Fault Confirmation and branch selection. This repairs OCR separator errors
# without altering genuine I/L characters elsewhere in the message.
old_branch = '''        val indexedTask = findTaskRow("TSM", record.indexTaskRef) ?: return null\n        val indexedContent = exactTaskContent(indexedTask)\n        var branch = selectTroubleshootingBranch(indexedContent, input)\n        var activeTask = indexedTask\n        var activeContent = indexedContent\n'''
new_branch = '''        val indexedTask = findTaskRow("TSM", record.indexTaskRef) ?: return null\n        val indexedContent = exactTaskContent(indexedTask)\n        val branchInput = if (record.hasFaultMessage && input.parsedFault != null &&\n            ocrMessageSimilarity(input.maintenanceMessage, record.maintenanceMessage) >= 0.94) {\n            input.copy(\n                maintenanceMessage = record.maintenanceMessage,\n                identifiers = (input.identifiers + record.identifiers + extractIdentifiers(record.maintenanceMessage)).distinct()\n            )\n        } else input\n        var branch = selectTroubleshootingBranch(indexedContent, branchInput)\n        var activeTask = indexedTask\n        var activeContent = indexedContent\n'''
if old_branch not in s:
    raise SystemExit('1.3.1 patch: branch input anchor not found')
s = s.replace(old_branch, new_branch, 1)
s = s.replace('branch = selectTroubleshootingBranch(activeContent, input)',
              'branch = selectTroubleshootingBranch(activeContent, branchInput)', 1)

s = s.replace(
    'LINE ASSISTANT 1.1.1 • DATABASE-NATIVE WORKFLOW',
    'LINE ASSISTANT 1.3.1 • PFR-FIRST EXACT TSM WORKFLOW'
)
engine_path.write_text(s)

# Version bump is applied at build time so the installed APK clearly identifies
# the separator-aware OCR build.
gradle_path = Path('app/build.gradle.kts')
g = gradle_path.read_text()
g = g.replace('versionCode = 28', 'versionCode = 29')
g = g.replace('versionName = "1.3.0"', 'versionName = "1.3.1"')
gradle_path.write_text(g)

print('Line Assistant 1.3.1 DB-aware slash OCR patch applied')
