package com.a340.technicalassistant

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Geometry-aware Airbus A340 PFR parser.
 *
 * The printed PFR is a three-column table: COCKPIT EFFECTS | UTC/FLIGHT PHASE | FAULTS.
 * ML Kit's flattened text can interleave these columns. This parser preserves bounding boxes,
 * clusters ATA anchors into physical table rows, and only associates cockpit and CMS data that
 * occupy the same printed row.
 */
object PfrGeometryAnalyzer {
    private data class OcrLine(
        val text: String,
        val box: Rect
    ) {
        val cx: Float get() = (box.left + box.right) / 2f
        val cy: Float get() = (box.top + box.bottom) / 2f
        val height: Int get() = max(1, box.height())
    }

    private data class AtaAnchor(
        val line: OcrLine,
        val digits: String,
        val maintenance: Boolean
    )

    private data class RowCluster(
        val anchors: MutableList<AtaAnchor> = mutableListOf()
    ) {
        val cy: Float get() = anchors.map { it.line.cy }.average().toFloat()
    }

    fun analyze(result: Text): PostFlightAnalyzer.Result {
        val raw = result.text.trim()
        val lines = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { OcrLine(normalize(line.text), Rect(it)) } }
            .filter { it.text.length >= 2 }

        if (lines.size < 8) return PostFlightAnalyzer.analyze(raw)

        val headerBottom = lines
            .filter { it.text.contains("COCKPIT EFFECT") || it.text == "FAULTS" || it.text.contains("FLIGHT PHASE") }
            .maxOfOrNull { it.box.bottom }
            ?: lines.minOfOrNull { it.box.top }
            ?: 0

        val body = lines.filter { it.box.top >= headerBottom - medianHeight(lines) }
        val pageLeft = lines.minOfOrNull { it.box.left } ?: 0
        val pageRight = lines.maxOfOrNull { it.box.right } ?: 1
        val pageWidth = max(1, pageRight - pageLeft)

        val anchors = body.mapNotNull { line ->
            val digits = parseAtaDigits(line.text) ?: return@mapNotNull null
            when (digits.length) {
                4 -> if (line.cx < pageLeft + pageWidth * 0.55f) AtaAnchor(line, digits, false) else null
                6 -> if (line.cx > pageLeft + pageWidth * 0.32f) AtaAnchor(line, digits, true) else null
                else -> null
            }
        }

        val maintAnchors = anchors.filter { it.maintenance }
        val cockpitAnchors = anchors.filterNot { it.maintenance }
        if (anchors.isEmpty()) return PostFlightAnalyzer.analyze(raw)

        val typicalHeight = medianHeight(body)
        val rowTolerance = max(10f, typicalHeight * 1.8f)
        val clusters = mutableListOf<RowCluster>()
        anchors.sortedBy { it.line.cy }.forEach { anchor ->
            val existing = clusters.lastOrNull()?.takeIf { abs(it.cy - anchor.line.cy) <= rowTolerance }
            if (existing != null) existing.anchors += anchor else clusters += RowCluster(mutableListOf(anchor))
        }

        val cockpitLeft = cockpitAnchors.map { it.line.box.left }.medianOrNull()
            ?: (pageLeft + pageWidth * 0.05f)
        val faultLeft = maintAnchors.map { it.line.box.left }.medianOrNull()
            ?: (pageLeft + pageWidth * 0.50f)
        val leftMax = ((cockpitLeft + faultLeft) / 2f).coerceIn(pageLeft + pageWidth * 0.20f, pageLeft + pageWidth * 0.42f)
        val faultMin = (faultLeft - pageWidth * 0.035f).coerceAtLeast(pageLeft + pageWidth * 0.40f)
        val faultMetaSplit = faultMin + (pageRight - faultMin) * 0.62f

