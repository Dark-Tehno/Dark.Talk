package com.example

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.data.local.PreferencesManager
import com.example.data.local.db.AppDatabase
import com.example.data.network.DarkTalkWebSocketManager
import com.example.data.network.NetworkClient
import com.example.data.repository.DarkTalkRepository

class DarkTalkApplication : Application(), ImageLoaderFactory {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var networkClient: NetworkClient
        private set

    lateinit var webSocketManager: DarkTalkWebSocketManager
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var repository: DarkTalkRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferencesManager = PreferencesManager(this)
        networkClient = NetworkClient(preferencesManager)
        webSocketManager = DarkTalkWebSocketManager(
            preferencesManager = preferencesManager,
            moshi = networkClient.moshi,
            okHttpClient = networkClient.okHttpClient
        )
        database = AppDatabase.getInstance(this)
        repository = DarkTalkRepository(
            preferencesManager = preferencesManager,
            networkClient = networkClient,
            webSocketManager = webSocketManager,
            database = database
        )

        // Explicitly set Coil global ImageLoader so all image requests pass Authorization token & secret key
        val imageLoader = newImageLoader()
        Coil.setImageLoader(imageLoader)

        // If user is already logged in, connect to global chats stream
        if (preferencesManager.isLoggedIn) {
            webSocketManager.connectChatsStream()
        }
    }

    override fun newImageLoader(): ImageLoader {
        val maxSizeBytes = preferencesManager.maxCacheSizeBytes
        return ImageLoader.Builder(this)
            .okHttpClient(networkClient.okHttpClient)
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("media_cache"))
                    .maxSizeBytes(maxSizeBytes)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: DarkTalkApplication
            private set
    }
}
