from pathlib import Path

# Line Assistant 1.4.0
# - Keep PFR IDENTIFIERS faithful to the printed Identifiers column only.
# - Use exact maintenance ATA as a primary TSM-index candidate source.
# - Keep ATA/source/class as hard gates, but tolerate one OCR-damaged CMS token
#   when the rest of the message evidence is strong.

parser = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
p = parser.read_text()
old = 'identifiers = (identifiers + extractIdentifiers(message)).distinct().take(20),'
new = 'identifiers = identifiers.distinct().take(20),'
if old not in p:
    raise SystemExit('1.4.0: parser identifier merge anchor not found')
p = p.replace(old, new, 1)
parser.write_text(p)

engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()

# Add an exact-ATA candidate pass before text FTS. Only index blocks survive parseTsmIndexRecords.
anchor = '''        val candidates = mutableListOf<TsmIndexRecord>()\n        val dedupe = linkedSetOf<String>()\n        for (expr in expressions.filter { it.isNotBlank() }) {\n'''
replacement = '''        val candidates = mutableListOf<TsmIndexRecord>()\n        val dedupe = linkedSetOf<String>()\n\n        val exactAta = input.maintenanceAta?.trim().orEmpty()\n        if (exactAta.matches(Regex("\\\\d{2}-\\\\d{2}-\\\\d{2}"))) {\n            val sqlAta = """\n                SELECT c.document_filename,c.pdf_page,c.content\n                FROM chunks c\n                WHERE UPPER(COALESCE(c.manual,''))='TSM'\n                  AND UPPER(c.content) LIKE ?\n                ORDER BY c.pdf_page LIMIT 240\n            """.trimIndent()\n            runCatching {\n                db.rawQuery(sqlAta, arrayOf("%${exactAta.uppercase(Locale.US)}%")).use { c ->\n                    while (c.moveToNext()) {\n                        parseTsmIndexRecords(c.getString(0), c.getInt(1), c.getString(2)).forEach { record ->\n                            val key = "${record.indexFilename}|${record.indexPage}|${record.indexTaskRef}|${record.maintenanceMessage}|${record.cockpitMessage}|${record.source}"\n                            if (dedupe.add(key)) candidates += record\n                        }\n                    }\n                }\n            }\n        }\n\n        for (expr in expressions.filter { it.isNotBlank() }) {\n'''
if anchor not in e:
    raise SystemExit('1.4.0: TSM candidate anchor not found')
e = e.replace(anchor, replacement, 1)

start = e.find('    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {')
end = e.find('    private fun exactIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {', start)
if start < 0 or end < 0:
    raise SystemExit('1.4.0: acceptableIndexMatch boundaries not found')
old_fn = e[start:end]
new_fn = r'''    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {
        if (!record.hasFaultMessage) {
            val query = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            val qWords = significantWords(query)
            val hay = normalizeText(record.cockpitMessage)
            val msgOk = qWords.isNotEmpty() && qWords.all { containsWholeToken(hay, it) }
            val qAta = input.cockpitAta ?: input.maintenanceAta
            val ataOk = qAta.isNullOrBlank() || record.cockpitAta.isBlank() || normalizeAta(qAta).take(4) == normalizeAta(record.cockpitAta).take(4)
            val typeOk = cockpitTypeMatches(input, record)
            return msgOk && ataOk && typeOk
        }

        // Maintenance ATA is the strongest field printed in the CMS row: never relax it.
        val ataOk = input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)
        if (!ataOk) return false

        // Source and class are also structured CMS fields. If both sides have them they must agree.
        if (!input.source.isNullOrBlank() && record.source.isNotBlank() && normalizeToken(input.source) != normalizeToken(record.source)) return false
        if (!input.faultClass.isNullOrBlank() && record.faultClass.isNotBlank() && input.faultClass.trim() != record.faultClass.trim()) return false

        // Only identifiers actually printed in the PFR Identifiers column are a gate.
        // Message-internal FINs are evidence, not mandatory exact identifiers.
        val printedIds = input.identifiers.map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val recordEvidence = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        if (printedIds.isNotEmpty() && printedIds.none { containsWholeToken(recordEvidence, it) }) return false

        if (!cmsMessageCompatible(input.maintenanceMessage, record.maintenanceMessage)) return false
        return true
    }

    private fun cmsMessageCompatible(query: String, candidate: String): Boolean {
        val q = baseFaultMessage(query)
        val c = baseFaultMessage(candidate)
        if (q == c) return true

        val qWords = significantWords(q).map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val cNorm = normalizeText(c)
        if (qWords.isEmpty()) return false
        val exactWordMatches = qWords.count { containsWholeToken(cNorm, it) }
        val coverage = exactWordMatches.toDouble() / qWords.size.toDouble()

        // FIN/equipment references such as 2CE2/67CE2 are strong independent evidence.
        val qRefs = strongCmsRefs(q)
        val cRefs = strongCmsRefs(c)
        val refOverlap = qRefs.intersect(cRefs).size

        // One OCR-damaged token is acceptable only when most of the message agrees,
        // or when at least two independent hardware references agree.
        return coverage >= 0.66 || (coverage >= 0.50 && refOverlap >= 1) || refOverlap >= 2
    }

    private fun strongCmsRefs(text: String): Set<String> {
        val upper = normalizeText(text)
        val out = linkedSetOf<String>()
        Regex("\\b\\d{1,3}[A-Z]{2,5}\\d{0,3}\\b").findAll(upper).forEach { out += normalizeToken(it.value) }
        Regex("\\b(?:FCPC|FCSC|FCDC|LGCIU|BSCU|BMC|FMGEC|SFCC|SDAC|FWC|DMU|FDIMU)\\d*\\b").findAll(upper).forEach { out += normalizeToken(it.value) }
        return out
    }

'''
e = e[:start] + new_fn + e[end:]

e = e.replace(
    'LINE ASSISTANT 1.3.9 • EXACT ECAM/CMS ENTRY POINT + TSM WORKFLOW',
    'LINE ASSISTANT 1.4.0 • STRUCTURED CMS + ROBUST EXACT-ATA TSM WORKFLOW'
)
engine.write_text(e)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 37' not in g or 'versionName = "1.3.9"' not in g:
    raise SystemExit('1.4.0: expected 1.3.9 version metadata not found')
g = g.replace('versionCode = 37', 'versionCode = 38', 1)
g = g.replace('versionName = "1.3.9"', 'versionName = "1.4.0"', 1)
gradle.write_text(g)

print('Line Assistant 1.4.0 robust CMS/TSM index matching applied')
