package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

/**
 * Verified technical resolver for structured PFR/tech-log faults.
 *
 * Important rule: maintenance-message ATA is context, not necessarily the TSM task ATA.
 * Example: a 49-62-16 message may resolve to a 49-62-00-810-xxx TSM task.
 * Therefore candidate discovery is performed inside the ATA family (XX-YY), then the
 * selected task is verified against real task_entries before it can be used.
 */
class SmartTechnicalResolver(
    dbFile: File,
    private val resolver: SearchRepository
) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private data class TaskRow(
        val taskId: String,
        val documentId: String,
        val filename: String,
        val startPage: Int,
        val reference: String,
        val title: String
    )

    private data class ScoredTask(val row: TaskRow, val score: Int)
    private data class Support(val amm: List<String>, val asm: List<String>, val identifiers: List<String>)

    @Volatile private var lastQuery: String = ""
    @Volatile private var lastCandidates: List<SearchHit> = emptyList()

    fun candidateTasks(query: String): List<SearchHit> {
        if (query == lastQuery && lastCandidates.isNotEmpty()) return lastCandidates
        val phrase = faultPhrase(query)
        val ataFull = fullAtaFromQuery(query) ?: return emptyList()
        val candidates = discoverCandidates(phrase, ataFull).take(8).map { taskHit(it.row, it.score) }
        lastQuery = query
        lastCandidates = candidates
        return candidates
    }

    fun enhance(base: LineMaintenanceResult, query: String, profile: String): LineMaintenanceResult {
        val phrase = faultPhrase(query)
        val ataFull = fullAtaFromQuery(query) ?: return base
        if (phrase.length < 3) return base

        val mel = strictMelHits(phrase, ataFull, 4)
        val scored = discoverCandidates(phrase, ataFull)
        lastQuery = query
        lastCandidates = scored.take(8).map { taskHit(it.row, it.score) }

        val selected = chooseDeterministic(scored)
        if (selected == null) {
            val orderedMel = mel.map { it.copy(title = "1 MEL CHECK • ${cleanStageTitle(it.title)}") }
            val family = ataFamily(ataFull)
            val note = buildString {
                append("\n\nVERIFIED TECHNICAL RESOLUTION")
                append("\nMaintenance message: ").append(phrase)
                append("\nMaintenance ATA: ").append(ataFull)
                append("\nTSM search family: ").append(family).append("-xx")
                append("\nExact task not yet deterministic.")
                if (scored.isEmpty()) append(" No verified TSM task candidate was found.")
                else append(" ${scored.size.coerceAtMost(8)} verified TSM candidate(s) are available for AI ranking/database verification.")
                append("\nBroad TSM/AMM result lists are suppressed.")
            }
            return base.copy(
                hits = orderedMel,
                melHits = mel,
                dispatchStatus = if (mel.isEmpty()) MelDispatchStatus.NO_MATCH else MelDispatchStatus.VERIFY_REQUIRED,
                workflowSummary = base.workflowSummary + note
            )
        }
        return buildResolved(base, query, profile, mel, selected.row, selected.score, "DETERMINISTIC VERIFIED")
    }

    /** Accepts only a reference that already exists in the verified candidate set. */
    fun applyVerifiedCandidate(
        base: LineMaintenanceResult,
        query: String,
        profile: String,
        selectedReference: String
    ): LineMaintenanceResult? {
        val phrase = faultPhrase(query)
        val ataFull = fullAtaFromQuery(query) ?: return null
        val candidates = discoverCandidates(phrase, ataFull).take(12)
        val selected = candidates.firstOrNull { it.row.reference.equals(selectedReference.trim(), true) } ?: return null
        val mel = strictMelHits(phrase, ataFull, 4)
        return buildResolved(base, query, profile, mel, selected.row, selected.score, "AI RANKED + DATABASE VERIFIED")
    }

    private fun buildResolved(
        base: LineMaintenanceResult,
        query: String,
        profile: String,
        mel: List<SearchHit>,
        row: TaskRow,
        evidenceScore: Int,
        resolutionMode: String
    ): LineMaintenanceResult {
        val primary = taskHit(row, evidenceScore)
        val support = support(row)
        val amm = referenceHits("AMM", support.amm, 10)
        val asm = referenceHits("ASM", support.asm, 6)
        val awm = identifierHits("AWM", support.identifiers, 5)
        val ipc = identifierHits("IPC", support.identifiers, 5)
        val espm = if (base.includeEspm) identifierHits("ESPM", support.identifiers, 4) else emptyList()

        val ordered = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(stage: String, list: List<SearchHit>) {
            list.forEach { hit ->
                val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
                if (seen.add(key)) ordered += hit.copy(title = "$stage • ${cleanStageTitle(hit.title)}")
            }
        }
        add("1 MEL CHECK", mel)
        add("2 TSM EXACT TASK", listOf(primary))
        add("3 AMM REFERENCED TASK", amm)
        add("4 ASM REFERENCED SCHEMATIC", asm)
        add("5 AWM BY TSM IDENTIFIER", awm)
        add("6 IPC BY TSM IDENTIFIER", ipc)
        if (base.includeEspm) add("7 ESPM STANDARD PRACTICE", espm)

        val note = buildString {
            append("\n\nVERIFIED TECHNICAL RESOLUTION")
            append("\nMode: ").append(resolutionMode)
            append("\nMaintenance message: ").append(faultPhrase(query))
            fullAtaFromQuery(query)?.let {
                append("\nMaintenance ATA: ").append(it)
                append("\nTSM family searched: ").append(ataFamily(it)).append("-xx")
            }
            append("\nPrimary TSM: ").append(row.reference).append(" — ").append(row.title)
            append("\nTSM task reference was verified in task_entries before acceptance.")
            append("\nViewer opens the complete task range through END OF TASK/next task boundary.")
            if (support.amm.isNotEmpty()) append("\nReferenced AMM: ").append(support.amm.joinToString(", "))
            if (support.asm.isNotEmpty()) append("\nReferenced ASM: ").append(support.asm.joinToString(", "))
            if (support.identifiers.isNotEmpty()) append("\nTask identifiers: ").append(support.identifiers.take(18).joinToString(", "))
            if (profile.isBlank()) append("\nEffectivity remains NOT VERIFIED until REG/MSN/effectivity is supplied.")
        }

        return base.copy(
            hits = ordered,
            melHits = mel,
            dispatchStatus = if (mel.isEmpty()) MelDispatchStatus.NO_MATCH else MelDispatchStatus.VERIFY_REQUIRED,
            workflowSummary = base.workflowSummary + note
        )
    }

    private fun discoverCandidates(phrase: String, ataFull: String): List<ScoredTask> {
        val family = ataFamily(ataFull)
        val ids = identifierTokens(phrase)
        val groups = semanticGroups(phrase)
        val indexRefs = faultSymptomTaskRefs(phrase, ataFull, family)
        val indexed = indexRefs.mapNotNull(::taskRowByReference)

        val rows = linkedMapOf<String, TaskRow>()
        indexed.forEach { rows[it.taskId] = it }
        taskRowsForFamily(family).forEach { rows.putIfAbsent(it.taskId, it) }

        return rows.values.map { row ->
            val text = taskRangeContent(row)
            val n = normalize("${row.reference} ${row.title} $text")
            var score = 0
            if (indexRefs.any { it.equals(row.reference, true) }) score += 120
            if (row.reference.startsWith(ataFull, true)) score += 25
            if (row.reference.startsWith(family, true)) score += 20
            ids.forEach { if (containsWholeToken(n, it)) score += 55 }
            groups.forEach { group -> if (group.any { containsWholeToken(n, it) }) score += 16 }
            if (n.contains(normalize(phrase))) score += 75
            ScoredTask(row, score)
        }.filter { st ->
            val min = if (ids.isNotEmpty()) 55 else 32
            st.score >= min
        }.sortedByDescending { it.score }
    }

    private fun chooseDeterministic(scored: List<ScoredTask>): ScoredTask? {
        val best = scored.firstOrNull() ?: return null
        if (scored.size == 1) return if (best.score >= 55) best else null
        val second = scored[1]
        val margin = best.score - second.score
        return when {
            best.score >= 150 && margin >= 8 -> best
            best.score >= 100 && margin >= 18 -> best
            else -> null
        }
    }

    private fun faultSymptomTaskRefs(phrase: String, ataFull: String, family: String): List<String> {
        val terms = linkedSetOf<String>()
        identifierTokens(phrase).forEach(terms::add)
        semanticGroups(phrase).flatten().forEach(terms::add)
        Regex("[A-Z0-9]+").findAll(phrase.uppercase()).map { it.value }
            .filter { it.length >= 3 }.forEach(terms::add)
        if (terms.isEmpty()) return emptyList()

        val match = terms.take(18).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',80),bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ? AND UPPER(manual)='TSM'
            ORDER BY bm25(global_search_fts) LIMIT 260
        """.trimIndent()
        val refs = linkedSetOf<String>()
        val ids = identifierTokens(phrase)
        val groups = semanticGroups(phrase)
        val refRegex = Regex("\\b(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-[A-Z](?:\\d{2})?)\\b")

        runCatching {
            db.rawQuery(sql, arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    val hit = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                    val src = runCatching { resolver.resolve(hit) }.getOrNull()
                    val full = listOf(hit.reference, hit.title, hit.snippet, src?.content.orEmpty()).joinToString("\n")
                    val n = normalize(full)
                    val hasContextAta = full.uppercase().contains(ataFull) || full.uppercase().contains(family)
                    if (!hasContextAta) continue
                    val idMatch = ids.any { containsWholeToken(n, it) }
                    val semanticCount = groups.count { g -> g.any { containsWholeToken(n, it) } }
                    if (ids.isNotEmpty() && !idMatch) continue
                    if (ids.isEmpty() && semanticCount < 2) continue
                    refRegex.findAll(full.uppercase()).forEach { m ->
                        val ref = m.groupValues[1]
                        if (ref.startsWith(family, true)) refs += ref
                    }
                }
            }
        }
        return refs.toList()
    }

    private fun taskRowsForFamily(family: String): List<TaskRow> {
        val out = mutableListOf<TaskRow>()
        val sql = """
            SELECT CAST(t.task_id AS TEXT),CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page,
                   COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))='TSM' AND UPPER(t.task_ref) LIKE ?
            ORDER BY t.start_pdf_page LIMIT 240
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf("${family.uppercase()}-%-810-%")).use { c ->
                while (c.moveToNext()) out += TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
            }
        }
        return out
    }

    private fun taskRowByReference(ref: String): TaskRow? {
        val sql = """
            SELECT CAST(t.task_id AS TEXT),CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page,
                   COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))='TSM' AND UPPER(t.task_ref)=?
            ORDER BY t.start_pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(ref.uppercase())).use { c ->
                if (!c.moveToFirst()) return@use null
                TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
            }
        }.getOrNull()
    }

    private fun taskHit(row: TaskRow, score: Int): SearchHit {
        val text = taskRangeContent(row).replace(Regex("\\s+"), " ").trim()
        return SearchHit("task", row.taskId, "TSM", row.reference, row.title, text.take(850), -2000.0 - score)
    }

    private fun taskRangeContent(row: TaskRow): String {
        val end = taskEndPage(row.documentId, row.filename, row.startPage)
        val out = StringBuilder()
        runCatching {
            db.rawQuery(
                "SELECT content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_id",
                arrayOf(row.filename, row.startPage.toString(), end.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    if (out.length > 140000) break
                    out.append(c.getString(0)).append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun taskEndPage(documentId: String, filename: String, start: Int): Int {
        var next: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT MIN(start_pdf_page) FROM task_entries WHERE CAST(document_id AS TEXT)=? AND start_pdf_page>?",
                arrayOf(documentId, start.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) next = c.getInt(0) }
        }
        val max = minOf(next?.minus(1) ?: start + 28, start + 28).coerceAtLeast(start)
        var marker: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT pdf_page,content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_id",
                arrayOf(filename, start.toString(), max.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(1).uppercase()
                    if (text.contains("END OF TASK") || text.contains("END TASK")) { marker = c.getInt(0); break }
                }
            }
        }
        return (marker ?: max).coerceAtLeast(start)
    }

    private fun support(row: TaskRow): Support {
        val text = taskRangeContent(row).uppercase()
        val amm = Regex("\\bAMM(?:\\s+TASK)?\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z](?:\\d{2})?)?)\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(24).toList()
        val asm = Regex("\\bASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()
        val ids = Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
            .findAll(text).map { it.value }
            .filterNot { it in setOf("AMM", "ASM", "TSM", "BITE", "A340", "TASK") }
            .filterNot { it.matches(Regex("\\d+")) }.distinct().take(48).toList()
        return Support(amm, asm, ids)
    }

    private fun referenceHits(manual: String, refs: List<String>, limit: Int): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        refs.forEach { ref ->
            runCatching {
                val sql = """
                    SELECT CAST(t.task_id AS TEXT),COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
                    FROM task_entries t JOIN documents d ON d.document_id=t.document_id
                    WHERE UPPER(COALESCE(d.manual,''))=? AND UPPER(t.task_ref) LIKE ?
                    ORDER BY t.start_pdf_page LIMIT 8
                """.trimIndent()
                db.rawQuery(sql, arrayOf(manual.uppercase(), "${ref.uppercase()}%")).use { c ->
                    while (c.moveToNext()) {
                        val key = "task|${c.getString(0)}"
                        if (seen.add(key)) out += SearchHit("task", c.getString(0), manual, c.getString(1), c.getString(2), "Referenced by verified TSM task", -1600.0)
                    }
                }
            }
            if (out.size >= limit) return@forEach
            runCatching {
                val sql = """
                    SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                           snippet(global_search_fts,5,'[',']','…',40),bm25(global_search_fts)
                    FROM global_search_fts WHERE UPPER(manual)=? AND UPPER(reference) LIKE ?
                    ORDER BY bm25(global_search_fts) LIMIT 8
                """.trimIndent()
                db.rawQuery(sql, arrayOf(manual.uppercase(), "%${ref.uppercase()}%")).use { c ->
                    while (c.moveToNext()) {
                        val h = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                        val key = "${h.entityType}|${h.entityId}|${h.reference}"
                        if (seen.add(key)) out += h
                    }
                }
            }
        }
        return out.take(limit)
    }

    private fun identifierHits(manual: String, identifiers: List<String>, limit: Int): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        identifiers.filter { it.length in 3..16 }.take(16).forEach { id ->
            val sql = """
                SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                       snippet(global_search_fts,5,'[',']','…',36),bm25(global_search_fts)
                FROM global_search_fts WHERE global_search_fts MATCH ? AND UPPER(manual)=?
                ORDER BY bm25(global_search_fts) LIMIT 8
            """.trimIndent()
            runCatching {
                db.rawQuery(sql, arrayOf("\"${id.replace("\"", "")}\"", manual.uppercase())).use { c ->
                    while (c.moveToNext()) {
                        val h = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                        val key = "${h.manual}|${h.reference}|${h.entityId}"
                        if (seen.add(key)) out += h
                    }
                }
            }
        }
        return out.take(limit)
    }

    private fun strictMelHits(phrase: String, ataFull: String, limit: Int): List<SearchHit> {
        val chapter = ataFull.take(2)
        val terms = melTerms(chapter, phrase)
        val out = mutableListOf<Pair<SearchHit, Int>>()
        runCatching {
            db.rawQuery(
                "SELECT CAST(mel_item_id AS TEXT),COALESCE(mel_ref,''),COALESCE(title,''),COALESCE(content,'') FROM mel_items WHERE UPPER(COALESCE(mel_ref,'')) LIKE ? LIMIT 320",
                arrayOf("${chapter.uppercase()}-%")
            ).use { c ->
                while (c.moveToNext()) {
                    val score = melScore(c.getString(1), c.getString(2), c.getString(3), chapter, terms)
                    if (score > 0) out += SearchHit("mel_item", "MEL-I-${c.getString(0)}", "MEL", c.getString(1), c.getString(2), c.getString(3).take(700), -score.toDouble()) to score
                }
            }
        }
        runCatching {
            db.rawQuery(
                "SELECT CAST(mel_entry_id AS TEXT),COALESCE(ata_chapter,''),COALESCE(title,''),COALESCE(content,'') FROM mel_entries WHERE UPPER(COALESCE(ata_chapter,'')) LIKE ? LIMIT 320",
                arrayOf("${chapter.uppercase()}%")
            ).use { c ->
                while (c.moveToNext()) {
                    val score = melScore(c.getString(1), c.getString(2), c.getString(3), chapter, terms)
                    if (score > 0) out += SearchHit("mel_entry", "MEL-E-${c.getString(0)}", "MEL", c.getString(1), c.getString(2), c.getString(3).take(700), -score.toDouble()) to score
                }
            }
        }
        val seen = linkedSetOf<String>()
        return out.sortedByDescending { it.second }.map { it.first }
            .filter { seen.add("${it.entityType}|${it.entityId}|${it.reference}") }.take(limit)
    }

    private fun melScore(ref: String, title: String, content: String, chapter: String, terms: List<String>): Int {
        val n = normalize("$ref $title $content")
        var score = if (ref.startsWith(chapter, true)) 20 else 0
        terms.forEach { if (containsWholeToken(n, it)) score += 12 }
        if (chapter == "49" && (ref.startsWith("49-10-01", true) || n.contains("POWER PLANT APU"))) score += 120
        if (chapter == "32") {
            if (n.contains("LANDING GEAR")) score += 40
            if (n.contains("INDICATION") || n.contains("POSITION")) score += 30
            if (n.contains("PROXIMITY") || n.contains("SENSOR")) score += 25
        }
        return score
    }

    private fun melTerms(chapter: String, phrase: String): List<String> {
        val terms = mutableListOf<String>()
        val base = mapOf(
            "21" to listOf("AIR", "CONDITIONING", "PACK"), "24" to listOf("ELECTRICAL"),
            "26" to listOf("FIRE", "SMOKE"), "27" to listOf("FLIGHT", "CONTROL"),
            "28" to listOf("FUEL"), "29" to listOf("HYDRAULIC"), "30" to listOf("ANTI", "ICE"),
            "32" to listOf("LANDING", "GEAR", "INDICATION", "POSITION"),
            "36" to listOf("PNEUMATIC", "BLEED"), "38" to listOf("WATER", "WASTE"),
            "49" to listOf("APU", "POWER", "PLANT"), "52" to listOf("DOOR")
        )
        terms += base[chapter].orEmpty()
        val p = phrase.uppercase()
        if (p.contains("PROX") || p.contains("SNSR") || p.contains("SENSOR")) terms += listOf("PROXIMITY", "SENSOR", "POSITION", "INDICATION")
        if (p.contains("PACK")) terms += "PACK"
        if (p.contains("APU")) terms += "APU"
        return terms.distinct()
    }

    private fun identifierTokens(phrase: String): List<String> =
        Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
            .findAll(phrase.uppercase()).map { it.value }.distinct().take(16).toList()

    private fun semanticGroups(phrase: String): List<Set<String>> {
        val tokens = Regex("[A-Z0-9]+").findAll(phrase.uppercase()).map { it.value }.filter { it.length >= 3 }.toSet()
        val groups = mutableListOf<Set<String>>()
        if (tokens.any { it.startsWith("PROX") }) groups += setOf("PROX", "PROXIMITY")
        if (tokens.any { it in setOf("SNSR", "SNSRS", "SENSOR", "SENSORS") }) groups += setOf("SNSR", "SNSRS", "SENSOR", "SENSORS", "SENSING")
        tokens.filterNot { it.matches(Regex("\\d.*[A-Z].*")) || it in setOf("FAULT", "MESSAGE", "MAINTENANCE") }
            .forEach { groups += setOf(it) }
        return groups.distinct()
    }

    private fun faultPhrase(query: String): String {
        var s = query.uppercase().replace('_', ' ')
        s = s.replace(Regex("\\bATA\\s*\\d{2}(?:[- ]?\\d{2})?(?:[- ]?\\d{2})?\\b.*$"), " ")
        s = s.replace(Regex("\\b(UTC|CLASS|HARD|INTERMITTENT|SOURCE|IDENTIFIERS?|FLIGHT PHASE)\\b.*$"), " ")
        return s.replace(Regex("\\s+"), " ").trim(' ', ':', ';', '-')
    }

    private fun fullAtaFromQuery(query: String): String? {
        val m = Regex("\\bATA\\s*(\\d{2})[- ]?(\\d{2})[- ]?(\\d{2})\\b", RegexOption.IGNORE_CASE).find(query) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
    }

    private fun ataFamily(ataFull: String): String = ataFull.split('-').take(2).joinToString("-")

    private fun cleanStageTitle(title: String): String =
        if (title.contains(" • ") && title.substringBefore(" • ").trim().firstOrNull()?.isDigit() == true) title.substringAfter(" • ").trim() else title.trim()

    private fun normalize(s: String): String = s.uppercase().replace('_', ' ')
        .replace(Regex("[^A-Z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun containsWholeToken(text: String, token: String): Boolean {
        val t = normalize(token)
        return Regex("(^| )${Regex.escape(t)}( |$)").containsMatchIn(text)
    }

    override fun close() = db.close()
}
