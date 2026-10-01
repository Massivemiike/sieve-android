package com.sieve.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sieve.app.di.AppGraph
import com.sieve.app.settings.AppPrefs
import com.sieve.app.ui.common.SharedUrlBus
import com.sieve.app.ui.nav.NavRequests
import com.sieve.app.ui.nav.SieveNavHost
import com.sieve.app.ui.theme.Appearance
import com.sieve.app.ui.theme.SieveTheme
import com.sieve.app.ui.theme.accentFromHex
import com.sieve.queue.service.QueueNotification

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedUrlBus.extract(intent)?.let { SharedUrlBus.publish(it) }
        if (savedInstanceState == null) handleNavRequest(intent) // a recreate (rotation) must not re-navigate
        setContent {
            val prefs by AppGraph.appSettings.flow.collectAsStateWithLifecycle(initialValue = AppPrefs())
            val appearance = Appearance(mode = prefs.themeMode, accent = accentFromHex(prefs.accentHex))
            SieveTheme(appearance) {
                SieveNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedUrlBus.extract(intent)?.let { SharedUrlBus.publish(it) }
        handleNavRequest(intent)
    }

    /** A tapped failure notification asks for the Queue; take the request once so it can't replay. */
    private fun handleNavRequest(intent: Intent?) {
        NavRequests.routeFor(intent)?.let { NavRequests.open(it) }
        intent?.removeExtra(QueueNotification.EXTRA_OPEN_QUEUE)
    }
}
