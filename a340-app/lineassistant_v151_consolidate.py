from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
ACT = ROOT/'app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt'
ENG = ROOT/'app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt'
GRADLE = ROOT/'app/build.gradle.kts'
REG = ROOT/'verify_lineassistant_regressions.py'
WF = ROOT.parent/'.github/workflows/a340-apk-build.yml'


def between(text, start, end, replacement, label):
    a=text.find(start); b=text.find(end, a+len(start))
    if a<0 or b<0: raise SystemExit(f'{label}: boundary not found')
    return text[:a]+replacement+text[b:]

# Activity
s=ACT.read_text()
if 'PfrOcrPipeline.recognize(this, uri)' not in s:
    s=s.replace('import android.graphics.Color\n','import android.graphics.Bitmap\nimport android.graphics.Color\n',1)
    anchor='''                    activityMenuIconColor = Color.parseColor("#0A84FF"),\n                    outputCompressQuality = 100'''
    repl='''                    activityMenuIconColor = Color.parseColor("#0A84FF"),\n                    allowRotation = true,\n                    allowCounterRotation = true,\n                    rotationDegrees = 1,\n                    outputCompressFormat = Bitmap.CompressFormat.PNG,\n                    outputCompressQuality = 100'''
    if anchor not in s: raise SystemExit('crop options anchor missing')
    s=s.replace('Crop PFR — tap USE','Crop / Straighten PFR — tap USE').replace('Crop image — tap USE','Crop / Straighten image — tap USE')
    s=s.replace(anchor,repl,1)
    ocr='''    private fun ocr(uri: Uri) {
        if (photoMode == PhotoMode.PFR) {
            clearResults(); ocrContainer.removeAllViews(); ocrContainer.visibility = View.VISIBLE
            addOcrLabel("PFR OCR • READING CONFIRMED CROP")
            addOcrInfo("The confirmed crop is the OCR source. Residual angle is auto-deskewed and a contrast-enhanced second pass is compared before fields are populated.")
            status("PFR OCR • CROP + AUTO-DESKEW + ENHANCED PASS…")
            io.execute {
                runCatching { PfrOcrPipeline.recognize(this, uri) }
                    .onSuccess { outcome -> ui {
                        appLog.i("PFR_OCR_PIPELINE", "${outcome.note} | raw=${outcome.rawScore} enhanced=${outcome.enhancedScore}")
                        handlePfrOcr(outcome.text, outcome.note)
                    } }
                    .onFailure { e -> ui { status("OCR FAILED • CHECK LOG"); appLog.e("OCR_FAIL", e) } }
            }
            return
        }
        status("OCR • READING IMAGE…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image -> recognizer.process(image)
                .addOnSuccessListener { result -> handleOcrText(result.text.trim()) }
                .addOnFailureListener { e -> status("OCR FAILED • CHECK LOG"); appLog.e("OCR_FAIL", e) }
                .addOnCompleteListener { recognizer.close() } }
            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }

'''
    s=between(s,'    private fun ocr(uri: Uri) {','    private fun handlePfrOcr',ocr,'ocr function')
    s=s.replace('private fun handlePfrOcr(result: Text) {','private fun handlePfrOcr(result: Text, processingNote: String = "") {',1)
    needle='        addOcrLabel("FIRST ENTRY POINT • OCR VALUES ARE EDITABLE")\n'
    if needle not in s: raise SystemExit('OCR label anchor missing')
    s=s.replace(needle,needle+'        if (processingNote.isNotBlank()) addOcrInfo(processingNote)\n',1)
    s=s.replace('        renderSourcePacket(result.hits)\n','        renderSourcePacket(result)\n',1)
    packet='''    private fun renderSourcePacket(result: LineAssistantResult) =
        renderSourcePacket(result.hits, showSupportStatus = result.tsm != null)

    private fun renderSourcePacket(hits: List<SearchHit>) =
        renderSourcePacket(hits, showSupportStatus = false)

    private fun renderSourcePacket(hits: List<SearchHit>, showSupportStatus: Boolean) {
        sourceContainer.removeAllViews(); txtSourcesHeading.text = "VERIFIED ORIGINAL SOURCES"
        if (hits.isEmpty() && !showSupportStatus) return
        txtSourcesHeading.visibility=View.VISIBLE; sourceContainer.visibility=View.VISIBLE
        val order=listOf("1 • MEL EXACT ENTRY","1B • MEL REFERENCED ITEM","1C • MEL OPERATIONAL PROCEDURE","2 • TSM EXACT TASK","3 • AMM REFERENCED TASK","4 • ASM REFERENCED SCHEMATIC","5 • AWM EXACT IDENTIFIER / DIAGRAM","6 • IPC PART / FIN","7 • ESPM INFO ONLY")
        val groups=hits.groupBy(::stageLabel)
        order.forEach { stage ->
            val rows=groups[stage].orEmpty()
            if (rows.isNotEmpty()) { sourceContainer.addView(sectionHeader(stage)); rows.forEach { sourceContainer.addView(sourceCard(it)) } }
            else if (showSupportStatus && stage.startsWith("5 • AWM")) { sourceContainer.addView(sectionHeader(stage)); sourceContainer.addView(unresolvedSourceCard("AWM","No exact AWM diagram or identifier was verified from the selected TSM path. Broad ATA/wiring guessing is suppressed.")) }
            else if (showSupportStatus && stage.startsWith("6 • IPC")) { sourceContainer.addView(sectionHeader(stage)); sourceContainer.addView(unresolvedSourceCard("IPC","No exact IPC FIN or part number was verified from the selected TSM path. Broad parts guessing is suppressed.")) }
        }
        groups.filterKeys { it !in order }.forEach { (stage,rows) -> sourceContainer.addView(sectionHeader(stage)); rows.forEach { sourceContainer.addView(sourceCard(it)) } }
    }

    private fun unresolvedSourceCard(manual:String, reason:String):View {
        val card=MaterialCardView(this).apply { radius=dp(12).toFloat(); strokeWidth=dp(1); strokeColor=Color.parseColor("#D1D1D6"); setCardBackgroundColor(Color.parseColor("#F8F8FA")); layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(8) } }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(11),dp(12),dp(11)) }
        box.addView(TextView(this).apply { text="$manual • NOT EXACTLY RESOLVED"; setTextColor(Color.parseColor("#6E6E73")); textSize=11f; setTypeface(typeface,Typeface.BOLD) })
        box.addView(TextView(this).apply { text=reason; setTextColor(Color.parseColor("#6E6E73")); textSize=11f; setPadding(0,dp(5),0,0) })
        card.addView(box); return card
    }

'''
    s=between(s,'    private fun renderSourcePacket(','    private fun stageLabel',packet,'source packet')
