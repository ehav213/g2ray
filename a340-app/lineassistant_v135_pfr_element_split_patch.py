from pathlib import Path

# Line Assistant 1.3.5
# - split ML Kit lines back into PFR columns using word/element bounding boxes
# - recover common OCR damage in MAINTENANCE STATUS / ACTIS / unmatched FIN brackets
# - allow a one-edit SOURCE OCR error only when matched against a verified TSM index source
# - if the warning/malfunction index cannot be recovered, permit a fail-closed direct TSM task
#   match only when the original TSM task itself contains the exact maintenance-message branch,
#   the maintenance ATA reference, and the PFR identifiers.

p = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
s = p.read_text()

old = '''        val lines = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { OcrLine(normalize(line.text), Rect(it)) } }
            .filter { it.text.length >= 2 }
            .sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
'''
new = '''        val mlLines = result.textBlocks.flatMap { it.lines }
        val lines = mlLines
            .mapNotNull { line -> line.boundingBox?.let { OcrLine(normalize(line.text), Rect(it)) } }
            .filter { it.text.length >= 2 }
            .sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
'''
if old not in s:
    raise SystemExit('1.3.5 parser patch: ML line anchor not found')
s = s.replace(old, new, 1)

old = '''        val body = if (hasFullHeader) {
            lines.filter { it.box.top >= headerBottom - typicalHeight / 2 }
        } else {
            lines
        }
'''
new = '''        // ML Kit can merge words from two adjacent printed PFR columns into one Text.Line
        // (for example: "Engine Start FDIMU(10TV)"). Re-split such lines by each element's
        // bounding-box center before any semantic field extraction.
        val elementSplitLines = splitMergedLinesByColumns(mlLines, columns)
        val effectiveLines = if (elementSplitLines.isNotEmpty()) elementSplitLines else lines
        val body = if (hasFullHeader) {
            effectiveLines.filter { it.box.top >= headerBottom - typicalHeight / 2 }
        } else {
            effectiveLines
        }
'''
if old not in s:
    raise SystemExit('1.3.5 parser patch: body anchor not found')
s = s.replace(old, new, 1)

marker = '''    private fun emptyResult(raw: String) = PostFlightAnalyzer.Result(
'''
if marker not in s:
    raise SystemExit('1.3.5 parser patch: helper insertion anchor not found')
helper = r'''    private fun splitMergedLinesByColumns(mlLines: List<Text.Line>, columns: Columns): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        mlLines.forEach { line ->
            val originalBox = line.boundingBox ?: return@forEach
            val original = OcrLine(normalize(line.text), Rect(originalBox))
            val elements = line.elements.mapNotNull { element ->
                element.boundingBox?.let { OcrLine(normalize(element.text), Rect(it)) }
            }.filter { it.text.isNotBlank() }

            if (elements.size < 2) {
                out += original
                return@forEach
            }

            fun bucket(item: OcrLine): Int = when {
                item.cx <= columns.cockpitEnd -> 0
                item.cx < columns.faultStart -> 1
                else -> 2
            }

            val grouped = elements.groupBy(::bucket)
            if (grouped.size <= 1) {
                out += original
            } else {
                grouped.toSortedMap().values.forEach { group ->
                    val ordered = group.sortedBy { it.box.left }
                    val text = ordered.joinToString(" ") { it.text }.replace(Regex("\s+"), " ").trim()
                    if (text.isNotBlank()) {
                        out += OcrLine(
                            text,
                            Rect(
                                ordered.minOf { it.box.left },
                                ordered.minOf { it.box.top },
                                ordered.maxOf { it.box.right },
                                ordered.maxOf { it.box.bottom }
                            )
                        )
                    }
                }
            }
        }
        return out.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
    }

    private fun repairFaultOcr(value: String): String {
        var t = value.replace(Regex("\s+"), " ").trim()
        // Context-limited repair: ML Kit sometimes reads '(' as 'C'. Only repair when a
        // closing ')' is present and there is no opening '(', immediately before a FIN.
        if (')' in t && '(' !in t) {
            t = t.replace(
                Regex("\\b([A-Z][A-Z0-9]{2,})\\s*C\\s*([0-9]{1,3}[A-Z]{1,4}[0-9]*)\\)"),
                "$1($2)"
            )
        }
        return t
    }

    private fun compactToken(value: String): String = normalize(value).replace(Regex("[^A-Z0-9]"), "")

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun repairCockpitMessageAgainstSource(message: String?, source: String?): String? {
        if (message.isNullOrBlank() || source.isNullOrBlank()) return message
        val m = compactToken(message)
        val src = compactToken(source)
        if (!message.contains(' ') && m.length >= 4 && src.length >= 4 && editDistance(m, src) <= 1) return source
        return message
    }

'''
s = s.replace(marker, helper + marker, 1)

