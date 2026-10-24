package com.a340.technicalassistant

object PostFlightAnalyzer {
    data class Fault(
        val number: Int,
        val category: String,
        val text: String,
        val ata: String?,
        val identifiers: List<String>
    ) {
        override fun toString(): String = buildString {
            append(category).append(" ").append(number)
            if (!ata.isNullOrBlank()) append(" • ATA ").append(ata)
            append("\n").append(text)
            if (identifiers.isNotEmpty()) append("\nIDs: ").append(identifiers.take(5).joinToString(", "))
        }
    }

    data class Result(val rawText: String, val faults: List<Fault>)

    private data class Seed(val category: String, val text: String, val ata: String?)

    private val explicitFaultTerms = listOf(
        "FAULT", "FAIL", "FAILURE", "INOP", "INOPERATIVE", "WARNING", "CAUTION",
        "OVHT", "OVERHEAT", "LEAK", "JAM", "DISAGREE", "LO PR", "LOW PR",
        "LOW PRESS", "HI PR", "HIGH PR", "LOW TEMP", "HIGH TEMP", "SMOKE", "FIRE",
        "DISABLED", "OFF", "LOW"
    )

    private val technicalTerms = listOf(
        "PUMP", "VALVE", "SENSOR", "PRESS", "PRESSURE", "TEMP", "GEN", "BUS",
        "PACK", "BLEED", "FUEL", "HYD", "BRAKE", "DOOR", "SLAT", "SLT", "FLAP",
        "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "ENGINE", "ENG", "APU",
        "FAN", "VIB", "OIL", "DUCT", "RESERVOIR", "RSVR", "DET", "DETECTOR",
        "QAR", "TAPE", "VENT", "XFR", "PRESS SW", "SMK"
    )

    private val noiseExact = setOf(
        "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "PFR", "A/C IDENT", "AC IDENT",
        "DATE", "FLT NBR", "FLIGHT NBR", "FROM/TO", "START/END", "CMC PRINTING",
        "CMC1 PRINTING", "CMC2 PRINTING", "REPORT", "FAULT REPORT", "UTC", "FLIGHT PHASE",
        "IDENTIFIERS", "MAINTENANCE", "END OF REPORT"
    )

    private val ataKeywords = linkedMapOf(
        "21" to listOf("PACK", "AIR COND", "AIR CONDITION", "TRIM AIR", "CABIN PRESS", "VENT"),
        "22" to listOf("AUTO FLIGHT", "AUTOPILOT", "A/P", "FLIGHT DIRECTOR"),
        "23" to listOf("VHF", "HF ", "COMM", "SATCOM"),
        "24" to listOf("ELEC", "GEN", "GENERATOR", "BUS", "BATTERY", "TRU"),
        "26" to listOf("FIRE", "SMOKE", "SMK", "FIRE DET", "SMOKE DET", "SMK DET", "FIRE DETECTOR", "SMOKE DETECTOR", "EXTING"),
        "27" to listOf("FLIGHT CONTROL", "AILERON", "RUDDER", "ELEVATOR", "SPOILER", "FLAP", "SLAT", "SLT"),
        "28" to listOf("FUEL", "TK PUMP", "TANK PUMP", "XFR"),
        "29" to listOf("HYD", "HYDRAULIC", "RSVR", "RESERVOIR"),
        "30" to listOf("ANTI ICE", "ANTI-ICE", "ICE DET", "ICE DETECTOR"),
        "31" to listOf("ECAM", "DISPLAY", "FWS", "WARNING COMPUTER", "QAR"),
        "32" to listOf("LANDING GEAR", "BRAKE", "NWS", "WHEEL", "TIRE", "TYRE"),
        "33" to listOf("LIGHT", "LIGHTING"),
        "34" to listOf("ADIRS", "ADR", "IR ", "NAV", "RAD ALT", "GPS"),
        "35" to listOf("OXY", "OXYGEN"),
        "36" to listOf("BLEED", "XBLEED", "CROSSBLEED", "PNEU", "PNEUMATIC"),
        "38" to listOf("WATER", "WASTE", "LAV"),
        "45" to listOf("CMC", "CENTRAL MAINT"),
        "49" to listOf("APU"),
        "52" to listOf("DOOR")
    )

    private val ataLine = Regex("^ATA\\s*(\\d{2,6})\\b.*")

