package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextAlign
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.CatalogRef
import com.kinora.tv.data.FilterCatalog
import com.kinora.tv.data.Info
import com.kinora.tv.data.JsonResult
import com.kinora.tv.data.Net
import com.kinora.tv.data.Strings
import com.kinora.tv.data.objects
import com.kinora.tv.data.urlEncode
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================
// HomeView: barra de navegacao no topo, filtros (Filmes/Series), banner (hero)
// do titulo em foco e linhas de catalogos dos addons ativos.
// A linha em foco fica sempre em y=548; as proximas vem abaixo.
// =============================================================================

class RowDef(val title: String, val items: List<Info>) {
    val listState = LazyListState()
    var focusedIdx by mutableIntStateOf(0)
    val requester = FocusRequester()
}

class FState(var cat: Int = -1, var genre: String = "")

class PickerState(
    val mode: String,
    val title: String,
    val labels: List<String>,
    val values: List<Any>,
    val current: Int,
)

fun progressOf(info: Info): Float =
    if (info.duration > 0 && info.position > 0) (info.position.toFloat() / info.duration).coerceAtMost(1f) else 0f

fun metaLine(t: Strings, info: Info, withEpisode: Boolean): String {
    val parts = ArrayList<String>()
    val k = t.kindSingular(info.kind)
    if (k.isNotEmpty()) parts.add(k)
    if (withEpisode && (info.season > 0 || info.episode > 0)) parts.add(t.epCode(info.season, info.episode))
    if (info.year.isNotEmpty()) parts.add(info.year)
    if (info.rating.isNotEmpty()) parts.add("IMDb " + info.rating)
    if (info.genres.isNotEmpty()) parts.add(info.genres)
    return parts.joinToString("  •  ")
}

class HomeModel {
    val menuKeys = listOf("home", "movies", "series", "search", "addons", "settings")

    var category by mutableStateOf("home")
    var rows by mutableStateOf<List<RowDef>>(emptyList())
    var curRow by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var status by mutableStateOf("")
    var hero by mutableStateOf<Info?>(null)
    var pendingHero by mutableStateOf<Info?>(null)
    var focusArea by mutableStateOf("nav")
    var navIdx by mutableIntStateOf(0)
    var chips by mutableStateOf<List<String>>(emptyList())
    var chipIdx by mutableIntStateOf(0)
    var picker by mutableStateOf<PickerState?>(null)

    val fState = mapOf("movie" to FState(), "series" to FState())
    var catOptions: List<FilterCatalog> = emptyList()
    var singleMode = false

    var loadedRev = -1
    var loadedHist = -1
    var loadedLang = ""
    private var loadJob: Job? = null
    private var navJob: Job? = null

    val navReq = List(6) { FocusRequester() }
    val chipReq = List(2) { FocusRequester() }

    var focusCmd by mutableIntStateOf(0)
    var focusTarget = "nav"

    fun requestFocus(target: String) {
        focusTarget = target
        focusCmd += 1
    }

    fun currentKind(): String = when (category) {
        "movies" -> "movie"
        "series" -> "series"
        else -> ""
    }

    fun isStale(app: AppState): Boolean {
        if (app.addonsRev != loadedRev) return true
        if (category == "home" && app.historyRev != loadedHist) return true
        if (app.settings.lang != loadedLang) return true
        return false
    }

    fun focusNav() {
        focusArea = "nav"
        requestFocus("nav")
    }

    fun focusFilters() {
        focusArea = "filters"
        requestFocus("filters")
    }

    fun focusRows() {
        if (rows.isEmpty()) return
        focusArea = "rows"
        requestFocus("rows")
    }

    // ---------------------------------------------------------------------------
    // Navegacao superior
    // ---------------------------------------------------------------------------
    fun onNavFocused(app: AppState, idx: Int) {
        focusArea = "nav"
        navIdx = idx
        navJob?.cancel()
        if (idx <= 2) {
            navJob = app.scope.launch {
                delay(400)
                val key = menuKeys[idx]
                if (focusArea == "nav" && navIdx == idx && key != category) loadCategory(app, key)
            }
        }
    }

    /** Foco saiu da barra (filtros/linhas): cancela a troca de aba pendente. */
    fun cancelNavJob() {
        navJob?.cancel()
        navJob = null
    }

