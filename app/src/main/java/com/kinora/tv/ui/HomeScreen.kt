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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.kinora.tv.R
import com.kinora.tv.data.str
import kotlinx.coroutines.withTimeoutOrNull
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.CatalogRef
import com.kinora.tv.data.FilterCatalog
import com.kinora.tv.data.Info
import com.kinora.tv.data.JsonResult
import com.kinora.tv.data.Net
import com.kinora.tv.data.Strings
import com.kinora.tv.data.objects
import com.kinora.tv.data.urlEncode
import com.kinora.tv.data.accentFor
import com.kinora.tv.data.filterAdultGenres
import com.kinora.tv.data.isAdultMeta
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    if (info.cert.isNotEmpty()) parts.add(info.cert)
    if (withEpisode && (info.season > 0 || info.episode > 0)) parts.add(t.epCode(info.season, info.episode))
    if (info.year.isNotEmpty()) parts.add(info.year)
    if (info.rating.isNotEmpty()) parts.add("IMDb " + info.rating)
    if (info.genres.isNotEmpty()) parts.add(info.genres)
    return parts.joinToString("  •  ")
}

class HomeModel {
    val menuKeys = listOf("home", "movies", "series", "search", "addons", "settings", "profiles")

    var category by mutableStateOf("home")
    var rows by mutableStateOf<List<RowDef>>(emptyList())
    var curRow by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var status by mutableStateOf("")
    var hero by mutableStateOf<Info?>(null)
    var heroDesc by mutableStateOf("")
    var heroAnimate by mutableStateOf(false)
    var pendingHero by mutableStateOf<Info?>(null)

    // Carrossel de destaques
    var featured by mutableStateOf<List<Info>>(emptyList())
    var fIdx by mutableIntStateOf(0)
    val heroReq = FocusRequester()
    val heroReq2 = FocusRequester()
    var heroIdx by mutableIntStateOf(0)
    /** a imagem de fundo do titulo em foco falta (historico/lista): buscar no meta */
    private var heroBgNeeded = false
    var heroBg by mutableStateOf("")
    /** muda a cada titulo novo no banner (reinicia o zoom lento) */
    var heroSeq by mutableIntStateOf(0)
    var hideAdult = true
    /** catalogos guardados em memoria por 5 minutos */
    private val cache = HashMap<String, Pair<Long, List<org.json.JSONObject>>>()
    private val metaCache = HashMap<String, Pair<String, String>>()
    private val metaPending = HashSet<String>()
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
    var loadedFav = -1
    var loadedProfile = ""
    private var loadJob: Job? = null
    private var navJob: Job? = null

    val navReq = List(7) { FocusRequester() }
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
        if (category == "home" && app.favRev != loadedFav) return true
        if (app.settings.lang != loadedLang) return true
        if (app.profile.id != loadedProfile) return true
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

    fun focusHero(app: AppState) {
        if (featured.isEmpty()) return
        focusArea = "hero"
        heroIdx = 0
        cancelNavJob()
        requestFocus("hero")
        onTopFocus(app)
    }

    fun focusRows() {
        if (rows.isEmpty()) return
        focusArea = "rows"
        requestFocus("rows")
    }

    /** Foco voltou para a barra/filtros/botao: retoma o carrossel de destaques. */
    fun onTopFocus(app: AppState) {
        featured.getOrNull(fIdx)?.let { setHero(app, it, true) }
    }

    // ---------------------------------------------------------------------------
    // Banner (hero)
    // ---------------------------------------------------------------------------
    fun setHero(app: AppState, info: Info, animate: Boolean) {
        pendingHero = null
        heroAnimate = animate
        if (hero === info) return
        hero = info
        heroSeq += 1
        heroDesc = info.description
        heroBgNeeded = info.background.isEmpty()
        heroBg = info.background.ifEmpty { info.poster }
        if (heroIdx == 1 && !heroPlayable()) heroIdx = 0
        // Sinopse ausente (ou cortada, no caso do historico): busca o meta completo
        if (info.description.isEmpty() || info.videoId.isNotEmpty() || heroBgNeeded) requestHeroMeta(app, info)
    }

