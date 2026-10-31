package com.a340.technicalassistant

import java.util.Locale

/**
 * PC8 context narrowing is deliberately downstream of the verified TSM task.
 * It never changes the selected TSM task, its Possible Causes list, or the
 * authoritative TSM text. It only identifies which already-verified cause
 * actions/references are directly supported by identifiers printed in the CMS
 * message entered/reviewed by the user.
 */
object TsmContextNarrower {
    enum class MatchKind { NONE, CONTEXT, DIRECT }

    data class CauseContext(
        val kind: MatchKind,
        val selectedIdentifiers: List<String>,
        val selectedReferences: List<TsmCauseReferenceMapper.ManualRef>
    )

    data class IpcDisplay(
        val fin: String,
        val partNumber: String,
        val nomenclature: String,
        val rawBlock: String,
        val identifierRecoveredFromRaw: Boolean
    )

    fun selectedIdentifiers(explicit: List<String>, maintenanceMessage: String): List<String> {
        val out = linkedSetOf<String>()
        explicit.map(::normalizeToken).filter(::looksLikeIdentifier).forEach(out::add)
        identifierRegex.findAll(maintenanceMessage.uppercase(Locale.US)).forEach {
            val token = normalizeToken(it.value)
            if (looksLikeIdentifier(token)) out += token
        }
        return out.toList()
    }

    fun causeContext(
        causeText: String,
        causeIdentifiers: List<String>,
        actionText: String,
        references: List<TsmCauseReferenceMapper.ManualRef>,
        selectedIdentifiers: List<String>
    ): CauseContext {
        val selected = selectedIdentifiers.map(::normalizeToken).filter { it.isNotBlank() }.distinct()
        if (causeText.isBlank() || selected.isEmpty()) return CauseContext(MatchKind.NONE, emptyList(), emptyList())

        val causeIds = causeIdentifiers.map(::normalizeToken).filter { it.isNotBlank() }.distinct()
        val direct = causeIds.intersect(selected.toSet()).toList()
        if (causeIds.isNotEmpty() && direct.isEmpty()) {
            // A Possible Cause with a printed FIN that is different from the CMS FIN
            // stays in the complete TSM map, but is never promoted as CMS-matched.
            return CauseContext(MatchKind.NONE, emptyList(), emptyList())
        }

        val narrowed = narrowReferences(actionText, references, selected)
        if (direct.isNotEmpty()) return CauseContext(MatchKind.DIRECT, direct, narrowed)

        // Generic TSM causes such as "computer" or "wirings" have no FIN in the
        // Possible Causes list. Promote them only when the exact Fault Isolation
        // action contains a CMS identifier and yields at least one scoped ref.
        val actionIds = selected.filter { containsWholeToken(actionText, it) }
        return if (actionIds.isNotEmpty() && narrowed.isNotEmpty()) {
            CauseContext(MatchKind.CONTEXT, actionIds, narrowed)
        } else {
            CauseContext(MatchKind.NONE, emptyList(), emptyList())
        }
    }

    fun narrowReferences(
        actionText: String,
        references: List<TsmCauseReferenceMapper.ManualRef>,
        selectedIdentifiers: List<String>
    ): List<TsmCauseReferenceMapper.ManualRef> {
        val selected = selectedIdentifiers.map(::normalizeToken).filter { it.isNotBlank() }.distinct()
        if (selected.isEmpty() || references.isEmpty() || actionText.isBlank()) return emptyList()

        val lines = actionText.replace("\r", "").lines()
        val out = linkedSetOf<TsmCauseReferenceMapper.ManualRef>()

        references.forEach { ref ->
            val refUpper = ref.reference.uppercase(Locale.US)
            val indices = lines.indices.filter { lines[it].uppercase(Locale.US).contains(refUpper) }
            if (indices.isEmpty()) return@forEach

            val accepted = indices.any { index ->
                val current = lines[index]
                val currentIds = identifiersIn(current)
                if (currentIds.isNotEmpty()) {
                    currentIds.any { it in selected }
                } else {
                    // PDF extraction can wrap an Airbus bullet across adjacent lines.
                    // Use only the immediately adjacent lines; never scan the whole
                    // Fault Isolation action because that recreates PC3's false links.
                    val local = buildString {
                        if (index > 0) append(lines[index - 1]).append('\n')
                        append(current).append('\n')
                        if (index + 1 < lines.size) append(lines[index + 1])
                    }
                    val localIds = identifiersIn(local)
                    when {
                        localIds.isNotEmpty() -> localIds.any { it in selected }
                        else -> selected.any { containsWholeToken(local, it) }
                    }
                }
            }
            if (accepted) out += ref
        }

        // Some extracted actions become one long line. In that case a common
        // reference after a list of components is valid for the selected FIN.
        if (out.isEmpty() && lines.size <= 2 && selected.any { containsWholeToken(actionText, it) }) {
            references.forEach { out += it }
        }
        return out.toList()
    }

