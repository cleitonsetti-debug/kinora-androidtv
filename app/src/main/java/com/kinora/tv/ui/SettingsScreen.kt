package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.kinora.tv.BuildConfig
import com.kinora.tv.R
import com.kinora.tv.data.I18n
import com.kinora.tv.data.Settings
import com.kinora.tv.data.Store
import com.kinora.tv.data.accentByIndex
import com.kinora.tv.data.pinHash

// =============================================================================
// SettingsView: categorias a esquerda, linhas com valor a direita. Tudo guiado por
// dados: cada linha tem uma chave (key) e um tipo (toggle, cycle ou action).
// =============================================================================

private class SetCat(val id: String, val title: String, val icon: Int)
private class SetRow(val key: String, val title: String, val hint: String)
private class SetValue(val text: String, val tone: String)

@Composable
fun SettingsScreen(app: AppState, screen: Screen.SettingsScreen) {
    val t = app.t
    var catIdx by remember { mutableIntStateOf(screen.cat) }
    var rowIdx by remember { mutableIntStateOf(screen.row) }
    var inRows by remember { mutableStateOf(screen.inRows) }
    val req = remember { FocusRequester() }
    var okDown by remember { mutableStateOf(false) }
    val st = app.settings

    val cats = listOf(
        SetCat("general", t("cat_general"), R.drawable.ic_sliders),
        SetCat("playback", t("cat_playback"), R.drawable.ic_play),
        SetCat("subs", t("cat_subs"), R.drawable.ic_subs),
        SetCat("safety", t("cat_safety"), R.drawable.ic_shield),
        SetCat("profiles", t("cat_profiles"), R.drawable.ic_user),
        SetCat("data", t("cat_data"), R.drawable.ic_data),
        SetCat("about", t("cat_about"), R.drawable.ic_info),
    )

    fun row(key: String) = SetRow(key, t("st_$key"), t("st_${key}_h"))

    fun rowsFor(id: String): List<SetRow> = when (id) {
        "general" -> listOf(row("lang"), row("carousel"), row("zoom"), row("ambient"))
        "playback" -> listOf(row("resume"), row("autopick"), row("quality"), row("autonext"), row("intro"), row("jump"))
        "subs" -> listOf(row("sublang"), row("audiolang"))
        "safety" -> listOf(row("hideadult"), row("pin"))
        "profiles" -> listOf(
            SetRow("profile_switch", t("st_profile_now") + ": " + app.profile.name, t("st_profile_switch_h")),
            row("profile_manage"),
        )
        "data" -> listOf(row("clear_history"), row("clear_favs"), row("clear_searches"), row("clear_watched"), row("reset_addons"), row("reset_settings"))
        else -> listOf(row("update"), row("diag"), row("shortcuts"), row("about"))
    }

    fun yesNo(v: Boolean) = SetValue(if (v) t("yes") else t("no"), if (v) "on" else "off")
    fun langLabel(v: String, offKey: String) = if (v == "off" || v == "auto") t(offKey) else t("lang_opt_$v")

    // Valor mostrado na "pilula": texto + tom (on | off | action | locked)
    fun valueFor(key: String): SetValue = when (key) {
        "lang" -> SetValue(t("lang_name"), "action")
        "carousel" -> yesNo(st.carousel)
        "zoom" -> yesNo(st.zoom)
        "ambient" -> yesNo(st.ambient)
        "resume" -> yesNo(st.resume)
        "autopick" -> yesNo(st.autoPick)
        "quality" -> SetValue(if (st.quality == "auto") t("opt_auto") else st.quality + "p", "action")
        "autonext" -> yesNo(st.autoNext)
        "intro" -> yesNo(st.intro)
        "jump" -> SetValue("${st.jump} s", "action")
        "sublang" -> SetValue(langLabel(st.subLang, "opt_off"), "action")
        "audiolang" -> SetValue(langLabel(st.audioLang, "opt_auto"), "action")
        "hideadult" -> if (app.profile.kids) SetValue(t("v_locked"), "locked") else yesNo(st.hideAdult)
        "pin" -> yesNo(app.pinHashValue.isNotEmpty())
        "profile_switch" -> SetValue(t("v_switch"), "action")
        "profile_manage", "diag" -> SetValue(t("v_open"), "action")
        "clear_history", "clear_favs", "clear_searches", "clear_watched" -> SetValue(t("v_clear"), "action")
        "reset_addons", "reset_settings" -> SetValue(t("v_restore"), "action")
        "update" -> SetValue(app.currentRelease, "action")
        "shortcuts" -> SetValue(t("v_view"), "action")
        "about" -> SetValue(BuildConfig.VERSION_NAME, "action")
        else -> SetValue("", "action")
    }

    fun next(cur: String, options: List<String>): String {
        val i = options.indexOf(cur)
        return if (i < 0) options[0] else options[(i + 1) % options.size]
    }

    fun set(s: Settings) = app.updateSettings(s)

    fun activate(key: String) {
        when (key) {
            "lang" -> set(st.copy(lang = next(st.lang, I18n.codes)))
            "carousel" -> set(st.copy(carousel = !st.carousel))
            "zoom" -> set(st.copy(zoom = !st.zoom))
            "ambient" -> set(st.copy(ambient = !st.ambient))
            "resume" -> set(st.copy(resume = !st.resume))
            "autopick" -> set(st.copy(autoPick = !st.autoPick))
            "quality" -> set(st.copy(quality = next(st.quality, listOf("auto", "1080", "720", "480"))))
            "autonext" -> set(st.copy(autoNext = !st.autoNext))
            "intro" -> set(st.copy(intro = !st.intro))
            "jump" -> set(st.copy(jump = next(st.jump.toString(), listOf("10", "15", "30")).toInt()))
            "sublang" -> set(st.copy(subLang = next(st.subLang, listOf("off", "pt", "en", "es"))))
            "audiolang" -> set(st.copy(audioLang = next(st.audioLang, listOf("auto", "pt", "en", "es"))))
            "hideadult" -> if (!app.profile.kids) {
                set(st.copy(hideAdult = !st.hideAdult))
                app.addonsRev += 1
            }
            "pin" -> if (app.pinHashValue.isEmpty()) {
                app.enterPin(t("pin_new")) { first ->
                    app.enterPin(t("pin_repeat")) { second ->
                        if (first == second && first.isNotEmpty()) app.setPinHash(pinHash(first))
                        else app.showMessage(t("settings_title"), t("pin_mismatch"))
                    }
                }
            } else {
                app.askPin(t("pin_current")) { ok -> if (ok) app.setPinHash("") }
            }
            "profile_switch" -> app.openAction("profiles")
            "profile_manage" -> app.openAction("profiles_manage")
            "clear_history" -> app.confirm(t("settings_title"), t("confirm_clear_history")) {
                app.store.clearHistory()
                app.bumpHistory()
                app.showMessage(t("settings_title"), t("history_cleared"))
            }
            "clear_favs" -> app.confirm(t("settings_title"), t("confirm_clear_favs")) {
                app.store.clearFavorites()
                app.favRev += 1
                app.showMessage(t("settings_title"), t("done_ok"))
            }
            "clear_searches" -> app.confirm(t("settings_title"), t("confirm_clear_searches")) {
                app.store.clearSearches()
                app.showMessage(t("settings_title"), t("done_ok"))
            }
            "clear_watched" -> app.confirm(t("settings_title"), t("confirm_clear_watched")) {
                app.store.clearWatched()
                app.showMessage(t("settings_title"), t("done_ok"))
            }
            "reset_addons" -> app.confirm(t("settings_title"), t("confirm_reset_addons")) {
                app.store.saveAddonConfig(Store.defaultAddonConfig())
                app.loadAddons()
                app.showMessage(t("settings_title"), t("addons_reset"))
            }
            "reset_settings" -> app.confirm(t("settings_title"), t("confirm_reset_settings")) {
                set(Settings())
                app.showMessage(t("settings_title"), t("settings_reset"))
            }
            "update" -> app.checkUpdate(true)
            "diag" -> app.openAction("diag")
            "shortcuts" -> app.showMessage(t("st_shortcuts"), t("shortcuts_body").replace("|", "\n"))
            "about" -> app.showMessage(t("about_title"), t("about_body") + "  [" + BuildConfig.VERSION_NAME + "]")
        }
    }

    val rows = rowsFor(cats[catIdx.coerceIn(0, cats.size - 1)].id)
    if (rowIdx >= rows.size) rowIdx = 0

    BackHandler(enabled = inRows) {
        inRows = false
        screen.inRows = false
    }
    LaunchedEffect(app.focusTick) { req.focusSoon() }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Bg)
            .focusRequester(req)
            .focusable()
            .onKeyEvent { e ->
                if (e.isSelect()) {
                    if (e.isDown() && e.repeatCount() == 0) okDown = true
                    if (e.isUp() && okDown) {
                        okDown = false
                        if (inRows) rows.getOrNull(rowIdx)?.let { activate(it.key) }
                        else if (rows.isNotEmpty()) {
                            inRows = true
                            rowIdx = 0
                        }
                    }
                    return@onKeyEvent true
                }
                if (!e.isDown()) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> if (inRows) { if (rowIdx > 0) rowIdx-- } else if (catIdx > 0) { catIdx--; rowIdx = 0 }
                    Key.DirectionDown -> if (inRows) { if (rowIdx < rows.size - 1) rowIdx++ } else if (catIdx < cats.size - 1) { catIdx++; rowIdx = 0 }
                    Key.DirectionRight -> if (!inRows && rows.isNotEmpty()) {
                        inRows = true
                        rowIdx = 0
                    }
                    Key.DirectionLeft -> inRows = false
                    else -> return@onKeyEvent false
                }
                screen.cat = catIdx
                screen.row = rowIdx
                screen.inRows = inRows
                true
            }
    ) {
        AccentGlow(accentByIndex(app.profile.color), -300, 200, 1500, 1000, 0.30f)
        KLabel(t("settings_title"), 80, 44, 800, 70, 48, weight = W.Bold, color = K.White)
        KLabel(t("st_profile_now") + ": " + app.profile.name, 80, 110, 1400, 36, 22, color = K.Hint)

        // categorias
        cats.forEachIndexed { i, c ->
            val sel = i == catIdx
            val focused = sel && !inRows
            Box(Modifier.at(70, 170 + i * 92).box(520, 92).padding(top = d(4), bottom = d(4)).glassCard(18, highlighted = sel))
            if (focused) Box(Modifier.at(70, 174 + i * 92).box(520, 84).clip(RoundedCornerShape(d(18))).background(Color(0x4DFFFFFF)))
            Image(painter = painterResource(c.icon), contentDescription = null, modifier = Modifier.at(96, 192 + i * 92).box(44, 44))
            KLabel(c.title, 160, 170 + i * 92, 410, 92, 25, weight = W.Bold, color = K.Text, vAlign = Alignment.CenterVertically)
        }

        // linhas da categoria
        rows.forEachIndexed { i, r ->
            val y = 170 + i * 100
            val focused = inRows && i == rowIdx
            Box(Modifier.at(640, y + 4).box(1180, 92).glassCard(18, highlighted = focused))
            if (focused) Box(Modifier.at(640, y + 4).box(1180, 92).clip(RoundedCornerShape(d(18))).background(Color(0x33FFFFFF)))
            KLabel(r.title, 672, y + 12, 800, 42, 26, weight = W.Bold, color = K.White)
            KLabel(r.hint, 672, y + 54, 800, 32, 19, color = K.Hint)
            val v = valueFor(r.key)
            val cw = (v.text.length * 13 + 44).coerceIn(120, 330)
            val (bg, fg) = when (v.tone) {
                "on" -> Pair(Color(0xFF2ECC71), K.White)
                "off" -> Pair(Color(0xFF6A6A75), Color(0xFFE6E6EE))
                "locked" -> Pair(Color(0xFFFF7A29), K.White)
                else -> Pair(Color(0x3CFFFFFF), K.White)
            }
            Box(
                Modifier.at(640 + 1180 - cw - 30, y + 26).box(cw, 44).clip(RoundedCornerShape(d(12))).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                KText(v.text, 21, weight = W.Bold, color = fg, align = TextAlign.Center)
            }
        }

        KLabel(t("settings_hint"), 80, 1000, 1760, 40, 22, color = K.Hint)
    }
}
