package com.example.tradingcardscanner.domain

enum class CardVariant {
    Normal,
    Holo,
    ReverseHolo,
    EX,
    GX,
    V,
    VMAX,
    VSTAR,
    FullArt,
    SecretRare,
    Promo;

    companion object {
        fun fromSpinnerPosition(position: Int): CardVariant = entries.getOrElse(position) { Normal }
    }
}
