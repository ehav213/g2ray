package com.a340.technicalassistant

object CaptainReportAnalyzer {
    data class Defect(
        val number: Int,
        val text: String,
        val ata: String?,
        val identifiers: List<String>
    ) {
        override fun toString(): String = buildString {
            append("CREW DEFECT ").append(number)
            if (!ata.isNullOrBlank()) append(" • ATA ").append(ata)
            append("\n").append(text)
            if (identifiers.isNotEmpty()) append("\nIDs: ").append(identifiers.take(5).joinToString(", "))
        }
    }

    data class Result(val rawText: String, val defects: List<Defect>)

    private val noise = listOf(
        "TECH LOG", "TECHNICAL LOG", "AIRCRAFT TECHNICAL LOG", "DEFECT LOG",
        "CAPTAIN REPORT", "CREW REPORT", "PILOT REPORT", "DATE", "FLIGHT",
        "FLIGHT NO", "FLT NO", "A/C REG", "AIRCRAFT REG", "STATION", "SECTOR",
        "FROM", "TO", "SIGNATURE", "CAPTAIN", "COMMANDER", "RECTIFICATION",
        "ACTION TAKEN", "ENGINEER", "LICENCE", "LICENSE", "TIME", "PAGE"
    )

    private val defectWords = listOf(
        "INOP", "INOPERATIVE", "FAULT", "FAIL", "FAILED", "FAILURE", "INTERMITTENT",
        "NOISE", "VIB", "VIBRATION", "SMELL", "ODOR", "ODOUR", "LEAK", "LEAKING",
        "STIFF", "JAM", "JAMMED", "LOOSE", "BROKEN", "DAMAGED", "CRACK", "DENT",
        "HOT", "OVERHEAT", "OVHT", "LOW", "HIGH", "ERRATIC", "FLUCTUAT", "DISAGREE",
        "NOT WORK", "NOT OPERAT", "SLOW", "HARD TO", "DIFFICULT", "WARNING", "CAUTION",
        "MESSAGE", "LIGHT", "INDICATION", "INDICATOR", "TRIP", "RESET", "OPEN", "CLOSE"
    )

    private val technicalWords = listOf(
        "ENG", "ENGINE", "APU", "PACK", "BLEED", "FUEL", "PUMP", "VALVE", "HYD",
        "BRAKE", "GEAR", "NWS", "WHEEL", "TIRE", "TYRE", "DOOR", "WINDOW", "SEAT",
        "LIGHT", "ECAM", "DISPLAY", "RADIO", "VHF", "HF", "NAV", "GPS", "ADIRS",
        "FLAP", "SLAT", "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "TRIM", "OXYGEN",
        "WATER", "WASTE", "LAV", "GALLEY", "CABIN", "COCKPIT", "WINDSHIELD", "WIPER",
        "TEMP", "PRESS", "PRESSURE", "GEN", "GENERATOR", "BATTERY", "BUS", "FAN"
    )

    private val ataKeywords = linkedMapOf(
        "21" to listOf("PACK", "AIR COND", "AIR CONDITION", "CABIN TEMP", "CABIN PRESS"),
        "22" to listOf("AUTOPILOT", "AUTO FLIGHT", "A/P", "FLIGHT DIRECTOR"),
        "23" to listOf("VHF", "HF ", "RADIO", "SATCOM", "COMM"),
        "24" to listOf("GEN", "GENERATOR", "BATTERY", "BUS", "ELEC"),
        "25" to listOf("SEAT", "GALLEY", "CABIN EQUIP", "FURNISH"),
        "26" to listOf("FIRE", "SMOKE", "EXTING"),
        "27" to listOf("FLAP", "SLAT", "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "TRIM"),
        "28" to listOf("FUEL", "TANK PUMP", "TK PUMP"),
        "29" to listOf("HYD", "HYDRAULIC", "RESERVOIR", "RSVR"),
        "30" to listOf("ANTI ICE", "ANTI-ICE", "WIPER", "WINDSHIELD HEAT"),
        "31" to listOf("ECAM", "DISPLAY", "FWS", "INDICATION"),
        "32" to listOf("GEAR", "BRAKE", "NWS", "WHEEL", "TIRE", "TYRE"),
        "33" to listOf("LIGHT", "LIGHTING"),
        "34" to listOf("ADIRS", "ADR", "NAV", "GPS", "RAD ALT"),
        "35" to listOf("OXYGEN", "OXY"),
        "36" to listOf("PNEU", "PNEUMATIC", "BLEED"),
        "38" to listOf("WATER", "WASTE", "LAV"),
        "49" to listOf("APU"),
        "52" to listOf("DOOR", "CARGO DOOR")
    )

    fun analyze(text: String): Result {
        val lines = text.lines()
            .map { normalize(it) }
            .filter { it.length >= 3 }
            .filterNot(::isNoise)

        val selected = mutableListOf<String>()
        for (line in lines) {
            if (looksLikeDefect(line)) {
                selected += line
            } else if (selected.isNotEmpty() && line.length in 4..45 && looksTechnical(line)) {
                val prev = selected.last()
                if (prev.length < 100) selected[selected.lastIndex] = "$prev $line"
            }
        }

        // If no defect keyword survives OCR, retain the longest technical-looking lines so
        // handwritten/abbreviated crew reports are still searchable rather than discarded.
        val fallback = if (selected.isEmpty()) {
            lines.filter { looksTechnical(it) }.sortedByDescending { it.length }.take(8)
        } else emptyList()

        val unique = linkedSetOf<String>()
        (selected + fallback).forEach { if (it.length >= 4) unique += it }

        val defects = unique.take(20).mapIndexed { index, line ->
            Defect(
                number = index + 1,
                text = line,
                ata = detectAta(line),
                identifiers = ComponentAnalyzer.analyze(line).identifiers
            )
        }
        return Result(text.trim(), defects)
    }

    private fun normalize(s: String): String = s.uppercase()
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim(' ', ':', ';', '|')

    private fun isNoise(line: String): Boolean {
        if (noise.any { line == it || line.startsWith("$it ") }) return true
        if (line.matches(Regex("PAGE\\s+\\d+.*"))) return true
        if (line.matches(Regex("\\d{1,2}[:.]\\d{2}.*"))) return true
        return false
    }

    private fun looksTechnical(line: String): Boolean {
        if (technicalWords.any { line.contains(it) }) return true
        return Regex("\\b(?=[A-Z0-9/-]{4,20}\\b)(?=[A-Z0-9/-]*[A-Z])(?=[A-Z0-9/-]*\\d)[A-Z0-9/-]+\\b")
            .containsMatchIn(line)
    }

    private fun looksLikeDefect(line: String): Boolean {
        val hasDefect = defectWords.any { line.contains(it) }
        return hasDefect && (looksTechnical(line) || line.split(' ').size >= 3)
    }

    private fun detectAta(line: String): String? {
        Regex("\\b(\\d{2})[- ]\\d{2}(?:[- ]\\d{2})?\\b").find(line)?.let { return it.groupValues[1] }
        for ((ata, words) in ataKeywords) {
            if (words.any { line.contains(it) }) return ata
        }
        return null
    }
}
