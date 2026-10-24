package com.a340.technicalassistant

object QueryIntelligence {
    enum class Intent {
        TROUBLESHOOTING,
        REMOVAL_INSTALLATION,
        WIRING,
        PARTS,
        DISPATCH,
        STANDARD_PRACTICE,
        INSPECTION,
        GENERAL
    }

    data class Analysis(
        val original: String,
        val normalized: String,
        val intent: Intent,
        val ata: String?,
        val identifiers: List<String>,
        val expandedTerms: List<String>,
        val searchText: String,
        val notes: List<String>
    ) {
        fun summary(): String = buildString {
            append("AI RETRIEVAL • ").append(intent.name.replace('_', ' '))
            if (!ata.isNullOrBlank()) append(" • ATA ").append(ata)
            if (identifiers.isNotEmpty()) append(" • IDs: ").append(identifiers.take(4).joinToString(", "))
            if (expandedTerms.isNotEmpty()) append("\nSemantic expansion: ").append(expandedTerms.take(12).joinToString(", "))
            notes.forEach { append("\n").append(it) }
        }
    }

    private val phraseMap = linkedMapOf(
        "NOSE WHEEL STEERING" to listOf("NWS", "STEERING"),
        "NOSEWHEEL STEERING" to listOf("NWS", "STEERING"),
        "NOT WORKING" to listOf("INOP", "INOPERATIVE", "FAIL"),
        "DOES NOT WORK" to listOf("INOP", "INOPERATIVE", "FAIL"),
        "DOESN'T WORK" to listOf("INOP", "INOPERATIVE", "FAIL"),
        "HARD TO OPERATE" to listOf("STIFF", "DIFFICULT", "JAM"),
        "HARD STEERING" to listOf("NWS", "STIFF", "STEERING"),
        "HEAVY STEERING" to listOf("NWS", "STIFF", "STEERING"),
        "BAD SMELL" to listOf("SMELL", "ODOR", "ODOUR"),
        "BURNING SMELL" to listOf("SMELL", "ODOR", "OVERHEAT"),
        "HIGH VIBRATION" to listOf("VIB", "VIBRATION"),
        "LOW PRESSURE" to listOf("LO PR", "LOW PR", "PRESSURE"),
        "HIGH PRESSURE" to listOf("HI PR", "HIGH PR", "PRESSURE"),
        "WINDSCREEN" to listOf("WINDSHIELD", "WINDOW"),
        "WIPER" to listOf("WINDSHIELD WIPER", "WIPER"),
        "CIRCUIT BREAKER" to listOf("C/B", "CB", "CIRCUIT BREAKER"),
        "PART NUMBER" to listOf("P/N", "PART NO", "PART NUMBER"),
        "REMOVE AND INSTALL" to listOf("REMOVAL", "INSTALLATION", "R/I")
    )

    private val persianMap = linkedMapOf(
        "کار نمی کند" to listOf("INOP", "INOPERATIVE", "FAIL"),
        "کار نمیکند" to listOf("INOP", "INOPERATIVE", "FAIL"),
        "خراب" to listOf("FAULT", "FAIL", "INOP"),
        "ایراد" to listOf("FAULT", "FAILURE"),
        "نشتی" to listOf("LEAK", "LEAKING"),
        "لرزش" to listOf("VIB", "VIBRATION"),
        "صدا" to listOf("NOISE", "ABNORMAL NOISE"),
        "بو" to listOf("SMELL", "ODOR", "ODOUR"),
        "داغ" to listOf("HOT", "OVERHEAT", "OVHT"),
        "فشار کم" to listOf("LO PR", "LOW PRESSURE"),
        "فشار زیاد" to listOf("HI PR", "HIGH PRESSURE"),
        "پمپ" to listOf("PUMP"),
        "شیر" to listOf("VALVE"),
        "ترمز" to listOf("BRAKE"),
        "چرخ دماغه" to listOf("NOSE WHEEL", "NWS", "STEERING"),
        "صندلی" to listOf("SEAT"),
        "برف پاک کن" to listOf("WIPER", "WINDSHIELD WIPER"),
        "موتور" to listOf("ENG", "ENGINE"),
        "هیدرولیک" to listOf("HYD", "HYDRAULIC"),
        "سوخت" to listOf("FUEL"),
        "اکسیژن" to listOf("OXYGEN", "OXY"),
        "در" to listOf("DOOR")
    )

