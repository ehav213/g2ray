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

data class LineMelResolution(
    val status: LineDispatchStatus,
    val title: String,
    val ataChapter: String,
    val page: Int?,
    val exactBlock: String,
    val itemRefs: List<String>,
    val note: String
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
    val taskContent: String
)

data class LineAssistantResult(
    val summary: String,
    val safetySummary: String,
    val evidencePack: String,
    val hits: List<SearchHit>,
    val mel: LineMelResolution,
    val tsm: LineTsmResolution?,
    val structuredFault: PostFlightAnalyzer.Fault?
)

/**
 * Database-native Line Maintenance engine.
 *
 * Design rules:
 * 1) MEL is resolved from the cockpit effect / ECAM entry, never from a generic fault-word score.
 * 2) TSM troubleshooting is resolved through the TSM Warnings/Malfunction index to an exact Ref. TASK.
 * 3) The accepted Ref. TASK must exist in task_entries before it can be shown.
 * 4) AMM/ASM/AWM/IPC support is derived only after the exact TSM task has been resolved.
 * 5) AI never chooses MEL dispatch status or invents a TSM task; it receives the already verified packet.
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
        val identifiers: List<String>
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
        val applicableTo: String,
        val content: String
    )

    fun resolvePfr(fault: PostFlightAnalyzer.Fault, aircraftProfile: String): LineAssistantResult {
        return resolve(
            FaultInput(
                rawText = fault.displaySummary(),
                maintenanceMessage = fault.text,
                maintenanceAta = fault.ataFull ?: fault.ata,
                cockpitMessage = fault.cockpitEffect,
                cockpitAta = fault.cockpitAta,
                source = fault.source,
                faultClass = fault.faultClass,
                identifiers = (fault.identifiers + extractIdentifiers(fault.text)).distinct(),
                parsedFault = fault
            ),
            aircraftProfile
        )
    }

    fun resolveText(text: String, aircraftProfile: String): LineAssistantResult {
        val parsed = runCatching { PostFlightAnalyzer.analyze(text).faults.firstOrNull() }.getOrNull()
        if (parsed != null) return resolvePfr(parsed, aircraftProfile)

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
        val indexRecord = findTsmIndexRecord(input)
        val cockpitMessage = input.cockpitMessage ?: indexRecord?.cockpitMessage
        val cockpitAta = input.cockpitAta ?: indexRecord?.cockpitAta
        val mel = resolveMel(cockpitMessage, cockpitAta, input.rawText, aircraftProfile)
        val tsm = indexRecord?.let(::resolveTaskFromIndex)

        val orderedHits = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(hit: SearchHit?) {
            hit ?: return
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) orderedHits += hit
        }

        melEntryHit(cockpitMessage, mel)?.let(::add)
        mel.itemRefs.forEach { ref -> melItemHit(ref)?.let(::add) }
        mel.itemRefs.flatMap(::melProcedureHits).forEach(::add)

        if (tsm != null) {
            taskHit(tsm.taskRef, "TSM")?.let(::add)
            supportHits(tsm, input).forEach(::add)
        }

        val summary = buildSummary(input, mel, tsm, aircraftProfile)
        val safety = buildSafety(mel, tsm, aircraftProfile)
        val evidence = buildEvidencePacket(input, mel, tsm, orderedHits)
        return LineAssistantResult(summary, safety, evidence, orderedHits, mel, tsm, input.parsedFault)
    }

    private fun findTsmIndexRecord(input: FaultInput): TsmIndexRecord? {
        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage)).map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val words = significantWords(input.maintenanceMessage)
        val expressions = mutableListOf<String>()
        if (ids.isNotEmpty()) expressions += ids.take(4).joinToString(" AND ") { "\"$it\"" }
        if (words.isNotEmpty()) expressions += words.take(5).joinToString(" AND ") { "\"$it\"*" }
        if (ids.isNotEmpty() || words.isNotEmpty()) {
            expressions += (ids.take(3) + words.take(5)).distinct().joinToString(" OR ") { "\"$it\"*" }
        }

        val candidates = mutableListOf<TsmIndexRecord>()
        val dedupe = linkedSetOf<String>()
        for (expr in expressions.filter { it.isNotBlank() }) {
            val sql = """
                SELECT c.document_filename,c.pdf_page,c.content
                FROM chunks_fts f JOIN chunks c ON c.chunk_id=f.chunk_id
                WHERE chunks_fts MATCH ? AND UPPER(COALESCE(c.manual,''))='TSM'
                ORDER BY c.pdf_page LIMIT 120
            """.trimIndent()
            runCatching {
                db.rawQuery(sql, arrayOf(expr)).use { c ->
                    while (c.moveToNext()) {
                        val filename = c.getString(0)
                        val page = c.getInt(1)
                        parseTsmIndexRecords(filename, page, c.getString(2)).forEach { record ->
                            val k = "${record.indexFilename}|${record.indexPage}|${record.indexTaskRef}|${record.maintenanceMessage}|${record.source}|${record.identifiers.joinToString()}"
                            if (dedupe.add(k)) candidates += record
                        }
                    }
                }
            }
            if (candidates.any { exactIndexMatch(it, input) }) break
        }

        val matched = candidates.filter { acceptableIndexMatch(it, input) }
        if (matched.isEmpty()) return null
        val scored = matched.map { it to indexScore(it, input) }.sortedByDescending { it.second }
        val best = scored.first()
        val competing = scored.drop(1).firstOrNull { it.second == best.second && it.first.indexTaskRef != best.first.indexTaskRef }
        return if (competing == null) best.first else null
    }

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
            if (faultMarker < 0) return@forEachIndexed

            val before = block.subList(0, faultMarker)
            val cockpitAtaIndex = before.indexOfLast { it.matches(Regex("\\d{2}-\\d{2}")) }
            val cockpitAta = before.getOrNull(cockpitAtaIndex).orEmpty()
            val cockpitValues = if (cockpitAtaIndex >= 0) before.drop(cockpitAtaIndex + 1) else emptyList()
            val cockpitType = cockpitValues.lastOrNull().orEmpty()
            val cockpitMessage = cockpitValues.dropLast(1).joinToString(" ").trim()

            val after = block.drop(faultMarker + 1)
            val maintAtaIndex = after.indexOfFirst { it.matches(Regex("\\d{2}-\\d{2}-\\d{2}")) }
            if (maintAtaIndex < 0) return@forEachIndexed
            val classIndex = ((maintAtaIndex + 1) until after.size).firstOrNull { after[it] in setOf("1", "2", "3") } ?: return@forEachIndexed
            if (classIndex - maintAtaIndex < 3) return@forEachIndexed
            val source = after[classIndex - 1]
            val message = after.subList(maintAtaIndex + 1, classIndex - 1).joinToString(" ").trim()
            val identifiers = mutableListOf<String>()
            val idMarker = after.indexOfFirst { it.equals("IDENTIFIERS", true) || it.equals("IDENTIFIER", true) }
            if (idMarker >= 0) {
                after.drop(idMarker + 1).take(6).forEach { line ->
                    if (line.matches(Regex("[A-Z0-9][A-Z0-9/.,-]*"))) {
                        identifiers += line.split(Regex("[/, ]+")).filter { it.isNotBlank() }
                    }
                }
            }
            out += TsmIndexRecord(
                indexFilename = filename,
                indexPage = page,
                indexTaskRef = task,
                cockpitAta = cockpitAta,
                cockpitMessage = cockpitMessage,
                cockpitType = cockpitType,
                maintenanceAta = after[maintAtaIndex],
                maintenanceMessage = message,
                source = source,
                faultClass = after[classIndex],
                identifiers = identifiers.distinct()
            )
        }
        return out
    }

    private fun acceptableIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {
        val ataOk = input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)
        if (!ataOk) return false
        val requestedIds = (input.identifiers + extractIdentifiers(input.maintenanceMessage)).map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        if (requestedIds.isNotEmpty() && requestedIds.any { !containsWholeToken(recordText, it) }) return false
        val a = baseFaultMessage(input.maintenanceMessage)
        val b = baseFaultMessage(record.maintenanceMessage)
        val words = significantWords(a)
        val messageOk = a == b || (words.isNotEmpty() && words.all { containsWholeToken(normalizeText(b), it) })
        if (!messageOk) return false
        if (!input.source.isNullOrBlank() && normalizeToken(input.source) != normalizeToken(record.source)) return false
        return true
    }

    private fun exactIndexMatch(record: TsmIndexRecord, input: FaultInput): Boolean {
        val exactMessage = normalizeText(input.maintenanceMessage) == normalizeText(record.maintenanceMessage)
        val ids = (input.identifiers + extractIdentifiers(input.maintenanceMessage)).map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        val idsOk = ids.isEmpty() || ids.all { containsWholeToken(recordText, it) }
        val ataOk = input.maintenanceAta.isNullOrBlank() || normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)
        return exactMessage && idsOk && ataOk
    }

    private fun indexScore(record: TsmIndexRecord, input: FaultInput): Int {
        var score = 0
        if (normalizeText(input.maintenanceMessage) == normalizeText(record.maintenanceMessage)) score += 400
        if (baseFaultMessage(input.maintenanceMessage) == baseFaultMessage(record.maintenanceMessage)) score += 180
        if (!input.maintenanceAta.isNullOrBlank() && normalizeAta(input.maintenanceAta) == normalizeAta(record.maintenanceAta)) score += 140
        if (!input.source.isNullOrBlank() && normalizeToken(input.source) == normalizeToken(record.source)) score += 80
        if (!input.faultClass.isNullOrBlank() && input.faultClass == record.faultClass) score += 40
        if (!input.cockpitMessage.isNullOrBlank() && melTitleCompatible(input.cockpitMessage, record.cockpitMessage)) score += 100
        val recordText = normalizeText(record.maintenanceMessage + " " + record.identifiers.joinToString(" "))
        (input.identifiers + extractIdentifiers(input.maintenanceMessage)).distinct().forEach {
            if (containsWholeToken(recordText, normalizeToken(it))) score += 120
        }
        return score
    }

    private fun resolveTaskFromIndex(record: TsmIndexRecord): LineTsmResolution? {
        val task = findTaskRow("TSM", record.indexTaskRef) ?: return null
        val content = taskContent(task)
        return LineTsmResolution(
            indexTaskRef = record.indexTaskRef,
            taskRef = task.reference,
            taskTitle = task.title,
            indexPage = record.indexPage,
            taskStartPage = task.startPage,
            taskEndPage = task.endPage,
            cockpitMessage = record.cockpitMessage,
            cockpitAta = record.cockpitAta,
            maintenanceMessage = record.maintenanceMessage,
            maintenanceAta = record.maintenanceAta,
            source = record.source,
            faultClass = record.faultClass,
            identifiers = record.identifiers,
            taskContent = content
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

    private fun taskContent(task: TaskRow): String {
        val out = StringBuilder()
        runCatching {
            db.rawQuery(
                "SELECT content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_index",
                arrayOf(task.filename, task.startPage.toString(), task.endPage.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if (out.length > 180000) break
                    out.append(c.getString(0)).append('\n')
                }
            }
        }
        return out.toString().trim()
    }

    private fun resolveMel(cockpitMessage: String?, cockpitAta: String?, rawText: String, aircraftProfile: String): LineMelResolution {
        if (!cockpitMessage.isNullOrBlank()) {
            val exact = findCompatibleMelEntry(cockpitMessage, cockpitAta)
            if (exact != null) return melFromEntry(exact, aircraftProfile)
        }

        val fallback = findEquipmentMelItem(rawText, cockpitAta)
        if (fallback != null) {
            return LineMelResolution(
                status = LineDispatchStatus.VERIFY_REQUIRED,
                title = fallback.reference + " — " + fallback.title,
                ataChapter = fallback.reference.take(2),
                page = repo.resolve(fallback)?.pdfPage,
                exactBlock = fallback.snippet,
                itemRefs = listOf(fallback.reference),
                note = "No exact MEL ECAM entry was resolved. Equipment-level MEL candidate only; verify original MEL and effectivity."
            )
        }
        return LineMelResolution(
            status = LineDispatchStatus.NO_ENTRY,
            title = cockpitMessage ?: "No exact MEL entry",
            ataChapter = cockpitAta?.take(2).orEmpty(),
            page = null,
            exactBlock = "",
            itemRefs = emptyList(),
            note = "No exact MEL entry confirmed. Absence of a match is never dispatch approval."
        )
    }

    private fun findCompatibleMelEntry(cockpitMessage: String, cockpitAta: String?): MelEntryRow? {
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit)
        val rows = mutableListOf<MelEntryRow>()
        val sql = if (!chapter.isNullOrBlank()) {
            "SELECT mel_entry_id,COALESCE(ata_chapter,''),title,start_pdf_page,end_pdf_page,COALESCE(applicable_to,''),content FROM mel_entries WHERE ata_chapter=?"
        } else {
            "SELECT mel_entry_id,COALESCE(ata_chapter,''),title,start_pdf_page,end_pdf_page,COALESCE(applicable_to,''),content FROM mel_entries"
        }
        runCatching {
            db.rawQuery(sql, if (!chapter.isNullOrBlank()) arrayOf(chapter) else null).use { c ->
                while (c.moveToNext()) {
                    rows += MelEntryRow("MEL-E-${c.getLong(0)}", c.getString(1), c.getString(2).trim(), c.getInt(3), c.getInt(4), c.getString(5), c.getString(6))
                }
            }
        }
        return rows.firstOrNull { melTitleCompatible(cockpitMessage, it.title) }
    }

    private fun melFromEntry(row: MelEntryRow, aircraftProfile: String): LineMelResolution {
        val block = isolateMelBlock(row.content, row.title).ifBlank { row.content }
        val itemRefs = Regex("(?i)Refer\\s+to\\s+Item\\s+(\\d{2}-\\d{2}-\\d{2})")
            .findAll(block).map { it.groupValues[1] }.distinct().toList()
        val upper = block.uppercase(Locale.US)
        val status = when {
            upper.contains("NO DISPATCH") && itemRefs.isEmpty() -> LineDispatchStatus.NO_DISPATCH
            upper.contains("MUST BE OPERATIVE") -> LineDispatchStatus.MUST_BE_OPERATIVE
            itemRefs.isNotEmpty() -> LineDispatchStatus.CONDITIONAL
            upper.contains("MAY BE INOPERATIVE") -> LineDispatchStatus.CONDITIONAL
            else -> LineDispatchStatus.VERIFY_REQUIRED
        }
        val profileNote = if (aircraftProfile.isBlank()) {
            "Effectivity not verified; original MEL applicability must be checked."
        } else {
            "Aircraft profile: $aircraftProfile. Verify applicability against original MEL wording: ${row.applicableTo.ifBlank { "not indexed" }}."
        }
        val note = when (status) {
            LineDispatchStatus.NO_DISPATCH -> "Exact MEL ECAM block states NO DISPATCH. $profileNote"
            LineDispatchStatus.MUST_BE_OPERATIVE -> "Exact MEL block requires the item to be operative. $profileNote"
            LineDispatchStatus.CONDITIONAL -> "MEL directs to specific item/conditions. No GO is claimed until all conditions, (O)/(M) procedures and effectivity are verified. $profileNote"
            LineDispatchStatus.VERIFY_REQUIRED -> "Exact MEL entry found but dispatch wording requires manual verification. $profileNote"
            LineDispatchStatus.NO_ENTRY -> "No exact MEL entry."
        }
        return LineMelResolution(status, row.title, row.ata, row.page, block, itemRefs, note)
    }

    private fun isolateMelBlock(content: String, title: String): String {
        val text = content.replace("\r", "")
        val regex = Regex("(?im)^ECAM Alert:\\s*(.+?)\\s*$")
        val matches = regex.findAll(text).toList()
        val target = normalizeText(title)
        matches.forEachIndexed { i, m ->
            val header = m.groupValues[1]
            if (melTitleCompatible(target, header)) {
                val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
                return text.substring(m.range.first, end).trim()
            }
        }
        return ""
    }

    private fun melTitleCompatible(a: String, b: String): Boolean {
        val left = canonicalMelText(a)
        val variants = expandMelAlternatives(canonicalMelText(b))
        return variants.any { canonicalMelText(it) == left }
    }

    private fun expandMelAlternatives(text: String): Set<String> {
        var current = linkedSetOf(text)
        val alt = Regex("([A-Z0-9]+)\\(([A-Z0-9]+)\\)")
        repeat(6) {
            val next = linkedSetOf<String>()
            var changed = false
            current.forEach { s ->
                val m = alt.find(s)
                if (m == null) next += s
                else {
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

    private fun canonicalMelText(value: String): String {
        return normalizeText(value)
            .replace(Regex("\\bPACK(\\d)\\b"), "PACK $1")
            .replace(Regex("\\bENG(\\d)\\b"), "ENG $1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findEquipmentMelItem(rawText: String, cockpitAta: String?): SearchHit? {
        val upper = rawText.uppercase(Locale.US)
        if (upper.contains("APU")) return melItemHit("49-10-01")
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit)
            ?: Regex("\\bATA\\s*(\\d{2})").find(upper)?.groupValues?.getOrNull(1)
        if (chapter.isNullOrBlank()) return null
        val terms = significantWords(rawText).take(5)
        if (terms.isEmpty()) return null
        val rows = mutableListOf<SearchHit>()
        val sql = "SELECT mel_item_id,mel_ref,title,start_pdf_page,content FROM mel_items WHERE ata_chapter=?"
        runCatching {
            db.rawQuery(sql, arrayOf(chapter)).use { c ->
                while (c.moveToNext()) {
                    val hay = normalizeText(c.getString(2) + " " + c.getString(4))
                    val matches = terms.count { containsWholeToken(hay, it) }
                    if (matches >= 2) {
                        rows += SearchHit("mel_item", "MEL-I-${c.getLong(0)}", "MEL", c.getString(1).orEmpty(), c.getString(2), c.getString(4).take(1200), -1000.0 - matches)
                    }
                }
            }
        }
        return rows.sortedBy { it.score }.firstOrNull()
    }

    private fun melEntryHit(cockpitMessage: String?, mel: LineMelResolution): SearchHit? {
        if (mel.page == null || cockpitMessage.isNullOrBlank()) return null
        val row = findCompatibleMelEntry(cockpitMessage, mel.ataChapter) ?: return null
        return SearchHit("mel_entry", row.id, "MEL", row.title, "Exact MEL ECAM entry • ${row.title}", mel.exactBlock.take(1800), -5000.0)
    }

    private fun melItemHit(ref: String): SearchHit? {
        val sql = "SELECT mel_item_id,COALESCE(mel_ref,''),title,content FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))=? ORDER BY start_pdf_page LIMIT 1"
        return runCatching {
            db.rawQuery(sql, arrayOf(ref.uppercase(Locale.US))).use { c ->
                if (!c.moveToFirst()) return@use null
                SearchHit("mel_item", "MEL-I-${c.getLong(0)}", "MEL", c.getString(1), c.getString(2), c.getString(3).take(1800), -4800.0)
            }
        }.getOrNull()
    }

    private fun melProcedureHits(itemRef: String): List<SearchHit> {
        val prefix = itemRef.uppercase(Locale.US)
        val out = mutableListOf<SearchHit>()
        runCatching {
            db.rawQuery(
                "SELECT mel_procedure_id,COALESCE(procedure_ref,''),title,content FROM mel_procedures WHERE UPPER(COALESCE(procedure_ref,'')) LIKE ? ORDER BY start_pdf_page LIMIT 6",
                arrayOf("$prefix%")
            ).use { c ->
                while (c.moveToNext()) {
                    out += SearchHit("mel_procedure", "MEL-P-${c.getLong(0)}", "MEL", c.getString(1), "Operational Procedure • ${c.getString(2)}", c.getString(3).take(1800), -4700.0)
                }
            }
        }
        return out
    }

    private fun supportHits(tsm: LineTsmResolution, input: FaultInput): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(h: SearchHit?) {
            h ?: return
            val k = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
            if (seen.add(k)) out += h
        }

        val text = tsm.taskContent.uppercase(Locale.US)
        val ammRefs = Regex("\\bAMM(?:\\s+TASK)?\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z]\\d*)?)\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(14).toList()
        ammRefs.forEach { ref -> taskHit(ref, "AMM")?.let(::add) }

        val asmRefs = Regex("\\bASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(8).toList()
        asmRefs.forEach { ref -> asmHit(ref)?.let(::add) }

        val awmRefs = Regex("\\bAWM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(6).toList()
        awmRefs.forEach { ref -> awmDiagramHit(ref)?.let(::add) }

        val ids = linkedSetOf<String>()
        ids += input.identifiers
        ids += extractIdentifiers(input.maintenanceMessage)
        ids += tsm.identifiers
        ids += extractIdentifiers(tsm.taskContent).take(14)
        ids.filter { it.length >= 3 }.take(16).forEach { id ->
            awmIdentifierHit(id)?.let(::add)
            ipcIdentifierHit(id)?.let(::add)
        }
        return out.take(28)
    }

    private fun taskHit(ref: String, manual: String): SearchHit? {
        val row = findTaskRow(manual, ref) ?: return null
        return SearchHit("task", row.taskId, manual, row.reference, row.title, taskContent(row).take(1800), -4500.0)
    }

    private fun asmHit(ref: String): SearchHit? {
        val sql = """
            SELECT a.sheet_id,a.schematic_ref,COALESCE(a.title,''),COALESCE(a.short_title,'')
            FROM asm_sheets a
            WHERE UPPER(a.schematic_ref)=? OR UPPER(a.schematic_ref) LIKE ?
            ORDER BY a.pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(ref.uppercase(Locale.US), "${ref.uppercase(Locale.US)}%" )).use { c ->
                if (!c.moveToFirst()) return@use null
                SearchHit("asm_sheet", c.getString(0), "ASM", c.getString(1), c.getString(2).ifBlank { c.getString(3) }, "Referenced by exact TSM task", -4300.0)
            }
        }.getOrNull()
    }

    private fun awmDiagramHit(ref: String): SearchHit? {
        val sql = "SELECT awm_diagram_id,diagram_ref,COALESCE(title,'') FROM awm_diagrams WHERE UPPER(diagram_ref)=? OR UPPER(diagram_ref) LIKE ? ORDER BY awm_diagram_id LIMIT 1"
        return runCatching {
            db.rawQuery(sql, arrayOf(ref.uppercase(Locale.US), "${ref.uppercase(Locale.US)}%" )).use { c ->
                if (!c.moveToFirst()) return@use null
                SearchHit("awm_diagram", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Referenced by exact TSM task", -4200.0)
            }
        }.getOrNull()
    }

    private fun awmIdentifierHit(id: String): SearchHit? {
        val sql = """
            SELECT i.id,i.token,i.kind,p.pdf_page,d.filename
            FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id JOIN documents d ON d.document_id=p.document_id
            WHERE UPPER(i.token)=? ORDER BY p.pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(normalizeToken(id))).use { c ->
                if (!c.moveToFirst()) return@use null
                SearchHit("awm_identifier", c.getLong(0).toString(), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier • PDF page ${c.getInt(3)}", -4100.0)
            }
        }.getOrNull()
    }

    private fun ipcIdentifierHit(id: String): SearchHit? {
        val sql = """
            SELECT ipc_item_id,COALESCE(catalog_ref,''),COALESCE(fin,''),part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'')
            FROM ipc_items
            WHERE UPPER(COALESCE(fin,''))=? OR UPPER(part_number)=?
            ORDER BY pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(normalizeToken(id), normalizeToken(id))).use { c ->
                if (!c.moveToFirst()) return@use null
                val ref = c.getString(2).ifBlank { c.getString(3) }
                SearchHit("part_number", c.getString(3), "IPC", ref, c.getString(4), c.getString(5).take(1200), -4000.0)
            }
        }.getOrNull()
    }

    private fun buildSummary(input: FaultInput, mel: LineMelResolution, tsm: LineTsmResolution?, profile: String): String {
        return buildString {
            append("LINE ASSISTANT • VERIFIED WORKFLOW\n\n")
            input.parsedFault?.let { append("PFR STRUCTURED RECORD\n").append(it.displaySummary()).append("\n\n") }

            append("1 • MEL / DISPATCH\n")
            append("Entry: ").append(mel.title).append('\n')
            append("Status: ").append(
                when (mel.status) {
                    LineDispatchStatus.NO_DISPATCH -> "NO DISPATCH"
                    LineDispatchStatus.MUST_BE_OPERATIVE -> "MUST BE OPERATIVE"
                    LineDispatchStatus.CONDITIONAL -> "CONDITIONAL — VERIFY ALL MEL CONDITIONS"
                    LineDispatchStatus.VERIFY_REQUIRED -> "VERIFY ORIGINAL MEL ENTRY"
                    LineDispatchStatus.NO_ENTRY -> "NO EXACT MEL ENTRY CONFIRMED"
                }
            ).append('\n')
            mel.page?.let { append("Original MEL PDF page: ").append(it).append('\n') }
            if (mel.itemRefs.isNotEmpty()) append("Referenced MEL item(s): ").append(mel.itemRefs.joinToString(", ")).append('\n')
            append(mel.note).append("\n\n")

            append("2 • TSM TROUBLESHOOTING\n")
            if (tsm == null) {
                append("Exact TSM Warnings/Malfunction index mapping NOT RESOLVED. Broad TSM lists are suppressed.\n")
            } else {
                append("Index match: ").append(tsm.maintenanceAta).append(" • ").append(tsm.maintenanceMessage).append('\n')
                append("Source/Class: ").append(tsm.source).append(" / ").append(tsm.faultClass).append('\n')
                append("Ref. TASK: ").append(tsm.indexTaskRef).append('\n')
                append("Verified task: ").append(tsm.taskRef).append(" — ").append(tsm.taskTitle).append('\n')
                append("Complete task range: PDF pages ").append(tsm.taskStartPage).append('-').append(tsm.taskEndPage).append('\n')
                append("Cockpit effect from TSM index: ").append(tsm.cockpitAta).append(" • ").append(tsm.cockpitMessage).append('\n')
            }

            append("\n3 • EFFECTIVITY\n")
            append(if (profile.isBlank()) "NOT VERIFIED — enter REG/MSN/effectivity before applying maintenance or dispatch action." else "Aircraft profile: $profile — verify each source applicability before use.")
            append("\n\nSOURCE MANUALS REMAIN AUTHORITATIVE.")
        }
    }

    private fun buildSafety(mel: LineMelResolution, tsm: LineTsmResolution?, profile: String): String = buildString {
        append("MEL STATUS: ").append(mel.status.name)
        append("\nTSM EXACT: ").append(tsm?.taskRef ?: "NOT RESOLVED")
        append("\nEFFECTIVITY: ").append(if (profile.isBlank()) "NOT VERIFIED" else profile)
        append("\nAI MAY EXPLAIN VERIFIED EVIDENCE ONLY; IT MUST NOT CHANGE MEL STATUS OR TSM TASK REFERENCE.")
    }

    private fun buildEvidencePacket(input: FaultInput, mel: LineMelResolution, tsm: LineTsmResolution?, hits: List<SearchHit>): String {
        return buildString {
            append("[E1 MEL EXACT BLOCK]\n")
            append(mel.exactBlock.ifBlank { mel.note }.take(3000)).append("\n\n")
            if (tsm != null) {
                append("[E2 TSM INDEX MAPPING]\n")
                append("Cockpit: ${tsm.cockpitAta} ${tsm.cockpitMessage}\n")
                append("Fault: ${tsm.maintenanceAta} ${tsm.maintenanceMessage}\n")
                append("Source: ${tsm.source} Class: ${tsm.faultClass} Identifiers: ${tsm.identifiers.joinToString(", ")}\n")
                append("Ref TASK: ${tsm.indexTaskRef} -> Verified ${tsm.taskRef}\n\n")
                append("[E3 VERIFIED TSM TASK ${tsm.taskRef}]\n")
                append(tsm.taskContent.take(8500)).append("\n\n")
            }
            hits.filter { it.manual !in setOf("MEL", "TSM") }.take(8).forEachIndexed { i, h ->
                append("[E${i + 4} ${h.manual} ${h.reference}]\n")
                append(h.title).append('\n').append(h.snippet.take(900)).append("\n\n")
            }
            append("[INPUT]\n").append(input.rawText.take(1200))
        }
    }

    private fun significantWords(text: String): List<String> {
        val stop = setOf("ATA", "REF", "MESSAGE", "TYPE", "SOURCE", "CLASS", "FAULT", "FAULTS", "THE", "OF", "A", "AN", "AND", "STATUS")
        return Regex("[A-Z0-9]+")
            .findAll(normalizeText(text))
            .map { it.value }
            .filter { it.length >= 2 && it !in stop && !it.matches(Regex("\\d{2,6}")) }
            .distinct()
            .toList()
    }

    private fun extractIdentifiers(text: String): List<String> {
        val upper = text.uppercase(Locale.US)
        val out = linkedSetOf<String>()
        Regex("\\b\\d{1,4}[A-Z]{1,4}\\d{0,4}\\b").findAll(upper).forEach { out += it.value }
        Regex("\\b(?:LGCIU\\d|BMC\\d(?:-\\d)?)\\b").findAll(upper).forEach { out += it.value }
        return out.filterNot { it.matches(Regex("\\d+")) }.take(24)
    }

    private fun baseFaultMessage(text: String): String {
        return normalizeText(text)
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeText(text: String): String = text
        .uppercase(Locale.US)
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanLine(text: String): String = normalizeText(text)
    private fun normalizeToken(text: String): String = normalizeText(text).replace(" ", "")
    private fun normalizeAta(text: String): String = text.filter(Char::isDigit).take(6)

    private fun containsWholeToken(haystack: String, token: String): Boolean {
        if (token.isBlank()) return false
        val h = normalizeText(haystack)
        val t = Regex.escape(normalizeText(token))
        return Regex("(?<![A-Z0-9])$t(?![A-Z0-9])").containsMatchIn(h)
    }

    override fun close() = db.close()
}
