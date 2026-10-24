package com.a340.technicalassistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_llm", Context.MODE_PRIVATE)

    fun installedModel(): File? {
        val path = prefs.getString("model_path", null) ?: return null
        return File(path).takeIf { it.isFile && it.length() > 0 }
    }

    fun importModel(uri: Uri, onProgress: (Long) -> Unit = {}): File {
        val displayName = queryName(uri) ?: "local_model.litertlm"
        require(displayName.lowercase().endsWith(".litertlm")) {
            "Select a LiteRT-LM .litertlm model file"
        }

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dst = File(dir, safeName)
        val tmp = File(dir, "$safeName.tmp")

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected model" }
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(4 * 1024 * 1024)
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
        require(tmp.length() > 8L * 1024 * 1024) { "Selected model file is unexpectedly small" }

        installedModel()?.takeIf { it.absolutePath != dst.absolutePath }?.delete()
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        prefs.edit().putString("model_path", dst.absolutePath).apply()
        return dst
    }

    private fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }
}
