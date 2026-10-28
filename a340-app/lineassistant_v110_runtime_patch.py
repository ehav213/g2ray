from pathlib import Path

path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = path.read_text()

def rep(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'Line Assistant patch failed: {label}')
    s = s.replace(old, new)

rep(
'''    val placard: String,\n    val requirements: String,''',
'''    val placard: String,\n    val applicability: String,\n    val requirements: String,''',
'condition applicability field')

rep(
'''            val window = normalizeText(m.groupValues[1])\n            mappings += Mapping(m.groupValues[2], null, mappingScore(window, ids, prefixes), window.takeLast(360))''',
'''            val window = normalizeText(m.groupValues[1])\n            val local = localSelectorClause(window)\n            mappings += Mapping(m.groupValues[2], null, mappingScore(local, ids, prefixes), local.takeLast(360))''',
'paragraph selector locality')

rep(
'''            val window = normalizeText(m.groupValues[1])\n            mappings += Mapping(null, m.groupValues[2], mappingScore(window, ids, prefixes), window.takeLast(360))''',
'''            val window = normalizeText(m.groupValues[1])\n            val local = localSelectorClause(window)\n            mappings += Mapping(null, m.groupValues[2], mappingScore(local, ids, prefixes), local.takeLast(360))''',
'redirect selector locality')

rep(
'''        val best = mappings.filter { it.score > 0 }.maxByOrNull { it.score }''',
'''        val best = mappings.filter { it.score > 0 }\n            .sortedWith(compareByDescending<Mapping> { it.score }.thenByDescending { if (it.redirect != null) 1 else 0 })\n            .firstOrNull()''',
'redirect tie-break')

rep(
'''    private fun mappingScore(window: String, ids: List<String>, prefixes: List<String>): Int {''',
'''    private fun localSelectorClause(window: String): String {\n        val bullet = "· IF THE MESSAGE"\n        val idx = window.lastIndexOf(bullet)\n        return if (idx >= 0) window.substring(idx) else window\n    }\n\n    private fun mappingScore(window: String, ids: List<String>, prefixes: List<String>): Int {''',
'local selector helper')

rep(
'''        val conditions = selected.itemRefs.flatMap { ref -> loadMelConditions(ref) }.distinctBy { it.conditionRef }''',
'''        val conditions = selected.itemRefs.flatMap { ref -> loadMelConditions(ref, aircraftProfile) }\n            .distinctBy { "${it.conditionRef}|${it.applicability}|${normalizeText(it.requirements)}" }''',
'condition effectivity load')

rep(
'''        val stateNote = if (conditions.size > 1 && selected.itemRefs.none { it.lastOrNull()?.isLetter() == true }) {\n            " Multiple MEL conditions/sub-items exist; aircraft state must select the applicable condition before dispatch."\n        } else ""''',
'''        val hasConditionEffectivityVariants = conditions.groupBy { it.conditionRef }.any { (_, list) -> list.map { it.applicability }.distinct().size > 1 }\n        val stateNote = when {\n            selected.itemRefs.isNotEmpty() && conditions.isEmpty() -> " MEL item condition/effectivity was not uniquely resolved; verify the original item."\n            hasConditionEffectivityVariants && aircraftProfile.isBlank() -> " Multiple MEL condition effectivity variants exist; enter MSN/aircraft variant before selecting a condition."\n            conditions.size > 1 && selected.itemRefs.none { it.lastOrNull()?.isLetter() == true } -> " Multiple MEL conditions/sub-items exist; aircraft state must select the applicable condition before dispatch."\n            else -> ""\n        }''',
'condition state note')

rep(
'''    private fun loadMelConditions(requestedRef: String): List<LineMelCondition> {''',
'''    private fun loadMelConditions(requestedRef: String, aircraftProfile: String): List<LineMelCondition> {''',
'condition loader signature')

