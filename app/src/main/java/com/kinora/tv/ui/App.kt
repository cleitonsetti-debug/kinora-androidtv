package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign

@Composable
fun KinoraApp(app: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(K.Bg), contentAlignment = Alignment.Center) {
        // Escala do layout de 1920x1080 para a tela real, mantendo 16:9
        val scale = minOf(maxWidth.value / 1920f, maxHeight.value / 1080f)
        CompositionLocalProvider(LocalScale provides scale) {
            Box(Modifier.box(1920, 1080).clipToBounds().background(K.Bg)) {
                BackHandler(enabled = app.stack.size > 1) { app.pop() }

                val top = app.stack.lastOrNull()
                if (top == null) {
                    KLabel(
                        app.statusText.ifEmpty { app.t("loading") },
                        0, 500, 1920, 60, 32,
                        weight = W.Medium, color = K.White, align = TextAlign.Center,
                    )
                } else {
                    key(top) {
                        when (top) {
                            is Screen.Home -> HomeScreen(app, top.model)
                            is Screen.Search -> SearchScreen(app, top.model)
                            is Screen.Addons -> AddonsScreen(app, top)
                            is Screen.SettingsScreen -> SettingsScreen(app, top)
                            is Screen.Details -> DetailsScreen(app, top.model)
                            is Screen.Player -> PlayerScreen(app, top.req)
                        }
                    }
                }

                app.dialog?.let { spec -> key(spec) { DialogView(app, spec) } }
            }
        }
    }
}
