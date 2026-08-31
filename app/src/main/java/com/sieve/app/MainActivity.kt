package com.sieve.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.AppPrefs
import com.sieve.app.ui.nav.SieveNavHost
import com.sieve.app.ui.theme.Appearance
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.app.ui.theme.accentFromHex

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by AppGraph.appSettings.flow.collectAsStateWithLifecycle(initialValue = AppPrefs())
            val appearance = Appearance(mode = prefs.themeMode, accent = accentFromHex(prefs.accentHex))
            SieveTheme(appearance) {
                SieveNavHost()
            }
        }
    }
}
