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
    fun subLangLabel(v: String) = if (v == "off") t("opt_off") else t("lang_opt_$v")
    fun audioLangLabel(v: String) = if (v == "auto") t("opt_auto") else t("lang_opt_$v")
    fun nextOption(cur: String, options: List<String>): String {
        val i = options.indexOf(cur)
        return if (i < 0) options[0] else options[(i + 1) % options.size]
    }

    val st = app.settings
    val lines = listOf(
        t("set_language") + ":   " + t("lang_name"),
        t("set_resume") + ":   " + yesNo(st.resume),
        t("set_autopick") + ":   " + yesNo(st.autoPick),
        t("set_sublang") + ":   " + subLangLabel(st.subLang),
        t("set_audiolang") + ":   " + audioLangLabel(st.audioLang),
        t("set_autonext") + ":   " + yesNo(st.autoNext),
        t("set_intro") + ":   " + yesNo(st.intro),
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
            3 -> app.updateSettings(st.copy(subLang = nextOption(st.subLang, listOf("off", "pt", "en", "es"))))
            4 -> app.updateSettings(st.copy(audioLang = nextOption(st.audioLang, listOf("auto", "pt", "en", "es"))))
            5 -> app.updateSettings(st.copy(autoNext = !st.autoNext))
            6 -> app.updateSettings(st.copy(intro = !st.intro))
            7 -> app.confirm(t("settings_title"), t("confirm_clear_history")) {
                app.store.clearHistory()
                app.bumpHistory()
                app.showMessage(t("settings_title"), t("history_cleared"))
            }
            8 -> app.confirm(t("settings_title"), t("confirm_reset_addons")) {
                app.store.saveAddonConfig(Store.defaultAddonConfig())
                app.loadAddons()
                app.showMessage(t("settings_title"), t("addons_reset"))
            }
            9 -> app.showMessage(t("about_title"), t("about_body") + "  [" + BuildConfig.VERSION_NAME + "]")
        }
    }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        KLabel(t("settings_title"), 100, 50, 1700, 70, 42, weight = W.Bold, color = K.White)
        KLabel(t("settings_hint"), 100, 140, 1700, 40, 22, color = K.Hint)

        LazyColumn(
            modifier = Modifier.at(100, 220).box(1700, 700),
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