    /** "Assistir/Continuar" aparece para filmes e para o que estava sendo assistido. */
    fun heroPlayable(): Boolean {
        val h = hero ?: return false
        return h.videoId.isNotEmpty() || h.kind == "movie"
    }

    /** Copia do destaque para "Assistir/Continuar": abre a ficha e ja procura a fonte. */
    fun heroPlayInfo(): Info? {
        val info = featured.getOrNull(fIdx) ?: return null
        return if (info.videoId.isEmpty()) info.copy(videoId = info.id, season = 0, episode = 0) else info
    }

    private fun applyHeroMeta(app: AppState, desc: String, bg: String) {
        heroDesc = desc.ifEmpty { app.t("no_synopsis") }
        if (heroBgNeeded && bg.isNotEmpty()) {
            heroBgNeeded = false
            heroAnimate = true
            heroBg = bg
        }
    }

    private fun requestHeroMeta(app: AppState, info: Info) {
        val id = info.id
        if (id.isEmpty()) return
        metaCache[id]?.let {
            applyHeroMeta(app, it.first, it.second)
            return
        }
        if (id in metaPending) return
        val base = AddonStore.findMetaBase(app.addons, info.kind, id, info.addon)
        if (base.isEmpty()) return
        metaPending.add(id)
        app.scope.launch {
            val res = Net.getJson(base + "/meta/" + urlEncode(info.kind) + "/" + urlEncode(id) + ".json")
            metaPending.remove(id)
            val meta = if (res.ok) res.data?.optJSONObject("meta") else null
            val d = meta?.str("description") ?: ""
            val bg = meta?.str("background") ?: ""
            metaCache[id] = Pair(d, bg)
            if (hero?.id == id) applyHeroMeta(app, d, bg)
        }
    }

