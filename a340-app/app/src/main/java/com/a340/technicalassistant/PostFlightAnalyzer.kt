package com.a340.technicalassistant

object PostFlightAnalyzer {
    data class Context(
        val utc: String? = null,
        val flightPhase: String? = null,
        val flightPhaseCode: String? = null,
        val cockpitEffects: List<String> = emptyList(),
        val cockpitAta: String? = null
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
        val cockpitEffect: String? = null,
        val cockpitAta: String? = null,
        val flightPhaseCode: String? = null
    ) {
        fun searchQuery(): String = buildString {
            // Only the maintenance message and its six-digit ATA are sent to TSM retrieval.
            // UTC/phase/class/source/identifiers are context, not search terms.
            append(text)
            val a = ataFull ?: ata
            if (!a.isNullOrBlank()) append(" ATA ").append(a)
        }

        fun displaySummary(): String = buildString {
            cockpitEffect?.let { append("COCKPIT EFFECT: ").append(it).append('\n') }
            cockpitAta?.let { append("COCKPIT ATA: ").append(it).append('\n') }
            utc?.let { append("UTC: ").append(formatUtc(it)).append('\n') }
            flightPhase?.let {
                append("FLIGHT PHASE: ").append(it)
                flightPhaseCode?.let { code -> append(" (").append(code).append(')') }
                append('\n')
            }
            append("MAINTENANCE FAULT: ").append(text)
            ataFull?.let { append("\nMAINT ATA: ").append(it) } ?: ata?.let { append("\nMAINT ATA: ").append(it) }
            faultClass?.let { append("\nCLASS: ").append(it) }
            persistence?.let { append("\nSTATUS: ").append(it) }
            source?.let { append("\nSOURCE: ").append(it) }
            if (identifiers.isNotEmpty()) append("\nIDENTIFIERS: ").append(identifiers.joinToString(", "))
        }

        override fun toString(): String = displaySummary()
    }

    data class Result(
        val rawText: String,
        val context: Context,
        val faults: List<Fault>
    )

    private data class AtaEntry(val index: Int, val digits: String)

    private data class Seed(
        val text: String,
        val ata: String?,
        val ataFull: String?,
        val utc: String?,
        val flightPhase: String?,
        val phaseCode: String?,
        val faultClass: String?,
        val persistence: String?,
        val source: String?,
        val explicitIdentifiers: List<String>,
        val cockpitEffect: String?,
        val cockpitAta: String?
    )

    private val explicitFaultTerms = listOf(
        "FAULT", "FAIL", "FAILURE", "INOP", "INOPERATIVE", "WARNING", "CAUTION",
        "OVHT", "OVERHEAT", "LEAK", "RUPTURE", "OPEN CIRCUIT", "SHORT CIRCUIT", "JAM",
        "DISAGREE", "LO PR", "LOW PR", "LOW PRESS", "HI PR", "HIGH PR", "LOW TEMP",
        "HIGH TEMP", "SMOKE", "FIRE", "DISABLED", "OFF", "LOW", "NO SIGNAL", "NO DATA"
    )

    private val technicalTerms = listOf(
        "PUMP", "VALVE", "SENSOR", "SENSORS", "SNSR", "SNSRS", "SENSING", "PROX", "PROXIMITY",
        "PRESS", "PRESSURE", "TEMP", "GEN", "BUS", "PACK", "BLEED", "FUEL", "HYD", "BRAKE",
        "DOOR", "SLAT", "SLT", "FLAP", "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "ENGINE",
        "ENG", "APU", "BMC", "LGCIU", "FAN", "VIB", "OIL", "DUCT", "RESERVOIR", "RSVR",
        "DET", "DETECTOR", "LOOP", "QAR", "TAPE", "VENT", "XFR", "PRESS SW", "SMK"
    )

    private val noiseExact = setOf(
        "POST FLIGHT REPORT", "POST-FLIGHT REPORT", "PFR", "A/C IDENT", "AC IDENT",
        "DATE", "FLT NBR", "FLIGHT NBR", "FROM/TO", "START/END", "CMC PRINTING",
        "CMC1 PRINTING", "CMC2 PRINTING", "REPORT", "FAULT REPORT", "UTC", "FLIGHT PHASE",
        "IDENTIFIERS", "IDENTIFIER", "SOURCE", "MAINTENANCE", "END OF REPORT", "MAINTENANCE STATUS"
    )

