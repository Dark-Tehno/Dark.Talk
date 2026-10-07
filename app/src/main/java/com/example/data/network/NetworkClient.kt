package com.example.data.network

import com.example.data.local.PreferencesManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClient(private val preferencesManager: PreferencesManager) {

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val authAndSecretInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // Dark-Talk-Secret-Key: <application-secret>--<application>|<version>
        builder.header("Dark-Talk-Secret-Key", preferencesManager.secretKeyHeader)

        // Authorization: Token <device-token>
        preferencesManager.authToken?.let { token ->
            if (token.isNotBlank()) {
                builder.header("Authorization", "Token $token")
            }
        }

        // Replace scheme, host, and port from preferencesManager.httpBaseUrl
        val configuredBaseUrl = preferencesManager.httpBaseUrl.toHttpUrlOrNull()
        if (configuredBaseUrl != null) {
            val newHttpUrl = originalRequest.url.newBuilder()
                .scheme(configuredBaseUrl.scheme)
                .host(configuredBaseUrl.host)
                .port(configuredBaseUrl.port)
                .build()
            builder.url(newHttpUrl)
        }

        chain.proceed(builder.build())
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authAndSecretInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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
