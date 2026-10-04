package com.kinora.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.kinora.tv.data.Store
import com.kinora.tv.ui.AppState
import com.kinora.tv.ui.KinoraApp

class MainActivity : ComponentActivity() {
    private var app: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = AppState(applicationContext, Store(applicationContext), lifecycleScope)
        app = state
        state.loadAddons()
        handleIntent(intent)
        setContent { KinoraApp(state) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Link para adicionar addon (equivale ao "addon=<url>" do Roku via ECP):
     *   stremio://endereco/manifest.json
     *   kinora://add?addon=https://endereco/manifest.json
     * Pelo computador: adb shell am start -a android.intent.action.VIEW -d "kinora://add?addon=URL"
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val url = when (data.scheme?.lowercase()) {
            "stremio" -> data.toString()
            "kinora" -> data.getQueryParameter("addon") ?: ""
            else -> ""
        }
        if (url.isNotEmpty()) app?.onIncomingAddon(url)
    }
}
