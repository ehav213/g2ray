package com.a340.technicalassistant

/**
 * Safety policy for selecting the first TSM entry point.
 *
 * An exact cockpit warning/status printed on the PFR (or entered by the user)
 * is authoritative. CMS data may disambiguate records under that cockpit
 * effect, but a broad identifier/FTS hit must never override it.
 */
object TsmFirstEntryPolicy {
    fun shouldUseCockpitIndex(cockpitMessage: String?): Boolean = !cockpitMessage.isNullOrBlank()

    fun uniqueTaskRef(taskRefs: List<String>): String? {
        val unique = taskRefs.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        return unique.singleOrNull()
    }

    fun allowBroadFallback(exactCockpitCandidateCount: Int): Boolean = exactCockpitCandidateCount == 0
}
