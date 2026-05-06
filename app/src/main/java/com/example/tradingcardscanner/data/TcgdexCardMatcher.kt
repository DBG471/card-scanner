package com.example.tradingcardscanner.data

import com.example.tradingcardscanner.domain.CardDetails
import com.example.tradingcardscanner.domain.CardMatch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

class TcgdexCardMatcher(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun findMatches(
        candidate: OcrCardCandidate,
        callback: (Result<List<CardMatch>>) -> Unit
    ) {
        val queries = buildCandidateQueries(candidate)
        if (queries.isEmpty()) {
            callback(Result.success(emptyList()))
            return
        }

        Thread {
            runCatching {
                val briefCards = queries
                    .flatMap { query -> fetchBriefCards(query) }
                    .distinctBy { "${it.language}:${it.id}" }
                    .take(MAX_DETAIL_FETCH)

                briefCards.mapNotNull(::fetchCardDetails)
                    .map { card -> score(card, candidate) }
                    .filter { match ->
                        val numberMatches = candidate.numberPrefix
                            ?.let { match.card.normalizedNumber == it.normalizedCardNumber() }
                            ?: true
                        val nameMatches = candidate.possibleName
                            ?.let { fuzzyNameScore(it, match.card.name) >= MIN_NAME_KEEP_SCORE }
                            ?: true
                        match.confidence >= MIN_VISIBLE_CONFIDENCE && numberMatches && nameMatches
                    }
                    .sortedWith(compareByDescending<CardMatch> { it.confidence }.thenBy { it.card.name })
                    .take(3)
            }.onSuccess { matches ->
                callback(Result.success(matches))
            }.onFailure { error ->
                callback(Result.failure(error))
            }
        }.start()
    }

    fun debugQueries(candidate: OcrCardCandidate): String {
        return buildString {
            appendLine("cleaned OCR query=${candidate.cleanedQuery}")
            append(buildCandidateQueries(candidate).joinToString("\n") { it.description })
        }.trim()
    }

    private fun buildCandidateQueries(candidate: OcrCardCandidate): List<CardQuery> {
        val queries = mutableListOf<CardQuery>()
        val rawNumber = candidate.numberPrefix
        val number = rawNumber?.padLocalId()
        val name = candidate.possibleName?.takeIf { it.length >= 3 }
        val localIds = listOfNotNull(number, rawNumber).distinct()

        for (language in listOf("de", "en")) {
            for (localId in localIds) {
                if (name != null) {
                    queries += CardQuery(
                        language = language,
                        url = cardsUrl(
                            language,
                            mapOf("localId" to "eq:$localId", "name" to "like:$name")
                        ),
                        description = "$language localId=eq:$localId name=like:$name"
                    )
                    queries += CardQuery(
                        language = language,
                        url = cardsUrl(
                            language,
                            mapOf("localId" to localId, "name" to name)
                        ),
                        description = "$language localId=$localId name=$name"
                    )
                }
                queries += CardQuery(
                    language = language,
                    url = cardsUrl(language, mapOf("localId" to "eq:$localId")),
                    description = "$language localId=eq:$localId"
                )
                queries += CardQuery(
                    language = language,
                    url = cardsUrl(language, mapOf("localId" to localId)),
                    description = "$language localId=$localId"
                )
            }
        }

        if (name != null) {
            for (language in listOf("de", "en")) {
                queries += CardQuery(
                    language = language,
                    url = cardsUrl(language, mapOf("name" to "eq:$name")),
                    description = "$language name=eq:$name"
                )
                queries += CardQuery(
                    language = language,
                    url = cardsUrl(language, mapOf("name" to name)),
                    description = "$language name=$name"
                )
            }
        }

        return queries.distinctBy { it.url }
    }

    private fun cardsUrl(language: String, filters: Map<String, String>): String {
        val builder = "$API_BASE/$language/cards".toHttpUrl().newBuilder()
        filters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun fetchBriefCards(query: CardQuery): List<CardBrief> {
        val request = Request.Builder().url(query.url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONArray(response.body?.string().orEmpty())
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CardBrief(
                    id = item.optString("id"),
                    localId = item.optString("localId"),
                    name = item.optString("name"),
                    image = item.optString("image").takeIf { it.isNotBlank() },
                    language = query.language,
                    queryUsed = query.description
                )
            }
        }
    }

    private fun fetchCardDetails(brief: CardBrief): Pair<CardDetails, String>? {
        val request = Request.Builder()
            .url("$API_BASE/${brief.language}/cards/${brief.id}")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            runCatching { TcgdexCardParser.parse(response.body?.string().orEmpty()) to brief.queryUsed }.getOrNull()
        }
    }

    private fun score(cardWithQuery: Pair<CardDetails, String>, candidate: OcrCardCandidate): CardMatch {
        val (card, queryUsed) = cardWithQuery
        var score = 0
        val reasons = mutableListOf<String>()
        val cardNumber = card.normalizedNumber
        val candidateNumber = candidate.numberPrefix?.normalizedCardNumber()
        val nameScore = fuzzyNameScore(candidate.possibleName.orEmpty(), card.name)

        if (candidateNumber != null && cardNumber == candidateNumber) {
            score += 65
            reasons += "card number matched ${candidate.possibleNumber}"
        }

        val officialTotal = card.setOfficialTotal?.trimStart('0')?.ifBlank { card.setOfficialTotal }
        if (candidate.numberTotal != null && officialTotal == candidate.numberTotal) {
            score += 25
            reasons += "set total matched ${candidate.numberTotal}"
        } else if (candidate.numberTotal != null) {
            score -= 25
            reasons += "set total ${officialTotal ?: "unknown"} did not match ${candidate.numberTotal}"
        }

        score += when {
            nameScore == 100 -> 35
            nameScore >= 90 -> 32
            nameScore >= 80 -> 24
            nameScore >= 70 -> 14
            else -> 0
        }
        if (nameScore == 100) reasons += "exact Pokemon name matched"
        else if (nameScore >= 70) reasons += "name fuzzy match $nameScore%"
        else if (!candidate.possibleName.isNullOrBlank()) reasons += "name did not match ${candidate.possibleName}"

        if (candidateNumber != null && cardNumber != candidateNumber) {
            score = 0
            reasons += "rejected: card number ${card.number} did not match ${candidate.possibleNumber}"
        } else if (!candidate.possibleName.isNullOrBlank() && nameScore < MIN_NAME_KEEP_SCORE) {
            score = 0
            reasons += "rejected: OCR name ${candidate.possibleName} did not match ${card.name}"
        }

        val confidence = score.coerceIn(0, 100)
        val totalMatches = candidate.numberTotal == null || officialTotal == candidate.numberTotal
        val nameIsStrong = candidate.possibleName.isNullOrBlank() || nameScore >= STRONG_NAME_SCORE
        return CardMatch(
            card = card,
            confidence = confidence,
            isStrong = confidence >= STRONG_MATCH_CONFIDENCE &&
                (
                    candidateNumber != null &&
                        cardNumber == candidateNumber &&
                        totalMatches &&
                        nameIsStrong
                ) || (
                    candidateNumber != null &&
                        cardNumber == candidateNumber &&
                        candidate.numberTotal == null &&
                        nameScore == 100
                ),
            queryUsed = queryUsed,
            matchReason = reasons.joinToString("; ").ifBlank { "low confidence fuzzy candidate" }
        )
    }

    private val CardDetails.normalizedNumber: String
        get() = number.normalizedCardNumber()

    private fun fuzzyNameScore(left: String, right: String): Int {
        val a = left.normalizedForMatch()
        val b = right.normalizedForMatch()
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) return 82

        val distance = levenshtein(a, b)
        val maxLength = max(a.length, b.length)
        return ((1.0 - distance.toDouble() / maxLength) * 100).toInt().coerceIn(0, 100)
    }

    private fun String.normalizedForMatch(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
    }

    private fun String.normalizedCardNumber(): String {
        val compact = uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        return if (compact.all(Char::isDigit)) {
            compact.trimStart('0').ifBlank { "0" }
        } else {
            compact.replace(Regex("""^([A-Z]+)0+(\d+)"""), "$1$2")
        }
    }

    private fun String.padLocalId(): String {
        val compact = uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        return if (compact.all(Char::isDigit)) compact.padStart(3, '0') else compact
    }

    private fun levenshtein(left: String, right: String): Int {
        val costs = IntArray(right.length + 1) { it }
        for (i in 1..left.length) {
            var previous = costs[0]
            costs[0] = i
            for (j in 1..right.length) {
                val current = costs[j]
                costs[j] = minOf(
                    costs[j] + 1,
                    costs[j - 1] + 1,
                    previous + if (left[i - 1] == right[j - 1]) 0 else 1
                )
                previous = current
            }
        }
        return costs[right.length]
    }

    private data class CardBrief(
        val id: String,
        val localId: String,
        val name: String,
        val image: String?,
        val language: String,
        val queryUsed: String
    )

    private data class CardQuery(
        val language: String,
        val url: String,
        val description: String
    )

    private companion object {
        const val API_BASE = "https://api.tcgdex.net/v2"
        const val MAX_DETAIL_FETCH = 48
        const val MIN_VISIBLE_CONFIDENCE = 50
        const val MIN_NAME_KEEP_SCORE = 70
        const val STRONG_MATCH_CONFIDENCE = 80
        const val STRONG_NAME_SCORE = 88
    }
}
