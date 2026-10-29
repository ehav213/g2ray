package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File
import java.util.Locale

/**
 * Resolves verified database entities to their exact original-PDF page ranges.
 *
 * Some imported Technical Master databases contain a valid task start page but an end_pdf_page
 * equal to the start page even when the task continues. For TSM/AMM we therefore validate the
 * stored range and, when it is collapsed, infer the task end from the next task in the same PDF.
 * This keeps the original manual authoritative while allowing maintenance to follow every page
 * of the selected procedure instead of opening only its first page.
 */
class SourceRangeResolver(dbFile: File) : AutoCloseable {
    data class SourceRange(val startPage: Int, val endPage: Int, val label: String)

    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun resolve(manual: String, reference: String, fallbackStart: Int): SourceRange {
        val m = manual.uppercase(Locale.US).trim()
        val ref = reference.trim().removePrefix("TASK ").trim()
        return when (m) {
            "TSM", "AMM" -> taskRange(m, ref, fallbackStart)
            "MEL" -> melRange(ref, fallbackStart)
            else -> SourceRange(fallbackStart, fallbackStart, "$m SOURCE PAGE")
        }
    }

    private fun taskRange(manual: String, ref: String, fallback: Int): SourceRange {
        if (ref.isBlank()) return SourceRange(fallback, fallback, "$manual SOURCE PAGE")
        val clean = ref.uppercase(Locale.US)
        val sql = """
            SELECT t.task_id,t.document_id,t.task_ref,t.start_pdf_page,
                   COALESCE(t.end_pdf_page,t.start_pdf_page)
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))=?
              AND (UPPER(t.task_ref)=? OR UPPER(t.task_ref) LIKE ?)
            ORDER BY CASE WHEN UPPER(t.task_ref)=? THEN 0 ELSE 1 END,t.start_pdf_page
            LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(manual, clean, "$clean-%", clean)).use { c ->
                if (!c.moveToFirst()) return@use SourceRange(fallback, fallback, "$manual SOURCE PAGE")

                val taskId = c.getString(0)
                val documentId = c.getLong(1)
                val taskRef = c.getString(2)
                val start = c.getInt(3).coerceAtLeast(1)
                val storedEnd = c.getInt(4).coerceAtLeast(start)
                val end = validatedTaskEnd(taskId, documentId, start, storedEnd)
                SourceRange(start, end, "$manual TASK $taskRef")
            }
        }.getOrDefault(SourceRange(fallback, fallback, "$manual SOURCE PAGE"))
    }

    private fun validatedTaskEnd(taskId: String, documentId: Long, start: Int, storedEnd: Int): Int {
        // A real multi-page range from the database is authoritative.
        if (storedEnd > start) return storedEnd

        // If another task starts later in the same PDF, the current task runs up to the page
        // immediately before it. This repairs imported rows whose end page collapsed to start.
        val nextStart = runCatching {
            db.rawQuery(
                """
                    SELECT MIN(start_pdf_page)
                    FROM task_entries
                    WHERE document_id=? AND task_id<>? AND start_pdf_page>?
                """.trimIndent(),
                arrayOf(documentId.toString(), taskId, start.toString())
            ).use { c ->
                if (!c.moveToFirst() || c.isNull(0)) null else c.getInt(0)
            }
        }.getOrNull()

        if (nextStart != null && nextStart > start) return (nextStart - 1).coerceAtLeast(start)

        // Last task in a source PDF: use the last indexed page of that same document.
        val documentLastPage = runCatching {
            db.rawQuery(
                "SELECT MAX(pdf_page) FROM pages WHERE document_id=?",
                arrayOf(documentId.toString())
            ).use { c ->
                if (!c.moveToFirst() || c.isNull(0)) null else c.getInt(0)
            }
        }.getOrNull()

        return documentLastPage?.coerceAtLeast(start) ?: start
    }

    private fun melRange(ref: String, fallback: Int): SourceRange {
        if (ref.isBlank()) return SourceRange(fallback, fallback, "MEL SOURCE PAGE")
        val upper = ref.uppercase(Locale.US)

        runCatching {
            db.rawQuery(
                "SELECT mel_ref,start_pdf_page,end_pdf_page FROM mel_items WHERE UPPER(COALESCE(mel_ref,''))=? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(upper)
            ).use { c ->
                if (c.moveToFirst()) return SourceRange(c.getInt(1), c.getInt(2).coerceAtLeast(c.getInt(1)), "MEL ITEM ${c.getString(0)}")
            }
        }

        runCatching {
            db.rawQuery(
                "SELECT procedure_ref,start_pdf_page,end_pdf_page FROM mel_procedures WHERE UPPER(COALESCE(procedure_ref,''))=? OR UPPER(COALESCE(procedure_ref,'')) LIKE ? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(upper, "$upper%")
            ).use { c ->
                if (c.moveToFirst()) return SourceRange(c.getInt(1), c.getInt(2).coerceAtLeast(c.getInt(1)), "MEL PROCEDURE ${c.getString(0)}")
            }
        }

        runCatching {
            db.rawQuery(
                "SELECT title,start_pdf_page,end_pdf_page FROM mel_entries WHERE UPPER(TRIM(title))=? ORDER BY start_pdf_page LIMIT 1",
                arrayOf(upper)
            ).use { c ->
                if (c.moveToFirst()) return SourceRange(c.getInt(1), c.getInt(2).coerceAtLeast(c.getInt(1)), "MEL ENTRY ${c.getString(0).trim()}")
            }
        }
        return SourceRange(fallback, fallback, "MEL SOURCE PAGE")
    }

    override fun close() = db.close()
}
