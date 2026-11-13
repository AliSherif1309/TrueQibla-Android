package com.aly.trueqibla

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Optional GitHub updates; network/disk work never runs on the compass/UI thread. */
class AppUpdates(private val activity: Activity, private val arabic: Boolean) {
    companion object { private val worker = Executors.newSingleThreadExecutor() }
    private data class Offer(val code: Long, val version: String, val hash: String, val size: Long, val url: String)
    private val prefs = activity.getSharedPreferences("updates", Activity.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val downloads = activity.getSystemService(DownloadManager::class.java)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var offer: Offer? = null
    private var resumed = false
    @Volatile private var closed = false
    private var busy = false
    private var ready = false
    private var permissionPending = false
    private var dialog: AlertDialog? = null
    var onChanged: (() -> Unit)? = null
    private fun t(en: String, ar: String) = if (arabic) ar else en
    private fun toast(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun installed() = activity.packageManager.getPackageInfo(activity.packageName, signatureFlags())
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { UpdatePolicy.sha256(it.toByteArray()) }?.toSet() ?: emptySet()

    val label: String get() = when {
        busy -> t("Checking update…", "جارٍ فحص التحديث…")
        ready -> t("Install downloaded update", "تثبيت التحديث المحمّل")
        prefs.getLong("download", -1) != -1L -> t("Downloading update… Tap to check", "جارٍ تنزيل التحديث… اضغط للتحقق")
        offer != null -> t("Update available · ${offer!!.version}", "تحديث متاح · ${offer!!.version}")
        else -> t("Check for updates", "التحقق من التحديثات")
    }

    fun onResume() {
        resumed = true
        if (prefs.getLong("code", Long.MAX_VALUE) <= code(installed())) {
            clearDownload(); apk.delete(); ready = false
        }
        if (permissionPending) { permissionPending = false; if (activity.packageManager.canRequestPackageInstalls()) install() }
        if (ready) return
        if (prefs.getLong("download", -1) != -1L) poll.run() else checkForUpdates(false)
    }

    fun onPause() { resumed = false; handler.removeCallbacks(poll); dialog?.dismiss(); dialog = null }
    fun close() { closed = true; onPause(); handler.removeCallbacksAndMessages(null) }

    fun click() {
        when {
            busy -> Unit
            ready -> install()
            prefs.getLong("download", -1) != -1L -> poll.run()
            offer != null -> showOffer()
            else -> checkForUpdates(true)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        val now = System.currentTimeMillis()
        val elapsed = now - prefs.getLong("lastCheck", 0)
        if (busy || ready || !manual && elapsed in 0 until UpdatePolicy.CHECK_INTERVAL_MS) return
        busy = true; onChanged?.invoke()
        // Automatic failures are quiet and rate limited, including when offline.
        prefs.edit().putLong("lastCheck", now).apply()
        worker.execute {
            try {
                val release = readJson("https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases/latest")
                check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
                val assets = release.getJSONArray("assets")
                fun asset(name: String): JSONObject = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .single { it.getString("name") == name }
                val manifestUrl = asset("update.json").getString("browser_download_url")
                check(UpdatePolicy.releaseAsset(manifestUrl))
                val manifest = readJson(manifestUrl)
                val url = asset("TrueQibla.apk").getString("browser_download_url")
                val next = Offer(manifest.getLong("versionCode"), manifest.getString("versionName").take(40),
                    manifest.getString("sha256").lowercase(), manifest.getLong("size"), url)
                check(UpdatePolicy.releaseAsset(url) && UpdatePolicy.validManifest(next.code, next.hash, next.size))
                check(asset("TrueQibla.apk").getLong("size") == next.size)
                val newer = next.code > code(installed())
                post {
                    offer = if (newer) next else null
                    busy = false; onChanged?.invoke()
                    if (resumed && newer) showOffer()
                    else if (resumed && manual) toast(t("You have the latest version.", "لديك أحدث إصدار."))
                }
            } catch (_: Exception) {
                post { busy = false; onChanged?.invoke(); if (manual && resumed) toast(t(
                    "Could not check for updates. Check your connection and try again later.",
                    "تعذّر التحقق من التحديثات. تحقق من الاتصال وحاول لاحقًا.")) }
            }
        }
    }

    private fun readJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "TrueQibla-Android")
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    check(output.size() + count <= 256 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun showOffer() {
        val next = offer ?: return
        dialog?.dismiss()
        dialog = com.aly.typography.ArabicDialogBuilder(activity).setTitle(t("Update available", "تحديث متاح"))
            .setMessage(t("True Qibla ${next.version} is ready. Download it now? Android will ask you to confirm installation.",
                "الإصدار ${next.version} متاح. هل تريد تنزيله الآن؟ سيطلب أندرويد تأكيد التثبيت."))
            .setNegativeButton(t("Later", "لاحقًا"), null)
            .setPositiveButton(t("Update", "تحديث")) { _, _ -> download(next) }.show()
    }

    private fun download(next: Offer) {
        try {
            // App-specific storage needs no storage permission. Only one update is kept.
            File(requireNotNull(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "TrueQibla-update.apk").delete()
            val request = DownloadManager.Request(Uri.parse(next.url)).setTitle("True Qibla ${next.version}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "TrueQibla-update.apk")
            val id = downloads.enqueue(request)
            prefs.edit().putLong("download", id).putLong("code", next.code).putString("version", next.version)
                .putString("hash", next.hash).putLong("size", next.size).apply()
            onChanged?.invoke(); poll.run()
        } catch (_: Exception) { toast(t("Could not start download. Check available storage and try again.", "تعذّر بدء التنزيل. تحقق من مساحة التخزين وحاول مجددًا.")) }
    }

    private val poll = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (!resumed || busy) return
            val id = prefs.getLong("download", -1)
            if (id == -1L) return
            try {
                downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) { failDownload(); return }
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> { verify(id); return }
                        DownloadManager.STATUS_FAILED -> { failDownload(); return }
                        else -> Unit
                    }
                }
                handler.postDelayed(this, 1500)
            } catch (_: Exception) { failDownload() }
        }
    }

