package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextAlign
import com.kinora.tv.data.Profile
import com.kinora.tv.data.accentByIndex
import com.kinora.tv.data.initialOf

// =============================================================================
// ProfileView: "Quem esta assistindo?" - escolher, criar, renomear, marcar como
// infantil e excluir perfis. Cada perfil tem historico, lista e ajustes proprios.
//   mode = "select": OK entra no perfil; Menu (ou segurar OK) abre o menu do perfil
//   mode = "manage": OK abre o menu do perfil, que tambem tem "Entrar"
// =============================================================================

private class ProfileMenu(val title: String, val labels: List<String>, val actions: List<String>, val mode: String)

@Composable
fun ProfileScreen(app: AppState, screen: Screen.Profiles) {
    val t = app.t
    var profiles by remember { mutableStateOf(app.store.loadProfiles()) }
    var idx by remember { mutableIntStateOf(screen.focused) }
    var menu by remember { mutableStateOf<ProfileMenu?>(null) }
    var menuIdx by remember { mutableIntStateOf(0) }
    var menuTarget by remember { mutableIntStateOf(-1) }
    var pendingName by remember { mutableStateOf("") }
    val req = remember { FocusRequester() }
    var okDown by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }

    val tileCount = profiles.size + if (profiles.size < 6) 1 else 0
    if (idx >= tileCount) idx = tileCount - 1

    fun reload() {
        profiles = app.store.loadProfiles()
    }

    fun openMenu(i: Int) {
        if (i < 0 || i >= profiles.size) return
        menuTarget = i
        menuIdx = 0
        menu = ProfileMenu(
            profiles[i].name,
            listOf(t("pf_enter"), t("pf_rename"), t("pf_toggle_kids"), t("pf_delete"), t("player_close")),
            listOf("enter", "rename", "kids", "delete", "close"),
            "actions",
        )
    }

    fun closeMenu() {
        menu = null
    }

    fun showNameDialog(purpose: String, current: String) {
        app.dialog = DialogSpec(t("pf_name_dlg"), "", listOf(t("btn_confirm"), t("btn_cancel")), input = current) { b, text ->
            var txt = text.trim()
            if (txt.length > 12) txt = txt.take(12)
            if (b != 0 || txt.isEmpty()) {
                // cancelado
            } else if (purpose == "rename") {
                val list = app.store.loadProfiles().toMutableList()
                if (menuTarget in list.indices) {
                    list[menuTarget] = list[menuTarget].copy(name = txt)
                    app.store.saveProfiles(list)
                    app.refreshProfile()
                }
                reload()
            } else {
                // depois do nome, o tipo do perfil (menu do proprio app)
                pendingName = txt
                menuIdx = 0
                menu = ProfileMenu(txt, listOf(t("pf_kind_normal"), t("pf_kind_kids")), listOf("normal", "kids"), "kind")
            }
        }
    }

    fun startNewProfile() {
        if (profiles.size >= 6) {
            app.showMessage(t("pf_title"), t("pf_max"))
            return
        }
        showNameDialog("new", t("pf_default_name") + " " + (profiles.size + 1))
    }

    fun createProfile(kids: Boolean) {
        val list = app.store.loadProfiles().toMutableList()
        val id = "p" + (System.currentTimeMillis() / 1000)
        list.add(Profile(id, pendingName, list.size, kids))
        app.store.saveProfiles(list)
        closeMenu()
        reload()
    }

    fun deleteSelected() {
        val list = app.store.loadProfiles()
        if (menuTarget !in list.indices) return
        val gone = list[menuTarget].id
        if (gone == "main") return
        app.store.saveProfiles(list.filterIndexed { i, _ -> i != menuTarget })
        app.store.deleteProfileData(gone)
        reload()
        // apagou o perfil em uso: volta para o principal
        if (gone == app.profile.id) app.onProfileChosen("main")
    }

    fun onMenuSelected() {
        val mn = menu ?: return
        val act = mn.actions.getOrNull(menuIdx) ?: return
        when (mn.mode) {
            "kind" -> createProfile(act == "kids")
            "confirm" -> {
                if (act == "yes") deleteSelected()
                closeMenu()
            }
            else -> {
                val p = profiles.getOrNull(menuTarget) ?: return
                when (act) {
                    "enter" -> {
                        closeMenu()
                        app.onProfileChosen(p.id)
                    }
                    "rename" -> {
                        closeMenu()
                        showNameDialog("rename", p.name)
                    }
                    "kids" -> {
                        val list = app.store.loadProfiles().toMutableList()
                        if (menuTarget in list.indices) {
                            list[menuTarget] = list[menuTarget].copy(kids = !list[menuTarget].kids)
                            app.store.saveProfiles(list)
                            app.refreshProfile()
                        }
                        closeMenu()
                        reload()
                    }
                    "delete" -> {
                        if (p.id == "main") {
                            closeMenu()
                            app.showMessage(t("pf_title"), t("pf_main_locked"))
                        } else {
                            menuIdx = 0
                            menu = ProfileMenu(t("pf_delete_confirm"), listOf(t("btn_confirm"), t("btn_cancel")), listOf("yes", "no"), "confirm")
                        }
                    }
                    else -> closeMenu()
                }
            }
        }
    }

    fun onTile(i: Int) {
        if (i >= profiles.size) startNewProfile()
        else if (screen.mode == "manage") openMenu(i)
        else app.onProfileChosen(profiles[i].id)
    }

    BackHandler(enabled = menu != null) { closeMenu() }
    LaunchedEffect(app.focusTick) { req.focusSoon() }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Bg)
            .focusRequester(req)
            .focusable()
            .onKeyEvent { e ->
                val mn = menu
                if (e.isSelect()) {
                    // OK no soltar; segurar OK abre o menu do perfil
                    if (e.isDown()) {
                        if (e.repeatCount() == 0) {
                            okDown = true
                            longFired = false
                        } else if (okDown && !longFired && mn == null && e.repeatCount() >= 3 && idx < profiles.size) {
                            longFired = true
                            openMenu(idx)
                        }
                    } else if (e.isUp()) {
                        val fire = okDown && !longFired
                        okDown = false
                        longFired = false
                        if (fire) {
                            if (mn != null) onMenuSelected() else onTile(idx)
                        }
                    }
                    return@onKeyEvent true
                }
                if (!e.isDown()) return@onKeyEvent false
                if (mn != null) {
                    when (e.key) {
                        Key.DirectionUp -> if (menuIdx > 0) menuIdx--
                        Key.DirectionDown -> if (menuIdx < mn.labels.size - 1) menuIdx++
                        else -> {}
                    }
                    return@onKeyEvent true
                }
                when {
                    e.key == Key.DirectionLeft -> if (idx > 0) idx--
                    e.key == Key.DirectionRight -> if (idx < tileCount - 1) idx++
                    e.isOptions() -> if (idx < profiles.size) openMenu(idx)
                    else -> return@onKeyEvent false
                }
                screen.focused = idx
                true
            }
    ) {
        AccentGlow(0xFF8338EC, 360, 140, 1200, 800, 0.35f)
        KLabel(t("pf_title"), 0, 150, 1920, 90, 58, weight = W.Bold, color = K.White, align = TextAlign.Center)

        // grade de ate 6 cartoes, centralizada
        val startX = (1920 - tileCount * 280 + 20) / 2
        for (i in 0 until tileCount) {
            val isNew = i >= profiles.size
            val p = profiles.getOrNull(i)
            val x = startX + i * 280
            val sel = i == idx && menu == null
            Box(Modifier.at(x, 330).box(260, 290).clip(RoundedCornerShape(d(20))).background(if (sel) Color(0x4DFFFFFF) else Color.Transparent))
            Box(
                Modifier.at(x + 50, 350).box(160, 160).clip(CircleShape)
                    .background(if (isNew) Color(0xFF3A3A46) else Color(accentByIndex(p?.color ?: 0))),
                contentAlignment = Alignment.Center,
            ) {
                KText(if (isNew) "+" else initialOf(p?.name ?: ""), 72, weight = W.Bold, color = K.White, align = TextAlign.Center)
            }
            KLabel(if (isNew) t("pf_new") else p?.name ?: "", x, 522, 260, 44, 26, weight = W.Medium, color = K.Text, align = TextAlign.Center)
            if (p?.kids == true) {
                KLabel(t("pf_kids_badge"), x, 568, 260, 32, 19, weight = W.Medium, color = Color(0xFFA78BFA), align = TextAlign.Center)
            }
        }

        KLabel(
            if (screen.mode == "manage") t("pf_manage_hint") else t("pf_hint"),
            0, 800, 1920, 40, 24, color = K.Hint, align = TextAlign.Center,
        )

        // Menu do perfil (acoes / tipo do perfil novo / confirmar exclusao)
        menu?.let { mn ->
            Box(Modifier.fillMaxSize().background(Color(0xB8000000)))
            Box(Modifier.at(640, 280).box(640, 520).glassCard(22))
            KLabel(mn.title, 690, 304, 540, 56, 30, weight = W.Bold, color = K.White)
            mn.labels.forEachIndexed { i, label ->
                val sel = i == menuIdx
                Box(
                    Modifier.at(680, 380 + i * 64).box(560, 60).clip(RoundedCornerShape(d(30)))
                        .background(if (sel) K.White else Color.Transparent),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.at(24, 0)) {
                        KText(label, 24, weight = W.Medium, color = if (sel) K.Bg else K.TextList)
                    }
                }
            }
        }
    }
}
