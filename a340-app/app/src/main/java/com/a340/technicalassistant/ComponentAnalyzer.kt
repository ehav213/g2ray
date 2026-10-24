package com.a340.technicalassistant

object ComponentAnalyzer {
    data class Result(
        val rawText: String,
        val identifiers: List<String>
    ) {
        val query: String get() = identifiers.joinToString(" ")
    }

    private val structured = Regex("\\b[A-Z0-9]{2,}(?:[-/.][A-Z0-9]{2,}){1,4}\\b")
    private val finLike = Regex("\\b\\d{1,4}[A-Z]{1,3}\\b")
    private val alphaNum = Regex("\\b(?=[A-Z0-9]{4,20}\\b)(?=.*[A-Z])(?=.*\\d)[A-Z0-9]+\\b")

    fun analyze(text: String): Result {
        val upper = text.uppercase()
            .replace('—', '-')
            .replace('–', '-')

        val ids = linkedSetOf<String>()
        structured.findAll(upper).forEach { ids += clean(it.value) }
        finLike.findAll(upper).forEach { ids += clean(it.value) }
        alphaNum.findAll(upper).forEach { ids += clean(it.value) }

        val filtered = ids
            .filter { it.length in 3..24 }
            .filterNot { it.matches(Regex("\\d+")) }
            .filterNot { it in setOf("A340", "AIRBUS", "SERIAL", "MODEL", "PART", "NUMBER") }
            .take(12)

        return Result(text.trim(), filtered)
    }

    private fun clean(s: String): String = s.trim().trim('.', ',', ':', ';', '(', ')', '[', ']')
}
