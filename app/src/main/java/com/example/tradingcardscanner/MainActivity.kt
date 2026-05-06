package com.example.tradingcardscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.tradingcardscanner.data.OcrCardCandidate
import com.example.tradingcardscanner.data.OcrCardCandidateExtractor
import com.example.tradingcardscanner.data.TcgdexPricingSource
import com.example.tradingcardscanner.domain.CardCondition
import com.example.tradingcardscanner.domain.CardDetails
import com.example.tradingcardscanner.domain.ConditionPriceAdjuster
import com.example.tradingcardscanner.domain.PricingSnapshot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var cardImageView: ImageView
    private lateinit var imagePlaceholderText: TextView
    private lateinit var conditionSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var recognitionTextView: TextView
    private lateinit var rawOcrTextView: TextView
    private lateinit var priceTextView: TextView
    private lateinit var testPriceButton: Button

    private val pricingSource = TcgdexPricingSource()
    private val conditionPriceAdjuster = ConditionPriceAdjuster()
    private val ocrCandidateExtractor = OcrCardCandidateExtractor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var pendingPhotoUri: Uri? = null

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
        rawOcrTextView = findViewById(R.id.rawOcrTextView)
        priceTextView = findViewById(R.id.priceTextView)
        testPriceButton = findViewById(R.id.testPriceButton)

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            startCardCapture()
        }
        testPriceButton.setOnClickListener {
            loadTestPrice()
        }
    }

    override fun onDestroy() {
        textRecognizer.close()
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
        recognitionTextView.text = getString(R.string.ocr_running)
        rawOcrTextView.text = getString(R.string.raw_ocr_title)

        runCatching { InputImage.fromFilePath(this, imageUri) }
            .onSuccess { image ->
                textRecognizer.process(image)
                    .addOnSuccessListener(::showOcrResult)
                    .addOnFailureListener { showOcrFailure() }
            }
            .onFailure { showOcrFailure() }
    }

    private fun showOcrResult(text: Text) {
        val rawText = text.text.trim()
        val candidate = ocrCandidateExtractor.extract(rawText)

        recognitionTextView.text = formatOcrCandidate(candidate)
        rawOcrTextView.text = buildString {
            appendLine(getString(R.string.raw_ocr_title))
            appendLine()
            append(if (rawText.isBlank()) getString(R.string.value_unavailable) else rawText)
        }
    }

    private fun showOcrFailure() {
        recognitionTextView.text = getString(R.string.ocr_failed)
        rawOcrTextView.text = buildString {
            appendLine(getString(R.string.raw_ocr_title))
            appendLine()
            append(getString(R.string.value_unavailable))
        }
    }

    private fun formatOcrCandidate(candidate: OcrCardCandidate): String {
        return buildString {
            appendLine(getString(R.string.ocr_result_title))
            appendLine()

            if (!candidate.isReliable) {
                appendLine(getString(R.string.card_not_recognized))
            }

            appendLine("${getString(R.string.possible_card_name)}: ${candidate.possibleName ?: getString(R.string.value_unavailable)}")
            appendLine("${getString(R.string.possible_card_number)}: ${candidate.possibleNumber ?: getString(R.string.value_unavailable)}")
            appendLine("${getString(R.string.recognition_confidence)}: ${candidate.confidenceScore}%")
            appendLine()
            append(getString(R.string.next_tcgdex_matching))
        }
    }

    private fun loadTestPrice() {
        testPriceButton.isEnabled = false
        priceTextView.text = getString(R.string.price_loading)

        pricingSource.fetchSampleCard { result ->
            runOnUiThread {
                testPriceButton.isEnabled = true
                priceTextView.text = result.fold(
                    onSuccess = ::formatCardDetails,
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

        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale ?: Locale.getDefault()
        }

        return NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(value)
    }
}
