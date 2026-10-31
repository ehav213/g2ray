package com.a340.technicalassistant

import java.util.Locale

data class TsmSymptomRecord(
    val taskRef: String,
    val cockpitAta: String = "",
    val cockpitMessage: String = "",
    val cockpitType: String = "",
    val maintenanceAta: String = "",
    val maintenanceMessage: String = "",
    val source: String = "",
    val faultClass: String = "",
    val identifiers: List<String> = emptyList(),
    val hasFaultMessage: Boolean = false
)

/** Parses Airbus TSM FAULT SYMPTOM LIST records, including standalone
 * "Fault messages Ref. TASK ..." records that have no Warnings/Malfunction row.
 */
object TsmSymptomIndexParser {
    private val taskRef = Regex("(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}(?:-[A-Z]\\d*)?)", RegexOption.IGNORE_CASE)
    private val ata2 = Regex("\\d{2}-\\d{2}")
    private val ata3 = Regex("\\d{2}-\\d{2}-\\d{2}")
    private val marker = Regex("(?i)^(Warnings/Malfunction|Fault messages|Possible associated Warning/Malfunction|\\*\\* ON A/C|End of document)")

    fun parse(content: String): List<TsmSymptomRecord> {
        val lines = content.lines().map { it.replace('\u0002', ' ').replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<TsmSymptomRecord>()
        var i = 0
        while (i < lines.size) {
            when {
                lines[i].equals("Warnings/Malfunction", true) -> {
                    val parsed = parseWarning(lines, i)
                    if (parsed.first != null) out += parsed.first!!
                    i = maxOf(i + 1, parsed.second)
                }
                lines[i].equals("Fault messages", true) -> {
                    val ownedTask = readTaskRef(lines, i + 1, minOf(lines.size, i + 6))
                    if (ownedTask != null) {
                        val parsed = parseStandaloneFault(lines, i, ownedTask)
                        if (parsed.first != null) out += parsed.first!!
                        i = maxOf(i + 1, parsed.second)
                    } else i++
                }
                else -> i++
            }
        }
        return out.distinctBy { listOf(it.taskRef,it.cockpitAta,it.cockpitMessage,it.maintenanceAta,it.maintenanceMessage,it.source,it.faultClass,it.identifiers.joinToString("|")).joinToString("§") }
    }

    private fun parseWarning(lines: List<String>, start: Int): Pair<TsmSymptomRecord?, Int> {
        val task = readTaskRef(lines, start + 1, minOf(lines.size, start + 7)) ?: return null to (start + 1)
        val faultAt = ((start + 1) until minOf(lines.size, start + 40)).firstOrNull { lines[it].equals("Fault messages", true) }
        val nextWarning = ((start + 1) until lines.size).firstOrNull { lines[it].equals("Warnings/Malfunction", true) }
        val cockpitEnd = listOfNotNull(faultAt, nextWarning).minOrNull() ?: minOf(lines.size, start + 40)
        val h = ((start + 1) until cockpitEnd).firstOrNull { lines[it].equals("ATA Ref", true) } ?: return null to cockpitEnd
        val ataIndex = ((h + 1) until cockpitEnd).firstOrNull { ata2.matches(lines[it]) } ?: return null to cockpitEnd
        val cockpitAta = lines[ataIndex]
        val payload = lines.subList(ataIndex + 1, cockpitEnd).filterNot { it.equals("Message", true) || it.equals("Type", true) || it.equals("Ref.", true) || it.startsWith("TASK ", true) }
        if (payload.isEmpty()) return null to cockpitEnd
        val cockpitType = payload.last()
        val cockpitMessage = payload.dropLast(1).joinToString(" ").trim()

        if (faultAt != null && (nextWarning == null || faultAt < nextWarning)) {
            val next = nextMarker(lines, faultAt + 1)
            val fault = parseFaultPayload(lines, faultAt + 1, next)
            if (fault != null) {
                return TsmSymptomRecord(task, cockpitAta, cockpitMessage, cockpitType,
                    fault.ata, fault.message, fault.source, fault.clazz, fault.identifiers, true) to next
            }
        }
        return TsmSymptomRecord(task, cockpitAta, cockpitMessage, cockpitType, hasFaultMessage = false) to cockpitEnd
    }

    private fun parseStandaloneFault(lines: List<String>, start: Int, task: String): Pair<TsmSymptomRecord?, Int> {
        val next = nextMarker(lines, start + 1)
        val fault = parseFaultPayload(lines, start + 1, next) ?: return null to next
        return TsmSymptomRecord(taskRef = task, maintenanceAta = fault.ata, maintenanceMessage = fault.message,
            source = fault.source, faultClass = fault.clazz, identifiers = fault.identifiers, hasFaultMessage = true) to next
    }

    private data class FaultPayload(val ata: String, val message: String, val source: String, val clazz: String, val identifiers: List<String>)

    private fun parseFaultPayload(lines: List<String>, start: Int, end: Int): FaultPayload? {
        val ataIndex = (start until end).firstOrNull { ata3.matches(lines[it]) } ?: return null
        val classIndex = ((ataIndex + 1) until end).firstOrNull { lines[it] == "1" || lines[it] == "2" || lines[it] == "3" } ?: return null
        if (classIndex <= ataIndex + 2) return null
        val source = lines[classIndex - 1]
        val msg = lines.subList(ataIndex + 1, classIndex - 1)
            .filterNot { it.equals("Message", true) || it.equals("Source", true) || it.equals("Class", true) || it.equals("ATA Ref", true) }
            .joinToString(" ").trim()
        if (msg.isBlank()) return null
        val ids = mutableListOf<String>()
        val idAt = ((classIndex + 1) until end).firstOrNull { lines[it].equals("Identifiers", true) || lines[it].equals("Identifier", true) }
        if (idAt != null) {
            for (j in idAt + 1 until end) {
                val line = lines[j]
                if (marker.containsMatchIn(line)) break
                if (line.matches(Regex("[A-Z0-9][A-Z0-9/.,()<>-]*", RegexOption.IGNORE_CASE))) {
                    ids += line.split(Regex("[/, ]+")).map { it.trim('(',')') }.filter { it.length >= 2 }
                }
            }
        }
        return FaultPayload(lines[ataIndex], msg, source, lines[classIndex], ids.distinct())
    }

    private fun readTaskRef(lines: List<String>, start: Int, end: Int): String? {
        val joined = lines.subList(start, end).joinToString(" ")
            .replace(Regex("(?i)Ref\\.\\s*TASK\\s*"), "TASK ")
            .replace(Regex("(?i)Ref\\.\\s*"), "")
            .replace(Regex("\\s+"), " ")
        taskRef.find(joined)?.let { return it.groupValues[1].uppercase(Locale.US) }
        val compact = joined.replace(" ", "")
        return taskRef.find(compact)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
    }

    private fun nextMarker(lines: List<String>, from: Int): Int {
        for (i in from until lines.size) {
            if (lines[i].equals("Warnings/Malfunction", true) || lines[i].equals("Fault messages", true)) return i
        }
        return lines.size
    }
}