    private val canonicalPhases = listOf(
        "ENGINE START", "TAXI OUT", "TAKEOFF", "CLIMB", "CRUISE", "DESCENT", "APPROACH",
        "LANDING", "TAXI IN", "ENGINE SHUTDOWN", "ON GROUND", "GROUND"
    )

    private val phaseAliases = mapOf(
        "COUISE" to "CRUISE", "CRUSE" to "CRUISE", "CROISE" to "CRUISE", "CRUI5E" to "CRUISE",
        "TAXIOUT" to "TAXI OUT", "TAXIIN" to "TAXI IN", "ENGSTART" to "ENGINE START",
        "ENGINESTART" to "ENGINE START", "TAKE OFF" to "TAKEOFF"
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
        "32" to listOf("LANDING GEAR", "L/G", "GEAR", "BRAKE", "NWS", "WHEEL", "TIRE", "TYRE", "LGCIU", "PROX", "SNSR"),
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
        val rowSeeds = extractPfrRows(lines)
        val context = contextFromRows(lines, rowSeeds)
        val seeds = if (rowSeeds.isNotEmpty()) rowSeeds else extractFallbackFaults(lines, context)

        val unique = linkedMapOf<String, Seed>()
        seeds.forEach { seed ->
            val key = normalizeKey(seed.text)
            if (key.length >= 4 && key !in unique) unique[key] = seed
        }

        val faults = unique.values.take(12).mapIndexed { index, seed ->
            val ids = linkedSetOf<String>()
            ids += seed.explicitIdentifiers
            ids += extractComponentIdentifiers(seed.text)
            ids += ComponentAnalyzer.analyze(seed.text).identifiers
            seed.source?.let { ids.remove(it) }
            Fault(
                number = index + 1,
                category = "MAINT FAULT",
                text = seed.text,
                ata = seed.ata ?: detectAta(seed.text),
                identifiers = ids.filter { it.isNotBlank() }.distinct().take(12),
                ataFull = seed.ataFull,
                utc = seed.utc ?: context.utc,
                flightPhase = seed.flightPhase ?: context.flightPhase,
                faultClass = seed.faultClass,
                persistence = seed.persistence,
                source = seed.source,
                cockpitEffect = seed.cockpitEffect ?: context.cockpitEffects.firstOrNull(),
                cockpitAta = seed.cockpitAta ?: context.cockpitAta,
                flightPhaseCode = seed.phaseCode ?: context.flightPhaseCode
            )
        }
        return Result(text.trim(), context, faults)
    }

