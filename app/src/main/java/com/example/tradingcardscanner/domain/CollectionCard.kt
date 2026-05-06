package com.example.tradingcardscanner.domain

data class CollectionCard(
    val id: String,
    val cardName: String,
    val setName: String,
    val cardNumber: String,
    val rarity: String?,
    val condition: String,
    val priceSource: String,
    val cardmarketTrendPrice: Double?,
    val currencyCode: String,
    val scanDate: String,
    val imageUrl: String?
)
