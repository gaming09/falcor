package com.falcor.viewer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.repo.FrigateRepository

class FalcorApp : Application(), ImageLoaderFactory {
    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var repository: FrigateRepository
        private set
    lateinit var appPreferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(this)
        repository = FrigateRepository(credentialStore)
        appPreferences = AppPreferences(this)
    }

    /** Coil uses the same trusted OkHttp client + JWT as Retrofit thumbnails/API. */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { repository.authenticatedHttpClient() }
            .crossfade(true)
            .build()
    }
}
