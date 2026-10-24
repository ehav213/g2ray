package com.a340.technicalassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

class DatabaseManager(private val context: Context) {
    companion object { private const val DB_NAME = "A340_TECHNICAL_MASTER.sqlite" }

    fun databaseFile(): File {
        val candidates = context.getExternalFilesDirs("database").filterNotNull()
        val removable = candidates.firstOrNull { Environment.isExternalStorageRemovable(it) }
        val base = removable ?: candidates.firstOrNull() ?: File(context.filesDir, "database")
        if (!base.exists()) base.mkdirs()
        return File(base, DB_NAME)
    }

    fun importDatabase(uri: Uri, onProgress: (Long) -> Unit = {}): File {
        val dst = databaseFile()
        val tmp = File(dst.parentFile, "$DB_NAME.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected database" }
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    total += n
                    if (total % (64L * 1024 * 1024) < buffer.size) onProgress(total)
                }
                output.fd.sync()
            }
        }
        checkSqlite(tmp)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        return dst
    }

    private fun checkSqlite(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val required = setOf("master_metadata", "manual_registry", "global_search_fts", "documents", "pages")
            val found = mutableSetOf<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null).use { c ->
                while (c.moveToNext()) found += c.getString(0)
            }
            require(found.containsAll(required)) { "Selected file is not a compatible A340 Technical Master database" }
        }
    }
}
