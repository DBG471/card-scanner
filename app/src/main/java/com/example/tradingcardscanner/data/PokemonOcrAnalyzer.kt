package com.example.tradingcardscanner.data

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

data class OcrCardCandidate(
    val possibleName: String?,
    val possibleNumber: String?,
    val numberPrefix: String?,
    val numberTotal: String?,
    val confidenceScore: Int
) {
    val isReliable: Boolean
        get() = confidenceScore >= RELIABLE_MATCH_THRESHOLD

    companion object {
        const val RELIABLE_MATCH_THRESHOLD = 70
    }
}

class PokemonOcrAnalyzer {
    fun analyze(text: Text): OcrCardCandidate {
        val lines = text.textBlocks
            .flatMap { block -> block.lines }
            .map { OcrLine(it.text.trim(), it.boundingBox) }
            .filter { it.text.isNotBlank() }

        val cardNumber = findBestCardNumber(lines)
        val name = findBestName(lines, cardNumber)
        val score = confidenceFor(lines, name, cardNumber)

        return OcrCardCandidate(
            possibleName = name,
            possibleNumber = cardNumber?.displayValue,
            numberPrefix = cardNumber?.prefix,
            numberTotal = cardNumber?.total,
            confidenceScore = score
        )
    }

    private fun findBestCardNumber(lines: List<OcrLine>): CardNumber? {
        return lines.asSequence()
            .flatMap { line -> cardNumberPattern.findAll(line.text) }
            .mapNotNull { match ->
                val prefix = match.groupValues[1].trimStart('0').ifBlank { "0" }
                val total = match.groupValues[2].trimStart('0').ifBlank { "0" }
                CardNumber(
                    displayValue = "${match.groupValues[1]}/${match.groupValues[2]}",
                    prefix = prefix,
                    total = total
                )
            }
            .firstOrNull()
    }

    private fun findBestName(lines: List<OcrLine>, cardNumber: CardNumber?): String? {
        val maxBottom = lines.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: return null
        val titleAreaLimit = (maxBottom * TITLE_AREA_RATIO).toInt()

        return lines.asSequence()
            .filter { line -> line.bounds?.top?.let { it <= titleAreaLimit } ?: true }
            .map { it.text.cleanupOcrWord() }
            .filter { it.isLikelyPokemonTitle() }
            .filterNot { cleaned -> cardNumber?.displayValue?.let { cleaned.contains(it) } == true }
            .sortedWith(compareByDescending<String> { it.length in 4..24 }.thenBy { it.length })
            .firstOrNull()
    }

    private fun confidenceFor(
        lines: List<OcrLine>,
        possibleName: String?,
        cardNumber: CardNumber?
    ): Int {
        var score = 0
        if (cardNumber != null) score += 50
        if (possibleName != null) score += 25
        if (lines.any { pokemonContextPattern.containsMatchIn(it.text) }) score += 10
        if (lines.size >= 5) score += 5
        if (lines.any { germanPokemonContextPattern.containsMatchIn(it.text) }) score += 5
        return score.coerceAtMost(100)
    }

    private fun String.cleanupOcrWord(): String {
        return replace(Regex("""^[^A-Za-z]+|[^A-Za-z]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.isLikelyPokemonTitle(): Boolean {
        val normalized = lowercase()
        if (length !in 3..32) return false
        if (!any(Char::isLetter)) return false
        if (count(Char::isDigit) > 0) return false
        if (contains("/")) return false
        if (ignoredLineFragments.any { normalized.contains(it) }) return false
        if (attackOrRulesPattern.containsMatchIn(this)) return false
        if (split(" ").size > 3) return false
        return true
    }

    private data class OcrLine(
        val text: String,
        val bounds: Rect?
    )

    private data class CardNumber(
        val displayValue: String,
        val prefix: String,
        val total: String
    )

    private companion object {
        const val TITLE_AREA_RATIO = 0.42f
        val cardNumberPattern = Regex("""\b0*(\d{1,3})\s*/\s*0*(\d{2,3})\b""")
        val pokemonContextPattern = Regex("""\b(HP|Pokemon|Weakness|Resistance|Retreat|Stage|Basic)\b""", RegexOption.IGNORE_CASE)
        val germanPokemonContextPattern = Regex("""\b(KP|Schwaeche|Schwache|Resistenz|Rueckzug|Ruckzug|Entwickelt|Rang)\b""", RegexOption.IGNORE_CASE)
        val attackOrRulesPattern = Regex(
            """\b(ability|attack|damage|draw|discard|energy|evolves|angriff|faehigkeit|fahigkeit|schaden|lege|wirf|karten|energie|gegner|pokemon)\b""",
            RegexOption.IGNORE_CASE
        )
        val ignoredLineFragments = setOf(
            "pokemon",
            "kp",
            "hp",
            "stage",
            "basic",
            "rang",
            "entwickelt",
            "schwaeche",
            "schwache",
            "resistenz",
            "rueckzug",
            "ruckzug",
            "illustrator",
            "nintendo",
            "game freak",
            "creatures"
        )
    }
}
