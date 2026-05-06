package com.example.tradingcardscanner.domain

data class CardDetails(
    val name: String,
    val setName: String,
    val number: String,
    val pricing: PricingSnapshot?
)

data class PricingSnapshot(
    val source: PricingSourceType,
    val currencyCode: String,
    val trend: Double?,
    val low: Double?,
    val average30Days: Double?,
    val holo: HoloPricing?
) {
    val hasAnyPrice: Boolean
        get() = trend != null || low != null || average30Days != null || holo?.hasAnyPrice == true
}

data class HoloPricing(
    val average: Double?,
    val trend: Double?,
    val low: Double?,
    val average30Days: Double?
) {
    val hasAnyPrice: Boolean
        get() = average != null || trend != null || low != null || average30Days != null
}
