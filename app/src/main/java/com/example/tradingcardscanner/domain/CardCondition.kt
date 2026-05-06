package com.example.tradingcardscanner.domain

enum class CardCondition {
    NearMint,
    Excellent,
    Good,
    LightPlayed,
    Played,
    Poor;

    companion object {
        fun fromSpinnerPosition(position: Int): CardCondition = entries.getOrElse(position) { NearMint }
    }
}
