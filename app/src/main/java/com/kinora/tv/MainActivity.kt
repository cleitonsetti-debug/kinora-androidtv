package com.kinora.tv

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
        val state = AppState(Store(applicationContext), lifecycleScope)
        app = state
        state.loadAddons()
        setContent { KinoraApp(state) }
    }
}