    private val wordGroups = listOf(
        setOf("INOP", "INOPERATIVE", "FAILED", "FAIL", "FAILURE", "FAULT"),
        setOf("VIB", "VIBRATION"),
        setOf("OVHT", "OVERHEAT", "HOT"),
        setOf("LEAK", "LEAKING"),
        setOf("STIFF", "DIFFICULT", "HARD", "JAM", "JAMMED"),
        setOf("INTERMITTENT", "ERRATIC", "FLUCTUATING", "FLUCTUATE"),
        setOf("NWS", "STEERING", "NOSEWHEEL", "NOSE-WHEEL"),
        setOf("WINDSHIELD", "WINDSCREEN", "WINDOW"),
        setOf("GEN", "GENERATOR"),
        setOf("HYD", "HYDRAULIC"),
        setOf("OXY", "OXYGEN"),
        setOf("PNEU", "PNEUMATIC"),
        setOf("C/B", "CB", "CIRCUIT", "BREAKER")
    )

    private val ataKeywords = linkedMapOf(
        "21" to listOf("PACK", "AIR CONDITION", "CABIN PRESS", "CABIN TEMP", "VENT"),
        "22" to listOf("AUTOPILOT", "AUTO FLIGHT", "FLIGHT DIRECTOR", "A/P"),
        "23" to listOf("VHF", "HF ", "RADIO", "SATCOM", "COMM"),
        "24" to listOf("GEN", "GENERATOR", "BATTERY", "ELEC", "BUS", "TRU"),
        "25" to listOf("SEAT", "GALLEY", "FURNISH", "CABIN EQUIP"),
        "26" to listOf("FIRE", "SMOKE", "SMK DET", "EXTING"),
        "27" to listOf("FLAP", "SLAT", "SPOILER", "AILERON", "RUDDER", "ELEVATOR", "TRIM"),
        "28" to listOf("FUEL", "TK PUMP", "TANK PUMP", "XFR"),
        "29" to listOf("HYD", "HYDRAULIC", "RESERVOIR", "RSVR"),
        "30" to listOf("ANTI ICE", "ANTI-ICE", "ICE DET", "WIPER", "WINDSHIELD HEAT"),
        "31" to listOf("ECAM", "DISPLAY", "FWS", "QAR", "INDICATION"),
        "32" to listOf("NWS", "NOSE WHEEL", "STEERING", "LANDING GEAR", "BRAKE", "WHEEL", "TIRE", "TYRE"),
        "33" to listOf("LIGHT", "LIGHTING"),
        "34" to listOf("ADIRS", "ADR", "NAV", "GPS", "RAD ALT"),
        "35" to listOf("OXYGEN", "OXY"),
        "36" to listOf("BLEED", "PNEU", "PNEUMATIC"),
        "38" to listOf("WATER", "WASTE", "LAV"),
        "45" to listOf("CMC", "CENTRAL MAINT"),
        "49" to listOf("APU"),
        "52" to listOf("DOOR", "CARGO DOOR")
    )

