package com.example.tradingcardscanner.data

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

data class OcrCardCandidate(
    val possibleName: String?,
    val possibleNumber: String?,
    val numberPrefix: String?,
    val numberTotal: String?,
    val confidenceScore: Int,
    val debugNotes: List<String> = emptyList()
) {
    val isReliable: Boolean
        get() = confidenceScore >= RELIABLE_MATCH_THRESHOLD

    companion object {
        const val RELIABLE_MATCH_THRESHOLD = 70
    }
}

class PokemonOcrAnalyzer {
    fun analyze(text: Text, imageWidth: Int? = null, imageHeight: Int? = null): OcrCardCandidate {
        val lines = text.textBlocks
            .flatMap { block -> block.lines }
            .map { OcrLine(it.text.trim(), it.boundingBox) }
            .filter { it.text.isNotBlank() }

        val regions = OcrRegions.from(imageWidth, imageHeight, lines)
        val cardNumber = findBestCardNumber(lines, regions)
        val name = findBestName(lines, cardNumber, regions)
        val score = confidenceFor(lines, name, cardNumber)

        return OcrCardCandidate(
            possibleName = name,
            possibleNumber = cardNumber?.displayValue,
            numberPrefix = cardNumber?.prefix,
            numberTotal = cardNumber?.total,
            confidenceScore = score,
            debugNotes = buildDebugNotes(regions, name, cardNumber)
        )
    }

    private fun findBestCardNumber(lines: List<OcrLine>, regions: OcrRegions): CardNumber? {
        val rankedLines = lines.map { line ->
            line to when {
                regions.isInBottom(line) -> 0
                regions.isInTitle(line) -> 2
                else -> 1
            }
        }.sortedBy { it.second }

        return rankedLines.asSequence()
            .flatMap { (line, rank) -> extractNumbers(line).map { it.copy(regionRank = rank) } }
            .filterNot { number -> number.isLikelyDamageNumber() }
            .sortedWith(compareBy<CardNumber> { it.regionRank }.thenByDescending { it.specificity })
            .firstOrNull()
    }

    private fun findBestName(lines: List<OcrLine>, cardNumber: CardNumber?, regions: OcrRegions): String? {
        return lines.asSequence()
            .filter { line -> regions.isInTitle(line) }
            .map { it.text.cleanupOcrWord() }
            .filter { it.isLikelyPokemonTitle() }
            .filterNot { cleaned -> cardNumber?.displayValue?.let { cleaned.contains(it) } == true }
            .sortedWith(
                compareByDescending<String> { it.length in 4..18 }
                    .thenBy { it.split(" ").size }
                    .thenBy { it.length }
            )
            .firstOrNull()
    }