# Exclude fuzzy OCR variants of the printed "MAINTENANCE STATUS" type label.
old = '''    private fun isCockpitType(text: String): Boolean {
        val t = normalize(text)
        return t in setOf(
            "MAINTENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE", "STS-INOP SYSTEM",
            "EWD WARNING(S)", "EWD WARNINGS", "PFD FLAG(S)", "ND FLAG(S)", "SD FLAG(S)",
            "SD ADVISORY(IES)", "OBSERVATIONS", "WARNING LIGHT(S)", "INDICATOR(S)"
        )
    }
'''
new = '''    private fun isCockpitType(text: String): Boolean {
        val t = normalize(text)
        val known = t in setOf(
            "MAINTENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE", "STS-INOP SYSTEM",
            "EWD WARNING(S)", "EWD WARNINGS", "PFD FLAG(S)", "ND FLAG(S)", "SD FLAG(S)",
            "SD ADVISORY(IES)", "OBSERVATIONS", "WARNING LIGHT(S)", "INDICATOR(S)"
        )
        if (known) return true
        val compact = t.replace(Regex("[^A-Z]"), "")
        return compact.length in 15..19 && editDistance(compact, "MAINTENANCESTATUS") <= 2
    }
'''
if old not in s:
    raise SystemExit('1.3.5 parser patch: cockpit type block not found')
s = s.replace(old, new, 1)

old = '''    private fun stripCockpitTypePrefix(text: String): String {
        var t = normalize(text)
        val prefixes = listOf("MAINTENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE")
        prefixes.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it).trim() }
        return t
    }
'''
new = '''    private fun stripCockpitTypePrefix(text: String): String {
        var t = normalize(text)
        val prefixes = listOf("MAINTENANCE STATUS", "IATENANCE STATUS", "MAINT STATUS", "STS-MAINTENANCE")
        prefixes.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it).trim() }
        return t
    }
'''
if old not in s:
    raise SystemExit('1.3.5 parser patch: cockpit prefix block not found')
s = s.replace(old, new, 1)

# Canonicalize only tightly-scoped ACTIS OCR variants. Do not globally rewrite arbitrary I/T characters.
old = '''        return m.groupValues[1]
            .trim()
            .trimStart('*', '#', ':', '-', '=')
            .replace(Regex("[^A-Z0-9/.-]"), "")
            .ifBlank { null }
'''
new = '''        val cleaned = m.groupValues[1]
            .trim()
            .trimStart('*', '#', ':', '-', '=')
            .replace(Regex("[^A-Z0-9/.-]"), "")
            .ifBlank { return null }
        return when (cleaned) {
            "TACTIS", "ACTS", "ACT1S", "ACTLS" -> "ACTIS"
            else -> cleaned
        }
'''
if old not in s:
    raise SystemExit('1.3.5 parser patch: source block not found')
s = s.replace(old, new, 1)

# Apply the unmatched-parenthesis repair only to the parsed maintenance-message function.
start = s.find('    private fun parseFaultMessage(')
end = s.find('\n    private fun parseIdentifiers', start)
if start < 0 or end < 0:
    raise SystemExit('1.3.5 parser patch: parseFaultMessage boundaries not found')
block = s[start:end]
needle = '            .trim()\n'
if needle not in block:
    raise SystemExit('1.3.5 parser patch: parseFaultMessage trim not found')
block = block.replace(needle, '            .trim()\n            .let(::repairFaultOcr)\n', 1)
s = s[:start] + block + s[end:]

# Use the right-column SOURCE to repair a one-edit cockpit status code such as ACTS -> ACTIS.
maint_block_start = s.find('            if (maintAtaDigits != null) {')
maint_block_end = s.find('            } else if (!cockpitAta.isNullOrBlank()', maint_block_start)
if maint_block_start < 0 or maint_block_end < 0:
    raise SystemExit('1.3.5 parser patch: maintenance block not found')
mb = s[maint_block_start:maint_block_end]
anchor = '                val source = fault.firstNotNullOfOrNull { parseSource(it.text) }\n'
if anchor not in mb:
    raise SystemExit('1.3.5 parser patch: source assignment not found')
