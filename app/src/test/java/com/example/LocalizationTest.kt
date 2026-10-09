package com.example

import com.example.util.EnTranslations
import com.example.util.RuTranslations
import com.example.util.getTranslations
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {
    @Test
    fun `language names and locale tags select matching translations`() {
        assertEquals(EnTranslations, getTranslations("English"))
        assertEquals(EnTranslations, getTranslations("en"))
        assertEquals(EnTranslations, getTranslations("en-US"))
        assertEquals(EnTranslations, getTranslations("Английский"))
        assertEquals(RuTranslations, getTranslations("Russian"))
        assertEquals(RuTranslations, getTranslations("Русский"))
        assertEquals(RuTranslations, getTranslations("ru-RU"))
        assertEquals(RuTranslations, getTranslations(null))
    }
}