    fun analyze(text: String): Result {
        val lines = text.lines()
            .map { normalize(it) }
            .filter { it.length >= 2 }

        val seeds = mutableListOf<Seed>()

        // Airbus PFR maintenance-fault blocks usually follow:
        // ATA xxxxxx -> CLASS -> HARD/INTERMITTENT -> fault text -> SOURCE.
        var i = 0
        while (i < lines.size) {
            val ataMatch = ataLine.find(lines[i])
            if (ataMatch != null) {
                val ataDigits = ataMatch.groupValues[1]
                val ata = ataDigits.take(2).takeIf { it.length == 2 }
                var j = i + 1
                var message: String? = null
                while (j < lines.size && j <= i + 9) {
                    val next = lines[j]
                    if (ataLine.matches(next) || isSectionBoundary(next)) break
                    if (!isBlockMetadata(next) && !isNoise(next)) {
                        if (message == null) {
                            message = next
                        } else if (needsContinuation(message) && !next.startsWith("SOURCE ")) {
                            message = "$message $next"
                        }
                    }
                    j++
                }
                if (!message.isNullOrBlank()) seeds += Seed("MAINT FAULT", message, ata)
            }
            i++
        }

        // Cockpit effects are commonly printed as a message followed by ATA on the next line.
        for (index in lines.indices) {
            val line = lines[index]
            if (isNoise(line) || isBlockMetadata(line) || ataLine.matches(line)) continue
            if (!containsExplicitFault(line)) continue
            var ata: String? = null
            for (lookAhead in 1..2) {
                val p = index + lookAhead
                if (p >= lines.size) break
                val m = ataLine.find(lines[p])
                if (m != null) {
                    ata = m.groupValues[1].take(2)
                    break
                }
            }
            seeds += Seed("COCKPIT EFFECT", line, ata ?: detectAta(line))
        }

        // Fallback for OCR captures that lose ATA/class/source layout.
        for (line in lines) {
            if (isNoise(line) || isBlockMetadata(line) || ataLine.matches(line)) continue
            if (isFaultCandidate(line)) seeds += Seed("FAULT", line, detectAta(line))
        }

        val unique = linkedMapOf<String, Seed>()
        for (seed in seeds) {
            val key = seed.text.replace(Regex("[^A-Z0-9]+"), " ").trim()
            if (key.length >= 4 && !unique.containsKey(key)) unique[key] = seed
        }

        val faults = unique.values.take(24).mapIndexed { index, seed ->
            val identifiers = ComponentAnalyzer.analyze(seed.text).identifiers
            Fault(index + 1, seed.category, seed.text, seed.ata ?: detectAta(seed.text), identifiers)
        }
        return Result(text.trim(), faults)
    }

    private fun normalize(s: String): String = s.uppercase()
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim(' ', ':', ';', '|')

    private fun isSectionBoundary(line: String): Boolean =
        line.startsWith("03 COCKPIT EFFECT") ||
        line.startsWith("05 FAULT") ||
        line.startsWith("END OF REPORT") ||
        line.startsWith("POST FLIGHT REPORT")

    private fun isBlockMetadata(line: String): Boolean {
        if (line.matches(Regex("^CLASS\\s+\\d+.*"))) return true
        if (line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) return true
        if (line.startsWith("SOURCE ")) return true
        if (line.startsWith("CIRCUIT/")) return false
        if (line.matches(Regex("^\\d{3,4}$"))) return true
        if (line.matches(Regex("^\\d{2}$"))) return true
        if (line.matches(Regex("^LEG\\s+\\d+.*"))) return true
        return line in setOf("UTC", "FLIGHT PHASE", "IDENTIFIERS", "MAINTENANCE")
    }

    private fun needsContinuation(s: String): Boolean =
        s.count { it == '(' } > s.count { it == ')' } || s.endsWith("/") || s.endsWith("-")

    private fun isNoise(line: String): Boolean {
        if (line in noiseExact) return true
        if (line.matches(Regex("PAGE\\s+\\d+\\s*/\\s*\\d+.*"))) return true
        if (line.matches(Regex("[A-Z]{3,4}\\d?\\s+PRINTING.*"))) return true
        if (line.startsWith("A/C IDENT ") || line.startsWith("FLT NBR ") || line.startsWith("FROM/TO ")) return true
        if (line.startsWith("DATE ") && line.length < 20) return true
        return false
    }

    private fun containsExplicitFault(line: String): Boolean = explicitFaultTerms.any { line.contains(it) }

    private fun isFaultCandidate(line: String): Boolean {
        if (containsExplicitFault(line) && technicalTerms.any { line.contains(it) }) return true
        val words = line.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return false
        val hasTechnical = technicalTerms.any { line.contains(it) }
        val hasIdentifier = Regex("\\b(?=[A-Z0-9/-]{3,20}\\b)(?=[A-Z0-9/-]*[A-Z])(?=[A-Z0-9/-]*\\d)[A-Z0-9/-]+\\b")
            .containsMatchIn(line)
        return hasTechnical && hasIdentifier
    }

    private fun detectAta(line: String): String? {
        ataLine.find(line)?.let { return it.groupValues[1].take(2) }
        Regex("\\b(\\d{2})[- ]\\d{2}(?:[- ]\\d{2})?\\b").find(line)?.let { return it.groupValues[1] }
        for ((ata, words) in ataKeywords) {
            if (words.any { line.contains(it) }) return ata
        }
        return null
    }
}