ACT.write_text(s)

# Engine
s=ENG.read_text()
if 'extractReferencedPartNumbers(scoped)' not in s:
    old='''        ids += tsm.identifiers.filter { containsWholeToken(scopedNorm, normalizeToken(it)) }\n        ids.filter { it.length >= 3 }.take(18).forEach { id ->'''
    new='''        ids += tsm.identifiers.filter { containsWholeToken(scopedNorm, normalizeToken(it)) }\n        // Support manuals are seeded only by the verified selected TSM path.\n        ids += extractIdentifiers(scoped)\n        ids += extractReferencedPartNumbers(scoped)\n        ids.filter { it.length >= 3 }.take(28).forEach { id ->'''
    if old not in s: raise SystemExit('support identifier anchor missing')
    s=s.replace(old,new,1)
    idx=s.find('    private fun baseFaultMessage')
    if idx<0: raise SystemExit('baseFaultMessage missing')
    fn='''    private fun extractReferencedPartNumbers(text:String):List<String> {\n        val out=linkedSetOf<String>()\n        Regex("\\\\b(?:P/N|PN|PART\\\\s*(?:NO\\\\.?|NUMBER))\\\\s*[:#=-]?\\\\s*([A-Z0-9][A-Z0-9./-]{3,})\\\\b",RegexOption.IGNORE_CASE).findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.uppercase(Locale.US)?.trim()?.trimEnd('.',',',';',':') }.filter { it.any(Char::isDigit) }.forEach(out::add)\n        return out.take(16)\n    }\n\n'''
    s=s[:idx]+fn+s[idx:]
    old_awm='''    private fun awmIdentifierHit(id: String): SearchHit? = runCatching {
        db.rawQuery("SELECT i.id,i.token,i.kind,p.pdf_page FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id WHERE UPPER(i.token)=? ORDER BY p.pdf_page LIMIT 1",
            arrayOf(normalizeToken(id))).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("awm_identifier", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier • PDF page ${c.getInt(3)}", -4100.0)
        }
    }.getOrNull()

    private fun ipcIdentifierHit(id: String): SearchHit? = runCatching {
        db.rawQuery("SELECT COALESCE(fin,''),part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'') FROM ipc_items WHERE UPPER(COALESCE(fin,''))=? OR UPPER(part_number)=? ORDER BY pdf_page LIMIT 1",
            arrayOf(normalizeToken(id), normalizeToken(id))).use { c ->
            if (!c.moveToFirst()) return@use null
            val ref = c.getString(0).ifBlank { c.getString(1) }
            SearchHit("part_number", c.getString(1), "IPC", ref, c.getString(2), c.getString(3).take(1200), -4000.0)
        }
    }.getOrNull()
'''
    new_awm='''    private fun awmIdentifierHit(id: String): SearchHit? = runCatching {
        val raw = id.trim().uppercase(Locale.US)
        val normalized = normalizeToken(id)
        db.rawQuery(
            """SELECT i.id,i.token,i.kind,p.pdf_page
               FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id
               WHERE UPPER(i.token)=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(i.token),'-',''),'/',''),' ',''),'.','')=?
               ORDER BY p.pdf_page LIMIT 1""".trimIndent(),
            arrayOf(raw, normalized)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("awm_identifier", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier • PDF page ${c.getInt(3)}", -4100.0)
        }
    }.getOrNull()

    private fun ipcIdentifierHit(id: String): SearchHit? = runCatching {
        val raw = id.trim().uppercase(Locale.US)
        val normalized = normalizeToken(id)
        db.rawQuery(
            """SELECT COALESCE(fin,''),part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'')
               FROM ipc_items
               WHERE UPPER(COALESCE(fin,''))=?
                  OR UPPER(part_number)=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(COALESCE(fin,'')),'-',''),'/',''),' ',''),'.','')=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(part_number),'-',''),'/',''),' ',''),'.','')=?
               ORDER BY pdf_page LIMIT 1""".trimIndent(),
            arrayOf(raw, raw, normalized, normalized)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val ref = c.getString(0).ifBlank { c.getString(1) }
            SearchHit("part_number", c.getString(1), "IPC", ref, c.getString(2), c.getString(3).take(1200), -4000.0)
        }
    }.getOrNull()
'''
    if old_awm not in s: raise SystemExit('AWM/IPC exact match block missing')
    s=s.replace(old_awm,new_awm,1)
