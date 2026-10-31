package com.a340.technicalassistant

import java.util.Locale

/**
 * Maps one verified TSM Possible Cause to the action(s) that actually name that
 * component inside the Fault Isolation section. Possible-cause lists and Job
 * Set-up reference tables are never used to create a component/action relation.
 *
 * PC5 also preserves every reference printed in the matched Fault Isolation
 * branch. `references` are cause-specific references from the exact selected
 * action(s). `branchReferences` are all references from the same verified branch
 * and must be presented as branch-level evidence, not as cause-specific actions.
 */
object TsmCauseReferenceMapper {
    data class ManualRef(val manual: String, val reference: String)

    data class Route(
        val matched: Boolean,
        val matchedIdentifiers: List<String>,
        val actionText: String,
        val references: List<ManualRef>,
        val branchText: String,
        val branchReferences: List<ManualRef>
    )

    private data class ScoredAction(
        val branchIndex: Int,
        val text: String,
        val score: Int,
        val ids: List<String>
    )

    fun route(
        taskContent: String,
        causeText: String,
        identifiers: List<String>,
        wiringLike: Boolean
    ): Route {
        val isolation = faultIsolationSection(taskContent)
        if (isolation.isBlank()) return emptyRoute()

        val branches = faultIsolationBranches(isolation)
        if (branches.isEmpty()) return emptyRoute()

        val ids = identifiers.map(::normalizeToken).filter { it.length >= 2 }.distinct()
        val terms = causeTerms(causeText)
        val scored = mutableListOf<ScoredAction>()

        branches.forEachIndexed { branchIndex, branch ->
            actionBlocks(branch).forEach { block ->
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
                    if (termMatches < required) return@forEach
                    score += termMatches * 20
                }

                if (Regex("(?i)\\b(TEST|CHECK|REPLACE|REMOVE|REMOV|INSTALL|REPAIR|MEASURE|INSPECT|DISCONNECT|CONNECT)\\b")
                        .containsMatchIn(block)) score += 10
                scored += ScoredAction(branchIndex, block.trim(), score, matchedIds)
            }
        }
        if (scored.isEmpty()) return emptyRoute()

        // FIN/component identifier matches are authoritative. If an identifier is
        // named in several actions in the same branch, keep all of those actions
        // so test/check and later removal/installation remain together.
        val idMatches = scored.filter { it.ids.isNotEmpty() }
        val selected = if (idMatches.isNotEmpty()) {
            idMatches
        } else {
            val best = scored.maxOf { it.score }
            scored.filter { it.score == best }
        }

        val selectedBranchIndexes = selected.map { it.branchIndex }.distinct()
        val selectedBranches = selectedBranchIndexes.map { branches[it] }

        val causeRefs = selected.flatMap { extractReferences(it.text) }
            .distinctBy { "${it.manual}|${it.reference}" }
        val branchRefs = selectedBranches.flatMap { extractReferences(it) }
            .distinctBy { "${it.manual}|${it.reference}" }
        val matchedIds = selected.flatMap { it.ids }.distinct()
        val action = selected.joinToString("\n\n--- NEXT CAUSE-SPECIFIC TSM ACTION ---\n\n") { it.text }
        val branchText = selectedBranches.joinToString("\n\n--- NEXT MATCHED TSM FAULT-ISOLATION BRANCH ---\n\n") { it.trim() }

        return Route(true, matchedIds, action, causeRefs, branchText, branchRefs)
    }

    private fun emptyRoute() = Route(false, emptyList(), "", emptyList(), "", emptyList())

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

    /**
     * Split only inside Fault Isolation. Airbus tasks normally use A./B./C.
     * branch headings. This prevents Job Set-up reference tables from entering
     * the relation while retaining all references printed in the selected branch.
     */
    private fun faultIsolationBranches(isolation: String): List<String> {
        val alpha = Regex("(?m)^\\s*([A-Z])\\.\\s+").findAll(isolation).toList()
        if (alpha.isEmpty()) return listOf(isolation.trim()).filter { it.isNotBlank() }

        return alpha.mapIndexed { index, match ->
            val end = alpha.getOrNull(index + 1)?.range?.first ?: isolation.length
            isolation.substring(match.range.first, end).trim()
        }.filter { it.isNotBlank() }
    }

    private fun actionBlocks(branch: String): List<String> {
        // Preserve the branch lead-in (A. If...) as context when no numbered
        // actions exist. When numbered actions do exist, use each complete
        // top-level numbered action for cause matching.
        val numeric = Regex("(?m)^\\s*\\((\\d{1,3})\\)\\s+").findAll(branch).toList()
        if (numeric.isNotEmpty()) {
            return numeric.mapIndexed { index, match ->
                val end = numeric.getOrNull(index + 1)?.range?.first ?: branch.length
                branch.substring(match.range.first, end).trim()
            }.filter { it.isNotBlank() }
        }
        return listOf(branch.trim()).filter { it.isNotBlank() }
    }

    private fun extractReferences(text: String): List<ManualRef> {
        val regex = Regex(
            "(?i)\\b(?:REF(?:ERENCE)?\\.?\\s*)?(AMM|ASM|AWM|ESPM|TSM)" +
                "(?:\\s+(?:TASK|FIG(?:URE)?|SHEET|DIAGRAM|SECTION))?\\s*[:.-]?\\s*" +
                "(\\d{2}-\\d{2}-\\d{2}(?:(?:-\\d{2})|(?:-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?))?)\\b"
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
