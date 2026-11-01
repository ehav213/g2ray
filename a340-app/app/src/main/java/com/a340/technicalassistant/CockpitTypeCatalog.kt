package com.a340.technicalassistant

/**
 * Exact warning/status Type labels used by the Airbus A340 TSM Fault Symptom List
 * for non-ECAM Maintenance/Cockpit Status entry. EWD WARNING(S) remains in the
 * dedicated ECAM field above, so it is deliberately not duplicated here.
 */
object CockpitTypeCatalog {
    val values: List<String> = listOf(
        "OBSERVATIONS",
        "SD FLAG(S)",
        "SD ADVISORY(IES)",
        "STS-MAINTENANCE",
        "STS-INOP SYSTEM",
        "WARNING LIGHT(S)",
        "ND FLAG(S)",
        "PFD FLAG(S)",
        "EWD FLAG(S)",
        "INDICATOR(S)"
    )
}
