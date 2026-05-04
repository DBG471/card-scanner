package com.example.tradingcardscanner;

import static com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG;
import static com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final int BATCH_PAGE_LIMIT = 40;

    private final List<File> scans = new ArrayList<>();
    private ActivityResultLauncher<IntentSenderRequest> scannerLauncher;
    private GridLayout galleryGrid;
    private TextView countText;
    private TextView emptyText;
    private Button shareAllButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerScannerLauncher();
        buildUi();
        refreshGallery();
    }

    private void registerScannerLauncher() {
        scannerLauncher = registerForActivityResult(new StartIntentSenderForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK) {
                return;
            }

            GmsDocumentScanningResult scanResult =
                    GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
            if (scanResult == null || scanResult.getPages() == null || scanResult.getPages().isEmpty()) {
                showToast("No scans were returned.");
                return;
            }

            int savedCount = 0;
            for (GmsDocumentScanningResult.Page page : scanResult.getPages()) {
                try {
                    saveScan(page.getImageUri(), savedCount + 1);
                    savedCount++;
                } catch (IOException exception) {
                    showToast("Could not save one scan.");
                }
            }

            refreshGallery();
            showToast(savedCount == 1 ? "Saved 1 cropped scan." : "Saved " + savedCount + " cropped scans.");
        });
    }

    private void buildUi() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F6F2);
        root.setPadding(padding, padding, padding, 0);

        TextView title = new TextView(this);
        title.setText("Trading Card Scanner");
        title.setTextColor(0xFF162522);
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Batch scan cards, auto crop and straighten, then save locally.");
        subtitle.setTextColor(0xFF4C5A56);
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, 0, 0, dp(18));

        Button scanButton = createButton("Start scan", true);
        scanButton.setOnClickListener(view -> startScanSession());
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(52), 1));

        shareAllButton = createButton("Share", false);
        shareAllButton.setOnClickListener(view -> shareScans());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        shareParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(shareAllButton, shareParams);
        root.addView(actions);

        countText = new TextView(this);
        countText.setTextColor(0xFF162522);
        countText.setTextSize(18);
        countText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countText.setPadding(0, 0, 0, dp(10));
        root.addView(countText);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout galleryContainer = new LinearLayout(this);
        galleryContainer.setOrientation(LinearLayout.VERTICAL);

        emptyText = new TextView(this);
        emptyText.setText("No local scans yet");
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextColor(0xFF6A716E);
        emptyText.setTextSize(16);
        emptyText.setBackgroundResource(R.drawable.empty_gallery);
        galleryContainer.addView(emptyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)));

        galleryGrid = new GridLayout(this);
        galleryGrid.setColumnCount(2);
        galleryContainer.addView(galleryGrid);

        scrollView.addView(galleryContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        setContentView(root);
    }

    private Button createButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(primary ? 0xFFFFFFFF : 0xFF2A6F68);
        button.setBackgroundResource(primary ? R.drawable.primary_button : R.drawable.secondary_button);
        return button;
    }

    private void startScanSession() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(BATCH_PAGE_LIMIT)
                .setResultFormats(RESULT_FORMAT_JPEG)
                .setScannerMode(SCANNER_MODE_FULL)
                .build();

        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);
        scanner.getStartScanIntent(this)
                .addOnSuccessListener(intentSender ->
                        scannerLauncher.launch(new IntentSenderRequest.Builder(intentSender).build()))
                .addOnFailureListener(exception ->
                        showToast("Scanner is unavailable on this device."));
    }

    private void saveScan(Uri sourceUri, int position) throws IOException {
        File scanDirectory = getScanDirectory();
        if (!scanDirectory.exists() && !scanDirectory.mkdirs()) {
            throw new IOException("Unable to create scan directory.");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File destination = new File(scanDirectory, "card_" + stamp + "_" + position + ".jpg");

        ContentResolver resolver = getContentResolver();
        try (InputStream input = resolver.openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("No input stream for scan.");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void refreshGallery() {
        scans.clear();
        File[] files = getScanDirectory().listFiles((directory, name) -> name.toLowerCase(Locale.US).endsWith(".jpg"));
        if (files != null) {
            scans.addAll(Arrays.asList(files));
            Collections.sort(scans, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        }

        countText.setText(scans.size() == 1 ? "1 saved scan" : scans.size() + " saved scans");
        shareAllButton.setEnabled(!scans.isEmpty());
        emptyText.setVisibility(scans.isEmpty() ? View.VISIBLE : View.GONE);

        galleryGrid.removeAllViews();
        for (File scan : scans) {
            galleryGrid.addView(createScanTile(scan), tileParams());
        }
    }

    private View createScanTile(File scan) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setBackgroundResource(R.drawable.card_panel);

        ImageView image = new ImageView(this);
        image.setImageURI(Uri.fromFile(scan));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)));

        TextView name = new TextView(this);
        name.setText(scan.getName());
        name.setTextSize(12);
        name.setTextColor(0xFF4C5A56);
        name.setSingleLine(true);
        name.setPadding(0, dp(8), 0, 0);
        tile.addView(name);

        tile.setOnClickListener(view -> showScanPreview(scan));
        return tile;
    }

    private void showScanPreview(File scan) {
        ImageView preview = new ImageView(this);
        preview.setImageURI(Uri.fromFile(scan));
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = dp(12);
        preview.setPadding(inset, inset, inset, inset);

        new AlertDialog.Builder(this)
                .setTitle(scan.getName())
                .setView(preview)
                .setPositiveButton("Share", (dialog, which) -> shareSingleScan(scan))
                .setNegativeButton("Close", null)
                .show();
    }

    private GridLayout.LayoutParams tileParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = (getResources().getDisplayMetrics().widthPixels - dp(56)) / 2;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.setMargins(0, 0, dp(10), dp(12));
        return params;
    }

    private void shareSingleScan(File scan) {
        Uri uri = contentUriFor(scan);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), scan.getName(), uri));
        startActivity(Intent.createChooser(intent, "Share scan"));
    }

    private void shareScans() {
        if (scans.isEmpty()) {
            return;
        }

        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = null;
        for (File scan : scans) {
            Uri uri = contentUriFor(scan);
            uris.add(uri);
            if (clipData == null) {
                clipData = ClipData.newUri(getContentResolver(), scan.getName(), uri);
            } else {
                clipData.addItem(new ClipData.Item(uri));
            }
        }

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("image/jpeg");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(clipData);
        startActivity(Intent.createChooser(intent, "Share scans"));
    }

    private Uri contentUriFor(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + AUTHORITY_SUFFIX, file);
    }

    private File getScanDirectory() {
        return new File(getFilesDir(), "scans");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
