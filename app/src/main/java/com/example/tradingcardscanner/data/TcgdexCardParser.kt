package com.example.tradingcardscanner.data

import com.example.tradingcardscanner.domain.CardDetails
import com.example.tradingcardscanner.domain.PricingSnapshot
import com.example.tradingcardscanner.domain.PricingSourceType
import com.example.tradingcardscanner.domain.VariantPricing
import org.json.JSONObject

object TcgdexCardParser {
    fun parse(json: String): CardDetails {
        val root = JSONObject(json)
        val set = root.optJSONObject("set")
        val cardCount = set?.optJSONObject("cardCount")
        val cardmarket = root.optJSONObject("pricing")?.optJSONObject("cardmarket")

        return CardDetails(
            id = root.optString("id", ""),
            name = root.optString("name", ""),
            setName = set?.optString("name", "").orEmpty(),
            rarity = root.optString("rarity").takeIf { it.isNotBlank() },
            number = root.optString("localId", ""),
            setOfficialTotal = cardCount?.optString("official")?.takeIf { it.isNotBlank() },
            imageUrl = root.optString("image").takeIf { it.isNotBlank() },
            pricing = cardmarket?.let(::parseCardmarketPricing)
        )
    }

    private fun parseCardmarketPricing(cardmarket: JSONObject): PricingSnapshot {
        val holoPricing = VariantPricing(
            average = cardmarket.optNullableDouble("avg-holo"),
            trend = cardmarket.optNullableDouble("trend-holo"),
            low = cardmarket.optNullableDouble("low-holo"),
            average30Days = cardmarket.optNullableDouble("avg30-holo")
        ).takeIf { it.hasAnyPrice }
        val reverseHoloPricing = VariantPricing(
            average = cardmarket.optNullableDouble("avg-reverse"),
            trend = cardmarket.optNullableDouble("trend-reverse"),
            low = cardmarket.optNullableDouble("low-reverse"),
            average30Days = cardmarket.optNullableDouble("avg30-reverse")
        ).takeIf { it.hasAnyPrice }

        return PricingSnapshot(
            source = PricingSourceType.Cardmarket,
            currencyCode = cardmarket.optString("unit", "EUR"),
            trend = cardmarket.optNullableDouble("trend"),
            low = cardmarket.optNullableDouble("low"),
            average30Days = cardmarket.optNullableDouble("avg30"),
            holo = holoPricing,
            reverseHolo = reverseHoloPricing
        )
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeUnless { it.isNaN() }
    }
}
