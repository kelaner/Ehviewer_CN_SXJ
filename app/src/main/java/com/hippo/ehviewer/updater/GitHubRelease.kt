package com.hippo.ehviewer.updater

import com.alibaba.fastjson.JSONException
import com.alibaba.fastjson.JSONObject
import java.math.BigInteger

/** The stable GitHub Release contract, independent of the old update.json versionCode. */
data class GitHubRelease(
    val version: String,
    val title: String,
    val notes: List<String>,
    val downloadUrl: String
) {
    companion object {
        private val versionPattern = Regex("[vV]?[0-9]+(?:\\.[0-9]+)*")
        private val abiPattern = Regex("(?:^|[-_.])(arm64-v8a|armeabi-v7a|x86_64|x86)(?=[-_.]|$)")

        fun compareVersions(first: String, second: String): Int {
            val left = normalizeVersion(first).split('.').map { BigInteger(it) }
            val right = normalizeVersion(second).split('.').map { BigInteger(it) }
            for (index in 0 until maxOf(left.size, right.size)) {
                val result = (left.getOrNull(index) ?: BigInteger.ZERO)
                    .compareTo(right.getOrNull(index) ?: BigInteger.ZERO)
                if (result != 0) return result
            }
            return 0
        }

        private fun normalizeVersion(version: String): String {
            val trimmed = version.trim()
            if (!versionPattern.matches(trimmed)) {
                throw JSONException("Invalid release version: $version")
            }
            return trimmed.removePrefix("v").removePrefix("V")
        }

        fun parse(data: JSONObject, supportedAbis: List<String>): GitHubRelease {
            if (data.getBooleanValue("draft") || data.getBooleanValue("prerelease")) {
                throw JSONException("Not a stable release")
            }
            val version = normalizeVersion(data.getString("tag_name").orEmpty())
            val assets = data.getJSONArray("assets") ?: throw JSONException("Missing release assets")
            val apks = (0 until assets.size).map { assets.getJSONObject(it) }.filter {
                it.getString("name").orEmpty().endsWith(".apk", ignoreCase = true) &&
                    !it.getString("browser_download_url").isNullOrBlank()
            }
            val apk = supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { asset ->
                    abiPattern.find(asset.getString("name").lowercase())?.groupValues?.get(1) == abi
                }
            } ?: apks.firstOrNull {
                !abiPattern.containsMatchIn(it.getString("name").lowercase())
            } ?: throw JSONException("No compatible release APK")
            return GitHubRelease(
                version,
                data.getString("name")?.takeIf { it.isNotBlank() } ?: version,
                data.getString("body").orEmpty().lines().filter { it.isNotBlank() },
                apk.getString("browser_download_url")
            )
        }
    }
}
