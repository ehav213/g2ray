package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File
import java.util.Locale

/** Resolves verified database entities to their exact original-PDF page ranges. */
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
            SELECT t.task_ref,t.start_pdf_page,COALESCE(t.end_pdf_page,t.start_pdf_page)
            FROM task_entries t JOIN documents d ON d.document_id=t.document_id
            WHERE UPPER(COALESCE(d.manual,''))=?
              AND (UPPER(t.task_ref)=? OR UPPER(t.task_ref) LIKE ?)
            ORDER BY CASE WHEN UPPER(t.task_ref)=? THEN 0 ELSE 1 END,t.start_pdf_page
            LIMIT 1
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, arrayOf(manual, clean, "$clean-%", clean)).use { c ->
                if (!c.moveToFirst()) return@use SourceRange(fallback, fallback, "$manual SOURCE PAGE")
                SourceRange(c.getInt(1), c.getInt(2).coerceAtLeast(c.getInt(1)), "$manual TASK ${c.getString(0)}")
            }
        }.getOrDefault(SourceRange(fallback, fallback, "$manual SOURCE PAGE"))
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