rep(
'''                parseMelConditions(base, content, c.getString(2), c.getString(3), c.getString(4), c.getString(5))\n                    .filter { requestedCondition == null || it.conditionRef.equals(requestedCondition, true) }''',
'''                val parsed = parseMelConditions(base, content, c.getString(2), c.getString(3), c.getString(4), c.getString(5))\n                    .filter { requestedCondition == null || it.conditionRef.equals(requestedCondition, true) }\n                if (aircraftProfile.isBlank()) parsed else {\n                    val matched = parsed.filter { applicabilityMatch(it.applicability, aircraftProfile) == ApplicabilityMatch.MATCH }\n                    if (matched.isNotEmpty()) matched else emptyList()\n                }''',
'condition loader filtering')

rep(
'''            return listOf(LineMelCondition(baseRef, "", fallbackInterval, fallbackInstalled, fallbackRequired, fallbackPlacard,\n                content.take(2200), emptyList(), emptyList()))''',
'''            return listOf(LineMelCondition(baseRef, "", fallbackInterval, fallbackInstalled, fallbackRequired, fallbackPlacard,\n                parseApplicability(content), content.take(2200), emptyList(), emptyList()))''',
'condition fallback applicability')

rep(
'''            val conditionRef = baseRef + match.groupValues[1].uppercase(Locale.US)\n            val title = match.groupValues.getOrNull(2).orEmpty().trim()''',
'''            val conditionRef = baseRef + match.groupValues[1].uppercase(Locale.US)\n            val title = match.groupValues.getOrNull(2).orEmpty().trim()\n            val applicability = lines.subList(0, start + 1).lastOrNull { it.startsWith("Applicable to:", true) }\n                ?.substringAfter(':')?.trim().orEmpty()''',
'condition applicability parse')

rep(
'''            out += LineMelCondition(conditionRef, title, interval, installed, required, placard, requirements, procRefs, ammRefs)''',
'''            out += LineMelCondition(conditionRef, title, interval, installed, required, placard, applicability, requirements, procRefs, ammRefs)''',
'condition constructor applicability')

rep(
'''            append(" | Placard ").append(c.placard.ifBlank { "?" }).append('\\n')''',
'''            append(" | Placard ").append(c.placard.ifBlank { "?" })\n            if (c.applicability.isNotBlank()) append(" | Applies ").append(c.applicability)\n            append('\\n')''',
'summary condition applicability')

rep(
'''                    .append(" Placard=").append(c.placard).append('\\n')''',
'''                    .append(" Placard=").append(c.placard)\n                    .append(" Applicability=").append(c.applicability.ifBlank { "UNKNOWN" }).append('\\n')''',
'evidence condition applicability')

rep(
'''        if (fallback != null) {\n            return LineMelResolution(\n                status = LineDispatchStatus.VERIFY_REQUIRED,\n                title = fallback.reference + " — " + fallback.title,\n                ataChapter = fallback.reference.take(2),\n                page = repo.resolve(fallback)?.pdfPage,\n                exactBlock = fallback.snippet,\n                itemRefs = listOf(fallback.reference),\n                note = "No exact MEL ECAM entry was resolved. Equipment-level MEL candidate only; verify original MEL, conditions and effectivity."\n            )\n        }''',
'''        if (fallback != null) {\n            val conditions = loadMelConditions(fallback.reference, aircraftProfile)\n            return LineMelResolution(\n                status = LineDispatchStatus.VERIFY_REQUIRED,\n                title = fallback.reference + " — " + fallback.title,\n                ataChapter = fallback.reference.take(2),\n                page = repo.resolve(fallback)?.pdfPage,\n                exactBlock = fallback.snippet,\n                itemRefs = listOf(fallback.reference),\n                note = "No exact MEL ECAM entry was resolved. Equipment-level MEL candidate only; verify original MEL, select the applicable condition, (O)/(M) procedures and effectivity.",\n                conditions = conditions\n            )\n        }''',
'equipment fallback condition details')

path.write_text(s)
print('Line Assistant 1.1 final troubleshooting/MEL patch applied')