    fun onNavSelected(app: AppState, idx: Int) {
        when (val key = menuKeys[idx]) {
            "search" -> app.push(Screen.Search(SearchModel()))
            "addons" -> app.push(Screen.Addons())
            "settings" -> app.push(Screen.SettingsScreen())
            else -> {
                if (key != category) {
                    loadCategory(app, key)
                } else if (rows.isNotEmpty()) {
                    focusRows()
                } else if (!loading) {
                    if (app.addons.isEmpty() || AddonStore.anyFailed(app.addons)) {
                        app.loadAddons()
                    } else {
                        loadCategory(app, key)
                    }
                }
            }
        }
    }

    fun moveRow(delta: Int) {
        val n = curRow + delta
        if (n < 0 || n >= rows.size) return
        curRow = n
        focusRows()
        val row = rows[n]
        row.items.getOrNull(row.focusedIdx)?.let {
            pendingHero = null
            hero = it
        }
    }

    // ---------------------------------------------------------------------------
    // Filtros (Filmes e Series): catalogo e genero/ano
    // ---------------------------------------------------------------------------
    fun genreChoices(kind: String): List<String> {
        val st = fState[kind] ?: return emptyList()
        if (st.cat >= 0 && st.cat < catOptions.size) return catOptions[st.cat].genres
        val out = LinkedHashSet<String>()
        for (c in catOptions) if (!c.genreReq) out.addAll(c.genres)
        return out.toList()
    }

    private fun updateFilterBar(app: AppState, kind: String) {
        val t = app.t
        if (kind.isEmpty()) {
            chips = emptyList()
            catOptions = emptyList()
            return
        }
        catOptions = AddonStore.listFilterCatalogs(app.addons, kind)
        val st = fState.getValue(kind)
        if (st.cat >= catOptions.size) {
            st.cat = -1
            st.genre = ""
        }
        if (st.cat >= 0) {
            val c = catOptions[st.cat]
            if (!c.hasGenre) st.genre = ""
            if (c.genreReq && st.genre.isEmpty() && c.genres.isNotEmpty()) st.genre = c.genres[0]
        }
        if (catOptions.isEmpty()) {
            chips = emptyList()
            return
        }
        var catName = t("filter_all")
        var gLabel = t("filter_genre")
        if (st.cat >= 0) {
            catName = catOptions[st.cat].name
            if (catOptions[st.cat].id == "year") gLabel = t("filter_year")
        }
        val genreName = if (st.genre.isNotEmpty()) t.genre(st.genre) else t("filter_all")
        val list = ArrayList<String>()
        list.add(t("filter_catalog") + ": " + catName)
        if (genreChoices(kind).isNotEmpty()) list.add("$gLabel: $genreName")
        chips = list
        if (chipIdx >= list.size) chipIdx = 0
    }

    fun openPicker(app: AppState, mode: String) {
        val t = app.t
        val kind = currentKind()
        val st = fState[kind] ?: return
        val labels = ArrayList<String>()
        val values = ArrayList<Any>()
        var current = 0
        val title: String
        if (mode == "cat") {
            title = t("filter_pick_catalog")
            labels.add(t("filter_all"))
            values.add(-1)
            for (i in catOptions.indices) {
                labels.add(catOptions[i].name)
                values.add(i)
                if (i == st.cat) current = i + 1
            }
        } else {
            var isYear = false
            var required = false
            if (st.cat >= 0) {
                isYear = catOptions[st.cat].id == "year"
                required = catOptions[st.cat].genreReq
            }
            title = if (isYear) t("filter_pick_year") else t("filter_pick_genre")
            if (!required) {
                labels.add(t("filter_all"))
                values.add("")
            }
            for (g in genreChoices(kind)) {
                labels.add(t.genre(g))
                values.add(g)
                if (g == st.genre) current = values.size - 1
            }
        }
        cancelNavJob()
        picker = PickerState(mode, title, labels, values, current)
    }

    fun onPickerSelected(app: AppState, idx: Int) {
        val p = picker ?: return
        if (idx < 0 || idx >= p.values.size) return
        val st = fState[currentKind()] ?: return
        if (p.mode == "cat") {
            st.cat = p.values[idx] as Int
            st.genre = ""
        } else {
            st.genre = p.values[idx] as String
        }
        focusChipNow()
        picker = null
        loadCategory(app, category)
        closePickerFocus()
    }

    fun closePicker() {
        focusChipNow()
        picker = null
        closePickerFocus()
    }

    private fun focusChipNow() {
        if (chips.isEmpty()) return
        focusArea = "filters"
        cancelNavJob()
        try {
            chipReq[chipIdx.coerceIn(0, chips.size - 1)].requestFocus()
        } catch (e: Exception) {
        }
    }

