package com.a340.technicalassistant

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

object PfrGeometryAnalyzerV2 {
    private data class Word(val text: String, val box: Rect) {
        val cx: Float get() = (box.left + box.right) / 2f
        val cy: Float get() = (box.top + box.bottom) / 2f
        val height: Int get() = max(1, box.height())
    }

    private data class AtaAnchor(val digits: String, val word: Word) {
        val cx: Float get() = word.cx
        val cy: Float get() = word.cy
    }

    private data class Columns(val cockpitEnd: Float, val faultStart: Float)
    private data class VisualLine(val words: List<Word>) {
        val text: String get() = words.sortedBy { it.box.left }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
        val cx: Float get() = words.map { it.cx }.average().toFloat()
        val cy: Float get() = words.map { it.cy }.average().toFloat()
        val left: Int get() = words.minOf { it.box.left }
    }

    fun analyze(result: Text): PostFlightAnalyzer.Result {
        val raw = result.text.trim()
        val words = result.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { e -> e.boundingBox?.let { Word(normalize(e.text), Rect(it)) } }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<Word> { it.box.top }.thenBy { it.box.left })

        if (words.size < 4) return emptyResult(raw)

        val h = medianHeight(words)
        val pageLeft = words.minOf { it.box.left }
        val pageRight = words.maxOf { it.box.right }
        val pageBottom = words.maxOf { it.box.bottom }
        val pageWidth = max(1, pageRight - pageLeft)

        val explicit = findAtaAnchors(words, h, pageWidth)
        val explicitRight = dedupeAnchors(explicit.filter { it.digits.length == 6 }, h)
        val explicitLeft = dedupeAnchors(explicit.filter { it.digits.length == 4 }, h)

        val rightAnchors = if (explicitRight.isNotEmpty()) explicitRight else {
            val candidates = words.mapNotNull { w -> numericToken(w.text)?.takeIf { it.length == 6 }?.let { AtaAnchor(it, w) } }
            val medianX = median(candidates.map { it.cx })
            if (medianX == null) emptyList() else dedupeAnchors(candidates.filter { it.cx >= medianX - pageWidth * 0.08f }, h)
        }

        val rightX = median(rightAnchors.map { it.cx })
        val leftAnchors = if (explicitLeft.isNotEmpty()) explicitLeft else {
            val candidates = words.mapNotNull { w -> numericToken(w.text)?.takeIf { it.length == 4 }?.let { AtaAnchor(it, w) } }
            if (rightX == null) candidates.filter { it.cx < pageLeft + pageWidth * 0.40f }
            else candidates.filter { it.cx < rightX - pageWidth * 0.18f }
        }.let { dedupeAnchors(it, h) }

        if (rightAnchors.isEmpty() && leftAnchors.isEmpty()) return emptyResult(raw)

        // Cropped cockpit-only PFR row: the right/CMS side may legitimately be blank.
        // Do not require a 6-digit maintenance ATA in that case. Prove the row from
        // independent cockpit and middle-column evidence, then return only the fields
        // that are actually present on the printout.
        if (rightAnchors.isEmpty() && leftAnchors.isNotEmpty()) {
            analyzeCockpitOnlyCrop(raw, words, leftAnchors, h, pageWidth, pageBottom)?.let { return it }
        }

        val leftX = median(leftAnchors.map { it.cx }) ?: (pageLeft + pageWidth * 0.10f)
        val resolvedRightX = rightX ?: (pageLeft + pageWidth * 0.62f)
        if (resolvedRightX <= leftX + pageWidth * 0.18f) return emptyResult(raw)

        val utcCandidates = words.mapNotNull { w -> parseUtc(w.text)?.let { w } }
            .filter { it.cx > leftX && it.cx < resolvedRightX }
        val utcX = median(utcCandidates.map { it.cx })
            ?: (leftX + (resolvedRightX - leftX) * 0.72f)

        var cockpitEnd = (leftX + utcX) / 2f
        var faultStart = (utcX + resolvedRightX) / 2f
        cockpitEnd = cockpitEnd.coerceIn(pageLeft + pageWidth * 0.18f, pageLeft + pageWidth * 0.48f)
        faultStart = faultStart.coerceIn(pageLeft + pageWidth * 0.40f, pageLeft + pageWidth * 0.72f)
        if (cockpitEnd >= faultStart - pageWidth * 0.025f) return emptyResult(raw)
        val columns = Columns(cockpitEnd, faultStart)

        val rowAnchors = mergeRowAnchors(rightAnchors, leftAnchors, h)
        if (rowAnchors.isEmpty()) return emptyResult(raw)