    private fun clearDownload() {
        val id = prefs.getLong("download", -1)
        try { if (id != -1L) downloads.remove(id) } catch (_: Exception) {}
        prefs.edit().remove("download").remove("code").remove("hash").remove("size").apply()
    }

    private fun failDownload() {
        clearDownload()
        busy = false; ready = false; offer = null; onChanged?.invoke()
        if (resumed) toast(t("Update download failed. Tap Check for updates to retry.", "فشل تنزيل التحديث. اضغط للتحقق والمحاولة مجددًا."))
    }

    @Suppress("DEPRECATION")
    private fun verify(id: Long) {
        busy = true; onChanged?.invoke()
        val expectedCode = prefs.getLong("code", -1)
        val expectedSize = prefs.getLong("size", -1)
        val expectedHash = prefs.getString("hash", "") ?: ""
        worker.execute {
            val downloaded = File(apk.parentFile, "download-$id.apk")
            try {
                if (closed) return@execute
                check(UpdatePolicy.validManifest(expectedCode, expectedHash, expectedSize))
                apk.parentFile!!.mkdirs()
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0L
                android.os.ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(id)).use { input ->
                    downloaded.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            count += size
                            check(count <= expectedSize && count <= UpdatePolicy.MAX_APK_BYTES)
                            hash.update(buffer, 0, size); output.write(buffer, 0, size)
                        }
                    }
                }
                check(count == expectedSize)
                check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash)
                val current = installed()
                if (expectedCode <= code(current)) { downloaded.delete(); post { clearDownload(); busy = false; onChanged?.invoke() }; return@execute }
                val archive = activity.packageManager.getPackageArchiveInfo(downloaded.absolutePath, signatureFlags()) ?: error("Invalid APK")
                check(UpdatePolicy.validPackage(code(current), expectedCode, code(archive), activity.packageName,
                    archive.packageName, signers(current), signers(archive)))
                check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT)
                if (closed) { downloaded.delete(); return@execute }
                Files.move(downloaded.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                post {
                    busy = false; ready = true; onChanged?.invoke()
                    if (resumed) {
                        dialog?.dismiss()
                        dialog = com.aly.typography.ArabicDialogBuilder(activity).setTitle(t("Update downloaded", "تم تنزيل التحديث"))
                            .setMessage(t("Ready to install. Your settings will be kept.", "جاهز للتثبيت. سيتم الاحتفاظ بإعداداتك."))
                            .setNegativeButton(t("Later", "لاحقًا"), null)
                            .setPositiveButton(t("Install", "تثبيت")) { _, _ -> install() }.show()
                    }
                }
            } catch (_: Exception) {
                downloaded.delete()
                post {
                    failDownload()
                    if (resumed) toast(t("Update could not be verified. Use a release signed with the same key as this installation.",
                        "تعذّر التحقق من التحديث. يجب أن يحمل الإصدار توقيع التثبيت الحالي نفسه."))
                }
            }
        }
    }

    private fun install() {
        if (!ready) return
        if (!apk.isFile) { failDownload(); return }
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                permissionPending = true
                toast(t("Allow updates from True Qibla, then return to the app.", "اسمح بالتحديثات من التطبيق ثم ارجع إليه."))
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                return
            }
            val uri = Uri.parse("content://${activity.packageName}.updates/update.apk")
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { toast(t("Could not open Android's installer. Try again from Settings → Check for updates.", "تعذّر فتح مثبّت أندرويد. حاول مجددًا من إعدادات التطبيق.")) }
    }

    private fun post(block: () -> Unit) { handler.post { if (!closed) block() } }
}

