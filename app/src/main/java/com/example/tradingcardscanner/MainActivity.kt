package com.example.tradingcardscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.example.tradingcardscanner.domain.CardVariant
import com.example.tradingcardscanner.domain.CollectionCard
import com.example.tradingcardscanner.domain.ConditionPriceAdjuster
import com.example.tradingcardscanner.domain.PricingSnapshot
import com.example.tradingcardscanner.domain.VariantPricing
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    private lateinit var manualCorrectionContainer: LinearLayout
    private lateinit var manualNameEditText: EditText
    private lateinit var manualNumberEditText: EditText
    private lateinit var manualSetEditText: EditText
    private lateinit var variantSpinner: Spinner
    private lateinit var searchAgainButton: Button

    private val pricingSource = TcgdexPricingSource()
    private val tcgdexCardMatcher = TcgdexCardMatcher()
    private val conditionPriceAdjuster = ConditionPriceAdjuster()
    private val pokemonOcrAnalyzer = PokemonOcrAnalyzer()
    private lateinit var collectionRepository: CollectionRepository
    private var textRecognizer: TextRecognizer? = null
    private var pendingPhotoUri: Uri? = null
    private var activeScanImageUri: Uri? = null
    private var scanResult: CardMatch? = null
    private var testPriceResult: CardDetails? = null
    private var ocrDebugText = ""
    private var latestOcrCandidate: OcrCardCandidate? = null
    private var isOcrDebugExpanded = false

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingPhotoUri?.let { handleCapturedImage(it) }
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
        manualCorrectionContainer = findViewById(R.id.manualCorrectionContainer)
        manualNameEditText = findViewById(R.id.manualNameEditText)
        manualNumberEditText = findViewById(R.id.manualNumberEditText)
        manualSetEditText = findViewById(R.id.manualSetEditText)
        variantSpinner = findViewById(R.id.variantSpinner)
        searchAgainButton = findViewById(R.id.searchAgainButton)
        collectionRepository = CollectionRepository(this)
        priceTextView.text = getString(R.string.price_idle)
        renderCollection()
        textRecognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrElse {
            recognitionTextView.text = getString(R.string.ocr_init_failed)
            null
        }
        findViewById<Button>(R.id.scanButton).setOnClickListener { startCardCapture() }
        testPriceButton.setOnClickListener { loadTestPrice() }
        ocrDebugButton.setOnClickListener { isOcrDebugExpanded = !isOcrDebugExpanded; renderOcrDebug() }
        searchAgainButton.setOnClickListener { searchAgainFromManualFields() }
    }

    override fun onDestroy() { runCatching { textRecognizer?.close() }; super.onDestroy() }

    private fun startCardCapture() {
        if (!isCameraIntentAvailable()) { Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera() else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun openCamera() { val uri = createPhotoUri(); pendingPhotoUri = uri; activeScanImageUri = null; takePicture.launch(uri) }
    private fun createPhotoUri(): Uri {
        val dir = File(cacheDir, "camera_scans").apply { mkdirs() }
        val file = File.createTempFile("trading-card-", ".jpg", dir)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }
    private fun isCameraIntentAvailable() = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

    private fun handleCapturedImage(originalUri: Uri) {
        val croppedUri = runCatching { cropCardImage(originalUri) }.getOrNull()
        val imageUri = croppedUri ?: originalUri
        activeScanImageUri = imageUri
        if (croppedUri == null) Toast.makeText(this, R.string.auto_crop_failed, Toast.LENGTH_SHORT).show()
        cardImageView.setImageURI(imageUri)
        imagePlaceholderText.visibility = View.GONE
        runOcr(imageUri)
    }

    private fun cropCardImage(originalUri: Uri): Uri? {
        val bitmap = decodeBitmap(originalUri, 2200) ?: return null
        val rect = detectCardRect(bitmap) ?: return null
        val cropped = runCatching { Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height()) }.getOrNull() ?: return null
        return saveCroppedBitmap(cropped)
    }

    private fun decodeBitmap(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSize || bounds.outHeight / sampleSize > maxSize) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun detectCardRect(bitmap: Bitmap): Rect? {
        val detectionBitmap = if (max(bitmap.width, bitmap.height) > 900) {
            val scale = 900f / max(bitmap.width, bitmap.height).toFloat()
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else bitmap
        val borderColor = estimateBorderColor(detectionBitmap)
        val step = max(2, max(detectionBitmap.width, detectionBitmap.height) / 350)
        var minX = detectionBitmap.width
        var minY = detectionBitmap.height
        var maxX = 0
        var maxY = 0
        var hits = 0
        val marginX = detectionBitmap.width / 25
        val marginY = detectionBitmap.height / 25
        var y = marginY
        while (y < detectionBitmap.height - marginY) {
            var x = marginX
            while (x < detectionBitmap.width - marginX) {
                if (isLikelyCardPixel(detectionBitmap.getPixel(x, y), borderColor)) {
                    minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y); hits++
                }
                x += step
            }
            y += step
        }
        if (hits < 80 || minX >= maxX || minY >= maxY) return null
        val padX = ((maxX - minX) * 0.035f).roundToInt()
        val padY = ((maxY - minY) * 0.035f).roundToInt()
        val scaleX = bitmap.width.toFloat() / detectionBitmap.width.toFloat()
        val scaleY = bitmap.height.toFloat() / detectionBitmap.height.toFloat()
        val left = ((minX - padX) * scaleX).roundToInt().coerceIn(0, bitmap.width - 2)
        val top = ((minY - padY) * scaleY).roundToInt().coerceIn(0, bitmap.height - 2)
        val right = ((maxX + padX) * scaleX).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((maxY + padY) * scaleY).roundToInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        val areaRatio = (width * height).toFloat() / (bitmap.width * bitmap.height).toFloat()
        val aspectRatio = width.toFloat() / height.toFloat()
        if (aspectRatio !in 0.50f..0.90f || areaRatio !in 0.18f..0.92f) return null
        return Rect(left, top, right, bottom)
    }

    private fun estimateBorderColor(bitmap: Bitmap): Int {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        val step = max(1, max(bitmap.width, bitmap.height) / 120)
        fun sample(x: Int, y: Int) {
            val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
        }
        var x = 0
        while (x < bitmap.width) { sample(x, 0); sample(x, bitmap.height - 1); x += step }
        var y = 0
        while (y < bitmap.height) { sample(0, y); sample(bitmap.width - 1, y); y += step }
        if (count == 0L) return Color.BLACK
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun isLikelyCardPixel(pixel: Int, borderColor: Int): Boolean {
        val distance = abs(Color.red(pixel) - Color.red(borderColor)) + abs(Color.green(pixel) - Color.green(borderColor)) + abs(Color.blue(pixel) - Color.blue(borderColor))
        val maxChannel = max(Color.red(pixel), max(Color.green(pixel), Color.blue(pixel)))
        val minChannel = min(Color.red(pixel), min(Color.green(pixel), Color.blue(pixel)))
        return distance > 70 || maxChannel - minChannel > 42
    }

    private fun saveCroppedBitmap(bitmap: Bitmap): Uri? {
        val dir = File(filesDir, "collection_scans").apply { mkdirs() }
        val file = File.createTempFile("trading-card-cropped-", ".jpg", dir)
        FileOutputStream(file).use { output -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) return null }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun runOcr(imageUri: Uri) {
        scanResult = null; testPriceResult = null; latestOcrCandidate = null; ocrDebugText = ""; isOcrDebugExpanded = false
        recognitionTextView.text = getString(R.string.ocr_running)
        matchesContainer.removeAllViews(); manualCorrectionContainer.visibility = View.GONE; ocrDebugButton.visibility = View.GONE
        rawOcrTextView.visibility = View.GONE; rawOcrTextView.text = ""; priceTextView.text = getString(R.string.price_idle)
        val recognizer = textRecognizer ?: return showOcrFailure(getString(R.string.ocr_init_failed))
        runCatching { InputImage.fromFilePath(this, imageUri) }
            .onSuccess { image -> recognizer.process(image).addOnSuccessListener { showOcrResult(it) }.addOnFailureListener { showOcrFailure() } }
            .onFailure { showOcrFailure() }
    }

    private fun showOcrResult(text: Text) {
        val rawText = text.text.trim()
        val candidate = pokemonOcrAnalyzer.analyze(text)
        latestOcrCandidate = candidate
        populateManualCorrection(candidate)
        recognitionTextView.text = formatScanSummary(candidate, getString(R.string.matching_cards))
        ocrDebugText = buildString { appendLine(getString(R.string.raw_ocr_title)); candidate.debugNotes.forEach { appendLine(it) }; appendLine(); append(if (rawText.isBlank()) getString(R.string.value_unavailable) else rawText) }
        ocrDebugButton.visibility = View.VISIBLE
        renderOcrDebug()
        findTcgdexMatches(candidate)
    }
    private fun showOcrFailure(message: String = getString(R.string.ocr_failed)) {
        scanResult = null; latestOcrCandidate = null; matchesContainer.removeAllViews(); recognitionTextView.text = message
        ocrDebugText = buildString { appendLine(getString(R.string.raw_ocr_title)); appendLine(); append(getString(R.string.value_unavailable)) }
        ocrDebugButton.visibility = View.VISIBLE; renderOcrDebug()
    }
    private fun populateManualCorrection(candidate: OcrCardCandidate) {
        manualCorrectionContainer.visibility = View.VISIBLE
        manualNameEditText.setText(candidate.possibleName.orEmpty())
        manualNumberEditText.setText(candidate.possibleNumber.orEmpty())
        manualSetEditText.setText(candidate.possibleSetName.orEmpty())
    }
    private fun searchAgainFromManualFields() { val candidate = buildManualCandidate(); latestOcrCandidate = candidate; recognitionTextView.text = formatScanSummary(candidate, getString(R.string.matching_cards)); findTcgdexMatches(candidate) }
    private fun buildManualCandidate(): OcrCardCandidate {
        val numberText = manualNumberEditText.text?.toString().orEmpty().trim()
        val parsed = parseManualNumber(numberText)
        val name = manualNameEditText.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
        val setName = manualSetEditText.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
        return OcrCardCandidate(name, numberText.takeIf { it.isNotBlank() }, parsed.first, parsed.second, 100, listOfNotNull(name, numberText.takeIf { it.isNotBlank() }, setName).joinToString(" "), setName, listOf("manual card name: ${name ?: "none"}", "manual card number: ${numberText.ifBlank { "none" }}", "manual set name: ${setName ?: "none"}"))
    }
    private fun parseManualNumber(value: String): Pair<String?, String?> {
        val normalized = value.uppercase(Locale.ROOT).replace(Regex("""\s+"""), "")
        Regex("""^([A-Z]*0*[0-9]{1,3})/0*([0-9]{1,3})$""").matchEntire(normalized)?.let {
            val prefix = it.groupValues[1].replace(Regex("""^([A-Z]+)0+(\d+)"""), "$1$2").trimStart('0').ifBlank { "0" }
            return prefix to it.groupValues[2].trimStart('0').ifBlank { "0" }
        }
        Regex("""^(SVP|TG|GG)0*([0-9]{1,3})$""").matchEntire(normalized)?.let { return "${it.groupValues[1]}${it.groupValues[2].trimStart('0')}" to null }
        return normalized.takeIf { it.isNotBlank() } to null
    }
    private fun formatScanSummary(candidate: OcrCardCandidate, status: String) = buildString {
        appendLine(getString(R.string.ocr_result_title)); appendLine()
        appendLine("${getString(R.string.possible_card_name)}: ${candidate.possibleName ?: getString(R.string.value_unavailable)}")
        appendLine("${getString(R.string.possible_card_number)}: ${candidate.possibleNumber ?: getString(R.string.value_unavailable)}")
        append("${getString(R.string.match_status)}: $status")
    }
    private fun findTcgdexMatches(candidate: OcrCardCandidate) {
        matchesContainer.removeAllViews()
        if (candidate.possibleName == null && candidate.numberPrefix == null) { recognitionTextView.text = formatScanSummary(candidate, getString(R.string.no_reliable_match_found)); return }
        ocrDebugText = buildString { append(ocrDebugText); appendLine(); appendLine(); appendLine(getString(R.string.match_query)); append(tcgdexCardMatcher.debugQueries(candidate)) }
        renderOcrDebug()
        tcgdexCardMatcher.findMatches(candidate) { result -> runOnUiThread { result.onSuccess(::showMatches).onFailure { showMatches(emptyList()) } } }
    }
    private fun showMatches(matches: List<CardMatch>) {
        matchesContainer.removeAllViews()
        val strong = matches.firstOrNull { it.isStrong }
        recognitionTextView.text = latestOcrCandidate?.let { formatScanSummary(it, if (strong != null) getString(R.string.strong_match_found) else getString(R.string.no_reliable_match_found)) } ?: getString(R.string.no_reliable_match_found)
        if (strong != null) selectMatch(strong) else matches.forEach { matchesContainer.addView(createSelectableMatchView(it)) }
    }
    private fun selectMatch(match: CardMatch) { scanResult = match; matchesContainer.removeAllViews(); matchesContainer.addView(createMatchView(match)); matchesContainer.addView(createCardDetailsView(match.card)); matchesContainer.addView(createAddToCollectionButton(match)) }
    private fun renderOcrDebug() { ocrDebugButton.text = getString(if (isOcrDebugExpanded) R.string.hide_ocr_debug else R.string.show_ocr_debug); rawOcrTextView.visibility = if (isOcrDebugExpanded) View.VISIBLE else View.GONE; rawOcrTextView.text = ocrDebugText }

    private fun createMatchView(match: CardMatch): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundResource(R.drawable.info_panel) }
        val image = ImageView(this).apply { contentDescription = match.card.name; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.preview_frame) }
        row.addView(image, LinearLayout.LayoutParams(dp(86), dp(120)))
        row.addView(TextView(this).apply { text = buildString { appendLine(match.card.name); appendLine("${getString(R.string.match_set)}: ${match.card.setName}"); appendLine("${getString(R.string.match_rarity)}: ${match.card.rarity ?: getString(R.string.value_unavailable)}"); appendLine("${getString(R.string.card_number)}: ${match.card.number}/${match.card.setOfficialTotal ?: "?"}"); append("${getString(R.string.match_confidence)}: ${match.confidence}%") }; setTextColor((0xFF18313B).toInt()); textSize = 14f; setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        loadCardImage(match.card.imageUrl, image, activeScanImageUri?.toString() ?: pendingPhotoUri?.toString())
        return row.withBottomMargin()
    }
    private fun createCardDetailsView(card: CardDetails) = TextView(this).apply { setBackgroundResource(R.drawable.info_panel); setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor((0xFF18313B).toInt()); textSize = 15f; text = formatCardDetails(card) }.withBottomMargin()
    private fun createSelectableMatchView(match: CardMatch): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.info_panel); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        panel.addView(createMatchView(match)); panel.addView(TextView(this).apply { setTextColor((0xFF18313B).toInt()); textSize = 13f; text = formatAvailablePriceSummary(match.card) })
        panel.addView(Button(this).apply { text = getString(R.string.button_select_card); isAllCaps = false; setTextColor((0xFFFFFFFF).toInt()); setBackgroundResource(R.drawable.primary_button); setOnClickListener { selectMatch(match) } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        return panel.withBottomMargin()
    }
    private fun createAddToCollectionButton(match: CardMatch) = Button(this).apply { text = getString(R.string.button_add_collection); isAllCaps = false; setTextColor((0xFFFFFFFF).toInt()); setBackgroundResource(R.drawable.primary_button); setOnClickListener { collectionRepository.addCard(match.toCollectionCard()); renderCollection(); Toast.makeText(this@MainActivity, R.string.collection_added, Toast.LENGTH_SHORT).show() } }.withFixedHeight(dp(48))
    private fun CardMatch.toCollectionCard(): CollectionCard {
        val card = this.card; val pricing = card.pricing; val adjusted = pricing?.let { conditionPriceAdjuster.adjust(it, CardCondition.fromSpinnerPosition(conditionSpinner.selectedItemPosition)) }
        return CollectionCard("${card.id}-${System.currentTimeMillis()}", card.name, card.setName, card.number, card.rarity, selectedConditionLabel(), selectedVariantLabel(), selectedPriceSourceLabel(), pricing?.trend, card.id, selectedVariantPrice(adjusted), pricing?.currencyCode ?: "EUR", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, currentLocale()).format(Date()), card.imageUrl, activeScanImageUri?.toString() ?: pendingPhotoUri?.toString())
    }
    private fun renderCollection() { collectionContainer.removeAllViews(); val cards = collectionRepository.getCards(); if (cards.isEmpty()) { collectionContainer.addView(TextView(this).apply { setBackgroundResource(R.drawable.info_panel); setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor((0xFF18313B).toInt()); textSize = 14f; text = getString(R.string.collection_empty) }); return }; cards.forEach { collectionContainer.addView(createCollectionCardView(it)) } }
    private fun createCollectionCardView(card: CollectionCard): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.info_panel); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thumbnail = ImageView(this).apply { contentDescription = card.cardName; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.preview_frame) }
        row.addView(thumbnail, LinearLayout.LayoutParams(dp(72), dp(100)))
        row.addView(TextView(this).apply { setTextColor((0xFF18313B).toInt()); textSize = 14f; setPadding(dp(12), 0, 0, 0); text = buildString { appendLine(card.cardName); appendLine("${getString(R.string.match_set)}: ${card.setName}"); appendLine("${getString(R.string.card_number)}: ${card.cardNumber}"); appendLine("${getString(R.string.match_rarity)}: ${card.rarity ?: getString(R.string.value_unavailable)}"); appendLine("${getString(R.string.collection_condition)}: ${card.condition}"); appendLine("${getString(R.string.variant)}: ${card.variant}"); appendLine("${getString(R.string.collection_price_source)}: ${card.priceSource}"); appendLine("${getString(R.string.selected_variant_price)}: ${formatPrice(card.priceUsed, card.currencyCode)}"); append("${getString(R.string.collection_scan_date)}: ${card.scanDate}") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        loadCollectionThumbnail(card, thumbnail)
        panel.addView(Button(this).apply { text = getString(R.string.button_delete); isAllCaps = false; setTextColor((0xFF164D61).toInt()); setBackgroundResource(R.drawable.secondary_button); setOnClickListener { collectionRepository.deleteCard(card.id); renderCollection(); Toast.makeText(this@MainActivity, R.string.collection_deleted, Toast.LENGTH_SHORT).show() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        return panel.withBottomMargin()
    }
    private fun loadCardImage(imageUrl: String?, imageView: ImageView, fallbackLocalUri: String? = null) {
        if (imageUrl.isNullOrBlank()) { loadLocalImage(fallbackLocalUri, imageView); return }
        Thread {
            val bitmap = imageUrl.candidateImageUrls().firstNotNullOfOrNull { candidate -> runCatching { URL(candidate).openStream().use(BitmapFactory::decodeStream) }.getOrNull() }
            runOnUiThread { if (bitmap != null) imageView.setImageBitmap(bitmap) else loadLocalImage(fallbackLocalUri, imageView) }
        }.start()
    }
    private fun loadCollectionThumbnail(card: CollectionCard, imageView: ImageView) = loadCardImage(card.imageUrl, imageView, card.localScanPhotoUri)
    private fun loadLocalImage(localImageUri: String?, imageView: ImageView) { if (!localImageUri.isNullOrBlank()) runCatching { imageView.setImageURI(Uri.parse(localImageUri)) } }
    private fun String.candidateImageUrls(): List<String> {
        val trimmed = trim().trimEnd('/')
        if (trimmed.endsWith(".png", true) || trimmed.endsWith(".webp", true) || trimmed.endsWith(".jpg", true) || trimmed.endsWith(".jpeg", true)) {
            val high = trimmed.replace("/low.png", "/high.png", true).replace("/low.webp", "/high.webp", true).replace("/low.jpg", "/high.jpg", true).replace("/low.jpeg", "/high.jpeg", true)
            val low = trimmed.replace("/high.png", "/low.png", true).replace("/high.webp", "/low.webp", true).replace("/high.jpg", "/low.jpg", true).replace("/high.jpeg", "/low.jpeg", true)
            return listOf(high, trimmed, low).distinct()
        }
        return listOf("$trimmed/high.png", "$trimmed/low.png")
    }
    private fun loadTestPrice() { testPriceButton.isEnabled = false; priceTextView.text = getString(R.string.price_loading); pricingSource.fetchSampleCard { result -> runOnUiThread { testPriceButton.isEnabled = true; testPriceResult = result.getOrNull(); priceTextView.text = result.fold(onSuccess = { buildString { appendLine(getString(R.string.test_price_result)); appendLine(); append(formatCardDetails(it)) } }, onFailure = { getString(R.string.price_error) }) } } }
    private fun formatCardDetails(card: CardDetails): String { val adjusted = card.pricing?.let { conditionPriceAdjuster.adjust(it, CardCondition.fromSpinnerPosition(conditionSpinner.selectedItemPosition)) }; return buildString { appendLine("${getString(R.string.card_name)}: ${card.name}"); appendLine("${getString(R.string.set_name)}: ${card.setName}"); appendLine("${getString(R.string.match_rarity)}: ${card.rarity ?: getString(R.string.value_unavailable)}"); appendLine("${getString(R.string.card_number)}: ${card.number}"); appendLine(getString(R.string.verify_older_cards)); appendLine(); append(if (adjusted?.hasAnyPrice == true) formatPricing(adjusted) else getString(R.string.no_pricing_data)) } }
    private fun formatPricing(pricing: PricingSnapshot): String = buildString { appendLine("${getString(R.string.selected_variant_price)}: ${formatPrice(selectedVariantPrice(pricing), pricing.currencyCode)}"); if (selectedVariantPrice(pricing) == null) appendLine(getString(R.string.no_variant_price)); appendLine(); appendLine(getString(R.string.normal_price)); appendLine("${getString(R.string.trend_price)}: ${formatPrice(pricing.trend, pricing.currencyCode)}"); appendLine("${getString(R.string.low_price)}: ${formatPrice(pricing.low, pricing.currencyCode)}"); appendLine("${getString(R.string.avg30_price)}: ${formatPrice(pricing.average30Days, pricing.currencyCode)}"); pricing.holo?.takeIf { it.hasAnyPrice }?.let { appendLine(); appendLine(getString(R.string.holo_prices)); appendLine(formatVariantPricing(it, pricing.currencyCode)) }; pricing.reverseHolo?.takeIf { it.hasAnyPrice }?.let { appendLine(); appendLine(getString(R.string.reverse_holo_prices)); append(formatVariantPricing(it, pricing.currencyCode)) } }.trimEnd()
    private fun formatVariantPricing(pricing: VariantPricing, currencyCode: String) = buildString { appendLine("${getString(R.string.trend_price)}: ${formatPrice(pricing.trend, currencyCode)}"); appendLine("${getString(R.string.low_price)}: ${formatPrice(pricing.low, currencyCode)}"); append("${getString(R.string.avg30_price)}: ${formatPrice(pricing.average30Days, currencyCode)}") }
    private fun formatAvailablePriceSummary(card: CardDetails): String { val p = card.pricing ?: return getString(R.string.no_pricing_data); return buildString { appendLine("${getString(R.string.normal_price)}: ${formatPrice(p.trend, p.currencyCode)}"); appendLine("${getString(R.string.holo_prices)}: ${formatPrice(p.holo?.trend, p.currencyCode)}"); append("${getString(R.string.reverse_holo_prices)}: ${formatPrice(p.reverseHolo?.trend, p.currencyCode)}") } }
    private fun selectedVariantPrice(pricing: PricingSnapshot?): Double? { pricing ?: return null; return when (CardVariant.fromSpinnerPosition(variantSpinner.selectedItemPosition)) { CardVariant.Holo -> pricing.holo?.trend; CardVariant.ReverseHolo -> pricing.reverseHolo?.trend; else -> pricing.trend } }
    private fun formatPrice(value: Double?, currencyCode: String): String { if (value == null) return getString(R.string.value_unavailable); return NumberFormat.getCurrencyInstance(currentLocale()).apply { currency = Currency.getInstance(currencyCode) }.format(value) }
    private fun currentLocale(): Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) resources.configuration.locales[0] else { @Suppress("DEPRECATION") resources.configuration.locale ?: Locale.getDefault() }
    private fun selectedConditionLabel() = conditionSpinner.selectedItem?.toString().orEmpty().ifBlank { CardCondition.NearMint.name }
    private fun selectedPriceSourceLabel() = sourceSpinner.selectedItem?.toString().orEmpty().ifBlank { getString(R.string.source_cardmarket) }
    private fun selectedVariantLabel() = variantSpinner.selectedItem?.toString().orEmpty().ifBlank { "Normal" }
    private fun View.withBottomMargin(): View { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }; return this }
    private fun Button.withFixedHeight(height: Int): Button { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { setMargins(0, 0, 0, dp(12)) }; return this }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
