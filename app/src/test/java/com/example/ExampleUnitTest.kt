package com.example

import com.example.util.MediaUrlUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testUrlParsing_localHostWithPort() {
    val raw = "10.0.2.2:8000"
    val candidate = "http://$raw/"
    val parsed = candidate.toHttpUrlOrNull()

    assertNotNull(parsed)
    assertEquals("http", parsed!!.scheme)
    assertEquals("10.0.2.2", parsed.host)
    assertEquals(8000, parsed.port)
    assertEquals("http://10.0.2.2:8000/", parsed.toString())
  }

  @Test
  fun testUrlParsing_domainWithoutPort() {
    val raw = "vsp210.ru"
    val candidate = "https://$raw/"
    val parsed = candidate.toHttpUrlOrNull()

    assertNotNull(parsed)
    assertEquals("https", parsed!!.scheme)
    assertEquals("vsp210.ru", parsed.host)
    assertEquals(443, parsed.port)
    assertEquals("https://vsp210.ru/", parsed.toString())
  }

  @Test
  fun testMediaUrlUtils() {
    val resolved1 = MediaUrlUtils.resolveUrl("/media/avatars/user.jpg", "http://10.0.2.2:8000/")
    assertEquals("http://10.0.2.2:8000/media/avatars/user.jpg", resolved1)

    val resolved2 = MediaUrlUtils.resolveUrl("media/avatars/user.jpg", "vsp210.ru")
    assertEquals("https://vsp210.ru/media/avatars/user.jpg", resolved2)

    val resolved3 = MediaUrlUtils.resolveUrl("https://cdn.example.com/avatar.jpg", "vsp210.ru")
    assertEquals("https://cdn.example.com/avatar.jpg", resolved3)
  }

  @Test
  fun testNetworkInterceptorUrlRewrite() {
    val rawDomain = "10.0.2.2:8000"
    val cleanDomain = rawDomain.removePrefix("http://").removePrefix("https://").trim('/')
    val httpBaseUrl = "http://$cleanDomain/".toHttpUrlOrNull()
    assertNotNull(httpBaseUrl)

    val originalUrl = "https://vsp210.ru/api/auth/login/".toHttpUrlOrNull()
    assertNotNull(originalUrl)

    val newUrl = originalUrl!!.newBuilder()
      .scheme(httpBaseUrl!!.scheme)
      .host(httpBaseUrl.host)
      .port(httpBaseUrl.port)
      .build()

    assertEquals("http", newUrl.scheme)
    assertEquals("10.0.2.2", newUrl.host)
    assertEquals(8000, newUrl.port)
    assertEquals("http://10.0.2.2:8000/api/auth/login/", newUrl.toString())
  }
}