    /**
     * Airbus PFR row parser. A cropped PFR event normally contains a four-digit cockpit-effect ATA,
     * UTC/flight phase, then a six-digit maintenance ATA followed by CLASS, HARD/INTERMITTENT,
     * maintenance message, SOURCE and IDENTIFIERS. OCR order may be linear rather than columnar,
     * so fields are associated by proximity to each six-digit maintenance ATA.
     */
    private fun extractPfrRows(lines: List<String>): List<Seed> {
        val ataEntries = lines.mapIndexedNotNull { index, line ->
            ataLine.find(line)?.groupValues?.getOrNull(1)?.let { AtaEntry(index, it) }
        }
        val maintenanceAtas = ataEntries.filter { it.digits.length == 6 }
        if (maintenanceAtas.isEmpty()) return emptyList()

        val out = mutableListOf<Seed>()
        maintenanceAtas.forEachIndexed { rowIndex, maint ->
            val nextMaint = maintenanceAtas.getOrNull(rowIndex + 1)?.index ?: lines.size
            val prevMaint = maintenanceAtas.getOrNull(rowIndex - 1)?.index ?: -1
            val windowStart = (maint.index - 10).coerceAtLeast(prevMaint + 1).coerceAtLeast(0)
            val windowEnd = (maint.index + 14).coerceAtMost(nextMaint - 1).coerceAtMost(lines.lastIndex)
            val before = (windowStart until maint.index).toList()
            val after = (maint.index + 1..windowEnd).toList()

            val cockpitAtaEntry = ataEntries
                .filter { it.digits.length == 4 && it.index in windowStart until maint.index }
                .maxByOrNull { it.index }
            val cockpitAta = cockpitAtaEntry?.digits?.let(::formatAta)

            val utcIndex = before.filter { looksLikeUtc(lines[it]) }.maxOrNull()
            val utc = utcIndex?.let { lines[it].filter(Char::isDigit).take(4) }
                ?: findNearestUtc(lines, maint.index, windowStart, windowEnd)

            val phaseHit = (before.asReversed() + after).firstNotNullOfOrNull { idx ->
                canonicalPhase(lines[idx])?.let { idx to it }
            }
            val phase = phaseHit?.second
            val phaseCode = phaseHit?.first?.let { pidx ->
                ((pidx + 1)..(pidx + 3).coerceAtMost(windowEnd))
                    .map { lines[it] }
                    .firstOrNull { it.matches(Regex("^\\d{2}$")) }
            }

            val cockpitEffect = cockpitAtaEntry?.let { entry ->
                val end = listOfNotNull(utcIndex, phaseHit?.first, maint.index).filter { it > entry.index }.minOrNull() ?: maint.index
                ((entry.index + 1) until end)
                    .map { lines[it] }
                    .firstOrNull { isCockpitEffectCandidate(it) }
            }

            var faultClass: String? = null
            var persistence: String? = null
            var source: String? = null
            val explicitIds = linkedSetOf<String>()
            var candidate: String? = null
            var identifierMode = false

            after.forEach { idx ->
                val line = lines[idx]
                if (faultClass == null) Regex("^CLASS\\s*[:=-]?\\s*([123])\\b.*").find(line)?.let { faultClass = it.groupValues[1] }
                if (persistence == null && line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) persistence = line

                Regex("^SOURCE\\s*[:=-]?\\s*([A-Z0-9/.-]+)").find(line)?.let {
                    source = it.groupValues[1].trim(); identifierMode = false
                }

                val idInline = Regex("^IDENTIFIERS?\\s*[:=-]?\\s*(.*)$").find(line)
                if (idInline != null) {
                    identifierMode = true
                    explicitIds += splitIdentifiers(idInline.groupValues[1])
                    return@forEach
                }

                if (identifierMode) {
                    if (isIdentifierOnlyLine(line)) {
                        explicitIds += splitIdentifiers(line)
                        return@forEach
                    } else if (isBlockMetadata(line) || ataLine.matches(line)) {
                        identifierMode = false
                    }
                }

                if (candidate == null && isMaintenanceMessageCandidate(line)) {
                    candidate = line
                } else if (candidate != null && needsContinuation(candidate!!) && !isBlockMetadata(line) && !isNoise(line)) {
                    candidate = "$candidate $line"
                }
            }

            // Some OCR engines place SOURCE/IDENTIFIERS before/after the message in a different order.
            val localText = (windowStart..windowEnd).map { lines[it] }
            if (source == null) source = localText.firstNotNullOfOrNull { line ->
                Regex("^SOURCE\\s*[:=-]?\\s*([A-Z0-9/.-]+)").find(line)?.groupValues?.getOrNull(1)
            }
            if (explicitIds.isEmpty()) {
                localText.forEachIndexed { localIndex, line ->
                    if (line == "IDENTIFIERS" || line == "IDENTIFIER") {
                        localText.drop(localIndex + 1).take(3).takeWhile(::isIdentifierOnlyLine).forEach { explicitIds += splitIdentifiers(it) }
                    }
                }
            }

            if (!candidate.isNullOrBlank()) {
                out += Seed(
                    text = candidate!!,
                    ata = maint.digits.take(2),
                    ataFull = formatAta(maint.digits),
                    utc = utc,
                    flightPhase = phase,
                    phaseCode = phaseCode,
                    faultClass = faultClass,
                    persistence = persistence,
                    source = source,
                    explicitIdentifiers = explicitIds.toList(),
                    cockpitEffect = cockpitEffect,
                    cockpitAta = cockpitAta
                )
            }
        }
        return out
    }