    fun narrowEvidence(actionText: String, selectedIdentifiers: List<String>): String {
        val selected = selectedIdentifiers.map(::normalizeToken).filter { it.isNotBlank() }.distinct()
        if (selected.isEmpty()) return actionText
        val lines = actionText.replace("\r", "").lines()
        val keep = linkedSetOf<Int>()
        lines.forEachIndexed { index, line ->
            if (selected.any { containsWholeToken(line, it) }) {
                keep += index
                if (index > 0 && lines[index - 1].trim().startsWith("(") ) keep += index - 1
                if (index + 1 < lines.size && lines[index + 1].contains("Ref.", true)) keep += index + 1
            }
        }
        return if (keep.isEmpty()) actionText else keep.sorted().joinToString("\n") { lines[it].trim() }
    }

    fun matchedMelRefs(itemRefs: List<String>, titles: Map<String, String>, maintenanceMessage: String): List<String> {
        if (itemRefs.isEmpty()) return emptyList()
        val message = normalizeText(maintenanceMessage)
        val wantsOutboard = listOf("OUTR", "OUTBOARD").any { containsWholeToken(message, it) }
        val wantsInboard = listOf("INBD", "INBOARD").any { containsWholeToken(message, it) }
        if (!wantsOutboard && !wantsInboard) return emptyList()

        val scored = itemRefs.map { ref ->
            val title = normalizeText(titles[ref].orEmpty())
            var score = 0
            if (wantsOutboard && title.contains("OUTBOARD")) score += 10
            if (wantsOutboard && title.contains("INBOARD")) score -= 10
            if (wantsInboard && title.contains("INBOARD")) score += 10
            if (wantsInboard && title.contains("OUTBOARD")) score -= 10
            ref to score
        }
        val best = scored.maxOfOrNull { it.second } ?: 0
        return if (best > 0) scored.filter { it.second == best }.map { it.first } else emptyList()
    }

    fun repairIpc(
        fin: String,
        partNumber: String,
        nomenclature: String,
        rawBlock: String,
        causeIdentifiers: List<String>
    ): IpcDisplay {
        val ids = causeIdentifiers.map(::normalizeToken).filter { it.isNotBlank() }.distinct()
        val raw = rawBlock.replace("\r", "")
        val rawUpper = raw.uppercase(Locale.US)
        val recovered = ids.firstOrNull { containsWholeToken(rawUpper, it) }
        val indexedFin = normalizeToken(fin)
        val useRecovered = recovered != null && indexedFin != recovered
        val displayFin = if (useRecovered) recovered!! else fin.trim()

        var displayNomenclature = nomenclature.trim()
        val fragment = fin.trim().uppercase(Locale.US)
        if (useRecovered && fragment.matches(Regex("[A-Z]{2,8}")) && displayNomenclature.endsWith("-")) {
            val combined = displayNomenclature.dropLast(1) + fragment
            val compactRaw = rawUpper.replace(Regex("[^A-Z0-9]+"), "")
            val compactCombined = combined.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "")
            if (compactRaw.contains(compactCombined)) displayNomenclature = combined
        }

        val cleanedRaw = raw.replace("\u0002", "")
        return IpcDisplay(displayFin, partNumber.trim(), displayNomenclature, cleanedRaw, useRecovered)
    }

    private val identifierRegex = Regex("(?<![A-Z0-9])(?=[A-Z0-9]{3,}(?![A-Z0-9]))(?=[A-Z0-9]*\\d)[A-Z0-9]+(?![A-Z0-9])")

    private fun identifiersIn(text: String): List<String> = identifierRegex.findAll(text.uppercase(Locale.US))
        .map { normalizeToken(it.value) }
        .filter(::looksLikeIdentifier)
        .distinct().toList()

    private fun looksLikeIdentifier(value: String): Boolean =
        value.length in 3..12 && value.any(Char::isLetter) && value.any(Char::isDigit)

    private fun normalizeToken(value: String): String = value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")

    private fun normalizeText(value: String): String = value.uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsWholeToken(haystack: String, token: String): Boolean {
        if (token.isBlank()) return false
        return Regex("(?<![A-Z0-9])${Regex.escape(token)}(?![A-Z0-9])", RegexOption.IGNORE_CASE)
            .containsMatchIn(haystack)
    }
}
