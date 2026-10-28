package com.a340.technicalassistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File

class LocalLlmEngine(private val context: Context) : AutoCloseable {
    private var engine: Engine? = null
    private var loadedPath: String? = null

    fun isLoaded(): Boolean = engine?.isInitialized() == true
    fun loadedModelName(): String? = loadedPath?.let { File(it).name }

    fun load(modelFile: File) {
        require(modelFile.isFile) { "Local LLM model not found" }
        if (isLoaded() && loadedPath == modelFile.absolutePath) return

        close()
        val cache = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(threadCount = 4),
            cacheDir = cache.absolutePath
        )
        val candidate = Engine(config)
        try {
            candidate.initialize()
            engine = candidate
            loadedPath = modelFile.absolutePath
        } catch (t: Throwable) {
            runCatching { candidate.close() }
            throw IllegalStateException("Local LLM could not initialize: ${t.message}", t)
        }
    }

    /**
     * Candidate selector used only after deterministic database retrieval has produced
     * real TSM task references. The model is forbidden to create a new reference.
     */
    fun selectCandidate(
        fault: String,
        candidates: List<SearchHit>,
        onResult: (String?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val active = engine ?: error("Load a local .litertlm model first")
        require(candidates.isNotEmpty()) { "No verified candidates supplied" }

        val allowed = candidates.take(8)
        val allowedRefs = allowed.map { it.reference.trim() }.filter { it.isNotBlank() }
        val systemInstruction = Contents.of(
            """
            You are a constrained Airbus A340 TSM candidate ranker.
            You may ONLY select one task reference that appears verbatim in CANDIDATES.
            Do not invent, alter, complete, or infer a task reference.
            Match abbreviations semantically: PROX=PROXIMITY, SNSR/SNSRS=SENSOR/SENSORS.
            Prefer exact component identifiers such as 39GA1, 39GA2, LGCIU1, LGCIU2.
            If the evidence is ambiguous, output NONE.
            Output only the selected task reference or NONE. No explanation.
            """.trimIndent()
        )
        val candidateText = allowed.mapIndexed { i, h ->
            "C${i + 1}: ${h.reference}\nTITLE: ${h.title}\nEVIDENCE: ${h.snippet.replace(Regex("\\s+"), " ").take(420)}"
        }.joinToString("\n\n")
        val prompt = "FAULT:\n$fault\n\nCANDIDATES:\n$candidateText\n\nReturn one allowed reference or NONE."
        val conversation = active.createConversation(ConversationConfig(systemInstruction = systemInstruction))
        val accumulated = StringBuilder()

        try {
            conversation.sendMessageAsync(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isBlank()) return
                        synchronized(accumulated) {
                            val previous = accumulated.toString()
                            when {
                                previous.isNotEmpty() && text.startsWith(previous) -> {
                                    accumulated.setLength(0); accumulated.append(text)
                                }
                                previous.endsWith(text) -> Unit
                                else -> accumulated.append(text)
                            }
                        }
                    }

                    override fun onDone() {
                        runCatching { conversation.close() }
                        val raw = accumulated.toString().uppercase()
                        val selected = allowedRefs.firstOrNull { ref -> raw.contains(ref.uppercase()) }
                        onResult(selected)
                    }

                    override fun onError(throwable: Throwable) {
                        runCatching { conversation.close() }
                        onError(throwable)
                    }
                },
                maxOutputToken = 32
            )
        } catch (t: Throwable) {
            runCatching { conversation.close() }
            onError(t)
        }
    }

    fun answerStreaming(
        question: String,
        evidencePack: String,
        safetySummary: String,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val active = engine ?: error("Load a local .litertlm model first")
        require(evidencePack.isNotBlank()) { "No approved evidence available for grounded answer" }

        val systemInstruction = Contents.of(
            """
            You are an Airbus A340 line-maintenance assistant.
            Use ONLY the supplied EVIDENCE. The source manuals remain authoritative.
            Never invent procedures, limits, values, part numbers, effectivity, or dispatch permission.
            MEL is always evaluated before troubleshooting. State GO or NO-GO only when the supplied MEL evidence explicitly supports it; otherwise state VERIFY ORIGINAL MEL ENTRY.
            Then summarize the relevant TSM fault-isolation direction and the supporting AMM/ASM/AWM/IPC evidence. Use ESPM only when ESPM evidence is supplied for wiring or standard practice.
            Answer in the user's language while keeping aircraft technical terms and manual references in English.
            Keep the answer practical and concise, normally 80-160 words.
            Use these headings when supported by evidence:
            MEL STATUS
            TSM
            TECHNICAL SUPPORT
            NEXT ACTION
            SOURCES
            Cite evidence IDs such as [E1], [E2].
            If the evidence is insufficient, say exactly what is missing and direct the user to the original source manual.
            """.trimIndent()
        )

        val prompt = """
            QUESTION: $question
            SAFETY / EFFECTIVITY: $safetySummary
            APPROVED EVIDENCE:
            $evidencePack

            Produce the grounded line-maintenance answer. Do not create any maintenance step that is absent from the evidence.
        """.trimIndent()

        val conversation = active.createConversation(
            ConversationConfig(systemInstruction = systemInstruction)
        )
        val accumulated = StringBuilder()

        try {
            conversation.sendMessageAsync(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isBlank()) return
                        synchronized(accumulated) {
                            val previous = accumulated.toString()
                            when {
                                previous.isNotEmpty() && text.startsWith(previous) -> {
                                    accumulated.setLength(0)
                                    accumulated.append(text)
                                }
                                previous.endsWith(text) -> Unit
                                else -> accumulated.append(text)
                            }
                            val clean = accumulated.toString()
                                .replace(Regex("[ \\t]{2,}"), " ")
                                .replace(Regex("\\n{3,}"), "\n\n")
                                .trimStart()
                            onChunk(clean)
                        }
                    }

                    override fun onDone() {
                        runCatching { conversation.close() }
                        onDone()
                    }

                    override fun onError(throwable: Throwable) {
                        runCatching { conversation.close() }
                        onError(throwable)
                    }
                },
                maxOutputToken = 160
            )
        } catch (t: Throwable) {
            runCatching { conversation.close() }
            onError(t)
        }
    }

    override fun close() {
        val e = engine
        engine = null
        loadedPath = null
        if (e != null && e.isInitialized()) runCatching { e.close() }
    }
}
