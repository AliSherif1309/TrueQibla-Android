# GitHub in-app updates

## What users see

Version 1.6 adds a once-per-day check when the app is opened and a manual Settings button. Automatic checks do not download anything. An available release asks the user before using Download Manager; a verified download opens Android's package installer after a further Install tap. Android retains control over confirmation and unknown-source permission. This is not a silent installer, a background polling service or a Play Store updater.

English and Arabic strings are provided. A network failure never disables the compass. The automatic check timestamp handles wall-clock rollback by allowing a fresh check; manual checks bypass the daily interval. No location or sensor data is included in update requests. IP address and normal HTTPS headers are visible to GitHub. No new tracking SDK is included.

## Validation and recovery

- Release discovery uses the public GitHub latest-release API, excluding drafts/prereleases, and requires `update.json` and `TrueQibla.apk` in the same release.
- Metadata and APK URLs must use HTTPS on github.com under the exact repository release path. No user-controlled update server is accepted. Cleartext traffic is disabled. GitHub's normal HTTPS asset redirects are supported by Android.
- JSON is limited to 256 KiB with connection/read timeouts. The manifest must have a positive Android version code, a 64-digit hexadecimal SHA-256 and size <=50 MiB, matching the GitHub APK asset's reported size.
- A finished download is copied to an app-private temporary file with bounded byte counting and SHA-256 verification. PackageManager then checks package identity, exact offered version, newer-than-installed version, compatible minimum SDK and equality of nonempty current APK signing-certificate sets. No key rotation/lineage exceptions are implemented.
- Only after validation is the temporary file atomically moved to the installable cache path. The non-exported provider exposes exactly that one file, read-only, through a temporary URI grant. Android's installer independently verifies the package signature.
- Download Manager owns background transfer and retries. Its ID and expected metadata are retained across process death. Completion is inspected while the app is resumed; no background receiver starts installation. Verification is serialized across activity instances. Paused/destroyed activities do not display dialogs.
- Installation-source permission is requested only after Install. If granted, the current activity resumes installation; if the process died, use the Settings button after reopening. Cancellation keeps the verified download available. Cache eviction or failed/removed downloads produce retry guidance. After a successful update is opened, old download records and the cached APK are removed.
- The same persistent release key must be used across builds. The release workflow now fails explicitly without signing secrets. A phone running an older debug-signed build may require a one-time migration to the official release; do not uninstall without considering local settings.

## Release pipeline

`scripts/make_update_manifest.py` reads the universal release APK and Gradle's `output-metadata.json`; it does not infer app version from the human-readable release title. `update.json` contains versionCode, versionName, SHA-256 and byte size. The workflow creates a draft `build-GITHUB_RUN_NUMBER` release, uploads both files, then publishes it as latest. This keeps existing releases and avoids advertising a release before both assets are present. The download link is `/releases/latest/download/TrueQibla.apk`.

VersionCode continues to be `100 + GITHUB_RUN_NUMBER` in CI. Keep that workflow's run sequence and application ID stable. A rerun after an already-published `build-N` release will not overwrite it silently; use a new workflow run for a new release. Never rebuild and replace an APK in an existing release with a different binary.

## Verification

Automated tests cover repository URL restrictions (including credentials, path traversal and alternate hosts), manifest bounds, SHA-256 reference/tampering, newer-version rules, package/signing mismatch and release-manifest generation from exact APK bytes and build metadata. Existing Qibla tests remain included.

Run:

```sh
python -m unittest discover -s scripts -p 'test_*.py'
gradle testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon
python scripts/make_update_manifest.py app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/output-metadata.json update.json
```

Local release builds without signing secrets use the debug key for testing; distribute the official GitHub Actions release to phones.

Physical-device validation still required:

1. Install the first official 1.6 build, then publish a higher-version release with the same key; use Settings to download/install it and confirm existing language/theme/compass settings remain.
2. Exercise Allow from this source, denial, return from Android settings, installer cancellation and successful installation on Android 8 and a current Android release.
3. Check offline startup and manual checks, interrupted/paused downloads, process termination during download, low storage, cache eviction and a removed Download Manager entry.
4. Confirm equal/older versions never prompt, and changed bytes, package identity or signing key cannot install.
5. Verify the compass remains responsive during checks and downloads. Reopen after a background download and complete the install from Settings.

References: [GitHub releases](https://docs.github.com/en/rest/releases/releases), [Android Download Manager](https://developer.android.com/reference/android/app/DownloadManager), [Android package installation](https://developer.android.com/reference/android/content/pm/PackageInstaller).
