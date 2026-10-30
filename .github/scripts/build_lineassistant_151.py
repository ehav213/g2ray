from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "a340-app"
activity_path = APP / "app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt"
engine_path = APP / "app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt"
gradle_path = APP / "app/build.gradle.kts"
regression_path = APP / "verify_lineassistant_regressions.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


activity = activity_path.read_text()

activity = replace_once(
    activity,
    "import android.graphics.Color\nimport android.graphics.Typeface",
    "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\nimport android.graphics.Canvas\nimport android.graphics.Color\nimport android.graphics.ColorMatrix\nimport android.graphics.ColorMatrixColorFilter\nimport android.graphics.Matrix\nimport android.graphics.Paint\nimport android.graphics.Typeface",
    "activity graphics imports",
)
activity = replace_once(
    activity,
    "import java.util.concurrent.Executors\n",
    "import java.util.concurrent.Executors\nimport kotlin.math.abs\nimport kotlin.math.atan2\n",
    "activity math imports",
)
activity = replace_once(
    activity,
    'status("CROP CONFIRMED • OCR READING SELECTED AREA…")',
    'status("CROP + STRAIGHTEN CONFIRMED • OCR READING FINAL RENDERED IMAGE…")',
    "crop confirmation status",
)
activity = replace_once(
    activity,
    'activityTitle = if (photoMode == PhotoMode.PFR) "Crop PFR — tap USE" else "Crop image — tap USE",',
    'activityTitle = if (photoMode == PhotoMode.PFR) "Crop + Straighten PFR — adjust angle, then tap USE" else "Crop + Straighten — tap USE",',
    "crop title",
)

old_ocr = '''    private fun ocr(uri: Uri) {
        if (photoMode == PhotoMode.PFR) {
            clearResults()
            ocrContainer.removeAllViews()
            ocrContainer.visibility = View.VISIBLE
            addOcrLabel("PFR OCR • READING CONFIRMED CROP")
            addOcrInfo("Previous manual entry fields were cleared. Only values detected from this cropped PFR image will be populated.")
        }
        status("OCR • READING IMAGE…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (photoMode == PhotoMode.PFR) handlePfrOcr(result)
                        else handleOcrText(result.text.trim())
                    }
                    .addOnFailureListener { e -> status("OCR FAILED • CHECK LOG"); appLog.e("OCR_FAIL", e) }
                    .addOnCompleteListener { recognizer.close() }
            }
            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }
'''

new_ocr = '''    private fun ocr(uri: Uri) {
        if (photoMode == PhotoMode.PFR) {
            clearResults()
            ocrContainer.removeAllViews()
            ocrContainer.visibility = View.VISIBLE
            addOcrLabel("PFR OCR • FINAL CROP + STRAIGHTEN IMAGE")
            addOcrInfo("OCR uses the rendered crop returned by the editor, then runs a geometry-aware enhanced second pass. Previous manual fields are cleared.")
        }
        status("OCR • PASS 1 / READING FINAL CROP…")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { primary ->
                        if (photoMode != PhotoMode.PFR) {
                            handleOcrText(primary.text.trim())
                            recognizer.close()
                            return@addOnSuccessListener
                        }

                        io.execute {
                            val base = loadOcrBitmap(uri)
                            val skew = estimateOcrSkew(primary)
                            val corrected = when {
                                base == null -> null
                                abs(skew) >= 0.35f -> rotateOcrBitmap(base, -skew)
                                else -> base
                            }
                            val enhanced = corrected?.let(::enhanceOcrBitmap)
                            ui {
                                if (enhanced == null) {
                                    appLog.i("OCR_PFR_QUALITY", "enhancement unavailable; primary retained")
                                    handlePfrOcr(primary)
                                    recognizer.close()
                                } else {
                                    status("OCR • PASS 2 / CONTRAST + DESKEW ENHANCEMENT…")
                                    recognizer.process(InputImage.fromBitmap(enhanced, 0))
                                        .addOnSuccessListener { secondary ->
                                            val primaryScore = pfrOcrScore(primary)
                                            val secondaryScore = pfrOcrScore(secondary)
                                            val chosen = if (secondaryScore > primaryScore) secondary else primary
                                            appLog.i(
                                                "OCR_PFR_QUALITY",
                                                "primary=$primaryScore secondary=$secondaryScore skew=${"%.2f".format(skew)} chosen=${if (chosen === secondary) "enhanced" else "primary"}"
                                            )
                                            handlePfrOcr(chosen)
                                        }
                                        .addOnFailureListener { e ->
                                            appLog.e("OCR_ENHANCED_PASS_FAIL", e)
                                            handlePfrOcr(primary)
                                        }
                                        .addOnCompleteListener { recognizer.close() }
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        status("OCR FAILED • CHECK LOG")
                        appLog.e("OCR_FAIL", e)
                        recognizer.close()
                    }
            }
            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }
    }

    private fun loadOcrBitmap(uri: Uri): Bitmap? = runCatching {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun estimateOcrSkew(result: Text): Float {
        val angles = mutableListOf<Float>()
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val points = line.cornerPoints
                if (points != null && points.size >= 2) {
                    val left = points[0]
                    val right = points[1]
                    val angle = (atan2((right.y - left.y).toDouble(), (right.x - left.x).toDouble()) * 180.0 / Math.PI).toFloat()
                    if (abs(angle) <= 12f) angles += angle
                }
            }
        }
        if (angles.isEmpty()) return 0f
        angles.sort()
        return angles[angles.size / 2]
    }

    private fun rotateOcrBitmap(source: Bitmap, degrees: Float): Bitmap = runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)
    }.getOrDefault(source)

    private fun enhanceOcrBitmap(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = when {
            maxSide < 1800 -> minOf(2.0f, 1800f / maxSide.toFloat())
            maxSide > 3200 -> 3200f / maxSide.toFloat()
            else -> 1f
        }
        val scaled = if (abs(scale - 1f) > 0.01f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else source

        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.35f
        val offset = (-0.5f * contrast + 0.5f) * 255f
        gray.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(gray)
        }
        Canvas(output).drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun pfrOcrScore(result: Text): Int {
        val upper = result.text.uppercase(java.util.Locale.US)
        var score = result.text.length.coerceAtMost(1600)
        score += result.textBlocks.sumOf { it.lines.size } * 12
        listOf("MAINTENANCE STATUS", "STS-MAINTENANCE", "SOURCE", "CLASS", "MESSAGE", "ATA").forEach {
            if (upper.contains(it)) score += 90
        }
        score += Regex("\\\\b\\\\d{2}[- ]\\\\d{2}[- ]\\\\d{2}\\\\b").findAll(upper).count() * 120
        score += runCatching { PfrGeometryAnalyzerV2.analyze(result).faults.size * 700 }.getOrDefault(0)
        return score
    }
'''
activity = replace_once(activity, old_ocr, new_ocr, "OCR pipeline")
activity_path.write_text(activity)

