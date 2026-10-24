package com.a340.technicalassistant

data class ManualInfo(
    val code: String,
    val name: String,
    val status: String,
    val documents: Int,
    val pages: Int,
    val images: Int
)

data class SearchHit(
    val entityType: String,
    val entityId: String,
    val manual: String,
    val reference: String,
    val title: String,
    val snippet: String,
    val score: Double
) {
    override fun toString(): String = buildString {
        append("[").append(manual).append("] ")
        if (manual == "ESPM") append("INFO ONLY • ")
        if (reference.isNotBlank()) append(reference).append(" — ")
        append(if (title.isNotBlank()) title else entityType)
        append("\n").append(snippet.replace("\n", " ").take(260))
    }
}

data class ResolvedSource(
    val manual: String,
    val filename: String,
    val pdfPage: Int,
    val reference: String,
    val title: String,
    val content: String = ""
)
