package com.a340.technicalassistant

import android.database.sqlite.SQLiteDatabase
import java.io.File

class EvidenceRepository(dbFile: File) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun buildEvidencePack(
        hits: List<SearchHit>,
        resolver: SearchRepository,
        maxItems: Int = 3,
        maxCharsPerItem: Int = 480
    ): String = buildPack(hits.take(maxItems), resolver, maxCharsPerItem)

    /**
     * Balanced pack for line maintenance. It prevents MEL-only or TSM-only context
     * by taking representative evidence from each relevant manual in workflow order.
     */
    fun buildLineMaintenanceEvidencePack(
        hits: List<SearchHit>,
        resolver: SearchRepository,
        maxCharsPerItem: Int = 430
    ): String {
        val selected = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        val stages = listOf("MEL", "TSM", "AMM", "ASM", "AWM", "IPC", "ESPM")
        val perStage = mapOf("MEL" to 1, "TSM" to 2, "AMM" to 1, "ASM" to 1, "AWM" to 1, "IPC" to 1, "ESPM" to 1)

        for (manual in stages) {
            hits.filter { it.manual.equals(manual, ignoreCase = true) }
                .take(perStage[manual] ?: 1)
                .forEach { hit ->
                    val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
                    if (seen.add(key)) selected += hit
                }
        }
        if (selected.isEmpty()) return buildEvidencePack(hits, resolver)
        return buildPack(selected.take(8), resolver, maxCharsPerItem)
    }

    private fun buildPack(
        selected: List<SearchHit>,
        resolver: SearchRepository,
        maxCharsPerItem: Int
    ): String {
        val blocks = mutableListOf<String>()
        selected.forEachIndexed { index, hit ->
            val src = runCatching { resolver.resolve(hit) }.getOrNull()
            val body = when (hit.entityType) {
                "task" -> taskEvidence(hit.entityId)
                "chunk" -> chunkEvidence(hit.entityId)
                else -> src?.content?.takeIf { it.isNotBlank() } ?: hit.snippet
            }.replace(Regex("\\s+"), " ").trim().take(maxCharsPerItem)

            val header = buildString {
                append("[E").append(index + 1).append("] ")
                append(hit.manual)
                if (hit.reference.isNotBlank()) append(" | ").append(hit.reference)
                if (hit.title.isNotBlank()) append(" | ").append(hit.title.take(140))
                if (src != null) append(" | PDF ").append(src.pdfPage)
                if (hit.manual == "ESPM") append(" | INFORMATION ONLY")
            }
            blocks += "$header\n$body"
        }
        return blocks.joinToString("\n\n")
    }

    private fun chunkEvidence(chunkId: String): String {
        db.rawQuery("SELECT content FROM chunks WHERE chunk_id=? LIMIT 1", arrayOf(chunkId)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else ""
        }
    }

    private fun taskEvidence(taskId: String): String {
        val meta = db.rawQuery(
            """
            SELECT t.document_id,t.start_pdf_page,COALESCE(t.end_pdf_page,t.start_pdf_page),
                   COALESCE(t.task_title,'')
            FROM task_entries t WHERE t.task_id=? LIMIT 1
            """.trimIndent(),
            arrayOf(taskId)
        ).use { c ->
            if (!c.moveToFirst()) return ""
            TaskMeta(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3))
        }

        val endPage = minOf(meta.endPage, meta.startPage + 1)
        val text = StringBuilder()
        if (meta.title.isNotBlank()) text.append(meta.title).append(". ")

        db.rawQuery(
            """
            SELECT content FROM chunks
            WHERE document_id=? AND pdf_page BETWEEN ? AND ?
            ORDER BY pdf_page,chunk_index
            LIMIT 6
            """.trimIndent(),
            arrayOf(meta.documentId.toString(), meta.startPage.toString(), endPage.toString())
        ).use { c ->
            while (c.moveToNext()) text.append(c.getString(0)).append('\n')
        }
        return text.toString()
    }

    private data class TaskMeta(
        val documentId: Int,
        val startPage: Int,
        val endPage: Int,
        val title: String
    )

    override fun close() = db.close()
}