    private fun contextFromRows(lines: List<String>, seeds: List<Seed>): Context {
        val first = seeds.firstOrNull()
        if (first != null) {
            return Context(
                utc = first.utc,
                flightPhase = first.flightPhase,
                flightPhaseCode = first.phaseCode,
                cockpitEffects = seeds.mapNotNull { it.cockpitEffect }.distinct(),
                cockpitAta = first.cockpitAta
            )
        }
        val flat = lines.joinToString(" ")
        val utc = extractUtc(lines, flat)
        val phase = lines.firstNotNullOfOrNull(::canonicalPhase)
        return Context(utc = utc, flightPhase = phase)
    }

    private fun extractFallbackFaults(lines: List<String>, context: Context): List<Seed> {
        val flat = lines.joinToString(" ")
        val faultClass = Regex("\\bCLASS\\s*[:=-]?\\s*([123])\\b").find(flat)?.groupValues?.getOrNull(1)
        val persistence = listOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT").firstOrNull { flat.contains(it) }
        val source = Regex("\\bSOURCE\\s*[:=-]?\\s*([A-Z0-9/.-]+)").find(flat)?.groupValues?.getOrNull(1)?.trim()
        val maintAta = lines.mapNotNull { ataLine.find(it)?.groupValues?.getOrNull(1) }.firstOrNull { it.length == 6 }
        val cockpitAta = lines.mapNotNull { ataLine.find(it)?.groupValues?.getOrNull(1) }.firstOrNull { it.length == 4 }
        val identifiers = extractExplicitIdentifiers(lines)
        val cockpitEffect = cockpitAta?.let { digits ->
            val idx = lines.indexOfFirst { ataLine.find(it)?.groupValues?.getOrNull(1) == digits }
            lines.drop(idx + 1).take(5).firstOrNull(::isCockpitEffectCandidate)
        }
        return lines.filter { isMaintenanceMessageCandidate(it) }
            .filterNot { normalizeKey(it) == normalizeKey(cockpitEffect.orEmpty()) }
            .take(8)
            .map {
                Seed(
                    text = it,
                    ata = maintAta?.take(2) ?: detectAta(it),
                    ataFull = maintAta?.let(::formatAta),
                    utc = context.utc,
                    flightPhase = context.flightPhase,
                    phaseCode = context.flightPhaseCode,
                    faultClass = faultClass,
                    persistence = persistence,
                    source = source,
                    explicitIdentifiers = identifiers,
                    cockpitEffect = cockpitEffect,
                    cockpitAta = cockpitAta?.let(::formatAta)
                )
            }
    }

    private fun extractExplicitIdentifiers(lines: List<String>): List<String> {
        val out = linkedSetOf<String>()
        lines.forEachIndexed { i, line ->
            val inline = Regex("^IDENTIFIERS?\\s*[:=-]?\\s*(.*)$").find(line)
            if (inline != null) {
                out += splitIdentifiers(inline.groupValues[1])
                lines.drop(i + 1).take(3).takeWhile(::isIdentifierOnlyLine).forEach { out += splitIdentifiers(it) }
            }
        }
        return out.toList()
    }

    private fun splitIdentifiers(text: String): List<String> = Regex("[A-Z0-9]+(?:[-/][A-Z0-9]+)*")
        .findAll(text.uppercase())
        .map { it.value }
        .flatMap { token -> token.split('/').asSequence() }
        .filter { token -> token.length >= 3 && token.any(Char::isLetter) && token.any(Char::isDigit) }
        .toList()

    private fun extractComponentIdentifiers(text: String): List<String> = Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
        .findAll(text.uppercase())
        .map { it.value }
        .filter { it.length >= 3 }
        .distinct()
        .toList()

    private fun isIdentifierOnlyLine(line: String): Boolean {
        if (line.startsWith("SOURCE") || line.startsWith("CLASS") || line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) return false
        val tokens = splitIdentifiers(line)
        return tokens.isNotEmpty() && line.split(' ').size <= 4
    }

