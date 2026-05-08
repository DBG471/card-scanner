package com.example.tradingcardscanner.data

import android.content.Context
import com.example.tradingcardscanner.domain.CollectionCard
import org.json.JSONArray
import org.json.JSONObject

class CollectionRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getCards(): List<CollectionCard> {
        val rawJson = preferences.getString(KEY_CARDS, "[]").orEmpty()
        val array = runCatching { JSONArray(rawJson) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toCollectionCard()
        }
    }

    fun addCard(card: CollectionCard) {
        val cards = getCards().toMutableList()
        cards.add(0, card)
        saveCards(cards)
    }

    fun deleteCard(id: String) {
        saveCards(getCards().filterNot { it.id == id })
    }

    private fun saveCards(cards: List<CollectionCard>) {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_CARDS, array.toString()).apply()
    }

    private fun CollectionCard.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("cardName", cardName)
            put("setName", setName)
            put("cardNumber", cardNumber)
            put("rarity", rarity)
            put("condition", condition)
            put("variant", variant)
            put("priceSource", priceSource)
            put("cardmarketTrendPrice", cardmarketTrendPrice)
            put("matchedCardId", matchedCardId)
            put("priceUsed", priceUsed)
            put("currencyCode", currencyCode)
            put("scanDate", scanDate)
            put("imageUrl", imageUrl)
            put("localScanPhotoUri", localScanPhotoUri)
        }
    }

    private fun JSONObject.toCollectionCard(): CollectionCard? {
        return runCatching {
            CollectionCard(
                id = optString("id"),
                cardName = optString("cardName"),
                setName = optString("setName"),
                cardNumber = optString("cardNumber"),
                rarity = optString("rarity").takeIf { it.isNotBlank() && it != "null" },
                condition = optString("condition"),
                variant = optString("variant", "Normal"),
                priceSource = optString("priceSource"),
                cardmarketTrendPrice = optNullableDouble("cardmarketTrendPrice"),
                matchedCardId = optString("matchedCardId"),
                priceUsed = optNullableDouble("priceUsed"),
                currencyCode = optString("currencyCode", "EUR"),
                scanDate = optString("scanDate"),
                imageUrl = optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
                localScanPhotoUri = optString("localScanPhotoUri").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeUnless { it.isNaN() }
    }

    private companion object {
        const val PREFERENCES_NAME = "collection"
        const val KEY_CARDS = "cards"
    }
}
