package com.aly.trueqibla

import java.net.URI
import java.security.MessageDigest

/** Small, deterministic rules shared by update discovery and installation. */
object UpdatePolicy {
    const val REPOSITORY = "AliSherif1309/TrueQibla-Android"
    const val MAX_APK_BYTES = 50L * 1024 * 1024
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun releaseAsset(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/download/") &&
            uri.rawPath.split('/').none { it == ".." || it == "." } &&
            !uri.rawPath.contains('%') && !uri.rawPath.contains('\\')
    } catch (_: Exception) { false }

    fun validManifest(code: Long, hash: String, size: Long) =
        code in 1..Int.MAX_VALUE.toLong() && hash.matches(Regex("[a-fA-F0-9]{64}")) && size in 1..MAX_APK_BYTES

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun validPackage(installedCode: Long, offeredCode: Long, archiveCode: Long,
                     installedPackage: String, archivePackage: String,
                     installedSigners: Set<String>, archiveSigners: Set<String>): Boolean =
        offeredCode > installedCode && archiveCode == offeredCode && installedPackage == archivePackage &&
            installedSigners.isNotEmpty() && installedSigners == archiveSigners
}
