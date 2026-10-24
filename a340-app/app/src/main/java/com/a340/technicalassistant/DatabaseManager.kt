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
            val required = setOf(
                "master_metadata", "manual_registry", "global_search_fts", "documents", "pages",
                "cross_manual_links", "ipc_items", "awm_identifiers", "mel_items", "cdl_items", "espm_sections"
            )
            val found = mutableSetOf<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null).use { c ->
                while (c.moveToNext()) found += c.getString(0)
            }
            require(found.containsAll(required)) {
                "Database is incomplete or too old for V6 AI Retrieval"
            }

            val architecture = it.rawQuery(
                "SELECT value FROM master_metadata WHERE key='architecture_version' LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getString(0).toIntOrNull() ?: 0 else 0 }
            require(architecture >= 5) { "V6 requires A340 Technical Master architecture v5 or newer" }

            val requiredManuals = setOf("AMM", "TSM", "ASM", "AWM", "IPC", "MEL", "CDL", "ESPM")
            val integrated = mutableSetOf<String>()
            it.rawQuery("SELECT manual_code FROM manual_registry WHERE status='INTEGRATED'", null).use { c ->
                while (c.moveToNext()) integrated += c.getString(0)
            }
            require(integrated.containsAll(requiredManuals)) {
                "V6 requires AMM/TSM/ASM/AWM/IPC/MEL/CDL/ESPM integrated"
            }
        }
    }
}
