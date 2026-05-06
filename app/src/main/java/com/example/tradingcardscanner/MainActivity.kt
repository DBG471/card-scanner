package com.example.tradingcardscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.tradingcardscanner.data.CollectionRepository
import com.example.tradingcardscanner.data.OcrCardCandidate
import com.example.tradingcardscanner.data.PokemonOcrAnalyzer
import com.example.tradingcardscanner.data.TcgdexCardMatcher
import com.example.tradingcardscanner.data.TcgdexPricingSource
import com.example.tradingcardscanner.domain.CardCondition
import com.example.tradingcardscanner.domain.CardDetails
import com.example.tradingcardscanner.domain.CardMatch
import com.example.tradingcardscanner.domain.CollectionCard
import com.example.tradingcardscanner.domain.ConditionPriceAdjuster
import com.example.tradingcardscanner.domain.PricingSnapshot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.net.URL
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var cardImageView: ImageView
    private lateinit var imagePlaceholderText: TextView
    private lateinit var conditionSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var recognitionTextView: TextView
    private lateinit var matchesContainer: LinearLayout
    private lateinit var ocrDebugButton: Button
    private lateinit var rawOcrTextView: TextView
    private lateinit var priceTextView: TextView
    private lateinit var testPriceButton: Button
    private lateinit var collectionContainer: LinearLayout

    private val pricingSource = TcgdexPricingSource()
    private val tcgdexCardMatcher = TcgdexCardMatcher()
    private val conditionPriceAdjuster = ConditionPriceAdjuster()
    private val pokemonOcrAnalyzer = PokemonOcrAnalyzer()
    private lateinit var collectionRepository: CollectionRepository
    private var textRecognizer: TextRecognizer? = null
    private var pendingPhotoUri: Uri? = null
    private var scanResult: CardMatch? = null
    private var testPriceResult: CardDetails? = null
    private var ocrDebugText: String = ""
    private var latestOcrCandidate: OcrCardCandidate? = null
    private var isOcrDebugExpanded: Boolean = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingPhotoUri?.let { uri ->
                cardImageView.setImageURI(uri)
                imagePlaceholderText.visibility = View.GONE
                runOcr(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardImageView = findViewById(R.id.cardImageView)
        imagePlaceholderText = findViewById(R.id.imagePlaceholderText)
        conditionSpinner = findViewById(R.id.conditionSpinner)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        recognitionTextView = findViewById(R.id.recognitionTextView)
        matchesContainer = findViewById(R.id.matchesContainer)
        ocrDebugButton = findViewById(R.id.ocrDebugButton)
        rawOcrTextView = findViewById(R.id.rawOcrTextView)
        priceTextView = findViewById(R.id.priceTextView)
        testPriceButton = findViewById(R.id.testPriceButton)
        collectionContainer = findViewById(R.id.collectionContainer)
        collectionRepository = CollectionRepository(this)
        priceTextView.text = getString(R.string.price_idle)
        renderCollection()

        textRecognizer = runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }.getOrElse {
            recognitionTextView.text = getString(R.string.ocr_init_failed)
            null
        }

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            startCardCapture()
        }
        testPriceButton.setOnClickListener {
            loadTestPrice()
        }
        ocrDebugButton.setOnClickListener {
            isOcrDebugExpanded = !isOcrDebugExpanded
            renderOcrDebug()
        }
    }

    override fun onDestroy() {
        runCatching { textRecognizer?.close() }
        super.onDestroy()
    }

    private fun startCardCapture() {
        if (!isCameraIntentAvailable()) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val photoUri = createPhotoUri()
        pendingPhotoUri = photoUri
        takePicture.launch(photoUri)
    }

    private fun createPhotoUri(): Uri {
        val scanDirectory = File(cacheDir, "camera_scans").apply { mkdirs() }
        val imageFile = File.createTempFile("trading-card-", ".jpg", scanDirectory)
        return FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            imageFile
        )
    }

    private fun isCameraIntentAvailable(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return intent.resolveActivity(packageManager) != null
    }

    private fun runOcr(imageUri: Uri) {
        scanResult = null
        testPriceResult = null
        latestOcrCandidate = null
        ocrDebugText = ""
        isOcrDebugExpanded = false
        recognitionTextView.text = getString(R.string.ocr_running)
        matchesContainer.removeAllViews()
        ocrDebugButton.visibility = View.GONE
        rawOcrTextView.visibility = View.GONE
        rawOcrTextView.text = ""
        priceTextView.text = getString(R.string.price_idle)

        val recognizer = textRecognizer
        if (recognizer == null) {
            showOcrFailure(getString(R.string.ocr_init_failed))
            return
        }

        runCatching { InputImage.fromFilePath(this, imageUri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { text -> showOcrResult(text) }
                    .addOnFailureListener { showOcrFailure() }
            }
            .onFailure { showOcrFailure() }
    }

    private fun showOcrResult(text: Text) {
        val rawText = text.text.trim()
        val candidate = pokemonOcrAnalyzer.analyze(text)
        latestOcrCandidate = candidate

        recognitionTextView.text = formatScanSummary(candidate, getString(R.string.matching_cards))
        ocrDebugText = buildString {
            appendLine(getString(R.string.raw_ocr_title))
            candidate.debugNotes.forEach { appendLine(it) }
            appendLine()
            append(if (rawText.isBlank()) getString(R.string.value_unavailable) else rawText)
        }
        ocrDebugButton.visibility = View.VISIBLE
        renderOcrDebug()

        findTcgdexMatches(candidate)
    }

    private fun showOcrFailure(message: String = getString(R.string.ocr_failed)) {
        scanResult = null
        latestOcrCandidate = null
        matchesContainer.removeAllViews()
        recognitionTextView.text = message
        ocrDebugText = buildString {
            appendLine(getString(R.string.raw_ocr_title))
            appendLine()
            append(getString(R.string.value_unavailable))
        }
        ocrDebugButton.visibility = View.VISIBLE
        renderOcrDebug()
    }

    private fun formatScanSummary(candidate: OcrCardCandidate, status: String): String {
        return buildString {
            appendLine(getString(R.string.ocr_result_title))
            appendLine()
            appendLine("${getString(R.string.possible_card_name)}: ${candidate.possibleName ?: getString(R.string.value_unavailable)}")
            appendLine("${getString(R.string.possible_card_number)}: ${candidate.possibleNumber ?: getString(R.string.value_unavailable)}")
            append("${getString(R.string.match_status)}: $status")
        }
    }

    private fun findTcgdexMatches(candidate: OcrCardCandidate) {
        matchesContainer.removeAllViews()
        if (candidate.possibleName == null && candidate.numberPrefix == null) {
            recognitionTextView.text = formatScanSummary(candidate, getString(R.string.no_reliable_match_found))
            return
        }

        ocrDebugText = buildString {
            append(ocrDebugText)
            appendLine()
            appendLine()
            appendLine(getString(R.string.match_query))
            append(tcgdexCardMatcher.debugQueries(candidate))
        }
        renderOcrDebug()
        tcgdexCardMatcher.findMatches(candidate) { result ->
            runOnUiThread {
                result
                    .onSuccess { matches -> showMatches(matches) }
                    .onFailure { showMatches(emptyList()) }
            }
        }
    }

    private fun showMatches(matches: List<CardMatch>) {
        matchesContainer.removeAllViews()
        val strongMatch = matches.firstOrNull { it.isStrong }
        scanResult = strongMatch
        val candidate = latestOcrCandidate

        recognitionTextView.text = if (candidate != null) {
            formatScanSummary(
                candidate,
                if (strongMatch != null) getString(R.string.strong_match_found) else getString(R.string.no_reliable_match_found)
            )
        } else {
            if (strongMatch != null) getString(R.string.strong_match_found) else getString(R.string.no_reliable_match_found)
        }

        if (strongMatch != null) {
            matchesContainer.addView(createMatchView(strongMatch))
            matchesContainer.addView(createCardDetailsView(strongMatch.card))
            matchesContainer.addView(createAddToCollectionButton(strongMatch))
        }
    }

    private fun renderOcrDebug() {
        ocrDebugButton.text = getString(
            if (isOcrDebugExpanded) R.string.hide_ocr_debug else R.string.show_ocr_debug
        )
        rawOcrTextView.visibility = if (isOcrDebugExpanded) View.VISIBLE else View.GONE
        rawOcrTextView.text = ocrDebugText
    }

    private fun createMatchView(match: CardMatch): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.info_panel)
        }

        val image = ImageView(this).apply {
            contentDescription = match.card.name
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.preview_frame)
        }
        row.addView(image, LinearLayout.LayoutParams(dp(86), dp(120)))

        val text = TextView(this).apply {
            text = buildString {
                appendLine(match.card.name)
                appendLine("${getString(R.string.match_set)}: ${match.card.setName}")
                appendLine("${getString(R.string.match_rarity)}: ${match.card.rarity ?: getString(R.string.value_unavailable)}")
                appendLine("${getString(R.string.card_number)}: ${match.card.number}/${match.card.setOfficialTotal ?: "?"}")
                append("${getString(R.string.match_confidence)}: ${match.confidence}%")
            }
            setTextColor((0xFF18313B).toInt())
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        match.card.imageUrl?.let { loadCardImage(it, image) }

        return row.apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(10))
            layoutParams = params
        }
    }

    private fun createCardDetailsView(card: CardDetails): View {
        return TextView(this).apply {
            setBackgroundResource(R.drawable.info_panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor((0xFF18313B).toInt())
            textSize = 15f
            text = formatCardDetails(card)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(10))
            layoutParams = params
        }
    }

    private fun createAddToCollectionButton(match: CardMatch): View {
        return Button(this).apply {
            text = getString(R.string.button_add_collection)
            isAllCaps = false
            setTextColor((0xFFFFFFFF).toInt())
            setBackgroundResource(R.drawable.primary_button)
            setOnClickListener {
                collectionRepository.addCard(match.toCollectionCard())
                renderCollection()
                Toast.makeText(this@MainActivity, R.string.collection_added, Toast.LENGTH_SHORT).show()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            params.setMargins(0, 0, 0, dp(12))
            layoutParams = params
        }
    }

    private fun CardMatch.toCollectionCard(): CollectionCard {
        val card = this.card
        val pricing = card.pricing
        return CollectionCard(
            id = "${card.id}-${System.currentTimeMillis()}",
            cardName = card.name,
            setName = card.setName,
            cardNumber = card.number,
            rarity = card.rarity,
            condition = selectedConditionLabel(),
            priceSource = selectedPriceSourceLabel(),
            cardmarketTrendPrice = pricing?.trend,
            currencyCode = pricing?.currencyCode ?: "EUR",
            scanDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, currentLocale()).format(Date()),
            imageUrl = card.imageUrl
        )
    }

    private fun renderCollection() {
        collectionContainer.removeAllViews()
        val cards = collectionRepository.getCards()
        if (cards.isEmpty()) {
            collectionContainer.addView(TextView(this).apply {
                setBackgroundResource(R.drawable.info_panel)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor((0xFF18313B).toInt())
                textSize = 14f
                text = getString(R.string.collection_empty)
            })
            return
        }

        cards.forEach { card ->
            collectionContainer.addView(createCollectionCardView(card))
        }
    }

    private fun createCollectionCardView(card: CollectionCard): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.info_panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        panel.addView(TextView(this).apply {
            setTextColor((0xFF18313B).toInt())
            textSize = 14f
            text = buildString {
                appendLine(card.cardName)
                appendLine("${getString(R.string.match_set)}: ${card.setName}")
                appendLine("${getString(R.string.card_number)}: ${card.cardNumber}")
                appendLine("${getString(R.string.match_rarity)}: ${card.rarity ?: getString(R.string.value_unavailable)}")
                appendLine("${getString(R.string.collection_condition)}: ${card.condition}")
                appendLine("${getString(R.string.collection_price_source)}: ${card.priceSource}")
                appendLine("${getString(R.string.trend_price)}: ${formatPrice(card.cardmarketTrendPrice, card.currencyCode)}")
                append("${getString(R.string.collection_scan_date)}: ${card.scanDate}")
            }
        })

        panel.addView(Button(this).apply {
            text = getString(R.string.button_delete)
            isAllCaps = false
            setTextColor((0xFF164D61).toInt())
            setBackgroundResource(R.drawable.secondary_button)
            setOnClickListener {
                collectionRepository.deleteCard(card.id)
                renderCollection()
                Toast.makeText(this@MainActivity, R.string.collection_deleted, Toast.LENGTH_SHORT).show()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
        })

        return panel.apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(10))
            layoutParams = params
        }
    }

    private fun loadCardImage(imageUrl: String, imageView: ImageView) {
        Thread {
            runCatching {
                val resolvedUrl = if (imageUrl.endsWith(".png")) imageUrl else "$imageUrl/high.png"
                URL(resolvedUrl).openStream().use(BitmapFactory::decodeStream)
            }.onSuccess { bitmap ->
                runOnUiThread { imageView.setImageBitmap(bitmap) }
            }
        }.start()
    }

    private fun loadTestPrice() {
        testPriceButton.isEnabled = false
        priceTextView.text = getString(R.string.price_loading)

        pricingSource.fetchSampleCard { result ->
            runOnUiThread {
                testPriceButton.isEnabled = true
                testPriceResult = result.getOrNull()
                priceTextView.text = result.fold(
                    onSuccess = { card ->
                        buildString {
                            appendLine(getString(R.string.test_price_result))
                            appendLine()
                            append(formatCardDetails(card))
                        }
                    },
                    onFailure = { getString(R.string.price_error) }
                )
            }
        }
    }

    private fun formatCardDetails(card: CardDetails): String {
        val condition = CardCondition.fromSpinnerPosition(conditionSpinner.selectedItemPosition)
        val adjustedPricing = card.pricing?.let { conditionPriceAdjuster.adjust(it, condition) }

        return buildString {
            appendLine("${getString(R.string.card_name)}: ${card.name}")
            appendLine("${getString(R.string.set_name)}: ${card.setName}")
            appendLine("${getString(R.string.match_rarity)}: ${card.rarity ?: getString(R.string.value_unavailable)}")
            appendLine("${getString(R.string.card_number)}: ${card.number}")
            appendLine()

            if (adjustedPricing?.hasAnyPrice == true) {
                append(formatPricing(adjustedPricing))
            } else {
                append(getString(R.string.no_pricing_data))
            }
        }
    }

    private fun formatPricing(pricing: PricingSnapshot): String {
        return buildString {
            appendLine("${getString(R.string.trend_price)}: ${formatPrice(pricing.trend, pricing.currencyCode)}")
            appendLine("${getString(R.string.low_price)}: ${formatPrice(pricing.low, pricing.currencyCode)}")
            appendLine("${getString(R.string.avg30_price)}: ${formatPrice(pricing.average30Days, pricing.currencyCode)}")

            val holo = pricing.holo
            if (holo?.hasAnyPrice == true) {
                appendLine()
                appendLine(getString(R.string.holo_prices))
                appendLine("${getString(R.string.trend_price)}: ${formatPrice(holo.trend, pricing.currencyCode)}")
                appendLine("${getString(R.string.low_price)}: ${formatPrice(holo.low, pricing.currencyCode)}")
                appendLine("${getString(R.string.avg30_price)}: ${formatPrice(holo.average30Days, pricing.currencyCode)}")
            }
        }.trimEnd()
    }

    private fun formatPrice(value: Double?, currencyCode: String): String {
        if (value == null) return getString(R.string.value_unavailable)

        val locale = currentLocale()

        return NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(value)
    }

    private fun currentLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale ?: Locale.getDefault()
        }
    }

    private fun selectedConditionLabel(): String {
        return conditionSpinner.selectedItem?.toString().orEmpty().ifBlank { CardCondition.NearMint.name }
    }

    private fun selectedPriceSourceLabel(): String {
        return sourceSpinner.selectedItem?.toString().orEmpty().ifBlank { getString(R.string.source_cardmarket) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
