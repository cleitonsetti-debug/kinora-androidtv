package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.Episode
import com.kinora.tv.data.Info
import com.kinora.tv.data.Net
import com.kinora.tv.data.PlayRequest
import com.kinora.tv.data.PlaylistItem
import com.kinora.tv.data.SourceInfo
import com.kinora.tv.data.Streams
import com.kinora.tv.data.accentFor
import com.kinora.tv.R
import com.kinora.tv.data.StreamOption
import com.kinora.tv.data.guessStreamFormat
import com.kinora.tv.data.int
import com.kinora.tv.data.joinList
import com.kinora.tv.data.objects
import com.kinora.tv.data.str
import com.kinora.tv.data.urlEncode
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONObject

// =============================================================================
// DetailsView: ficha do titulo, episodios (series/animes) e escolha da fonte.
// Se o item vem de "Continuar assistindo" (tem videoId), abre direto a escolha
// da fonte do episodio/filme que estava sendo assistido.
// =============================================================================

class PendingPlay(val videoId: String, val label: String, val season: Int, val episode: Int)

class DetailsModel(initial: Info, autoplay: Boolean = false) {
    var info by mutableStateOf(initial)
    var desc by mutableStateOf(initial.description)
    var mode by mutableStateOf("loading")
    /** onde esta o foco: buttons (Assistir / Minha lista), seasons ou episodes */
    var area by mutableStateOf("buttons")
    var btnIdx by mutableIntStateOf(0)
    var cast by mutableStateOf("")
    var inList by mutableStateOf(false)
    /** "Continuar/Assistir" do banner: toca sozinho a fonte do mesmo addon de antes */
    private var forceAuto = autoplay
    private val preferSrc = initial.src
    private var realCount = 0
    var watched by mutableStateOf<Set<String>>(emptySet())
    var progress by mutableStateOf<Map<String, Float>>(emptyMap())

    private var episodes: List<Episode> = emptyList()
    var seasons by mutableStateOf<List<Int>>(emptyList())
    var seasonIdx by mutableIntStateOf(0)
    var seasonEps by mutableStateOf<List<Episode>>(emptyList())
    var epFocused by mutableIntStateOf(0)
    var singleVideoId = ""

    var panelOpen by mutableStateOf(false)
    var panelTitle by mutableStateOf("")
    var streams by mutableStateOf<List<StreamOption>>(emptyList())
    var streamFocused by mutableIntStateOf(0)
    private var pendingPlay: PendingPlay? = null
    private var streamJob: Job? = null
    private var started = false

    val watchReq = FocusRequester()
    val listReq = FocusRequester()
    val seasonReq = FocusRequester()
    val episodeReq = FocusRequester()
    val streamReq = FocusRequester()
    val seasonState = LazyListState()
    val episodeState = LazyListState()
    val streamState = LazyListState()
    var focusCmd by mutableIntStateOf(0)

    fun applyFocus() {
        focusCmd += 1
    }

    private var tr: com.kinora.tv.data.Strings? = null
    private fun t0(k: String): String = tr?.invoke(k) ?: k

    fun start(app: AppState) {
        if (started) return
        started = true
        inList = app.store.isFavorite(info.id)
        tr = app.t
        refreshWatched(app)
        val it = info
        if (it.kind == "movie") {
            showSingle()
            loadMeta(app)
        } else {
            loadMeta(app)
        }

        // Veio de "Continuar assistindo": ja abre a escolha da fonte do episodio
        if (it.videoId.isNotEmpty()) {
            val label = if (it.season > 0 || it.episode > 0) app.t.epCode(it.season, it.episode) else ""
            startStreams(app, it.videoId, label, it.season, it.episode)
        }
    }

    private fun showSingle() {
        mode = "single"
        area = "buttons"
        applyFocus()
    }

    fun toggleList(app: AppState) {
        inList = app.store.toggleFavorite(info)
        app.favRev += 1
    }

    fun focusButton(idx: Int) {
        btnIdx = if (idx == 0 && mode != "single") 1 else idx
        area = "buttons"
        applyFocus()
    }

    private fun loadMeta(app: AppState) {
        val base = AddonStore.findMetaBase(app.addons, info.kind, info.id, info.addon)
        if (base.isEmpty()) {
            showSingle()
            if (info.description.isEmpty()) desc = app.t("no_synopsis")
            return
        }
        val url = base + "/meta/" + urlEncode(info.kind) + "/" + urlEncode(info.id) + ".json"
        app.scope.launch {
            val res = Net.getJson(url)
            episodes = emptyList()
            val meta = if (res.ok) res.data?.optJSONObject("meta") else null
            if (meta != null) {
                updateFromMeta(meta)
                parseEpisodes(meta)
            }
            if (episodes.isNotEmpty() && info.kind != "movie") showEpisodes() else if (mode != "single") showSingle()
            if (info.description.isEmpty()) desc = app.t("no_synopsis")
        }
    }