mb = mb.replace(anchor, anchor + '                val repairedCockpitMessage = repairCockpitMessageAgainstSource(cockpitMessage, source)\n', 1)
mb = mb.replace('                        cockpitEffect = cockpitMessage,\n', '                        cockpitEffect = repairedCockpitMessage,\n', 1)
s = s[:maint_block_start] + mb + s[maint_block_end:]
p.write_text(s)

# Engine: DB-aware source tolerance + fail-closed direct task recovery.
e = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
t = e.read_text()

old = '''        val indexRecord = findTsmIndexRecord(input)
        val cockpitMessage = input.cockpitMessage ?: indexRecord?.cockpitMessage
        val cockpitAta = input.cockpitAta ?: indexRecord?.cockpitAta
        val cockpitSource = if (!input.cockpitMessage.isNullOrBlank()) "PFR" else if (indexRecord != null) "VERIFIED TSM INDEX" else "UNRESOLVED"

        val mel = resolveMel(cockpitMessage, cockpitAta, input.rawText, aircraftProfile)
        val tsm = indexRecord?.let { resolveTaskFromIndex(it, input, cockpitSource) }
'''
new = '''        val indexRecord = findTsmIndexRecord(input)
        val directTask = if (indexRecord == null) findDirectTsmTask(input) else null
        val cockpitMessage = input.cockpitMessage ?: indexRecord?.cockpitMessage
        val cockpitAta = input.cockpitAta ?: indexRecord?.cockpitAta
        val cockpitSource = when {
            !input.cockpitMessage.isNullOrBlank() -> "PFR"
            indexRecord != null -> "VERIFIED TSM INDEX"
            directTask != null -> "PFR + VERIFIED TSM TASK"
            else -> "UNRESOLVED"
        }

        val mel = resolveMel(cockpitMessage, cockpitAta, input.rawText, aircraftProfile)
        val tsm = indexRecord?.let { resolveTaskFromIndex(it, input, cockpitSource) }
            ?: directTask?.let { resolveDirectTask(it, input, cockpitSource) }
'''
if old not in t:
    raise SystemExit('1.3.5 engine patch: resolve block not found')
t = t.replace(old, new, 1)

# Both the base acceptableIndexMatch and the 1.3.1 photo-strength gate must use the
# same narrow one-edit SOURCE comparison against the trusted TSM record.
t = t.replace(
    'normalizeToken(input.source) != normalizeToken(record.source)) return false',
    '!sourceTokensEquivalent(input.source, record.source)) return false'
)

marker = '    private fun findTsmIndexRecord(input: FaultInput): TsmIndexRecord? {\n'
if marker not in t:
    raise SystemExit('1.3.5 engine patch: direct task insertion anchor not found')
