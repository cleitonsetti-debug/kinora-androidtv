package com.kinora.tv.ui

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinora.tv.BuildConfig
import com.kinora.tv.data.Addon
import com.kinora.tv.data.AddonConfig
import com.kinora.tv.data.Info
import com.kinora.tv.data.Net
import com.kinora.tv.data.PlayRequest
import com.kinora.tv.data.Profile
import com.kinora.tv.data.Settings
import com.kinora.tv.data.Store
import com.kinora.tv.data.Strings
import com.kinora.tv.data.hostOf
import com.kinora.tv.data.normalizeAddonUrl
import com.kinora.tv.data.objects
import com.kinora.tv.data.pinHash
import com.kinora.tv.data.str
import com.kinora.tv.data.versionNewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Telas da pilha (equivale ao m.stack da MainScene do Roku). */
sealed class Screen {
    class Home(val model: HomeModel) : Screen()
    class Search(val model: SearchModel) : Screen()
    class Addons : Screen() { var focused = 0 }
    class SettingsScreen : Screen() { var cat = 0; var row = 0; var inRows = false }
    class Details(val model: DetailsModel) : Screen()
    class Player(val req: PlayRequest) : Screen()
    class Profiles(val mode: String) : Screen() { var focused = 0 }
    class Diag : Screen()
}

/** Dialogo simples (mensagem, confirmacao ou digitar texto). */
class DialogSpec(
    val title: String,
    val message: String,
    val buttons: List<String>,
    val input: String? = null,
    val onButton: (index: Int, text: String) -> Unit = { _, _ -> },
)

/** Pedido de PIN (teclado numerico do proprio app). */
class PinSpec(val title: String, val onPin: (String) -> Unit)

/**
 * Estado global do app (equivale ao m.global do Roku): addons, perfil, ajustes,
 * revisoes de addons/historico/lista, pilha de telas e dialogos.
 */
class AppState(val context: Context, val store: Store, val scope: CoroutineScope) {
    var addons by mutableStateOf<List<Addon>>(emptyList())
    var addonsRev by mutableIntStateOf(0)
    var historyRev by mutableIntStateOf(0)
    var favRev by mutableIntStateOf(0)
    var settings by mutableStateOf(Settings())
    var profile by mutableStateOf(Profile("main", "Principal", 0, false))
    var pinHashValue by mutableStateOf(store.loadPinHash())
    var statusText by mutableStateOf("")
    var started = false
    private var pendingAdd = ""

    val t: Strings by derivedStateOf { Strings(settings.lang) }

    /** Conteudo adulto escondido: ajuste do perfil ou sempre, no perfil infantil. */
    val hideAdult: Boolean get() = settings.hideAdult || profile.kids

    val stack = mutableStateListOf<Screen>()
    var dialog by mutableStateOf<DialogSpec?>(null)
    var pin by mutableStateOf<PinSpec?>(null)

    /** Incrementado quando um dialogo fecha: a tela de baixo devolve o foco. */
    var focusTick by mutableIntStateOf(0)

    init {
        // ajustes do ultimo perfil usado (o idioma aparece certo ja no "Carregando")
        val last = store.lastProfile()
        val p = store.findProfile(last) ?: store.loadProfiles().first()
        store.profileId = p.id
        profile = p
        settings = store.loadSettings()
    }

    /** Versao da release do app (ex.: 1.5.2). */
    val currentRelease: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

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
            if (!started) startAfterAddons()
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
    // Perfis: "Quem esta assistindo?" na abertura (se houver mais de um)
    // ---------------------------------------------------------------------
    private fun startAfterAddons() {
        val profs = store.loadProfiles()
        if (profs.size > 1) {
            stack.add(Screen.Profiles("select"))
        } else {
            activateProfile(profs[0].id, false)
            showHome()
        }
    }

    private fun showHome() {
        started = true
        stack.clear()
        stack.add(Screen.Home(HomeModel()))
        checkUpdate(false)
        if (pendingAdd.isNotEmpty()) {
            val b = pendingAdd
            pendingAdd = ""
            confirmRemoteAdd(b)
        }
    }

    fun activateProfile(id: String, bump: Boolean) {
        val p = store.findProfile(id) ?: store.loadProfiles().first()
        store.profileId = p.id
        profile = p
        settings = store.loadSettings()
        store.saveLastProfile(p.id)
        if (bump) {
            historyRev += 1
            favRev += 1
            addonsRev += 1
        }
    }

    /** Escolheu um perfil na tela de perfis. */
    fun onProfileChosen(id: String) {
        if (id.isEmpty()) return
        // sair de um perfil infantil para um perfil de adulto exige o PIN (se houver um)
        val target = store.findProfile(id)
        if (started && profile.kids && pinHashValue.isNotEmpty() && id != profile.id && target != null && !target.kids) {
            askPin(t("pin_current")) { pinOk -> if (pinOk) chooseProfile(id) }
            return
        }
        chooseProfile(id)
    }

    private fun chooseProfile(id: String) {
        activateProfile(id, true)
        if (!started) showHome() else pop()
    }

    /** O perfil em uso mudou (renomeado, infantil ligado/desligado, excluido). */
    fun refreshProfile() {
        val p = store.findProfile(profile.id)
        if (p == null) {
            activateProfile("main", true)
        } else {
            profile = p
            addonsRev += 1   // perfil infantil muda o que aparece (conteudo adulto)
        }
    }

