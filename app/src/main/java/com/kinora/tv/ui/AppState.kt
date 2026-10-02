package com.kinora.tv.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinora.tv.data.Addon
import com.kinora.tv.data.AddonConfig
import com.kinora.tv.data.Info
import com.kinora.tv.data.Net
import com.kinora.tv.data.PlayRequest
import com.kinora.tv.data.Settings
import com.kinora.tv.data.Store
import com.kinora.tv.data.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Telas da pilha (equivale ao m.stack da MainScene do Roku). */
sealed class Screen {
    class Home(val model: HomeModel) : Screen()
    class Search(val model: SearchModel) : Screen()
    class Addons : Screen() { var focused = 0 }
    class SettingsScreen : Screen() { var focused = 0 }
    class Details(val model: DetailsModel) : Screen()
    class Player(val req: PlayRequest) : Screen()
}

/** Dialogo simples (mensagem, confirmacao ou digitar texto). */
class DialogSpec(
    val title: String,
    val message: String,
    val buttons: List<String>,
    val input: String? = null,
    val onButton: (index: Int, text: String) -> Unit = { _, _ -> },
)

/**
 * Estado global do app (equivale ao m.global do Roku): addons, ajustes,
 * revisoes de addons/historico, pilha de telas e dialogo aberto.
 */
class AppState(val store: Store, val scope: CoroutineScope) {
    var addons by mutableStateOf<List<Addon>>(emptyList())
    var addonsRev by mutableIntStateOf(0)
    var historyRev by mutableIntStateOf(0)
    var settings by mutableStateOf(store.loadSettings())
    var statusText by mutableStateOf("")
    var started = false

    val t: Strings by derivedStateOf { Strings(settings.lang) }

    val stack = mutableStateListOf<Screen>()
    var dialog by mutableStateOf<DialogSpec?>(null)

    /** Incrementado quando um dialogo fecha: a tela de baixo devolve o foco. */
    var focusTick by mutableIntStateOf(0)

    // ---------------------------------------------------------------------
    // Addons: le a configuracao salva e busca o manifest de cada um
    // ---------------------------------------------------------------------
    fun loadAddons() {
        val cfg = store.loadAddonConfig()
        statusText = t("loading_addons")
        scope.launch {
            val results = cfg.map { c -> async { Net.getJson(c.url + "/manifest.json") } }.awaitAll()
            val list = cfg.mapIndexed { i, c ->
                val res = results[i]
                val mf = if (res.ok) res.data else null
                val nm = mf?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: c.url
                Addon(c.url, c.enabled, nm, mf, mf != null)
            }
            addons = list
            addonsRev += 1
            statusText = ""
            if (!started) {
                started = true
                stack.add(Screen.Home(HomeModel()))
            }
        }
    }

    fun commitAddons(list: List<Addon>) {
        addons = list
        addonsRev += 1
        store.saveAddonConfig(list.map { AddonConfig(it.url, it.enabled) })
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
    }

    fun bumpHistory() {
        historyRev += 1
    }

    // ---------------------------------------------------------------------
    // Pilha de telas
    // ---------------------------------------------------------------------
    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun openDetails(info: Info) = push(Screen.Details(DetailsModel(info)))

    fun play(req: PlayRequest) = push(Screen.Player(req))

    // ---------------------------------------------------------------------
    // Dialogos
    // ---------------------------------------------------------------------
    fun showMessage(title: String, text: String) {
        dialog = DialogSpec(title, text, listOf("OK"))
    }

    fun confirm(title: String, text: String, onConfirm: () -> Unit) {
        dialog = DialogSpec(title, text, listOf(t("btn_confirm"), t("btn_cancel"))) { idx, _ ->
            if (idx == 0) onConfirm()
        }
    }

    fun closeDialog() {
        dialog = null
        focusTick += 1
    }
}
