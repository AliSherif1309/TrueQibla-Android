# True Qibla — Android

**القبلة الدقيقة** — a Qibla compass that checks itself.

**⬇ Download:** [TrueQibla.apk](https://github.com/AliSherif1309/TrueQibla-Android/releases/download/latest/TrueQibla.apk). Open it on your Android phone to install (Android 8 or newer).

## What makes it accurate
- **Gyroscope** (Android's game rotation vector, no magnet): keeps the arrow smooth and steady.
- **Compass, corrected to true north** with Android's built-in World Magnetic Model.
- **Interference check**: the app measures the magnetic field's strength and dip angle and compares them with the field Earth should have at your exact GPS position.
  - **Green**: the field matches, so the arrow is accurate.
  - **Red**: something nearby (steel, cars, speakers, laptops) is bending the field. The compass is ignored, the gyroscope holds the last accurate direction, and the app tells you to move. A live bar shows when you reach a clean spot.

## Features
- Opens straight to the arrow. Nothing to set up.
- **Arabic** interface (right-to-left), used automatically when the phone is set to Arabic.
- **Dark mode**, following the phone's setting.
- **Vibration guidance**: ticks get faster as you turn toward the Qibla, then a double buzz when you face it. It can be switched off.
- Works offline. No ads, no tracking, no internet permission, and no external libraries.

## How it's built
Kotlin, single screen, no dependencies. Every push to `main` builds a signed APK with GitHub Actions and publishes it as the **latest** release.

Release signing uses these repository secrets (never commit the keystore):
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`.
