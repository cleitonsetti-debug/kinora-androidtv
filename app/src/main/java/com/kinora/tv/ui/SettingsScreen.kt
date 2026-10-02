package com.kinora.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kinora.tv.BuildConfig
import com.kinora.tv.data.I18n
import com.kinora.tv.data.Store

// =============================================================================
// SettingsView: idioma da interface, retomar de onde parou, escolha automatica
// de fonte, limpar historico e restaurar addons padrao
// =============================================================================

@Composable
fun SettingsScreen(app: AppState, screen: Screen.SettingsScreen) {
    val t = app.t
    var focused by remember { mutableIntStateOf(screen.focused) }
    val req = remember { FocusRequester() }

    LaunchedEffect(app.focusTick) { req.focusSoon() }

    fun yesNo(v: Boolean) = if (v) t("yes") else t("no")

    val st = app.settings
    val lines = listOf(
        t("set_language") + ":   " + t("lang_name"),
        t("set_resume") + ":   " + yesNo(st.resume),
        t("set_autopick") + ":   " + yesNo(st.autoPick),
        t("set_clear_history"),
        t("set_reset_addons"),
        t("set_about"),
    )

    fun onSelected(idx: Int) {
        when (idx) {
            0 -> {
                val codes = I18n.codes
                val cur = codes.indexOf(st.lang).coerceAtLeast(0)
                app.updateSettings(st.copy(lang = codes[(cur + 1) % codes.size]))
            }
            1 -> app.updateSettings(st.copy(resume = !st.resume))
            2 -> app.updateSettings(st.copy(autoPick = !st.autoPick))
            3 -> app.confirm(t("settings_title"), t("confirm_clear_history")) {
                app.store.clearHistory()
                app.bumpHistory()
                app.showMessage(t("settings_title"), t("history_cleared"))
            }
            4 -> app.confirm(t("settings_title"), t("confirm_reset_addons")) {
                app.store.saveAddonConfig(Store.defaultAddonConfig())
                app.loadAddons()
                app.showMessage(t("settings_title"), t("addons_reset"))
            }
            5 -> app.showMessage(t("about_title"), t("about_body") + "  [" + BuildConfig.VERSION_NAME + "]")
        }
    }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        KLabel(t("settings_title"), 100, 50, 1700, 70, 42, weight = W.Bold, color = K.White)
        KLabel(t("settings_hint"), 100, 140, 1700, 40, 22, color = K.Hint)

        LazyColumn(
            modifier = Modifier.at(100, 220).box(1700, 560),
            contentPadding = PaddingValues(vertical = d(4)),
        ) {
            itemsIndexed(lines) { idx, line ->
                ListPill(
                    text = line,
                    w = 1700,
                    h = 64,
                    textOffset = 28,
                    size = 24,
                    modifier = (if (idx == focused) Modifier.focusRequester(req) else Modifier)
                        .onPreviewKeyEvent { e ->
                            e.isDown() && ((e.key == Key.DirectionUp && idx == 0) ||
                                (e.key == Key.DirectionDown && idx == lines.size - 1) ||
                                e.key == Key.DirectionLeft || e.key == Key.DirectionRight)
                        },
                    onFocus = {
                        focused = idx
                        screen.focused = idx
                    },
                    onClick = { onSelected(idx) },
                )
            }
        }
    }
}
