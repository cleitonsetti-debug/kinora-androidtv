package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import com.kinora.tv.R
import com.kinora.tv.data.Addon
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.Net
import com.kinora.tv.data.accentFor
import com.kinora.tv.data.initialOf
import com.kinora.tv.data.normalizeAddonUrl
import com.kinora.tv.data.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

// =============================================================================
// AddonsView: cartoes com icone, painel de detalhes e botoes (ativar/desativar,
// atualizar, configurar, remover). Itens fixos no topo: adicionar e atualizar todos.
// =============================================================================

/** Avatar do addon: logo do manifest ou a inicial numa bolinha colorida. */
@Composable
private fun AddonAvatar(a: Addon?, special: Int, x: Int, y: Int, size: Int, fontSize: Int) {
    val logo = a?.manifest?.str("logo") ?: ""
    Box(
        Modifier.at(x, y).box(size, size).clip(CircleShape)
            .background(if (a == null) Color(0xFF3A3A46) else Color(accentFor(a.url))),
        contentAlignment = Alignment.Center,
    ) {
        if (a == null) {
            if (special != 0) Image(painter = painterResource(special), contentDescription = null, modifier = Modifier.box(size / 2, size / 2))
        } else {
            KText(initialOf(a.name), fontSize, weight = W.Bold, color = K.White, align = TextAlign.Center)
            if (logo.isNotEmpty()) {
                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun AddonsScreen(app: AppState, screen: Screen.Addons) {
    val t = app.t
    var idx by remember { mutableIntStateOf(screen.focused) }
    var area by remember { mutableStateOf("list") }
    var actIdx by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf("") }
    var okDown by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val addons = app.addons
    val total = addons.size + 2
    if (idx >= total) idx = total - 1
    val addonIdx = idx - 2
    val cur = addons.getOrNull(addonIdx)
    if (cur == null && area == "actions") area = "list"

    fun stateLabel(a: Addon) = when {
        !a.ok -> t("ad_state_err")
        !a.enabled -> t("ad_state_off")
        else -> t("ad_state_on")
    }

    fun subtitle(a: Addon): String {
        val v = a.manifest?.str("version") ?: ""
        return if (a.ok && v.isNotEmpty()) "v$v   •   " + stateLabel(a) else stateLabel(a)
    }

    fun toggle(i: Int) {
        val list = app.addons.toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(enabled = !list[i].enabled)
        app.commitAddons(list)
    }

    fun askRemove(i: Int) {
        val a = app.addons.getOrNull(i) ?: return
        app.confirm(t("addons_title"), t.f("ad_remove_confirm", a.name)) {
            val list = app.addons.toMutableList()
            if (i in list.indices) {
                list.removeAt(i)
                area = "list"
                app.commitAddons(list)
            }
        }
    }

    fun configure(i: Int) {
        val a = app.addons.getOrNull(i) ?: return
        val url = AddonStore.addonConfigUrl(a)
        if (url.isEmpty()) app.showMessage(t("ad_cfg_title"), t("ad_cfg_none"))
        else app.showMessage(a.name, t("ad_cfg_body") + "\n\n" + url)
    }

    /** Busca o manifest de novo: 0 = falhou, 1 = igual, 2 = atualizado (com as versoes). */
    suspend fun refetch(a: Addon): Triple<Int, String, Addon?> {
        val res = Net.getJson(a.url + "/manifest.json")
        val data = res.data
        if (!res.ok || data == null || !data.has("id")) return Triple(0, "", null)
        val oldV = a.manifest?.str("version") ?: ""
        val newV = data.str("version")
        val nm = data.optString("name", "").ifEmpty { a.name }
        val updated = a.copy(manifest = data, ok = true, name = nm)
        return Triple(if (oldV != newV) 2 else 1, "$oldV|$newV", updated)
    }

    fun updateOne(i: Int) {
        val a = app.addons.getOrNull(i) ?: return
        hint = t("ad_checking")
        app.scope.launch {
            val (r, versions, updated) = refetch(a)
            hint = ""
            if (r == 0 || updated == null) {
                app.showMessage(t("ad_act_update"), t.f("ad_update_failed", a.name))
                return@launch
            }
            app.commitAddons(app.addons.map { if (it.url == a.url) updated else it })
            if (r == 2) app.showMessage(updated.name, t.f2("ad_updated", versions.substringBefore('|'), versions.substringAfter('|')))
            else app.showMessage(updated.name, t.f("ad_uptodate", updated.name))
        }
    }

    fun updateAll() {
        val list = app.addons
        if (list.isEmpty()) return
        hint = t("ad_checking")
        app.scope.launch {
            val results = list.map { a -> async { refetch(a) } }.awaitAll()
            var changed = 0
            val out = list.mapIndexed { i, a ->
                val (r, _, updated) = results[i]
                if (r == 2) changed++
                updated ?: a
            }
            hint = ""
            app.commitAddons(out)
            app.showMessage(t("ad_title_updated"), t.f2("ad_updated_all", list.size.toString(), changed.toString()))
        }
    }

    fun showAddDialog() {
        app.dialog = DialogSpec(
            title = t("addon_dlg_title"),
            message = t("addon_dlg_msg"),
            buttons = listOf(t("btn_add"), t("btn_cancel")),
            input = "https://",
        ) { b, text ->
            if (b == 0) {
                val base = normalizeAddonUrl(text)
                if (base.isEmpty()) {
                    app.showMessage(t("addon_url_bad_t"), t("addon_url_bad_b"))
                } else {
                    hint = t("addons_checking")
                    app.addAddon(base, base) { hint = "" }
                }
            }
        }
    }

    fun onListOk() {
        when {
            idx == 0 -> showAddDialog()
            idx == 1 -> updateAll()
            cur != null -> {
                area = "actions"
                actIdx = 0
            }
        }
    }

    fun onAction() {
        when (actIdx) {
            0 -> toggle(addonIdx)
            1 -> updateOne(addonIdx)
            2 -> configure(addonIdx)
            3 -> askRemove(addonIdx)
        }
    }

    BackHandler(enabled = area == "actions") { area = "list" }
    LaunchedEffect(app.focusTick) { req.focusSoon() }
    LaunchedEffect(idx) {
        val vis = listState.layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty() && (idx < vis.first().index || idx > vis.last().index - 1)) {
            listState.scrollToItem((idx - 6).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Bg)
            .focusRequester(req)
            .focusable()
            .onKeyEvent { e ->
                if (e.isSelect()) {
                    if (e.isDown()) {
                        if (e.repeatCount() == 0) {
                            okDown = true
                            longFired = false
                        } else if (okDown && !longFired && area == "list" && cur != null && e.repeatCount() >= 3) {
                            longFired = true
                            askRemove(addonIdx)
                        }
                    } else if (e.isUp()) {
                        val fire = okDown && !longFired
                        okDown = false
                        longFired = false
                        if (fire) {
                            if (area == "actions") onAction() else onListOk()
                        }
                    }
                    return@onKeyEvent true
                }
                if (!e.isDown()) return@onKeyEvent false
                if (area == "actions") {
                    when (e.key) {
                        Key.DirectionLeft -> if (actIdx > 0) actIdx-- else area = "list"
                        Key.DirectionRight -> if (actIdx < 3) actIdx++
                        Key.DirectionUp -> area = "list"
                        Key.DirectionDown -> {}
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                when {
                    e.key == Key.DirectionUp -> if (idx > 0) idx--
                    e.key == Key.DirectionDown -> if (idx < total - 1) idx++
                    e.key == Key.DirectionRight -> if (cur != null) {
                        area = "actions"
                        actIdx = 0
                    }
                    e.key == Key.DirectionLeft -> {}
                    e.isOptions() -> if (cur != null) askRemove(addonIdx)
                    else -> return@onKeyEvent false
                }
                screen.focused = idx
                true
            }
    ) {
        AccentGlow(if (cur != null) accentFor(cur.url) else 0xFF8338EC, 700, -80, 1500, 1000, 0.35f)
        KLabel(t("addons_title"), 80, 44, 800, 70, 48, weight = W.Bold, color = K.White)
        KLabel(t.f("ad_count", addons.size.toString()), 80, 110, 800, 36, 22, color = K.Hint)

        // lista de cartoes
        LazyColumn(state = listState, modifier = Modifier.at(70, 170).box(820, 800), userScrollEnabled = false) {
            itemsIndexed(List(total) { it }) { i, _ ->
                val a = addons.getOrNull(i - 2)
                val sel = i == idx
                Box(Modifier.box(820, 100)) {
                    Box(Modifier.at(0, 4).box(820, 92).glassCard(18, highlighted = sel))
                    if (sel && area == "list") Box(Modifier.at(0, 4).box(820, 92).clip(RoundedCornerShape(d(18))).background(Color(0x40FFFFFF)))
                    AddonAvatar(a, if (i == 0) R.drawable.ic_plus else R.drawable.ic_refresh, 18, 18, 64, 30)
                    val title = when (i) {
                        0 -> t("ad_add_short")
                        1 -> t("ad_update_all")
                        else -> a?.name ?: ""
                    }
                    val sub = when (i) {
                        0 -> t("ad_add_hint")
                        1 -> t("ad_update_all_hint")
                        else -> a?.let { subtitle(it) } ?: ""
                    }
                    KLabel(title, 104, 14, 630, 40, 26, weight = W.Bold, color = K.White)
                    KLabel(sub, 104, 54, 630, 32, 19, color = K.Hint)
                    if (a != null) {
                        val dot = when {
                            !a.ok -> Color(0xFFFF4D4D)
                            !a.enabled -> Color(0xFF6A6A75)
                            else -> Color(0xFF2ECC71)
                        }
                        Box(Modifier.at(774, 41).box(18, 18).clip(CircleShape).background(dot))
                    }
                }
            }
        }

        // painel de detalhes do addon em foco
        Box(Modifier.at(950, 170).box(890, 790).glassCard(22))
        if (cur == null) {
            AddonAvatar(null, 0, 990, 206, 120, 56)
            KLabel(t("ad_help"), 990, 350, 810, 380, 21, color = K.TextList, maxLines = 12)
        } else {
            AddonAvatar(cur, 0, 990, 206, 120, 56)
            KLabel(cur.name, 1136, 212, 660, 60, 38, weight = W.Bold, color = K.White)
            KLabel(subtitle(cur), 1136, 276, 660, 40, 23, weight = W.Medium, color = K.Meta)
            KLabel(AddonStore.addonBody(cur, t), 990, 350, 810, 400, 21, color = K.TextList, maxLines = 12)
            val acts = listOf(
                Pair(if (cur.enabled) t("ad_act_disable") else t("ad_act_enable"), R.drawable.ic_power),
                Pair(t("ad_act_update"), R.drawable.ic_refresh),
                Pair(t("ad_act_config"), R.drawable.ic_gear),
                Pair(t("ad_act_remove"), R.drawable.ic_trash),
            )
            acts.forEachIndexed { i, (label, icon) ->
                IconButtonTile(label, icon, 150, 112, focused = area == "actions" && i == actIdx, modifier = Modifier.at(990 + i * 166, 786))
            }
        }

        KLabel(hint, 80, 990, 1760, 40, 24, weight = W.Medium, color = K.Meta)
    }
}
