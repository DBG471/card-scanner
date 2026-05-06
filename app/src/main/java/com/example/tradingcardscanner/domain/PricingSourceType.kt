package com.example.tradingcardscanner.domain

enum class PricingSourceType {
    Cardmarket,
    EbaySoldListings,
    Psa,
    PriceCharting;

    companion object {
        fun fromSpinnerPosition(position: Int): PricingSourceType = entries.getOrElse(position) { Cardmarket }
    }
}
