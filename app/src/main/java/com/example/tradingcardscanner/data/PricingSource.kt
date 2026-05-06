package com.example.tradingcardscanner.data

import com.example.tradingcardscanner.domain.CardDetails

interface PricingSource {
    fun fetchSampleCard(callback: (Result<CardDetails>) -> Unit)
}
