# Trading Card Scanner

Native Android scanner app for trading-card capture workflows.

## Features

- Starts a Google ML Kit Document Scanner session
- Uses ML Kit's document scanner UI for rectangular edge detection
- Auto crops, straightens, and applies perspective correction through ML Kit
- Supports batch scanning up to 40 cards in one session
- Saves cropped JPEG scans locally under the app's private files directory
- Shows saved scans in a simple local gallery
- Opens a full-image preview from the gallery and supports sharing scans

## Cloud build

The project is configured for Codemagic in `codemagic.yaml`.

Use the `android-debug` workflow. It runs:

```sh
gradle assembleDebug
```

The debug APK artifact is collected from:

```text
app/build/outputs/**/*.apk
```

## Dependencies

Declared in `app/build.gradle`:

- `androidx.activity:activity:1.9.3`
- `androidx.core:core:1.13.1`
- `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`

No card recognition, price lookup, accounts, or network product features are included.
