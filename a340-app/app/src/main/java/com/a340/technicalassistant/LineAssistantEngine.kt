package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File
import java.util.Locale

enum class LineDispatchStatus {
    NO_DISPATCH,
    MUST_BE_OPERATIVE,
    CONDITIONAL,
    VERIFY_REQUIRED,
    NO_ENTRY
}

data class LineMelCondition(
    val conditionRef: String,
    val title: String,
    val repairInterval: String,
    val nbrInstalled: String,
    val nbrRequired: String,
    val placard: String,
    val applicability: String,
    val requirements: String,
    val procedureRefs: List<String>,
    val ammTaskRefs: List<String>
)

data class LineMelResolution(
    val status: LineDispatchStatus,
    val title: String,
    val ataChapter: String,
    val page: Int?,
    val exactBlock: String,
    val itemRefs: List<String>,
    val note: String,
    val applicability: String = "",
    val conditions: List<LineMelCondition> = emptyList(),
    val variantCount: Int = 1,
    val selectedByProfile: Boolean = false
)

data class LineTroubleshootingBranch(
    val selector: String,
    val paragraph: String?,
    val redirectTaskRef: String?,
    val possibleCauses: String,
    val faultConfirmation: String,
    val isolation: String,
    val continuation: String,
    val evidence: String
)

data class LineTsmResolution(
    val indexTaskRef: String,
    val taskRef: String,
    val taskTitle: String,
    val indexPage: Int,
    val taskStartPage: Int,
    val taskEndPage: Int,
    val cockpitMessage: String,
    val cockpitAta: String,
    val maintenanceMessage: String,
    val maintenanceAta: String,
    val source: String,
    val faultClass: String,
    val identifiers: List<String>,
    val taskContent: String,
    val branch: LineTroubleshootingBranch? = null,
    val cockpitEffectSource: String = "TSM INDEX"
)

data class LineTsmCandidate(
    val taskRef: String,
    val cockpitAta: String,
    val cockpitMessage: String,
    val cockpitType: String,
    val maintenanceAta: String,
    val maintenanceMessage: String,
    val source: String,
    val faultClass: String,
    val identifiers: List<String>,
    val applicability: String = ""
)

enum class LineTsmState { RESOLVED, NEEDS_CMS, AMBIGUOUS, NOT_FOUND }

data class LineAssistantResult(
    val summary: String,
    val safetySummary: String,
    val evidencePack: String,
    val hits: List<SearchHit>,
    val mel: LineMelResolution,
    val tsm: LineTsmResolution?,
    val structuredFault: PostFlightAnalyzer.Fault?,
    val tsmCandidates: List<LineTsmCandidate> = emptyList(),
    val tsmState: LineTsmState = LineTsmState.NOT_FOUND,
    val tsmNote: String = ""
)

/**
 * Line Assistant 1.1 database-native resolver.
 *
 * TSM: PFR -> Warnings/Malfunction index -> exact Ref. TASK -> exact task text -> applicable branch.
 * MEL: Cockpit effect/ECAM -> exact ECAM block -> effectivity variant -> MEL item/condition/(O)/(M).
 * AI receives only the already verified MEL block and selected troubleshooting branch.
 */
