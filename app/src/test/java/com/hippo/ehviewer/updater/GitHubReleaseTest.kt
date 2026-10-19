package com.hippo.ehviewer.updater

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONException
import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {
    @Test
    fun comparesNumericComponentsAndTagPrefixWithoutDowngrading() {
        assertTrue(GitHubRelease.compareVersions("2.0.2.5.1", "v2.0.2.5.2") < 0)
        assertEquals(0, GitHubRelease.compareVersions("v2.0.2.5.2", "2.0.2.5.2.0"))
        assertTrue(GitHubRelease.compareVersions("2.0.2.5.3", "v2.0.2.5.2") > 0)
        assertTrue(GitHubRelease.compareVersions("2.0.2.5.10", "2.0.2.5.9") > 0)
    }

    @Test
    fun parsesPublishedReleaseTagAndSelectsApkRatherThanChecksum() {
        // Fields from the repository's v2.0.2.5.2 GitHub Release API response.
        val data = JSON.parseObject("""
            {
              "tag_name": "v2.0.2.5.2",
              "name": "2.0.2.5.2",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "SHA256.txt", "browser_download_url": "https://github.com/kelaner/Ehviewer_CN_SXJ/releases/download/v2.0.2.5.2/SHA256.txt"},
                {"name": "Ehviewer-2.0.2.5.2.apk", "browser_download_url": "https://github.com/kelaner/Ehviewer_CN_SXJ/releases/download/v2.0.2.5.2/Ehviewer-2.0.2.5.2.apk"}
              ]
            }
        """.trimIndent())
        val release = GitHubRelease.parse(data, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("2.0.2.5.2", release.version)
        assertEquals("2.0.2.5.2", release.title)
        assertEquals("https://github.com/kelaner/Ehviewer_CN_SXJ/releases/download/v2.0.2.5.2/Ehviewer-2.0.2.5.2.apk", release.downloadUrl)
    }

    @Test
    fun selectsCompatibleAbiInDeviceOrderAndFallsBackToUniversal() {
        val data = releaseWithAssets("Ehviewer-x86.apk", "Ehviewer-x86_64.apk", "Ehviewer.apk")
        assertEquals("https://example.test/Ehviewer-x86_64.apk",
            GitHubRelease.parse(data, listOf("x86_64", "x86")).downloadUrl)
        assertEquals("https://example.test/Ehviewer.apk",
            GitHubRelease.parse(data, listOf("arm64-v8a")).downloadUrl)
    }

    @Test(expected = JSONException::class)
    fun rejectsIncompatibleApkInsteadOfDownloadingAnotherAbi() {
        GitHubRelease.parse(releaseWithAssets("Ehviewer-x86_64.apk"), listOf("x86"))
    }

    @Test(expected = JSONException::class)
    fun rejectsMissingApkInsteadOfReportingUpToDate() {
        GitHubRelease.parse(releaseWithAssets("SHA256.txt"), listOf("arm64-v8a"))
    }

    @Test(expected = JSONException::class)
    fun rejectsInvalidVersionInsteadOfThrowingNumberFormatException() {
        GitHubRelease.compareVersions("2.0.2.5.2", "v2.0.2.5.3-beta")
    }

    @Test(expected = JSONException::class)
    fun rejectsPrerelease() {
        val data = releaseWithAssets("Ehviewer.apk")
        data["prerelease"] = true
        GitHubRelease.parse(data, listOf("arm64-v8a"))
    }

    private fun releaseWithAssets(vararg names: String): JSONObject = JSONObject().apply {
        put("tag_name", "v2.0.2.5.2")
        put("assets", names.map { name ->
            JSONObject().apply {
                put("name", name)
                put("browser_download_url", "https://example.test/$name")
            }
        })
    }
}
