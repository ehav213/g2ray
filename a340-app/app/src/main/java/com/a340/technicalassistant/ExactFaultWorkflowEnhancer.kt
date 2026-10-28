package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

/**
 * Strict PFR fault resolver.
 *
 * A structured PFR maintenance message is resolved in this order:
 * 1) MEL candidates from the affected ATA/equipment function.
 * 2) TSM fault-symptom/index evidence using the exact maintenance ATA and identifiers.
 * 3) Exact TSM 810 task. If an index chunk points to a task, the task entity is used.
 *    If the index link cannot be extracted, tasks inside the exact six-digit ATA are
 *    scored against the complete task text (start page through END OF TASK/next task).
 * 4) AMM/ASM references and AWM/IPC identifiers are extracted from that complete TSM task.
 *
 * Broad TSM results are never shown for a structured PFR fault when an exact task cannot
 * be established with sufficient evidence.
 */
class ExactFaultWorkflowEnhancer(
    dbFile: File,
    private val resolver: SearchRepository
) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private data class Support(
        val amm: List<String>,
        val asm: List<String>,
        val identifiers: List<String>
    )

    private data class TaskRow(
        val taskId: String,
        val documentId: String,
        val filename: String,
        val startPage: Int,
        val reference: String,
        val title: String
    )

    fun enhance(base: LineMaintenanceResult, query: String, profile: String): LineMaintenanceResult {
        val phrase = faultPhrase(query)
        if (phrase.length < 4) return base
        val ataFull = fullAtaFromQuery(query)
        val strictPfr = ataFull != null

        val strictMel = if (strictPfr) strictMelHits(phrase, ataFull!!, 4) else base.melHits
        val exactTsm = exactTsmHits(phrase, ataFull, 1)

        if (exactTsm.isEmpty()) {
            if (!strictPfr) return base
            val orderedMel = strictMel.map { it.copy(title = "1 MEL CHECK • ${cleanStageTitle(it.title)}") }
            val note = buildString {
                append("\n\nEXACT FAULT RESOLUTION")
                append("\nMaintenance message: ").append(phrase)
                append("\nMaintenance ATA: ").append(ataFull)
                if (strictMel.isEmpty()) append("\nMEL: no ATA/equipment candidate resolved — verify original operator MEL.")
                else append("\nMEL: ATA/equipment candidate(s) found — original MEL/effectivity must be verified.")
                append("\nEXACT TSM TASK NOT RESOLVED — broad TSM/AMM alternatives were suppressed.")
                append("\nNo troubleshooting task should be applied until the exact TSM fault-symptom/index link is resolved.")
            }
            return base.copy(
                hits = orderedMel,
                melHits = strictMel,
                dispatchStatus = if (strictMel.isEmpty()) MelDispatchStatus.NO_MATCH else MelDispatchStatus.VERIFY_REQUIRED,
                workflowSummary = base.workflowSummary + note
            )
        }

        val primary = exactTsm.first()
        val support = support(primary)
        val amm = referenceHits("AMM", support.amm, 10)
        val asm = referenceHits("ASM", support.asm, 5)
        val awm = identifierHits("AWM", support.identifiers, 5)
        val ipc = identifierHits("IPC", support.identifiers, 5)
        val espm = if (base.includeEspm) identifierHits("ESPM", support.identifiers, 4) else emptyList()

        val ordered = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(stage: String, items: List<SearchHit>) {
            items.forEach { h ->
                val key = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
                if (seen.add(key)) ordered += h.copy(title = "$stage • ${cleanStageTitle(h.title)}")
            }
        }

        add("1 MEL CHECK", strictMel)
        add("2 TSM EXACT TASK", exactTsm)
        add("3 AMM REFERENCED TASK", amm)
        add("4 ASM REFERENCED SCHEMATIC", asm)
        add("5 AWM BY TSM IDENTIFIER", awm)
        add("6 IPC BY TSM IDENTIFIER", ipc)
        if (base.includeEspm) add("7 ESPM STANDARD PRACTICE", espm)

        val exactNote = buildString {
            append("\n\nEXACT FAULT RESOLUTION")
            append("\nMaintenance message: ").append(phrase)
            ataFull?.let { append("\nMaintenance ATA: ").append(it) }
            append("\nPrimary TSM: ").append(primary.reference.ifBlank { "reference not indexed" })
            append(" — ").append(cleanStageTitle(primary.title))
            append("\nTSM viewer opens the complete task range, not a single middle page.")
            if (support.amm.isNotEmpty()) append("\nReferenced AMM: ").append(support.amm.joinToString(", "))
            if (support.asm.isNotEmpty()) append("\nReferenced ASM: ").append(support.asm.joinToString(", "))
            if (support.identifiers.isNotEmpty()) append("\nTSM identifiers: ").append(support.identifiers.take(16).joinToString(", "))
            append("\nBroad TSM alternatives are suppressed for structured PFR faults.")
        }

        return base.copy(
            hits = ordered,
            melHits = strictMel,
            dispatchStatus = if (strictMel.isEmpty()) MelDispatchStatus.NO_MATCH else MelDispatchStatus.VERIFY_REQUIRED,
            workflowSummary = base.workflowSummary + exactNote
        )
    }

    private fun exactTsmHits(phrase: String, ataFull: String?, limit: Int): List<SearchHit> {
        if (ataFull == null) return emptyList()

        // First use fault-symptom/index chunks. Airbus PFR messages often use abbreviations
        // (PROX/SNSRS), while the TSM task may spell them out (PROXIMITY/SENSORS).
        val indexRefs = faultSymptomTaskRefs(phrase, ataFull)
        val linkedTasks = indexRefs.mapNotNull(::taskHitByReference)
        if (linkedTasks.isNotEmpty()) {
            return linkedTasks
                .map { Triple(it, taskScore(it, phrase, ataFull), it.score) }
                .sortedWith(compareBy<Triple<SearchHit, Int, Double>> { it.second }.thenBy { it.third })
                .map { it.first }
                .take(limit)
        }

        // Fallback remains strict: evaluate only TSM tasks inside the exact six-digit ATA.
        // This is not a broad TSM search.
        val ataTasks = taskRowsForAta(ataFull)
        if (ataTasks.isEmpty()) return emptyList()
        val ids = identifierTokens(phrase)
        val semanticGroups = semanticGroups(phrase)

        val scored = ataTasks.map { row ->
            val text = taskRangeContent(row)
            val normalized = normalize("${row.reference} ${row.title} $text")
            val idMatches = ids.count { containsWholeToken(normalized, it) }
            val semanticMatches = semanticGroups.count { group -> group.any { containsWholeToken(normalized, it) } }
            val phraseBonus = if (normalized.contains(normalize(phrase))) 60 else 0
            val score = idMatches * 45 + semanticMatches * 12 + phraseBonus
            row to score
        }.filter { (row, score) ->
            val hasIdEvidence = ids.isNotEmpty() && score >= 45
            val hasSemanticEvidence = ids.isEmpty() && score >= 24
            hasIdEvidence || hasSemanticEvidence || (ataTasks.size == 1 && score > 0)
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return emptyList()
        // Do not choose between near-tied tasks when the evidence does not discriminate.
        if (scored.size > 1 && best.second - scored[1].second < 8 && best.second < 70) return emptyList()

        return listOf(taskHit(best.first, best.second)).take(limit)
    }

    private fun faultSymptomTaskRefs(phrase: String, ataFull: String): List<String> {
        val searchTerms = linkedSetOf<String>()
        identifierTokens(phrase).forEach(searchTerms::add)
        semanticGroups(phrase).flatten().forEach(searchTerms::add)
        Regex("[A-Z0-9]+")
            .findAll(phrase.uppercase())
            .map { it.value }
            .filter { it.length >= 3 }
            .forEach(searchTerms::add)
        if (searchTerms.isEmpty()) return emptyList()

        val match = searchTerms.take(18).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',70), bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ? AND UPPER(manual)='TSM'
            ORDER BY bm25(global_search_fts)
            LIMIT 220
        """.trimIndent()

        val ids = identifierTokens(phrase)
        val groups = semanticGroups(phrase)
        val refs = linkedSetOf<String>()
        val taskRefRegex = Regex("\\b(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-[A-Z](?:\\d{2})?)\\b")

        runCatching {
            db.rawQuery(sql, arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    val h = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                    val src = runCatching { resolver.resolve(h) }.getOrNull()
                    val full = listOf(h.reference, h.title, h.snippet, src?.content.orEmpty()).joinToString("\n")
                    val n = normalize(full)
                    val ataPresent = full.uppercase().contains(ataFull) || h.reference.startsWith(ataFull, true)
                    if (!ataPresent) continue
                    val idMatches = ids.count { containsWholeToken(n, it) }
                    val semanticMatches = groups.count { group -> group.any { containsWholeToken(n, it) } }
                    val enough = if (ids.isNotEmpty()) idMatches > 0 else semanticMatches >= 2
                    if (!enough) continue
                    taskRefRegex.findAll(full.uppercase()).forEach { m ->
                        val ref = m.groupValues[1]
                        if (ref.startsWith(ataFull)) refs += ref
                    }
                }
            }
        }
        return refs.toList()
    }

    private fun taskRowsForAta(ataFull: String): List<TaskRow> {
        val out = mutableListOf<TaskRow>()
        val sql = """
            SELECT CAST(t.task_id AS TEXT),CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page,
                   COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
            FROM task_entries t
            JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))='TSM' AND UPPER(t.task_ref) LIKE ?
            ORDER BY t.start_pdf_page
            LIMIT 80
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf("${ataFull.uppercase()}-810-%")).use { c ->
                while (c.moveToNext()) out += TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
            }
        }
        return out
    }

    private fun taskHitByReference(ref: String): SearchHit? {
        val sql = """
            SELECT CAST(t.task_id AS TEXT),CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page,
                   COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
            FROM task_entries t
            JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))='TSM' AND UPPER(t.task_ref)=?
            ORDER BY t.start_pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(ref.uppercase())).use { c ->
                if (!c.moveToFirst()) return@use null
                val row = TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
                taskHit(row, 1000)
            }
        }.getOrNull()
    }

    private fun taskHit(row: TaskRow, evidenceScore: Int): SearchHit {
        val content = taskRangeContent(row).replace(Regex("\\s+"), " ").trim()
        return SearchHit(
            entityType = "task",
            entityId = row.taskId,
            manual = "TSM",
            reference = row.reference,
            title = row.title,
            snippet = content.take(700),
            score = -2000.0 - evidenceScore
        )
    }

    private fun taskRangeContent(row: TaskRow): String {
        val end = taskEndPage(row.documentId, row.filename, row.startPage)
        val out = StringBuilder()
        val sql = """
            SELECT content FROM chunks
            WHERE document_filename=? AND pdf_page BETWEEN ? AND ?
            ORDER BY pdf_page,chunk_id
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf(row.filename, row.startPage.toString(), end.toString())).use { c ->
                while (c.moveToNext()) {
                    if (out.length > 120000) break
                    out.append(c.getString(0)).append('\n')
                }
            }
        }
        return out.toString()
    }

    private fun taskEndPage(documentId: String, filename: String, start: Int): Int {
        var nextStart: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT MIN(start_pdf_page) FROM task_entries WHERE CAST(document_id AS TEXT)=? AND start_pdf_page>?",
                arrayOf(documentId, start.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) nextStart = c.getInt(0) }
        }
        val hardMax = minOf((nextStart?.minus(1) ?: (start + 24)), start + 24).coerceAtLeast(start)
        var endMarker: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT pdf_page,content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_id",
                arrayOf(filename, start.toString(), hardMax.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(1).uppercase()
                    if (text.contains("END OF TASK") || text.contains("END TASK")) {
                        endMarker = c.getInt(0); break
                    }
                }
            }
        }
        return (endMarker ?: hardMax).coerceAtLeast(start)
    }

    private fun taskScore(hit: SearchHit, phrase: String, ataFull: String): Int {
        val n = normalize("${hit.reference} ${hit.title} ${hit.snippet}")
        var score = 0
        if (hit.reference.startsWith(ataFull, true)) score += 40
        identifierTokens(phrase).forEach { if (containsWholeToken(n, it)) score += 35 }
        semanticGroups(phrase).forEach { group -> if (group.any { containsWholeToken(n, it) }) score += 10 }
        if (n.contains(normalize(phrase))) score += 50
        return score
    }

    private fun support(primary: SearchHit): Support {
        val row = taskRow(primary)
        val text = if (row != null) taskRangeContent(row).uppercase()
        else listOf(primary.title, primary.snippet, runCatching { resolver.resolve(primary)?.content }.getOrNull().orEmpty()).joinToString("\n").uppercase()

        val amm = Regex("\\bAMM(?:\\s+TASK)?\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3}(?:-[A-Z](?:\\d{2})?)?)\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(24).toList()
        val asm = Regex("\\bASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(10).toList()
        val ids = Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
            .findAll(text).map { it.value }
            .filterNot { it in setOf("AMM", "ASM", "TSM", "BITE", "A340", "TASK") }
            .filterNot { it.matches(Regex("\\d+")) }
            .distinct().take(40).toList()
        return Support(amm, asm, ids)
    }

    private fun taskRow(hit: SearchHit): TaskRow? {
        if (!hit.entityType.equals("task", true)) return null
        val sql = """
            SELECT CAST(t.task_id AS TEXT),CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page,
                   COALESCE(t.task_ref,''),COALESCE(t.task_title,'')
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE CAST(t.task_id AS TEXT)=? LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(hit.entityId)).use { c ->
                if (!c.moveToFirst()) return@use null
                TaskRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5))
            }
        }.getOrNull()
    }

    private fun strictMelHits(phrase: String, ataFull: String, limit: Int): List<SearchHit> {
        val chapter = ataFull.take(2)
        val terms = melTerms(chapter, phrase)
        val out = mutableListOf<Pair<SearchHit, Int>>()

        runCatching {
            val sql = """
                SELECT CAST(m.mel_item_id AS TEXT),COALESCE(m.mel_ref,''),COALESCE(m.title,''),COALESCE(m.content,'')
                FROM mel_items m
                WHERE UPPER(COALESCE(m.mel_ref,'')) LIKE ?
                LIMIT 240
            """.trimIndent()
            db.rawQuery(sql, arrayOf("${chapter.uppercase()}-%")).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val ref = c.getString(1); val title = c.getString(2); val content = c.getString(3)
                    val score = melScore(ref, title, content, chapter, terms)
                    if (score > 0) out += SearchHit("mel_item", "MEL-I-$id", "MEL", ref, title, content.take(600), -score.toDouble()) to score
                }
            }
        }

        runCatching {
            val sql = """
                SELECT CAST(m.mel_entry_id AS TEXT),COALESCE(m.ata_chapter,''),COALESCE(m.title,''),COALESCE(m.content,'')
                FROM mel_entries m
                WHERE UPPER(COALESCE(m.ata_chapter,'')) LIKE ?
                LIMIT 240
            """.trimIndent()
            db.rawQuery(sql, arrayOf("${chapter.uppercase()}%")).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val ref = c.getString(1); val title = c.getString(2); val content = c.getString(3)
                    val score = melScore(ref, title, content, chapter, terms)
                    if (score > 0) out += SearchHit("mel_entry", "MEL-E-$id", "MEL", ref, title, content.take(600), -score.toDouble()) to score
                }
            }
        }

        val seen = linkedSetOf<String>()
        return out.sortedByDescending { it.second }.map { it.first }.filter {
            seen.add("${it.entityType}|${it.entityId}|${it.reference}")
        }.take(limit)
    }

    private fun melScore(ref: String, title: String, content: String, chapter: String, terms: List<String>): Int {
        val n = normalize("$ref $title $content")
        var score = if (ref.startsWith(chapter, true)) 25 else 0
        terms.forEach { term -> if (containsWholeToken(n, term)) score += 10 }
        if (chapter == "49" && (ref.startsWith("49-10-01", true) || n.contains("POWER PLANT APU"))) score += 100
        if (chapter == "32" && (n.contains("LANDING GEAR") || n.contains("GEAR INDICATION") || n.contains("GEAR POSITION"))) score += 35
        return score
    }

    private fun melTerms(chapter: String, phrase: String): List<String> {
        val terms = mutableListOf<String>()
        val chapterTerms = mapOf(
            "21" to listOf("AIR", "CONDITIONING", "PACK"), "22" to listOf("AUTO", "FLIGHT"),
            "23" to listOf("COMMUNICATION"), "24" to listOf("ELECTRICAL"), "26" to listOf("FIRE", "SMOKE"),
            "27" to listOf("FLIGHT", "CONTROL"), "28" to listOf("FUEL"), "29" to listOf("HYDRAULIC"),
            "30" to listOf("ANTI", "ICE"), "31" to listOf("INDICATING", "RECORDING"),
            "32" to listOf("LANDING", "GEAR", "INDICATION", "POSITION"), "33" to listOf("LIGHT"),
            "34" to listOf("NAVIGATION"), "35" to listOf("OXYGEN"), "36" to listOf("PNEUMATIC", "BLEED"),
            "38" to listOf("WATER", "WASTE"), "49" to listOf("APU", "POWER", "PLANT"), "52" to listOf("DOOR")
        )
        terms += chapterTerms[chapter].orEmpty()
        val u = phrase.uppercase()
        if (u.contains("PROX") || u.contains("SNSR") || u.contains("SENSOR")) terms += listOf("SENSOR", "POSITION", "INDICATION")
        if (u.contains("PACK")) terms += "PACK"
        if (u.contains("APU")) terms += "APU"
        return terms.distinct()
    }

    private fun referenceHits(manual: String, refs: List<String>, limit: Int): List<SearchHit> {
        if (refs.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        refs.forEach { ref ->
            // Prefer real task entities so the viewer can open a complete task range.
            runCatching {
                val sqlTask = """
                    SELECT CAST(t.task_id AS TEXT),COALESCE(t.task_ref,''),COALESCE(t.task_title,''),t.start_pdf_page,d.filename,CAST(t.document_id AS TEXT)
                    FROM task_entries t JOIN documents d ON d.document_id=t.document_id
                    WHERE UPPER(COALESCE(d.manual,''))=? AND UPPER(t.task_ref) LIKE ?
                    ORDER BY t.start_pdf_page LIMIT 8
                """.trimIndent()
                db.rawQuery(sqlTask, arrayOf(manual.uppercase(), "${ref.uppercase()}%")).use { c ->
                    while (c.moveToNext()) {
                        val key = "task|${c.getString(0)}"
                        if (seen.add(key)) out += SearchHit("task", c.getString(0), manual, c.getString(1), c.getString(2), "Referenced by exact TSM task", -1500.0)
                    }
                }
            }
            if (out.size >= limit) return@forEach
            runCatching {
                val sql = """
                    SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                           snippet(global_search_fts,5,'[',']','…',36), bm25(global_search_fts)
                    FROM global_search_fts
                    WHERE UPPER(manual)=? AND UPPER(reference) LIKE ?
                    ORDER BY bm25(global_search_fts) LIMIT 10
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
        val ids = identifiers.filter { it.length in 3..16 }.take(14)
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        ids.forEach { id ->
            val match = "\"${id.replace("\"", "")}\""
            val sql = """
                SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                       snippet(global_search_fts,5,'[',']','…',32), bm25(global_search_fts)
                FROM global_search_fts
                WHERE global_search_fts MATCH ? AND UPPER(manual)=?
                ORDER BY bm25(global_search_fts) LIMIT 8
            """.trimIndent()
            runCatching {
                db.rawQuery(sql, arrayOf(match, manual.uppercase())).use { c ->
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

    private fun identifierTokens(phrase: String): List<String> =
        Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
            .findAll(phrase.uppercase()).map { it.value }.distinct().take(12).toList()

    private fun semanticGroups(phrase: String): List<Set<String>> {
        val tokens = Regex("[A-Z0-9]+")
            .findAll(phrase.uppercase()).map { it.value }.filter { it.length >= 3 }.toSet()
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
        Regex("\\bATA\\s*(\\d{2})[- ]?(\\d{2})[- ]?(\\d{2})\\b", RegexOption.IGNORE_CASE).find(query)?.let {
            return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}"
        }
        return null
    }

    private fun cleanStageTitle(title: String): String =
        if (title.contains(" • ") && title.substringBefore(" • ").trim().firstOrNull()?.isDigit() == true) title.substringAfter(" • ").trim()
        else title.trim()

    private fun normalize(s: String): String = s.uppercase()
        .replace('_', ' ')
        .replace(Regex("[^A-Z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsWholeToken(normalizedText: String, token: String): Boolean {
        val t = normalize(token)
        return Regex("(^| )${Regex.escape(t)}( |$)").containsMatchIn(normalizedText)
    }

    override fun close() = db.close()
}
