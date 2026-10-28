package com.a340.technicalassistant

object PostFlightAnalyzer {
    data class Context(
        val utc: String? = null,
        val flightPhase: String? = null,
        val cockpitEffects: List<String> = emptyList()
    )

    data class Fault(
        val number: Int,
        val category: String,
        val text: String,
        val ata: String?,
        val identifiers: List<String>,
        val ataFull: String? = null,
        val utc: String? = null,
        val flightPhase: String? = null,
        val faultClass: String? = null,
        val persistence: String? = null,
        val source: String? = null,
        val cockpitEffect: String? = null
    ) {
        fun searchQuery(): String = buildString {
            append(text)
            val a = ataFull ?: ata
            if (!a.isNullOrBlank()) append(" ATA ").append(a)
        }

        fun displaySummary(): String = buildString {
            append("MAINTENANCE FAULT\n")
            append(text)
            ataFull?.let { append("\nATA: ").append(it) } ?: ata?.let { append("\nATA: ").append(it) }
            utc?.let { append("\nUTC: ").append(formatUtc(it)) }
            flightPhase?.let { append("\nFLIGHT PHASE: ").append(it) }
            faultClass?.let { append("\nCLASS: ").append(it) }
            persistence?.let { append("\nSTATUS: ").append(it) }
            source?.let { append("\nSOURCE: ").append(it) }
            cockpitEffect?.let { append("\nCOCKPIT EFFECT: ").append(it) }
        }

        override fun toString(): String = displaySummary()
    }

    data class Result(
        val rawText: String,
        val context: Context,
        val faults: List<Fault>
    )

    private data class Seed(
        val text: String,
        val ata: String?,
        val ataFull: String?,
        val utc: String?,
        val flightPhase: String?,
        val faultClass: String?,
        val persistence: String?,
        val source: String?
    )

    private val explicitFaultTerms = listOf(
        "FAULT", "FAIL", "FAILURE", "INOP", "INOPERATIVE", "WARNING", "CAUTION",
        "OVHT", "OVERHEAT", "LEAK", "RUPTURE", "OPEN CIRCUIT", "SHORT CIRCUIT", "JAM",
        "DISAGREE", "LO PR", "LOW PR", "LOW PRESS", "HI PR", "HIGH PR", "LOW TEMP",
        "HIGH TEMP", "SMOKE", "FIRE", "DISABLED", "OFF", "LOW", "NO SIGNAL", "NO DATA"
    )

    private val technicalTerms = listOf(
        "PUMP", "VALVE", "SENSOR", "SENSING", "PRESS", "PRESSURE", "TEMP", "GEN", "BUS",
        "PACK", "BLEED", "FUEL", "HYD", "BRAKE", "DOOR", "SLAT", "SLT", "FLAP",
        "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "ENGINE", "ENG", "APU", "BMC",
        "FAN", "VIB", "OIL", "DUCT", "RESERVOIR", "RSVR", "DET", "DETECTOR", "LOOP",
        "QAR", "TAPE", "VENT", "XFR", "PRESS SW", "SMK"
    )

    private val noiseExact = setOf(
        "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "PFR", "A/C IDENT", "AC IDENT",
        "DATE", "FLT NBR", "FLIGHT NBR", "FROM/TO", "START/END", "CMC PRINTING",
        "CMC1 PRINTING", "CMC2 PRINTING", "REPORT", "FAULT REPORT", "UTC", "FLIGHT PHASE",
        "IDENTIFIERS", "MAINTENANCE", "END OF REPORT", "MAINTENANCE STATUS"
    )

    private val phaseNames = listOf(
        "ENGINE START", "TAXI OUT", "TAKEOFF", "CLIMB", "CRUISE", "DESCENT", "APPROACH",
        "LANDING", "TAXI IN", "ENGINE SHUTDOWN", "ON GROUND", "GROUND"
    )

    private val ataKeywords = linkedMapOf(
        "21" to listOf("AIR COND", "AIR CONDITION", "TRIM AIR", "CABIN PRESS", "VENT"),
        "22" to listOf("AUTO FLIGHT", "AUTOPILOT", "A/P", "FLIGHT DIRECTOR"),
        "23" to listOf("VHF", "HF ", "COMM", "SATCOM"),
        "24" to listOf("ELEC", "GENERATOR", "BUS", "BATTERY", "TRU"),
        "26" to listOf("FIRE", "SMOKE", "SMK", "FIRE DET", "SMOKE DET", "EXTING"),
        "27" to listOf("FLIGHT CONTROL", "AILERON", "RUDDER", "ELEVATOR", "SPOILER", "FLAP", "SLAT"),
        "28" to listOf("FUEL", "TK PUMP", "TANK PUMP", "XFR"),
        "29" to listOf("HYD", "HYDRAULIC", "RSVR", "RESERVOIR"),
        "30" to listOf("ANTI ICE", "ANTI-ICE", "ICE DET", "ICE DETECTOR"),
        "31" to listOf("ECAM", "DISPLAY", "FWS", "WARNING COMPUTER", "QAR"),
        "32" to listOf("LANDING GEAR", "BRAKE", "NWS", "WHEEL", "TIRE", "TYRE"),
        "33" to listOf("LIGHT", "LIGHTING"),
        "34" to listOf("ADIRS", "ADR", "NAV", "RAD ALT", "GPS"),
        "35" to listOf("OXY", "OXYGEN"),
        "36" to listOf("BLEED", "XBLEED", "CROSSBLEED", "PNEU", "PNEUMATIC", "BMC", "LOOP"),
        "38" to listOf("WATER", "WASTE", "LAV"),
        "45" to listOf("CMC", "CENTRAL MAINT"),
        "49" to listOf("APU"),
        "52" to listOf("DOOR")
    )

