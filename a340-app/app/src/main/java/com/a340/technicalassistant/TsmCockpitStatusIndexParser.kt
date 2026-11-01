package com.a340.technicalassistant

import java.util.Locale

/**
 * Sidecar parser for Airbus TSM FAULT SYMPTOM LIST warning/status rows that
 * legitimately have a blank ATA Ref and are therefore not parsed by the
 * legacy/core TsmSymptomIndexParser. This does not alter TSM content or the
 * existing parser; it only adds explicit routing for OBSERVATIONS and
 * SD FLAG(S) rows.
 */
object TsmCockpitStatusIndexParser {
    private val taskRef = Regex("(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}(?:-[A-Z]\\d*)?)", RegexOption.IGNORE_CASE)
    private val ata2 = Regex("\\d{2}-\\d{2}")
    private val supportedType = Regex("(?i)(OBSERVATIONS|SD\\s*FLAG\\(S\\))\\s*$")

    fun parse(content: String): List<TsmSymptomRecord> {
        val lines = content.lines()
            .map { it.replace('\u0002', ' ').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        val out = mutableListOf<TsmSymptomRecord>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].equals("Warnings/Malfunction", true)) {
                i++
                continue
            }
            val next = nextWarning(lines, i + 1)
            val end = if (next > i) next else lines.size
            parseWarningBlock(lines.subList(i, end))?.let(out::add)
            i = if (next > i) next else i + 1
        }
        return out.distinctBy { "${it.taskRef}|${it.cockpitAta}|${it.cockpitMessage}|${it.cockpitType}" }
    }

    private fun parseWarningBlock(block: List<String>): TsmSymptomRecord? {
        if (block.isEmpty()) return null
        val joined = block.joinToString(" ")
            .replace(Regex("(?i)Ref\\.\\s*TASK\\s*"), "TASK ")
            .replace(Regex("\\s+"), " ")
        val task = taskRef.find(joined.replace(" ", ""))?.groupValues?.getOrNull(1)
            ?: taskRef.find(joined)?.groupValues?.getOrNull(1)
            ?: return null

        val headerAt = block.indexOfFirst {
            val n = it.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")
            n.contains("ATAREF") && n.contains("MESSAGE") && n.contains("TYPE")
        }
        if (headerAt < 0 || headerAt + 1 >= block.size) return null

        val payloadLines = block.subList(headerAt + 1, block.size)
            .filterNot { line ->
                line.startsWith("TASK ", true) ||
                    line.equals("Ref.", true) ||
                    line.equals("ATA Ref", true) ||
                    line.equals("Message", true) ||
                    line.equals("Type", true) ||
                    line.startsWith("Fault messages", true) ||
                    line.startsWith("Possible associated Warning/Malfunction", true)
            }
        if (payloadLines.isEmpty()) return null

        var payload = payloadLines.joinToString(" ").trim()
        val typeMatch = supportedType.find(payload) ?: return null
        val cockpitType = typeMatch.value.trim().replace(Regex("\\s+"), " ").uppercase(Locale.US)
        payload = payload.substring(0, typeMatch.range.first).trim()
        if (payload.isBlank()) return null

        var cockpitAta = ""
        val ataMatch = Regex("^(\\d{2}-\\d{2})\\s+").find(payload)
        if (ataMatch != null && ata2.matches(ataMatch.groupValues[1])) {
            cockpitAta = ataMatch.groupValues[1]
            payload = payload.substring(ataMatch.range.last + 1).trim()
        }
        if (payload.isBlank()) return null

        return TsmSymptomRecord(
            taskRef = task.uppercase(Locale.US),
            cockpitAta = cockpitAta,
            cockpitMessage = payload,
            cockpitType = cockpitType,
            hasFaultMessage = false
        )
    }

    private fun nextWarning(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (lines[i].equals("Warnings/Malfunction", true)) return i
        }
        return lines.size
    }
}
