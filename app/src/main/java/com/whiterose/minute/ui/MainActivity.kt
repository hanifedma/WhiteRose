package com.whiterose.minute.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.whiterose.minute.core.PulseService
import com.whiterose.minute.data.SettingsStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            WhiteRoseTheme {
                WhiteRoseScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the system reclaimed the service while we were away, bring it back.
        if (SettingsStore.get(this).current.enabled) PulseService.ensureRunning(this)
    }
}
