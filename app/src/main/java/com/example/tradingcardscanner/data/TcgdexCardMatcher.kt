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
        val urls = buildCandidateUrls(candidate)
        if (urls.isEmpty()) {
            callback(Result.success(emptyList()))
            return
        }

        Thread {
            runCatching {
                val briefCards = urls
                    .flatMap(::fetchBriefCards)
                    .distinctBy { it.id }
                    .take(MAX_DETAIL_FETCH)

                briefCards.mapNotNull(::fetchCardDetails)
                    .map { card -> score(card, candidate) }
                    .filter { it.confidence >= MIN_VISIBLE_CONFIDENCE }
                    .sortedWith(compareByDescending<CardMatch> { it.confidence }.thenBy { it.card.name })
                    .take(3)
            }.onSuccess { matches ->
                callback(Result.success(matches))
            }.onFailure { error ->
                callback(Result.failure(error))
            }
        }.start()
    }

    private fun buildCandidateUrls(candidate: OcrCardCandidate): List<String> {
        val urls = mutableListOf<String>()
        val number = candidate.numberPrefix?.padStart(3, '0')
        val rawNumber = candidate.numberPrefix

        for (language in listOf("de", "en")) {
            if (number != null) urls += cardsUrl(language, "localId", number)
            if (rawNumber != null && rawNumber != number) urls += cardsUrl(language, "localId", rawNumber)
            candidate.possibleName?.takeIf { it.length >= 3 }?.let { name ->
                urls += cardsUrl(language, "name", name)
            }
        }

        return urls.distinct()
    }

    private fun cardsUrl(language: String, key: String, value: String): String {
        return "$API_BASE/$language/cards".toHttpUrl()
            .newBuilder()
            .addQueryParameter(key, value)
            .build()
            .toString()
    }

    private fun fetchBriefCards(url: String): List<CardBrief> {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONArray(response.body?.string().orEmpty())
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CardBrief(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    image = item.optString("image").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private fun fetchCardDetails(brief: CardBrief): CardDetails? {
        val language = languageFromImageUrl(brief.image) ?: "de"
        val request = Request.Builder()
            .url("$API_BASE/$language/cards/${brief.id}")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            runCatching { TcgdexCardParser.parse(response.body?.string().orEmpty()) }.getOrNull()
        }
    }

    private fun score(card: CardDetails, candidate: OcrCardCandidate): CardMatch {
        var score = 0
        val cardNumber = card.number.trimStart('0').ifBlank { card.number }
        val candidateNumber = candidate.numberPrefix

        if (candidateNumber != null && cardNumber == candidateNumber) score += 45

        val officialTotal = card.setOfficialTotal?.trimStart('0')?.ifBlank { card.setOfficialTotal }
        if (candidate.numberTotal != null && officialTotal == candidate.numberTotal) score += 25

        val nameScore = fuzzyNameScore(candidate.possibleName.orEmpty(), card.name)
        score += (nameScore * 30 / 100)

        if (candidateNumber != null && cardNumber != candidateNumber) {
            score -= 30
        }

        val confidence = score.coerceIn(0, 100)
        return CardMatch(
            card = card,
            confidence = confidence,
            isStrong = confidence >= STRONG_MATCH_CONFIDENCE &&
                candidateNumber != null &&
                cardNumber == candidateNumber &&
                (candidate.numberTotal == null || officialTotal == candidate.numberTotal)
        )
    }

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

    private fun languageFromImageUrl(imageUrl: String?): String? {
        return imageUrl?.split("/")?.getOrNull(3)
    }

    private data class CardBrief(
        val id: String,
        val name: String,
        val image: String?
    )

    private companion object {
        const val API_BASE = "https://api.tcgdex.net/v2"
        const val MAX_DETAIL_FETCH = 48
        const val MIN_VISIBLE_CONFIDENCE = 35
        const val STRONG_MATCH_CONFIDENCE = 85
    }
}