    fun analyze(input: String): Analysis {
        val original = input.trim()
        val upper = original.uppercase()
            .replace('—', '-')
            .replace('–', '-')
            .replace(Regex("\\s+"), " ")
            .trim()

        val terms = linkedSetOf<String>()
        val notes = mutableListOf<String>()

        phraseMap.forEach { (phrase, expansion) ->
            if (upper.contains(phrase)) terms.addAll(expansion)
        }
        persianMap.forEach { (phrase, expansion) ->
            if (original.contains(phrase)) terms.addAll(expansion)
        }

        val upperTokens = Regex("[A-Z0-9][A-Z0-9_./-]*").findAll(upper).map { it.value }.toSet()
        for (group in wordGroups) {
            if (group.any { upperTokens.contains(it) || upper.contains(it) }) terms.addAll(group)
        }

        val ids = ComponentAnalyzer.analyze(original).identifiers
        val ata = detectAta(upper, terms)
        val intent = detectIntent(upper, original)

        when (intent) {
            Intent.TROUBLESHOOTING -> terms.addAll(listOf("FAULT", "FAILURE", "TROUBLE SHOOTING"))
            Intent.REMOVAL_INSTALLATION -> terms.addAll(listOf("REMOVAL", "INSTALLATION"))
            Intent.WIRING -> terms.addAll(listOf("WIRING", "CONNECTOR", "WIRE"))
            Intent.PARTS -> terms.addAll(listOf("PART NUMBER", "NOMENCLATURE", "IPC"))
            Intent.DISPATCH -> terms.addAll(listOf("MEL", "DISPATCH", "INOPERATIVE"))
            Intent.STANDARD_PRACTICE -> terms.addAll(listOf("STANDARD PRACTICE", "ESPM"))
            Intent.INSPECTION -> terms.addAll(listOf("INSPECTION", "CHECK"))
            Intent.GENERAL -> Unit
        }

        if (ata != null) terms += "ATA $ata"
        if (original.any { it.code in 0x0600..0x06FF }) notes += "Persian query normalized to English maintenance terms"
        notes += "Evidence-only mode: AI retrieval does not invent maintenance procedures"

        val cleanedTerms = terms
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(28)

        val searchText = buildString {
            append(original)
            if (cleanedTerms.isNotEmpty()) append(" ").append(cleanedTerms.joinToString(" "))
            if (ids.isNotEmpty()) append(" ").append(ids.joinToString(" "))
        }.trim()

        return Analysis(original, upper, intent, ata, ids, cleanedTerms, searchText, notes)
    }

    private fun detectIntent(upper: String, original: String): Intent {
        fun has(vararg words: String) = words.any { upper.contains(it) || original.contains(it) }
        return when {
            has("WIRE", "WIRING", "CONNECTOR", "CONTACT", "SPLICE", "CRIMP", "HARNESS", "کابل", "سیم") -> Intent.WIRING
            has("PART NUMBER", "P/N", "PART NO", "NOMENCLATURE", "پارت نامبر") -> Intent.PARTS
            has("MEL", "DEFER", "DISPATCH", "INOP FOR DISPATCH", "دیفر") -> Intent.DISPATCH
            has("REMOVE", "REMOVAL", "INSTALL", "INSTALLATION", "تعویض", "باز کردن", "نصب") -> Intent.REMOVAL_INSTALLATION
            has("INSPECT", "INSPECTION", "CHECK FOR", "بازرسی") -> Intent.INSPECTION
            has("CRIMP", "SPLICE", "STANDARD PRACTICE", "ESPM") -> Intent.STANDARD_PRACTICE
            has("FAULT", "FAIL", "INOP", "WARNING", "CAUTION", "ECAM", "CFDS", "CMC", "VIB", "LEAK", "NOISE", "SMELL", "خراب", "ایراد", "کار نمی", "کار نمیکند", "لرزش", "نشتی") -> Intent.TROUBLESHOOTING
            else -> Intent.GENERAL
        }
    }

    private fun detectAta(upper: String, expanded: Set<String>): String? {
        Regex("\\bATA\\s*(\\d{2})\\b").find(upper)?.let { return it.groupValues[1] }
        Regex("\\b(\\d{2})[- ]\\d{2}(?:[- ]\\d{2})?\\b").find(upper)?.let { return it.groupValues[1] }
        val combined = upper + " " + expanded.joinToString(" ")
        for ((ata, words) in ataKeywords) {
            if (words.any { combined.contains(it) }) return ata
        }
        return null
    }
}
