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
            You are an Airbus A340 grounded maintenance assistant.
            Use ONLY the supplied EVIDENCE.
            Never invent procedures, limits, values, part numbers, effectivity, or dispatch permission.
            Answer in the user's language; keep aircraft technical terms in English.
            Produce ONE concise answer only, maximum 35 words.
            Do NOT write headings, labels, bullet points, or repeat the question.
            Cite the supporting evidence at the end using [E1], [E2], or [E3].
            If the evidence does not support a useful answer, output exactly:
            INSUFFICIENT APPROVED DATA — CHECK SOURCE MANUAL.
            """.trimIndent()
        )

        val prompt = """
            QUESTION: $question
            SAFETY: $safetySummary
            EVIDENCE:
            $evidencePack
            Answer only with the concise grounded result.
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
                                .replace("Finding:", "", ignoreCase = true)
                                .replace("Evidence:", "", ignoreCase = true)
                                .replace("Next Reference:", "", ignoreCase = true)
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
                maxOutputToken = 48
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