    private val ataLine = Regex("^ATA\\s*(\\d{4,6})\\b.*")

    fun analyze(text: String): Result {
        val lines = text.lines().map { normalize(it) }.filter { it.length >= 2 }
        val context = extractContext(lines)
        val structured = extractStructuredFaults(lines, context)
        val seeds = if (structured.isNotEmpty()) structured else extractFallbackFaults(lines, context)

        val unique = linkedMapOf<String, Seed>()
        seeds.forEach { seed ->
            val key = normalizeKey(seed.text)
            if (key.length >= 4 && key !in unique) unique[key] = seed
        }

        val faults = unique.values.take(12).mapIndexed { index, seed ->
            val ids = ComponentAnalyzer.analyze(seed.text).identifiers
            Fault(
                number = index + 1,
                category = "MAINT FAULT",
                text = seed.text,
                ata = seed.ata ?: detectAta(seed.text),
                identifiers = ids,
                ataFull = seed.ataFull,
                utc = seed.utc ?: context.utc,
                flightPhase = seed.flightPhase ?: context.flightPhase,
                faultClass = seed.faultClass,
                persistence = seed.persistence,
                source = seed.source,
                cockpitEffect = context.cockpitEffects.firstOrNull()
            )
        }
        return Result(text.trim(), context, faults)
    }

    private fun extractContext(lines: List<String>): Context {
        val flat = lines.joinToString(" ")
        val utc = extractUtc(lines, flat)
        val phase = phaseNames.firstOrNull { flat.contains(it) }
        val effects = mutableListOf<String>()

        val cockpitStart = lines.indexOfFirst { it.contains("COCKPIT EFFECT") }
        if (cockpitStart >= 0) {
            var i = cockpitStart + 1
            while (i < lines.size) {
                val line = lines[i]
                if ((line.startsWith("05") && line.contains("FAULT")) || line == "FAULT" || line.startsWith("FAULT REPORT")) break
                if (!isNoise(line) && !isBlockMetadata(line) && !ataLine.matches(line) && !looksLikeUtc(line) && phaseNames.none { line == it }) {
                    effects += line
                }
                i++
            }
        }
        if (flat.contains("MAINTENANCE STATUS") && effects.none { it.contains("MAINTENANCE STATUS") }) {
            effects += "MAINTENANCE STATUS"
        }
        return Context(utc, phase, effects.distinct().take(6))
    }

    private fun extractStructuredFaults(lines: List<String>, context: Context): List<Seed> {
        val out = mutableListOf<Seed>()
        for (i in lines.indices) {
            val ataMatch = ataLine.find(lines[i]) ?: continue
            val digits = ataMatch.groupValues[1]
            val ataFull = formatAta(digits)
            val ata = digits.take(2)
            var faultClass: String? = null
            var persistence: String? = null
            var source: String? = null
            var utc: String? = null
            var phase: String? = null
            var candidate: String? = null
            var sawFaultMetadata = false

            var j = i + 1
            while (j < lines.size && j <= i + 12) {
                val line = lines[j]
                if (j > i + 1 && ataLine.matches(line)) break
                if (isSectionBoundary(line)) break

                Regex("^CLASS\\s*([123])\\b.*").find(line)?.let {
                    faultClass = it.groupValues[1]; sawFaultMetadata = true
                }
                if (line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) {
                    persistence = line; sawFaultMetadata = true
                }
                Regex("^SOURCE\\s*[:=-]?\\s*([A-Z0-9/.-]+(?:\\s+[A-Z0-9/.-]+)?)").find(line)?.let {
                    source = it.groupValues[1].trim(); sawFaultMetadata = true
                }
                if (looksLikeUtc(line)) utc = line.filter { it.isDigit() }.take(4)
                phaseNames.firstOrNull { line.contains(it) }?.let { phase = it }

                if (candidate == null && sawFaultMetadata && isMaintenanceMessageCandidate(line)) {
                    candidate = line
                } else if (candidate != null && needsContinuation(candidate) && !isBlockMetadata(line) && !isNoise(line)) {
                    candidate = "$candidate $line"
                }
                j++
            }

            if (!candidate.isNullOrBlank()) {
                out += Seed(candidate, ata, ataFull, utc ?: context.utc, phase ?: context.flightPhase, faultClass, persistence, source)
            }
        }
        return out
    }

