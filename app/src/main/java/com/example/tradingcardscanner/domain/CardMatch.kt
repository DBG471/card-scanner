package com.example.tradingcardscanner.domain

data class CardMatch(
    val card: CardDetails,
    val confidence: Int,
    val isStrong: Boolean,
    val queryUsed: String,
    val matchReason: String,
    val numberScore: Int = 0,
    val nameScore: Int = 0,
    val setScore: Int = 0,
    val hpScore: Int = 0,
    val imageSimilarityScore: Int? = null
)
