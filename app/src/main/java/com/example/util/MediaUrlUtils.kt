package com.example.util

import com.example.DarkTalkApplication
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MediaUrlUtils {
    const val DEFAULT_DOMAIN = "vsp210.ru"
    const val DEFAULT_BASE_URL = "https://vsp210.ru/"

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

    /** ISO 8601 (с долями секунды и Z / ±hh:mm) -> epoch millis. Без заголовка в UTC. */
    fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val s = iso.trim()
            val base = s.substring(0, 19).replace(' ', 'T')
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            var millis = fmt.parse(base)!!.time
            val tz = Regex("([+-])(\\d{2}):?(\\d{2})$").find(s.substring(19))
            if (tz != null) {
                val sign = if (tz.groupValues[1] == "-") -1 else 1
                millis -= sign * (tz.groupValues[2].toLong() * 3_600_000L + tz.groupValues[3].toLong() * 60_000L)
            }
            millis
        }.getOrNull()
    }

    private fun fmt(ms: Long, pattern: String, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(pattern, locale).format(Date(ms))

    private fun dayKeyOf(ms: Long) = fmt(ms, "yyyy-MM-dd", Locale.US)

    /** Время сообщения в ЛОКАЛЬНОМ часовом поясе (раньше показывался UTC). */
    fun formatMessageTime(isoString: String?): String {
        val ms = parseIsoMillis(isoString) ?: return isoString?.take(10).orEmpty()
        return fmt(ms, "HH:mm")
    }

    fun localDayKey(iso: String?): String? = parseIsoMillis(iso)?.let { dayKeyOf(it) }

    fun formatDayLabel(iso: String?): String {
        val ms = parseIsoMillis(iso) ?: return ""
        val cal = Calendar.getInstance()
        val today = dayKeyOf(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dayKeyOf(cal.timeInMillis)
        val key = dayKeyOf(ms)
        return when (key) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> fmt(ms, if (key.take(4) == today.take(4)) "d MMMM" else "d MMMM yyyy")
        }
    }

    fun formatChatListTime(iso: String?): String {
        val ms = parseIsoMillis(iso) ?: return ""
        val now = System.currentTimeMillis()
        return when {
            dayKeyOf(ms) == dayKeyOf(now) -> fmt(ms, "HH:mm")
            now - ms < 6 * 86_400_000L -> fmt(ms, "EEE")
            else -> fmt(ms, "dd.MM.yy")
        }
    }

    fun formatLastSeen(iso: String?): String {
        val ms = parseIsoMillis(iso) ?: return "last seen recently"
        val label = formatDayLabel(iso)
        return when (label) {
            "Today" -> "last seen at ${fmt(ms, "HH:mm")}"
            "Yesterday" -> "last seen yesterday at ${fmt(ms, "HH:mm")}"
            else -> "last seen $label"
        }
    }
}
