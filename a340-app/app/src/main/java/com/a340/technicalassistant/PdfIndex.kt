package com.a340.technicalassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class PdfIndex(context: Context) : SQLiteOpenHelper(context, "pdf_index.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pdf_files(filename TEXT PRIMARY KEY COLLATE NOCASE, uri TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun rebuild(context: Context, treeUri: Uri): Int {
        writableDatabase.execSQL("DELETE FROM pdf_files")
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        var count = 0
        writableDatabase.beginTransaction()
        try {
            fun walk(dir: DocumentFile) {
                dir.listFiles().forEach { f ->
                    if (f.isDirectory) walk(f)
                    else if (f.name?.endsWith(".pdf", ignoreCase = true) == true) {
                        writableDatabase.execSQL("INSERT OR REPLACE INTO pdf_files(filename,uri) VALUES(?,?)", arrayOf(f.name, f.uri.toString()))
                        count++
                    }
                }
            }
            walk(root)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return count
    }

    fun find(filename: String): Uri? {
        readableDatabase.rawQuery("SELECT uri FROM pdf_files WHERE filename=? COLLATE NOCASE LIMIT 1", arrayOf(filename)).use { c ->
            return if (c.moveToFirst()) Uri.parse(c.getString(0)) else null
        }
    }
}
