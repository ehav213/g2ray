package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

/**
 * Replaces broad semantic source lists with an exact fault-centered source cascade
 * whenever the reported maintenance message is present verbatim in TSM evidence.
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

    fun enhance(base: LineMaintenanceResult, query: String, profile: String): LineMaintenanceResult {
        val phrase = faultPhrase(query)
        if (phrase.length < 6) return base

        val exactTsm = exactTsmHits(phrase, 1)
        if (exactTsm.isEmpty()) return base

        val primary = exactTsm.first()
        val support = support(primary)
        val amm = referenceHits("AMM", support.amm, 6)
        val asm = referenceHits("ASM", support.asm, 3)
        val awm = identifierHits("AWM", support.identifiers, 3)
        val ipc = identifierHits("IPC", support.identifiers, 3)
        val espm = if (base.includeEspm) identifierHits("ESPM", support.identifiers, 3) else emptyList()

        val ordered = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(stage: String, items: List<SearchHit>) {
            items.forEach { h ->
                val key = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
                if (seen.add(key)) ordered += h.copy(title = "$stage • ${cleanStageTitle(h.title)}")
            }
        }

        add("1 MEL CHECK", base.hits.filter { it.manual.equals("MEL", true) }.take(4))
        add("2 TSM EXACT FAULT", exactTsm)
        add("3 AMM REFERENCED TASK", if (amm.isNotEmpty()) amm else base.hits.filter { it.manual.equals("AMM", true) }.take(2))
        add("4 ASM REFERENCED SCHEMATIC", if (asm.isNotEmpty()) asm else base.hits.filter { it.manual.equals("ASM", true) }.take(2))
        add("5 AWM BY TSM IDENTIFIER", if (awm.isNotEmpty()) awm else base.hits.filter { it.manual.equals("AWM", true) }.take(2))
        add("6 IPC BY TSM IDENTIFIER", if (ipc.isNotEmpty()) ipc else base.hits.filter { it.manual.equals("IPC", true) }.take(2))
        if (base.includeEspm) add("7 ESPM STANDARD PRACTICE", if (espm.isNotEmpty()) espm else base.hits.filter { it.manual.equals("ESPM", true) }.take(2))

        val exactNote = buildString {
            append("\n\nEXACT FAULT RESOLUTION")
            append("\nMaintenance message: ").append(phrase)
            append("\nPrimary TSM: ").append(primary.reference.ifBlank { "reference not indexed" })
            append(" — ").append(cleanStageTitle(primary.title))
            if (support.amm.isNotEmpty()) append("\nReferenced AMM: ").append(support.amm.joinToString(", "))
            if (support.asm.isNotEmpty()) append("\nReferenced ASM: ").append(support.asm.joinToString(", "))
            if (support.identifiers.isNotEmpty()) append("\nTSM identifiers: ").append(support.identifiers.take(10).joinToString(", "))
            append("\nBroad TSM alternatives were suppressed because an exact maintenance-message match was found.")
        }

        return base.copy(
            hits = ordered,
            workflowSummary = base.workflowSummary + exactNote
        )
    }

    private fun exactTsmHits(phrase: String, limit: Int): List<SearchHit> {
        val tokens = Regex("[A-Z0-9]+")
            .findAll(phrase.uppercase().replace('_', ' '))
            .map { it.value }
            .filter { it.length >= 1 }
            .toList()
        if (tokens.size < 2) return emptyList()
        val match = "\"${tokens.joinToString(" ")}\""
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',36), bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ? AND UPPER(manual)='TSM'
            ORDER BY bm25(global_search_fts)
            LIMIT 40
        """.trimIndent()
        val candidates = mutableListOf<SearchHit>()
        db.rawQuery(sql, arrayOf(match)).use { c ->
            while (c.moveToNext()) {
                candidates += SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
            }
        }
        val needle = normalize(phrase)
        return candidates.mapNotNull { h ->
            val src = runCatching { resolver.resolve(h) }.getOrNull()
            val hay = normalize(listOf(h.title, h.snippet, src?.content.orEmpty()).joinToString(" "))
            if (!hay.contains(needle)) null else {
                var rank = 0
                if (h.entityType.contains("TASK", true)) rank -= 100
                if (Regex("\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-A", RegexOption.IGNORE_CASE).containsMatchIn(h.reference)) rank -= 80
                rank += h.reference.length.coerceAtMost(40)
                Triple(h, rank, h.score)
            }
        }.sortedWith(compareBy<Triple<SearchHit, Int, Double>> { it.second }.thenBy { it.third })
            .map { it.first }
            .take(limit)
    }

    private fun support(primary: SearchHit): Support {
        val src = runCatching { resolver.resolve(primary) }.getOrNull()
        val text = listOf(primary.snippet, src?.content.orEmpty()).joinToString("\n").uppercase()
        val amm = Regex("\\bAMM\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()
        val asm = Regex("\\bASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(6).toList()
        val ids = Regex("\\b(?:\\d{1,4}[A-Z]{1,3}|[A-Z]{1,4}\\d{1,4}[A-Z]?)\\b")
            .findAll(text).map { it.value }
            .filterNot { it in setOf("AMM", "ASM", "TSM", "BITE", "A340") }
            .filterNot { it.matches(Regex("\\d+")) }
            .distinct().take(20).toList()
        return Support(amm, asm, ids)
    }

    private fun referenceHits(manual: String, refs: List<String>, limit: Int): List<SearchHit> {
        if (refs.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        refs.forEach { ref ->
            val sql = """
                SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                       snippet(global_search_fts,5,'[',']','…',30), bm25(global_search_fts)
                FROM global_search_fts
                WHERE UPPER(manual)=? AND UPPER(reference) LIKE ?
                LIMIT 8
            """.trimIndent()
            db.rawQuery(sql, arrayOf(manual.uppercase(), "%${ref.uppercase()}%")).use { c ->
                while (c.moveToNext()) {
                    val h = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                    val key = "${h.manual}|${h.reference}|${h.entityId}"
                    if (seen.add(key)) out += h
                }
            }
        }
        return out.sortedBy { h -> refs.indexOfFirst { h.reference.uppercase().contains(it.uppercase()) }.let { if (it < 0) 999 else it } }.take(limit)
    }

    private fun identifierHits(manual: String, identifiers: List<String>, limit: Int): List<SearchHit> {
        val ids = identifiers.filter { it.length in 3..14 }.take(8)
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        ids.forEach { id ->
            val match = "\"${id.replace("\"", "")}\""
            val sql = """
                SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                       snippet(global_search_fts,5,'[',']','…',28), bm25(global_search_fts)
                FROM global_search_fts
                WHERE global_search_fts MATCH ? AND UPPER(manual)=?
                ORDER BY bm25(global_search_fts)
                LIMIT 6
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

    private fun faultPhrase(query: String): String {
        var s = query.uppercase().replace('_', ' ')
        s = s.substringBefore(" ATA ")
        s = s.substringBefore(" UTC ")
        s = s.replace(Regex("\\bATA\\s*\\d{2}(?:[- ]?\\d{2})?(?:[- ]?\\d{2})?\\b"), " ")
        s = s.replace(Regex("\\b(CLASS|HARD|INTERMITTENT|SOURCE|FLIGHT PHASE)\\b.*$"), " ")
        return s.replace(Regex("\\s+"), " ").trim(' ', ':', ';', '-')
    }

    private fun cleanStageTitle(title: String): String =
        if (title.contains(" • ") && title.substringBefore(" • ").trim().firstOrNull()?.isDigit() == true) title.substringAfter(" • ").trim()
        else title.trim()

    private fun normalize(s: String): String = s.uppercase().replace('_', ' ').replace(Regex("[^A-Z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

    override fun close() = db.close()
}