        val faults = mutableListOf<PostFlightAnalyzer.Fault>()
        rowAnchors.forEachIndexed { index, rowAnchor ->
            val next = rowAnchors.getOrNull(index + 1)
            val top = (rowAnchor.cy - h * 1.6f).toInt().coerceAtLeast(0)
            val bottom = next?.let { (it.cy - h * 0.45f).toInt() } ?: (pageBottom + h)
            val rowWords = words.filter { it.cy >= top && it.cy < bottom }
            if (rowWords.isEmpty()) return@forEachIndexed

            val cockpitWords = rowWords.filter { it.cx <= columns.cockpitEnd }
            val middleWords = rowWords.filter { it.cx > columns.cockpitEnd && it.cx < columns.faultStart }
            val rightWords = rowWords.filter { it.cx >= columns.faultStart }

            val cockpitAtaDigits = findAtaDigits(cockpitWords, 4, h, pageWidth)
            val maintAtaDigits = findAtaDigits(rightWords, 6, h, pageWidth)

            val cockpitLines = groupLines(cockpitWords, h)
            val middleLines = groupLines(middleWords, h)
            val rightLines = groupLines(rightWords, h)

            val sourceMarkerX = rightWords
                .filter { isMarker(it.text, "SOURCE") || isMarker(it.text, "IDENTIFIER") || isMarker(it.text, "IDENTIFIERS") }
                .minOfOrNull { it.box.left }
                ?.toFloat()
            val metaStart = sourceMarkerX ?: (pageLeft + pageWidth * 0.84f)

            val source = parseSource(rightLines, metaStart)
            val identifiers = parseIdentifiers(rightWords, metaStart, h)
            val cockpitType = parseCockpitType(cockpitLines)
            val cockpitMessageRaw = parseCockpitMessage(cockpitLines, cockpitAtaDigits)
            val cockpitMessage = repairSingleWordAgainstSource(cockpitMessageRaw, source)

            val utc = middleLines.firstNotNullOfOrNull { parseUtc(it.text) }
            val phaseCode = middleLines.firstNotNullOfOrNull { parsePhaseCode(it.text) }
            val phase = parseFlightPhase(middleLines, utc, phaseCode)

            if (maintAtaDigits != null) {
                val faultClass = parseClass(rightLines)
                val persistence = parsePersistence(rightLines)
                val message = parseMaintenanceMessage(rightWords, maintAtaDigits, metaStart, h)
                if (message.isNotBlank()) {
                    faults += PostFlightAnalyzer.Fault(
                        number = faults.size + 1,
                        category = "CMS FAULT MESSAGE",
                        text = message,
                        ata = maintAtaDigits.take(2),
                        identifiers = identifiers.distinct().take(20),
                        ataFull = formatAta(maintAtaDigits),
                        utc = utc,
                        flightPhase = phase,
                        faultClass = faultClass,
                        persistence = persistence,
                        source = source,
                        cockpitEffect = cockpitMessage,
                        cockpitAta = cockpitAtaDigits?.let(::formatAta),
                        flightPhaseCode = phaseCode,
                        cockpitType = cockpitType
                    )
                }
            } else if (cockpitAtaDigits != null && !cockpitMessage.isNullOrBlank()) {
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
                    source = source,
                    cockpitEffect = cockpitMessage,
                    cockpitAta = formatAta(cockpitAtaDigits),
                    flightPhaseCode = phaseCode,
                    cockpitType = cockpitType
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

    private fun analyzeCockpitOnlyCrop(
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

    private fun emptyResult(raw: String) = PostFlightAnalyzer.Result(
        rawText = raw,
        context = PostFlightAnalyzer.Context(),
        faults = emptyList()
    )

    private fun findAtaAnchors(words: List<Word>, h: Int, pageWidth: Int): List<AtaAnchor> {
        val out = mutableListOf<AtaAnchor>()
        words.forEachIndexed { index, w ->
            val combined = Regex("(?i)^A[T7]A[-:]?([0-9OILQDSZGB]{4,6})$").find(w.text.replace(" ", ""))
            if (combined != null) {
                numericToken(combined.groupValues[1])?.let { out += AtaAnchor(it, w) }
                return@forEachIndexed
            }
            if (!isAtaLabel(w.text)) return@forEachIndexed
            val candidate = words.drop(index + 1)
                .asSequence()
                .filter { it.box.left >= w.box.right - h / 2 }
                .filter { abs(it.cy - w.cy) <= h * 1.2f }
                .filter { it.box.left - w.box.right <= pageWidth * 0.16f }
                .mapNotNull { n -> numericToken(n.text)?.takeIf { it.length == 4 || it.length == 6 }?.let { n to it } }
                .minByOrNull { it.first.box.left - w.box.right }
            if (candidate != null) out += AtaAnchor(candidate.second, candidate.first)
        }
        return out
    }

    private fun findAtaDigits(words: List<Word>, length: Int, h: Int, pageWidth: Int): String? {
        val explicit = findAtaAnchors(words, h, pageWidth).firstOrNull { it.digits.length == length }?.digits
        if (explicit != null) return explicit
        val standalone = words.mapNotNull { numericToken(it.text)?.takeIf { d -> d.length == length } }
        return standalone.firstOrNull()
    }

    private fun mergeRowAnchors(right: List<AtaAnchor>, left: List<AtaAnchor>, h: Int): List<AtaAnchor> {
        val preferred = (right + left).sortedBy { it.cy }
        val out = mutableListOf<AtaAnchor>()
        preferred.forEach { a ->
            val existing = out.indexOfLast { abs(it.cy - a.cy) <= h * 1.25f }
            if (existing < 0) out += a
            else if (out[existing].digits.length < a.digits.length) out[existing] = a
        }
        return out.sortedBy { it.cy }
    }

    private fun dedupeAnchors(input: List<AtaAnchor>, h: Int): List<AtaAnchor> {
        val out = mutableListOf<AtaAnchor>()
        input.sortedBy { it.cy }.forEach { a ->
            if (out.none { abs(it.cy - a.cy) <= h * 0.70f && it.digits == a.digits }) out += a
        }
        return out
    }

    private fun groupLines(words: List<Word>, h: Int): List<VisualLine> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedWith(compareBy<Word> { it.cy }.thenBy { it.box.left })
        val groups = mutableListOf<MutableList<Word>>()
        sorted.forEach { w ->
            val last = groups.lastOrNull()
            val lastCy = last?.map { it.cy }?.average()?.toFloat()
            if (last == null || lastCy == null || abs(lastCy - w.cy) > h * 0.72f) groups += mutableListOf(w)
            else last += w
        }
        return groups.map { VisualLine(it.sortedBy { w -> w.box.left }) }
    }

    private fun parseCockpitType(lines: List<VisualLine>): String? {
        val candidate = lines.map { normalizeSpacing(it.text) }.firstOrNull(::isCockpitTypeLine) ?: return null
        val compact = candidate.replace(Regex("[^A-Z]"), "")
        return when {
            compact.contains("STSMAINTENANCE") -> "STS-MAINTENANCE"
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

    private fun parseCockpitMessage(lines: List<VisualLine>, ataDigits: String?): String? {
        val parts = lines.map { it.text }
            .filterNot { containsAta(it, ataDigits) }
            .filterNot(::isHeaderOrNoise)
            .filterNot(::isCockpitTypeLine)
            .filterNot { parseUtc(it) != null || parsePhaseCode(it) != null }
            .map { normalizeSpacing(it) }
            .filter { it.isNotBlank() }
        return parts.joinToString(" ").trim().ifBlank { null }
    }

    private fun parseMaintenanceMessage(words: List<Word>, ataDigits: String, metaStart: Float, h: Int): String {
        val messageWords = words.filter { it.cx < metaStart - h * 0.15f }
        val lines = groupLines(messageWords, h)
        return lines.map { it.text }
            .filterNot { containsAta(it, ataDigits) }
            .filterNot(::isHeaderOrNoise)
            .filterNot(::isFaultMetadata)
            .filterNot { parseUtc(it) != null || parsePhaseCode(it) != null }
            .joinToString(" ")
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex("\\s+\\)"), ")")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseSource(lines: List<VisualLine>, metaStart: Float): String? {
        for (line in lines.filter { it.left >= metaStart - 80 || it.words.any { w -> isMarker(w.text, "SOURCE") } }) {
            val ordered = line.words.sortedBy { it.box.left }
            val markerIndex = ordered.indexOfFirst { isMarker(it.text, "SOURCE") }
            if (markerIndex >= 0) {
                val tail = ordered.drop(markerIndex + 1).joinToString("") { it.text }
                    .trim().trimStart('*', '#', ':', '-', '=')
                    .replace(Regex("[^A-Z0-9/.-]"), "")
                if (tail.isNotBlank()) return tail
            }
            val m = Regex("(?i)S[O0]URCE\\s*[:=-]?\\s*[*#]?([A-Z0-9/.-]+)").find(line.text)
            if (m != null) return m.groupValues[1].uppercase(Locale.US)
        }
        return null
    }

    private fun parseIdentifiers(words: List<Word>, metaStart: Float, h: Int): List<String> {
        val meta = words.filter { it.cx >= metaStart - h * 0.3f }
        val lines = groupLines(meta, h)
        val out = linkedSetOf<String>()
        var collecting = false
        lines.forEach { line ->
            val ordered = line.words.sortedBy { it.box.left }
            val markerIndex = ordered.indexOfFirst { isMarker(it.text, "IDENTIFIER") || isMarker(it.text, "IDENTIFIERS") }
            if (markerIndex >= 0) {
                collecting = true
                splitIdentifiers(ordered.drop(markerIndex + 1).joinToString(" ") { it.text }).forEach(out::add)
            } else if (collecting) {
                if (ordered.any { isMarker(it.text, "SOURCE") || isClassToken(it.text) }) collecting = false
                else splitIdentifiers(line.text).forEach(out::add)
            }
        }
        return out.toList()
    }

    private fun parseClass(lines: List<VisualLine>): String? {
        lines.forEach { line ->
            val compact = line.text.replace(" ", "")
            val m = Regex("(?i)C[L1I]ASS[:=-]?([123IL])").find(compact)
            if (m != null) return when (m.groupValues[1].uppercase(Locale.US)) { "I", "L" -> "1"; else -> m.groupValues[1] }
            val words = line.words
            val idx = words.indexOfFirst { isClassToken(it.text) }
            if (idx >= 0) {
                val n = words.drop(idx + 1).firstNotNullOfOrNull { phaseDigit(it.text) }
                if (n in listOf("1", "2", "3")) return n
            }
        }
        return null
    }

    private fun parsePersistence(lines: List<VisualLine>): String? {
        val known = listOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")
        lines.forEach { line ->
            val t = normalize(line.text)
            known.firstOrNull { t == it || t.startsWith("$it ") }?.let { return it }
        }
        return null
    }

    private fun parseFlightPhase(lines: List<VisualLine>, utc: String?, phaseCode: String?): String? {
        val parts = lines.map { normalizeSpacing(it.text) }
            .filterNot { parseUtc(it) == utc && utc != null }
            .filterNot { parsePhaseCode(it) == phaseCode && phaseCode != null }
            .filterNot(::isHeaderOrNoise)
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val raw = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return canonicalPhase(raw) ?: raw
    }

    private fun parseUtc(text: String): String? {
        val compact = normalize(text).replace(Regex("[^A-Z0-9]"), "")
        if (compact.length != 4) return null
        val digits = numericToken(compact) ?: return null
        if (digits.length != 4) return null
        val hh = digits.take(2).toIntOrNull() ?: return null
        val mm = digits.drop(2).toIntOrNull() ?: return null
        return digits.takeIf { hh in 0..23 && mm in 0..59 }
    }

    private fun parsePhaseCode(text: String): String? {
        val compact = normalize(text).replace(Regex("[^A-Z0-9]"), "")
        val digits = numericToken(compact) ?: return null
        if (digits.length != 2) return null
        val value = digits.toIntOrNull() ?: return null
        return digits.takeIf { value in 1..12 }
    }

    private fun phaseDigit(text: String): String? {
        val d = numericToken(text) ?: return null
        return d.takeIf { it.length == 1 }
    }

    private fun canonicalPhase(text: String): String? {
        val t = normalize(text).replace(Regex("\\s+"), " ")
        val compact = t.replace(" ", "")
        return when {
            compact.contains("ENGINESTART") || compact.contains("ENGSTART") -> "ENGINE START"
            compact.contains("TAXIOUT") -> "TAXI OUT"
            compact.contains("TAKEOFF") -> "TAKEOFF"
            compact.contains("CLIMB") -> "CLIMB"
            editDistance(compact, "CRUISE") <= 2 -> "CRUISE"
            compact.contains("DESCENT") -> "DESCENT"
            compact.contains("APPROACH") -> "APPROACH"
            compact.contains("LANDING") -> "LANDING"
            compact.contains("TAXIIN") -> "TAXI IN"
            compact.contains("ENGINESHUTDOWN") -> "ENGINE SHUTDOWN"
            compact.contains("GROUND") -> "GROUND"
            else -> null
        }
    }

    private fun numericToken(raw: String): String? {
        val t = normalize(raw).replace(Regex("[^A-Z0-9|]"), "")
        if (t.isBlank()) return null
        val out = StringBuilder()
        for (ch in t) {
            val mapped = when (ch) {
                in '0'..'9' -> ch
                'O', 'Q', 'D' -> '0'
                'I', 'L', '|' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'G' -> '6'
                'B' -> '8'
                else -> return null
            }
            out.append(mapped)
        }
        return out.toString()
    }

    private fun isAtaLabel(text: String): Boolean {
        val letters = normalize(text).replace(Regex("[^A-Z]"), "")
        return letters.length == 3 && editDistance(letters, "ATA") <= 1
    }

    private fun isMarker(text: String, trusted: String): Boolean {
        val a = normalize(text).replace(Regex("[^A-Z]"), "")
        val b = trusted.uppercase(Locale.US)
        if (a == b) return true
        if (a.length < 4 || abs(a.length - b.length) > 1) return false
        return editDistance(a, b) <= 1
    }

    private fun isClassToken(text: String): Boolean {
        val a = normalize(text).replace(Regex("[^A-Z1]"), "").replace('1', 'I')
        return editDistance(a, "CLASS") <= 1
    }

    private fun isCockpitTypeLine(text: String): Boolean {
        val t = normalize(text)
        val compact = t.replace(Regex("[^A-Z]"), "")
        if (compact.contains("STSMAINTENANCE")) return true
        if (compact.contains("MAINTENANCESTATUS") || editDistance(compact, "MAINTENANCESTATUS") <= 3) return true
        return listOf("OBSERVATIONS", "EWDWARNINGS", "PFD FLAGS", "ND FLAGS", "SD FLAGS", "STSINOPSYSTEM", "WARNINGLIGHTS", "INDICATORS")
            .any { compact.contains(it.replace(" ", "")) }
    }

    private fun isHeaderOrNoise(text: String): Boolean {
        val t = normalize(text)
        return t in setOf("COCKPIT EFFECTS", "FAULTS", "UTC", "FLIGHT PHASE", "SOURCE", "IDENTIFIER", "IDENTIFIERS") ||
            t.startsWith("POST FLIGHT REPORT") || t.startsWith("CMC1 PRINTING") || t.startsWith("CMC2 PRINTING") || t.startsWith("PAGE ")
    }

    private fun isFaultMetadata(text: String): Boolean {
        val t = normalize(text)
        return t.startsWith("CLASS") || isClassToken(t) ||
            t in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT") ||
            t.startsWith("SOURCE") || t.startsWith("IDENTIFIER")
    }

    private fun containsAta(text: String, digits: String?): Boolean {
        if (digits == null) return false
        val compact = normalize(text).replace(Regex("[^A-Z0-9]"), "")
        return compact.contains("ATA$digits") || compact == digits
    }

    private fun splitIdentifiers(text: String): List<String> = normalize(text)
        .replace("*", " ")
        .split(Regex("[/,;\\s]+"))
        .map { it.replace(Regex("[^A-Z0-9.-]"), "").trim() }
        .filter { it.length >= 2 && it !in setOf("SOURCE", "IDENTIFIER", "IDENTIFIERS") }

    private fun extractIdentifiers(text: String): List<String> {
        val out = linkedSetOf<String>()
        val upper = normalize(text)
        Regex("\\b\\d{1,4}[A-Z]{1,4}\\d{0,4}\\b").findAll(upper).forEach { out += it.value }
        Regex("\\b[A-Z]{2,8}\\d{1,3}\\b").findAll(upper).forEach { out += it.value }
        return out.toList()
    }

    private fun repairSingleWordAgainstSource(message: String?, source: String?): String? {
        if (message.isNullOrBlank() || source.isNullOrBlank() || message.contains(' ')) return message
        val a = normalize(message).replace(Regex("[^A-Z0-9]"), "")
        val b = normalize(source).replace(Regex("[^A-Z0-9]"), "")
        return if (a.length >= 4 && b.length >= 4 && editDistance(a, b) <= 1) source else message
    }

    private fun formatAta(digits: String): String = when (digits.length) {
        4 -> "${digits.take(2)}-${digits.drop(2)}"
        6 -> "${digits.take(2)}-${digits.substring(2, 4)}-${digits.drop(4)}"
        else -> digits
    }

    private fun normalizeSpacing(value: String): String = normalize(value)
        .replace(Regex("\\s*/\\s*"), "/")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalize(value: String): String = value.uppercase(Locale.US)
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    private fun medianHeight(words: List<Word>): Int {
        val s = words.map { it.height }.sorted()
        return if (s.isEmpty()) 10 else s[s.size / 2].coerceAtLeast(6)
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