    /** Avanca o carrossel (a cada 8 s, so com o foco fora das linhas). */
    fun nextFeatured(app: AppState) {
        if (!app.settings.carousel) return
        if (focusArea == "rows" || picker != null || featured.size < 2) return
        fIdx = (fIdx + 1) % featured.size
        setHero(app, featured[fIdx], true)
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
            "addons" -> app.onMenuAction("addons")
            "settings" -> app.onMenuAction("settings")
            "profiles" -> app.onMenuAction("profiles")
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

    fun moveRow(app: AppState, delta: Int) {
        val n = curRow + delta
        if (n < 0 || n >= rows.size) return
        curRow = n
        focusRows()
        val row = rows[n]
        row.items.getOrNull(row.focusedIdx)?.let { setHero(app, it, false) }
    }

    /** Entrou nas linhas: o banner acompanha o poster em foco. */
    fun enterRows(app: AppState) {
        if (rows.isEmpty()) return
        focusRows()
        val row = rows[curRow]
        row.items.getOrNull(row.focusedIdx)?.let { setHero(app, it, false) }
    }

    // ---------------------------------------------------------------------------
    // Filtros (Filmes e Series): catalogo e genero/ano
    // ---------------------------------------------------------------------------
    fun genreChoices(kind: String): List<String> {
        val st = fState[kind] ?: return emptyList()
        if (st.cat >= 0 && st.cat < catOptions.size) {
            val g = catOptions[st.cat].genres
            return if (hideAdult) filterAdultGenres(g) else g
        }
        val out = LinkedHashSet<String>()
        for (c in catOptions) if (!c.genreReq) out.addAll(if (hideAdult) filterAdultGenres(c.genres) else c.genres)
        return out.toList()
    }

    private fun updateFilterBar(app: AppState, kind: String) {
        val t = app.t
        hideAdult = app.hideAdult
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
        loadedFav = app.favRev
        loadedProfile = app.profile.id
        hideAdult = app.hideAdult
        loading = true
        singleMode = false
        val wasRows = focusArea == "rows"
        rows = emptyList()
        curRow = 0
        hero = null
        heroDesc = ""
        heroBg = ""
        pendingHero = null
        featured = emptyList()
        fIdx = 0
        if (focusArea == "hero") focusNav()

        val kind = currentKind()
        updateFilterBar(app, kind)
        if (wasRows || (focusArea == "filters" && chips.isEmpty())) focusNav()

        val catalogs = ArrayList<CatalogRef>()
        if (kind.isEmpty()) {
            catalogs.addAll(AddonStore.listCatalogs(app.addons, "", false, 6, t))
            addGenreRows(app, catalogs)
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
        val now = System.currentTimeMillis() / 1000
        loadJob = app.scope.launch {
            val results = catalogs.map { c ->
                async {
                    var url = c.base + "/catalog/" + urlEncode(c.kind) + "/" + urlEncode(c.id)
                    if (c.extra.isNotEmpty()) url += "/" + c.extra
                    url += ".json"
                    val cached = cache[url]
                    if (cached != null && now - cached.first < 300) {
                        cached.second
                    } else {
                        val res = Net.getJson(url)
                        val metas = if (res.ok) res.data?.optJSONArray("metas").objects() else emptyList()
                        if (metas.isNotEmpty()) cache[url] = Pair(now, metas)
                        metas
                    }
                }
            }.awaitAll()
            finishLoad(app, catalogs, results)
        }
    }

    /** Linhas extras de filmes por genero na tela inicial (Acao, Comedia, Terror, Ficcao...). */
    private fun addGenreRows(app: AppState, catalogs: MutableList<CatalogRef>) {
        val t = app.t
        val pick = AddonStore.listFilterCatalogs(app.addons, "movie")
            .firstOrNull { it.hasGenre && !it.genreReq && it.genres.isNotEmpty() } ?: return
        val chosen = ArrayList<String>()
        for (w in listOf("action", "comedy", "horror", "sci-fi", "drama")) {
            pick.genres.firstOrNull { it.lowercase() == w }?.let { chosen.add(it) }
            if (chosen.size >= 4) break
        }
        if (chosen.isEmpty()) chosen.addAll(pick.genres.take(4))
        for (g in chosen) {
            catalogs.add(
                CatalogRef(pick.base, "movie", pick.id, t.kindLabel("movie") + " - " + t.genre(g), true, "genre=" + urlEncode(g))
            )
        }
    }

    private fun finishLoad(app: AppState, catalogs: List<CatalogRef>, results: List<List<org.json.JSONObject>>) {
        val t = app.t
        loading = false
        val defs = ArrayList<RowDef>()
        var first: Info? = null

        var historyRow: RowDef? = null
        // Linha "Continuar assistindo" (so na tela inicial)
        if (category == "home") {
            val seen = HashSet<String>()
            val items = app.store.loadHistory().filter { it.id.isNotEmpty() && seen.add(it.id) }
            if (items.isNotEmpty()) {
                historyRow = RowDef(t("continue_watching"), items)
                defs.add(historyRow)
                first = items[0]
            }
        }

        // Linha "Minha lista" (so na tela inicial)
        var favRow: RowDef? = null
        if (category == "home") {
            val favs = app.store.loadFavorites()
            if (favs.isNotEmpty()) {
                favRow = RowDef(t("my_list"), favs)
                defs.add(favRow)
            }
        }

        // Linhas dos catalogos, na ordem dos addons
        val perRow = if (singleMode) 8 else 20
        for (i in catalogs.indices) {
            val metas = results[i]
            if (metas.isEmpty()) continue
            val c = catalogs[i]
            val chunks = ArrayList<Pair<String, MutableList<Info>>>()
            var n = 0
            for (meta in metas) {
                if (hideAdult && isAdultMeta(meta)) continue
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

        // Destaques do carrossel: ate 2 titulos (com imagem de fundo) de cada linha de catalogo
        val feats = ArrayList<Info>()
        for (d in defs) {
            if (d === historyRow || d === favRow) continue
            var taken = 0
            for (inf in d.items) {
                if (inf.background.isNotEmpty() && taken < 2 && feats.size < 8) {
                    feats.add(inf)
                    taken++
                }
            }
        }
        // o que voce estava assistindo vai primeiro (com o botao "Continuar")
        historyRow?.items?.firstOrNull()?.let {
            feats.add(0, it)
            if (feats.size > 8) feats.removeAt(feats.size - 1)
        }
        featured = feats
        fIdx = 0
        heroIdx = 0

        if (defs.isNotEmpty()) {
            status = ""
            val h = feats.firstOrNull() ?: first
            h?.let { setHero(app, it, false) }
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
    LaunchedEffect(app.addonsRev, app.historyRev, app.favRev, app.settings.lang, app.profile.id) {
        if (m.isStale(app)) m.loadCategory(app, m.category)
    }

    // Devolve o foco ao voltar para a tela ou ao fechar um dialogo
    LaunchedEffect(app.focusTick) {
        if (m.picker != null) return@LaunchedEffect
        when {
            m.focusArea == "rows" && m.rows.isNotEmpty() -> m.focusRows()
            m.focusArea == "filters" && m.chips.isNotEmpty() -> m.focusFilters()
            m.focusArea == "hero" && m.featured.isNotEmpty() -> m.focusHero(app)
            else -> m.focusNav()
        }
    }

    // Carrossel: troca o destaque a cada 8 s (pre-carrega a imagem para o fade ficar suave)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(8000)
            if (!app.settings.carousel || m.focusArea == "rows" || m.picker != null || m.featured.size < 2) continue
            val next = m.featured[(m.fIdx + 1) % m.featured.size]
            val url = next.background.ifEmpty { next.poster }
            if (url.isNotEmpty()) {
                try {
                    withTimeoutOrNull(4000) {
                        context.imageLoader.execute(ImageRequest.Builder(context).data(url).build())
                    }
                } catch (e: Exception) {
                }
            }
            m.nextFeatured(app)
        }
    }

    // Executa os pedidos de foco
    LaunchedEffect(m.focusCmd) {
        if (m.focusCmd == 0) return@LaunchedEffect
        when (m.focusTarget) {
            "nav" -> m.navReq[m.navIdx.coerceIn(0, 6)].focusSoon()
            "hero" -> if (m.heroIdx == 1 && m.heroPlayable()) m.heroReq2.focusSoon() else m.heroReq.focusSoon()
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
        if (m.focusArea == "rows") m.setHero(app, p, false)
    }

    BackHandler(enabled = m.picker == null && (m.focusArea == "rows" || m.focusArea == "filters" || m.focusArea == "hero")) {
        m.focusNav()
    }

    // Zoom lento no fundo do banner (efeito cinematografico; desligado por padrao)
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(m.heroSeq, app.settings.zoom) {
        zoom.snapTo(1f)
        if (app.settings.zoom) zoom.animateTo(1.07f, tween(durationMillis = 11000, easing = LinearEasing))
    }

    val hero = m.hero
    val accent = if (hero != null) accentFor(hero.id) else 0xFF8338EC

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (!e.isDown() || m.picker != null) return@onPreviewKeyEvent false
                val hasHero = m.featured.isNotEmpty()
                when (e.key) {
                    Key.MediaPlay, Key.MediaPlayPause -> {
                        // Play abre o destaque atual ja procurando a fonte (fora das linhas)
                        if (m.focusArea != "rows" && hasHero) {
                            m.heroPlayInfo()?.let { app.openDetails(it, autoplay = true) }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> {
                        when (m.focusArea) {
                            "rows" -> m.moveRow(app, 1)
                            "hero" -> m.enterRows(app)
                            "nav" -> if (m.chips.isNotEmpty()) m.focusFilters() else if (hasHero) m.focusHero(app) else m.enterRows(app)
                            "filters" -> if (hasHero) m.focusHero(app) else m.enterRows(app)
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        when (m.focusArea) {
                            "rows" -> if (m.curRow > 0) m.moveRow(app, -1)
                                else if (hasHero) m.focusHero(app)
                                else if (m.chips.isNotEmpty()) m.focusFilters()
                                else m.focusNav()
                            "hero" -> if (m.chips.isNotEmpty()) m.focusFilters() else m.focusNav()
                            "filters" -> m.focusNav()
                        }
                        true
                    }
                    Key.DirectionLeft -> when (m.focusArea) {
                        "hero" -> {
                            if (m.heroIdx == 1) {
                                m.heroIdx = 0
                                m.requestFocus("hero")
                            }
                            true
                        }
                        "nav" -> m.navIdx == 0
                        "filters" -> m.chipIdx == 0
                        "rows" -> m.rows.getOrNull(m.curRow)?.focusedIdx == 0
                        else -> false
                    }
                    Key.DirectionRight -> when (m.focusArea) {
                        "hero" -> {
                            if (m.heroIdx == 0 && m.heroPlayable()) {
                                m.heroIdx = 1
                                m.requestFocus("hero")
                            }
                            true
                        }
                        "nav" -> m.navIdx == 6
                        "filters" -> m.chipIdx >= m.chips.size - 1
                        "rows" -> m.rows.getOrNull(m.curRow)?.let { it.focusedIdx >= it.items.size - 1 } ?: false
                        else -> false
                    }
                    else -> false
                }
            }
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.value
            scaleY = zoom.value
            transformOrigin = TransformOrigin(0.5f, 0.28f)
        }) {
            FadingBackdrop(m.heroBg, m.heroAnimate)
        }
        // Luz ambiente colorida (muda com cada titulo)
        if (app.settings.ambient && hero != null) AccentGlow(accent, -260, 20, 1500, 1000, 0.5f)
        Box(Modifier.fillMaxSize().background(K.GradLeft))
        Box(Modifier.fillMaxSize().background(K.GradBottom))
        Box(Modifier.fillMaxWidth().height(d(200)).background(K.GradTop))

        // Marca e navegacao (barra de vidro; o ultimo item e o perfil)
        KLabel("KINORA", 80, 38, 400, 50, 30, weight = W.Bold, color = K.White)
        Box(
            Modifier.at(590, 28).box(1262, 72).clip(RoundedCornerShape(d(36)))
                .background(Color(0x2AFFFFFF))
                .border(d(2), Color(0x41FFFFFF), RoundedCornerShape(d(36)))
        )
        var pname = app.profile.name.ifEmpty { t("pf_default_name") }
        if (pname.length > 11) pname = pname.take(10) + "…"
        val navLabels = listOf(t("nav_home"), t("nav_movies"), t("nav_series"), t("nav_search"), t("nav_addons"), t("nav_settings"), pname)
        Row(Modifier.at(600, 36), horizontalArrangement = Arrangement.spacedBy(d(8))) {
            navLabels.forEachIndexed { i, label ->
                NavPill(
                    label,
                    Modifier.focusRequester(m.navReq[i]),
                    onFocus = {
                        val fromRows = m.focusArea == "rows"
                        m.onNavFocused(app, i)
                        if (fromRows) m.onTopFocus(app)
                    },
                    onClick = { m.onNavSelected(app, i) },
                )
            }
        }
        val catIdx = when (m.category) {
            "movies" -> 1
            "series" -> 2
            else -> 0
        }
        Box(Modifier.at(600 + catIdx * 178 + 40, 98).box(90, 6).clip(RoundedCornerShape(d(3))).background(Color(accent)))

        // Filtros
        if (m.chips.isNotEmpty()) {
            Row(Modifier.at(1120, 112), horizontalArrangement = Arrangement.spacedBy(d(12))) {
                m.chips.forEachIndexed { i, label ->
                    Chip(
                        label, 360, 52,
                        modifier = Modifier.focusRequester(m.chipReq[i]),
                        onFocus = {
                            val fromRows = m.focusArea == "rows"
                            m.focusArea = "filters"
                            m.chipIdx = i
                            m.cancelNavJob()
                            if (fromRows) m.onTopFocus(app)
                        },
                        onClick = { m.openPicker(app, if (i == 0) "cat" else "genre") },
                    )
                }
            }
        }

        // Banner do titulo em foco: titulo, selos (tipo, IMDb), ano/generos e sinopse
        hero?.let { h ->
            KLabel(h.name, 80, 84, 1000, 140, 46, weight = W.Bold, color = K.White, maxLines = 2, vAlign = Alignment.Bottom)
            Row(Modifier.at(80, 228).height(d(38)), verticalAlignment = Alignment.CenterVertically) {
                var k = t.kindSingular(h.kind)
                if (h.season > 0 || h.episode > 0) k = (k + " " + t.epCode(h.season, h.episode)).trim()
                if (k.isNotEmpty()) {
                    Badge(k.uppercase(), Color(0x3CFFFFFF), K.White, 38, 19)
                    Box(Modifier.width(d(14)))
                }
                if (h.rating.isNotEmpty()) {
                    Badge("IMDb " + h.rating, Color(0xFFF5C518), K.Bg, 38, 19)
                    Box(Modifier.width(d(14)))
                }
                KText(listOf(h.year, h.genres).filter { it.isNotEmpty() }.joinToString("  •  "), 22, weight = W.Medium, color = K.Meta)
            }
            KLabel(m.heroDesc, 80, 270, 860, 70, 21, color = K.Desc, maxLines = 2)
        }

        // Carrossel: botoes Detalhes e Assistir/Continuar e pontos (escondidos nas linhas)
        val carousel = m.focusArea != "rows"
        if (m.featured.isNotEmpty() && carousel) {
            IconPill(
                text = t("hero_details"),
                icon = R.drawable.ic_info,
                iconDark = R.drawable.ic_info_dark,
                w = 320, h = 72,
                modifier = Modifier.at(80, 354).focusRequester(m.heroReq),
                onFocus = {
                    m.focusArea = "hero"
                    m.heroIdx = 0
                    m.cancelNavJob()
                },
                onClick = { m.featured.getOrNull(m.fIdx)?.let { app.openDetails(it) } },
            )
            if (m.heroPlayable()) {
                IconPill(
                    text = if (hero?.videoId?.isNotEmpty() == true) t("hero_continue") else t("details_watch"),
                    icon = R.drawable.ic_play,
                    iconDark = R.drawable.ic_play_dark,
                    w = 320, h = 72,
                    modifier = Modifier.at(416, 354).focusRequester(m.heroReq2),
                    onFocus = {
                        m.focusArea = "hero"
                        m.heroIdx = 1
                        m.cancelNavJob()
                    },
                    onClick = { m.heroPlayInfo()?.let { app.openDetails(it, autoplay = true) } },
                )
            }
        }
        if (m.featured.size > 1 && carousel) {
            Row(Modifier.at(80, 446), horizontalArrangement = Arrangement.spacedBy(d(8))) {
                m.featured.forEachIndexed { i, _ ->
                    val active = i == m.fIdx
                    Box(
                        Modifier.box(if (active) 34 else 12, 6)
                            .clip(RoundedCornerShape(d(3)))
                            .background(if (active) K.White else Color(0xFF6A6A75))
                    )
                }
            }
        }

        // Linhas: a em foco fica em y=540; com titulo 90 px de respiro, sem titulo 24 px
        var y = 540
        for (i in m.curRow until m.rows.size) {
            val row = m.rows[i]
            if (i > m.curRow) {
                y += 300 + if (row.title.isNotEmpty()) 90 else 24
            }
            if (y >= 1080) break
            key(row) {
                HomeRowView(app, m, row, i, y)
            }
        }

        if (m.loading) Spinner(912, 620)
        if (m.rows.isEmpty() && m.status.isNotEmpty()) {
            KLabel(m.status, 400, 560, 1100, 180, 28, weight = W.Medium, color = K.Meta, maxLines = 4, align = TextAlign.Center)
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
        modifier = Modifier.at(56, y).width(d(1864)).height(d(300)),
        contentPadding = PaddingValues(start = d(14), end = d(60)),
        horizontalArrangement = Arrangement.spacedBy(d(22)),
    ) {
        itemsIndexed(row.items) { idx, info ->
            PosterCard(
                url = info.poster,
                w = 200,
                h = 300,
                modifier = if (idx == row.focusedIdx) Modifier.focusRequester(row.requester) else Modifier,
                progress = progressOf(info),
                onFocus = {
                    row.focusedIdx = idx
                    m.focusArea = "rows"
                    m.heroAnimate = false
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

/** Fundo do banner: troca direta, ou com fade de 0,9 s quando vem do carrossel. */
@Composable
private fun FadingBackdrop(url: String, animate: Boolean) {
    Box(Modifier.fillMaxSize().background(K.Bg)) {
        Crossfade(
            targetState = url,
            animationSpec = tween(durationMillis = if (animate) 900 else 0),
            label = "backdrop",
        ) { u ->
            if (u.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(u).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}