    private fun isCockpitEffectCandidate(line: String): Boolean {
        if (isNoise(line) || isBlockMetadata(line) || ataLine.matches(line) || looksLikeUtc(line)) return false
        if (canonicalPhase(line) != null) return false
        if (line.matches(Regex("^\\d{2}$"))) return false
        if (line.startsWith("SOURCE") || line.startsWith("IDENTIFIER") || line.startsWith("CLASS")) return false
        return line.any(Char::isLetter) && line.length >= 4
    }

    private fun extractUtc(lines: List<String>, flat: String): String? {
        Regex("\\bUTC\\s*[:=-]?\\s*([0-2]\\d[0-5]\\d)\\b").find(flat)?.let { return it.groupValues[1] }
        return lines.firstOrNull(::looksLikeUtc)?.filter(Char::isDigit)?.take(4)
    }

    private fun findNearestUtc(lines: List<String>, center: Int, start: Int, end: Int): String? {
        return (start..end)
            .filter { looksLikeUtc(lines[it]) }
            .minByOrNull { kotlin.math.abs(it - center) }
            ?.let { lines[it].filter(Char::isDigit).take(4) }
    }

    private fun looksLikeUtc(line: String): Boolean {
        if (line.startsWith("ATA")) return false
        val digits = line.filter(Char::isDigit)
        if (digits.length != 4) return false
        val hh = digits.take(2).toIntOrNull() ?: return false
        val mm = digits.takeLast(2).toIntOrNull() ?: return false
        return hh in 0..23 && mm in 0..59
    }

    private fun canonicalPhase(line: String): String? {
        val clean = normalize(line)
        canonicalPhases.firstOrNull { clean.contains(it) }?.let { return it }
        val letters = clean.replace(Regex("[^A-Z ]"), "").replace(Regex("\\s+"), " ").trim()
        phaseAliases[letters]?.let { return it }
        phaseAliases[letters.replace(" ", "")]?.let { return it }
        return canonicalPhases.firstOrNull { phase -> editDistance(letters.replace(" ", ""), phase.replace(" ", "")) <= 1 }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) {
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun isMaintenanceMessageCandidate(line: String): Boolean {
        if (isNoise(line) || isBlockMetadata(line) || ataLine.matches(line)) return false
        if (canonicalPhase(line) != null) return false
        if (line.startsWith("SOURCE") || line.startsWith("IDENTIFIER") || line.startsWith("CLASS")) return false
        val hasTechnical = technicalTerms.any { line.contains(it) }
        val hasFault = explicitFaultTerms.any { line.contains(it) }
        val hasFin = extractComponentIdentifiers(line).isNotEmpty()
        val words = line.split(' ').filter { it.isNotBlank() }
        // Airbus maintenance messages can be pure signal/component messages without words such as FAIL/FAULT,
        // e.g. PROX SNSRS (39GA1/39GA2).
        return words.size >= 2 && hasTechnical && (hasFault || hasFin || line.contains('/'))
    }

    private fun normalize(s: String): String = s.uppercase()
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim(' ', ':', ';', '|')

    private fun normalizeKey(s: String): String = s.uppercase()
        .replace('_', ' ')
        .replace(Regex("[^A-Z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun formatAta(digits: String): String = when (digits.length) {
        6 -> "${digits.substring(0, 2)}-${digits.substring(2, 4)}-${digits.substring(4, 6)}"
        4 -> "${digits.substring(0, 2)}-${digits.substring(2, 4)}"
        else -> digits.take(2)
    }

    private fun isBlockMetadata(line: String): Boolean {
        if (line.matches(Regex("^CLASS\\s*[:=-]?\\s*\\d+.*"))) return true
        if (line in setOf("HARD", "INTERMITTENT", "PERMANENT", "TRANSIENT")) return true
        if (line.startsWith("SOURCE")) return true
        if (line.startsWith("IDENTIFIER")) return true
        if (looksLikeUtc(line)) return true
        if (canonicalPhase(line) != null) return true
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
