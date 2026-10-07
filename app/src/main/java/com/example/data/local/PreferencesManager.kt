package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("darktalk_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_DOMAIN = "server_domain"
        private const val KEY_USE_SSL = "use_ssl"
        private const val KEY_APP_SECRET = "app_secret"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_AVATAR = "user_avatar"
        private const val KEY_2FA_ENABLED = "2fa_enabled"
        private const val KEY_MAX_CACHE_MB = "max_cache_mb"
        private const val KEY_CUSTOM_CACHE_GB = "custom_cache_gb"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_REACTION_EMOJIS = "reaction_emojis"
    }

    var serverDomain: String
        get() {
            val saved = prefs.getString(KEY_SERVER_DOMAIN, null)
            return if (!saved.isNullOrBlank()) {
                saved
            } else {
                runCatching { BuildConfig.SERVER_DOMAIN }.getOrNull()?.takeIf { it.isNotBlank() } ?: "vsp210.ru"
            }
        }
        set(value) {
            val trimmed = value.trim()
            prefs.edit().putString(KEY_SERVER_DOMAIN, trimmed).apply()
            // Auto-detect SSL preference if user explicitly entered a scheme
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true)) {
                useSsl = false
            } else if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)) {
                useSsl = true
            }
        }

    var useSsl: Boolean
        get() = prefs.getBoolean(KEY_USE_SSL, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_SSL, value).apply()

    var appSecretKey: String
        get() {
            val saved = prefs.getString(KEY_APP_SECRET, null)
            return if (!saved.isNullOrBlank()) {
                saved
            } else {
                runCatching { BuildConfig.DARK_TALK_SECRET_KEY }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "darktalk_secret_key_prod"
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_SECRET, value.trim()).apply()

    var clientName: String
        get() = runCatching { BuildConfig.APP_CLIENT_NAME }.getOrNull()?.takeIf { it.isNotBlank() } ?: "DarkTalk"
        set(_) {}

    var clientVersion: String
        get() = runCatching { BuildConfig.APP_CLIENT_VERSION }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0.0"
        set(_) {}

    val secretKeyHeader: String
        get() = "${appSecretKey}--${clientName}|${clientVersion}"

    val wsSecretKeyHeader: String
        get() = "${appSecretKey}--${clientVersion}"

    val httpBaseUrl: String
        get() {
            val raw = serverDomain.trim()
            val lower = raw.lowercase()

            val scheme = when {
                lower.startsWith("http://") || lower.startsWith("ws://") -> "http"
                lower.startsWith("https://") || lower.startsWith("wss://") -> "https"
                useSsl -> "https"
                else -> "http"
            }

            val cleanDomain = raw
                .removePrefix("http://").removePrefix("HTTP://")
                .removePrefix("https://").removePrefix("HTTPS://")
                .removePrefix("ws://").removePrefix("WS://")
                .removePrefix("wss://").removePrefix("WSS://")
                .trim('/')
                .trim()

            val hostPort = if (cleanDomain.isNotBlank()) cleanDomain else "vsp210.ru"
            val candidate = "$scheme://$hostPort/"

            val parsed = candidate.toHttpUrlOrNull()
            return if (parsed != null) {
                parsed.toString()
            } else {
                "https://vsp210.ru/"
            }
        }

    val wsBaseUrl: String
        get() {
            val httpUrl = httpBaseUrl.toHttpUrlOrNull() ?: "https://vsp210.ru/".toHttpUrl()
            val wsScheme = if (httpUrl.scheme == "https") "wss" else "ws"
            val portSuffix = if ((httpUrl.scheme == "http" && httpUrl.port == 80) ||
                                 (httpUrl.scheme == "https" && httpUrl.port == 443)) {
                ""
            } else {
                ":${httpUrl.port}"
            }
            return "$wsScheme://${httpUrl.host}$portSuffix"
        }

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    val isLoggedIn: Boolean
        get() = !authToken.isNullOrBlank()

    var deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val fromEnv = runCatching { BuildConfig.DEFAULT_DEVICE_ID }.getOrNull()?.takeIf { it.isNotBlank() }
            val newId = fromEnv ?: "android-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userAvatar: String?
        get() = prefs.getString(KEY_USER_AVATAR, null)
        set(value) = prefs.edit().putString(KEY_USER_AVATAR, value).apply()

    var twoFactorEnabled: Boolean
        get() = prefs.getBoolean(KEY_2FA_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_2FA_ENABLED, value).apply()

    var maxCacheMb: Long
        get() = prefs.getLong(KEY_MAX_CACHE_MB, 4096L)
        set(value) = prefs.edit().putLong(KEY_MAX_CACHE_MB, value).apply()

    var customCacheGb: String
        get() = prefs.getString(KEY_CUSTOM_CACHE_GB, "4") ?: "4"
        set(value) = prefs.edit().putString(KEY_CUSTOM_CACHE_GB, value.trim()).apply()

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, "cyber") ?: "cyber"
        set(value) = prefs.edit().putString(KEY_APP_THEME, value.trim()).apply()

    var customReactionEmojis: String
        get() = prefs.getString(KEY_REACTION_EMOJIS, "❤️,🔥,👍,😂,😮,👏") ?: "❤️,🔥,👍,😂,😮,👏"
        set(value) = prefs.edit().putString(KEY_REACTION_EMOJIS, value.trim()).apply()

    val maxCacheSizeBytes: Long
        get() {
            val mb = maxCacheMb
            return if (mb <= 0L || mb >= 50000L) {
                Long.MAX_VALUE
            } else {
                mb * 1024L * 1024L
            }
        }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_AVATAR)
            .remove(KEY_2FA_ENABLED)
            .apply()
    }
}