class LineAssistantEngine(
    dbFile: File,
    private val repo: SearchRepository
) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private data class FaultInput(
        val rawText: String,
        val maintenanceMessage: String,
        val maintenanceAta: String?,
        val cockpitMessage: String?,
        val cockpitAta: String?,
        val source: String?,
        val faultClass: String?,
        val identifiers: List<String>,
        val parsedFault: PostFlightAnalyzer.Fault?
    )

    private data class TsmIndexRecord(
        val indexFilename: String,
        val indexPage: Int,
        val indexTaskRef: String,
        val cockpitAta: String,
        val cockpitMessage: String,
        val cockpitType: String,
        val maintenanceAta: String,
        val maintenanceMessage: String,
        val source: String,
        val faultClass: String,
        val identifiers: List<String>,
        val hasFaultMessage: Boolean
    )

    private data class TaskRow(
        val taskId: String,
        val filename: String,
        val reference: String,
        val title: String,
        val startPage: Int,
        val endPage: Int,
        val applicability: String
    )

    private data class MelEntryRow(
        val id: String,
        val ata: String,
        val title: String,
        val page: Int,
        val endPage: Int,
        val content: String
    )

    private data class MelCandidate(
        val row: MelEntryRow,
        val block: String,
        val applicability: String,
        val itemRefs: List<String>,
        val status: LineDispatchStatus
    )

    private enum class ApplicabilityMatch { MATCH, NO_MATCH, UNKNOWN }

    fun classifyCockpitEntryExact(message: String?, cockpitAta: String?): String? {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return null
        val wanted = normalizeText(raw)
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit).orEmpty()
        val sql: String
        val args: Array<String>?
        if (chapter.length == 2) {
            sql = "SELECT title FROM mel_entries WHERE ata_chapter=?"
            args = arrayOf(chapter)
        } else {
            sql = "SELECT title FROM mel_entries"
            args = null
        }
        val exactMel = runCatching {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    if (normalizeText(c.getString(0).orEmpty()) == wanted) return@use true
                }
                false
            }
        }.getOrDefault(false)
        return if (exactMel) "EWD WARNING(S)" else null
    }

    fun resolvePfr(fault: PostFlightAnalyzer.Fault, aircraftProfile: String): LineAssistantResult {
        val hasCms = fault.category == "CMS FAULT MESSAGE" || !fault.ataFull.isNullOrBlank() ||
            !fault.source.isNullOrBlank() || !fault.faultClass.isNullOrBlank()
        val cockpitMessage = fault.cockpitEffect ?: fault.text.takeIf { !hasCms }
        return resolve(
            FaultInput(
                rawText = fault.displaySummary(),
                maintenanceMessage = if (hasCms) fault.text else "",
                maintenanceAta = if (hasCms) (fault.ataFull ?: fault.ata) else null,
                cockpitMessage = cockpitMessage,
                cockpitAta = fault.cockpitAta,
                source = if (hasCms) fault.source else null,
                faultClass = if (hasCms) fault.faultClass else null,
                identifiers = if (hasCms) fault.identifiers.distinct() else emptyList(),
                parsedFault = fault
            ),
            aircraftProfile
        )
    }

    fun resolveText(text: String, aircraftProfile: String): LineAssistantResult {
        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()
        val structuredPfr = parsed?.let {
            !it.ataFull.isNullOrBlank() ||
            !it.cockpitEffect.isNullOrBlank() ||
            !it.source.isNullOrBlank() ||
            !it.faultClass.isNullOrBlank() ||
            text.contains("COCKPIT EFFECT:", true) ||
            text.contains("MAINT ATA:", true) ||
            text.contains("SOURCE:", true) ||
            text.contains("CLASS:", true)
        } == true
        if (structuredPfr) return resolvePfr(parsed!!, aircraftProfile)

        val ata = Regex("\\b(\\d{2})[- ]?(\\d{2})[- ]?(\\d{2})\\b")
            .find(text.uppercase(Locale.US))
            ?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        val message = text
            .replace(Regex("(?i)\\bATA\\s*\\d{2}[- ]?\\d{2}[- ]?\\d{2}\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return resolve(
            FaultInput(
                rawText = text,
                maintenanceMessage = message,
                maintenanceAta = ata,
                cockpitMessage = null,
                cockpitAta = null,
                source = null,
                faultClass = null,
                identifiers = extractIdentifiers(text),
                parsedFault = null
            ),
            aircraftProfile
        )
    }

    private fun resolve(input: FaultInput, aircraftProfile: String): LineAssistantResult {
        val cockpitOnly = isCockpitOnlyInput(input)
        val cockpitCandidates = if (cockpitOnly)
            filterTsmCandidatesByProfile(findCockpitTsmCandidates(input), aircraftProfile)
        else emptyList()

        var candidateRecords: List<TsmIndexRecord> = emptyList()
        var state = LineTsmState.NOT_FOUND
        var note = ""
        val indexRecord: TsmIndexRecord?

        if (cockpitOnly) {
            val requiresCms = cockpitCandidates.any { it.hasFaultMessage }
            indexRecord = when {
                cockpitCandidates.size == 1 && !requiresCms -> cockpitCandidates.first()
                cockpitCandidates.isNotEmpty() -> {
                    candidateRecords = cockpitCandidates
                    state = LineTsmState.NEEDS_CMS
                    note = "Cockpit/STS entry is verified, but the TSM index contains Fault Message branches. Enter the CMS fault actually observed on the aircraft; the app will not fabricate or auto-select one."
                    null
                }
                else -> findTsmIndexRecord(input)
            }
        } else {
            val matches = filterTsmCandidatesByProfile(findTsmIndexMatches(input), aircraftProfile)
            indexRecord = chooseUniqueIndexRecord(matches, input)
            if (indexRecord == null && matches.size > 1) {
                candidateRecords = matches
                state = LineTsmState.AMBIGUOUS
                note = "More than one TSM index record still matches the verified input. Additional real aircraft evidence/effectivity is required; no task is selected automatically."
            }
        }

        val cockpitMessage = input.cockpitMessage ?: indexRecord?.cockpitMessage
        val cockpitAta = input.cockpitAta ?: indexRecord?.cockpitAta
        val cockpitSource = when {
            !input.cockpitMessage.isNullOrBlank() -> "PFR / USER-VERIFIED ENTRY"
            indexRecord != null -> "VERIFIED TSM INDEX"
            else -> "UNRESOLVED"
        }

        val mel = resolveMel(cockpitMessage, cockpitAta, input.rawText, aircraftProfile)
        val tsm = indexRecord?.let { resolveTaskFromIndex(it, input, cockpitSource) }
        if (tsm != null) {
            state = LineTsmState.RESOLVED
            note = "Unique TSM index mapping verified."
        } else if (candidateRecords.isEmpty() && state == LineTsmState.NOT_FOUND) {
            note = "No unique TSM Warnings/Malfunction index mapping was verified. Broad task guessing is disabled."
        }

        val orderedHits = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(hit: SearchHit?) {
            hit ?: return
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) orderedHits += hit
        }

        melEntryHit(cockpitMessage, mel)?.let(::add)
        mel.itemRefs.forEach { melItemHit(it)?.let(::add) }
        mel.conditions.flatMap { condition -> melProcedureHits(condition.conditionRef) }.forEach(::add)

        if (tsm != null) {
            taskHit(tsm.taskRef, "TSM")?.let(::add)
            supportHits(tsm, input).forEach(::add)
        }

        val publicCandidates = candidateRecords.distinctBy {
            "${it.indexTaskRef}|${it.maintenanceAta}|${normalizeText(it.maintenanceMessage)}|${it.source}|${it.faultClass}"
        }.map(::toPublicCandidate)

        return LineAssistantResult(
            summary = buildSummary(input, mel, tsm, aircraftProfile, publicCandidates, state, note),
            safetySummary = buildSafety(mel, tsm, aircraftProfile),
            evidencePack = buildEvidencePacket(input, mel, tsm, orderedHits),
            hits = orderedHits,
            mel = mel,
            tsm = tsm,
            structuredFault = input.parsedFault,
            tsmCandidates = publicCandidates,
            tsmState = state,
            tsmNote = note
        )
    }

    private fun isCockpitOnlyInput(input: FaultInput): Boolean {
        val f = input.parsedFault ?: return false
        if (f.category == "CMS FAULT MESSAGE") return false
        return input.maintenanceAta.isNullOrBlank() && input.source.isNullOrBlank() &&
            input.faultClass.isNullOrBlank() && f.ataFull.isNullOrBlank()
    }

    private fun toPublicCandidate(record: TsmIndexRecord) = LineTsmCandidate(
        taskRef = record.indexTaskRef,
        cockpitAta = record.cockpitAta,
        cockpitMessage = record.cockpitMessage,
        cockpitType = record.cockpitType,
        maintenanceAta = record.maintenanceAta,
        maintenanceMessage = record.maintenanceMessage,
        source = record.source,
        faultClass = record.faultClass,
        identifiers = record.identifiers,
        applicability = findTaskRow("TSM", record.indexTaskRef)?.applicability.orEmpty()
    )

    private fun filterTsmCandidatesByProfile(records: List<TsmIndexRecord>, aircraftProfile: String): List<TsmIndexRecord> {
        if (records.size <= 1 || aircraftProfile.isBlank()) return records
        val evaluated = records.map { record ->
            val applicability = findTaskRow("TSM", record.indexTaskRef)?.applicability.orEmpty()
            record to applicabilityMatch(applicability, aircraftProfile)
        }
        val matched = evaluated.filter { it.second == ApplicabilityMatch.MATCH }.map { it.first }
        if (matched.isNotEmpty()) return matched
        val notRejected = evaluated.filter { it.second != ApplicabilityMatch.NO_MATCH }.map { it.first }
        return if (notRejected.isNotEmpty()) notRejected else records
    }

    private fun findCockpitTsmCandidates(input: FaultInput): List<TsmIndexRecord> {
        val query = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
        val words = significantWords(query)
        if (words.isEmpty()) return emptyList()
        val expressions = mutableListOf<String>()
        expressions += words.take(5).joinToString(" AND ") { "\"$it\"*" }
        expressions += words.take(5).joinToString(" OR ") { "\"$it\"*" }

        val records = mutableListOf<TsmIndexRecord>()
        val seen = linkedSetOf<String>()
        for (expr in expressions.distinct()) {
            runCatching {
                db.rawQuery(
                    """
                    SELECT c.document_filename,c.pdf_page,c.content
                    FROM chunks_fts f JOIN chunks c ON c.chunk_id=f.chunk_id
                    WHERE chunks_fts MATCH ? AND UPPER(COALESCE(c.manual,''))='TSM'
                    ORDER BY c.pdf_page LIMIT 240
                    """.trimIndent(),
                    arrayOf(expr)
                ).use { c ->
                    while (c.moveToNext()) {
                        parseTsmIndexRecords(c.getString(0), c.getInt(1), c.getString(2)).forEach { r ->
                            val key = "${r.indexTaskRef}|${r.maintenanceAta}|${r.maintenanceMessage}|${r.source}|${r.faultClass}"
                            if (seen.add(key)) records += r
                        }
                    }
                }
            }
        }

        val requestedAta = input.cockpitAta?.let(::normalizeAta).orEmpty().take(4)
        val matches = records.filter { r ->
            val msg = normalizeText(r.cockpitMessage)
            val msgOk = normalizeText(query) == msg ||
                (words.all { containsWholeToken(msg, it) } && ocrMessageSimilarity(query, r.cockpitMessage) >= 0.90)
            val ataOk = requestedAta.isBlank() || r.cockpitAta.isBlank() || normalizeAta(r.cockpitAta).take(4) == requestedAta
            msgOk && ataOk && cockpitTypeMatches(input, r)
        }
        val cmsMatches = matches.filter { it.hasFaultMessage }
        return (if (cmsMatches.isNotEmpty()) cmsMatches else matches)
            .sortedWith(compareBy<TsmIndexRecord> { it.maintenanceMessage }.thenBy { it.indexTaskRef })
    }

    private fun sourceTokensEquivalent(observedRaw: String, trustedRaw: String): Boolean {
        val a = normalizeToken(observedRaw)
        val b = normalizeToken(trustedRaw)
        if (a == b) return true
        if (minOf(a.length, b.length) < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        return referenceAwareLevenshtein(a, b) <= 1
    }

    private fun legacyFindDirectTsmTaskDisabled(input: FaultInput): TaskRow? {
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
                            if (!candidates.containsKey(task.taskId)) {
                                val content = exactTaskContent(task)
                                if (directTaskContentMatches(content, input)) candidates[task.taskId] = task
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

    private fun legacyResolveDirectTaskDisabled(task: TaskRow, input: FaultInput, cockpitSource: String): LineTsmResolution? {
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

    private fun findTsmIndexMatches(input: FaultInput): List<TsmIndexRecord> {
        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage))
            .map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val words = significantWords(input.maintenanceMessage)
        val cockpitWords = significantWords(input.cockpitMessage.orEmpty())
        val expressions = mutableListOf<String>()
        if (ids.isNotEmpty()) expressions += ids.take(4).joinToString(" AND ") { "\"$it\"" }
        if (words.isNotEmpty()) expressions += words.take(5).joinToString(" AND ") { "\"$it\"*" }
        if (cockpitWords.isNotEmpty()) expressions += cockpitWords.take(5).joinToString(" AND ") { "\"$it\"*" }
        val orTerms = (ids.take(3) + words.take(5) + cockpitWords.take(4)).distinct()
        if (orTerms.isNotEmpty()) expressions += orTerms.joinToString(" OR ") { "\"$it\"*" }

        val candidates = mutableListOf<TsmIndexRecord>()
        val dedupe = linkedSetOf<String>()

        val exactAta = input.maintenanceAta?.trim().orEmpty()
        if (exactAta.matches(Regex("\\d{2}-\\d{2}-\\d{2}"))) {
            val sqlAta = """
                SELECT c.document_filename,c.pdf_page,c.content
                FROM chunks c
                WHERE UPPER(COALESCE(c.manual,''))='TSM'
                  AND UPPER(c.content) LIKE ?
                ORDER BY c.pdf_page LIMIT 240
            """.trimIndent()
            runCatching {
                db.rawQuery(sqlAta, arrayOf("%${exactAta.uppercase(Locale.US)}%")).use { c ->
                    while (c.moveToNext()) {
                        parseTsmIndexRecords(c.getString(0), c.getInt(1), c.getString(2)).forEach { record ->
                            val key = "${record.indexFilename}|${record.indexPage}|${record.indexTaskRef}|${record.maintenanceMessage}|${record.cockpitMessage}|${record.source}"
                            if (dedupe.add(key)) candidates += record
                        }
                    }
                }
            }
        }

        for (expr in expressions.filter { it.isNotBlank() }) {
            val sql = """
                SELECT c.document_filename,c.pdf_page,c.content
                FROM chunks_fts f JOIN chunks c ON c.chunk_id=f.chunk_id
                WHERE chunks_fts MATCH ? AND UPPER(COALESCE(c.manual,''))='TSM'
                ORDER BY c.pdf_page LIMIT 180
            """.trimIndent()
            runCatching {
                db.rawQuery(sql, arrayOf(expr)).use { c ->
                    while (c.moveToNext()) {
                        parseTsmIndexRecords(c.getString(0), c.getInt(1), c.getString(2)).forEach { record ->
                            val key = "${record.indexFilename}|${record.indexPage}|${record.indexTaskRef}|${record.maintenanceMessage}|${record.cockpitMessage}|${record.source}"
                            if (dedupe.add(key)) candidates += record
                        }
                    }
                }
            }
            if (candidates.any { exactIndexMatch(it, input) }) break
        }

        return candidates
            .filter { acceptableIndexMatch(it, input) && pfrPhotoMatchIsStrong(it, input) }
            .distinctBy { "${it.indexTaskRef}|${it.maintenanceAta}|${normalizeText(it.maintenanceMessage)}|${it.source}|${it.faultClass}" }
            .sortedByDescending { indexScore(it, input) }
    }

    private fun chooseUniqueIndexRecord(matches: List<TsmIndexRecord>, input: FaultInput): TsmIndexRecord? {
        if (matches.isEmpty()) return null
        val scored = matches.map { it to indexScore(it, input) }.sortedByDescending { it.second }
        val best = scored.first()
        val competing = scored.drop(1).firstOrNull {
            it.second == best.second && it.first.indexTaskRef != best.first.indexTaskRef
        }
        return if (competing == null) best.first else null
    }

    private fun findTsmIndexRecord(input: FaultInput): TsmIndexRecord? =
        chooseUniqueIndexRecord(findTsmIndexMatches(input), input)

    private fun parseTsmIndexRecords(filename: String, page: Int, content: String): List<TsmIndexRecord> {
        val lines = content.lines().map(::cleanLine).filter { it.isNotBlank() }
        val starts = lines.indices.filter { lines[it].equals("WARNINGS/MALFUNCTION", true) }
        if (starts.isEmpty()) return emptyList()
        val out = mutableListOf<TsmIndexRecord>()

        starts.forEachIndexed { idx, start ->
            val end = starts.getOrNull(idx + 1) ?: lines.size
            val block = lines.subList(start, end)
            val task = block.firstNotNullOfOrNull { line ->
                Regex("(?i)\\bTASK\\s+(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}(?:-[A-Z]\\d*)?)\\b")
                    .find(line)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
            } ?: return@forEachIndexed

            val faultMarker = block.indexOfFirst { it.equals("FAULT MESSAGES", true) }
            if (faultMarker >= 0) {
                val before = block.subList(0, faultMarker)
                val cockpitAtaIndex = before.indexOfLast { it.matches(Regex("\\d{2}-\\d{2}")) }
                val cockpitAta = before.getOrNull(cockpitAtaIndex).orEmpty()
                val cockpitValues = if (cockpitAtaIndex >= 0) before.drop(cockpitAtaIndex + 1) else emptyList()
                val cockpitType = cockpitValues.lastOrNull().orEmpty()
                val cockpitMessage = cockpitValues.dropLast(1).joinToString(" ").trim()

                val after = block.drop(faultMarker + 1)
                val maintAtaIndex = after.indexOfFirst { it.matches(Regex("\\d{2}-\\d{2}-\\d{2}")) }
                if (maintAtaIndex < 0) return@forEachIndexed
                val classIndex = ((maintAtaIndex + 1) until after.size).firstOrNull { after[it] in setOf("1", "2", "3") }
                    ?: return@forEachIndexed
                if (classIndex - maintAtaIndex < 3) return@forEachIndexed
                val source = after[classIndex - 1]
                val message = after.subList(maintAtaIndex + 1, classIndex - 1).joinToString(" ").trim()
                val identifiers = mutableListOf<String>()
                val idMarker = after.indexOfFirst { it.equals("IDENTIFIERS", true) || it.equals("IDENTIFIER", true) }
                if (idMarker >= 0) {
                    after.drop(idMarker + 1).take(8).forEach { line ->
                        if (line.matches(Regex("[A-Z0-9][A-Z0-9/.,-]*"))) {
                            identifiers += line.split(Regex("[/, ]+")).filter { it.isNotBlank() }
                        }
                    }
                }
                out += TsmIndexRecord(filename, page, task, cockpitAta, cockpitMessage, cockpitType,
                    after[maintAtaIndex], message, source, after[classIndex], identifiers.distinct(), true)
            } else {
                val header = block.indexOfFirst { it.equals("TYPE", true) }
                if (header < 0) return@forEachIndexed
                val payload = block.drop(header + 1).filterNot { it.startsWith("** ON A/C") || it.startsWith("EMB SB") }
                if (payload.isEmpty()) return@forEachIndexed
                val ataIndex = payload.indexOfFirst { it.matches(Regex("\\d{2}-\\d{2}")) }
                val cockpitAta = if (ataIndex >= 0) payload[ataIndex] else ""
                val startMessage = if (ataIndex >= 0) ataIndex + 1 else 0
                val knownTypes = listOf("OBSERVATIONS", "EWD WARNING(S)", "PFD FLAG(S)", "ND FLAG(S)", "STS-INOP SYSTEM", "STS-MAINTENANCE", "MAINTENANCE STATUS", "MAINT STATUS")
                var joined = payload.drop(startMessage).joinToString(" ").trim()
                val type = knownTypes.firstOrNull { joined.endsWith(it, true) }.orEmpty()
                if (type.isNotBlank()) joined = joined.dropLast(type.length).trim()
                if (joined.isBlank()) return@forEachIndexed
                out += TsmIndexRecord(filename, page, task, cockpitAta, joined, type,
                    "", "", "", "", emptyList(), false)
            }
        }
        return out
    }

    private fun pfrPhotoMatchIsStrong(record: TsmIndexRecord, input: FaultInput): Boolean {
        if (input.parsedFault == null) return true

        if (!record.hasFaultMessage) {
            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            return ocrMessageSimilarity(q, record.cockpitMessage) >= 0.94 && cockpitTypeMatches(input, record)
        }

        val requestedAta = input.maintenanceAta?.let(::normalizeAta).orEmpty()
        if (requestedAta.length >= 6 && normalizeAta(record.maintenanceAta).take(6) != requestedAta.take(6)) return false

        if (!input.source.isNullOrBlank() && record.source.isNotBlank() &&
            !sourceTokensEquivalent(input.source, record.source)) return false

        if (!input.faultClass.isNullOrBlank() && record.faultClass.isNotBlank() &&
            input.faultClass.trim() != record.faultClass.trim()) return false

        val inputIds = extractIdentifiers(input.maintenanceMessage)
            .map(::normalizeToken)
            .filter { it.length >= 3 }
            .distinct()
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        if (inputIds.isNotEmpty() && inputIds.any { !containsWholeToken(recordText, it) }) return false

        return ocrMessageSimilarity(input.maintenanceMessage, record.maintenanceMessage) >= 0.94
    }

    // DB-aware OCR comparison. The reference side is the trusted TSM index text.
    // A slash may be misread by OCR as I/L/|/1, but those characters are never
    // globally rewritten. They are treated as equivalent only when aligned to
    // an actual '/' in the verified TSM reference.
    private fun ocrMessageSimilarity(observedRaw: String, referenceRaw: String): Double {
        val observed = ocrComparable(observedRaw)
        val reference = ocrComparable(referenceRaw)
        if (observed.isBlank() || reference.isBlank()) return 0.0
        if (observed == reference) return 1.0
        if (minOf(observed.length, reference.length) < 8) return 0.0
        val distance = referenceAwareLevenshtein(observed, reference)
        return 1.0 - distance.toDouble() / maxOf(observed.length, reference.length).toDouble()
    }

    private fun ocrComparable(raw: String): String = normalizeText(raw)
        .filterNot { it.isWhitespace() }
        .replace(Regex("[^A-Z0-9/|]"), "")

    private fun referenceAwareLevenshtein(observed: String, reference: String): Int {
        var previous = IntArray(reference.length + 1) { it }
        var current = IntArray(reference.length + 1)
        for (i in 1..observed.length) {
            current[0] = i
            for (j in 1..reference.length) {
                val o = observed[i - 1]
                val r = reference[j - 1]
                val separatorAlias = r == '/' && (o == 'I' || o == 'L' || o == '|' || o == '1')
                val cost = if (o == r || separatorAlias) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val temp = previous
            previous = current
            current = temp
        }
        return previous[reference.length]
    }

    private fun cockpitTypeMatches(input: FaultInput, record: TsmIndexRecord): Boolean {
        val requested = input.parsedFault?.cockpitType.orEmpty().trim()
        if (requested.isBlank() || record.cockpitType.isBlank()) return true
        fun canonical(value: String): String {
            val compact = normalizeText(value).replace(Regex("[^A-Z]"), "")
            return when {
                compact.contains("MAINTENANCESTATUS") || compact.contains("MAINTSTATUS") || compact.contains("STSMAINTENANCE") -> "MAINTENANCESTATUS"
                compact.contains("EWDWARNING") || compact.contains("ECAMWARNING") -> "EWDWARNING"
                compact.contains("STSINOPSYSTEM") -> "STSINOPSYSTEM"
                compact.contains("PFDFLAG") -> "PFDFLAG"
                compact.contains("NDFLAG") -> "NDFLAG"
                compact.contains("SDFLAG") -> "SDFLAG"
                else -> compact
            }
        }
        return canonical(requested) == canonical(record.cockpitType)
    }

    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {
        if (!record.hasFaultMessage) {
            val query = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            val qWords = significantWords(query)
            val hay = normalizeText(record.cockpitMessage)
            val msgOk = qWords.isNotEmpty() && qWords.all { containsWholeToken(hay, it) }
            val qAta = input.cockpitAta ?: input.maintenanceAta
            val ataOk = qAta.isNullOrBlank() || record.cockpitAta.isBlank() || normalizeAta(qAta).take(4) == normalizeAta(record.cockpitAta).take(4)
            val typeOk = cockpitTypeMatches(input, record)
            return msgOk && ataOk && typeOk
        }

        // Maintenance ATA is the strongest field printed in the CMS row: never relax it.
        val ataOk = input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)
        if (!ataOk) return false

        // Source and class are also structured CMS fields. If both sides have them they must agree.
        if (!input.source.isNullOrBlank() && record.source.isNotBlank() && normalizeToken(input.source) != normalizeToken(record.source)) return false
        if (!input.faultClass.isNullOrBlank() && record.faultClass.isNotBlank() && input.faultClass.trim() != record.faultClass.trim()) return false

        // Only identifiers actually printed in the PFR Identifiers column are a gate.
        // Message-internal FINs are evidence, not mandatory exact identifiers.
        val printedIds = input.identifiers.map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val recordEvidence = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        if (printedIds.isNotEmpty() && printedIds.none { containsWholeToken(recordEvidence, it) }) return false

        if (!cmsMessageCompatible(input.maintenanceMessage, record.maintenanceMessage)) return false
        return true
    }

    private fun cmsMessageCompatible(query: String, candidate: String): Boolean {
        val q = baseFaultMessage(query)
        val c = baseFaultMessage(candidate)
        if (q == c) return true

        val qWords = significantWords(q).map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val cNorm = normalizeText(c)
        if (qWords.isEmpty()) return false
        val exactWordMatches = qWords.count { containsWholeToken(cNorm, it) }
        val coverage = exactWordMatches.toDouble() / qWords.size.toDouble()

        // FIN/equipment references such as 2CE2/67CE2 are strong independent evidence.
        val qRefs = strongCmsRefs(q)
        val cRefs = strongCmsRefs(c)
        val refOverlap = qRefs.intersect(cRefs).size

        // One OCR-damaged token is acceptable only when most of the message agrees,
        // or when at least two independent hardware references agree.
        return coverage >= 0.66 || (coverage >= 0.50 && refOverlap >= 1) || refOverlap >= 2
    }

    private fun strongCmsRefs(text: String): Set<String> {
        val upper = normalizeText(text)
        val out = linkedSetOf<String>()
        Regex("\\b\\d{1,3}[A-Z]{2,5}\\d{0,3}\\b").findAll(upper).forEach { out += normalizeToken(it.value) }
        Regex("\\b(?:FCPC|FCSC|FCDC|LGCIU|BSCU|BMC|FMGEC|SFCC|SDAC|FWC|DMU|FDIMU)\\d*\\b").findAll(upper).forEach { out += normalizeToken(it.value) }
        return out
    }

    private fun exactIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {
        if (!record.hasFaultMessage) {
            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            return normalizeText(q) == normalizeText(record.cockpitMessage) && cockpitTypeMatches(input, record)
        }
        val exactMessage = normalizeText(input.maintenanceMessage) == normalizeText(record.maintenanceMessage)
        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage))
            .map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        val idsOk = ids.isEmpty() || ids.all { containsWholeToken(recordText, it) }
        val ataOk = ataCompatible(input.maintenanceAta, record.maintenanceAta)
        return exactMessage && idsOk && ataOk
    }

    private fun indexScore(record: TsmIndexRecord, input: FaultInput): Int {
        var score = 0
        if (record.hasFaultMessage) {
            if (normalizeText(input.maintenanceMessage) == normalizeText(record.maintenanceMessage)) score += 400
            if (baseFaultMessage(input.maintenanceMessage) == baseFaultMessage(record.maintenanceMessage)) score += 180
            if (!input.maintenanceAta.isNullOrBlank() && normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)) score += 140
            if (!input.source.isNullOrBlank() && normalizeToken(input.source) == normalizeToken(record.source)) score += 80
            if (!input.faultClass.isNullOrBlank() && input.faultClass == record.faultClass) score += 40
            val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
            (input.identifiers + extractIdentifiers(input.maintenanceMessage)).distinct().forEach {
                if (containsWholeToken(recordText, normalizeToken(it))) score += 120
            }
        } else {
            val q = input.cockpitMessage?.takeIf { it.isNotBlank() } ?: input.maintenanceMessage
            if (normalizeText(q) == normalizeText(record.cockpitMessage)) score += 400
            significantWords(q).forEach { if (containsWholeToken(record.cockpitMessage, it)) score += 30 }
        }
        if (!input.cockpitMessage.isNullOrBlank() && melTitleCompatible(input.cockpitMessage, record.cockpitMessage)) score += 100
        return score
    }

    private fun resolveTaskFromIndex(record: TsmIndexRecord, input: FaultInput, cockpitSource: String): LineTsmResolution? {
        val indexedTask = findTaskRow("TSM", record.indexTaskRef) ?: return null
        val indexedContent = exactTaskContent(indexedTask)
        val branchInput = if (record.hasFaultMessage && input.parsedFault != null &&
            ocrMessageSimilarity(input.maintenanceMessage, record.maintenanceMessage) >= 0.94) {
            input.copy(
                maintenanceMessage = record.maintenanceMessage,
                identifiers = (input.identifiers + record.identifiers + extractIdentifiers(record.maintenanceMessage)).distinct()
            )
        } else input
        var branch = selectTroubleshootingBranch(indexedContent, branchInput)
        var activeTask = indexedTask
        var activeContent = indexedContent

        val redirect = branch?.redirectTaskRef
        if (!redirect.isNullOrBlank()) {
            val redirected = findTaskRow("TSM", redirect)
            if (redirected != null) {
                activeTask = redirected
                activeContent = exactTaskContent(redirected)
                branch = selectTroubleshootingBranch(activeContent, branchInput)
            }
        }

        return LineTsmResolution(
            indexTaskRef = record.indexTaskRef,
            taskRef = activeTask.reference,
            taskTitle = activeTask.title,
            indexPage = record.indexPage,
            taskStartPage = activeTask.startPage,
            taskEndPage = activeTask.endPage,
            cockpitMessage = record.cockpitMessage,
            cockpitAta = record.cockpitAta,
            maintenanceMessage = if (record.maintenanceMessage.isNotBlank()) record.maintenanceMessage else input.maintenanceMessage,
            maintenanceAta = if (record.maintenanceAta.isNotBlank()) record.maintenanceAta else input.maintenanceAta.orEmpty(),
            source = record.source,
            faultClass = record.faultClass,
            identifiers = (record.identifiers + input.identifiers).distinct(),
            taskContent = activeContent,
            branch = branch,
            cockpitEffectSource = cockpitSource
        )
    }

    private fun findTaskRow(manual: String, indexRef: String): TaskRow? {
        val clean = indexRef.removePrefix("TASK ").trim().uppercase(Locale.US)
        val sql = """
            SELECT t.task_id,d.filename,t.task_ref,COALESCE(t.task_title,''),t.start_pdf_page,
                   COALESCE(t.end_pdf_page,t.start_pdf_page),COALESCE(t.selected_applicability,'')
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))=?
              AND (UPPER(t.task_ref)=? OR UPPER(t.task_ref) LIKE ?)
            ORDER BY CASE WHEN UPPER(t.task_ref)=? THEN 0 ELSE 1 END,t.start_pdf_page
            LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(manual.uppercase(Locale.US), clean, "$clean-%", clean)).use { c ->
                if (!c.moveToFirst()) return@use null
                TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getString(6))
            }
        }.getOrNull()
    }

    private fun exactTaskContent(task: TaskRow): String {
        val raw = StringBuilder()
        runCatching {
            db.rawQuery(
                "SELECT content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_index",
                arrayOf(task.filename, task.startPage.toString(), task.endPage.toString())
            ).use { c -> while (c.moveToNext()) raw.append(c.getString(0)).append('\n') }
        }
        val text = raw.toString()
        val start = Regex("(?im)^TASK\\s+${Regex.escape(task.reference)}\\s*$").find(text)?.range?.first ?: 0
        val afterStart = text.substring(start)
        val nextTask = Regex("(?im)^TASK\\s+\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?\\s*$")
            .find(afterStart, 1)?.range?.first
        val endMarker = Regex("(?im)^END OF (?:THIS )?TASK\\b.*$").find(afterStart, 1)?.range?.last?.plus(1)
        val end = listOfNotNull(nextTask, endMarker).minOrNull() ?: afterStart.length
        return afterStart.substring(0, end).trim()
    }

    private fun selectTroubleshootingBranch(taskContent: String, input: FaultInput): LineTroubleshootingBranch? {
        if (taskContent.isBlank()) return null
        val possible = section(taskContent, "Possible Causes", "Fault Confirmation")
        val confirmation = section(taskContent, "Fault Confirmation", "Fault Isolation")
        val isolation = section(taskContent, "Fault Isolation", "Close-up")
        if (isolation.isBlank()) {
            val evidence = listOf(possible, confirmation, taskContent).filter { it.isNotBlank() }.joinToString("\n\n")
            return LineTroubleshootingBranch("FULL TASK PROCEDURE", null, null, possible, confirmation, taskContent, "", evidence)
        }

        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage)).map(::normalizeToken).distinct()
        val prefixes = ids.mapNotNull { Regex("^(\\d{1,4})GA").find(it)?.groupValues?.getOrNull(1) }.distinct()

        data class Mapping(val paragraph: String?, val redirect: String?, val score: Int, val selector: String)
        val mappings = mutableListOf<Mapping>()
        val paraRegex = Regex("(?is)(.{0,520}?)(?:PROCEDURE\\s+IN\\s+)?PARA\\.\\s*4\\s*\\.\\s*A\\s*\\.\\s*\\((\\d+)\\)")
        paraRegex.findAll(confirmation).forEach { m ->
            val window = normalizeText(m.groupValues[1])
            val local = localSelectorClause(window)
            mappings += Mapping(m.groupValues[2], null, mappingScore(local, ids, prefixes), local.takeLast(360))
        }
        val redirectRegex = Regex("(?is)(.{0,520}?)\\(REF\\.\\s*TSM\\s+(\\d{2}-\\d{2}-\\d{2}-810-\\d{3})\\)")
        redirectRegex.findAll(confirmation).forEach { m ->
            val window = normalizeText(m.groupValues[1])
            val local = localSelectorClause(window)
            mappings += Mapping(null, m.groupValues[2], mappingScore(local, ids, prefixes), local.takeLast(360))
        }

        val best = mappings.filter { it.score > 0 }
            .sortedWith(compareByDescending<Mapping> { it.score }.thenByDescending { if (it.redirect != null) 1 else 0 })
            .firstOrNull()
        if (best?.redirect != null) {
            val evidence = listOf(possible, confirmation).filter { it.isNotBlank() }.joinToString("\n\n")
            return LineTroubleshootingBranch(best.selector, null, best.redirect, possible, confirmation, "", "", evidence)
        }

        val paragraph = best?.paragraph
        if (paragraph.isNullOrBlank()) {
            val evidence = listOf(possible, confirmation, isolation).filter { it.isNotBlank() }.joinToString("\n\n")
            return LineTroubleshootingBranch("FULL FAULT ISOLATION — no unique sub-branch selected", null, null, possible, confirmation, isolation, "", evidence)
        }

        val steps = topLevelSteps(isolation)
        val selected = steps[paragraph].orEmpty()
        if (selected.isBlank()) {
            val evidence = listOf(possible, confirmation, isolation).filter { it.isNotBlank() }.joinToString("\n\n")
            return LineTroubleshootingBranch("Para. 4.A.($paragraph) selected but step text not isolated", paragraph, null, possible, confirmation, isolation, "", evidence)
        }
        val explicitNext = Regex("(?i)DO\\s+(?:THE\\s+)?(?:NEXT\\s+)?STEP\\s*\\((\\d+)\\)")
            .findAll(selected).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.minOrNull()
        val continuation = if (explicitNext != null) {
            steps.entries
                .filter { it.key.toIntOrNull()?.let { n -> n >= explicitNext } == true }
                .sortedBy { it.key.toIntOrNull() ?: 999 }
                .joinToString("\n") { it.value }
        } else ""
        val evidence = listOf(possible, confirmation, selected, continuation).filter { it.isNotBlank() }.joinToString("\n\n")
        return LineTroubleshootingBranch(
            selector = best.selector,
            paragraph = paragraph,
            redirectTaskRef = null,
            possibleCauses = possible,
            faultConfirmation = confirmation,
            isolation = selected,
            continuation = continuation,
            evidence = evidence
        )
    }

    private fun localSelectorClause(window: String): String {
        val bullet = "· IF THE MESSAGE"
        val idx = window.lastIndexOf(bullet)
        return if (idx >= 0) window.substring(idx) else window
    }

    private fun mappingScore(window: String, ids: List<String>, prefixes: List<String>): Int {
        var score = 0
        ids.forEach { if (containsWholeToken(window, it)) score += 80 }
        prefixes.forEach { prefix ->
            if (Regex("XX\\s*=\\s*[^.\\n]{0,120}\\b${Regex.escape(prefix)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(window)) score += 60
        }
        return score
    }

    private fun topLevelSteps(isolation: String): Map<String, String> {
        val matches = Regex("(?m)^\\((\\d+)\\)\\s").findAll(isolation).toList()
        if (matches.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        matches.forEachIndexed { i, m ->
            val end = matches.getOrNull(i + 1)?.range?.first ?: isolation.length
            out[m.groupValues[1]] = isolation.substring(m.range.first, end).trim()
        }
        return out
    }

    private fun section(text: String, startLabel: String, endLabel: String): String {
        val start = Regex("(?im)^\\d+\\.\\s*\\n${Regex.escape(startLabel)}\\s*$").find(text)
            ?: Regex("(?im)^${Regex.escape(startLabel)}\\s*$").find(text)
            ?: return ""
        val tail = text.substring(start.range.first)
        val end = Regex("(?im)^\\d+\\.\\s*\\n${Regex.escape(endLabel)}\\s*$").find(tail, 1)
            ?: Regex("(?im)^${Regex.escape(endLabel)}\\s*$").find(tail, 1)
        return if (end == null) tail.trim() else tail.substring(0, end.range.first).trim()
    }

    private fun resolveMel(cockpitMessage: String?, cockpitAta: String?, rawText: String, aircraftProfile: String): LineMelResolution {
        if (!cockpitMessage.isNullOrBlank()) {
            val exact = findCompatibleMelEntries(cockpitMessage, cockpitAta)
            if (exact.isNotEmpty()) return chooseMelVariant(exact, aircraftProfile)
        }
        return LineMelResolution(
            LineDispatchStatus.NO_ENTRY,
            cockpitMessage ?: "No exact MEL entry",
            cockpitAta?.take(2).orEmpty(),
            null,
            "",
            emptyList(),
            "No exact MEL cockpit/ECAM entry confirmed. Equipment-level guessing is disabled; absence of a match is never dispatch approval."
        )
    }

    private fun findCompatibleMelEntries(cockpitMessage: String, cockpitAta: String?): List<MelCandidate> {
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit)
        val rows = mutableListOf<MelEntryRow>()
        val sql = if (!chapter.isNullOrBlank()) {
            "SELECT mel_entry_id,COALESCE(ata_chapter,''),title,start_pdf_page,end_pdf_page,content FROM mel_entries WHERE ata_chapter=?"
        } else {
            "SELECT mel_entry_id,COALESCE(ata_chapter,''),title,start_pdf_page,end_pdf_page,content FROM mel_entries"
        }
        runCatching {
            db.rawQuery(sql, if (!chapter.isNullOrBlank()) arrayOf(chapter) else null).use { c ->
                while (c.moveToNext()) rows += MelEntryRow("MEL-E-${c.getLong(0)}", c.getString(1), c.getString(2).trim(), c.getInt(3), c.getInt(4), c.getString(5))
            }
        }

        val dedupe = linkedMapOf<String, MelCandidate>()
        rows.filter { melTitleCompatible(cockpitMessage, it.title) }.forEach { row ->
            val block = isolateMelBlock(row.content, row.title).ifBlank { return@forEach }
            val refs = extractMelItemRefs(block)
            val candidate = MelCandidate(
                row = row,
                block = block,
                applicability = parseApplicability(block),
                itemRefs = refs,
                status = dispatchStatus(block, refs)
            )
            val key = normalizeText(block)
            dedupe.putIfAbsent(key, candidate)
        }
        return dedupe.values.toList()
    }

    private fun chooseMelVariant(candidates: List<MelCandidate>, aircraftProfile: String): LineMelResolution {
        val selected: MelCandidate
        var selectedByProfile = false

        if (candidates.size == 1) {
            selected = candidates.first()
            if (aircraftProfile.isNotBlank() && applicabilityMatch(selected.applicability, aircraftProfile) == ApplicabilityMatch.NO_MATCH) {
                return ambiguousMel(candidates, "Exact MEL alert exists, but its indexed applicability does not match the entered aircraft profile. Verify original MEL/effectivity.")
            }
        } else {
            if (aircraftProfile.isBlank()) {
                return ambiguousMel(candidates, "Multiple MEL effectivity variants exist for this exact alert. Enter REG/MSN/aircraft variant before any dispatch decision.")
            }
            val matched = candidates.filter { applicabilityMatch(it.applicability, aircraftProfile) == ApplicabilityMatch.MATCH }
            if (matched.size != 1) {
                return ambiguousMel(candidates, if (matched.isEmpty())
                    "No unique MEL effectivity variant matches the entered aircraft profile. Verify original MEL."
                else "More than one MEL effectivity variant matches the entered aircraft profile. Verify original MEL.")
            }
            selected = matched.first()
            selectedByProfile = true
        }

        val conditions = selected.itemRefs.flatMap { ref -> loadMelConditions(ref, aircraftProfile) }
            .distinctBy { "${it.conditionRef}|${it.applicability}|${normalizeText(it.requirements)}" }
        val profileNote = if (aircraftProfile.isBlank()) {
            "Effectivity not verified; original MEL applicability must be checked."
        } else {
            "Aircraft profile: $aircraftProfile. Selected applicability: ${selected.applicability.ifBlank { "not indexed" }}."
        }
        val hasConditionEffectivityVariants = conditions.groupBy { it.conditionRef }.any { (_, list) -> list.map { it.applicability }.distinct().size > 1 }
        val stateNote = when {
            selected.itemRefs.isNotEmpty() && conditions.isEmpty() -> " MEL item condition/effectivity was not uniquely resolved; verify the original item."
            hasConditionEffectivityVariants && aircraftProfile.isBlank() -> " Multiple MEL condition effectivity variants exist; enter MSN/aircraft variant before selecting a condition."
            conditions.size > 1 && selected.itemRefs.none { it.lastOrNull()?.isLetter() == true } -> " Multiple MEL conditions/sub-items exist; aircraft state must select the applicable condition before dispatch."
            else -> ""
        }
        val note = when (selected.status) {
            LineDispatchStatus.NO_DISPATCH -> "Exact MEL ECAM block states NO DISPATCH. $profileNote"
            LineDispatchStatus.MUST_BE_OPERATIVE -> "Exact MEL block requires the item to be operative. $profileNote"
            LineDispatchStatus.CONDITIONAL -> "MEL directs to specific item/condition(s). No GO is claimed until all limitations, (O)/(M) procedures and effectivity are verified. $profileNote$stateNote"
            LineDispatchStatus.VERIFY_REQUIRED -> "Exact MEL entry found but dispatch wording requires manual verification. $profileNote"
            LineDispatchStatus.NO_ENTRY -> "No exact MEL entry."
        }
        return LineMelResolution(
            selected.status,
            selected.row.title,
            selected.row.ata,
            selected.row.page,
            selected.block,
            selected.itemRefs,
            note,
            applicability = selected.applicability,
            conditions = conditions,
            variantCount = candidates.size,
            selectedByProfile = selectedByProfile
        )
    }

    private fun ambiguousMel(candidates: List<MelCandidate>, note: String): LineMelResolution {
        val preview = candidates.joinToString("\n\n--- MEL VARIANT ---\n") {
            "Applicable to: ${it.applicability.ifBlank { "UNKNOWN" }}\n${it.block.take(1800)}"
        }
        val first = candidates.first()
        return LineMelResolution(
            LineDispatchStatus.VERIFY_REQUIRED,
            first.row.title,
            first.row.ata,
            first.row.page,
            preview,
            emptyList(),
            note,
            applicability = candidates.joinToString(" | ") { it.applicability.ifBlank { "UNKNOWN" } },
            conditions = emptyList(),
            variantCount = candidates.size,
            selectedByProfile = false
        )
    }

    private fun dispatchStatus(block: String, refs: List<String>): LineDispatchStatus {
        val upper = normalizeText(block)
        return when {
            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE
            upper.contains("NO DISPATCH") && refs.isEmpty() -> LineDispatchStatus.NO_DISPATCH
            upper.contains("NO DISPATCH") && refs.isNotEmpty() -> LineDispatchStatus.VERIFY_REQUIRED
            refs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL
            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL
            else -> LineDispatchStatus.VERIFY_REQUIRED
        }
    }

    private fun isolateMelBlock(content: String, title: String): String {
        val text = content.replace("\r", "")
        val matches = Regex("(?im)^\\s*ECAM Alert:\\s*(.+?)\\s*$").findAll(text).toList()
        matches.forEachIndexed { i, m ->
            if (melTitleCompatible(title, m.groupValues[1])) {
                val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
                return text.substring(m.range.first, end).trim()
            }
        }
        return ""
    }

    private fun parseApplicability(block: String): String = Regex("(?im)^\\s*Applicable to:\\s*(.+?)\\s*$")
        .find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun extractMelItemRefs(block: String): List<String> {
        val out = linkedSetOf<String>()
        val lines = block.lines()
        lines.forEachIndexed { i, line ->
            if (line.contains("Refer to Item", true)) {
                val scope = (listOf(line) + lines.drop(i + 1).take(2).takeWhile { !it.startsWith("ECAM Alert:", true) }).joinToString(" ")
                Regex("\\b\\d{2}-\\d{2}-\\d{2}(?:-\\d{2})?[A-Z]?\\b").findAll(scope).forEach { out += it.value.uppercase(Locale.US) }
            }
        }
        return out.toList()
    }

    private fun loadMelConditions(requestedRef: String, aircraftProfile: String): List<LineMelCondition> {
        val exactExists = scalarInt("SELECT COUNT(*) FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))='${sqlSafe(requestedRef)}'") > 0
        val base = if (exactExists) requestedRef else requestedRef.removeSuffixLetter()
        val requestedCondition = if (!exactExists && requestedRef.length > base.length) requestedRef else null
        val sql = """
            SELECT COALESCE(mel_ref,''),content,COALESCE(repair_interval,''),COALESCE(nbr_installed,''),
                   COALESCE(nbr_required,''),COALESCE(placard,'')
            FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))=? ORDER BY start_pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(base.uppercase(Locale.US))).use { c ->
                if (!c.moveToFirst()) return@use emptyList()
                val content = exactMelItemContent(base, c.getString(1))
                val parsed = parseMelConditions(base, content, c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                    .filter { requestedCondition == null || it.conditionRef.equals(requestedCondition, true) }
                if (aircraftProfile.isBlank()) parsed else {
                    val matched = parsed.filter { applicabilityMatch(it.applicability, aircraftProfile) == ApplicabilityMatch.MATCH }
                    if (matched.isNotEmpty()) matched else emptyList()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun exactMelItemContent(baseRef: String, content: String): String {
        val lines = content.lines()
        val start = lines.indexOfFirst { it.trim().equals(baseRef, true) }
        if (start < 0) return content
        val basePattern = Regex("^\\d{2}-\\d{2}-\\d{2}(?:-\\d{2})?$")
        val end = ((start + 1) until lines.size).firstOrNull { idx ->
            val value = lines[idx].trim()
            basePattern.matches(value) && !value.equals(baseRef, true)
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun parseMelConditions(baseRef: String, content: String, fallbackInterval: String, fallbackInstalled: String, fallbackRequired: String, fallbackPlacard: String): List<LineMelCondition> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        val marker = Regex("^${Regex.escape(baseRef)}([A-Z])(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
        val starts = lines.mapIndexedNotNull { i, line -> marker.find(line)?.let { i to it } }
        if (starts.isEmpty()) {
            return listOf(LineMelCondition(baseRef, "", fallbackInterval, fallbackInstalled, fallbackRequired, fallbackPlacard,
                parseApplicability(content), content.take(2200), emptyList(), emptyList()))
        }
        val out = mutableListOf<LineMelCondition>()
        starts.forEachIndexed { index, pair ->
            val start = pair.first
            val match = pair.second
            val end = starts.getOrNull(index + 1)?.first ?: lines.size
            val segment = lines.subList(start, end)
            val conditionRef = baseRef + match.groupValues[1].uppercase(Locale.US)
            val title = match.groupValues.getOrNull(2).orEmpty().trim()
            val applicability = lines.subList(0, start + 1).lastOrNull { it.trimStart().startsWith("Applicable to:", true) }
                ?.substringAfter(':')?.trim().orEmpty()
            val header = segment.indexOfFirst { it.equals("Repair interval", true) }
            var interval = fallbackInterval
            var installed = fallbackInstalled
            var required = fallbackRequired
            var placard = fallbackPlacard
            var afterFields = 1
            if (header >= 0) {
                val placardHeader = ((header + 1) until segment.size).firstOrNull { segment[it].equals("Placard", true) }
                if (placardHeader != null && placardHeader + 4 < segment.size) {
                    interval = segment[placardHeader + 1]
                    installed = segment[placardHeader + 2]
                    required = segment[placardHeader + 3]
                    placard = segment[placardHeader + 4]
                    afterFields = placardHeader + 5
                }
            }
            val refIndex = segment.indexOfFirst { it.equals("Reference(s)", true) }
            val reqEnd = if (refIndex >= 0) refIndex else segment.size
            val requirements = segment.subList(afterFields.coerceAtMost(reqEnd), reqEnd).joinToString(" ").trim()
            val refsText = if (refIndex >= 0) segment.drop(refIndex + 1).joinToString(" ") else ""
            val procRefs = Regex("(?i)OpsProc\\s+(\\d{2}-\\d{2}-\\d{2}(?:-\\d{2})?[A-Z]?)")
                .findAll(refsText).map { it.groupValues[1].uppercase(Locale.US) }.distinct().toList()
            val ammRefs = Regex("(?i)AMM\\s+Task\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?)")
                .findAll(refsText).map { it.groupValues[1].uppercase(Locale.US) }.distinct().toList()
            out += LineMelCondition(conditionRef, title, interval, installed, required, placard, applicability, requirements, procRefs, ammRefs)
        }
        return out
    }

    private fun applicabilityMatch(applicability: String, profile: String): ApplicabilityMatch {
        if (applicability.isBlank() || profile.isBlank()) return ApplicabilityMatch.UNKNOWN
        val app = normalizeText(applicability)
        val p = normalizeText(profile)
        val explicitMsn = Regex("\\bMSN\\s*[:#-]?\\s*0*(\\d{1,4})\\b").find(p)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val modelSuffix = Regex("A340-(\\d{3})").find(p)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val msn = explicitMsn ?: Regex("\\b0*(\\d{1,4})\\b").findAll(p)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it != 340 && it != modelSuffix }
        val family = when {
            p.contains("A340-600") || Regex("A340-6\\d{2}").containsMatchIn(p) -> "A340-600"
            p.contains("A340-300") || Regex("A340-3\\d{2}").containsMatchIn(p) -> "A340-300"
            p.contains("A340-200") || Regex("A340-2\\d{2}").containsMatchIn(p) -> "A340-200"
            else -> null
        }

        if (app.contains("ALL EXCEPT MSN")) {
            if (msn == null) return ApplicabilityMatch.UNKNOWN
            return if (msnInExpression(msn, app.substringAfter("ALL EXCEPT MSN"))) ApplicabilityMatch.NO_MATCH else ApplicabilityMatch.MATCH
        }
        if (app == "ALL" || app.startsWith("ALL;")) return ApplicabilityMatch.MATCH

        val hasMsn = app.contains("MSN")
        val hasFamily = listOf("A340-200", "A340-300", "A340-600").any { app.contains(it) }
        var evidence = false
        if (hasMsn && msn != null) {
            evidence = true
            if (msnInExpression(msn, app)) return ApplicabilityMatch.MATCH
        }
        if (hasFamily && family != null) {
            evidence = true
            if (app.contains(family)) return ApplicabilityMatch.MATCH
        }
        return if (evidence) ApplicabilityMatch.NO_MATCH else ApplicabilityMatch.UNKNOWN
    }

    private fun msnInExpression(msn: Int, text: String): Boolean {
        Regex("0*(\\d{1,4})\\s*-\\s*0*(\\d{1,4})").findAll(text).forEach {
            val a = it.groupValues[1].toIntOrNull() ?: return@forEach
            val b = it.groupValues[2].toIntOrNull() ?: return@forEach
            if (msn in minOf(a, b)..maxOf(a, b)) return true
        }
        Regex("(?<![-\\d])0*(\\d{1,4})(?![-\\d])").findAll(text).forEach {
            if (it.groupValues[1].toIntOrNull() == msn) return true
        }
        return false
    }

    private fun supportHits(tsm: LineTsmResolution, input: FaultInput): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(h: SearchHit?) {
            h ?: return
            val key = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
            if (seen.add(key)) out += h
        }

        val scoped = tsm.branch?.let { branch ->
            listOf(branch.isolation, branch.continuation)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        } ?: tsm.taskContent
        val upper = scoped.uppercase(Locale.US)
        Regex("\\b(?:REF\\.\\s*)?AMM(?:\\s+TASK)?\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?)\\b")
            .findAll(upper).map { it.groupValues[1] }.distinct().take(18).forEach { taskHit(it, "AMM")?.let(::add) }
        Regex("\\b(?:REF\\.\\s*)?ASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(upper).map { it.groupValues[1] }.distinct().take(8).forEach { asmHit(it)?.let(::add) }
        Regex("\\b(?:REF\\.\\s*)?AWM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(upper).map { it.groupValues[1] }.distinct().take(8).forEach { awmDiagramHit(it)?.let(::add) }
        Regex("\\b(?:REF\\.\\s*)?ESPM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(upper).map { it.groupValues[1] }.distinct().take(6).forEach { espmHit(it)?.let(::add) }

        val ids = linkedSetOf<String>()
        ids += input.identifiers
        val scopedNorm = normalizeText(scoped)
        ids += tsm.identifiers.filter { containsWholeToken(scopedNorm, normalizeToken(it)) }
        // Support manuals are seeded only by the verified selected TSM path.
        ids += extractIdentifiers(scoped)
        ids += extractReferencedPartNumbers(scoped)
        ids.filter { it.length >= 3 }.take(28).forEach { id ->
            awmIdentifierHit(id)?.let(::add)
            ipcIdentifierHit(id)?.let(::add)
        }
        return out.take(32)
    }

    private fun taskHit(ref: String, manual: String): SearchHit? {
        val row = findTaskRow(manual, ref) ?: return null
        return SearchHit("task", row.taskId, manual, row.reference, row.title, exactTaskContent(row).take(2000), -4500.0)
    }

    private fun asmHit(ref: String): SearchHit? = runCatching {
        db.rawQuery("SELECT sheet_id,schematic_ref,COALESCE(title,''),COALESCE(short_title,'') FROM asm_sheets WHERE UPPER(schematic_ref)=? OR UPPER(schematic_ref) LIKE ? ORDER BY pdf_page LIMIT 1",
            arrayOf(ref.uppercase(Locale.US), "${ref.uppercase(Locale.US)}%" )).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("asm_sheet", c.getString(0), "ASM", c.getString(1), c.getString(2).ifBlank { c.getString(3) }, "Referenced by selected TSM branch", -4300.0)
        }
    }.getOrNull()

    private fun awmDiagramHit(ref: String): SearchHit? = runCatching {
        db.rawQuery("SELECT awm_diagram_id,diagram_ref,COALESCE(title,'') FROM awm_diagrams WHERE UPPER(diagram_ref)=? OR UPPER(diagram_ref) LIKE ? ORDER BY awm_diagram_id LIMIT 1",
            arrayOf(ref.uppercase(Locale.US), "${ref.uppercase(Locale.US)}%" )).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("awm_diagram", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Referenced by selected TSM branch", -4200.0)
        }
    }.getOrNull()

    private fun espmHit(ref: String): SearchHit? = runCatching {
        db.rawQuery("SELECT section_id,section_ref,COALESCE(title,''),COALESCE(first_page_text,'') FROM espm_sections WHERE UPPER(section_ref)=? LIMIT 1",
            arrayOf(ref.uppercase(Locale.US))).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("espm_section", c.getString(0), "ESPM", c.getString(1), c.getString(2), c.getString(3).take(1000), -4150.0)
        }
    }.getOrNull()

    private fun awmIdentifierHit(id: String): SearchHit? = runCatching {
        val raw = id.trim().uppercase(Locale.US)
        val normalized = normalizeToken(id)
        db.rawQuery(
            """SELECT i.id,i.token,i.kind,p.pdf_page
               FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id
               WHERE UPPER(i.token)=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(i.token),'-',''),'/',''),' ',''),'.','')=?
               ORDER BY p.pdf_page LIMIT 1""".trimIndent(),
            arrayOf(raw, normalized)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            SearchHit("awm_identifier", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier • PDF page ${c.getInt(3)}", -4100.0)
        }
    }.getOrNull()

    private fun ipcIdentifierHit(id: String): SearchHit? = runCatching {
        val raw = id.trim().uppercase(Locale.US)
        val normalized = normalizeToken(id)
        db.rawQuery(
            """SELECT COALESCE(fin,''),part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'')
               FROM ipc_items
               WHERE UPPER(COALESCE(fin,''))=?
                  OR UPPER(part_number)=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(COALESCE(fin,'')),'-',''),'/',''),' ',''),'.','')=?
                  OR REPLACE(REPLACE(REPLACE(REPLACE(UPPER(part_number),'-',''),'/',''),' ',''),'.','')=?
               ORDER BY pdf_page LIMIT 1""".trimIndent(),
            arrayOf(raw, raw, normalized, normalized)
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val ref = c.getString(0).ifBlank { c.getString(1) }
            SearchHit("part_number", c.getString(1), "IPC", ref, c.getString(2), c.getString(3).take(1200), -4000.0)
        }
    }.getOrNull()

    private fun melEntryHit(cockpitMessage: String?, mel: LineMelResolution): SearchHit? {
        if (mel.page == null || cockpitMessage.isNullOrBlank() || mel.variantCount > 1 && !mel.selectedByProfile) return null
        val row = findCompatibleMelEntries(cockpitMessage, mel.ataChapter).firstOrNull { it.row.page == mel.page } ?: return null
        return SearchHit("mel_entry", row.row.id, "MEL", row.row.title, "Exact MEL ECAM entry • ${row.row.title}", mel.exactBlock.take(2000), -5000.0)
    }

    private fun melItemHit(requestedRef: String): SearchHit? {
        val exactCount = scalarInt("SELECT COUNT(*) FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))='${sqlSafe(requestedRef)}'")
        val base = if (exactCount > 0) requestedRef else requestedRef.removeSuffixLetter()
        return runCatching {
            db.rawQuery("SELECT mel_item_id,COALESCE(mel_ref,''),title,content FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))=? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(base.uppercase(Locale.US))).use { c ->
                if (!c.moveToFirst()) return@use null
                SearchHit("mel_item", "MEL-I-${c.getLong(0)}", "MEL", c.getString(1), c.getString(2), exactMelItemContent(base, c.getString(3)).take(2200), -4800.0)
            }
        }.getOrNull()
    }

    private fun melProcedureHits(conditionRef: String): List<SearchHit> {
        val base = conditionRef.removeSuffixLetter()
        val out = mutableListOf<SearchHit>()
        runCatching {
            db.rawQuery("SELECT mel_procedure_id,COALESCE(procedure_ref,''),title,content FROM mel_procedures WHERE UPPER(COALESCE(procedure_ref,'')) LIKE ? ORDER BY start_pdf_page LIMIT 8",
                arrayOf("${base.uppercase(Locale.US)}%" )).use { c ->
                while (c.moveToNext()) {
                    out += SearchHit("mel_procedure", "MEL-P-${c.getLong(0)}", "MEL", c.getString(1), "Operational Procedure • ${c.getString(2)}", c.getString(3).take(2200), -4700.0)
                }
            }
        }
        return out
    }

    private fun legacyFindEquipmentMelItemDisabled(rawText: String, cockpitAta: String?): SearchHit? {
        val upper = rawText.uppercase(Locale.US)
        if (upper.contains("APU")) return melItemHit("49-10-01")
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit)
            ?: Regex("\\bATA\\s*(\\d{2})").find(upper)?.groupValues?.getOrNull(1)
        if (chapter.isNullOrBlank()) return null
        val terms = significantWords(rawText).take(5)
        if (terms.isEmpty()) return null
        val rows = mutableListOf<SearchHit>()
        runCatching {
            db.rawQuery("SELECT mel_item_id,mel_ref,title,content FROM mel_items WHERE ata_chapter=?", arrayOf(chapter)).use { c ->
                while (c.moveToNext()) {
                    val hay = normalizeText(c.getString(2) + " " + c.getString(3))
                    val matches = terms.count { containsWholeToken(hay, it) }
                    if (matches >= 2) rows += SearchHit("mel_item", "MEL-I-${c.getLong(0)}", "MEL", c.getString(1).orEmpty(), c.getString(2), c.getString(3).take(1200), -1000.0 - matches)
                }
            }
        }
        return rows.sortedBy { it.score }.firstOrNull()
    }

    private fun buildSummary(input: FaultInput, mel: LineMelResolution, tsm: LineTsmResolution?, profile: String, candidates: List<LineTsmCandidate>, state: LineTsmState, note: String): String = buildString {
        append("LINE ASSISTANT 1.5.1 • OCR + VERIFIED SUPPORT SOURCES\n\n")
        input.parsedFault?.let { append("PFR STRUCTURED RECORD\n").append(it.displaySummary()).append("\n\n") }

        append("1 • MEL / DISPATCH\n")
        append("Entry: ").append(mel.title).append('\n')
        append("Status: ").append(when (mel.status) {
            LineDispatchStatus.NO_DISPATCH -> "NO DISPATCH"
            LineDispatchStatus.MUST_BE_OPERATIVE -> "MUST BE OPERATIVE"
            LineDispatchStatus.CONDITIONAL -> "CONDITIONAL — VERIFY MEL CONDITION"
            LineDispatchStatus.VERIFY_REQUIRED -> "VERIFY ORIGINAL MEL / EFFECTIVITY"
            LineDispatchStatus.NO_ENTRY -> "NO EXACT MEL ENTRY CONFIRMED"
        }).append('\n')
        mel.page?.let { append("Original MEL PDF page: ").append(it).append('\n') }
        if (mel.applicability.isNotBlank()) append("Applicability: ").append(mel.applicability).append('\n')
        if (mel.variantCount > 1) append("Effectivity variants: ").append(mel.variantCount).append('\n')
        if (mel.itemRefs.isNotEmpty()) append("Referenced MEL item(s): ").append(mel.itemRefs.joinToString(", ")).append('\n')
        mel.conditions.forEach { c ->
            append("  ").append(c.conditionRef)
            if (c.title.isNotBlank()) append(" — ").append(c.title)
            append(" | Interval ").append(c.repairInterval.ifBlank { "?" })
            append(" | Installed ").append(c.nbrInstalled.ifBlank { "?" })
            append(" | Required ").append(c.nbrRequired.ifBlank { "?" })
            append(" | Placard ").append(c.placard.ifBlank { "?" })
            if (c.applicability.isNotBlank()) append(" | Applies ").append(c.applicability)
            append('\n')
        }
        append(mel.note).append("\n\n")

        append("2 • TSM TROUBLESHOOTING\n")
        if (candidates.isNotEmpty()) {
            append(when (state) {
                LineTsmState.NEEDS_CMS -> "COCKPIT / STS ENTRY VERIFIED • ACTUAL CMS FAULT REQUIRED.\n"
                LineTsmState.AMBIGUOUS -> "TSM MATCH REMAINS AMBIGUOUS • MORE VERIFIED AIRCRAFT DATA REQUIRED.\n"
                else -> "TSM CANDIDATES REQUIRE VERIFICATION.\n"
            })
            append(note).append('\n')
            append("Candidate index records: ").append(candidates.size).append('\n')
            candidates.forEachIndexed { i, c ->
                append(i + 1).append(") ").append(c.maintenanceAta).append(" • ")
                    .append(c.maintenanceMessage)
                if (c.source.isNotBlank()) append(" • Source ").append(c.source)
                if (c.faultClass.isNotBlank()) append(" • Class ").append(c.faultClass)
                if (c.applicability.isNotBlank()) append(" • Applies ").append(c.applicability)
                append(" • ").append(c.taskRef).append('\n')
            }
        } else if (tsm == null) {
            append("Exact TSM Warnings/Malfunction mapping NOT RESOLVED. Broad TSM lists and direct-task guessing are suppressed.\n")
            if (note.isNotBlank()) append(note).append('\n')
        } else {
            append("Index mapping: ").append(tsm.indexTaskRef).append('\n')
            append("Verified task: ").append(tsm.taskRef).append(" — ").append(tsm.taskTitle).append('\n')
            append("Complete task PDF range: ").append(tsm.taskStartPage).append('-').append(tsm.taskEndPage).append('\n')
            append("Cockpit effect: ").append(tsm.cockpitAta).append(" • ").append(tsm.cockpitMessage)
                .append(" [").append(tsm.cockpitEffectSource).append("]\n")
            if (tsm.maintenanceMessage.isNotBlank()) append("Maintenance fault: ").append(tsm.maintenanceAta).append(" • ").append(tsm.maintenanceMessage).append('\n')
            tsm.branch?.let { b ->
                append("Selected troubleshooting path: ")
                when {
                    !b.redirectTaskRef.isNullOrBlank() -> append("redirect to ").append(b.redirectTaskRef)
                    !b.paragraph.isNullOrBlank() -> append("Para. 4.A.(").append(b.paragraph).append(')')
                    else -> append(b.selector)
                }
                append('\n')
                if (b.selector.isNotBlank()) append("Branch basis: ").append(b.selector.take(360)).append('\n')
            }
        }

        append("\n3 • EFFECTIVITY\n")
        append(if (profile.isBlank()) "NOT VERIFIED — enter REG/MSN/aircraft variant before applying maintenance or dispatch action." else "Aircraft profile: $profile — verify each original source applicability before use.")
        append("\n\nSOURCE MANUALS REMAIN AUTHORITATIVE.")
    }

    private fun buildSafety(mel: LineMelResolution, tsm: LineTsmResolution?, profile: String): String = buildString {
        append("MEL STATUS: ").append(mel.status.name)
        append("\nMEL VARIANTS: ").append(mel.variantCount)
        append("\nTSM EXACT: ").append(tsm?.taskRef ?: "NOT RESOLVED")
        append("\nTSM BRANCH: ").append(tsm?.branch?.paragraph?.let { "4.A.($it)" } ?: tsm?.branch?.selector ?: "NOT SELECTED")
        append("\nEFFECTIVITY: ").append(if (profile.isBlank()) "NOT VERIFIED" else profile)
        append("\nAI MAY EXPLAIN VERIFIED EVIDENCE ONLY; IT MUST NOT CHANGE MEL STATUS, EFFECTIVITY OR TSM TASK/BRANCH.")
    }

    private fun buildEvidencePacket(input: FaultInput, mel: LineMelResolution, tsm: LineTsmResolution?, hits: List<SearchHit>): String = buildString {
        append("[E1 MEL EXACT BLOCK]\n").append(mel.exactBlock.ifBlank { mel.note }.take(5000)).append("\n\n")
        if (mel.conditions.isNotEmpty()) {
            append("[E2 MEL SELECTED/AVAILABLE CONDITIONS]\n")
            mel.conditions.forEach { c ->
                append(c.conditionRef).append(" ").append(c.title).append(" | Interval=").append(c.repairInterval)
                    .append(" Installed=").append(c.nbrInstalled).append(" Required=").append(c.nbrRequired)
                    .append(" Placard=").append(c.placard)
                    .append(" Applicability=").append(c.applicability.ifBlank { "UNKNOWN" }).append('\n')
                append(c.requirements.take(1800)).append("\n\n")
            }
        }
        if (tsm != null) {
            append("[E3 TSM INDEX MAPPING]\n")
            append("Cockpit: ${tsm.cockpitAta} ${tsm.cockpitMessage}\n")
            append("Fault: ${tsm.maintenanceAta} ${tsm.maintenanceMessage}\n")
            append("Source: ${tsm.source} Class: ${tsm.faultClass} Identifiers: ${tsm.identifiers.joinToString(", ")}\n")
            append("Ref TASK: ${tsm.indexTaskRef} -> Active verified ${tsm.taskRef}\n\n")
            append("[E4 SELECTED TROUBLESHOOTING BRANCH]\n")
            append((tsm.branch?.evidence ?: tsm.taskContent).take(18000)).append("\n\n")
        }
        hits.filter { it.manual !in setOf("MEL", "TSM") }.take(12).forEachIndexed { i, h ->
            append("[E${i + 5} ${h.manual} ${h.reference}]\n")
            append(h.title).append('\n').append(h.snippet.take(1200)).append("\n\n")
        }
        append("[INPUT]\n").append(input.rawText.take(1600))
    }

    private fun melTitleCompatible(a: String, b: String): Boolean {
        val left = canonicalMelText(a)
        return expandMelAlternatives(canonicalMelText(b)).any { canonicalMelText(it) == left }
    }

    private fun expandMelAlternatives(text: String): Set<String> {
        var current = linkedSetOf(text)
        val alt = Regex("([A-Z0-9]+)\\(([A-Z0-9]+)\\)")
        repeat(6) {
            val next = linkedSetOf<String>()
            var changed = false
            current.forEach { s ->
                val m = alt.find(s)
                if (m == null) next += s else {
                    changed = true
                    next += s.replaceRange(m.range, m.groupValues[1])
                    next += s.replaceRange(m.range, m.groupValues[2])
                }
            }
            current = next
            if (!changed) return current
        }
        return current
    }

    private fun canonicalMelText(value: String): String = normalizeText(value)
        .replace(Regex("\\bPACK(\\d)\\b"), "PACK $1")
        .replace(Regex("\\bENG(\\d)\\b"), "ENG $1")
        .replace(Regex("\\s+"), " ").trim()

    private fun significantWords(text: String): List<String> {
        val stop = setOf("ATA", "REF", "MESSAGE", "TYPE", "SOURCE", "CLASS", "FAULT", "FAULTS", "THE", "OF", "A", "AN", "AND", "STATUS")
        return Regex("[A-Z0-9]+").findAll(normalizeText(text)).map { it.value }
            .filter { it.length >= 2 && it !in stop && !it.matches(Regex("\\d{2,6}")) }.distinct().toList()
    }

    private fun extractIdentifiers(text: String): List<String> {
        val upper = text.uppercase(Locale.US)
        val out = linkedSetOf<String>()
        Regex("\\b\\d{1,4}[A-Z]{1,4}\\d{0,4}\\b").findAll(upper).forEach { out += it.value }
        Regex("\\b(?:LGCIU\\d|BMC\\d(?:-\\d)?)\\b").findAll(upper).forEach { out += it.value }
        return out.filterNot { it.matches(Regex("\\d+")) }.take(28)
    }

    private fun extractReferencedPartNumbers(text:String):List<String> {
        val out=linkedSetOf<String>()
        Regex("\\b(?:P/N|PN|PART\\s*(?:NO\\.?|NUMBER))\\s*[:#=-]?\\s*([A-Z0-9][A-Z0-9./-]{3,})\\b",RegexOption.IGNORE_CASE).findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.uppercase(Locale.US)?.trim()?.trimEnd('.',',',';',':') }.filter { it.any(Char::isDigit) }.forEach(out::add)
        return out.take(16)
    }

    private fun baseFaultMessage(text: String): String = normalizeText(text).replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
    private fun normalizeText(text: String): String = text.uppercase(Locale.US).replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun cleanLine(text: String): String = normalizeText(text)
    private fun normalizeToken(text: String): String = normalizeText(text).replace(" ", "")
    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)

    private fun ataCompatible(requested: String?, candidate: String): Boolean {
        if (requested.isNullOrBlank()) return true
        val r = normalizeAta(requested)
        val c = normalizeAta(candidate)
        return when {
            r.length >= 6 -> c.take(6) == r.take(6)
            r.length >= 4 -> c.take(4) == r.take(4)
            r.length >= 2 -> c.take(2) == r.take(2)
            else -> true
        }
    }

    private fun containsWholeToken(haystack: String, token: String): Boolean {
        if (token.isBlank()) return false
        val h = normalizeText(haystack)
        val t = Regex.escape(normalizeText(token))
        return Regex("(?<![A-Z0-9])$t(?![A-Z0-9])").containsMatchIn(h)
    }

    private fun String.removeSuffixLetter(): String {
        val m = Regex("^(\\d{2}-\\d{2}-\\d{2}(?:-\\d{2})?)[A-Z]$").find(this.uppercase(Locale.US))
        return m?.groupValues?.getOrNull(1) ?: this
    }

    private fun scalarInt(sql: String): Int = runCatching {
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    private fun sqlSafe(value: String): String = value.uppercase(Locale.US).replace("'", "''")

    override fun close() = db.close()
}
