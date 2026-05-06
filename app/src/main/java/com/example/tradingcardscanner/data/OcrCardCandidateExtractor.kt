package com.example.tradingcardscanner.data

data class OcrCardCandidate(
    val possibleName: String?,
    val possibleNumber: String?,
    val confidenceScore: Int
) {
    val isReliable: Boolean
        get() = confidenceScore >= RELIABLE_MATCH_THRESHOLD

    companion object {
        const val RELIABLE_MATCH_THRESHOLD = 70
    }
}

class OcrCardCandidateExtractor {
    fun extract(rawText: String): OcrCardCandidate {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val possibleName = lines.firstOrNull(::looksLikeCardName)
        val possibleNumber = lines.firstNotNullOfOrNull(::extractCardNumber)
        val score = confidenceFor(lines, possibleName, possibleNumber)

        return OcrCardCandidate(
            possibleName = possibleName,
            possibleNumber = possibleNumber,
            confidenceScore = score
        )
    }

    private fun looksLikeCardName(line: String): Boolean {
        val normalized = line.lowercase()
        if (line.length !in 3..32) return false
        if (!line.any(Char::isLetter)) return false
        if (line.count(Char::isDigit) > 2) return false
        if (ignoredLineFragments.any { normalized.contains(it) }) return false
        if (cardNumberPattern.containsMatchIn(line)) return false
        return true
    }

    private fun extractCardNumber(line: String): String? {
        return cardNumberPattern.find(line)?.value
            ?: labeledLocalNumberPattern.find(line)?.value
    }

    private fun confidenceFor(
        lines: List<String>,
        possibleName: String?,
        possibleNumber: String?
    ): Int {
        var score = 0
        if (possibleName != null) score += 35
        if (possibleNumber != null) score += 35
        if (lines.size >= 4) score += 10
        if (lines.any { pokemonContextPattern.containsMatchIn(it) }) score += 15
        if (lines.any { attackOrRulesPattern.containsMatchIn(it) }) score += 5
        return score.coerceAtMost(100)
    }

    private companion object {
        val cardNumberPattern = Regex("""\b\d{1,3}\s*/\s*\d{2,3}\b""")
        val labeledLocalNumberPattern = Regex("""\b(?:No\.?|#)\s*\d{1,3}\b""", RegexOption.IGNORE_CASE)
        val pokemonContextPattern = Regex("""\b(HP|Pokemon|Weakness|Resistance|Retreat|Stage|Basic)\b""", RegexOption.IGNORE_CASE)
        val attackOrRulesPattern = Regex("""\b(ability|attack|damage|draw|discard|energy|evolves)\b""", RegexOption.IGNORE_CASE)
        val ignoredLineFragments = setOf(
            "pokemon",
            "evolves",
            "weakness",
            "resistance",
            "retreat",
            "illustrator",
            "nintendo",
            "game freak",
            "creatures"
        )
    }
}
