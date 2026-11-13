# Arabic typography implementation status

## Preference and status

Thmanyah is the user's preferred Arabic typeface. Runtime integration is **not completed by this PR**. This PR records the preference, inspected integration points and required checks; it does not add fonts, change UI typography or certify existing packaging.

## Official source and licensing review

Reviewed 2026-11-13:
- Official family page: https://font.thmanyah.com/
- Controlling Arabic license: https://font.thmanyah.com/licenses
- English translation: https://font.thmanyah.com/licenses-en

The official page advertises Sans, Serif Display and Serif Text, five weights each. The download uses an email form. No new download was submitted; actual downloadable filenames, current version, checksums and glyph coverage remain unverified.

The license permits personal/commercial app use and embedding in compiled, packaged or obfuscated products. It also restricts sharing/hosting font files, independent extraction/reuse, modification and derivatives. Only written exceptions from Thmanyah are effective; the Arabic text prevails. App-use permission is not blanket permission to place raw font files in GitHub.

The user has confirmed that they have no separate written permission and requested SahlTV's packaging approach. SahlTV's encrypted repository assets plus obfuscated APK assets are the architectural reference, but runtime reconstruction with an embedded key does not by itself establish compliance with the extraction restriction. Obtain clarification covering repository storage, CI processing, APK distribution and runtime extraction before expanding font distribution. Do not infer permission from existing code or use base64/XOR/encryption as a substitute for permission.

## Inspected repository

The main UI is programmatic Kotlin in `app/src/main/java/com/aly/trueqibla/MainActivity.kt`, with explicit bold Typeface calls and English/Arabic strings. No bundled font files were found. Cover settings, sensor guidance, reminders, and custom-drawn compass labels without changing numerical or direction calculations.

## Integration requirements after clearance

1. Obtain official, authorized font files and record version, weights, source, SHA-256 and applicable permission; preserve notices. Do not publish font binaries or secret keys.
2. Configure an authorized protected build input. Repository secrets cannot be read or transferred from SahlTV by this task. Use an offline runtime package and private no-backup storage; no runtime font download.
3. Use Sans for ordinary Arabic UI; opt into Display/Text by semantic role. Preserve Latin and monospace typography in mixed text, explicit Quran fonts, document rendering and third-party views. Preserve weight and span behavior.
4. Handle dynamic views, changed text, editable inputs, hints, dialogs, popup windows and service-owned views where applicable. Keep system-font fallback when font provisioning fails.
5. Submit a separate implementation PR with the following evidence. Keep it unmerged until relevant CI passes.

## Required acceptance checks

- Automated: Arabic-run selection including diacritics and supplementary Arabic characters; mixed Arabic/Latin/numerals; normal/medium/bold; preserved existing spans; no repeated scaling; missing/corrupt assets; release packaging excludes raw font files and keys.
- Device: Arabic and English locales, RTL, long labels, menus/dialogs/inputs, text updates, supported API levels, large accessibility fonts, no clipping and consistent baselines.
- Offline: cold start and relaunch in airplane mode, with expected font assets present; separately prove system fallback with them absent.
- Regression: existing unit tests, lint and build; relevant rendering/instrumentation tests. Record exact commands, commit and CI run URLs.
- Security/licensing: inspect repository diff and final APK contents; verify authorized distribution conditions. Obfuscation is not a compliance test.

## Validation of this PR

Read-only inspection of the default-branch file tree, README, manifest and main UI/theme files. No runtime code changes; no Android build, device tests or font binary verification were performed. Existing CI status must be checked on this PR; documentation checks cannot prove typography integration.
