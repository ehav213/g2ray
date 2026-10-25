package com.a340.technicalassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SearchLearningStore(context: Context) :
    SQLiteOpenHelper(context, "search_learning.sqlite", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE confirmations(
                query_signature TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                manual TEXT NOT NULL,
                reference TEXT NOT NULL,
                confirm_count INTEGER NOT NULL DEFAULT 1,
                last_confirmed INTEGER NOT NULL,
                PRIMARY KEY(query_signature,entity_type,entity_id,manual,reference)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun confirm(query: String, hit: SearchHit) {
        val signature = signature(query)
        if (signature.isBlank()) return
        writableDatabase.execSQL(
            """
            INSERT INTO confirmations(query_signature,entity_type,entity_id,manual,reference,confirm_count,last_confirmed)
            VALUES(?,?,?,?,?,1,?)
            ON CONFLICT(query_signature,entity_type,entity_id,manual,reference)
            DO UPDATE SET confirm_count=confirm_count+1,last_confirmed=excluded.last_confirmed
            """.trimIndent(),
            arrayOf<Any?>(
                signature,
                hit.entityType,
                hit.entityId,
                hit.manual,
                hit.reference,
                System.currentTimeMillis()
            )
        )
    }

    fun rerank(query: String, hits: List<SearchHit>): Pair<List<SearchHit>, Int> {
        if (hits.isEmpty()) return hits to 0
        val q = tokens(query)
        if (q.isEmpty()) return hits to 0

        data class Learned(
            val type: String,
            val id: String,
            val manual: String,
            val ref: String,
            val strength: Double
        )

        val learned = mutableListOf<Learned>()
        readableDatabase.rawQuery(
            """
            SELECT query_signature,entity_type,entity_id,manual,reference,confirm_count
            FROM confirmations ORDER BY last_confirmed DESC LIMIT 300
            """.trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val prior = c.getString(0).split(' ').filter { it.isNotBlank() }.toSet()
                val sim = jaccard(q, prior)
                if (sim >= 0.55) {
                    val count = c.getInt(5).coerceAtMost(5)
                    learned += Learned(
                        c.getString(1), c.getString(2), c.getString(3), c.getString(4), sim * count
                    )
                }
            }
        }
        if (learned.isEmpty()) return hits to 0

        fun boost(h: SearchHit): Double = learned
            .filter {
                it.type == h.entityType && it.id == h.entityId &&
                    it.manual == h.manual && it.ref == h.reference
            }
            .maxOfOrNull { it.strength } ?: 0.0

        val scored = hits.mapIndexed { index, hit -> Triple(hit, boost(hit), index) }
        val boostedCount = scored.count { it.second > 0.0 }
        val ranked = scored.sortedWith(
            compareByDescending<Triple<SearchHit, Double, Int>> { it.second }
                .thenBy { it.third }
        ).map { it.first }
        return ranked to boostedCount
    }

    fun totalConfirmations(): Int =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(confirm_count),0) FROM confirmations", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun signature(text: String): String = tokens(text).sorted().joinToString(" ")

    private fun tokens(text: String): Set<String> {
        val stop = setOf("THE", "A", "AN", "IS", "ARE", "OF", "TO", "IN", "ON", "AND", "OR", "FOR", "WITH")
        return Regex("[\\p{L}\\p{N}/.-]+")
            .findAll(text.uppercase())
            .map { it.value.trim('.', '-', '/') }
            .filter { it.length >= 2 && it !in stop }
            .take(40)
            .toSet()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }
}
