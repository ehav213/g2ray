package com.a340.technicalassistant

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Fail-closed Airbus A340 PFR table parser.
 *
 * Full-page PFR photos use the printed table headers to establish the three logical columns.
 * Cropped single-row/multi-row photos are also accepted when geometry can be proven from three
 * independent anchors: a 4-digit cockpit ATA on the left, a valid UTC in the middle, and a
 * 6-digit maintenance ATA on the right. No flattened-text fallback is used for troubleshooting.
 */
object PfrGeometryAnalyzerV2 {
    private data class OcrLine(val text: String, val box: Rect) {
        val cx: Float get() = (box.left + box.right) / 2f
        val cy: Float get() = (box.top + box.bottom) / 2f
        val height: Int get() = max(1, box.height())
    }

    private data class Columns(val cockpitEnd: Float, val faultStart: Float)

    fun analyze(result: Text): PostFlightAnalyzer.Result {
        val raw = result.text.trim()
        val lines = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { OcrLine(normalize(line.text), Rect(it)) } }
            .filter { it.text.length >= 2 }
            .sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })

        if (lines.size < 6) return emptyResult(raw)

        val typicalHeight = medianHeight(lines)
        val pageLeft = lines.minOf { it.box.left }
        val pageRight = lines.maxOf { it.box.right }
        val pageWidth = max(1, pageRight - pageLeft)

        val cockpitHeader = lines.firstOrNull { it.text.contains("COCKPIT") && it.text.contains("EFFECT") }
        val faultHeader = lines.firstOrNull { it.text == "FAULTS" || it.text.matches(Regex("^\\d+\\s+FAULTS$")) }
        val middleHeaders = lines.filter { it.text == "UTC" || it.text.contains("FLIGHT PHASE") }

        val hasFullHeader = cockpitHeader != null && faultHeader != null && middleHeaders.isNotEmpty()
        val headerBottom: Int
        val columns: Columns

        if (hasFullHeader) {
            val ch = cockpitHeader!!
            val fh = faultHeader!!
            headerBottom = listOf(
                ch.box.bottom,
                fh.box.bottom,
                middleHeaders.maxOf { it.box.bottom }
            ).max()
            val middleCx = middleHeaders.map { it.cx }.average().toFloat()
            var cockpitEnd = (ch.cx + middleCx) / 2f
            var faultStart = (middleCx + fh.cx) / 2f
            cockpitEnd = cockpitEnd.coerceIn(pageLeft + pageWidth * 0.22f, pageLeft + pageWidth * 0.42f)
            faultStart = faultStart.coerceIn(pageLeft + pageWidth * 0.42f, pageLeft + pageWidth * 0.64f)
            if (cockpitEnd >= faultStart - pageWidth * 0.04f) {
                cockpitEnd = pageLeft + pageWidth * 0.31f
                faultStart = pageLeft + pageWidth * 0.50f
            }
            columns = Columns(cockpitEnd, faultStart)
        } else {
            // Cropped PFR row: infer the same three logical columns from independent content anchors.
            // This remains fail-closed: all three anchor families must exist in the expected order.
            columns = inferColumnsFromAnchors(lines, pageLeft, pageWidth) ?: return emptyResult(raw)
            headerBottom = (lines.minOf { it.box.top } - typicalHeight).coerceAtLeast(0)
        }

        val body = if (hasFullHeader) {
            lines.filter { it.box.top >= headerBottom - typicalHeight / 2 }
        } else {
            lines
        }

        // UTC is the row anchor. Repeated times are retained when their vertical positions differ.
        val utcAnchors = body
            .filter { it.cx > columns.cockpitEnd && it.cx < columns.faultStart }
            .mapNotNull { line -> parseUtc(line.text)?.let { line to it } }
            .sortedBy { it.first.cy }
            .fold(mutableListOf<Pair<OcrLine, String>>()) { acc, item ->
                val prev = acc.lastOrNull()
                if (prev == null || abs(prev.first.cy - item.first.cy) > typicalHeight * 0.65f) acc += item
                acc
            }

        if (utcAnchors.isEmpty()) return emptyResult(raw)

        val bodyBottom = body.maxOfOrNull { it.box.bottom } ?: headerBottom
        val faults = mutableListOf<PostFlightAnalyzer.Fault>()

        utcAnchors.forEachIndexed { index, anchor ->
            val prevCy = utcAnchors.getOrNull(index - 1)?.first?.cy
            val nextCy = utcAnchors.getOrNull(index + 1)?.first?.cy
            val top = prevCy?.let { ((it + anchor.first.cy) / 2f).toInt() } ?: headerBottom
            val bottom = nextCy?.let { ((it + anchor.first.cy) / 2f).toInt() } ?: (bodyBottom + typicalHeight)

            val row = body
                .filter { it.cy >= top && it.cy < bottom }
                .sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
            if (row.isEmpty()) return@forEachIndexed

            val cockpit = row.filter { it.cx <= columns.cockpitEnd }
            val middle = row.filter { it.cx > columns.cockpitEnd && it.cx < columns.faultStart }
            val fault = row.filter { it.cx >= columns.faultStart }

            val cockpitAtaDigits = cockpit.firstNotNullOfOrNull { parseAtaDigits(it.text)?.takeIf { d -> d.length == 4 } }
            val cockpitAta = cockpitAtaDigits?.let(::formatAta)
            val cockpitMessage = parseCockpitMessage(cockpit, cockpitAtaDigits)

            val phase = middle.firstNotNullOfOrNull { canonicalPhase(it.text) }
            val phaseCode = middle.firstNotNullOfOrNull { parsePhaseCode(it.text) }
            val utc = anchor.second

            val maintAtaDigits = fault.firstNotNullOfOrNull { parseAtaDigits(it.text)?.takeIf { d -> d.length == 6 } }
            if (maintAtaDigits != null) {
                val faultClass = fault.firstNotNullOfOrNull(::parseClass)
                val persistence = fault.firstOrNull {
                    normalize(it.text) in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")
                }?.text
                val source = fault.firstNotNullOfOrNull { parseSource(it.text) }
                val metaStart = fault
                    .filter { isSourceOrIdentifierLine(it.text) }
                    .minOfOrNull { it.box.left }
                    ?.toFloat()
                    ?: (pageLeft + pageWidth * 0.82f)
                val identifiers = parseIdentifiers(fault, metaStart)
                val message = parseFaultMessage(fault, maintAtaDigits, metaStart)

                if (message.isNotBlank()) {
                    faults += PostFlightAnalyzer.Fault(
                        number = faults.size + 1,
                        category = "CMS FAULT MESSAGE",
                        text = message,
                        ata = maintAtaDigits.take(2),
                        identifiers = (identifiers + extractIdentifiers(message)).distinct().take(16),
                        ataFull = formatAta(maintAtaDigits),
                        utc = utc,
                        flightPhase = phase,
                        faultClass = faultClass,
                        persistence = persistence,
                        source = source,
                        cockpitEffect = cockpitMessage,
                        cockpitAta = cockpitAta,
                        flightPhaseCode = phaseCode
                    )
                }
            } else if (!cockpitAta.isNullOrBlank() && !cockpitMessage.isNullOrBlank()) {
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
                    cockpitAta = cockpitAta,
                    flightPhaseCode = phaseCode
                )
            }
        }

        if (faults.isEmpty()) return emptyResult(raw)
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

    private fun emptyResult(raw: String) = PostFlightAnalyzer.Result(
        rawText = raw,
        context = PostFlightAnalyzer.Context(),
        faults = emptyList()
    )

    private fun inferColumnsFromAnchors(lines: List<OcrLine>, pageLeft: Int, pageWidth: Int): Columns? {
        val cockpitXs = mutableListOf<Float>()
        val utcXs = mutableListOf<Float>()
        val faultXs = mutableListOf<Float>()

        lines.forEach { line ->
            val ata = parseAtaDigits(line.text)
            when (ata?.length) {
                4 -> cockpitXs += line.cx
                6 -> faultXs += line.cx
            }
            if (parseUtc(line.text) != null) utcXs += line.cx
        }

        val cockpitX = median(cockpitXs) ?: return null
        val utcX = median(utcXs) ?: return null
        val faultX = median(faultXs) ?: return null

        if (!(cockpitX < utcX && utcX < faultX)) return null
        if (utcX - cockpitX < pageWidth * 0.10f) return null
        if (faultX - utcX < pageWidth * 0.045f) return null

        var cockpitEnd = (cockpitX + utcX) / 2f
        var faultStart = (utcX + faultX) / 2f
        cockpitEnd = cockpitEnd.coerceIn(pageLeft + pageWidth * 0.20f, pageLeft + pageWidth * 0.46f)
        faultStart = faultStart.coerceIn(pageLeft + pageWidth * 0.40f, pageLeft + pageWidth * 0.68f)
        if (cockpitEnd >= faultStart - pageWidth * 0.035f) return null
        return Columns(cockpitEnd, faultStart)
    }

    private fun parseCockpitMessage(lines: List<OcrLine>, ataDigits: String?): String? {
        if (ataDigits == null) return null
        val sorted = lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
        val ataIndex = sorted.indexOfFirst { parseAtaDigits(it.text) == ataDigits }
        val afterAta = if (ataIndex >= 0) sorted.drop(ataIndex + 1) else sorted
        val parts = mutableListOf<String>()
        afterAta.forEach { line ->
            if (isNoise(line.text) || parseUtc(line.text) != null || canonicalPhase(line.text) != null) return@forEach
            val cleaned = stripCockpitTypePrefix(line.text)
            if (cleaned.isNotBlank() && !isCockpitType(cleaned)) parts += cleaned
        }
        return parts.joinToString(" ")
            .replace(Regex("\\bL[IL|1]G\\b"), "L/G")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
    }

    private fun parseFaultMessage(lines: List<OcrLine>, ataDigits: String, metaStart: Float): String {
        val sorted = lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
        val ataIndex = sorted.indexOfFirst { parseAtaDigits(it.text) == ataDigits }
        val afterAta = if (ataIndex >= 0) sorted.drop(ataIndex + 1) else sorted
        return afterAta
            .filter { it.box.left < metaStart }
            .map { it.text }
            .filterNot(::isFaultMetadata)
            .filterNot(::isNoise)
            .filterNot { parseAtaDigits(it) != null || parseUtc(it) != null || canonicalPhase(it) != null }
            .joinToString(" ")
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseIdentifiers(lines: List<OcrLine>, metaStart: Float): List<String> {
        val out = linkedSetOf<String>()
        var collecting = false
        lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left }).forEach { line ->
            val m = Regex("^IDENTIFIERS?\\s*[:=-]?\\s*(.*)$").find(line.text)
            if (m != null) {
                collecting = true
                splitIdentifiers(m.groupValues[1]).forEach(out::add)
                return@forEach
            }
            if (collecting && line.box.left >= metaStart) {
                if (line.text.startsWith("SOURCE") || parseAtaDigits(line.text) != null || parseClass(line) != null) collecting = false
                else splitIdentifiers(line.text).forEach(out::add)
            }
        }
        return out.toList()
    }

    private fun parseClass(line: OcrLine): String? {
        val m = Regex("^CLASS\\s*[:=-]?\\s*([123IL])\\b.*").find(line.text) ?: return null
        return when (m.groupValues[1]) { "I", "L" -> "1"; else -> m.groupValues[1] }
    }

    private fun parseSource(text: String): String? {
        val m = Regex("^SOURCE\\s*[:=-]?\\s*([^\\s]+)").find(text) ?: return null
        return m.groupValues[1]
            .trim()
            .trimStart('*', '#', ':', '-', '=')
            .replace(Regex("[^A-Z0-9/.-]"), "")
            .ifBlank { null }
    }

    private fun isSourceOrIdentifierLine(text: String): Boolean {
        val t = normalize(text)
        return t.startsWith("SOURCE") || t.startsWith("IDENTIFIER")
    }

    private fun parseAtaDigits(text: String): String? {
        val m = Regex("\\bATA\\s*([0-9OIL]{4,6})\\b").find(text) ?: return null
        val digits = m.groupValues[1]
            .replace('O', '0').replace('I', '1').replace('L', '1')
            .filter(Char::isDigit)
        return digits.takeIf { it.length == 4 || it.length == 6 }
    }

    private fun parseUtc(text: String): String? {
        val compact = normalize(text).replace(" ", "")
        val m = Regex("^[0-9OIL]{4}$").find(compact) ?: return null
        val digits = m.value.replace('O', '0').replace('I', '1').replace('L', '1')
        val hh = digits.take(2).toIntOrNull() ?: return null
        val mm = digits.drop(2).toIntOrNull() ?: return null
        return digits.takeIf { hh in 0..23 && mm in 0..59 }
    }

    private fun parsePhaseCode(text: String): String? {
        val t = normalize(text).replace("O", "0").replace("I", "1").replace("L", "1")
        return Regex("^(0[1-9]|1[0-2])$").find(t)?.groupValues?.getOrNull(1)
    }

    private fun canonicalPhase(text: String): String? {
        val t = normalize(text)
        val aliases = mapOf(
            "COUISE" to "CRUISE", "CRUSE" to "CRUISE", "CROISE" to "CRUISE", "CRUI5E" to "CRUISE",
            "ENGINESTART" to "ENGINE START", "ENG START" to "ENGINE START", "TAKE OFF" to "TAKEOFF"
        )
        aliases[t]?.let { return it }
        return listOf(
            "ENGINE START", "TAXI OUT", "TAKEOFF", "CLIMB", "CRUISE", "DESCENT", "APPROACH",
            "LANDING", "TAXI IN", "ENGINE SHUTDOWN", "ROLLOUT", "GROUND"
        ).firstOrNull { t.contains(it) }
    }

    private fun stripCockpitTypePrefix(text: String): String {
        var t = normalize(text)
        val prefixes = listOf("MAINTENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE")
        prefixes.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it).trim() }
        return t
    }

    private fun isCockpitType(text: String): Boolean {
        val t = normalize(text)
        return t in setOf(
            "MAINTENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE", "STS-INOP SYSTEM",
            "EWD WARNING(S)", "EWD WARNINGS", "PFD FLAG(S)", "ND FLAG(S)", "SD FLAG(S)",
            "SD ADVISORY(IES)", "OBSERVATIONS", "WARNING LIGHT(S)", "INDICATOR(S)"
        )
    }

    private fun isFaultMetadata(text: String): Boolean {
        val t = normalize(text)
        return t.matches(Regex("^CLASS\\s*[:=-]?\\s*[123IL].*")) ||
            t in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT") ||
            t.startsWith("SOURCE") || t.startsWith("IDENTIFIER")
    }

    private fun isNoise(text: String): Boolean {
        val t = normalize(text)
        return t in setOf(
            "COCKPIT EFFECTS", "FAULTS", "UTC", "FLIGHT PHASE", "SOURCE", "IDENTIFIERS", "IDENTIFIER",
            "MAINTENANCE", "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "CONTINUED", "END OF REPORT"
        ) || t.startsWith("CMC1 PRINTING") || t.startsWith("CMC2 PRINTING") || t.startsWith("PAGE ")
    }

    private fun splitIdentifiers(text: String): List<String> = normalize(text)
        .replace("*", " ")
        .split(Regex("[/,;\\s]+"))
        .map { it.replace(Regex("[^A-Z0-9.-]"), "").trim() }
        .filter { it.length >= 2 && it !in setOf("SOURCE", "IDENTIFIERS", "IDENTIFIER") }

    private fun extractIdentifiers(text: String): List<String> {
        val out = linkedSetOf<String>()
        val upper = normalize(text)
        Regex("\\b\\d{1,4}[A-Z]{1,4}\\d{0,4}\\b").findAll(upper).forEach { out += it.value }
        Regex("\\b(?:LGCIU\\d|BMC\\d(?:-\\d)?|CIDS\\d|EFCS\\d|FCMC\\d|RMP\\d)\\b").findAll(upper).forEach { out += it.value }
        return out.toList()
    }

    private fun formatAta(digits: String): String = when (digits.length) {
        4 -> "${digits.take(2)}-${digits.drop(2)}"
        6 -> "${digits.take(2)}-${digits.substring(2, 4)}-${digits.drop(4)}"
        else -> digits
    }

    private fun normalize(value: String): String = value.uppercase(Locale.US)
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun medianHeight(lines: List<OcrLine>): Int {
        val s = lines.map { it.height }.sorted()
        return if (s.isEmpty()) 12 else s[s.size / 2].coerceAtLeast(8)
    }
}
