from pathlib import Path

# Line Assistant 1.4.4
# - Accept cropped cockpit-only PFR rows (e.g. MAINTENANCE STATUS) when the right/CMS column is blank.
# - Keep this path fail-closed: require cockpit ATA + valid UTC + recognized cockpit type + cockpit message
#   and at least flight phase text or a valid phase code.
# - Clear stale manual first-entry fields as soon as a confirmed crop enters OCR.
# - Correct deterministic result version header.

parser = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
s = parser.read_text()

anchor = '''        if (rightAnchors.isEmpty() && leftAnchors.isEmpty()) return emptyResult(raw)\n\n        val leftX = median(leftAnchors.map { it.cx }) ?: (pageLeft + pageWidth * 0.10f)\n'''
replacement = '''        if (rightAnchors.isEmpty() && leftAnchors.isEmpty()) return emptyResult(raw)\n\n        // Cropped cockpit-only PFR row: the right/CMS side may legitimately be blank.\n        // Do not require a 6-digit maintenance ATA in that case. Prove the row from\n        // independent cockpit and middle-column evidence, then return only the fields\n        // that are actually present on the printout.\n        if (rightAnchors.isEmpty() && leftAnchors.isNotEmpty()) {\n            analyzeCockpitOnlyCrop(raw, words, leftAnchors, h, pageWidth, pageBottom)?.let { return it }\n        }\n\n        val leftX = median(leftAnchors.map { it.cx }) ?: (pageLeft + pageWidth * 0.10f)\n'''
if anchor not in s:
    raise SystemExit('1.4.4: cockpit-only insertion anchor not found')
s = s.replace(anchor, replacement, 1)

marker = '''    private fun emptyResult(raw: String) = PostFlightAnalyzer.Result(\n'''
if marker not in s:
    raise SystemExit('1.4.4: emptyResult marker not found')
helper = r'''    private fun analyzeCockpitOnlyCrop(
        raw: String,
        words: List<Word>,
        leftAnchors: List<AtaAnchor>,
        h: Int,
        pageWidth: Int,
        pageBottom: Int
    ): PostFlightAnalyzer.Result? {
        val rowAnchors = dedupeAnchors(leftAnchors, h).sortedBy { it.cy }
        if (rowAnchors.isEmpty()) return null

        val faults = mutableListOf<PostFlightAnalyzer.Fault>()
        rowAnchors.forEachIndexed { index, rowAnchor ->
            val next = rowAnchors.getOrNull(index + 1)
            val top = (rowAnchor.cy - h * 1.6f).toInt().coerceAtLeast(0)
            val bottom = next?.let { (it.cy - h * 0.45f).toInt() } ?: (pageBottom + h)
            val rowWords = words.filter { it.cy >= top && it.cy < bottom }
            if (rowWords.isEmpty()) return@forEachIndexed

            // ATA 2790 is not a valid UTC (27:90), while 1828 is. Use a real UTC to prove
            // the middle column and establish the cockpit/middle split without inventing a right column.
            val utcWord = rowWords
                .filter { it.cx > rowAnchor.cx + pageWidth * 0.05f }
                .mapNotNull { w -> parseUtc(w.text)?.let { utc -> w to utc } }
                .minByOrNull { it.first.cx }
                ?: return@forEachIndexed

            val cockpitEnd = (rowAnchor.cx + utcWord.first.cx) / 2f
            if (cockpitEnd <= rowAnchor.cx) return@forEachIndexed

            val cockpitWords = rowWords.filter { it.cx <= cockpitEnd }
            val middleWords = rowWords.filter { it.cx > cockpitEnd }
            val cockpitAtaDigits = findAtaDigits(cockpitWords, 4, h, pageWidth) ?: rowAnchor.digits.takeIf { it.length == 4 }
            if (cockpitAtaDigits == null) return@forEachIndexed

            val cockpitLines = groupLines(cockpitWords, h)
            val middleLines = groupLines(middleWords, h)
            val cockpitType = parseCockpitType(cockpitLines) ?: return@forEachIndexed
            val cockpitMessage = parseCockpitMessage(cockpitLines, cockpitAtaDigits)?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed

            val utc = utcWord.second
            val phaseCode = middleLines.firstNotNullOfOrNull { parsePhaseCode(it.text) }
            val phase = parseFlightPhase(middleLines, utc, phaseCode)
            if (phase.isNullOrBlank() && phaseCode.isNullOrBlank()) return@forEachIndexed

            faults += PostFlightAnalyzer.Fault(
                number = faults.size + 1,
                category = "WARNING/MALFUNCTION",
                text = cockpitMessage,
                ata = null,
                identifiers = emptyList(),
                ataFull = null,
                utc = utc,
                flightPhase = phase,
                faultClass = null,
                persistence = null,
                source = null,
                cockpitEffect = cockpitMessage,
                cockpitAta = formatAta(cockpitAtaDigits),
                flightPhaseCode = phaseCode,
                cockpitType = cockpitType
            )
        }

        if (faults.isEmpty()) return null
        val first = faults.first()
        return PostFlightAnalyzer.Result(
            rawText = raw,
            context = PostFlightAnalyzer.Context(
                utc = first.utc,
                flightPhase = first.flightPhase,
                flightPhaseCode = first.flightPhaseCode,
                cockpitEffects = faults.mapNotNull { it.cockpitEffect }.distinct(),
                cockpitAta = first.cockpitAta
            ),
            faults = faults.mapIndexed { i, f -> f.copy(number = i + 1) }
        )
    }

'''
s = s.replace(marker, helper + marker, 1)
parser.write_text(s)

activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()
ocr_anchor = '''    private fun ocr(uri: Uri) {\n        status("OCR • READING IMAGE…")\n        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n'''
ocr_repl = '''    private fun ocr(uri: Uri) {\n        if (photoMode == PhotoMode.PFR) {\n            clearResults()\n            ocrContainer.removeAllViews()\n            ocrContainer.visibility = View.VISIBLE\n            addOcrLabel("PFR OCR • READING CONFIRMED CROP")\n            addOcrInfo("Previous manual entry fields were cleared. Only values detected from this cropped PFR image will be populated.")\n        }\n        status("OCR • READING IMAGE…")\n        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n'''
if ocr_anchor not in a:
    raise SystemExit('1.4.4: OCR reset anchor not found')
a = a.replace(ocr_anchor, ocr_repl, 1)
activity.write_text(a)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 41' not in g or 'versionName = "1.4.3"' not in g:
    raise SystemExit('1.4.4: expected 1.4.3 version metadata not found')
g = g.replace('versionCode = 41', 'versionCode = 42', 1)
g = g.replace('versionName = "1.4.3"', 'versionName = "1.4.4"', 1)
gradle.write_text(g)

engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()
old_header = 'LINE ASSISTANT 1.4.2 • UNIFIED FIRST ENTRY + CROP-CONFIRMED PFR WORKFLOW'
if old_header not in e:
    raise SystemExit('1.4.4: deterministic result header anchor not found')
e = e.replace(old_header, 'LINE ASSISTANT 1.4.4 • COCKPIT-ONLY PFR OCR + VERIFIED STATE RESET', 1)
engine.write_text(e)

print('Line Assistant 1.4.4 cockpit-only PFR OCR + stale-state reset applied')
