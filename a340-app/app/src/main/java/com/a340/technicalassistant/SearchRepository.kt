package com.a340.technicalassistant

import android.database.sqlite.SQLiteDatabase
import java.io.File


data class AiSearchBundle(
    val analysis: QueryIntelligence.Analysis,
    val hits: List<SearchHit>,
    val graphLinksAdded: Int,
    val safetySummary: String
)

class SearchRepository(dbFile: File) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun architectureVersion(): String = scalar("SELECT value FROM master_metadata WHERE key='architecture_version'") ?: "?"
    fun buildTimestamp(): String = scalar("SELECT value FROM master_metadata WHERE key='build_timestamp_utc'") ?: "?"
    fun integratedManuals(): String = scalar("SELECT value FROM master_metadata WHERE key='integrated_manuals'") ?: ""

    fun manuals(): List<ManualInfo> {
        val out = mutableListOf<ManualInfo>()
        db.rawQuery(
            "SELECT manual_code,COALESCE(manual_name,''),status,document_count,page_count,image_assets FROM manual_registry ORDER BY manual_code",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += ManualInfo(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5))
            }
        }
        return out
    }

    fun search(userQuery: String, limit: Int = 60): List<SearchHit> {
        val raw = ftsSearch(userQuery, (limit * 3).coerceAtLeast(120))
        val priority = preferredManualOrder(userQuery)
        if (priority.isEmpty()) return raw.take(limit)
        val rank = priority.withIndex().associate { it.value to it.index }
        return raw.sortedWith(compareBy<SearchHit> { rank[it.manual] ?: 99 }.thenBy { it.score }).take(limit)
    }

    fun searchAi(query: String, aircraftProfile: String = "", limit: Int = 80): AiSearchBundle {
        val analysis = QueryIntelligence.analyze(query)
        val all = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()

        fun add(hit: SearchHit) {
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) all += hit
        }

        ftsSearch(analysis.original, 240).forEach(::add)
        if (analysis.searchText != analysis.original) {
            ftsSearch(analysis.searchText, 360).forEach(::add)
        }
        if (analysis.identifiers.isNotEmpty()) {
            searchComponentIdentifiers(analysis.identifiers, analysis.original, 120).forEach(::add)
        }

        val graphAdded = expandCrossManualGraph(all.take(36), ::add)
        val manualOrder = aiManualOrder(analysis.intent)
        val manualRank = manualOrder.withIndex().associate { it.value to it.index }
        val entityRank = mapOf(
            "task" to 0,
            "chunk" to 1,
            "mel_entry" to 2,
            "mel_item" to 2,
            "mel_procedure" to 2,
            "awm_identifier" to 2,
            "asm_sheet" to 2,
            "part_number" to 2,
            "figure" to 3,
            "espm_section" to 3,
            "espm_figure" to 4,
            "cdl_item" to 4
        )

        fun primaryEntityPenalty(hit: SearchHit): Int = when (analysis.intent) {
            QueryIntelligence.Intent.TROUBLESHOOTING,
            QueryIntelligence.Intent.REMOVAL_INSTALLATION,
            QueryIntelligence.Intent.INSPECTION -> if (hit.entityType == "task") 0 else 1
            else -> 0
        }

        fun ataPenalty(hit: SearchHit): Int {
            val ata = analysis.ata ?: return 0
            val needle = "$ata-"
            return if (
                hit.reference.startsWith(needle, ignoreCase = true) ||
                hit.entityId.contains(":$needle", ignoreCase = true) ||
                hit.title.startsWith(needle, ignoreCase = true)
            ) 0 else 2
        }

        fun semanticMatchScore(hit: SearchHit): Int {
            val hay = "${hit.reference} ${hit.title} ${hit.snippet}".uppercase()
            var matches = 0
            analysis.expandedTerms.forEach { if (hay.contains(it.uppercase())) matches++ }
            analysis.identifiers.forEach { if (hay.contains(it.uppercase())) matches += 3 }
            return -matches
        }

        val ranked = all.sortedWith(
            compareBy<SearchHit> { primaryEntityPenalty(it) }
                .thenBy { manualRank[it.manual] ?: 50 }
                .thenBy { ataPenalty(it) }
                .thenBy { semanticMatchScore(it) }
                .thenBy { entityRank[it.entityType] ?: 10 }
                .thenBy { it.score }
        ).take(limit)

        return AiSearchBundle(
            analysis = analysis,
            hits = ranked,
            graphLinksAdded = graphAdded,
            safetySummary = buildSafetySummary(ranked.take(12), aircraftProfile)
        )
    }

    fun searchPostFlightFault(faultText: String, ata: String?, identifiers: List<String>, limit: Int = 80): List<SearchHit> {
        val out = collectEvidence(faultText, identifiers)
        return rankTechnicalResults(
            out,
            ata,
            mapOf(
                "TSM" to 0, "AMM/TSM" to 0, "MIXED (TSM/AMM)" to 0,
                "AMM" to 1, "MEL" to 2, "AWM" to 3, "ASM" to 4,
                "IPC" to 5, "CDL" to 6, "ESPM" to 7
            ),
            limit
        )
    }

    fun searchCaptainDefect(defectText: String, ata: String?, identifiers: List<String>, limit: Int = 80): List<SearchHit> {
        val out = collectEvidence(defectText, identifiers)
        return rankTechnicalResults(
            out,
            ata,
            mapOf(
                "TSM" to 0, "AMM/TSM" to 0, "MIXED (TSM/AMM)" to 0,
                "AMM" to 1, "MEL" to 2, "CDL" to 3, "AWM" to 4,
                "ASM" to 5, "IPC" to 6, "ESPM" to 7
            ),
            limit
        )
    }

    private fun collectEvidence(text: String, identifiers: List<String>): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(hit: SearchHit) {
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) out += hit
        }
        ftsSearch(text, 300).forEach(::add)
        if (identifiers.isNotEmpty()) searchComponentIdentifiers(identifiers, text, 120).forEach(::add)
        return out
    }

    private fun rankTechnicalResults(
        input: List<SearchHit>,
        ata: String?,
        manualRank: Map<String, Int>,
        limit: Int
    ): List<SearchHit> {
        val entityRank = mapOf(
            "task" to 0, "chunk" to 1, "mel_entry" to 2, "mel_item" to 2,
            "awm_identifier" to 2, "asm_sheet" to 2, "part_number" to 2,
            "figure" to 3, "espm_section" to 3, "espm_figure" to 4, "cdl_item" to 4
        )
        fun ataPenalty(hit: SearchHit): Int {
            if (ata.isNullOrBlank()) return 0
            val needle = "$ata-"
            return if (
                hit.reference.startsWith(needle, ignoreCase = true) ||
                hit.entityId.contains(":$needle", ignoreCase = true) ||
                hit.title.startsWith(needle, ignoreCase = true)
            ) 0 else 1
        }
        return input.sortedWith(
            compareBy<SearchHit> { manualRank[it.manual] ?: 50 }
                .thenBy { ataPenalty(it) }
                .thenBy { entityRank[it.entityType] ?: 10 }
                .thenBy { it.score }
        ).take(limit)
    }

    private fun aiManualOrder(intent: QueryIntelligence.Intent): List<String> = when (intent) {
        QueryIntelligence.Intent.TROUBLESHOOTING -> listOf("TSM", "AMM/TSM", "MIXED (TSM/AMM)", "AMM", "MEL", "ASM", "AWM", "IPC", "CDL", "ESPM")
        QueryIntelligence.Intent.REMOVAL_INSTALLATION -> listOf("AMM", "TSM", "IPC", "ASM", "AWM", "ESPM", "MEL", "CDL")
        QueryIntelligence.Intent.WIRING -> listOf("AWM", "ESPM", "ASM", "AMM", "TSM", "IPC", "MEL", "CDL")
        QueryIntelligence.Intent.PARTS -> listOf("IPC", "AWM", "AMM", "TSM", "ASM", "ESPM", "MEL", "CDL")
        QueryIntelligence.Intent.DISPATCH -> listOf("MEL", "CDL", "AMM", "TSM", "IPC", "AWM", "ASM", "ESPM")
        QueryIntelligence.Intent.STANDARD_PRACTICE -> listOf("ESPM", "AWM", "AMM", "ASM", "TSM", "IPC", "MEL", "CDL")
        QueryIntelligence.Intent.INSPECTION -> listOf("AMM", "TSM", "IPC", "ASM", "AWM", "MEL", "CDL", "ESPM")
        QueryIntelligence.Intent.GENERAL -> listOf("TSM", "AMM", "ASM", "AWM", "IPC", "MEL", "CDL", "ESPM")
    }

    private fun expandCrossManualGraph(seedHits: List<SearchHit>, add: (SearchHit) -> Unit): Int {
        val sections = linkedSetOf<String>()
        val refRegex = Regex("\\b\\d{2}-\\d{2}-\\d{2}\\b")
        seedHits.forEach { h ->
            listOf(h.reference, h.title, h.snippet).forEach { s ->
                refRegex.findAll(s).forEach { sections += it.value }
            }
        }
        var added = 0
        for (ref in sections.take(18)) {
            val links = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT DISTINCT reference_value,COALESCE(target_manual,'') FROM cross_manual_links WHERE resolution_status='EXACT' AND (reference_value=? OR source_entity_id=?) AND target_manual IS NOT NULL LIMIT 12",
                arrayOf(ref, "coverage:$ref")
            ).use { c ->
                while (c.moveToNext()) links += c.getString(0) to c.getString(1)
            }
            for ((value, targetManual) in links) {
                val candidates = ftsSearch(value, 32).filter { it.manual == targetManual }.take(4)
                for (candidate in candidates) {
                    add(candidate.copy(snippet = "AI CROSS-MANUAL EXACT LINK • $ref → $targetManual\n${candidate.snippet}"))
                    added++
                }
            }
        }
        return added
    }

    private data class SourceSafety(
        val manual: String,
        val revisionDate: String,
        val revisionNumber: String,
        val revisionStatus: String,
        val applicability: String
    )

    private fun safetyFor(hit: SearchHit): SourceSafety? {
        val src = runCatching { resolve(hit) }.getOrNull() ?: return null
        val sql = """
            SELECT COALESCE(d.manual,''),COALESCE(d.revision_date,''),COALESCE(d.revision_number,''),
                   COALESCE(d.revision_status,''),
                   COALESCE(p.effectivity_json,'')
            FROM documents d
            LEFT JOIN pages p ON p.document_id=d.document_id AND p.pdf_page=?
            WHERE d.filename=?
            LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(src.pdfPage.toString(), src.filename)).use { c ->
                if (!c.moveToFirst()) return@runCatching null
                SourceSafety(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            }
        }.getOrNull()
    }

    private fun buildSafetySummary(hits: List<SearchHit>, aircraftProfile: String): String {
        val sourceInfo = hits.mapNotNull(::safetyFor)
        val statuses = sourceInfo.map { "${it.manual}:${it.revisionDate.ifBlank { "REV?" }}:${it.revisionStatus.ifBlank { "STATUS?" }}" }.distinct().take(8)
        val espInfo = sourceInfo.any { it.manual == "ESPM" || it.revisionStatus.contains("INFORMATION_ONLY", ignoreCase = true) }
        val effectivity = if (aircraftProfile.isBlank()) {
            "EFFECTIVITY: NOT VERIFIED — enter REG/MSN/effectivity profile before using a task"
        } else {
            val tokens = Regex("[A-Z0-9-]{2,}").findAll(aircraftProfile.uppercase()).map { it.value }.toList()
            val known = sourceInfo.filter { it.applicability.isNotBlank() }
            val matched = known.count { s ->
                val app = s.applicability.uppercase()
                app.contains("ALL") || tokens.any { app.contains(it) }
            }
            "EFFECTIVITY PROFILE: $aircraftProfile • matched evidence $matched/${known.size.coerceAtLeast(1)} • verify in source manual"
        }
        return buildString {
            append(effectivity)
            if (statuses.isNotEmpty()) append("\nREVISION SOURCES: ").append(statuses.joinToString(" | "))
            if (espInfo) append("\nESPM: INFORMATION ONLY / NOT PRIMARY REFERENCE")
            append("\nAI SAFETY: evidence retrieval only; source manual remains authoritative")
        }
    }

    private fun ftsSearch(userQuery: String, limit: Int): List<SearchHit> {
        val terms = Regex("[A-Za-z0-9][A-Za-z0-9_./-]*")
            .findAll(userQuery.uppercase()).map { it.value }.distinct().toList()
        if (terms.isEmpty()) return emptyList()
        val match = terms.take(28).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',22), bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ?
            ORDER BY bm25(global_search_fts)
            LIMIT ?
        """.trimIndent()
        val out = mutableListOf<SearchHit>()
        db.rawQuery(sql, arrayOf(match, limit.toString())).use { c ->
            while (c.moveToNext()) {
                out += SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6))
            }
        }
        return out
    }

    fun searchComponentIdentifiers(identifiers: List<String>, rawText: String, limit: Int = 60): List<SearchHit> {
        val ids = identifiers.map { it.uppercase() }.distinct().take(16)
        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        fun add(hit: SearchHit) {
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) out += hit
        }
        ids.forEach { id ->
            runCatching {
                db.rawQuery("SELECT part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'') FROM ipc_items WHERE UPPER(part_number)=? LIMIT 10", arrayOf(id)).use { c ->
                    while (c.moveToNext()) add(SearchHit("part_number", c.getString(0), "IPC", c.getString(0), c.getString(1), c.getString(2), -1000.0))
                }
            }
        }
        ids.forEach { id ->
            runCatching {
                db.rawQuery("SELECT CAST(id AS TEXT),token,COALESCE(kind,'') FROM awm_identifiers WHERE UPPER(token)=? LIMIT 16", arrayOf(id)).use { c ->
                    while (c.moveToNext()) add(SearchHit("awm_identifier", c.getString(0), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier match", -950.0))
                }
            }
        }
        ids.forEach { id ->
            runCatching {
                db.rawQuery("SELECT section_id,section_ref,COALESCE(title,''),COALESCE(first_page_text,'') FROM espm_sections WHERE UPPER(section_ref)=? LIMIT 10", arrayOf(id)).use { c ->
                    while (c.moveToNext()) add(SearchHit("espm_section", c.getString(0), "ESPM", c.getString(1), c.getString(2), c.getString(3), -900.0))
                }
            }
        }
        val broadQuery = listOf(rawText.trim(), ids.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")
        if (broadQuery.isNotBlank()) runCatching { search(broadQuery, limit * 2) }.getOrDefault(emptyList()).forEach(::add)
        return out.take(limit)
    }

    fun resolve(hit: SearchHit): ResolvedSource? = when (hit.entityType) {
        "chunk" -> one("SELECT c.manual,c.document_filename,c.pdf_page,COALESCE(c.ata_chapter,''),COALESCE(c.ata_title,''),c.content FROM chunks c WHERE c.chunk_id=?", hit.entityId)
        "task" -> one("SELECT COALESCE(d.manual,''),d.filename,t.start_pdf_page,t.task_ref,COALESCE(t.task_title,''),'' FROM task_entries t JOIN documents d ON d.document_id=t.document_id WHERE t.task_id=?", hit.entityId)
        "figure" -> one("SELECT COALESCE(d.manual,''),d.filename,f.pdf_page,COALESCE(f.figure_ref,f.figure_number,''),COALESCE(f.caption,''),'' FROM figures f JOIN documents d ON d.document_id=f.document_id WHERE f.figure_id=?", hit.entityId)
        "asm_sheet" -> one("SELECT 'ASM',d.filename,a.pdf_page,a.schematic_ref,COALESCE(a.title,''),'' FROM asm_sheets a JOIN documents d ON d.document_id=a.document_id WHERE a.sheet_id=?", hit.entityId)
        "awm_diagram" -> one("SELECT 'AWM',d.filename,p.pdf_page,COALESCE(a.diagram_ref,''),COALESCE(a.title,''),'' FROM awm_diagrams a JOIN pages p ON p.page_id=a.canonical_page_id JOIN documents d ON d.document_id=p.document_id WHERE CAST(a.awm_diagram_id AS TEXT)=?", hit.entityId)
        "awm_identifier" -> one("SELECT 'AWM',d.filename,p.pdf_page,i.token,i.kind,'' FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id JOIN documents d ON d.document_id=p.document_id WHERE CAST(i.id AS TEXT)=?", hit.entityId)
        "part_number" -> one("SELECT 'IPC',d.filename,i.pdf_page,i.part_number,COALESCE(i.nomenclature,''),COALESCE(i.raw_block,'') FROM ipc_items i JOIN documents d ON d.document_id=i.document_id WHERE UPPER(i.part_number)=UPPER(?) ORDER BY i.pdf_page LIMIT 1", hit.entityId)
        "cdl_item" -> one("SELECT 'CDL',d.filename,c.start_pdf_page,c.cdl_ref,COALESCE(c.title,''),COALESCE(c.content,'') FROM cdl_items c JOIN documents d ON d.document_id=c.document_id WHERE ('CDL-' || c.cdl_item_id)=?", hit.entityId)
        "mel_entry" -> one("SELECT 'MEL',d.filename,m.start_pdf_page,COALESCE(m.ata_chapter,''),COALESCE(m.title,''),COALESCE(m.content,'') FROM mel_entries m JOIN documents d ON d.document_id=m.document_id WHERE ('MEL-E-' || m.mel_entry_id)=?", hit.entityId)
        "mel_item" -> one("SELECT 'MEL',d.filename,m.start_pdf_page,COALESCE(m.mel_ref,''),COALESCE(m.title,''),COALESCE(m.content,'') FROM mel_items m JOIN documents d ON d.document_id=m.document_id WHERE ('MEL-I-' || m.mel_item_id)=?", hit.entityId)
        "mel_procedure" -> one("SELECT 'MEL',d.filename,m.start_pdf_page,COALESCE(m.procedure_ref,''),COALESCE(m.title,''),COALESCE(m.content,'') FROM mel_procedures m JOIN documents d ON d.document_id=m.document_id WHERE ('MEL-P-' || m.mel_procedure_id)=?", hit.entityId)
        "espm_section" -> one("SELECT 'ESPM',d.filename,e.start_pdf_page,e.section_ref,COALESCE(e.title,''),COALESCE(e.first_page_text,'') FROM espm_sections e JOIN documents d ON d.document_id=e.document_id WHERE e.section_id=?", hit.entityId)
        "espm_figure" -> one("SELECT 'ESPM',d.filename,e.pdf_page,e.figure_ref,COALESCE(e.caption,''),'' FROM espm_figures e JOIN documents d ON d.document_id=e.document_id WHERE ('ESPM-FIG-' || e.espm_figure_id)=?", hit.entityId)
        else -> null
    }

    private fun preferredManualOrder(query: String): List<String> {
        val q = query.uppercase()
        fun hasAny(vararg words: String) = words.any { q.contains(it) }
        return when {
            hasAny("WIRE", "WIRING", "CONNECTOR", "CONTACT", "SPLICE", "TERMINAL", "CRIMP", "HARNESS", "CABLE", "BONDING") -> listOf("AWM", "ESPM", "IPC", "AMM", "TSM", "ASM", "MEL", "CDL")
            hasAny("PART NUMBER", "P/N", "PART NO", "PN ", "NOMENCLATURE") -> listOf("IPC", "AWM", "AMM", "TSM", "ASM", "ESPM", "MEL", "CDL")
            hasAny("FAULT", "ECAM", "CFDS", "WARNING", "FAILURE", "MESSAGE") -> listOf("TSM", "AMM/TSM", "AMM", "MEL", "ASM", "AWM", "IPC", "CDL", "ESPM")
            hasAny("MEL", "DEFER", "DISPATCH", "INOPERATIVE", "INOP", "REPAIR INTERVAL") -> listOf("MEL", "CDL", "AMM", "TSM", "IPC", "AWM", "ASM", "ESPM")
            hasAny("REMOVE", "REMOVAL", "INSTALL", "INSTALLATION", "TEST", "INSPECTION", "ADJUST", "SERVIC") -> listOf("AMM", "TSM", "IPC", "ASM", "AWM", "ESPM", "MEL", "CDL")
            else -> emptyList()
        }
    }

    private fun one(sql: String, arg: String): ResolvedSource? {
        db.rawQuery(sql, arrayOf(arg)).use { c ->
            if (!c.moveToFirst()) return null
            return ResolvedSource(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))
        }
    }

    private fun scalar(sql: String): String? = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    override fun close() = db.close()
}
