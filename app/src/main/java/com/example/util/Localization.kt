package com.example.util

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

data class AppTranslations(
    val chatsTitle: String,
    val settingsTitle: String,
    val profileTab: String,
    val securityTab: String,
    val networkTab: String,
    val storageTab: String,
    val personalizationTab: String,
    val writeMessagePlaceholder: String,
    val searchChatsPlaceholder: String,
    val saveProfileButton: String,
    val signOutButton: String,
    val newDirectChat: String,
    val newGroupChat: String,
    val bioLabel: String,
    val languageLabel: String,
    val dobLabel: String,
    val clearCacheButton: String,
    val applyLimitButton: String
)

val RuTranslations = AppTranslations(
    chatsTitle = "Чаты",
    settingsTitle = "Настройки",
    profileTab = "Профиль",
    securityTab = "Безопасность",
    networkTab = "Сеть",
    storageTab = "Память",
    personalizationTab = "Оформление",
    writeMessagePlaceholder = "Написать сообщение...",
    searchChatsPlaceholder = "Поиск чатов или сообщений",
    saveProfileButton = "Сохранить профиль",
    signOutButton = "Выйти из аккаунта",
    newDirectChat = "Новый чат",
    newGroupChat = "Новая группа",
    bioLabel = "О себе / Био",
    languageLabel = "Предпочитаемый язык",
    dobLabel = "Дата рождения",
    clearCacheButton = "Очистить весь кэш",
    applyLimitButton = "Применить лимит"
)

val EnTranslations = AppTranslations(
    chatsTitle = "Chats",
    settingsTitle = "Settings",
    profileTab = "Profile",
    securityTab = "Security",
    networkTab = "Network",
    storageTab = "Storage",
    personalizationTab = "Personalization",
    writeMessagePlaceholder = "Write a message...",
    searchChatsPlaceholder = "Search chats or messages",
    saveProfileButton = "Save Profile",
    signOutButton = "Sign Out",
    newDirectChat = "New Direct Chat",
    newGroupChat = "New Group Chat",
    bioLabel = "Bio / About",
    languageLabel = "Preferred Language",
    dobLabel = "Date of Birth",
    clearCacheButton = "Clear All Cache",
    applyLimitButton = "Apply Limit"
)

val LocalAppStrings = staticCompositionLocalOf { RuTranslations }

fun getTranslations(languageName: String?): AppTranslations {
    return if (isEnglishLanguage(languageName)) EnTranslations else RuTranslations
}

fun isEnglishLanguage(languageName: String?): Boolean {
    val normalized = languageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val languageCode = normalized.substringBefore('-').substringBefore('_')
    return languageCode == "en" || "english" in normalized || "англий" in normalized
}
