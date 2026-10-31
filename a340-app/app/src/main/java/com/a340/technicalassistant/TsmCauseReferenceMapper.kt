package com.a340.technicalassistant

import java.util.Locale

/**
 * Maps one verified TSM Possible Cause to the action(s) that actually name that
 * component inside the Fault Isolation section.  It deliberately ignores the
 * Possible Causes list, reference tables and job set-up pages when assigning a
 * manual reference to a component.
 */
object TsmCauseReferenceMapper {
    data class ManualRef(val manual: String, val reference: String)

    data class Route(
        val matched: Boolean,
        val matchedIdentifiers: List<String>,
        val actionText: String,
        val references: List<ManualRef>
    )

    fun route(
        taskContent: String,
        causeText: String,
        identifiers: List<String>,
        wiringLike: Boolean
    ): Route {
        val isolation = faultIsolationSection(taskContent)
        if (isolation.isBlank()) return Route(false, emptyList(), "", emptyList())

        val ids = identifiers.map(::normalizeToken).filter { it.length >= 2 }.distinct()
        val terms = causeTerms(causeText)
        val blocks = actionBlocks(isolation)
        if (blocks.isEmpty()) return Route(false, emptyList(), "", emptyList())

        data class Scored(val text: String, val score: Int, val ids: List<String>)
        val scored = blocks.mapNotNull { block ->
            val normalized = normalizeText(block)
            val matchedIds = ids.filter { containsWholeToken(normalized, it) }
            var score = matchedIds.size * 100

            if (matchedIds.isEmpty()) {
                val termMatches = terms.count { containsWholeToken(normalized, it) }
                val required = when {
                    wiringLike -> 1
                    terms.size <= 1 -> 1
                    else -> 2
                }
                if (termMatches < required) return@mapNotNull null
                score += termMatches * 20
            }

            if (Regex("(?i)\\b(TEST|CHECK|REPLACE|REMOVE|REMOV|INSTALL|REPAIR|MEASURE|INSPECT|DISCONNECT|CONNECT)\\b")
                    .containsMatchIn(block)) score += 10
            Scored(block.trim(), score, matchedIds)
        }
        if (scored.isEmpty()) return Route(false, emptyList(), "", emptyList())

        // Identifier matches are authoritative.  When a FIN appears in more than
        // one Fault Isolation action, keep all of those actions so a test/check
        // step and a later replacement step remain together.
        val idMatches = scored.filter { it.ids.isNotEmpty() }
        val selected = if (idMatches.isNotEmpty()) {
            idMatches
        } else {
            val best = scored.maxOf { it.score }
            scored.filter { it.score == best }
        }

        val refs = selected.flatMap { extractReferences(it.text) }
            .distinctBy { "${it.manual}|${it.reference}" }
        val matchedIds = selected.flatMap { it.ids }.distinct()
        val action = selected.joinToString("\n\n--- NEXT CAUSE-SPECIFIC TSM ACTION ---\n\n") { it.text }
        return Route(true, matchedIds, action, refs)
    }

    fun faultIsolationSection(taskContent: String): String {
        val text = taskContent.replace("\r", "")
        val start = Regex("(?im)^\\s*(?:\\d+\\.\\s*)?Fault Isolation\\s*$").find(text)
            ?: return ""
        val tail = text.substring(start.range.last + 1)
        val end = Regex(
            "(?im)^\\s*(?:\\d+\\.\\s*)?(?:Close[- ]?up|Job Close[- ]?up|END OF (?:THIS )?TASK)\\b.*$"
        ).find(tail)
        return (if (end == null) tail else tail.substring(0, end.range.first)).trim()
    }

    private fun actionBlocks(isolation: String): List<String> {
        // Airbus TSM fault-isolation actions are normally top-level numbered
        // steps: (1), (2), ... .  Keep each complete numbered step, including
        // its nested (a)/(b) substeps and all REF lines.
        val numeric = Regex("(?m)^\\s*\\((\\d{1,3})\\)\\s+").findAll(isolation).toList()
        if (numeric.isNotEmpty()) {
            return numeric.mapIndexed { index, match ->
                val end = numeric.getOrNull(index + 1)?.range?.first ?: isolation.length
                isolation.substring(match.range.first, end).trim()
            }.filter { it.isNotBlank() }
        }

        // Conservative fallback for revisions that use A./B. action headings.
        val alpha = Regex("(?m)^\\s*([A-Z])\\.\\s+").findAll(isolation).toList()
        if (alpha.isNotEmpty()) {
            return alpha.mapIndexed { index, match ->
                val end = alpha.getOrNull(index + 1)?.range?.first ?: isolation.length
                isolation.substring(match.range.first, end).trim()
            }.filter { it.isNotBlank() }
        }
        return listOf(isolation.trim()).filter { it.isNotBlank() }
    }

    private fun extractReferences(text: String): List<ManualRef> {
        val regex = Regex(
            "(?i)\\b(?:REF(?:ERENCE)?\\.?\\s*)?(AMM|ASM|AWM|ESPM)" +
                "(?:\\s+(?:TASK|FIG(?:URE)?|SHEET|DIAGRAM|SECTION))?\\s*[:.-]?\\s*" +
                "(\\d{2}-\\d{2}-\\d{2}(?:-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?)?)\\b"
        )
        return regex.findAll(text).map {
            ManualRef(it.groupValues[1].uppercase(Locale.US), it.groupValues[2].uppercase(Locale.US))
        }.toList()
    }

    private fun causeTerms(text: String): List<String> {
        val stop = setOf(
            "THE", "AND", "OR", "OF", "TO", "FROM", "WITH", "WITHOUT", "A", "AN", "AIRCRAFT",
            "SYSTEM", "ASSY", "ASSEMBLY", "LH", "RH", "LEFT", "RIGHT", "UNIT", "FAULT", "FAILED",
            "FAILURE", "INDICATED", "RELATED", "REPLACE", "CHECK", "REPAIR", "POSSIBLE", "CAUSE"
        )
        return Regex("[A-Z][A-Z0-9]{2,}").findAll(normalizeText(text)).map { it.value }
            .filter { it !in stop && !it.matches(Regex("\\d+")) }
            .distinct().toList()
    }

    private fun normalizeToken(value: String): String = value.uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]"), "")

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
