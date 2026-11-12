# True Qibla — Android

**القبلة الدقيقة** — a Qibla compass that checks itself.

**⬇ Download:** [TrueQibla.apk](https://github.com/AliSherif1309/TrueQibla-Android/releases/download/latest/TrueQibla.apk). Open it on your Android phone to install (Android 8 or newer).

## Direction and reliability
- Spherical initial great-circle bearing to the Kaaba, clockwise from **true north**.
- Magnetic heading from Android's rotation vector, geomagnetic rotation vector, or accelerometer/magnetometer fallback; east-positive declination is added once.
- Optional game rotation vector smooths relative turns. It cannot find north by itself, and gyro-only reference expires after 30 seconds.
- Strength and dip checks detect some magnetic disturbances. **A matching field does not prove an accurate direction.** Move away from metal, magnets and magnetic phone cases; follow calibration guidance.
- Foreground location updates continue after the first fix, with age and accuracy checks. A saved position cannot enable the live compass until refreshed. Coarse-only permission is supported through the network provider.
- Hold the phone flat, screen up, with the screen's top edge pointing ahead. Guidance is unavailable when location/sensors are stale, the horizontal field is too weak, or the location cannot determine a stable direction near the Kaaba, antipode or geographic poles.

## Indoors
Room averaging is an **approximate** option. Readings from different motion groups may still share a magnetic bias; agreement is not a measurement of absolute accuracy. Neither averaging nor a green field meter guarantees an angular error bound. For an independent check, use a surveyed direction or a suitable solar reference outdoors.

See [ACCURACY_AUDIT.md](ACCURACY_AUDIT.md) for fixes, assumptions, Android magnetic-model limitations, tests and the device validation checklist.

## Settings
- **Method**: Smart (default), Strict (requires passing field checks), or Room average (approximate combined readings).
- **Language**: English / العربية.
- **Theme**: same as phone, light or dark.
- **View**: Simple (arrow and status) or Detailed (field check, coordinates, true-north correction).
- **Vibration guidance**: on or off.

## Features
- Opens straight to the arrow. Nothing to set up.
- **Arabic / English**, with right-to-left layout for Arabic.
- **Dark mode**.
- **Vibration guidance**: ticks get faster as you turn toward the Qibla, then a double buzz when you face it. It can be switched off.
- **Sun over the Kaaba (رصد القبلة)**: on the two days a year when the sun passes over the Kaaba, a card shows an estimated event time for an approximate shadow check (sun must be visible). An optional reminder is in Settings.
- **Prayer-mat mode**: a dark, dimmed screen showing only the arrow, with no vibration. Tap to exit.
- **Mini globe** (Detailed view): the shortest path over the Earth from you to Makkah, with the distance.
- Works offline. No ads, no tracking, no internet permission, and no external libraries.

## How it's built
Kotlin, single screen, no runtime dependencies; JUnit is used for tests. Every push to `main` builds a signed APK with GitHub Actions and publishes it as the **latest** release.

Release signing uses these repository secrets (never commit the keystore):
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS`.

## Build and verify
Use Java 17, Gradle 8.7 and Android SDK 34:

```sh
gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
```

The repository has Gradle wrapper configuration but no wrapper executable/JAR, so use an installed Gradle 8.7. Pull requests run verification; release builds run tests and lint before publication. Physical sensor accuracy still requires device testing.

## License
[MIT](LICENSE): free to use, share and improve. Please keep the copyright notice.
