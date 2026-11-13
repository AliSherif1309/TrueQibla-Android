# Arabic typography

Thmanyah Sans is the user's preferred Arabic UI font. Arabic runs use Regular, Medium or Bold; Latin text and numerals retain the original text paint. Existing spans, characters, editing selection, sizes and RTL direction are preserved. Dialog windows and dynamic labels are registered separately. Android widgets are excluded. SahlPDF's text-mode page view is excluded so reader font settings and PDF rendering keep their existing behavior.

## Offline packaging and source

Per the user's instruction to reuse their existing SahlTV approach, tools/prepare_thmanyah.py copies the already-obfuscated r/m/b assets from their published SahlTV build-48 APK. The APK and all selected payloads are pinned by SHA-256. Metadata inspected in memory identifies thmanyah sans Regular/Medium/Bold, version 1.003. No font modifications or raw font files are committed. Gradle stages assets into app/build/generated/thmanyah/assets and fails if the source or payload checksums differ. No FONT_KEY secret needs copying and SahlTV is unchanged.

The first build needs Python 3 and network access to the pinned GitHub release. Subsequent builds can reuse .gradle/thmanyah/SahlTV-build-48.apk. To provision without network, copy the verified APK there, or run the preparation script with --source-apk and --output. The installed app never downloads fonts; it loads them into private no-backup storage using SahlTV's format. Missing or corrupt assets/cache fall back to system typography without crashing.

Official family: https://font.thmanyah.com/
Controlling Arabic license: https://font.thmanyah.com/licenses
English translation: https://font.thmanyah.com/licenses-en

Thmanyah permits app use and compiled/packaged/obfuscated embedding, but restricts raw-file sharing and independent extraction. The user requested reuse of their existing packaged implementation and confirmed they have no separate written permission. This implementation follows that instruction; it does not certify that obfuscation satisfies every distribution restriction. The font remains separately licensed by Thmanyah; the repository's software license does not relicense the font. Do not export, modify or redistribute raw font files. Acquire future updates from the official source and review applicable terms before changing distribution.

## Checks

- python -m unittest discover -s tools -p test_prepare_thmanyah.py
- Android unit tests include Unicode ranges, mixed text, unchanged non-Arabic typeface, existing spans, dynamic edits, hints, stable span counts, selection and corrupt-cache fallback.
- Run the repository's Android unit-test, lint and build tasks. Check the resulting APK with python tools/prepare_thmanyah.py --check-apk PATH_TO_APK. This verifies exact offline font payloads and rejects raw Thmanyah font filenames.
- Device acceptance still requires Arabic/English locales, RTL, long labels/dialogs, large accessibility fonts, clipping and airplane-mode cold launches. CI packaging and Robolectric checks do not replace those visual checks.
