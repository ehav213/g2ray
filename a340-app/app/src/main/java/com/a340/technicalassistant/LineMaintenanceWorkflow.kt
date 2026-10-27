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
 * MEL lookup is equipment/item based, not fault-phrase based. A reported fault such
 * as "APU AUTO SHUTDOWN" is first normalized to the dispatch equipment item APU.
 * Where an approved canonical MEL mapping is known, that MEL item is searched first.
 */
class LineMaintenanceWorkflow(
    dbFile: File,
    private val resolver: SearchRepository
) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private data class MelTarget(
        val coreTerms: List<String>,
        val canonicalQueries: List<String>,
        val preferredReferences: List<String>,
        val description: String
    )

    fun run(query: String, aircraftProfile: String): LineMaintenanceResult {
        val analysis = QueryIntelligence.analyze(query)
        val base = resolver.searchAi(query, aircraftProfile, limit = 120)
        val includeEspm = needsEspm(query, analysis)

        val mel = melStageHits(query, aircraftProfile, analysis, base.hits, 4)
        val tsm = stageHits("TSM", query, aircraftProfile, 6, base.hits)
        val amm = stageHits("AMM", query, aircraftProfile, 4, base.hits)
        val asm = stageHits("ASM", query, aircraftProfile, 3, base.hits)
        val awm = stageHits("AWM", query, aircraftProfile, 3, base.hits)
        val ipc = stageHits("IPC", query, aircraftProfile, 3, base.hits)
        val espm = if (includeEspm) stageHits("ESPM", query, aircraftProfile, 3, base.hits) else emptyList()

        val dispatch = classifyMel(mel, aircraftProfile, query, analysis)
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
            safety = base.safetySummary,
            query = query
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

    private fun melTarget(query: String, analysis: QueryIntelligence.Analysis): MelTarget {
        val upper = query.uppercase()

        // Approved mapping supplied for the operator MEL: any APU dispatch concern is
        // first checked against MEL 49-10-01 Power Plant (APU). Fault-isolation wording
        // such as AUTO SHUTDOWN remains a TSM concern and must not replace the MEL item.
        if (upper.contains("APU") || analysis.ata == "49") {
            return MelTarget(
                coreTerms = listOf("APU", "POWER PLANT"),
                canonicalQueries = listOf(
                    "49-10-01",
                    "POWER PLANT APU",
                    "APU"
                ),
                preferredReferences = listOf("49-10-01", "49-10-01A", "49-10-01B"),
                description = "MEL 49-10-01 — Power Plant (APU)"
            )
        }

        val core = melCoreTerms(query, analysis)
        return MelTarget(
            coreTerms = core,
            canonicalQueries = listOf(query),
            preferredReferences = emptyList(),
            description = core.joinToString(" / ").ifBlank { "affected equipment item" }
        )
    }

    /**
     * MEL is searched from the equipment/item level. Canonical item queries are run
     * before the raw fault wording. Example: APU AUTO SHUTDOWN -> MEL 49-10-01 Power
     * Plant (APU), while AUTO SHUTDOWN itself is used for the subsequent TSM stage.
     */
    private fun melStageHits(
        query: String,
        profile: String,
        analysis: QueryIntelligence.Analysis,
        baseHits: List<SearchHit>,
        limit: Int
    ): List<SearchHit> {
        val target = melTarget(query, analysis)
        val canonical = target.canonicalQueries.flatMap { searchManual("MEL", it, 50) }
        val raw = searchManual("MEL", query, 50)
        val fallback = baseHits.filter { it.manual.equals("MEL", ignoreCase = true) }

        val merged = mutableListOf<SearchHit>()
        val seen = linkedSetOf<String>()
        (canonical + raw + fallback).forEach { h ->
            val key = "${h.entityType}|${h.entityId}|${h.manual}|${h.reference}"
            if (seen.add(key)) merged += h
        }

        return merged
            .map { it to melRelevanceScore(it, query, analysis, target) }
            .filter { (_, score) -> score < 1000 }
            .sortedWith(
                compareBy<Pair<SearchHit, Int>> { it.second }
                    .thenBy { effectivityPenalty(it.first, profile) }
                    .thenBy { it.first.score }
            )
            .map { it.first }
            .take(limit)
    }

    private fun melCoreTerms(query: String, analysis: QueryIntelligence.Analysis): List<String> {
        val generic = setOf(
            "AUTO", "AUTOMATIC", "FAULT", "FAIL", "FAILED", "FAILURE", "WARNING", "CAUTION",
            "MESSAGE", "ECAM", "PFR", "REPORT", "TECH", "LOG", "INOP", "INOPERATIVE",
            "SHUTDOWN", "SHUT", "DOWN", "TRIP", "RESET", "ATA", "SYSTEM"
        )
        val raw = Regex("[A-Z0-9][A-Z0-9_./-]*")
            .findAll(query.uppercase())
            .map { it.value }
            .filter { it.length >= 2 && it !in generic && !it.matches(Regex("\\d{2}")) }
            .distinct()
            .toMutableList()

        if (query.uppercase().contains("APU") && "APU" !in raw) raw += "APU"
        if (analysis.ata == "49" && raw.isEmpty()) raw += "APU"
        return raw.take(8)
    }

    private fun melRelevanceScore(
        hit: SearchHit,
        query: String,
        analysis: QueryIntelligence.Analysis,
        target: MelTarget
    ): Int {
        val resolved = runCatching { resolver.resolve(hit) }.getOrNull()
        val hay = listOf(hit.reference, hit.title, hit.snippet, resolved?.content.orEmpty())
            .joinToString(" ")
            .uppercase()
            .replace(Regex("\\s+"), " ")

        val normalizedQuery = query.uppercase().replace(Regex("\\s+"), " ").trim()
        val exactPhrase = normalizedQuery.length >= 6 && hay.contains(normalizedQuery)

        val ata = analysis.ata
        if (!ata.isNullOrBlank()) {
            val explicitAta = Regex("(?:ATA\\s*)?(\\d{2})[- ./]").find(hay)?.groupValues?.getOrNull(1)
            if (explicitAta != null && explicitAta != ata) return 1000
        }

        // Canonical MEL item references outrank all semantic candidates. For APU this
        // makes 49-10-01 / 49-10-01A / 49-10-01B the dispatch item shown first.
        val refUpper = hit.reference.uppercase()
        val preferredIndex = target.preferredReferences.indexOfFirst { pref ->
            refUpper.startsWith(pref) || hay.contains(pref)
        }
        if (preferredIndex >= 0) return -500 + preferredIndex

        val coreTerms = target.coreTerms
        if (coreTerms.isNotEmpty()) {
            val matched = coreTerms.count { term ->
                term.split(' ').all { token -> containsWholeTerm(hay, token) }
            }
            if (matched == 0) return 1000

            // APU canonical mapping may be titled simply "Power Plant (APU)"; one
            // canonical equipment term is enough after ATA 49 has already matched.
            val required = if (analysis.ata == "49" || query.uppercase().contains("APU")) 1
            else if (coreTerms.size <= 2) 1 else (coreTerms.size + 1) / 2
            if (matched < required) return 1000

            var score = (coreTerms.size - matched) * 20
            if (exactPhrase) score -= 100
            val titleWords = Regex("[A-Z0-9][A-Z0-9_./-]*").findAll(hit.title.uppercase()).count()
            score += titleWords.coerceAtMost(20)
            return score
        }

        return if (exactPhrase) 0 else 1000
    }

    private fun containsWholeTerm(text: String, term: String): Boolean {
        val p = "(^|[^A-Z0-9])${Regex.escape(term.uppercase())}([^A-Z0-9]|$)"
        return Regex(p).containsMatchIn(text)
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

    private fun classifyMel(
        melHits: List<SearchHit>,
        profile: String,
        query: String,
        analysis: QueryIntelligence.Analysis
    ): MelDispatchStatus {
        if (melHits.isEmpty()) return MelDispatchStatus.NO_MATCH
        if (profile.isBlank()) return MelDispatchStatus.VERIFY_REQUIRED

        val target = melTarget(query, analysis)
        val eligible = melHits.filter {
            effectivityPenalty(it, profile) == 0 && melRelevanceScore(it, query, analysis, target) < 1000
        }
        if (eligible.isEmpty()) return MelDispatchStatus.VERIFY_REQUIRED

        val text = eligible.take(3).joinToString("\n") { hit ->
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
        safety: String,
        query: String
    ): String = buildString {
        val target = melTarget(query, analysis)
        append("LINE MAINTENANCE WORKFLOW")
        if (!analysis.ata.isNullOrBlank()) append(" • ATA ").append(analysis.ata)
        append("\n\n1 — MEL FIRST")
        append("\nDispatch item target: ").append(target.description)
        append("\nFault wording is used to identify the affected equipment; MEL is then checked by equipment/item, not by requiring the MEL title to repeat the fault message.")
        when (dispatch) {
            MelDispatchStatus.GO_EXPLICIT -> append("\nMEL DISPATCH: GO wording detected in a relevance- and effectivity-matched MEL candidate — only if every MEL condition, (M)/(O) procedure and limitation is satisfied. Open the original MEL source below.")
            MelDispatchStatus.NO_GO_EXPLICIT -> append("\nMEL DISPATCH: NO-GO / operative requirement wording detected in a relevance- and effectivity-matched MEL candidate. Open the original MEL source below before any dispatch decision.")
            MelDispatchStatus.VERIFY_REQUIRED -> append("\nMEL CHECK REQUIRES ORIGINAL SOURCE VERIFICATION — effectivity may be missing/unmatched or the candidate is not strong enough for an automatic dispatch decision. AI must not infer dispatch permission.")
            MelDispatchStatus.NO_MATCH -> append("\nNO RELEVANT MEL ITEM MATCHED THE AFFECTED EQUIPMENT. This does NOT mean GO. Verify MEL applicability before dispatch.")
        }
        append("\nRelevant MEL sources: ").append(mel.size)

        append("\n\n2 — TSM FAULT ISOLATION\nRelevant TSM sources: ").append(tsm.size)
        append("\n3 — AMM MAINTENANCE TASKS\nRelevant AMM sources: ").append(amm.size)
        append("\n4 — ASM SCHEMATIC\nRelevant ASM sources: ").append(asm.size)
        append("\n5 — AWM WIRING\nRelevant AWM sources: ").append(awm.size)
        append("\n6 — IPC PART / FIGURE DATA\nRelevant IPC sources: ").append(ipc.size)
        if (includeEspm) append("\n7 — ESPM STANDARD PRACTICE\nRelevant ESPM sources: ").append(espm.size)
        else append("\n7 — ESPM\nNot searched unless wiring/connector/standard-practice context is requested.")

        append("\n\nAIRCRAFT EFFECTIVITY\n")
        if (profile.isBlank()) append("NOT VERIFIED — enter REG / MSN / effectivity before applying a task or accepting a MEL dispatch status.")
        else append("Profile: ").append(profile).append(" — matching sources are ranked first; verify applicability in the source manual.")

        append("\n\nSOURCE DOCUMENT PACKET\nTap any source below to open the exact original PDF page. MEL is always listed first, followed by TSM, AMM, ASM, AWM, IPC, and ESPM when applicable. Use SAVE / SHARE SOURCE PAGE PDF to separate the displayed source page.")
        append("\n\n").append(safety)
    }

    override fun close() = db.close()
}