    /** Classificacao indicativa, quando o addon a fornece (o Cinemeta nao traz). */
    private fun metaCertification(meta: JSONObject): String {
        for (k in listOf("certification", "contentRating", "ageRating", "rated", "mpaa", "classification")) {
            val v = meta.str(k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun updateFromMeta(meta: JSONObject) {
        var i = info
        val cert = metaCertification(meta)
        if (cert.isNotEmpty()) i = i.copy(cert = cert)
        val rt = meta.str("runtime")
        if (rt.isNotEmpty()) i = i.copy(runtime = rt)
        val castText = joinList(meta.opt("cast"), 4)
        val directors = joinList(meta.opt("director"), 2)
        val extra = ArrayList<String>()
        if (castText.isNotEmpty()) extra.add(t0("details_cast") + ": " + castText)
        if (directors.isNotEmpty()) extra.add(t0("details_director") + ": " + directors)
        cast = extra.joinToString("     |     ")
        val d = meta.str("description")
        if (d.isNotEmpty()) {
            i = i.copy(description = d)
            desc = d
        }
        val ri = meta.str("releaseInfo")
        if (ri.isNotEmpty()) i = i.copy(year = ri)
        val g = joinList(meta.opt("genres"), 3)
        if (g.isNotEmpty()) i = i.copy(genres = g)
        val r = meta.str("imdbRating")
        if (r.isNotEmpty()) i = i.copy(rating = r)
        val bg = meta.str("background")
        if (bg.isNotEmpty()) i = i.copy(background = bg)
        info = i
    }

    private fun parseEpisodes(meta: JSONObject) {
        val list = ArrayList<Episode>()
        singleVideoId = ""
        for (v in meta.optJSONArray("videos").objects()) {
            val vid = v.str("id")
            if (vid.isEmpty()) continue
            var epn = v.int("episode")
            if (epn == 0) epn = v.int("number")
            var title = v.str("title")
            if (title.isEmpty()) title = v.str("name")
            var ov = v.str("overview")
            if (ov.isEmpty()) ov = v.str("description")
            list.add(Episode(vid, v.int("season"), epn, title, v.str("thumbnail"), ov))
        }
        // Um unico video (ex.: filme de anime): trata como titulo simples
        if (list.size == 1) {
            singleVideoId = list[0].id
            list.clear()
        }
        episodes = list
    }

    /** Episodios em ordem temporada/episodio, sem especiais (lista de reproducao do player). */
    private fun orderedEpisodes(): List<PlaylistItem> =
        episodes.filter { it.season > 0 }
            .sortedBy { it.season * 100000 + it.episode }
            .map { PlaylistItem(it.id, it.season, it.episode, it.title) }

    private fun findNextEpisode(videoId: String): PlaylistItem? {
        val ordered = orderedEpisodes()
        val found = ordered.indexOfFirst { it.id == videoId }
        if (found < 0) return null
        return ordered.getOrNull(found + 1)
    }

    // ---------------------------------------------------------------------------
    // Temporadas e episodios
    // ---------------------------------------------------------------------------
    private fun showEpisodes() {
        area = "seasons"
        episodes = episodes.sortedBy { it.episode }
        seasons = episodes.map { it.season }.distinct().sorted()
        val first = if (seasons.size > 1 && seasons[0] == 0) 1 else 0
        fillEpisodes(first)
        mode = "episodes"
        applyFocus()
    }

    /** Barra cheia = assistido; barra parcial = em andamento (nos cartoes de episodio). */
    fun refreshWatched(app: AppState) {
        watched = app.store.loadWatched()
        val p = HashMap<String, Float>()
        for (h in app.store.loadHistory()) {
            if (h.videoId.isNotEmpty() && h.duration > 0 && h.position > 0) p[h.videoId] = (h.position.toFloat() / h.duration).coerceAtMost(1f)
        }
        progress = p
    }

    fun epProgress(id: String): Float = if (id in watched) 1f else progress[id] ?: 0f

    fun fillEpisodes(idx: Int) {
        if (idx < 0 || idx >= seasons.size) return
        seasonIdx = idx
        val sn = seasons[idx]
        seasonEps = episodes.filter { it.season == sn }
        epFocused = 0
    }

    fun onEpisodeFocused(idx: Int) {
        epFocused = idx
        area = "episodes"
        val ov = seasonEps.getOrNull(idx)?.overview ?: ""
        desc = ov.ifEmpty { info.description }
    }

    fun onSeasonFocused(idx: Int) {
        area = "seasons"
        if (idx != seasonIdx) fillEpisodes(idx)
        desc = info.description
    }

    fun onEpisodeSelected(app: AppState, idx: Int) {
        val ep = seasonEps.getOrNull(idx) ?: return
        area = "episodes"
        startStreams(app, ep.id, app.t.epCode(ep.season, ep.episode), ep.season, ep.episode)
    }

    fun onWatchPressed(app: AppState) {
        val vid = singleVideoId.ifEmpty { info.id }
        startStreams(app, vid, "", 0, 0)
    }

    // ---------------------------------------------------------------------------
    // Fontes de video (addons com o recurso "stream")
    // ---------------------------------------------------------------------------
    private fun startStreams(app: AppState, videoId: String, epLabel: String, season: Int, episode: Int) {
        val t = app.t
        pendingPlay = PendingPlay(videoId, epLabel, season, episode)
        streamJob?.cancel()
        streams = emptyList()
        streamFocused = 0
        panelOpen = true
        panelTitle = t("panel_searching")

        val kind = info.kind
        streamJob = app.scope.launch {
            val res = Streams.fetch(app.addons, kind, videoId)
            val found = ArrayList(Streams.sortByQuality(res.found, app.settings.quality))
            val unsupported = res.unsupported

            realCount = found.size
            // Video de teste sempre no fim da lista, para validar o player
            found.add(
                StreamOption(
                    Streams.DEMO_URL, t("demo_title"), emptyMap(), demo = true,
                    addonName = t("demo_addon"), addonUrl = "",
                )
            )
            streams = found
            streamFocused = 0
            panelTitle = if (realCount > 0) {
                t.f("panel_choose", realCount.toString())
            } else {
                var s = t("panel_none")
                if (unsupported > 0) s += t.f("panel_unsupported", unsupported.toString())
                s
            }
            applyFocus()

            // "Continuar/Assistir" do banner ou ajuste "escolher a fonte automaticamente":
            // toca a fonte do mesmo addon de antes (se houver) ou a primeira
            if (realCount > 0 && (app.settings.autoPick || forceAuto)) {
                var pick = 0
                if (preferSrc.isNotEmpty()) {
                    val i = found.indexOfFirst { it.addonUrl == preferSrc }
                    if (i in 0 until realCount) pick = i
                }
                forceAuto = false
                playStreamAt(app, pick)
            }
        }
        applyFocus()
    }

    fun playStreamAt(app: AppState, idx: Int) {
        val s = streams.getOrNull(idx) ?: return
        val pp = pendingPlay ?: return
        var title = info.name
        if (pp.label.isNotEmpty()) title += "  " + pp.label
        app.play(
            PlayRequest(
                url = s.url,
                format = guessStreamFormat(s.url),
                title = title,
                headers = s.headers,
                videoId = pp.videoId,
                info = info,
                season = pp.season,
                episode = pp.episode,
                nextEp = findNextEpisode(pp.videoId),
                playlist = orderedEpisodes(),
                source = SourceInfo(s.addonName, s.addonUrl, s.title),
                subs = s.subs,
                // outras fontes reais da lista: o player tenta a proxima se esta falhar
                alts = streams.take(realCount),
                altIdx = if (idx < realCount) idx else -1,
            )
        )
    }

    fun closePanel() {
        streamJob?.cancel()
        // devolve o foco antes de esconder o painel (senao ele "cai" no primeiro item da tela)
        try {
            when {
                area == "buttons" && btnIdx == 0 && mode == "single" -> watchReq.requestFocus()
                area == "buttons" -> listReq.requestFocus()
                mode == "episodes" && area == "episodes" -> episodeReq.requestFocus()
                mode == "episodes" -> seasonReq.requestFocus()
            }
        } catch (e: Exception) {
        }
        panelOpen = false
        applyFocus()
    }
}

@Composable
fun DetailsScreen(app: AppState, m: DetailsModel) {
    val t = app.t

    LaunchedEffect(Unit) { m.start(app) }
    // voltou do player: atualiza as barras de assistido/em andamento
    LaunchedEffect(app.historyRev) { m.refreshWatched(app) }

    LaunchedEffect(m.focusCmd, app.focusTick) {
        when {
            m.panelOpen -> {
                if (m.streams.isNotEmpty()) {
                    val visible = m.streamState.layoutInfo.visibleItemsInfo.any { it.index == m.streamFocused }
                    if (!visible) m.streamState.scrollToItem(m.streamFocused)
                    m.streamReq.focusSoon()
                }
            }
            m.mode == "loading" -> {}
            m.area == "buttons" -> if (m.btnIdx == 0 && m.mode == "single") m.watchReq.focusSoon() else m.listReq.focusSoon()
            m.mode == "episodes" -> {
                if (m.area == "episodes" && m.seasonEps.isNotEmpty()) {
                    val visible = m.episodeState.layoutInfo.visibleItemsInfo.any { it.index == m.epFocused }
                    if (!visible) m.episodeState.scrollToItem(m.epFocused)
                    m.episodeReq.focusSoon()
                } else {
                    val visible = m.seasonState.layoutInfo.visibleItemsInfo.any { it.index == m.seasonIdx }
                    if (!visible) m.seasonState.scrollToItem(m.seasonIdx)
                    m.seasonReq.focusSoon()
                }
            }
        }
    }

    BackHandler(enabled = m.panelOpen) { m.closePanel() }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (m.panelOpen) {
                    // Enquanto as fontes carregam, a tela de baixo nao recebe teclas
                    return@onPreviewKeyEvent m.streams.isEmpty() && e.key != Key.Back
                }
                if (!e.isDown()) return@onPreviewKeyEvent false
                if (m.mode == "loading") {
                    return@onPreviewKeyEvent e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
                        e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                }
                // botoes Assistir / Minha lista
                if (m.area == "buttons") {
                    return@onPreviewKeyEvent when (e.key) {
                        Key.DirectionLeft -> {
                            if (m.btnIdx == 1 && m.mode == "single") m.focusButton(0)
                            true
                        }
                        Key.DirectionRight -> {
                            if (m.btnIdx == 0) m.focusButton(1)
                            true
                        }
                        Key.DirectionDown -> {
                            if (m.mode == "episodes") {
                                m.area = "seasons"
                                m.applyFocus()
                            }
                            true
                        }
                        Key.DirectionUp -> true
                        else -> false
                    }
                }
                when (e.key) {
                    Key.DirectionDown -> {
                        if (m.area == "seasons" && m.seasonEps.isNotEmpty()) {
                            m.area = "episodes"
                            m.applyFocus()
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (m.area == "episodes") {
                            m.area = "seasons"
                            m.applyFocus()
                        } else {
                            m.focusButton(1)
                        }
                        true
                    }
                    Key.DirectionLeft -> if (m.area == "episodes") m.epFocused == 0 else m.seasonIdx == 0
                    Key.DirectionRight -> if (m.area == "episodes") m.epFocused >= m.seasonEps.size - 1 else m.seasonIdx >= m.seasons.size - 1
                    else -> false
                }
            }
    ) {
        val info = m.info
        Backdrop(info.background.ifEmpty { info.poster })
        if (app.settings.ambient) AccentGlow(accentFor(info.id), -260, 120, 1500, 1000, 0.5f)

        KLabel(info.name, 90, 100, 1100, 160, 50, weight = W.Bold, color = K.White, maxLines = 2, vAlign = Alignment.Bottom)
        KLabel(detailsMeta(t, info), 90, 272, 1100, 40, 23, weight = W.Medium, color = K.Meta)
        KLabel(m.desc, 90, 322, 900, 100, 21, color = K.Desc, maxLines = 3)
        if (m.cast.isNotEmpty()) KLabel(m.cast, 90, 426, 1500, 38, 20, color = K.Hint)

        if (m.mode == "loading") {
            KLabel(t("loading_episodes"), 90, 560, 1100, 50, 28, weight = W.Medium, color = K.Meta)
        }
        if (m.mode == "single") {
            IconPill(
                text = t("details_watch"),
                icon = R.drawable.ic_play,
                iconDark = R.drawable.ic_play_dark,
                w = 290, h = 64, iconSize = 32, fontSize = 24,
                modifier = Modifier.at(90, 478).focusRequester(m.watchReq),
                onFocus = {
                    m.area = "buttons"
                    m.btnIdx = 0
                },
                onClick = { m.onWatchPressed(app) },
            )
        }
        if (m.mode == "single" || m.mode == "episodes") {
            IconPill(
                text = if (m.inList) t("details_in_list") else t("my_list"),
                icon = if (m.inList) R.drawable.ic_check else R.drawable.ic_plus,
                iconDark = if (m.inList) R.drawable.ic_check_dark else R.drawable.ic_plus_dark,
                w = 290, h = 64, iconSize = 32, fontSize = 24,
                modifier = Modifier.at(400, 478).focusRequester(m.listReq),
                onFocus = {
                    m.area = "buttons"
                    m.btnIdx = 1
                },
                onClick = { m.toggleList(app) },
            )
        }
        if (m.mode == "episodes") {
            LazyRow(
                state = m.seasonState,
                modifier = Modifier.at(80, 570).width(d(1840)).height(d(64)),
                contentPadding = PaddingValues(horizontal = d(10), vertical = d(6)),
                horizontalArrangement = Arrangement.spacedBy(d(14)),
            ) {
                itemsIndexed(m.seasons) { idx, sn ->
                    Chip(
                        text = if (sn == 0) t("specials") else t.f("season_n", sn.toString()),
                        w = 200,
                        h = 52,
                        selected = idx == m.seasonIdx,
                        modifier = if (idx == m.seasonIdx) Modifier.focusRequester(m.seasonReq) else Modifier,
                        onFocus = { m.onSeasonFocused(idx) },
                        onClick = {
                            m.area = "episodes"
                            m.applyFocus()
                        },
                    )
                }
            }
            LazyRow(
                state = m.episodeState,
                modifier = Modifier.at(76, 666).width(d(1844)).height(d(250)),
                contentPadding = PaddingValues(start = d(14), end = d(60)),
                horizontalArrangement = Arrangement.spacedBy(d(22)),
            ) {
                itemsIndexed(m.seasonEps, key = { _, ep -> ep.id }) { idx, ep ->
                    var label = "E" + ep.episode
                    if (ep.title.isNotEmpty()) label += "  " + ep.title
                    PosterCard(
                        url = ep.thumb,
                        w = 320,
                        h = 250,
                        title = label,
                        progress = m.epProgress(ep.id),
                        modifier = if (idx == m.epFocused) Modifier.focusRequester(m.episodeReq) else Modifier,
                        onFocus = { m.onEpisodeFocused(idx) },
                        onClick = { m.onEpisodeSelected(app, idx) },
                    )
                }
            }
        }

        if (m.panelOpen) {
            StreamPanel(app, m)
        }
    }
}

/** Linha de metadados da ficha: tipo, classificacao, ano, duracao, IMDb e generos. */
private fun detailsMeta(t: com.kinora.tv.data.Strings, info: Info): String {
    val parts = ArrayList<String>()
    val k = t.kindSingular(info.kind)
    if (k.isNotEmpty()) parts.add(k)
    if (info.cert.isNotEmpty()) parts.add(info.cert)
    if (info.year.isNotEmpty()) parts.add(info.year)
    if (info.runtime.isNotEmpty()) parts.add(info.runtime)
    if (info.rating.isNotEmpty()) parts.add("IMDb " + info.rating)
    if (info.genres.isNotEmpty()) parts.add(info.genres)
    return parts.joinToString("  •  ")
}

@Composable
private fun StreamPanel(app: AppState, m: DetailsModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(K.Scrim)
            .onPreviewKeyEvent { e ->
                e.isDown() && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)
            }
    ) {
        Box(Modifier.at(360, 90).box(1200, 900).clip(RoundedCornerShape(d(24))).background(K.Panel))
        KLabel(m.panelTitle, 410, 118, 1100, 90, 28, weight = W.Bold, color = K.White, maxLines = 2)
        LazyColumn(
            state = m.streamState,
            modifier = Modifier.at(400, 220).box(1120, 740),
            contentPadding = PaddingValues(vertical = d(4)),
        ) {
            itemsIndexed(m.streams) { idx, s ->
                ListPill(
                    text = s.title,
                    w = 1120,
                    h = 64,
                    textOffset = 24,
                    size = 22,
                    modifier = (if (idx == m.streamFocused) Modifier.focusRequester(m.streamReq) else Modifier)
                        .onPreviewKeyEvent { e ->
                            e.isDown() && ((e.key == Key.DirectionUp && idx == 0) ||
                                (e.key == Key.DirectionDown && idx == m.streams.size - 1))
                        },
                    onFocus = { m.streamFocused = idx },
                    onClick = { m.playStreamAt(app, idx) },
                )
            }
        }
    }
}