s=s.replace('LINE ASSISTANT 1.5.0 • CONSOLIDATED FAIL-CLOSED RESOLVER','LINE ASSISTANT 1.5.1 • OCR + VERIFIED SUPPORT SOURCES')
ENG.write_text(s)

# Version
s=GRADLE.read_text().replace('versionCode = 50','versionCode = 51').replace('versionName = "1.5.0"','versionName = "1.5.1"')
GRADLE.write_text(s)

# Regression guards
s=REG.read_text()
if 'ocrpipe =' not in s: s=s.replace("geom = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt').read_text()", "geom = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt').read_text()\nocrpipe = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PfrOcrPipeline.kt').read_text()")
s=s.replace("require('version is consolidated 1.5.0', 'versionName = \"1.5.0\"' in gradle and 'versionCode = 50' in gradle)","require('version is 1.5.1', 'versionName = \"1.5.1\"' in gradle and 'versionCode = 51' in gradle)")
s=s.replace("require('engine is consolidated release', 'LINE ASSISTANT 1.5.0 • CONSOLIDATED FAIL-CLOSED RESOLVER' in engine)","require('engine is 1.5.1 release', 'LINE ASSISTANT 1.5.1 • OCR + VERIFIED SUPPORT SOURCES' in engine)")
anchor="require('supportHits does not derive identifiers from message text', 'extractIdentifiers(input.maintenanceMessage)' not in support)"
if 'supportHits derives exact IDs' not in s: s=s.replace(anchor, anchor + "\nrequire('supportHits derives exact IDs from selected TSM branch only', 'extractIdentifiers(scoped)' in support and 'extractReferencedPartNumbers(scoped)' in support)\nrequire('AWM IPC identifiers use normalized exact matching', 'REPLACE(REPLACE(REPLACE(REPLACE(UPPER(i.token)' in engine and 'arrayOf(raw, raw, normalized, normalized)' in engine)")
s=re.sub(r"require\('PFR OCR clears prior free-text query'.*?\n", "require('PFR OCR clears prior free-text query', re.search(r'private fun handlePfrOcr\\(result: Text, processingNote: String = \"\"\\).*?editQuery\\.setText\\(\"\"\\)', activity, re.S) is not None)\n", s, count=1)
if 'PFR OCR uses confirmed crop enhancement pipeline' not in s:
    marker="require('PFR OCR clears prior free-text query', re.search(r'private fun handlePfrOcr\\(result: Text, processingNote: String = \"\"\\).*?editQuery\\.setText\\(\"\"\\)', activity, re.S) is not None)\n"
    s=s.replace(marker,marker+"require('PFR OCR uses confirmed crop enhancement pipeline', 'PfrOcrPipeline.recognize(this, uri)' in activity and 'estimateSkewDegrees' in ocrpipe and 'enhance(source: Bitmap' in ocrpipe)\nrequire('crop supports fine straighten angle', 'rotationDegrees = 1' in activity and 'allowCounterRotation = true' in activity and 'Bitmap.CompressFormat.PNG' in activity)\nrequire('AWM and IPC unresolved stages remain visible without guessing', 'unresolvedSourceCard(' in activity and 'Broad ATA/wiring guessing is supressed.' in activity and 'Broad parts guessing is suppressed.' in activity)\n")
REG.write_text(s)

# Main direct-source build workflow
if WF.exists():
    s=WF.read_text().replace('Build Line Assistant 1.5.0 Consolidated','Build Line Assistant 1.5.1 OCR + Verified Support Sources').replace('1.5.0','1.5.1')
    WF.write_text(s)

print('Line Assistant 1.5.1 consolidated source prepared')