engine = engine_path.read_text()
old_support = '''        val ids = linkedSetOf<String>()
        ids += input.identifiers
        val scopedNorm = normalizeText(scoped)
        ids += tsm.identifiers.filter { containsWholeToken(scopedNorm, normalizeToken(it)) }
        ids.filter { it.length >= 3 }.take(18).forEach { id ->
            awmIdentifierHit(id)?.let(::add)
            ipcIdentifierHit(id)?.let(::add)
        }
        return out.take(32)
'''
new_support = '''        // Support-manual expansion remains fail-closed: identifiers must come from
        // the verified aircraft input, selected TSM index record, or selected TSM branch.
        // No ATA-only or free-text guessing is permitted here.
        val ids = linkedSetOf<String>()
        ids += input.identifiers
        ids += tsm.identifiers
        ids += extractIdentifiers(tsm.maintenanceMessage)
        ids += extractIdentifiers(scoped)
        ids.filter { it.length >= 3 }
            .distinctBy(::normalizeToken)
            .take(28)
            .forEach { id ->
                awmIdentifierHit(id)?.let(::add)
                ipcIdentifierHit(id)?.let(::add)
            }
        return out.take(40)
'''
engine = replace_once(engine, old_support, new_support, "support identifier expansion")

old_awm = '''    private fun awmIdentifierHit(id: String): SearchHit? = runCatching {
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
new_awm = '''    private fun awmIdentifierHit(id: String): SearchHit? = runCatching {
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
engine = replace_once(engine, old_awm, new_awm, "normalized AWM/IPC exact matching")
engine = replace_once(
    engine,
    'append("LINE ASSISTANT 1.5.0 • CONSOLIDATED FAIL-CLOSED RESOLVER\\n\\n")',
    'append("LINE ASSISTANT 1.5.1 • CONSOLIDATED FAIL-CLOSED RESOLVER + OCR2\\n\\n")',
    "engine release label",
)
engine_path.write_text(engine)

gradle = gradle_path.read_text()
gradle = replace_once(gradle, 'versionCode = 50', 'versionCode = 51', "versionCode")
gradle = replace_once(gradle, 'versionName = "1.5.0"', 'versionName = "1.5.1"', "versionName")
gradle_path.write_text(gradle)

regression = regression_path.read_text()
regression = regression.replace('version is consolidated 1.5.0', 'version is consolidated 1.5.1')
regression = regression.replace('versionName = "1.5.0"', 'versionName = "1.5.1"')
regression = regression.replace("'versionCode = 50'", "'versionCode = 51'")
regression = regression.replace('LINE ASSISTANT 1.5.0 • CONSOLIDATED FAIL-CLOSED RESOLVER', 'LINE ASSISTANT 1.5.1 • CONSOLIDATED FAIL-CLOSED RESOLVER + OCR2')
regression += '''\n# 1.5.1 regression additions.\nrequire('OCR uses final crop and enhanced second pass', 'FINAL CROP + STRAIGHTEN IMAGE' in activity and 'OCR • PASS 2 / CONTRAST + DESKEW ENHANCEMENT' in activity)\nrequire('OCR geometry deskew is bounded', 'abs(angle) <= 12f' in activity and 'estimateOcrSkew' in activity)\nrequire('support manuals use verified TSM branch identifiers', 'extractIdentifiers(scoped)' in engine and 'ids += tsm.identifiers' in engine)\nrequire('AWM normalized exact identifier matching', "REPLACE(REPLACE(REPLACE(REPLACE(UPPER(i.token)" in engine)\nrequire('IPC normalized exact FIN/PN matching', "UPPER(COALESCE(fin,''))" in engine and 'normalized, normalized' in engine)\n'''
regression_path.write_text(regression)

print("Line Assistant 1.5.1 workspace patch applied successfully")
