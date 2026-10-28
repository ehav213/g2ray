package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

/**
 * Exact fault-centered workflow for structured PFR maintenance messages.
 *
 * Rules:
 * 1) A PFR maintenance message with a six-digit ATA is never allowed to fan out into a broad TSM list.
 * 2) The maintenance message is searched with AND-token semantics inside TSM only.
 * 3) If the first exact hit is a page/chunk, any referenced TSM 810 task is extracted and resolved to the task entity.
 * 4) AMM/ASM/AWM/IPC are then derived from that exact TSM task, not from a second broad search.
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
        if (phrase.length < 4) return base
        val pfrAta = fullAtaFromQuery(query)
        val strictPfr = pfrAta != null

        val exactTsm = exactTsmHits(phrase, pfrAta, 1)
        if (exactTsm.isEmpty()) {
            if (!strictPfr) return base

            // A known PFR maintenance message must never degrade into unrelated broad TSM/AMM results.
            val melOnly = base.hits.filter { it.manual.equals("MEL", true) }.take(4)
            val note = buildString {
                append("\n\nEXACT FAULT RESOLUTION")
                append("\nMaintenance message: ").append(phrase)
                append("\nMaintenance ATA: ").append(pfrAta)
                append("\nEXACT TSM TASK NOT RESOLVED — broad TSM alternatives were intentionally suppressed.")
                append("\nUse the original TSM fault-symptom/index entry for this exact maintenance message before applying troubleshooting actions.")
            }
            return base.copy(hits = melOnly, workflowSummary = base.workflowSummary + note)
        }

        val primary = exactTsm.first()
        val support = support(primary)
        val amm = referenceHits("AMM", support.amm, 8)
        val asm = referenceHits("ASM", support.asm, 4)
        val awm = identifierHits("AWM", support.identifiers, 4)
        val ipc = identifierHits("IPC", support.identifiers, 4)
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
        // No broad fallback after exact TSM resolution: only sources referenced by that task are shown.
        add("3 AMM REFERENCED TASK", amm)
        add("4 ASM REFERENCED SCHEMATIC", asm)
        add("5 AWM BY TSM IDENTIFIER", awm)
        add("6 IPC BY TSM IDENTIFIER", ipc)
        if (base.includeEspm) add("7 ESPM STANDARD PRACTICE", espm)

        val exactNote = buildString {
            append("\n\nEXACT FAULT RESOLUTION")
            append("\nMaintenance message: ").append(phrase)
            pfrAta?.let { append("\nMaintenance ATA: ").append(it) }
            append("\nPrimary TSM: ").append(primary.reference.ifBlank { "reference not indexed" })
            append(" — ").append(cleanStageTitle(primary.title))
            if (support.amm.isNotEmpty()) append("\nReferenced AMM: ").append(support.amm.joinToString(", "))
            if (support.asm.isNotEmpty()) append("\nReferenced ASM: ").append(support.asm.joinToString(", "))
            if (support.identifiers.isNotEmpty()) append("\nTSM identifiers: ").append(support.identifiers.take(12).joinToString(", "))
            append("\nBroad TSM alternatives are suppressed because a structured PFR maintenance message is being resolved.")
        }

        return base.copy(
            hits = ordered,
            workflowSummary = base.workflowSummary + exactNote
        )
    }

    private fun exactTsmHits(phrase: String, ataFull: String?, limit: Int): List<SearchHit> {
        val tokens = faultTokens(phrase)
        if (tokens.size < 2) return emptyList()

        // AND semantics: every meaningful maintenance-message token must be present.
        val match = tokens.joinToString(" AND ") { token -> "\"${token.replace("\"", "")}\"" }
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',48), bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ? AND UPPER(manual)='TSM'
            ORDER BY bm25(global_search_fts)
            LIMIT 100
        """.trimIndent()

        val candidates = mutableListOf<SearchHit>()
        runCatching {
            db.rawQuery(sql, arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    candidates += SearchHit(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getDouble(6)
                    )
                }
            }
        }.getOrElse { return emptyList() }

        val needle = normalize(phrase)
        val taskRefRegex = Regex("\\b(\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-[A-Z](?:\\d{2})?)\\b")
        val taskRefs = linkedSetOf<String>()
        val directTasks = mutableListOf<SearchHit>()

        candidates.forEach { h ->
            val src = runCatching { resolver.resolve(h) }.getOrNull()
            val full = listOf(h.reference, h.title, h.snippet, src?.content.orEmpty()).joinToString("\n")
            val hay = normalize(full)
            val allTokensPresent = tokens.all { token -> containsWholeToken(hay, token) }
            val phrasePresent = hay.contains(needle)
            if (!phrasePresent && !allTokensPresent) return@forEach

            if (ataFull != null) {
                val ataDigits = ataFull.replace("-", "")
                val normalizedAta = ataFull.replace("-", " ")
                val hasAta = full.uppercase().contains(ataFull) || hay.contains(normalizedAta) || hay.contains(ataDigits)
                // Do not reject a task that has a valid TSM task reference starting with the same ATA family.
                val refMatchesAta = h.reference.uppercase().startsWith(ataFull)
                if (!hasAta && !refMatchesAta) return@forEach
            }

            taskRefRegex.findAll(full.uppercase()).forEach { m ->
                val ref = m.groupValues[1]
                if (ataFull == null || ref.startsWith(ataFull)) taskRefs += ref
            }

            if (looksLikeTsmTask(h)) directTasks += h
        }

        val resolvedTasks = referenceHits("TSM", taskRefs.toList(), 12)
        val merged = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        (resolvedTasks + directTasks).forEach { h ->
            val key = "${h.manual}|${h.reference}|${h.entityId}"
            if (seen.add(key)) merged += h
        }

        return merged
            .map { h -> Triple(h, exactTaskRank(h, phrase, ataFull), h.score) }
            .sortedWith(compareBy<Triple<SearchHit, Int, Double>> { it.second }.thenBy { it.third })
            .map { it.first }
            .take(limit)
    }

    private fun exactTaskRank(hit: SearchHit, phrase: String, ataFull: String?): Int {
        val src = runCatching { resolver.resolve(hit) }.getOrNull()
        val hay = normalize(listOf(hit.title, hit.snippet, src?.content.orEmpty()).joinToString(" "))
        var rank = 100
        if (hay.contains(normalize(phrase))) rank -= 60
        if (hit.entityType.contains("TASK", true)) rank -= 30
        if (Regex("\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-[A-Z]", RegexOption.IGNORE_CASE).containsMatchIn(hit.reference)) rank -= 25
        if (ataFull != null && hit.reference.startsWith(ataFull)) rank -= 20
        return rank
    }

    private fun looksLikeTsmTask(hit: SearchHit): Boolean =
        hit.entityType.contains("TASK", true) ||
            Regex("\\d{2}-\\d{2}-\\d{2}-810-\\d{3}-[A-Z]", RegexOption.IGNORE_CASE).containsMatchIn(hit.reference)

    private fun faultTokens(phrase: String): List<String> {
        val generic = setOf("FAULT", "MESSAGE", "MAINT", "MAINTENANCE", "ATA")
        return Regex("[A-Z0-9]+")
            .findAll(phrase.uppercase().replace('_', ' '))
            .map { it.value }
            .filter { it.length >= 2 && it !in generic }
            .distinct()
            .take(12)
            .toList()
    }

    private fun containsWholeToken(normalizedText: String, token: String): Boolean {
        val t = normalize(token)
        return Regex("(^| )${Regex.escape(t)}( |$)").containsMatchIn(normalizedText)
    }

    private fun support(primary: SearchHit): Support {
        val src = runCatching { resolver.resolve(primary) }.getOrNull()
        val text = listOf(primary.title, primary.snippet, src?.content.orEmpty()).joinToString("\n").uppercase()
        val amm = Regex("\\bAMM\\s+(\\d{2}-\\d{2}-\\d{2}-\\d{3}-\\d{3})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(16).toList()
        val asm = Regex("\\bASM\\s+(\\d{2}-\\d{2}-\\d{2})\\b")
            .findAll(text).map { it.groupValues[1] }.distinct().take(8).toList()
        val ids = Regex("\\b(?:\\d{1,4}[A-Z]{1,4}\\d{0,3}|[A-Z]{2,6}\\d{1,4})\\b")
            .findAll(text).map { it.value }
            .filterNot { it in setOf("AMM", "ASM", "TSM", "BITE", "A340") }
            .filterNot { it.matches(Regex("\\d+")) }
            .distinct().take(30).toList()
        return Support(amm, asm, ids)
    }

    private fun referenceHits(manual: String, refs: List<String>, limit: Int): List<SearchHit> {
        if (refs.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        refs.forEach { ref ->
            val sql = """
                SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                       snippet(global_search_fts,5,'[',']','…',36), bm25(global_search_fts)
                FROM global_search_fts
                WHERE UPPER(manual)=? AND UPPER(reference) LIKE ?
                ORDER BY bm25(global_search_fts)
                LIMIT 12
            """.trimIndent()
            db.rawQuery(sql, arrayOf(manual.uppercase(), "%${ref.uppercase()}%")).use { c ->
                while (c.moveToNext()) {
                    val h = SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
                    val key = "${h.manual}|${h.reference}|${h.entityId}"
                    if (seen.add(key)) out += h
                }
            }
        }
        return out.sortedBy { h ->
            refs.indexOfFirst { h.reference.uppercase().contains(it.uppercase()) }.let { if (it < 0) 999 else it }
        }.take(limit)
    }

    private fun identifierHits(manual: String, identifiers: List<String>, limit: Int): List<SearchHit> {
        val ids = identifiers.filter { it.length in 3..16 }.take(12)
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
                ORDER BY bm25(global_search_fts)
                LIMIT 8
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

    override fun close() = db.close()
}
