package com.a340.technicalassistant

object PostFlightAnalyzer {
    data class Fault(
        val number: Int,
        val text: String,
        val ata: String?,
        val identifiers: List<String>
    ) {
        override fun toString(): String = buildString {
            append("FAULT ").append(number)
            if (!ata.isNullOrBlank()) append(" • ATA ").append(ata)
            append("\n").append(text)
            if (identifiers.isNotEmpty()) append("\nIDs: ").append(identifiers.take(5).joinToString(", "))
        }
    }

    data class Result(val rawText: String, val faults: List<Fault>)

    private val explicitFaultTerms = listOf(
        "FAULT", "FAIL", "FAILURE", "INOP", "INOPERATIVE", "WARNING", "CAUTION",
        "OVHT", "OVERHEAT", "LEAK", "JAM", "DISAGREE", "LO PR", "LOW PR",
        "LOW PRESS", "HI PR", "HIGH PR", "LOW TEMP", "HIGH TEMP", "SMOKE", "FIRE"
    )

    private val technicalTerms = listOf(
        "PUMP", "VALVE", "SENSOR", "PRESS", "PRESSURE", "TEMP", "GEN", "BUS",
        "PACK", "BLEED", "FUEL", "HYD", "BRAKE", "DOOR", "SLAT", "FLAP",
        "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "ENGINE", "ENG", "APU",
        "FAN", "VIB", "OIL", "DUCT", "RESERVOIR", "RSVR", "DET", "DETECTOR"
    )

    private val noiseExact = setOf(
        "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "PFR", "A/C IDENT", "AC IDENT",
        "DATE", "FLT NBR", "FLIGHT NBR", "FROM/TO", "START/END", "CMC PRINTING",
        "CMC1 PRINTING", "CMC2 PRINTING", "REPORT", "FAULT REPORT"
    )

    private val ataKeywords = linkedMapOf(
        "21" to listOf("PACK", "AIR COND", "AIR CONDITION", "BLEED", "TRIM AIR", "CABIN PRESS"),
        "22" to listOf("AUTO FLIGHT", "AUTOPILOT", "A/P", "FLIGHT DIRECTOR"),
        "23" to listOf("VHF", "HF ", "COMM", "SATCOM"),
        "24" to listOf("ELEC", "GEN", "GENERATOR", "BUS", "BATTERY", "TRU"),
        "26" to listOf("FIRE", "SMOKE", "DETECTOR", "EXTING"),
        "27" to listOf("FLIGHT CONTROL", "AILERON", "RUDDER", "ELEVATOR", "SPOILER", "FLAP", "SLAT"),
        "28" to listOf("FUEL", "TK PUMP", "TANK PUMP"),
        "29" to listOf("HYD", "HYDRAULIC", "RSVR", "RESERVOIR"),
        "30" to listOf("ANTI ICE", "ANTI-ICE", "ICE DET"),
        "31" to listOf("ECAM", "DISPLAY", "FWS", "WARNING COMPUTER"),
        "32" to listOf("LANDING GEAR", "BRAKE", "NWS", "WHEEL", "TIRE", "TYRE"),
        "33" to listOf("LIGHT", "LIGHTING"),
        "34" to listOf("ADIRS", "ADR", "IR ", "NAV", "RAD ALT", "GPS"),
        "35" to listOf("OXY", "OXYGEN"),
        "36" to listOf("PNEU", "PNEUMATIC"),
        "38" to listOf("WATER", "WASTE", "LAV"),
        "45" to listOf("CMC", "CENTRAL MAINT"),
        "49" to listOf("APU"),
        "52" to listOf("DOOR")
    )

    fun analyze(text: String): Result {
        val rawLines = text.lines()
            .map { normalize(it) }
            .filter { it.length >= 3 }

        val candidates = mutableListOf<String>()
        for (line in rawLines) {
            if (isNoise(line)) continue
            if (isFaultCandidate(line)) candidates += line
        }

        // OCR can split a message across two short lines. Merge a short technical continuation
        // into the previous fault when it does not look like a report header.
        val merged = mutableListOf<String>()
        for (line in candidates) {
            if (merged.isNotEmpty() && line.length < 18 && !containsExplicitFault(line)) {
                val previous = merged.last()
                if (previous.length < 70) {
                    merged[merged.lastIndex] = "$previous $line"
                    continue
                }
            }
            merged += line
        }

        val unique = linkedSetOf<String>()
        merged.forEach { if (it.length >= 4) unique += it }

        val faults = unique.take(20).mapIndexed { index, line ->
            val identifiers = ComponentAnalyzer.analyze(line).identifiers
            Fault(index + 1, line, detectAta(line), identifiers)
        }
        return Result(text.trim(), faults)
    }

    private fun normalize(s: String): String = s.uppercase()
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim(' ', ':', ';', '|')

    private fun isNoise(line: String): Boolean {
        if (line in noiseExact) return true
        if (line.matches(Regex("PAGE\\s+\\d+\\s*/\\s*\\d+.*"))) return true
        if (line.matches(Regex("\\d{1,2}[:.]\\d{2}.*"))) return true
        if (line.matches(Regex("[A-Z]{3,4}\\d?\\s+PRINTING.*"))) return true
        if (line.startsWith("A/C IDENT ") || line.startsWith("FLT NBR ") || line.startsWith("FROM/TO ")) return true
        return false
    }

    private fun containsExplicitFault(line: String): Boolean = explicitFaultTerms.any { line.contains(it) }

    private fun isFaultCandidate(line: String): Boolean {
        if (containsExplicitFault(line)) return true
        val words = line.split(' ').filter { it.isNotBlank() }
        if (words.size < 3) return false

        val hasTechnical = technicalTerms.any { line.contains(it) }
        val hasAbnormal = Regex("\\b(LO|LOW|HI|HIGH|OFF|OPEN|CLOSED|PR|TEMP)\\b").containsMatchIn(line)
        if (hasTechnical && hasAbnormal) return true

        // PFR/CMC messages are typically uppercase compact technical lines. Require a technical
        // keyword or mixed alpha-numeric identifier so ordinary report metadata is rejected.
        val hasIdentifier = Regex("\\b(?=[A-Z0-9/-]{4,20}\\b)(?=[A-Z0-9/-]*[A-Z])(?=[A-Z0-9/-]*\\d)[A-Z0-9/-]+\\b")
            .containsMatchIn(line)
        return hasTechnical && hasIdentifier
    }

    private fun detectAta(line: String): String? {
        Regex("\\b(\\d{2})[- ]\\d{2}(?:[- ]\\d{2})?\\b").find(line)?.let { return it.groupValues[1] }
        for ((ata, words) in ataKeywords) {
            if (words.any { line.contains(it) }) return ata
        }
        return null
    }
}
