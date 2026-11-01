package com.a340.technicalassistant

import java.util.Locale

/**
 * Sidecar parser for Airbus TSM FAULT SYMPTOM LIST cockpit/status rows that
 * can legitimately have a blank ATA Ref and are therefore not reliably
 * represented by the legacy/core TsmSymptomIndexParser.
 *
 * IMPORTANT: This parser does not alter TSM content, the core index parser,
 * task selection rules, or fault-isolation logic. It only contributes exact
 * warning/status index rows for the supported non-ECAM Type labels below.
 */
object TsmCockpitStatusIndexParser {
    private val taskRef = Regex("(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}(?:-[A-Z]\\d*)?)", RegexOption.IGNORE_CASE)
    private val ata2 = Regex("\\d{2}-\\d{2}")

    private val supportedTypes = setOf(
        "OBSERVATIONS",
        "SD FLAG(S)",
        "SD ADVISORY(IES)",
        "WARNING LIGHT(S)",
        "ND FLAG(S)",
        "PFD FLAG(S)",
        "EWD FLAG(S)",
        "INDICATOR(S)"
    )

    private val anyType = Regex(
        "(?i)(SD\\s+ADVISORY\\(IES\\)|WARNING\\s+LIGHT\\(S\\)|EWD\\s+WARNING\\(S\\)|" +
            "STS-MAINTENANCE|STS-INOP\\s+SYSTEM|OBSERVATIONS|ND\\s+FLAG\\(S\\)|" +
            "PFD\\s+FLAG\\(S\\)|EWD\\s+FLAG\\(S\\)|SD\\s+FLAG\\(S\\)|INDICATOR\\(S\\))"
    )

    fun parse(content: String): List<TsmSymptomRecord> {
        val lines = content.lines()
            .map { it.replace('\u0002', ' ').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        val out = mutableListOf<TsmSymptomRecord>()
        var i = 0
        while (i < lines.size) {
            if (!isWarningMarker(lines[i])) {
                i++
                continue
            }
            val next = nextWarning(lines, i + 1)
            val end = if (next > i) next else lines.size
            out += parseWarningBlock(lines.subList(i, end))
            i = if (next > i) next else i + 1
        }
        return out.distinctBy {
            "${it.taskRef}|${it.cockpitAta}|${it.cockpitMessage}|${it.cockpitType}"
        }
    }

    private fun parseWarningBlock(block: List<String>): List<TsmSymptomRecord> {
        if (block.isEmpty()) return emptyList()
        val task = readTask(block) ?: return emptyList()
        val out = mutableListOf<TsmSymptomRecord>()

        var i = 0
        while (i < block.size) {
            val headerEnd = headerEnd(block, i)
            if (headerEnd == null) {
                i++
                continue
            }
            val segmentEnd = nextTableBoundary(block, headerEnd)
            parseTypeRows(block.subList(headerEnd, segmentEnd), task).forEach(out::add)
            i = if (segmentEnd > i) segmentEnd else i + 1
        }
        return out
    }

    private fun parseTypeRows(lines: List<String>, task: String): List<TsmSymptomRecord> {
        if (lines.isEmpty()) return emptyList()
        val text = lines
            .filterNot(::isNoiseLabel)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return emptyList()

        val out = mutableListOf<TsmSymptomRecord>()
        var cursor = 0
        for (match in anyType.findAll(text)) {
            var row = text.substring(cursor, match.range.first).trim()
            cursor = match.range.last + 1
            val type = canonicalType(match.value) ?: continue

            var cockpitAta = ""
            val ataMatch = Regex("^(\\d{2}-\\d{2})\\s+").find(row)
            if (ataMatch != null && ata2.matches(ataMatch.groupValues[1])) {
                cockpitAta = ataMatch.groupValues[1]
                row = row.substring(ataMatch.range.last + 1).trim()
            }

            if (type in supportedTypes && row.isNotBlank()) {
                out += TsmSymptomRecord(
                    taskRef = task,
                    cockpitAta = cockpitAta,
                    cockpitMessage = row,
                    cockpitType = type,
                    hasFaultMessage = false
                )
            }
        }
        return out
    }

    private fun readTask(block: List<String>): String? {
        val joined = block.joinToString(" ")
            .replace(Regex("(?i)Ref\\.\\s*TASK\\s*"), "TASK ")
            .replace(Regex("\\s+"), " ")
        taskRef.find(joined.replace(" ", ""))?.let {
            return it.groupValues[1].uppercase(Locale.US)
        }
        return taskRef.find(joined)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
    }

    private fun headerEnd(lines: List<String>, at: Int): Int? {
        val compact = lines[at].uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
        if (compact.contains("ATAREFMESSAGETYPE")) return at + 1

        if (compact == "ATAREF") {
            var j = at + 1
            var sawMessage = false
            while (j < minOf(lines.size, at + 4)) {
                val c = lines[j].uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
                if (c == "MESSAGE") sawMessage = true
                if (sawMessage && c == "TYPE") return j + 1
                j++
            }
        }
        return null
    }

    private fun nextTableBoundary(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (headerEnd(lines, i) != null) return i
            val line = lines[i]
            if (line.startsWith("Fault messages", true) ||
                line.startsWith("Possible associated Warning/Malfunction", true) ||
                line.startsWith("Identifiers", true) ||
                line.startsWith("Customer :", true) ||
                line.startsWith("Manual :", true) ||
                line.startsWith("Print Date", true) ||
                line.startsWith("End of document", true)) return i
        }
        return lines.size
    }

    private fun isNoiseLabel(line: String): Boolean {
        val compact = line.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
        return compact == "ATAREF" || compact == "MESSAGE" || compact == "TYPE" ||
            line.equals("Ref.", true) || line.startsWith("TASK ", true) ||
            line.startsWith("** ON A/C", true) || line.startsWith("EMB SB", true) ||
            line.startsWith("PRE SB", true) || line.startsWith("POST SB", true)
    }

    private fun canonicalType(raw: String): String? {
        val compact = raw.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
        return when (compact) {
            "OBSERVATIONS" -> "OBSERVATIONS"
            "SDFLAGS" -> "SD FLAG(S)"
            "SDADVISORYIES" -> "SD ADVISORY(IES)"
            "WARNINGLIGHTS" -> "WARNING LIGHT(S)"
            "NDFLAGS" -> "ND FLAG(S)"
            "PFDFLAGS" -> "PFD FLAG(S)"
            "EWDFLAGS" -> "EWD FLAG(S)"
            "INDICATORS" -> "INDICATOR(S)"
            "EWDWARNINGS" -> "EWD WARNING(S)"
            "STSMAINTENANCE" -> "STS-MAINTENANCE"
            "STSINOPSYSTEM" -> "STS-INOP SYSTEM"
            else -> null
        }
    }

    private fun isWarningMarker(line: String): Boolean = line.startsWith("Warnings/Malfunction", true)

    private fun nextWarning(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (isWarningMarker(lines[i])) return i
        }
        return lines.size
    }
}
