package com.aly.trueqibla

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val release = "https://github.com/AliSherif1309/TrueQibla-Android/releases/download/latest/TrueQibla.apk"

    @Test fun onlyOurHttpsReleaseAssetsAreAllowed() {
        assertTrue(UpdatePolicy.releaseAsset(release))
        for (url in listOf(release.replace("https:", "http:"), release.replace("github.com", "github.com.evil.test"),
            release.replace("github.com", "github.com@evil.test"), release.replace("AliSherif1309", "other"),
            release + "?redirect=evil", release + "#fragment", release.replace("/latest/", "/../"),
            release.replace("/latest/", "/%2e%2e/"), "file:///update.apk", "not a URL"))
            assertFalse(url, UpdatePolicy.releaseAsset(url))
    }

    @Test fun rejectsMalformedOrUnboundedManifests() {
        val hash = "a".repeat(64)
        assertTrue(UpdatePolicy.validManifest(117, hash, 10))
        assertFalse(UpdatePolicy.validManifest(0, hash, 10))
        assertFalse(UpdatePolicy.validManifest(Int.MAX_VALUE.toLong() + 1, hash, 10))
        assertFalse(UpdatePolicy.validManifest(117, "z".repeat(64), 10))
        assertFalse(UpdatePolicy.validManifest(117, hash, 0))
        assertFalse(UpdatePolicy.validManifest(117, hash, UpdatePolicy.MAX_APK_BYTES + 1))
    }

    private fun valid(installed: Long = 116, offered: Long = 117, archive: Long = 117,
                      name: String = "com.aly.trueqibla", signers: Set<String> = setOf("release")) =
        UpdatePolicy.validPackage(installed, offered, archive, "com.aly.trueqibla", name, setOf("release"), signers)

    @Test fun onlyNewerMatchingPackagesAndCertificatesCanBeInstalled() {
        assertTrue(valid())
        assertFalse(valid(installed = 117))
        assertFalse(valid(installed = 118))
        assertFalse(valid(archive = 118))
        assertFalse(valid(name = "other.package"))
        assertFalse(valid(signers = setOf("debug")))
        assertFalse(valid(signers = emptySet()))
        assertFalse(valid(signers = setOf("release", "other")))
    }

    @Test fun sha256MatchesKnownReferenceAndDetectsAlteration() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdatePolicy.sha256("abc".toByteArray()))
        assertNotEquals(UpdatePolicy.sha256("abc".toByteArray()), UpdatePolicy.sha256("abd".toByteArray()))
    }
}
