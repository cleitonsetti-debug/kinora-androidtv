package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.style.TextAlign
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.Info
import com.kinora.tv.data.Net
import com.kinora.tv.data.objects
import com.kinora.tv.data.urlEncode
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================
// SearchView: teclado proprio (a tela recebe as teclas do controle) + resultados.
//   Teclado: setas movem o cursor, OK digita.
//   Atalhos: voltar-rapido = apagar, avancar-rapido = espaco, play = resultados.
//   Resultados: Voltar / Esquerda / Cima (na borda) / Menu voltam ao teclado.
//   Um teclado fisico/USB tambem digita direto.
// =============================================================================

class KeyDef(val label: String, val kind: String, val ch: String)

class SearchModel {
    var query by mutableStateOf("")
    var focusArea by mutableStateOf("kb")
    var curCol by mutableIntStateOf(0)
    var curRow by mutableIntStateOf(0)
    var results by mutableStateOf<List<Info>>(emptyList())
    var status by mutableStateOf("")
    var gridFocused by mutableIntStateOf(0)
    val gridState = LazyGridState()
    val gridReq = FocusRequester()
    val kbReq = FocusRequester()
    var focusCmd by mutableIntStateOf(0)
    var debounceJob: Job? = null
    var searchJob: Job? = null

    val cols = 6
    val rowsCount = 7

    fun keyDefs(app: AppState): List<KeyDef> {
        val t = app.t
        val out = ArrayList<KeyDef>()
        for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") out.add(KeyDef(c.toString(), "char", c.lowercase()))
        out.add(KeyDef(t("key_space"), "space", " "))
        out.add(KeyDef(t("key_del"), "del", ""))
        out.add(KeyDef(t("key_clear"), "clear", ""))
        out.add(KeyDef("-", "char", "-"))
        out.add(KeyDef("'", "char", "'"))
        out.add(KeyDef(t("key_go"), "go", ""))
        return out
    }

    fun goResults() {
        if (results.isEmpty()) return
        focusArea = "grid"
        focusCmd += 1
    }

    fun goKeyboard() {
        focusArea = "kb"
        focusCmd += 1
    }

    fun applyKey(app: AppState, kind: String, ch: String) {
        when (kind) {
            "go" -> {
                goResults()
                return
            }
            "char" -> if (query.length < 60) query += ch
            "space" -> if (query.isNotEmpty() && !query.endsWith(" ")) query += " "
            "del" -> if (query.isNotEmpty()) query = query.dropLast(1)
            "clear" -> query = ""
        }
        debounceJob?.cancel()
        debounceJob = app.scope.launch {
            delay(700)
            runSearch(app)
        }
    }

    private fun clearResults(message: String) {
        results = emptyList()
        status = message
        if (focusArea == "grid") goKeyboard()
    }

    private fun runSearch(app: AppState) {
        val t = app.t
        val q = query.trim()
        searchJob?.cancel()
        if (q.length < 2) {
            clearResults(t("search_hint_min"))
            return
        }
        val catalogs = AddonStore.listCatalogs(app.addons, "", true, 6, t)
        if (catalogs.isEmpty()) {
            clearResults(t("search_none_addon"))
            return
        }
        status = t("searching")
        val enc = urlEncode(q)
        searchJob = app.scope.launch {
            val res = catalogs.map { c ->
                async {
                    Net.getJson(c.base + "/catalog/" + urlEncode(c.kind) + "/" + urlEncode(c.id) + "/search=" + enc + ".json")
                }
            }.awaitAll()
            val seen = HashSet<String>()
            val out = ArrayList<Info>()
            for (i in catalogs.indices) {
                val r = res[i]
                if (!r.ok) continue
                for (meta in r.data?.optJSONArray("metas").objects()) {
                    if (out.size >= 40) break
                    val info = Info.fromMeta(meta, catalogs[i].base, catalogs[i].kind)
                    if (info.id.isNotEmpty() && info.name.isNotEmpty() && seen.add(info.id)) out.add(info)
                }
            }
            if (out.isEmpty()) {
                clearResults(t("nothing_found"))
            } else {
                results = out
                gridFocused = 0
                try {
                    gridState.scrollToItem(0)
                } catch (e: Exception) {
                }
                status = ""
            }
        }
    }
}

