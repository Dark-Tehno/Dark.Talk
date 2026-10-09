package com.example.data.network

import com.example.BuildConfig
import com.example.data.local.PreferencesManager
import com.example.util.MediaUrlUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClient(private val preferencesManager: PreferencesManager) {

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val authAndSecretInterceptor = Interceptor { chain ->
        val original = chain.request()
        val configured = preferencesManager.httpBaseUrl.toHttpUrlOrNull()

        // Токен и секрет отправляем ТОЛЬКО на наш сервер (раньше уходили на любой хост,
        // например на внешний CDN с аватаркой).
        val isOurServer = configured != null &&
                (original.url.host == configured.host || original.url.host == MediaUrlUtils.DEFAULT_DOMAIN)

        val request = if (isOurServer && configured != null) {
            val builder = original.newBuilder()
            builder.header("Dark-Talk-Secret-Key", preferencesManager.secretKeyHeader)
            preferencesManager.authToken?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Token $it")
            }
            builder.url(
                original.url.newBuilder()
                    .scheme(configured.scheme)
                    .host(configured.host)
                    .port(configured.port)
                    .build()
            )
            builder.build()
        } else original

        val response = chain.proceed(request)

        // Токен отозван / устройство удалено -> выходим из аккаунта (MainActivity перекинет на экран входа).
        if (response.code == 401 && isOurServer && preferencesManager.isLoggedIn &&
            !request.url.encodedPath.contains("/auth/")
        ) {
            preferencesManager.clearAuth()
        }
        response
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authAndSecretInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
                redactHeader("Dark-Talk-Secret-Key")
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS) // держит WebSocket живым за NAT/прокси
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createApi(): DarkTalkApi {
        return Retrofit.Builder()
            .baseUrl(preferencesManager.httpBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DarkTalkApi::class.java)
    }
}