helpers = r'''    private fun sourceTokensEquivalent(observedRaw: String, trustedRaw: String): Boolean {
        val a = normalizeToken(observedRaw)
        val b = normalizeToken(trustedRaw)
        if (a == b) return true
        if (minOf(a.length, b.length) < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        return referenceAwareLevenshtein(a, b) <= 1
    }

    private fun findDirectTsmTask(input: FaultInput): TaskRow? {
        if (input.parsedFault == null || input.maintenanceAta.isNullOrBlank() || input.maintenanceMessage.isBlank()) return null
        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage))
            .map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val words = significantWords(input.maintenanceMessage)
        val searchTerms = (ids + words).distinct().take(6)
        if (searchTerms.isEmpty()) return null

        val expressions = mutableListOf<String>()
        if (ids.isNotEmpty()) expressions += ids.take(3).joinToString(" AND ") { "\"$it\"" }
        if (words.isNotEmpty()) expressions += words.take(4).joinToString(" AND ") { "\"$it\"*" }
        expressions += searchTerms.joinToString(" OR ") { "\"$it\"*" }

        val candidates = linkedMapOf<String, TaskRow>()
        for (expr in expressions.distinct()) {
            runCatching {
                db.rawQuery(
                    """
                    SELECT DISTINCT c.document_filename,c.pdf_page
                    FROM chunks_fts f JOIN chunks c ON c.chunk_id=f.chunk_id
                    WHERE chunks_fts MATCH ? AND UPPER(COALESCE(c.manual,''))='TSM'
                    ORDER BY c.pdf_page LIMIT 120
                    """.trimIndent(),
                    arrayOf(expr)
                ).use { c ->
                    while (c.moveToNext()) {
                        val filename = c.getString(0)
                        val page = c.getInt(1)
                        taskRowsNearPage(filename, page).forEach { task ->
                            if (!candidates.containsKey(task.id)) {
                                val content = exactTaskContent(task)
                                if (directTaskContentMatches(content, input)) candidates[task.id] = task
                            }
                        }
                    }
                }
            }
            if (candidates.size > 1) break
        }
        return if (candidates.size == 1) candidates.values.first() else null
    }

    private fun taskRowsNearPage(filename: String, page: Int): List<TaskRow> {
        val out = mutableListOf<TaskRow>()
        runCatching {
            db.rawQuery(
                """
                SELECT t.task_id,d.filename,t.task_ref,COALESCE(t.task_title,''),t.start_pdf_page,
                       COALESCE(t.end_pdf_page,t.start_pdf_page),COALESCE(t.selected_applicability,'')
                FROM task_entries t JOIN documents d ON d.document_id=t.document_id
                WHERE UPPER(COALESCE(d.manual,''))='TSM' AND d.filename=?
                  AND t.start_pdf_page BETWEEN ? AND ?
                ORDER BY t.start_pdf_page DESC,t.task_ref
                LIMIT 24
                """.trimIndent(),
                arrayOf(filename, (page - 3).coerceAtLeast(1).toString(), page.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out += TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getString(6))
                }
            }
        }
        return out
    }

    private fun directTaskContentMatches(content: String, input: FaultInput): Boolean {
        val upper = normalizeText(content)
        if (!upper.contains("FAULT ISOLATION")) return false

        val ataDigits = normalizeAta(input.maintenanceAta.orEmpty()).take(6)
        if (ataDigits.length == 6) {
            val compactContent = upper.replace(Regex("[^A-Z0-9]"), "")
            if (!compactContent.contains(ataDigits)) return false
        }

        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage))
            .map(::normalizeToken).filter { it.length >= 3 }.distinct()
        if (ids.isNotEmpty() && ids.any { !containsWholeToken(upper, it) }) return false

        val branches = Regex("(?im)maintenance message\\s*:?\\s*([^\\n\\r]{2,100})")
            .findAll(content).map { it.groupValues[1].trim().trimEnd(':', '.', ';') }.toList()
        if (branches.isEmpty()) return false
        return branches.any { candidate -> ocrMessageSimilarity(input.maintenanceMessage, candidate) >= 0.94 }
    }

    private fun resolveDirectTask(task: TaskRow, input: FaultInput, cockpitSource: String): LineTsmResolution? {
        val content = exactTaskContent(task)
        if (!directTaskContentMatches(content, input)) return null
        val branch = selectTroubleshootingBranch(content, input)
        return LineTsmResolution(
            indexTaskRef = task.reference,
            taskRef = task.reference,
            taskTitle = task.title,
            indexPage = task.startPage,
            taskStartPage = task.startPage,
            taskEndPage = task.endPage,
            cockpitMessage = input.cockpitMessage.orEmpty(),
            cockpitAta = input.cockpitAta.orEmpty(),
            maintenanceMessage = input.maintenanceMessage,
            maintenanceAta = input.maintenanceAta.orEmpty(),
            source = input.source.orEmpty(),
            faultClass = input.faultClass.orEmpty(),
            identifiers = (input.identifiers + extractIdentifiers(input.maintenanceMessage)).distinct(),
            taskContent = content,
            branch = branch,
            cockpitEffectSource = cockpitSource
        )
    }

'''
t = t.replace(marker, helpers + marker, 1)

t = t.replace(
    'LINE ASSISTANT 1.3.3 • PFR-FIRST EXACT TSM WORKFLOW',
    'LINE ASSISTANT 1.3.5 • PFR ELEMENT-GEOMETRY + EXACT TSM WORKFLOW'
)
e.write_text(t)

# Version bump after 1.3.4 metadata patch.
g = Path('app/build.gradle.kts')
u = g.read_text()
if 'versionCode = 32' not in u or 'versionName = "1.3.4"' not in u:
    raise SystemExit('1.3.5 patch: expected 1.3.4 build metadata not found')
u = u.replace('versionCode = 32', 'versionCode = 33', 1)
u = u.replace('versionName = "1.3.4"', 'versionName = "1.3.5"', 1)
g.write_text(u)

print('Line Assistant 1.3.5 element-level PFR geometry and direct exact-TSM recovery applied')
