package com.kinora.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kinora.tv.data.Addon
import com.kinora.tv.data.Net
import com.kinora.tv.data.normalizeAddonUrl
import kotlinx.coroutines.launch

// =============================================================================
// AddonsView: lista, ativa/desativa, remove e adiciona addons por URL
// =============================================================================

private fun addonLine(app: AppState, a: Addon): String {
    val t = app.t
    val st = when {
        !a.ok -> t("state_err")
        !a.enabled -> t("state_off")
        else -> t("state_on")
    }
    return "[" + st + "]  " + a.name + "     " + a.url
}

@Composable
fun AddonsScreen(app: AppState, screen: Screen.Addons) {
    val t = app.t
    var hint by remember { mutableStateOf("") }
    var focused by remember { mutableIntStateOf(screen.focused) }
    val listState = rememberLazyListState()
    val req = remember { FocusRequester() }
    var focusCmd by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusCmd, app.focusTick) {
        val n = app.addons.size + 1
        if (focused >= n) focused = n - 1
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == focused }
        if (!visible) listState.scrollToItem(focused)
        req.focusSoon()
    }

    fun toggle(i: Int) {
        val list = app.addons.toMutableList()
        if (i < 0 || i >= list.size) return
        list[i] = list[i].copy(enabled = !list[i].enabled)
        app.commitAddons(list)
    }

    fun remove(i: Int) {
        val list = app.addons.toMutableList()
        if (i < 0 || i >= list.size) return
        list.removeAt(i)
        app.commitAddons(list)
        if (focused > list.size) focused = list.size
        focusCmd += 1
    }

    fun addFromUrl(raw: String) {
        val base = normalizeAddonUrl(raw)
        if (base.isEmpty()) {
            app.showMessage(t("addon_url_bad_t"), t("addon_url_bad_b"))
            return
        }
        if (app.addons.any { it.url == base }) {
            app.showMessage(t("addon_dup_t"), base)
            return
        }
        hint = t("addons_checking")
        app.scope.launch {
            val res = Net.getJson("$base/manifest.json")
            hint = ""
            val data = res.data
            val valid = res.ok && data != null && data.has("id") && data.optJSONArray("resources") != null
            if (!valid || data == null) {
                app.showMessage(t("addon_bad_t"), t.f("addon_bad_b", base))
                return@launch
            }
            val nm = data.optString("name", "").ifEmpty { base }
            app.commitAddons(app.addons + Addon(base, true, nm, data, true))
            app.showMessage(t("addon_added_t"), nm)
        }
    }

    fun showAddDialog() {
        app.dialog = DialogSpec(
            title = t("addon_dlg_title"),
            message = t("addon_dlg_msg"),
            buttons = listOf(t("btn_add"), t("btn_cancel")),
            input = "https://",
        ) { idx, text -> if (idx == 0) addFromUrl(text) }
    }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        KLabel(t("addons_title"), 100, 50, 1700, 70, 42, weight = W.Bold, color = K.White)
        KLabel(t("addons_hint"), 100, 140, 1700, 40, 22, color = K.Hint)

        val lines = listOf(t("addons_add")) + app.addons.map { addonLine(app, it) }
        LazyColumn(
            state = listState,
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
                            if (!e.isDown()) return@onPreviewKeyEvent false
                            when {
                                e.isOptions() && idx > 0 && e.repeatCount() == 0 -> {
                                    remove(idx - 1)
                                    true
                                }
                                e.key == Key.DirectionUp && idx == 0 -> true
                                e.key == Key.DirectionDown && idx == lines.size - 1 -> true
                                e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> true
                                else -> false
                            }
                        },
                    onFocus = {
                        focused = idx
                        screen.focused = idx
                    },
                    onLongClick = if (idx > 0) ({ remove(idx - 1) }) else null,
                    onClick = { if (idx == 0) showAddDialog() else toggle(idx - 1) },
                )
            }
        }

        KLabel(hint, 100, 940, 1700, 40, 24, weight = W.Medium, color = K.Meta)
    }
}