    private fun closePickerFocus() {
        if (chips.isNotEmpty()) focusFilters() else focusNav()
    }

    // ---------------------------------------------------------------------------
    // Carregamento dos catalogos
    // ---------------------------------------------------------------------------
    fun loadCategory(app: AppState, cat: String) {
        loadJob?.cancel()
        val t = app.t
        category = cat
        loadedRev = app.addonsRev
        loadedHist = app.historyRev
        loadedLang = app.settings.lang
        loading = true
        singleMode = false
        val wasRows = focusArea == "rows"
        rows = emptyList()
        curRow = 0
        hero = null
        pendingHero = null

        val kind = currentKind()
        updateFilterBar(app, kind)
        if (wasRows || (focusArea == "filters" && chips.isEmpty())) focusNav()

        val catalogs = ArrayList<CatalogRef>()
        if (kind.isEmpty()) {
            catalogs.addAll(AddonStore.listCatalogs(app.addons, "", false, 10, t))
        } else {
            val st = fState.getValue(kind)
            if (st.cat >= 0) {
                val c = catOptions[st.cat]
                singleMode = true
                var title = c.name
                var extra = ""
                if (st.genre.isNotEmpty()) {
                    title = title + " - " + t.genre(st.genre)
                    if (c.hasGenre) extra = "genre=" + urlEncode(st.genre)
                }
                catalogs.add(CatalogRef(c.base, c.kind, c.id, title, c.hasGenre, extra))
            } else {
                for (c in AddonStore.listCatalogs(app.addons, kind, false, 10, t)) {
                    if (st.genre.isNotEmpty()) {
                        if (c.hasGenre) {
                            catalogs.add(c.copy(extra = "genre=" + urlEncode(st.genre), title = c.title + " - " + t.genre(st.genre)))
                        }
                    } else {
                        catalogs.add(c)
                    }
                }
            }
        }

        status = t("loading")
        loadJob = app.scope.launch {
            val results = catalogs.map { c ->
                async {
                    var url = c.base + "/catalog/" + urlEncode(c.kind) + "/" + urlEncode(c.id)
                    if (c.extra.isNotEmpty()) url += "/" + c.extra
                    Net.getJson("$url.json")
                }
            }.awaitAll()
            finishLoad(app, catalogs, results)
        }
    }

    private fun finishLoad(app: AppState, catalogs: List<CatalogRef>, results: List<JsonResult>) {
        val t = app.t
        loading = false
        val defs = ArrayList<RowDef>()
        var first: Info? = null

        // Linha "Continuar assistindo" (so na tela inicial)
        if (category == "home") {
            val seen = HashSet<String>()
            val items = app.store.loadHistory().filter { it.id.isNotEmpty() && seen.add(it.id) }
            if (items.isNotEmpty()) {
                defs.add(RowDef(t("continue_watching"), items))
                first = items[0]
            }
        }

        // Linhas dos catalogos, na ordem dos addons
        val perRow = if (singleMode) 8 else 30
        for (i in catalogs.indices) {
            val res = results[i]
            if (!res.ok) continue
            val metas = res.data?.optJSONArray("metas").objects()
            if (metas.isEmpty()) continue
            val c = catalogs[i]
            val chunks = ArrayList<Pair<String, MutableList<Info>>>()
            var n = 0
            for (meta in metas) {
                val info = Info.fromMeta(meta, c.base, c.kind)
                if (info.id.isEmpty() || info.name.isEmpty()) continue
                if (chunks.isEmpty() || n % perRow == 0) {
                    if (chunks.isNotEmpty() && !singleMode) break
                    chunks.add(Pair(if (n == 0) c.title else "", ArrayList()))
                }
                chunks.last().second.add(info)
                if (first == null) first = info
                n++
                if (n >= 96) break
            }
            for ((title, items) in chunks) defs.add(RowDef(title, items))
        }

        rows = defs
        curRow = 0
        if (defs.isNotEmpty()) {
            status = ""
            first?.let { hero = it }
        } else {
            status = emptyMessage(app)
            if (focusArea == "rows") focusNav()
        }
    }

    private fun emptyMessage(app: AppState): String {
        val t = app.t
        val addons = app.addons
        if (addons.isEmpty()) return t("empty_no_addons")
        if (AddonStore.countEnabled(addons) == 0) return t("empty_all_disabled")
        if (AddonStore.anyFailed(addons)) return t("empty_failed")
        return t("empty_nocatalog")
    }
}