        val faults = mutableListOf<PostFlightAnalyzer.Fault>()
        clusters.forEachIndexed { index, cluster ->
            val previousCy = clusters.getOrNull(index - 1)?.cy
            val nextCy = clusters.getOrNull(index + 1)?.cy
            val top = previousCy?.let { ((it + cluster.cy) / 2f).toInt() } ?: headerBottom
            val bottom = nextCy?.let { ((it + cluster.cy) / 2f).toInt() }
                ?: (body.maxOfOrNull { it.box.bottom } ?: cluster.cy.toInt() + typicalHeight * 5)

            val rowLines = body.filter { it.cy >= top && it.cy < bottom }.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
            val cockpitAnchor = cluster.anchors.filterNot { it.maintenance }.minByOrNull { it.line.box.left }
            val maintAnchor = cluster.anchors.filter { it.maintenance }.minByOrNull { it.line.box.left }

            val cockpitColumn = rowLines.filter { it.cx <= leftMax }
            val faultColumn = rowLines.filter { it.cx >= faultMin }
            val middleColumn = rowLines.filter { it.cx > leftMax && it.cx < faultMin }

            val cockpitAta = cockpitAnchor?.digits?.let(::formatAta)
            val cockpitEffect = cockpitMessage(cockpitColumn, cockpitAnchor)
            val utc = middleColumn.firstNotNullOfOrNull { parseUtc(it.text) }
            val phase = middleColumn.firstNotNullOfOrNull { canonicalPhase(it.text) }
            val phaseCode = middleColumn.firstNotNullOfOrNull { line ->
                Regex("^(?:0[1-9]|1[0-2])$").find(line.text)?.value
            }

            if (maintAnchor != null) {
                val maintenanceAta = formatAta(maintAnchor.digits)
                val faultClass = faultColumn.firstNotNullOfOrNull { line ->
                    Regex("^CLASS\\s*[:=-]?\\s*([123])\\b.*").find(line.text)?.groupValues?.getOrNull(1)
                }
                val persistence = faultColumn.firstOrNull { it.text in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT") }?.text
                val source = faultColumn.firstNotNullOfOrNull { parseSource(it.text) }
                val identifiers = parseIdentifiers(faultColumn, faultMetaSplit)
                val message = faultMessage(faultColumn, maintAnchor, faultMetaSplit)

                if (message.isNotBlank()) {
                    faults += PostFlightAnalyzer.Fault(
                        number = faults.size + 1,
                        category = "CMS FAULT MESSAGE",
                        text = message,
                        ata = maintAnchor.digits.take(2),
                        identifiers = (identifiers + extractIdentifiers(message)).distinct().take(16),
                        ataFull = maintenanceAta,
                        utc = utc,
                        flightPhase = phase,
                        faultClass = faultClass,
                        persistence = persistence,
                        source = source,
                        cockpitEffect = cockpitEffect,
                        cockpitAta = cockpitAta,
                        flightPhaseCode = phaseCode
                    )
                }
            } else if (!cockpitEffect.isNullOrBlank() && !cockpitAta.isNullOrBlank()) {
                // Warning/Malfunction-only TSM entry: valid first entry point even without CMS fault message.
                faults += PostFlightAnalyzer.Fault(
                    number = faults.size + 1,
                    category = "WARNING/MALFUNCTION",
                    text = cockpitEffect,
                    ata = null,
                    identifiers = emptyList(),
                    ataFull = null,
                    utc = utc,
                    flightPhase = phase,
                    faultClass = null,
                    persistence = null,
                    source = null,
                    cockpitEffect = cockpitEffect,
                    cockpitAta = cockpitAta,
                    flightPhaseCode = phaseCode
                )
            }
        }

        if (faults.isEmpty()) return PostFlightAnalyzer.analyze(raw)

        val renumbered = faults.mapIndexed { i, f -> f.copy(number = i + 1) }
        val first = renumbered.firstOrNull()
        return PostFlightAnalyzer.Result(
            rawText = raw,
            context = PostFlightAnalyzer.Context(
                utc = first?.utc,
                flightPhase = first?.flightPhase,
                flightPhaseCode = first?.flightPhaseCode,
                cockpitEffects = renumbered.mapNotNull { it.cockpitEffect }.distinct(),
                cockpitAta = first?.cockpitAta
            ),
            faults = renumbered
        )
    }

    private fun cockpitMessage(lines: List<OcrLine>, anchor: AtaAnchor?): String? {
        if (anchor == null) return null
        val candidates = lines
            .filter { it.cy >= anchor.line.cy - anchor.line.height * 0.5f }
            .map { it.text }
            .filterNot(::isAtaLine)
            .filterNot(::isCockpitType)
            .filterNot(::isNoise)
            .filterNot { parseUtc(it) != null || canonicalPhase(it) != null || it.matches(Regex("^(?:0[1-9]|1[0-2])$")) }
        return candidates.joinToString(" ").replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }

