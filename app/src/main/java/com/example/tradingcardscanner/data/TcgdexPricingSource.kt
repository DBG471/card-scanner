package com.example.tradingcardscanner.data

import com.example.tradingcardscanner.domain.CardDetails
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class TcgdexPricingSource(
    private val client: OkHttpClient = OkHttpClient()
) : PricingSource {
    override fun fetchSampleCard(callback: (Result<CardDetails>) -> Unit) {
        val request = Request.Builder()
            .url(SAMPLE_CARD_URL)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Unexpected response ${it.code}")))
                        return
                    }

                    val body = it.body?.string().orEmpty()
                    runCatching { TcgdexCardParser.parse(body) }
                        .onSuccess { card -> callback(Result.success(card)) }
                        .onFailure { error -> callback(Result.failure(error)) }
                }
            }
        })
    }

    private companion object {
        const val SAMPLE_CARD_URL = "https://api.tcgdex.net/v2/en/cards/swsh3-136"
    }
}
