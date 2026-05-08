package com.example.tradingcardscanner.domain

data class CardDetails(
    val id: String,
    val name: String,
    val setName: String,
    val rarity: String?,
    val number: String,
    val setOfficialTotal: String?,
    val imageUrl: String?,
    val pricing: PricingSnapshot?
)

data class PricingSnapshot(
    val source: PricingSourceType,
    val currencyCode: String,
    val trend: Double?,
    val low: Double?,
    val average30Days: Double?,
    val holo: VariantPricing?,
    val reverseHolo: VariantPricing?
) {
    val hasAnyPrice: Boolean
        get() = trend != null || low != null || average30Days != null ||
            holo?.hasAnyPrice == true ||
            reverseHolo?.hasAnyPrice == true
}

data class VariantPricing(
    val average: Double?,
    val trend: Double?,
    val low: Double?,
    val average30Days: Double?
) {
    val hasAnyPrice: Boolean
        get() = average != null || trend != null || low != null || average30Days != null
}