    // ---------------------------------------------------------------------
    // Menu do topo e bloqueio por PIN (Ajustes e Addons)
    // ---------------------------------------------------------------------
    fun onMenuAction(action: String) {
        if (action == "settings" || action == "addons") {
            if (profile.kids) {
                if (pinHashValue.isNotEmpty()) askPin(t("pin_current")) { ok -> if (ok) openAction(action) }
                else showMessage(t("settings_title"), t("kids_locked"))
            } else if (pinHashValue.isNotEmpty()) {
                askPin(t("pin_current")) { ok -> if (ok) openAction(action) }
            } else {
                openAction(action)
            }
        } else {
            openAction(action)
        }
    }

    fun openAction(action: String) {
        when (action) {
            "search" -> push(Screen.Search(SearchModel()))
            "addons" -> push(Screen.Addons())
            "settings" -> push(Screen.SettingsScreen())
            "profiles" -> push(Screen.Profiles("select"))
            "profiles_manage" -> push(Screen.Profiles("manage"))
            "diag" -> push(Screen.Diag())
            "reload" -> loadAddons()
        }
    }

    /** Pede o PIN e confere com o salvo; onResult(true) se estiver certo. */
    fun askPin(title: String, onResult: (Boolean) -> Unit) {
        pin = PinSpec(title) { typed ->
            if (pinHash(typed) == pinHashValue) {
                onResult(true)
            } else {
                showMessage(t("settings_title"), t("pin_wrong"))
                onResult(false)
            }
        }
    }

    /** Pede um PIN qualquer (definir PIN novo). */
    fun enterPin(title: String, onPin: (String) -> Unit) {
        pin = PinSpec(title, onPin)
    }

    fun closePin() {
        pin = null
        focusTick += 1
    }

    fun setPinHash(h: String) {
        pinHashValue = h
        store.savePinHash(h)
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

    /** autoplay = "Continuar/Assistir" do banner: abre a ficha e ja procura a fonte. */
    fun openDetails(info: Info, autoplay: Boolean = false) = push(Screen.Details(DetailsModel(info, autoplay)))

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

    // ---------------------------------------------------------------------
    // Adicionar addon por fora: link stremio:// ou kinora://add?addon=<url>
    // (de outro app, do navegador da TV ou por ADB). A TV pede confirmacao.
    // ---------------------------------------------------------------------
    fun onIncomingAddon(raw: String) {
        val base = normalizeAddonUrl(raw)
        if (base.isEmpty()) return
        if (pinHashValue.isNotEmpty()) {
            if (started) showMessage(t("addons_title"), t("pin_locked_remote"))
            return
        }
        if (!started) {
            pendingAdd = base
            return
        }
        confirmRemoteAdd(base)
    }

    private fun confirmRemoteAdd(base: String) {
        dialog = DialogSpec(t("remote_add_title"), t.f("remote_add_body", hostOf(base)), listOf(t("btn_add"), t("btn_cancel"))) { idx, _ ->
            if (idx == 0) addAddon(base, hostOf(base))
        }
    }

    /** Busca o manifest e adiciona o addon (usado pela tela de Addons e pelos links). */
    fun addAddon(base: String, shownName: String, onDone: () -> Unit = {}) {
        if (addons.any { it.url == base }) {
            showMessage(t("addon_dup_t"), shownName)
            onDone()
            return
        }
        scope.launch {
            val res = Net.getJson("$base/manifest.json")
            onDone()
            val data = res.data
            if (!res.ok || data == null || !data.has("id") || data.optJSONArray("resources") == null) {
                showMessage(t("addon_bad_t"), t.f("addon_bad_b", shownName))
                return@launch
            }
            val nm = data.optString("name", "").ifEmpty { base }
            commitAddons(addons + Addon(base, true, nm, data, true))
            showMessage(t("addon_added_t"), nm)
        }
    }

    // ---------------------------------------------------------------------
    // Aviso de versao nova (releases do GitHub) e atualizacao pelo proprio app
    // ---------------------------------------------------------------------
    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val res = Net.getJson(RELEASES_API)
            var tag = ""
            var apkUrl = ""
            for (r in res.array.objects()) {
                if (r.optBoolean("draft", false)) continue
                tag = r.str("tag_name")
                for (a in r.optJSONArray("assets").objects()) {
                    if (a.str("name").lowercase().endsWith(".apk")) {
                        apkUrl = a.str("browser_download_url")
                        break
                    }
                }
                break
            }
            if (tag.isEmpty()) {
                if (manual) showMessage(t("st_update"), t("upd_failed"))
                return@launch
            }
            val latest = tag.removePrefix("v").removePrefix("V")
            val cur = currentRelease
            if (versionNewer(latest, cur)) {
                if (manual || store.seenUpdate() != latest) {
                    store.saveSeenUpdate(latest)
                    val url = apkUrl.ifEmpty { LATEST_APK }
                    dialog = DialogSpec(t("upd_new_title"), t.f2("upd_new_body", latest, cur), listOf(t("upd_install"), t("upd_later"))) { idx, _ ->
                        if (idx == 0) Updater.downloadAndInstall(this@AppState, url)
                    }
                }
            } else if (manual) {
                showMessage(t("st_update"), t.f("upd_latest", cur))
            }
        }
    }

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/cleitonsetti-debug/kinora-androidtv/releases?per_page=5"
        const val LATEST_APK = "https://github.com/cleitonsetti-debug/kinora-androidtv/releases/latest/download/Kinora-AndroidTV.apk"
    }
}
