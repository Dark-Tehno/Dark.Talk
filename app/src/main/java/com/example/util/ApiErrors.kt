package com.example.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Превращает тело ошибки сервера ({"status":"error","message":"CODE"}, {"detail":"..."},
 * либо ошибки полей DRF) в читаемую строку. Возвращает null, если разобрать нечего –
 * тогда вызывающий код использует свой fallback.
 */
fun parseServerError(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (!text.startsWith("{")) return null
    return runCatching {
        val obj = JSONObject(text)
        val code = obj.optString("message").ifBlank { obj.optString("detail") }
        if (code.isNotBlank()) {
            humanizeErrorCode(code)
        } else {
            val parts = mutableListOf<String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "status") continue
                val value = obj.opt(key)
                val msg = if (value is JSONArray) {
                    (0 until value.length()).joinToString(", ") { value.optString(it) }
                } else value?.toString().orEmpty()
                if (msg.isNotBlank()) parts += "$key: $msg"
            }
            parts.joinToString("\n").ifBlank { null }
        }
    }.getOrNull()
}

private fun humanizeErrorCode(code: String): String = when (code) {
    "INVALID_CREDENTIALS" -> "Invalid username or password"
    "USERNAME_PASSWORD_NOT_PROVIDED" -> "Enter username and password"
    "TWO_FACTOR_EMAIL_NOT_CONFIGURED" -> "Two-factor authentication needs an email on the account"
    "INVALID_OR_EXPIRED_TWO_FACTOR_CODE",
    "INVALID_OR_EXPIRED_TWO_FACTOR_CHALLENGE" -> "The code is invalid or expired"
    "LANGUAGE_NOT_SUPPORTED" -> "This language is not supported"
    "USERNAME_NOT_PROVIDED" -> "Enter a username"
    "INVALID_PAGINATION" -> "Invalid pagination parameters"
    else -> if (Regex("^[A-Z0-9_]+$").matches(code)) {
        code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    } else code
}
