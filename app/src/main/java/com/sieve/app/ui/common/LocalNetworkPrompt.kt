package com.sieve.app.ui.common

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.sieve.app.di.AppGraph
import com.sieve.app.net.LocalNetworkAccess
import com.sieve.app.net.LocalNetworkGate
import kotlinx.coroutines.launch

/**
 * Runs a Download-screen action (read the link, queue it) only once Android 17 will let it reach the link.
 *
 * On a device below Android 17, for a link on the internet and once the permission is granted, [runWith] just runs the action.
 * When the link (or the proxy a new download would use) is on the local network and `ACCESS_LOCAL_NETWORK` is not granted, it asks
 * for the permission (the system's "Nearby devices" prompt) and runs the action after a grant. After a refusal nothing starts and
 * the user is told why, with a button to Sieve's settings page (the system stops prompting after repeated refusals).
 */
class LocalNetworkPrompt internal constructor(private val start: (url: String?, action: () -> Unit) -> Unit) {
    fun runWith(url: String?, action: () -> Unit) = start(url, action)
}

@Composable
fun rememberLocalNetworkPrompt(gate: LocalNetworkGate = AppGraph.localNetwork): LocalNetworkPrompt {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The action that waits for the answer of the permission dialog; one at a time (the dialog is modal). It does not survive a
    // recreation of the Activity, the dialog's answer does: onLocalNetworkAnswer covers that case.
    val waiting = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = waiting[0]
        waiting[0] = null
        onLocalNetworkAnswer(granted, action, post = SnackbarMessages::post, denied = { localNetworkDenied(context) })
    }
    return remember(gate, scope, launcher) {
        LocalNetworkPrompt { url, action ->
            scope.launch {
                if (gate.needsPermission(url)) {
                    waiting[0] = action
                    launcher.launch(LocalNetworkAccess.PERMISSION)
                } else {
                    action()
                }
            }
        }
    }
}

/**
 * What the answer of the system's permission dialog does. [pending] is the action that was waiting for it; it is null when the
 * Activity was recreated while the dialog was up (rotation, a theme or language change): the registry that delivers the answer
 * outlives the Activity, the lambda that waited for it does not. So:
 *  * refused: the user is always told that nothing started, with the way to Settings, whether or not an action was waiting;
 *  * granted with the action still waiting: it runs;
 *  * granted but the action is gone: a short message says to tap again (the tap that asked cannot be replayed).
 */
internal fun onLocalNetworkAnswer(
    granted: Boolean,
    pending: (() -> Unit)?,
    post: (SnackbarMessage) -> Unit,
    denied: () -> SnackbarMessage,
) {
    when {
        !granted -> post(denied())
        pending != null -> pending()
        else -> post(SnackbarMessage(LOCAL_NETWORK_ALLOWED_TEXT))
    }
}

internal const val LOCAL_NETWORK_ALLOWED_TEXT = "Nearby devices allowed. Tap again to start."

/** The message after a refusal: nothing was started, and the one place the permission can still be switched on. */
internal fun localNetworkDenied(context: Context): SnackbarMessage = SnackbarMessage(
    text = "Not started: Sieve needs the Nearby devices permission to reach a link on your local network.",
    actionLabel = "Settings",
    onAction = { runCatching { context.startActivity(LocalNetworkAccess.appSettingsIntent(context)) } },
)
