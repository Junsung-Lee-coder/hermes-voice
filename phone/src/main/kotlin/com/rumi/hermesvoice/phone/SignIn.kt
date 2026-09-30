package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rumi.hermesvoice.core.auth.HermesNativeSignIn
import com.rumi.hermesvoice.core.auth.LoopbackCallbackResult
import com.rumi.hermesvoice.core.auth.LoopbackCallbackServer
import com.rumi.hermesvoice.core.auth.PkcePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * RFC 8252 native sign-in: the system browser opens the dashboard's `/auth/native/authorize`
 * (the dashboard's own password form or IdP collects credentials; the app never sees them), the
 * dashboard redirects to this app's 127.0.0.1 loopback listener, and the one-time code is
 * redeemed with the PKCE verifier. Returns a short status for the settings screen.
 */
suspend fun runNativeSignIn(context: Context, timeoutMs: Long = 5 * 60_000L): String {
    val wiring = PhoneApp.from(context).wiring()
    val pkce = PkcePair.generate()
    val state = HermesNativeSignIn.newState()
    val result = withContext(Dispatchers.IO) {
        LoopbackCallbackServer().use { server ->
            // accept() is not interruptible: closing the socket is what unblocks a cancelled sign-in.
            coroutineContext[Job]?.invokeOnCompletion { server.close() }
            val url = HermesNativeSignIn.authorizeUrl(wiring.endpoint, pkce, server.redirectUri, state)
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            runInterruptible { server.awaitCallback(state, timeoutMs) }
        }
    }
    return when (result) {
        is LoopbackCallbackResult.Failed -> "Sign-in failed: ${result.reason}"
        is LoopbackCallbackResult.Code -> {
            val session = wiring.dashboard.exchangeNativeCode(result.code, pkce.verifier)
            "Signed in as ${session.userId.ifBlank { "dashboard user" }}"
        }
    }
}
