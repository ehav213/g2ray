package com.a340.technicalassistant.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Minimal cursor-style adapter used by SearchRepository.
 * It deliberately uses AndroidX bundled SQLite so FTS5 behavior is identical
 * on every supported Android device instead of depending on the OEM SQLite build.
 */
class FtsSQLiteDatabase private constructor(
    private val connection: SQLiteConnection
) : AutoCloseable {

    companion object {
        const val OPEN_READONLY: Int = 1
        private const val SQLITE_OPEN_FULLMUTEX: Int = 65536

        fun openDatabase(path: String, factory: Any?, flags: Int): FtsSQLiteDatabase {
            @Suppress("UNUSED_VARIABLE")
            val ignoredFactory = factory
            @Suppress("UNUSED_VARIABLE")
            val requestedReadOnly = flags and OPEN_READONLY != 0

            val openFlags = OPEN_READONLY or SQLITE_OPEN_FULLMUTEX
            return FtsSQLiteDatabase(BundledSQLiteDriver().open(path, openFlags))
        }
    }

    fun rawQuery(sql: String, selectionArgs: Array<String>?): FtsCursor {
        val statement = connection.prepare(sql)
        try {
            selectionArgs?.forEachIndexed { index, value ->
                statement.bindText(index + 1, value)
            }
            return FtsCursor(statement)
        } catch (t: Throwable) {
            statement.close()
            throw t
        }
    }

    override fun close() {
        connection.close()
    }
}

class FtsCursor internal constructor(
    private val statement: SQLiteStatement
) : AutoCloseable {

    fun moveToNext(): Boolean = statement.step()

    fun moveToFirst(): Boolean = statement.step()

    fun getString(index: Int): String = statement.getText(index)

    fun getInt(index: Int): Int = statement.getInt(index)

    fun getDouble(index: Int): Double = statement.getDouble(index)

    override fun close() {
        statement.close()
    }
}