    private fun extractNumbers(line: OcrLine): List<CardNumber> {
        val normalized = line.text.normalizeNumberOcr()
        val slashNumbers = cardNumberPattern.findAll(normalized).map { match ->
            val prefix = "${match.groupValues[1]}${match.groupValues[2]}".uppercase().trimStart('0').ifBlank { "0" }
            val total = match.groupValues[3].trimStart('0').ifBlank { "0" }
            CardNumber(
                displayValue = "${match.groupValues[1].uppercase()}${match.groupValues[2].uppercase()}/${match.groupValues[3]}",
                prefix = prefix,
                total = total,
                regionRank = 1,
                specificity = if (prefix.any(Char::isLetter)) 95 else 90
            )
        }

        val promos = promoPattern.findAll(normalized).map { match ->
            val promo = match.value.uppercase().replace(Regex("\\s+"), "")
            CardNumber(
                displayValue = promo,
                prefix = promo,
                total = null,
                regionRank = 1,
                specificity = 100
            )
        }

        return (slashNumbers + promos).toList()
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

    private fun String.normalizeNumberOcr(): String {
        return replace(Regex("""(?i)(?<=\d)[oO](?=\d)"""), "0")
            .replace(Regex("""(?i)\b[oO](?=\d{2,3}\b)"""), "0")
            .replace(Regex("""(?i)\bTG\s*([0-9oO]{1,2})\s*/\s*([0-9oO]{1,2})\b""")) {
                "TG${it.groupValues[1].replace(Regex("(?i)o"), "0")}/${it.groupValues[2].replace(Regex("(?i)o"), "0")}"
            }
            .replace(Regex("""(?i)\bSVP\s*([0-9oO]{1,3})\b""")) {
                "SVP${it.groupValues[1].replace(Regex("(?i)o"), "0")}"
            }
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

    private fun buildDebugNotes(
        regions: OcrRegions,
        name: String?,
        cardNumber: CardNumber?
    ): List<String> {
        return listOf(
            "title region: top ${regions.titleBottom}px",
            "number region: from ${regions.numberTop}px",
            "name source: ${name ?: "none"}",
            "number source: ${cardNumber?.displayValue ?: "none"}"
        )
    }

    private data class OcrLine(
        val text: String,
        val bounds: Rect?
    )

    private data class CardNumber(
        val displayValue: String,
        val prefix: String,
        val total: String?,
        val regionRank: Int,
        val specificity: Int
    ) {
        fun isLikelyDamageNumber(): Boolean {
            return total == null && prefix.all(Char::isDigit) && prefix.toIntOrNull() in commonDamageNumbers
        }
    }

    private data class OcrRegions(
        val titleBottom: Int,
        val numberTop: Int
    ) {
        fun isInTitle(line: OcrLine): Boolean {
            val bounds = line.bounds ?: return true
            return bounds.centerY() <= titleBottom
        }

        fun isInBottom(line: OcrLine): Boolean {
            val bounds = line.bounds ?: return false
            return bounds.centerY() >= numberTop
        }

        companion object {
            fun from(imageWidth: Int?, imageHeight: Int?, lines: List<OcrLine>): OcrRegions {
                val height = imageHeight
                    ?: lines.mapNotNull { it.bounds?.bottom }.maxOrNull()
                    ?: 0
                return OcrRegions(
                    titleBottom = (height * TITLE_AREA_RATIO).toInt(),
                    numberTop = (height * NUMBER_AREA_TOP_RATIO).toInt()
                )
            }
        }
    }

    private companion object {
        const val TITLE_AREA_RATIO = 0.24f
        const val NUMBER_AREA_TOP_RATIO = 0.70f
        val cardNumberPattern = Regex("""\b(TG)?0*([A-Z]{0,3}\d{1,3})\s*/\s*0*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        val promoPattern = Regex("""\bSVP\s*[0-9O]{1,3}\b""", RegexOption.IGNORE_CASE)
        val pokemonContextPattern = Regex("""\b(HP|Pokemon|Weakness|Resistance|Retreat|Stage|Basic)\b""", RegexOption.IGNORE_CASE)
        val germanPokemonContextPattern = Regex("""\b(KP|Schwaeche|Schwache|Resistenz|Rueckzug|Ruckzug|Entwickelt|Rang)\b""", RegexOption.IGNORE_CASE)
        val attackOrRulesPattern = Regex(
            """\b(ability|attack|damage|draw|discard|energy|evolves|angriff|faehigkeit|fahigkeit|schaden|lege|wirf|karten|energie|gegner|pokemon|basis|phasej|phase)\b""",
            RegexOption.IGNORE_CASE
        )
        val commonDamageNumbers = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200)
        val ignoredLineFragments = setOf(
            "pokemon",
            "kp",
            "hp",
            "stage",
            "basic",
            "rang",
            "entwickelt",
            "meg de",
            "phasej",
            "phase",
            "basis",
            "schwaeche",
            "schwache",
            "resistenz",
            "rueckzug",
            "ruckzug",
            "illustrator",
            "nintendo",
            "game freak",
            "creatures",
            "copyright"
        )
    }
}