    private fun extractFallbackFaults(lines: List<String>, context: Context): List<Seed> {
        val flat = lines.joinToString(" ")
        val faultClass = Regex("\\bCLASS\\s*([123])\\b").find(flat)?.groupValues?.getOrNull(1)
        val persistence = listOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT").firstOrNull { flat.contains(it) }
        val source = Regex("\\bSOURCE\\s*[:=-]?\\s*([A-Z0-9/.-]+(?:\\s+[A-Z0-9/.-]+)?)").find(flat)?.groupValues?.getOrNull(1)?.trim()
        val ataFull = ataLine.find(lines.firstOrNull { ataLine.matches(it) }.orEmpty())?.groupValues?.getOrNull(1)?.let(::formatAta)
        return lines.filter { isMaintenanceMessageCandidate(it) }
            .filterNot { context.cockpitEffects.any { effect -> normalizeKey(effect) == normalizeKey(it) } }
            .take(8)
            .map { Seed(it, detectAta(it), ataFull, context.utc, context.flightPhase, faultClass, persistence, source) }
    }

    private fun extractUtc(lines: List<String>, flat: String): String? {
        Regex("\\bUTC\\s*[:=-]?\\s*([0-2]\\d[0-5]\\d)\\b").find(flat)?.let { return it.groupValues[1] }
        val phaseIndex = lines.indexOfFirst { line -> phaseNames.any { line.contains(it) } }
        if (phaseIndex >= 0) {
            for (i in (phaseIndex - 3).coerceAtLeast(0)..phaseIndex) {
                if (looksLikeUtc(lines[i])) return lines[i].filter { it.isDigit() }.take(4)
            }
        }
        return lines.firstOrNull(::looksLikeUtc)?.filter { it.isDigit() }?.take(4)
    }

    private fun looksLikeUtc(line: String): Boolean {
        val digits = line.filter { it.isDigit() }
        if (digits.length != 4) return false
        val hh = digits.take(2).toIntOrNull() ?: return false
        val mm = digits.takeLast(2).toIntOrNull() ?: return false
        return hh in 0..23 && mm in 0..59
    }

    private fun isMaintenanceMessageCandidate(line: String): Boolean {
        if (isNoise(line) || isBlockMetadata(line) || ataLine.matches(line)) return false
        if (phaseNames.any { line == it }) return false
        val hasTechnical = technicalTerms.any { line.contains(it) }
        val hasFault = explicitFaultTerms.any { line.contains(it) }
        val words = line.split(' ').filter { it.isNotBlank() }
        return words.size >= 2 && hasTechnical && (hasFault || Regex("\\b[A-Z]+\\d*[_ -][A-Z0-9 _/-]{3,}").containsMatchIn(line))
    }

    private fun normalize(s: String): String = s.uppercase()
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim(' ', ':', ';', '|')

    private fun normalizeKey(s: String): String = s.uppercase().replace('_', ' ').replace(Regex("[^A-Z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun formatAta(digits: String): String = when (digits.length) {
        6 -> "${digits.substring(0, 2)}-${digits.substring(2, 4)}-${digits.substring(4, 6)}"
        4 -> "${digits.substring(0, 2)}-${digits.substring(2, 4)}"
        else -> digits.take(2)
    }

    private fun isSectionBoundary(line: String): Boolean =
        line.startsWith("03 COCKPIT EFFECT") || line.startsWith("05 FAULT") ||
            line.startsWith("END OF REPORT") || line.startsWith("POST FLIGHT REPORT")

    private fun isBlockMetadata(line: String): Boolean {
        if (line.matches(Regex("^CLASS\\s+\\d+.*"))) return true
        if (line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) return true
        if (line.startsWith("SOURCE ")) return true
        if (looksLikeUtc(line)) return true
        if (phaseNames.any { line == it }) return true
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
        if (line.startsWith("DATE ") && line.length < 24) return true
        return false
    }

    private fun detectAta(line: String): String? {
        ataLine.find(line)?.let { return it.groupValues[1].take(2) }
        Regex("\\b(\\d{2})[- ]\\d{2}(?:[- ]\\d{2})?\\b").find(line)?.let { return it.groupValues[1] }
        for ((ata, words) in ataKeywords) if (words.any { line.contains(it) }) return ata
        return null
    }

    private fun formatUtc(value: String): String = if (value.length == 4) "${value.take(2)}:${value.takeLast(2)}" else value
}