// =============================================================================
// Tela
// =============================================================================
@Composable
fun HomeScreen(app: AppState, m: HomeModel) {
    val t = app.t

    // Recarrega quando addons, historico ou idioma mudam (como o focusView do Roku)
    LaunchedEffect(app.addonsRev, app.historyRev, app.settings.lang) {
        if (m.isStale(app)) m.loadCategory(app, m.category)
    }

    // Devolve o foco ao voltar para a tela ou ao fechar um dialogo
    LaunchedEffect(app.focusTick) {
        if (m.picker != null) return@LaunchedEffect
        when {
            m.focusArea == "rows" && m.rows.isNotEmpty() -> m.focusRows()
            m.focusArea == "filters" && m.chips.isNotEmpty() -> m.focusFilters()
            else -> m.focusNav()
        }
    }

    // Executa os pedidos de foco
    LaunchedEffect(m.focusCmd) {
        if (m.focusCmd == 0) return@LaunchedEffect
        when (m.focusTarget) {
            "nav" -> m.navReq[m.navIdx.coerceIn(0, 5)].focusSoon()
            "filters" -> if (m.chips.isNotEmpty()) m.chipReq[m.chipIdx.coerceIn(0, m.chips.size - 1)].focusSoon()
            "rows" -> {
                val row = m.rows.getOrNull(m.curRow) ?: return@LaunchedEffect
                val visible = row.listState.layoutInfo.visibleItemsInfo.any { it.index == row.focusedIdx }
                if (!visible) row.listState.scrollToItem(row.focusedIdx)
                row.requester.focusSoon()
            }
        }
    }

    // Banner com atraso, para nao trocar a imagem a cada tecla
    LaunchedEffect(m.pendingHero) {
        val p = m.pendingHero ?: return@LaunchedEffect
        delay(300)
        m.hero = p
    }

    BackHandler(enabled = m.picker == null && (m.focusArea == "rows" || m.focusArea == "filters")) {
        m.focusNav()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (!e.isDown() || m.picker != null) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> {
                        when (m.focusArea) {
                            "rows" -> m.moveRow(1)
                            "nav" -> if (m.chips.isNotEmpty()) m.focusFilters() else m.focusRows()
                            "filters" -> m.focusRows()
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        when (m.focusArea) {
                            "rows" -> if (m.curRow > 0) m.moveRow(-1) else if (m.chips.isNotEmpty()) m.focusFilters() else m.focusNav()
                            "filters" -> m.focusNav()
                        }
                        true
                    }
                    Key.DirectionLeft -> when (m.focusArea) {
                        "nav" -> m.navIdx == 0
                        "filters" -> m.chipIdx == 0
                        "rows" -> m.rows.getOrNull(m.curRow)?.focusedIdx == 0
                        else -> false
                    }
                    Key.DirectionRight -> when (m.focusArea) {
                        "nav" -> m.navIdx == 5
                        "filters" -> m.chipIdx >= m.chips.size - 1
                        "rows" -> m.rows.getOrNull(m.curRow)?.let { it.focusedIdx >= it.items.size - 1 } ?: false
                        else -> false
                    }
                    else -> false
                }
            }
    ) {
        Backdrop(m.hero?.let { it.background.ifEmpty { it.poster } } ?: "")

        // Marca e navegacao
        KLabel("KINORA", 80, 38, 400, 50, 30, weight = W.Bold, color = K.White)
        val navLabels = listOf(t("nav_home"), t("nav_movies"), t("nav_series"), t("nav_search"), t("nav_addons"), t("nav_settings"))
        Row(Modifier.at(600, 36), horizontalArrangement = Arrangement.spacedBy(d(8))) {
            navLabels.forEachIndexed { i, label ->
                NavPill(
                    label,
                    Modifier.focusRequester(m.navReq[i]),
                    onFocus = { m.onNavFocused(app, i) },
                    onClick = { m.onNavSelected(app, i) },
                )
            }
        }
        val catIdx = when (m.category) {
            "movies" -> 1
            "series" -> 2
            else -> 0
        }
        Box(Modifier.at(600 + catIdx * 178 + 35, 98).box(100, 4).background(K.White))

        // Filtros
        if (m.chips.isNotEmpty()) {
            Row(Modifier.at(1120, 112), horizontalArrangement = Arrangement.spacedBy(d(12))) {
                m.chips.forEachIndexed { i, label ->
                    Chip(
                        label, 360, 52,
                        modifier = Modifier.focusRequester(m.chipReq[i]),
                        onFocus = {
                            m.focusArea = "filters"
                            m.chipIdx = i
                            m.cancelNavJob()
                        },
                        onClick = { m.openPicker(app, if (i == 0) "cat" else "genre") },
                    )
                }
            }
        }

        // Banner do titulo em foco
        m.hero?.let { h ->
            KLabel(h.name, 80, 130, 1000, 150, 46, weight = W.Bold, color = K.White, maxLines = 2, vAlign = Alignment.Bottom)
            KLabel(metaLine(t, h, true), 80, 290, 1000, 40, 22, weight = W.Medium, color = K.Meta)
            KLabel(h.description, 80, 336, 860, 110, 21, color = K.Desc, maxLines = 3)
        }

        // Linhas
        var y = 548
        for (i in m.curRow until m.rows.size) {
            val row = m.rows[i]
            if (i > m.curRow) {
                val prevTitled = row.title.isNotEmpty()
                y += 240 + if (prevTitled) 90 else 24
            }
            if (y >= 1080) break
            key(row) {
                HomeRowView(app, m, row, i, y)
            }
        }

        if (m.rows.isEmpty() && m.status.isNotEmpty()) {
            KLabel(m.status, 400, 520, 1100, 180, 28, weight = W.Medium, color = K.Meta, maxLines = 4, align = TextAlign.Center)
        }

        m.picker?.let { p -> key(p) { FilterPicker(app, m, p) } }
    }
}

