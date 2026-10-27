package com.a340.technicalassistant

import com.a340.technicalassistant.db.FtsSQLiteDatabase as SQLiteDatabase
import java.io.File

enum class MelDispatchStatus {
    GO_EXPLICIT,
    NO_GO_EXPLICIT,
    VERIFY_REQUIRED,
    NO_MATCH
}

data class LineMaintenanceResult(
    val analysis: QueryIntelligence.Analysis,
    val hits: List<SearchHit>,
    val melHits: List<SearchHit>,
    val dispatchStatus: MelDispatchStatus,
    val workflowSummary: String,
    val safetySummary: String,
    val includeEspm: Boolean,
    val graphLinksAdded: Int
)

/**
 * Line-maintenance retrieval sequence:
 * MEL -> TSM -> AMM -> ASM -> AWM -> IPC -> ESPM (only for wiring/ESPM intent).
 *
 * Dispatch classification is conservative: GO/NO-GO is emitted only when explicit
 * wording is present in the indexed MEL source text. Otherwise the app requires
 * verification of the original MEL entry.
 */
class LineMaintenanceWorkflow(
    dbFile: File,
    private val resolver: SearchRepository
) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun run(query: String, aircraftProfile: String): LineMaintenanceResult {
        val analysis = QueryIntelligence.analyze(query)
        val base = resolver.searchAi(query, aircraftProfile, limit = 120)
        val includeEspm = needsEspm(query, analysis)

        val mel = stageHits("MEL", query, aircraftProfile, 4, base.hits)
        val tsm = stageHits("TSM", query, aircraftProfile, 6, base.hits)
        val amm = stageHits("AMM", query, aircraftProfile, 4, base.hits)
        val asm = stageHits("ASM", query, aircraftProfile, 3, base.hits)
        val awm = stageHits("AWM", query, aircraftProfile, 3, base.hits)
        val ipc = stageHits("IPC", query, aircraftProfile, 3, base.hits)
        val espm = if (includeEspm) stageHits("ESPM", query, aircraftProfile, 3, base.hits) else emptyList()

        val dispatch = classifyMel(mel)
        val packet = linkedSetOf<String>()
        val ordered = mutableListOf<SearchHit>()

        fun addStage(label: String, source: List<SearchHit>) {
            source.forEach { hit ->
                val key = "${hit.entityType}|${hit.entityId}|${hit.manual}|${hit.reference}"
                if (packet.add(key)) {
                    ordered += hit.copy(title = "$label • ${hit.title.ifBlank { hit.entityType }}")
                }
            }
        }

        addStage("1 MEL CHECK", mel)
        addStage("2 TSM FAULT ISOLATION", tsm)
        addStage("3 AMM TASK", amm)
        addStage("4 ASM SCHEMATIC", asm)
        addStage("5 AWM WIRING", awm)
        addStage("6 IPC PART DATA", ipc)
        if (includeEspm) addStage("7 ESPM STANDARD PRACTICE", espm)

        val summary = buildWorkflowSummary(
            analysis = analysis,
            profile = aircraftProfile,
            mel = mel,
            dispatch = dispatch,
            tsm = tsm,
            amm = amm,
            asm = asm,
            awm = awm,
            ipc = ipc,
            espm = espm,
            includeEspm = includeEspm,
            safety = base.safetySummary
        )

        return LineMaintenanceResult(
            analysis = analysis,
            hits = ordered,
            melHits = mel,
            dispatchStatus = dispatch,
            workflowSummary = summary,
            safetySummary = base.safetySummary,
            includeEspm = includeEspm,
            graphLinksAdded = base.graphLinksAdded
        )
    }

    private fun stageHits(
        manual: String,
        query: String,
        profile: String,
        limit: Int,
        baseHits: List<SearchHit>
    ): List<SearchHit> {
        val direct = searchManual(manual, query, (limit * 5).coerceAtLeast(20))
        val fallback = baseHits.filter { it.manual.equals(manual, ignoreCase = true) }
        val merged = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        (direct + fallback).forEach { h ->
            val key = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
            if (seen.add(key)) merged += h
        }
        return merged.sortedWith(
            compareBy<SearchHit> { effectivityPenalty(it, profile) }
                .thenBy { it.score }
        ).take(limit)
    }

    private fun searchManual(manual: String, query: String, limit: Int): List<SearchHit> {
        val terms = Regex("[A-Za-z0-9][A-Za-z0-9_./-]*")
            .findAll(query.uppercase())
            .map { it.value }
            .distinct()
            .take(24)
            .toList()
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
        val sql = """
            SELECT entity_type,entity_id,COALESCE(manual,''),COALESCE(reference,''),COALESCE(title,''),
                   snippet(global_search_fts,5,'[',']','…',26), bm25(global_search_fts)
            FROM global_search_fts
            WHERE global_search_fts MATCH ? AND UPPER(manual)=?
            ORDER BY bm25(global_search_fts)
            LIMIT ?
        """.trimIndent()
        val out = mutableListOf<SearchHit>()
        db.rawQuery(sql, arrayOf(match, manual.uppercase(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                out += SearchHit(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getDouble(6)
                )
            }
        }
        return out
    }

    private fun classifyMel(melHits: List<SearchHit>): MelDispatchStatus {
        if (melHits.isEmpty()) return MelDispatchStatus.NO_MATCH
        val text = melHits.take(4).joinToString("\n") { hit ->
            val source = runCatching { resolver.resolve(hit) }.getOrNull()
            listOf(hit.title, hit.snippet, source?.content.orEmpty()).joinToString(" ")
        }.uppercase()

        val noGo = listOf(
            "NO GO", "NO-GO", "MUST BE OPERATIVE", "REQUIRED FOR DISPATCH",
            "DISPATCH NOT PERMITTED", "MAY NOT BE INOPERATIVE", "NOT PERMITTED TO BE INOPERATIVE"
        )
        if (noGo.any { text.contains(it) }) return MelDispatchStatus.NO_GO_EXPLICIT

        val go = listOf(
            "MAY BE INOPERATIVE", "MAY BE DEACTIVATED", "MAY BE MISSING",
            "DISPATCH PERMITTED", "MAY BE INOPERATIVE PROVIDED"
        )
        if (go.any { text.contains(it) }) return MelDispatchStatus.GO_EXPLICIT

        return MelDispatchStatus.VERIFY_REQUIRED
    }

    private fun effectivityPenalty(hit: SearchHit, profile: String): Int {
        if (profile.isBlank()) return 1
        val src = runCatching { resolver.resolve(hit) }.getOrNull() ?: return 1
        val applicability = runCatching {
            db.rawQuery(
                """
                SELECT COALESCE(p.effectivity_json,'')
                FROM documents d
                LEFT JOIN pages p ON p.document_id=d.document_id AND p.pdf_page=?
                WHERE d.filename=? LIMIT 1
                """.trimIndent(),
                arrayOf(src.pdfPage.toString(), src.filename)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        }.getOrDefault("")
        if (applicability.isBlank()) return 1
        val upper = applicability.uppercase()
        if (upper.contains("ALL")) return 0
        val tokens = Regex("[A-Z0-9-]{2,}")
            .findAll(profile.uppercase())
            .map { it.value }
            .toList()
        return if (tokens.any { upper.contains(it) }) 0 else 2
    }

    private fun needsEspm(query: String, analysis: QueryIntelligence.Analysis): Boolean {
        val u = query.uppercase()
        return analysis.intent == QueryIntelligence.Intent.WIRING ||
            analysis.intent == QueryIntelligence.Intent.STANDARD_PRACTICE ||
            listOf("ESPM", "WIRE", "WIRING", "SPLICE", "CRIMP", "CONTACT", "CONNECTOR")
                .any { u.contains(it) }
    }

    private fun buildWorkflowSummary(
        analysis: QueryIntelligence.Analysis,
        profile: String,
        mel: List<SearchHit>,
        dispatch: MelDispatchStatus,
        tsm: List<SearchHit>,
        amm: List<SearchHit>,
        asm: List<SearchHit>,
        awm: List<SearchHit>,
        ipc: List<SearchHit>,
        espm: List<SearchHit>,
        includeEspm: Boolean,
        safety: String
    ): String = buildString {
        append("LINE MAINTENANCE WORKFLOW")
        if (!analysis.ata.isNullOrBlank()) append(" • ATA ").append(analysis.ata)
        append("\n\n1 — MEL FIRST")
        when (dispatch) {
            MelDispatchStatus.GO_EXPLICIT -> append("\nMEL DISPATCH: GO wording detected — only if every MEL condition, (M)/(O) procedure and effectivity requirement is satisfied. Open the original MEL source below.")
            MelDispatchStatus.NO_GO_EXPLICIT -> append("\nMEL DISPATCH: NO-GO / operative requirement wording detected. Open the original MEL source below before any dispatch decision.")
            MelDispatchStatus.VERIFY_REQUIRED -> append("\nMEL ENTRY FOUND, but GO/NO-GO is not explicit in the indexed text. VERIFY ORIGINAL MEL ENTRY — AI must not infer dispatch permission.")
            MelDispatchStatus.NO_MATCH -> append("\nNO DIRECT MEL MATCH FOUND in the indexed MEL. This does NOT mean GO. Verify MEL applicability before dispatch.")
        }
        append("\nMEL sources: ").append(mel.size)

        append("\n\n2 — TSM FAULT ISOLATION\nRelevant TSM sources: ").append(tsm.size)
        append("\n3 — AMM MAINTENANCE TASKS\nRelevant AMM sources: ").append(amm.size)
        append("\n4 — ASM SCHEMATIC\nRelevant ASM sources: ").append(asm.size)
        append("\n5 — AWM WIRING\nRelevant AWM sources: ").append(awm.size)
        append("\n6 — IPC PART / FIGURE DATA\nRelevant IPC sources: ").append(ipc.size)
        if (includeEspm) append("\n7 — ESPM STANDARD PRACTICE\nRelevant ESPM sources: ").append(espm.size)
        else append("\n7 — ESPM\nNot searched unless wiring/connector/standard-practice context is requested.")

        append("\n\nAIRCRAFT EFFECTIVITY\n")
        if (profile.isBlank()) append("NOT VERIFIED — enter REG / MSN / effectivity before applying a task.")
        else append("Profile: ").append(profile).append(" — matching sources are ranked first; verify applicability in the source manual.")

        append("\n\nSOURCE DOCUMENT PACKET\nTap any source below to open the exact original PDF page. MEL is always listed first, followed by TSM, AMM, ASM, AWM, IPC, and ESPM when applicable.")
        append("\n\n").append(safety)
    }

    override fun close() = db.close()
}
