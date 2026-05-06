package com.example.tradingcardscanner.domain

class ConditionPriceAdjuster {
    fun adjust(pricing: PricingSnapshot, condition: CardCondition): PricingSnapshot {
        val multiplier = multiplierFor(condition)
        if (multiplier == 1.0) return pricing

        return pricing.copy(
            trend = pricing.trend?.times(multiplier),
            low = pricing.low?.times(multiplier),
            average30Days = pricing.average30Days?.times(multiplier),
            holo = pricing.holo?.let {
                it.copy(
                    average = it.average?.times(multiplier),
                    trend = it.trend?.times(multiplier),
                    low = it.low?.times(multiplier),
                    average30Days = it.average30Days?.times(multiplier)
                )
            }
        )
    }

    private fun multiplierFor(condition: CardCondition): Double = when (condition) {
        CardCondition.NearMint,
        CardCondition.Excellent,
        CardCondition.Good,
        CardCondition.LightPlayed,
        CardCondition.Played,
        CardCondition.Poor -> 1.0
    }
}