@Composable
private fun NavPill(label: String, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .box(170, 56)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvClick(null, onClick)
            .clip(RoundedCornerShape(d(28)))
            .background(if (focused) Color(0x33FFFFFF) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        KText(label, 24, weight = W.Medium, color = K.Text, align = TextAlign.Center)
    }
}

@Composable
private fun HomeRowView(app: AppState, m: HomeModel, row: RowDef, rowIndex: Int, y: Int) {
    if (row.title.isNotEmpty()) {
        KLabel(row.title, 70, y - 50, 1700, 42, 26, weight = W.Medium, color = K.Text)
    }
    LazyRow(
        state = row.listState,
        modifier = Modifier.at(56, y).width(d(1864)).height(d(240)),
        contentPadding = PaddingValues(start = d(14), end = d(60)),
        horizontalArrangement = Arrangement.spacedBy(d(22)),
    ) {
        itemsIndexed(row.items) { idx, info ->
            PosterCard(
                url = info.poster,
                w = 160,
                h = 240,
                modifier = if (idx == row.focusedIdx) Modifier.focusRequester(row.requester) else Modifier,
                progress = progressOf(info),
                onFocus = {
                    row.focusedIdx = idx
                    m.focusArea = "rows"
                    m.cancelNavJob()
                    if (m.curRow != rowIndex) m.curRow = rowIndex
                    m.pendingHero = info
                },
                onClick = { app.openDetails(info) },
            )
        }
    }
}

@Composable
private fun FilterPicker(app: AppState, m: HomeModel, p: PickerState) {
    val listState = rememberLazyListState()
    val currentReq = remember { FocusRequester() }

    BackHandler { m.closePicker() }

    LaunchedEffect(p) {
        listState.scrollToItem((p.current - 4).coerceAtLeast(0))
        currentReq.focusSoon()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Scrim)
            .onPreviewKeyEvent { e ->
                e.isDown() && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)
            }
    ) {
        Box(Modifier.at(560, 90).box(800, 900).clip(RoundedCornerShape(d(24))).background(K.Panel))
        KLabel(p.title, 610, 116, 700, 60, 32, weight = W.Bold, color = K.White)
        LazyColumn(
            state = listState,
            modifier = Modifier.at(600, 200).box(720, 760),
            contentPadding = PaddingValues(vertical = d(4)),
        ) {
            itemsIndexed(p.labels) { idx, label ->
                ListPill(
                    text = label,
                    w = 720,
                    h = 60,
                    modifier = (if (idx == p.current) Modifier.focusRequester(currentReq) else Modifier)
                        .onPreviewKeyEvent { e ->
                            e.isDown() && ((e.key == Key.DirectionUp && idx == 0) ||
                                (e.key == Key.DirectionDown && idx == p.labels.size - 1))
                        },
                    textOffset = 24,
                    size = 24,
                    onClick = { m.onPickerSelected(app, idx) },
                )
            }
        }
    }
}
