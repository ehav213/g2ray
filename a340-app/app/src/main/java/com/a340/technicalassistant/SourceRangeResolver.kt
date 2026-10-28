package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

/** Resolves a technical source reference to the full PDF range that belongs to it. */
class SourceRangeResolver(dbFile: File) : AutoCloseable {
    data class SourceRange(
        val startPage: Int,
        val endPage: Int,
        val label: String
    )

    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun resolve(manual: String, reference: String, fallbackStart: Int): SourceRange {
        val m = manual.uppercase().trim()
        val ref = cleanReference(reference)
        return when {
            m == "TSM" || m == "AMM" -> taskRange(m, ref, fallbackStart)
            m == "MEL" -> melRange(ref, fallbackStart)
            else -> SourceRange(fallbackStart, fallbackStart, "SOURCE PAGE")
        }
    }

    private fun taskRange(manual: String, ref: String, fallback: Int): SourceRange {
        if (ref.isBlank()) return SourceRange(fallback, fallback, "$manual SOURCE PAGE")
        val sql = """
            SELECT CAST(t.document_id AS TEXT),d.filename,t.start_pdf_page
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))=? AND UPPER(t.task_ref)=?
            ORDER BY t.start_pdf_page LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(manual, ref.uppercase())).use { c ->
                if (!c.moveToFirst()) return@use SourceRange(fallback, fallback, "$manual SOURCE PAGE")
                val documentId = c.getString(0)
                val filename = c.getString(1)
                val start = c.getInt(2)
                val end = findTaskEnd(documentId, filename, start)
                SourceRange(start, end, "$manual TASK $ref")
            }
        }.getOrDefault(SourceRange(fallback, fallback, "$manual SOURCE PAGE"))
    }

    private fun findTaskEnd(documentId: String, filename: String, start: Int): Int {
        var nextStart: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT MIN(start_pdf_page) FROM task_entries WHERE CAST(document_id AS TEXT)=? AND start_pdf_page>?",
                arrayOf(documentId, start.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) nextStart = c.getInt(0) }
        }
        val max = minOf((nextStart?.minus(1) ?: start + 30), start + 30).coerceAtLeast(start)
        var end: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT pdf_page,content FROM chunks WHERE document_filename=? AND pdf_page BETWEEN ? AND ? ORDER BY pdf_page,chunk_id",
                arrayOf(filename, start.toString(), max.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(1).uppercase()
                    if (text.contains("END OF TASK") || text.contains("END TASK")) {
                        end = c.getInt(0)
                        break
                    }
                }
            }
        }
        return (end ?: max).coerceAtLeast(start)
    }

    private fun melRange(ref: String, fallback: Int): SourceRange {
        if (ref.isBlank()) return SourceRange(fallback, fallback, "MEL SOURCE PAGE")

        val item = runCatching {
            db.rawQuery(
                "SELECT CAST(document_id AS TEXT),start_pdf_page FROM mel_items WHERE UPPER(mel_ref)=? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(ref.uppercase())
            ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null }
        }.getOrNull()
        if (item != null) {
            val end = nextMelItemPage(item.first, item.second)
            return SourceRange(item.second, end, "MEL ITEM $ref")
        }

        val entry = runCatching {
            db.rawQuery(
                "SELECT CAST(document_id AS TEXT),start_pdf_page FROM mel_entries WHERE UPPER(COALESCE(ata_chapter,''))=? OR UPPER(COALESCE(title,'')) LIKE ? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(ref.uppercase(), "%${ref.uppercase()}%")
            ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null }
        }.getOrNull()
        if (entry != null) {
            val end = nextMelEntryPage(entry.first, entry.second)
            return SourceRange(entry.second, end, "MEL ENTRY $ref")
        }
        return SourceRange(fallback, fallback, "MEL SOURCE PAGE")
    }

    private fun nextMelItemPage(documentId: String, start: Int): Int {
        var next: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT MIN(start_pdf_page) FROM mel_items WHERE CAST(document_id AS TEXT)=? AND start_pdf_page>?",
                arrayOf(documentId, start.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) next = c.getInt(0) }
        }
        return (next?.minus(1) ?: start + 4).coerceIn(start, start + 8)
    }

    private fun nextMelEntryPage(documentId: String, start: Int): Int {
        var next: Int? = null
        runCatching {
            db.rawQuery(
                "SELECT MIN(start_pdf_page) FROM mel_entries WHERE CAST(document_id AS TEXT)=? AND start_pdf_page>?",
                arrayOf(documentId, start.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) next = c.getInt(0) }
        }
        return (next?.minus(1) ?: start + 6).coerceIn(start, start + 10)
    }

    private fun cleanReference(reference: String): String = reference.trim().removePrefix("TASK ").trim()

    override fun close() = db.close()
}
