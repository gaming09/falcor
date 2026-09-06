package com.falcor.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.falcor.viewer.ui.navigation.FalcorNavHost
import com.falcor.viewer.ui.theme.FalcorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FalcorApp
        setContent {
            FalcorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FalcorNavHost(
                        repository = app.repository,
                        credentialStore = app.credentialStore,
                        appPreferences = app.appPreferences
                    )
                }
            }
        }
    }
}
