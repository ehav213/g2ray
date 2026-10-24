package com.a340.technicalassistant

import android.database.sqlite.SQLiteDatabase
import java.io.File

class SearchRepository(dbFile: File) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun architectureVersion(): String = scalar("SELECT value FROM master_metadata WHERE key='architecture_version'") ?: "?"
    fun buildTimestamp(): String = scalar("SELECT value FROM master_metadata WHERE key='build_timestamp_utc'") ?: "?"

    fun manuals(): List<ManualInfo> {
        val out = mutableListOf<ManualInfo>()
        db.rawQuery("SELECT manual_code,COALESCE(manual_name,''),status,document_count,page_count,image_assets FROM manual_registry ORDER BY manual_code", null).use { c ->
            while (c.moveToNext()) out += ManualInfo(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getInt(4),c.getInt(5))
        }
        return out
    }

    fun search(userQuery: String, limit: Int = 40): List<SearchHit> {
        val terms = Regex("[A-Za-z0-9][A-Za-z0-9_./-]*").findAll(userQuery.uppercase()).map { it.value }.toList()
        if (terms.isEmpty()) return emptyList()
        val match = terms.take(18).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
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
            while (c.moveToNext()) out += SearchHit(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getDouble(6))
        }
        return out
    }

    fun searchComponentIdentifiers(identifiers: List<String>, limit: Int = 60): List<SearchHit> {
        val ids = identifiers.map { it.uppercase() }.distinct().take(12)
        if (ids.isEmpty()) return emptyList()

        val out = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()

        fun add(hit: SearchHit) {
            val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
            if (seen.add(key)) out += hit
        }

        // Highest priority: exact IPC part-number matches.
        ids.forEach { id ->
            try {
                db.rawQuery(
                    "SELECT part_number,COALESCE(nomenclature,''),COALESCE(raw_block,'') FROM ipc_items WHERE UPPER(part_number)=? LIMIT 8",
                    arrayOf(id)
                ).use { c ->
                    while (c.moveToNext()) {
                        add(SearchHit("part_number", c.getString(0), "IPC", c.getString(0), c.getString(1), c.getString(2), -1000.0))
                    }
                }
            } catch (_: Throwable) { }
        }

        // Next: exact AWM identifiers such as FIN/connector/equipment labels.
        ids.forEach { id ->
            try {
                db.rawQuery(
                    "SELECT CAST(id AS TEXT),token,COALESCE(kind,'') FROM awm_identifiers WHERE UPPER(token)=? LIMIT 12",
                    arrayOf(id)
                ).use { c ->
                    while (c.moveToNext()) {
                        add(SearchHit("awm_identifier", c.getString(0), "AWM", c.getString(1), c.getString(2), "Exact AWM identifier match", -900.0))
                    }
                }
            } catch (_: Throwable) { }
        }

        // Then broaden through the master FTS to AMM/TSM/ASM/AWM/IPC and future manuals.
        try {
            search(ids.joinToString(" "), limit).forEach(::add)
        } catch (_: Throwable) { }

        return out.take(limit)
    }

    fun resolve(hit: SearchHit): ResolvedSource? = when (hit.entityType) {
        "chunk" -> one("SELECT c.manual,c.document_filename,c.pdf_page,COALESCE(c.ata_chapter,''),COALESCE(c.ata_title,''),c.content FROM chunks c WHERE c.chunk_id=?", hit.entityId)
        "task" -> one("SELECT COALESCE(d.manual,''),d.filename,t.start_pdf_page,t.task_ref,COALESCE(t.task_title,''),'' FROM task_entries t JOIN documents d ON d.document_id=t.document_id WHERE t.task_id=?", hit.entityId)
        "figure" -> one("SELECT COALESCE(d.manual,''),d.filename,f.pdf_page,COALESCE(f.figure_ref,f.figure_number,''),COALESCE(f.caption,''),'' FROM figures f JOIN documents d ON d.document_id=f.document_id WHERE f.figure_id=?", hit.entityId)
        "asm_sheet" -> one("SELECT 'ASM',d.filename,a.pdf_page,a.schematic_ref,COALESCE(a.title,''),'' FROM asm_sheets a JOIN documents d ON d.document_id=a.document_id WHERE a.sheet_id=?", hit.entityId)
        "awm_diagram" -> one("SELECT 'AWM',d.filename,p.pdf_page,COALESCE(a.diagram_ref,''),COALESCE(a.title,''),'' FROM awm_diagrams a JOIN pages p ON p.page_id=a.canonical_page_id JOIN documents d ON d.document_id=p.document_id WHERE CAST(a.awm_diagram_id AS TEXT)=?", hit.entityId)
        "awm_identifier" -> one("SELECT 'AWM',d.filename,p.pdf_page,i.token,i.kind,'' FROM awm_identifiers i JOIN pages p ON p.page_id=i.page_id JOIN documents d ON d.document_id=p.document_id WHERE CAST(i.id AS TEXT)=?", hit.entityId)
        "part_number" -> one("SELECT 'IPC',d.filename,i.pdf_page,i.part_number,COALESCE(i.nomenclature,''),COALESCE(i.raw_block,'') FROM ipc_items i JOIN documents d ON d.document_id=i.document_id WHERE i.part_number=? ORDER BY i.pdf_page LIMIT 1", hit.entityId)
        else -> null
    }

    private fun one(sql: String, arg: String): ResolvedSource? {
        db.rawQuery(sql, arrayOf(arg)).use { c ->
            if (!c.moveToFirst()) return null
            return ResolvedSource(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getString(4),c.getString(5))
        }
    }

    private fun scalar(sql: String): String? = db.rawQuery(sql,null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    override fun close() = db.close()
}
