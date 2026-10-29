from pathlib import Path

activity_path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity_path.read_text()

old = '        val parsed = PfrGeometryAnalyzer.analyze(result)\n'
new = '        val parsed = PfrGeometryAnalyzerV2.analyze(result)\n'
if old not in a:
    raise SystemExit('1.3 patch: PfrGeometryAnalyzer call not found')
a = a.replace(old, new, 1)

a = a.replace('PFR TABLE EVENTS • ${parsed.faults.size} • GEOMETRY VERIFIED',
              'PFR TABLE EVENTS • ${parsed.faults.size} • UTC-ROW GEOMETRY VERIFIED')

old = '''        // Resolve the first real CMS fault message first. If the PFR only has Warning/Malfunction entries, use the first one.\n        val primary = parsed.faults.firstOrNull { it.category == "CMS FAULT MESSAGE" } ?: parsed.faults.first()\n        resolvePfrFault(primary, "PFR EVENT ${primary.number}")\n'''
new = '''        // Never choose an arbitrary event from a multi-event PFR. Auto-resolve only when there is exactly one\n        // CMS maintenance fault. Otherwise the engineer selects the printed event row explicitly.\n        val cmsFaults = parsed.faults.filter { it.category == "CMS FAULT MESSAGE" }\n        when {\n            cmsFaults.size == 1 -> resolvePfrFault(cmsFaults.first(), "PFR EVENT ${cmsFaults.first().number}")\n            cmsFaults.isEmpty() && parsed.faults.size == 1 -> resolvePfrFault(parsed.faults.first(), "PFR EVENT ${parsed.faults.first().number}")\n            else -> status("PFR OCR • ${parsed.faults.size} VERIFIED ROWS • SELECT EVENT")\n        }\n'''
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
helper = '''    private fun pfrPhotoMatchIsStrong(record: TsmIndexRecord, input: FaultInput): Boolean {\n        if (input.parsedFault == null) return true\n\n        if (!record.hasFaultMessage) {\n            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage\n            return ocrMessageSimilarity(q, record.cockpitMessage) >= 0.94\n        }\n\n        val requestedAta = input.maintenanceAta?.let(::normalizeAta).orEmpty()\n        if (requestedAta.length >= 6 && normalizeAta(record.maintenanceAta).take(6) != requestedAta.take(6)) return false\n\n        if (!input.source.isNullOrBlank() && record.source.isNotBlank() &&\n            normalizeToken(input.source) != normalizeToken(record.source)) return false\n\n        if (!input.faultClass.isNullOrBlank() && record.faultClass.isNotBlank() &&\n            input.faultClass.trim() != record.faultClass.trim()) return false\n\n        val inputIds = extractIdentifiers(input.maintenanceMessage).map(::normalizeToken).filter { it.length >= 3 }.distinct()\n        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))\n        if (inputIds.isNotEmpty() && inputIds.any { !containsWholeToken(recordText, it) }) return false\n\n        val similarity = ocrMessageSimilarity(input.maintenanceMessage, record.maintenanceMessage)\n        return similarity >= 0.94\n    }\n\n    private fun ocrMessageSimilarity(aRaw: String, bRaw: String): Double {\n        val a = normalizeText(aRaw).replace(Regex("[^A-Z0-9]"), "")\n        val b = normalizeText(bRaw).replace(Regex("[^A-Z0-9]"), "")\n        if (a.isBlank() || b.isBlank()) return 0.0\n        if (a == b) return 1.0\n        if (minOf(a.length, b.length) < 8) return 0.0\n        val distance = levenshtein(a, b)\n        return 1.0 - distance.toDouble() / maxOf(a.length, b.length).toDouble()\n    }\n\n    private fun levenshtein(a: String, b: String): Int {\n        var previous = IntArray(b.length + 1) { it }\n        var current = IntArray(b.length + 1)\n        for (i in 1..a.length) {\n            current[0] = i\n            for (j in 1..b.length) {\n                val cost = if (a[i - 1] == b[j - 1]) 0 else 1\n                current[j] = minOf(\n                    current[j - 1] + 1,\n                    previous[j] + 1,\n                    previous[j - 1] + cost\n                )\n            }\n            val temp = previous\n            previous = current\n            current = temp\n        }\n        return previous[b.length]\n    }\n\n'''
s = s.replace(marker, helper + marker, 1)

# If a PFR task did not resolve to a selected troubleshooting branch, do not spray every AMM/ASM\n# reference from the whole task into the source packet. The TSM task itself remains visible.\nold = '        } ?: tsm.taskContent\n'
new = '        } ?: if (input.parsedFault != null) "" else tsm.taskContent\n'
if old not in s:
    raise SystemExit('1.3 patch: branch-scoped support fallback not found')
s = s.replace(old, new, 1)

s = s.replace('LINE ASSISTANT 1.1.1 • DATABASE-NATIVE WORKFLOW', 'LINE ASSISTANT 1.3 • PFR-FIRST EXACT TSM WORKFLOW')
engine_path.write_text(s)
print('Line Assistant 1.3 strict PFR/TSM patch applied')
