package com.example.tradingcardscanner.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.example.tradingcardscanner.domain.CardMatch
import java.net.URL
import kotlin.math.roundToInt

class ImageSimilarityScorer(
    private val contentResolver: ContentResolver
) {
    fun score(
        scanUri: Uri?,
        matches: List<CardMatch>,
        callback: (List<CardMatch>) -> Unit
    ) {
        if (scanUri == null || matches.isEmpty()) {
            callback(matches)
            return
        }

        Thread {
            val scanHash = decodeLocalBitmap(scanUri)?.let(::averageHash)
            if (scanHash == null) {
                callback(matches.map { it.copy(matchReason = "${it.matchReason}; image similarity unavailable") })
                return@Thread
            }

            val scored = matches.map { match ->
                val tcgdexHash = match.card.imageUrl
                    ?.candidateImageUrls()
                    ?.firstNotNullOfOrNull { imageUrl ->
                        runCatching {
                            URL(imageUrl).openStream().use(BitmapFactory::decodeStream)?.let(::averageHash)
                        }.getOrNull()
                    }

                if (tcgdexHash == null) {
                    match.copy(matchReason = "${match.matchReason}; image similarity unavailable")
                } else {
                    match.withImageSimilarity(hashSimilarity(scanHash, tcgdexHash))
                }
            }.sortedWith(compareByDescending<CardMatch> { it.confidence }.thenBy { it.card.name })

            callback(scored)
        }.start()
    }

    private fun decodeLocalBitmap(uri: Uri): Bitmap? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private fun CardMatch.withImageSimilarity(imageScore: Int): CardMatch {
        val weightedConfidence = ((confidence * 0.85f) + (imageScore * 0.15f)).roundToInt()
        val imageAdjustment = when {
            imageScore >= 82 -> 5
            imageScore >= 70 -> 2
            imageScore < 42 -> -8
            else -> 0
        }
        val finalConfidence = (weightedConfidence + imageAdjustment).coerceIn(0, 100)
        val visualGatePassed = imageScore >= 45
        val numberNameGatePassed = numberScore == 100 && nameScore >= 88
        return copy(
            confidence = finalConfidence,
            isStrong = finalConfidence >= 90 && visualGatePassed && (isStrong || numberNameGatePassed),
            imageSimilarityScore = imageScore,
            matchReason = "$matchReason; image similarity $imageScore%"
        )
    }

    private fun averageHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val luminance = IntArray(HASH_SIZE * HASH_SIZE)
        var total = 0
        var index = 0
        for (y in 0 until HASH_SIZE) {
            for (x in 0 until HASH_SIZE) {
                val pixel = scaled.getPixel(x, y)
                val value = ((Color.red(pixel) * 30) + (Color.green(pixel) * 59) + (Color.blue(pixel) * 11)) / 100
                luminance[index++] = value
                total += value
            }
        }
        val average = total / luminance.size
        var hash = 0L
        luminance.forEachIndexed { bit, value ->
            if (value >= average) hash = hash or (1L shl bit)
        }
        return hash
    }

    private fun hashSimilarity(left: Long, right: Long): Int {
        val differentBits = java.lang.Long.bitCount(left xor right)
        return ((HASH_BITS - differentBits) * 100 / HASH_BITS).coerceIn(0, 100)
    }

    private fun String.candidateImageUrls(): List<String> {
        val trimmed = trim().trimEnd('/')
        if (trimmed.endsWith(".png", true) || trimmed.endsWith(".webp", true) || trimmed.endsWith(".jpg", true) || trimmed.endsWith(".jpeg", true)) {
            val high = trimmed
                .replace("/low.png", "/high.png", true)
                .replace("/low.webp", "/high.webp", true)
                .replace("/low.jpg", "/high.jpg", true)
                .replace("/low.jpeg", "/high.jpeg", true)
            val low = trimmed
                .replace("/high.png", "/low.png", true)
                .replace("/high.webp", "/low.webp", true)
                .replace("/high.jpg", "/low.jpg", true)
                .replace("/high.jpeg", "/low.jpeg", true)
            return listOf(high, trimmed, low).distinct()
        }
        return listOf("$trimmed/high.png", "$trimmed/low.png")
    }

    private companion object {
        const val HASH_SIZE = 8
        const val HASH_BITS = HASH_SIZE * HASH_SIZE
    }
}
