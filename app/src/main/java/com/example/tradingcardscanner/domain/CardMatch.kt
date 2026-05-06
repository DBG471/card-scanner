package com.example.tradingcardscanner.domain

data class CardMatch(
    val card: CardDetails,
    val confidence: Int,
    val isStrong: Boolean
)
