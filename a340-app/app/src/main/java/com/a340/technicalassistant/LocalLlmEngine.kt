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
            backend = Backend.CPU(),
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
            You are A340 Grounded Maintenance Assistant.
            Use ONLY the supplied EVIDENCE. Never invent maintenance procedures, values, limits,
            torques, part numbers, effectivity, troubleshooting steps, or dispatch permission.
            Cite evidence labels such as [E1] after every technical statement.
            If the evidence does not support an answer, state exactly:
            INSUFFICIENT APPROVED DATA — CHECK SOURCE MANUAL.
            Do not treat ESPM marked INFORMATION ONLY as a primary maintenance reference.
            Never claim effectivity is verified unless SAFETY STATUS explicitly says it is verified.
            Prefer TSM for fault isolation, AMM for maintenance actions, MEL/CDL for dispatch,
            AWM/ASM for wiring/schematics, and IPC for parts.
            Answer in the user's language while keeping aircraft technical terms in English.
            Keep the answer concise and separate: Finding, Evidence, Next Reference.
            """.trimIndent()
        )

        val prompt = """
            USER QUESTION:
            $question

            SAFETY STATUS:
            $safetySummary

            EVIDENCE:
            $evidencePack

            Produce a concise grounded answer now.
        """.trimIndent()

        val conversation = active.createConversation(
            ConversationConfig(systemInstruction = systemInstruction)
        )

        try {
            conversation.sendMessageAsync(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isNotBlank()) onChunk(text)
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
                maxOutputToken = 384
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
