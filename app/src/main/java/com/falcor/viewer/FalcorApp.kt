package com.falcor.viewer

import android.app.Application
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.repo.FrigateRepository

class FalcorApp : Application() {
    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var repository: FrigateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(this)
        repository = FrigateRepository(credentialStore)
    }
}