@Composable
fun SearchScreen(app: AppState, m: SearchModel) {
    val t = app.t
    val keys = remember(app.settings.lang) { m.keyDefs(app) }

    LaunchedEffect(Unit) {
        if (m.status.isEmpty() && m.results.isEmpty()) m.status = t("search_hint_min")
    }

    LaunchedEffect(m.focusCmd, app.focusTick) {
        if (m.focusArea == "grid" && m.results.isNotEmpty()) {
            val visible = m.gridState.layoutInfo.visibleItemsInfo.any { it.index == m.gridFocused }
            if (!visible) m.gridState.scrollToItem(m.gridFocused)
            m.gridReq.focusSoon()
        } else {
            m.focusArea = "kb"
            m.kbReq.focusSoon()
        }
    }

    BackHandler(enabled = m.focusArea == "grid") { m.goKeyboard() }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        KLabel(t("search_title"), 80, 40, 800, 70, 42, weight = W.Bold, color = K.White)

        // Campo da busca
        Box(Modifier.at(80, 150).box(588, 68).clip(RoundedCornerShape(d(12))).background(K.Surface))
        val placeholder = m.query.isEmpty()
        KLabel(
            if (placeholder) t("search_placeholder") else m.query + "|",
            104, 150, 546, 68, 26,
            weight = W.Medium,
            color = if (placeholder) K.Muted else K.White,
            vAlign = Alignment.CenterVertically,
        )

        // Teclado: um unico elemento focavel que trata as setas (como o Roku)
        Box(
            Modifier
                .at(80, 240)
                .box(588, 470)
                .focusRequester(m.kbReq)
                .focusable()
                .onKeyEvent { e ->
                    if (!e.isDown()) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> {
                            if (m.curCol > 0) m.curCol -= 1
                            true
                        }
                        Key.DirectionRight -> {
                            if (m.curCol < m.cols - 1) m.curCol += 1 else m.goResults()
                            true
                        }
                        Key.DirectionUp -> {
                            if (m.curRow > 0) m.curRow -= 1
                            true
                        }
                        Key.DirectionDown -> {
                            if (m.curRow < m.rowsCount - 1) m.curRow += 1
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.repeatCount() == 0) {
                                val k = keys.getOrNull(m.curRow * m.cols + m.curCol)
                                if (k != null) m.applyKey(app, k.kind, k.ch)
                            }
                            true
                        }
                        Key.MediaRewind, Key.Backspace, Key.Delete -> {
                            m.applyKey(app, "del", "")
                            true
                        }
                        Key.MediaFastForward, Key.Spacebar -> {
                            m.applyKey(app, "space", " ")
                            true
                        }
                        Key.MediaPlay, Key.MediaPlayPause -> {
                            m.goResults()
                            true
                        }
                        else -> {
                            // Teclado fisico: letras, numeros, - e '
                            val cp = e.utf16CodePoint
                            if (cp > 0) {
                                val c = cp.toChar()
                                if (c.isLetterOrDigit() || c == '-' || c == '\'') {
                                    m.applyKey(app, "char", c.lowercaseChar().toString())
                                    return@onKeyEvent true
                                }
                            }
                            false
                        }
                    }
                }
        ) {
            keys.forEachIndexed { i, k ->
                val col = i % m.cols
                val rw = i / m.cols
                Box(
                    Modifier.at(col * 98, rw * 68).box(92, 62).clip(RoundedCornerShape(d(10))).background(K.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    KText(k.label, 20, weight = W.Medium, color = K.Text, align = TextAlign.Center)
                }
            }
            // Cursor translucido
            if (m.focusArea == "kb") {
                Box(
                    Modifier.at(m.curCol * 98, m.curRow * 68).box(92, 62)
                        .clip(RoundedCornerShape(d(10))).background(Color(0x4DFFFFFF))
                )
            }
        }

        KLabel(t("search_hint_keys"), 80, 730, 588, 70, 18, color = K.Muted, maxLines = 2)

        // Resultados: grade de 5 colunas
        if (m.results.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = m.gridState,
                modifier = Modifier.at(690, 140).box(1230, 940),
                contentPadding = PaddingValues(start = d(10), top = d(10), end = d(110), bottom = d(60)),
                horizontalArrangement = Arrangement.spacedBy(d(22)),
                verticalArrangement = Arrangement.spacedBy(d(26)),
            ) {
                itemsIndexed(m.results) { idx, info ->
                    PosterCard(
                        url = info.poster,
                        w = 200,
                        h = 350,
                        title = info.name,
                        modifier = (if (idx == m.gridFocused) Modifier.focusRequester(m.gridReq) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (!e.isDown()) return@onPreviewKeyEvent false
                                val toKb = (e.key == Key.DirectionLeft && idx % 5 == 0) ||
                                    (e.key == Key.DirectionUp && idx < 5) || e.isOptions()
                                if (toKb) {
                                    m.goKeyboard()
                                    true
                                } else if (e.key == Key.DirectionRight && (idx % 5 == 4 || idx == m.results.size - 1)) {
                                    true
                                } else if (e.key == Key.DirectionDown && idx + 5 >= m.results.size &&
                                    (idx / 5) == (m.results.size - 1) / 5
                                ) {
                                    true
                                } else {
                                    false
                                }
                            },
                        onFocus = {
                            m.gridFocused = idx
                            m.focusArea = "grid"
                        },
                        onClick = { app.openDetails(info) },
                    )
                }
            }
        }

        if (m.results.isEmpty() && m.status.isNotEmpty()) {
            KLabel(m.status, 700, 420, 1100, 140, 28, weight = W.Medium, color = K.Meta, maxLines = 3, align = TextAlign.Center)
        }
    }
}
