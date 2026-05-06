# TradingCardScanner

Clean native Android Kotlin app for scanning a trading card image and testing pricing data from TCGdex.

## What is included

- Native Android Kotlin project, no Flutter.
- Camera capture flow using `ActivityResultContracts.TakePicture`.
- Captured image preview inside the app.
- `Test Price` button that loads:
  `https://api.tcgdex.net/v2/en/cards/swsh3-136`
- Cardmarket pricing display for trend, low, 30-day average, and holo prices when present.
- English and German string resources.
- Card condition model for NM, EX, GD, LP, Played, and Poor.
- Pricing source architecture prepared for Cardmarket, eBay sold listings, PSA, and PriceCharting.
- `INTERNET` and `CAMERA` permissions.
- OkHttp-based API layer.

## Project structure

```text
app/src/main/java/com/example/tradingcardscanner/
  MainActivity.kt
  data/
    PricingSource.kt
    TcgdexCardParser.kt
    TcgdexPricingSource.kt
  domain/
    CardCondition.kt
    CardDetails.kt
    ConditionPriceAdjuster.kt
    PricingSourceType.kt
```

`ConditionPriceAdjuster` currently keeps all multipliers at `1.0`; it is the intended place to add condition-based price adjustments later.

## Build

The project uses a stable Android Gradle Plugin and Kotlin plugin pairing:

- Android Gradle Plugin `8.7.3`
- Kotlin Android plugin `2.0.21`
- Compile SDK `35`
- Java/Kotlin JVM target `17`

Build a debug APK with:

```sh
gradle assembleDebug
```

Codemagic can use the included `codemagic.yaml` workflow and will collect APK artifacts from `app/build/outputs/**/*.apk`.

CI artifact build requested from `codex/apk-build-trigger`.