    private fun faultMessage(lines: List<OcrLine>, anchor: AtaAnchor, metaSplit: Float): String {
        val candidates = lines
            .filter { it.cy >= anchor.line.cy - anchor.line.height * 0.4f }
            .filter { it.box.left < metaSplit }
            .map { it.text }
            .filterNot(::isAtaLine)
            .filterNot(::isFaultMetadata)
            .filterNot(::isNoise)
        return candidates.joinToString(" ")
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseIdentifiers(lines: List<OcrLine>, metaSplit: Float): List<String> {
        val out = linkedSetOf<String>()
        var afterLabel = false
        lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left }).forEach { line ->
            val inline = Regex("^IDENTIFIERS?\\s*[:=-]?\\s*(.*)$").find(line.text)
            if (inline != null) {
                afterLabel = true
                splitIdentifiers(inline.groupValues[1]).forEach(out::add)
                return@forEach
            }
            if (afterLabel && line.box.left >= metaSplit) {
                if (isFaultMetadata(line.text) || isAtaLine(line.text)) afterLabel = false
                else splitIdentifiers(line.text).forEach(out::add)
            }
        }
        return out.toList()
    }

    private fun parseSource(text: String): String? {
        val match = Regex("^SOURCE\\s*[:=-]?\\s*([^\\s]+)").find(text) ?: return null
        return match.groupValues[1].trim().trimStart('*', '#', ':', '-', '=')
            .replace(Regex("[^A-Z0-9/.-]"), "")
            .ifBlank { null }
    }

    private fun parseAtaDigits(text: String): String? {
        val m = Regex("\\bATA\\s*([0-9OIL]{4,6})\\b").find(text) ?: return null
        val digits = m.groupValues[1]
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
            .filter(Char::isDigit)
        return digits.takeIf { it.length == 4 || it.length == 6 }
    }

    private fun parseUtc(text: String): String? {
        val token = text.filter(Char::isDigit)
        if (token.length != 4) return null
        val hh = token.take(2).toIntOrNull() ?: return null
        val mm = token.drop(2).toIntOrNull() ?: return null
        return token.takeIf { hh in 0..23 && mm in 0..59 }
    }

    private fun canonicalPhase(text: String): String? {
        val t = normalize(text)
        val aliases = mapOf(
            "COUISE" to "CRUISE", "CRUSE" to "CRUISE", "CROISE" to "CRUISE", "CRUI5E" to "CRUISE",
            "ENGINESTART" to "ENGINE START", "ENG START" to "ENGINE START", "TAKE OFF" to "TAKEOFF"
        )
        aliases[t]?.let { return it }
        return listOf("ENGINE START", "TAXI OUT", "TAKEOFF", "CLIMB", "CRUISE", "DESCENT", "APPROACH", "LANDING", "TAXI IN", "ENGINE SHUTDOWN", "GROUND")
            .firstOrNull { t.contains(it) }
    }

    private fun isAtaLine(text: String): Boolean = parseAtaDigits(text) != null

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
        return t.matches(Regex("^CLASS\\s*[:=-]?\\s*[123].*")) ||
            t in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT") ||
            t.startsWith("SOURCE") || t.startsWith("IDENTIFIER")
    }

    private fun isNoise(text: String): Boolean {
        val t = normalize(text)
        return t in setOf(
            "COCKPIT EFFECTS", "FAULTS", "UTC", "FLIGHT PHASE", "SOURCE", "IDENTIFIERS", "IDENTIFIER",
            "MAINTENANCE", "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "CONTINUED", "END OF REPORT"
        ) || t.startsWith("CMC1 PRINTING") || t.startsWith("CMC2 PRINTING")
    }

    private fun splitIdentifiers(text: String): List<String> = text.uppercase(Locale.US)
        .replace("*", " ")
        .split(Regex("[/,;\\s]+"))
        .map { it.replace(Regex("[^A-Z0-9.-]"), "").trim() }
        .filter { it.length >= 2 && it !in setOf("SOURCE", "IDENTIFIERS", "IDENTIFIER") }

    private fun extractIdentifiers(text: String): List<String> {
        val out = linkedSetOf<String>()
        Regex("\\b\\d{1,4}[A-Z]{1,4}\\d{0,4}\\b").findAll(text.uppercase(Locale.US)).forEach { out += it.value }
        Regex("\\b(?:LGCIU\\d|BMC\\d(?:-\\d)?|CIDS\\d|EFCS\\d|FCMC\\d)\\b").findAll(text.uppercase(Locale.US)).forEach { out += it.value }
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

    private fun medianHeight(lines: List<OcrLine>): Int {
        val values = lines.map { it.height }.sorted()
        return if (values.isEmpty()) 12 else values[values.size / 2].coerceAtLeast(8)
    }

    private fun List<Int>.medianOrNull(): Float? {
        if (isEmpty()) return null
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toFloat() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
