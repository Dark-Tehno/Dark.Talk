package com.example.util

import com.example.DarkTalkApplication
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object MediaUrlUtils {
    const val DEFAULT_DOMAIN = "vsp210.ru"
    const val DEFAULT_BASE_URL = "https://vsp210.ru/"

    /**
     * Resolves image and media URLs. If the URL is relative (e.g. /media/...),
     * it prepends the configured base URL from PreferencesManager.
     */
    fun resolveUrl(url: String?, baseUrlOrDomain: String? = null): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        val activeBaseUrl = baseUrlOrDomain?.takeIf { it.isNotBlank() }
            ?: runCatching { DarkTalkApplication.instance.preferencesManager.httpBaseUrl }.getOrNull()
            ?: DEFAULT_BASE_URL

        val baseHttpUrl = when {
            activeBaseUrl.startsWith("http://", ignoreCase = true) || activeBaseUrl.startsWith("https://", ignoreCase = true) -> {
                activeBaseUrl.toHttpUrlOrNull()
            }
            else -> {
                val cleanDomain = activeBaseUrl
                    .removePrefix("ws://").removePrefix("wss://")
                    .trim('/')
                    .trim()
                val scheme = if (cleanDomain.contains(":80") || cleanDomain.contains(":8000") || cleanDomain.contains(":8080") || cleanDomain.contains(":5000") || cleanDomain.contains(":3000")) "http" else "https"
                "$scheme://$cleanDomain/".toHttpUrlOrNull()
            }
        } ?: DEFAULT_BASE_URL.toHttpUrlOrNull() ?: return null

        val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return baseHttpUrl.newBuilder()
            .encodedPath("/")
            .build()
            .toString()
            .trimEnd('/') + cleanPath
    }

    /**
     * Safely formats ISO 8601 timestamps (e.g. 2026-09-28T17:06:54.817599Z) into readable time (17:06).
     */
    fun formatMessageTime(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return runCatching {
            val trimmed = isoString.trim()
            val timePart = if (trimmed.contains("T")) {
                trimmed.substringAfter("T").substringBefore(".").removeSuffix("Z")
            } else {
                trimmed
            }
            val parts = timePart.split(":")
            if (parts.size >= 2) {
                val hour = parts[0].padStart(2, '0')
                val min = parts[1].padStart(2, '0')
                "$hour:$min"
            } else {
                trimmed.take(10)
            }
        }.getOrElse {
            isoString.take(10)
        }
    }
}
