package com.kinora.tv.ui

import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kinora.tv.R
import com.kinora.tv.data.AddonStore
import com.kinora.tv.data.Info
import com.kinora.tv.data.PlayRequest
import com.kinora.tv.data.PlaylistItem
import com.kinora.tv.data.SourceInfo
import com.kinora.tv.data.Streams
import com.kinora.tv.data.SubTrack
import com.kinora.tv.data.formatTime
import com.kinora.tv.data.guessStreamFormat
import com.kinora.tv.data.langMatches
import com.kinora.tv.data.langName
import com.kinora.tv.data.toLang2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =============================================================================
// Player proprio (igual ao Kinora v1.3 do Roku). A tela recebe as teclas do controle;
// o PlayerView do Media3 so desenha o video e as legendas (sem a interface nativa).
//
//  Controles escondidos:  Baixo/Cima/Menu = mostrar controles   OK = pausar/continuar
//                         Esquerda/Direita = abre a barra de tempo   Voltar = sair
//  Controles visiveis:    Esquerda/Direita escolhem o botao, OK ativa
//                         (-10 s, pausar, +10 s, ir para, legendas, audio, proximo ep., fonte)
//                         Cima = barra de tempo; Voltar = esconder
//  Barra de tempo: Esquerda/Direita movem o ponto (passos que aceleram), OK confirma;
//                  parado por 3 s confirma sozinho; Voltar cancela
//  Tambem: Play = pausar, retroceder/avancar = -/+30 s
// =============================================================================

private class PlayerButtonDef(val label: String, val icon: Int, val action: String)

private class PlayerModel(val app: AppState, request: PlayRequest, val player: ExoPlayer) {
    // Pedido atual (muda ao passar para o proximo episodio)
    var info: Info = request.info
    var videoId by mutableStateOf(request.videoId)
    var season by mutableIntStateOf(request.season)
    var episode by mutableIntStateOf(request.episode)
    var url = request.url
    var format = request.format
    var headers: Map<String, String> = request.headers
    var source: SourceInfo? = request.source
    var streamSubs: List<SubTrack> = request.subs
    val playlist: List<PlaylistItem> = request.playlist
    private val reqNextEp: PlaylistItem? = request.nextEp
    private val firstVideoId = request.videoId
    var nextEp: PlaylistItem? = null
    val preferAddonUrl = request.source?.addonUrl ?: ""

    // Estado da tela
    var loadingText by mutableStateOf("")
    var toast by mutableStateOf("")
    var centerIcon by mutableIntStateOf(0)      // 0 = nenhum, senao drawable
    var introVisible by mutableStateOf(false)
    var hintVisible by mutableStateOf(false)
    var ctrlVisible by mutableStateOf(false)
    var btnIdx by mutableIntStateOf(1)
    var panelOpen by mutableStateOf(false)
    var panelMode = "subs"
    var panelTitle by mutableStateOf("")
    var panelInfo by mutableStateOf("")
    var menuLabels by mutableStateOf<List<String>>(emptyList())
    var menuActions: List<() -> Unit> = emptyList()
    var menuIdx by mutableIntStateOf(0)
    val menuState = LazyListState()
    var nextVisible by mutableStateOf(false)
    var nextSeconds by mutableIntStateOf(0)
    var pos by mutableFloatStateOf(0f)        // segundos
    var dur by mutableFloatStateOf(0f)        // segundos
    var paused by mutableStateOf(false)

    // Barra de tempo
    var scrubbing by mutableStateOf(false)
    var scrubPos by mutableFloatStateOf(0f)
    private var scrubOrigin = "buttons"
    private var scrubMoved = false
    private var scrubIdx = 0
    private var scrubDir = 0
    private var scrubAt = 0L

    var switching = false
    var done = false
    private var finished = false
    private var introShown = false
    private var hintShown = false

    private var hideJob: Job? = null
    private var toastJob: Job? = null
    private var iconJob: Job? = null
    private var introJob: Job? = null
    private var hintJob: Job? = null
    private var scrubJob: Job? = null
    private var prepareJob: Job? = null

    val t get() = app.t

    init {
        computeNext()
    }

    private fun computeNext() {
        val idx = playlist.indexOfFirst { it.id == videoId }
        nextEp = if (idx >= 0) playlist.getOrNull(idx + 1) else if (videoId == firstVideoId) reqNextEp else null
    }

    fun displayTitle(): String {
        var s = info.name
        if (season > 0 || episode > 0) s += "  " + t.epCode(season, episode)
        return s
    }

    fun episodeTitle(): String = playlist.firstOrNull { it.id == videoId }?.title ?: ""

    // ---------------------------------------------------------------------------
    // Preparo: legendas dos addons -> inicia o video
    // ---------------------------------------------------------------------------
    fun beginPrepare() {
        loadingText = t("player_preparing")
        prepareJob?.cancel()
        prepareJob = app.scope.launch {
            val addonSubs = Streams.fetchSubs(app.addons, info.kind, videoId)
            loadingText = ""
            startPlayback(addonSubs)
        }
    }

    private fun subMime(u: String): String {
        val low = u.lowercase()
        return when {
            ".vtt" in low -> MimeTypes.TEXT_VTT
            ".ass" in low || ".ssa" in low -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun buildSubConfigs(addonSubs: List<SubTrack>): List<MediaItem.SubtitleConfiguration> {
        val all = streamSubs + addonSubs
        val pref = app.settings.subLang
        val ordered = if (pref != "off") {
            all.filter { langMatches(it.lang, pref) } + all.filter { !langMatches(it.lang, pref) }
        } else {
            all
        }
        return ordered.take(12).map { s ->
            var desc = langName(s.lang)
            if (s.label.isNotEmpty()) desc += " (" + s.label + ")"
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(subMime(s.url))
                .setLanguage(toLang2(s.lang))
                .setLabel(desc)
                .build()
        }
    }

    private fun startPlayback(addonSubs: List<SubTrack>) {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(displayTitle()).build())
            .setSubtitleConfigurations(buildSubConfigs(addonSubs))
        when (format) {
            "hls" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "dash" -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            "mkv" -> builder.setMimeType(MimeTypes.VIDEO_MATROSKA)
            "mp4" -> builder.setMimeType(MimeTypes.VIDEO_MP4)
        }

        // Idiomas preferidos dos Ajustes
        val st = app.settings
        val params = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, st.subLang == "off")
        if (st.subLang != "off") params.setPreferredTextLanguage(st.subLang)
        if (st.audioLang != "auto") params.setPreferredAudioLanguage(st.audioLang)
        player.trackSelectionParameters = params.build()

        val start = if (st.resume) app.store.getSavedPosition(videoId) else 0
        val item = builder.build()
        if (start > 0) player.setMediaItem(item, start * 1000L) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true

        switching = false
        finished = false
        introShown = false
        introVisible = false
        hideControls()
    }

    // ---------------------------------------------------------------------------
    // Estados do video
    // ---------------------------------------------------------------------------
    fun onPlaying() {
        loadingText = ""
        paused = false
        if (centerIcon != 0) {
            centerIcon = R.drawable.ic_play_big
            iconJob?.cancel()
            iconJob = app.scope.launch {
                delay(700)
                centerIcon = 0
            }
        }
        if (ctrlVisible) restartHide()
        if (!introShown) {
            introShown = true
            showIntro()
        }
    }

    fun onPaused() {
        paused = true
        iconJob?.cancel()
        centerIcon = R.drawable.ic_pause_big
        if (!switching && !panelOpen) showControls()
    }

    fun onBuffering() {
        if (!switching) loadingText = t("loading")
    }

    fun onEnded() {
        if (switching || done) return
        if (nextEp != null && app.settings.autoNext) {
            playNext()
        } else {
            finishEpisode()
            closePlayer()
        }
    }

    fun onError(error: PlaybackException) {
        if (switching || done) return
        saveNow()
        closePlayer()
        app.showMessage(
            t("play_error_title"),
            t.f2("play_error_body", error.message ?: error.errorCodeName, error.errorCode.toString()),
        )
    }

    /** Atualiza tempo/duracao e o cartao do proximo episodio (1x por segundo). */
    fun tick() {
        val d = player.duration
        dur = if (d > 0) d / 1000f else 0f
        pos = player.currentPosition / 1000f
        if (panelOpen || switching || ctrlVisible) return
        if (nextEp == null) return
        val st = player.playbackState
        if (st != Player.STATE_READY && st != Player.STATE_BUFFERING) return
        if (dur < 480) return
        val remain = dur - pos
        if (remain <= 25 && remain > 0) {
            nextSeconds = remain.toInt()
            nextVisible = true
        } else {
            nextVisible = false
        }
    }

    // ---------------------------------------------------------------------------
    // Ficha de abertura e dica
    // ---------------------------------------------------------------------------
    private fun showHintOnce() {
        if (hintShown) return
        hintShown = true
        hintVisible = true
        hintJob = app.scope.launch {
            delay(6000)
            hintVisible = false
        }
    }

    private fun showIntro() {
        if (!app.settings.intro || ctrlVisible) {
            showHintOnce()
            return
        }
        introVisible = true
        introJob?.cancel()
        introJob = app.scope.launch {
            delay(7000)
            introVisible = false
            showHintOnce()
        }
    }

    fun introMeta(): String {
        val parts = ArrayList<String>()
        if (season > 0 || episode > 0) {
            var e = t.epCode(season, episode)
            val et = episodeTitle()
            if (et.isNotEmpty()) e += "  $et"
            parts.add(e)
        }
        if (info.year.isNotEmpty()) parts.add(info.year)
        if (info.genres.isNotEmpty()) parts.add(info.genres)
        return parts.joinToString("  •  ")
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = app.scope.launch {
            delay(1400)
            toast = ""
        }
    }

    // ---------------------------------------------------------------------------
    // Controles
    // ---------------------------------------------------------------------------
    fun buttons(): List<PlayerButtonDef> {
        val list = ArrayList<PlayerButtonDef>()
        list.add(PlayerButtonDef("-10 s", R.drawable.ic_rewind, "back10"))
        if (paused) list.add(PlayerButtonDef(t("player_resume"), R.drawable.ic_play, "pause"))
        else list.add(PlayerButtonDef(t("player_pause"), R.drawable.ic_pause, "pause"))
        list.add(PlayerButtonDef("+10 s", R.drawable.ic_forward, "fwd10"))
        list.add(PlayerButtonDef(t("player_goto"), R.drawable.ic_scrub, "scrub"))
        list.add(PlayerButtonDef(t("player_subs"), R.drawable.ic_subs, "subs"))
        list.add(PlayerButtonDef(t("player_audio"), R.drawable.ic_audio, "audio"))
        if (nextEp != null) list.add(PlayerButtonDef(t("player_next_short"), R.drawable.ic_next, "next"))
        list.add(PlayerButtonDef(t("player_source"), R.drawable.ic_info, "info"))
        return list
    }

    fun ctlInfo(): String {
        if (season > 0 || episode > 0) {
            var s = t.epCode(season, episode)
            val et = episodeTitle()
            if (et.isNotEmpty()) s += "  $et"
            return s
        }
        return listOf(info.year, info.genres).filter { it.isNotEmpty() }.joinToString("  •  ")
    }

    fun showControls() {
        if (switching || done) return
        if (!ctrlVisible) btnIdx = 1
        tick()
        nextVisible = false
        hintVisible = false
        introVisible = false
        ctrlVisible = true
        restartHide()
    }

    fun hideControls() {
        ctrlVisible = false
        hideJob?.cancel()
    }

    /** Some sozinho depois de 7 s, exceto se o video estiver pausado. */
    fun restartHide() {
        hideJob?.cancel()
        if (scrubbing || paused) return
        hideJob = app.scope.launch {
            delay(7000)
            if (!panelOpen && !scrubbing && !paused) hideControls()
        }
    }

    fun pressButton(action: String) {
        restartHide()
        when (action) {
            "back10" -> seekBy(-10)
            "fwd10" -> seekBy(10)
            "pause" -> togglePause()
            "scrub" -> enterScrub("buttons")
            "subs" -> openTrackPanel("subs")
            "audio" -> openTrackPanel("audio")
            "next" -> {
                hideControls()
                playNext()
            }
            "info" -> openInfoPanel()
        }
    }

    fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun seekBy(delta: Int) {
        val d = player.duration
        if (d <= 0) return
        var target = player.currentPosition / 1000 + delta
        if (target < 0) target = 0
        if (target > d / 1000 - 2) target = d / 1000 - 2
        player.seekTo(target * 1000)
        val sign = if (delta < 0) "-" else "+"
        showToast("$sign${kotlin.math.abs(delta)} s   " + formatTime(target.toInt()))
        tick()
    }

    // ---------------------------------------------------------------------------
    // Modo "ir para": mover pela barra de tempo e confirmar com OK
    // ---------------------------------------------------------------------------
    fun enterScrub(origin: String): Boolean {
        if (player.duration <= 0) return false
        if (!ctrlVisible) showControls()
        scrubOrigin = origin
        scrubMoved = false
        scrubbing = true
        scrubPos = player.currentPosition / 1000f
        scrubIdx = 0
        scrubDir = 0
        hideJob?.cancel()
        restartScrubTimer()
        return true
    }

    private fun restartScrubTimer() {
        scrubJob?.cancel()
        scrubJob = app.scope.launch {
            delay(3000)
            // Parado por 3 s: confirma o ponto escolhido (ou sai, se nao mexeu)
            if (scrubbing) {
                if (scrubMoved) commitScrub() else exitScrub()
            }
        }
    }

    fun exitScrub() {
        scrubbing = false
        scrubJob?.cancel()
        if (scrubOrigin == "hidden") hideControls() else restartHide()
    }

    /** Passos de 10, 10, 20, 30, 60, 90 e 120 s: repetir a tecla acelera. */
    fun moveScrub(dir: Int) {
        val steps = intArrayOf(10, 10, 20, 30, 60, 90, 120)
        val now = System.currentTimeMillis()
        if (dir == scrubDir && now - scrubAt < 700) {
            if (scrubIdx < steps.size - 1) scrubIdx++
        } else {
            scrubIdx = 0
        }
        scrubDir = dir
        scrubAt = now
        scrubTo(scrubPos + dir * steps[scrubIdx])
    }

    fun scrubTo(target: Float) {
        val d = player.duration / 1000f
        var tgt = target
        if (tgt < 0f) tgt = 0f
        if (tgt > d - 2) tgt = d - 2
        scrubPos = tgt
        scrubMoved = true
        restartScrubTimer()
    }

    fun commitScrub() {
        val target = scrubPos
        player.seekTo((target * 1000).toLong())
        showToast(t("player_goto") + "  " + formatTime(target.toInt()))
        exitScrub()
    }

    // ---------------------------------------------------------------------------
    // Proximo episodio
    // ---------------------------------------------------------------------------
    fun playNext() {
        val nxt = nextEp ?: return
        if (switching) return
        switching = true
        nextVisible = false
        finishEpisode()

        videoId = nxt.id
        season = nxt.season
        episode = nxt.episode
        player.stop()
        computeNext()
        loadingText = t("player_loading_next")
        prepareJob?.cancel()
        prepareJob = app.scope.launch {
            val res = Streams.fetch(app.addons, info.kind, nxt.id)
            val found = res.found
            // prefere a mesma fonte (addon) do episodio anterior
            val s = found.firstOrNull { it.addonUrl == preferAddonUrl } ?: found.firstOrNull()
            if (s == null) {
                loadingText = ""
                closePlayer()
                app.showMessage(t("play_error_title"), t("player_no_next_sources"))
                return@launch
            }
            url = s.url
            format = guessStreamFormat(s.url)
            headers = s.headers
            source = SourceInfo(s.addonName, s.addonUrl, s.title)
            streamSubs = s.subs
            onSourceChanged?.invoke(s.headers)
            beginPrepare()
        }
    }

    /** Avisa a tela para trocar os cabecalhos HTTP do player (fonte nova). */
    var onSourceChanged: ((Map<String, String>) -> Unit)? = null

    // ---------------------------------------------------------------------------
    // Painel lateral: legendas, audio e detalhes do addon/fonte
    // ---------------------------------------------------------------------------
    private fun openPanel(title: String, infoText: String, labels: List<String>, actions: List<() -> Unit>) {
        panelTitle = title
        panelInfo = infoText
        menuLabels = labels
        menuActions = actions
        menuIdx = 0
        panelOpen = true
        hideJob?.cancel()
    }

    fun closePanel() {
        panelOpen = false
        if (ctrlVisible) restartHide()
    }

    fun selectMenu() {
        menuActions.getOrNull(menuIdx)?.invoke()
        closePanel()
    }

    private fun openTrackPanel(mode: String) {
        panelMode = mode
        val type = if (mode == "subs") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (mode == "subs") {
            labels.add(t("opt_off"))
            actions.add {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                var label = f.label ?: ""
                if (label.isEmpty()) label = langName(f.language ?: "")
                if (label == "?") label = if (mode == "subs") t("player_subs") + " " + (labels.size) else t("player_audio") + " " + (labels.size + 1)
                if (group.isTrackSelected(i)) label = "✓  $label"
                labels.add(label)
                val g: Tracks.Group = group
                val trackIndex = i
                actions.add {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, trackIndex))
                        .build()
                }
            }
        }
        if (labels.isEmpty()) {
            labels.add(t("opt_auto"))
            actions.add { }
        }
        openPanel(if (mode == "subs") t("player_subs") else t("player_audio"), "", labels, actions)
    }

    private fun sourceDetails(): String {
        val src = source ?: return ""
        val lines = ArrayList<String>()
        val addon = AddonStore.findAddon(app.addons, src.addonUrl)
        if (addon != null) lines.add(AddonStore.addonDetailsText(addon, t))
        else if (src.addonName.isNotEmpty()) lines.add(src.addonName)
        if (src.label.isNotEmpty()) lines.add(t("player_source") + ": " + src.label)
        return lines.joinToString("\n\n")
    }

    private fun openInfoPanel() {
        panelMode = "info"
        openPanel(t("player_source"), sourceDetails(), listOf(t("player_close")), listOf<() -> Unit>({}))
    }

    // ---------------------------------------------------------------------------
    // Historico ("Continuar assistindo")
    // ---------------------------------------------------------------------------
    private fun buildEntry(vid: String, sn: Int, ep: Int, p: Int, d: Int) =
        info.copy(
            videoId = vid,
            season = sn,
            episode = ep,
            position = p,
            duration = d,
            description = info.description.take(120),
            ts = System.currentTimeMillis() / 1000,
        )

    /** Terminou: remove o item atual e, se houver proximo episodio, deixa a serie apontando para ele. */
    fun finishEpisode() {
        if (finished) return
        finished = true
        app.store.removeHistory(videoId)
        nextEp?.let { nxt -> app.store.upsertHistory(buildEntry(nxt.id, nxt.season, nxt.episode, 0, 0)) }
        app.bumpHistory()
    }

    fun saveNow() {
        if (switching || finished) return
        val p = (player.currentPosition / 1000).toInt()
        val dMs = player.duration
        if (dMs <= 0) return
        val d = (dMs / 1000).toInt()
        if (d <= 0 || p < 10) return
        if (p >= d * 0.95) {
            finishEpisode()
        } else {
            app.store.upsertHistory(buildEntry(videoId, season, episode, p, d))
            app.bumpHistory()
        }
    }

    fun closePlayer() {
        if (done) return
        done = true
        prepareJob?.cancel()
        player.stop()
        if (app.stack.lastOrNull() is Screen.Player) app.pop()
    }

    // ---------------------------------------------------------------------------
    // Teclas
    // ---------------------------------------------------------------------------
    fun onBack() {
        when {
            panelOpen -> closePanel()
            scrubbing -> exitScrub()
            ctrlVisible -> hideControls()
            else -> {
                saveNow()
                closePlayer()
            }
        }
    }

    /** Retorna true se a tecla foi usada (so KeyDown chega aqui). */
    fun onKey(key: Key, repeat: Int): Boolean {
        val isOk = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

        if (panelOpen) {
            when {
                key == Key.DirectionUp -> if (menuIdx > 0) menuIdx--
                key == Key.DirectionDown -> if (menuIdx < menuLabels.size - 1) menuIdx++
                isOk && repeat == 0 -> selectMenu()
            }
            return true
        }

        // ajuste da barra de tempo
        if (scrubbing) {
            when {
                key == Key.DirectionLeft -> moveScrub(-1)
                key == Key.DirectionRight -> moveScrub(1)
                key == Key.MediaRewind -> scrubTo(scrubPos - 30)
                key == Key.MediaFastForward -> scrubTo(scrubPos + 30)
                (isOk && repeat == 0) || key == Key.MediaPlay || key == Key.MediaPlayPause -> commitScrub()
                key == Key.DirectionDown || key == Key.DirectionUp -> exitScrub()
            }
            return true
        }

        // atalhos que valem com ou sem controles na tela
        when (key) {
            Key.MediaPlay, Key.MediaPlayPause, Key.MediaPause -> {
                if (repeat == 0) togglePause()
                return true
            }
            Key.MediaRewind -> {
                seekBy(-30)
                return true
            }
            Key.MediaFastForward -> {
                seekBy(30)
                return true
            }
        }

        if (ctrlVisible) {
            val count = buttons().size
            when {
                key == Key.DirectionLeft -> {
                    if (btnIdx > 0) btnIdx--
                    restartHide()
                }
                key == Key.DirectionRight -> {
                    if (btnIdx < count - 1) btnIdx++
                    restartHide()
                }
                key == Key.DirectionUp -> enterScrub("buttons")
                isOk && repeat == 0 -> buttons().getOrNull(btnIdx)?.let { pressButton(it.action) }
            }
            return true
        }

        // controles escondidos
        when {
            isOk -> {
                if (repeat == 0) {
                    if (nextVisible) playNext() else togglePause()
                }
            }
            key == Key.DirectionDown || key == Key.DirectionUp || key == Key.Menu -> showControls()
            key == Key.DirectionLeft || key == Key.DirectionRight -> {
                val dir = if (key == Key.DirectionRight) 1 else -1
                if (enterScrub("hidden")) moveScrub(dir) else seekBy(dir * 10)
            }
            else -> return false
        }
        return true
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(app: AppState, req: PlayRequest) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = app.t

    val http = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent("KinoraAndroidTV/1.3")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(req.headers)
    }
    val m = remember {
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
        PlayerModel(app, req, player).also { pm ->
            pm.onSourceChanged = { h -> http.setDefaultRequestProperties(h) }
        }
    }
    val player = m.player
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    m.onPlaying()
                } else if (!player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    m.onPaused()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && player.playbackState == Player.STATE_READY) m.onPaused()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> m.onBuffering()
                    Player.STATE_READY -> if (player.playWhenReady) m.loadingText = ""
                    Player.STATE_ENDED -> m.onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                m.onError(error)
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                m.saveNow()
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        m.beginPrepare()
    }
    // tempo, barra e cartao do proximo episodio
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            m.tick()
        }
    }
    // salva o progresso a cada 15 s
    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            if (player.isPlaying) m.saveNow()
        }
    }
    LaunchedEffect(app.focusTick) { focus.focusSoon() }

    // o menu do painel acompanha o item escolhido
    LaunchedEffect(m.menuIdx, m.panelOpen) {
        if (m.panelOpen) {
            val visible = m.menuState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == m.menuIdx }) m.menuState.scrollToItem(m.menuIdx)
            else if (visible.lastOrNull()?.index == m.menuIdx && m.menuIdx < m.menuLabels.size - 1) {
                m.menuState.scrollToItem((m.menuIdx - 5).coerceAtLeast(0))
            }
        }
    }

    BackHandler { m.onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.key == Key.Back) return@onKeyEvent false
                if (!e.isDown()) {
                    // o soltar das teclas tratadas tambem e consumido
                    return@onKeyEvent e.isSelect() || e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
                        e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                }
                m.onKey(e.key, e.repeatCount())
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setBackgroundColor(AColor.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Ficha de abertura
        if (m.introVisible) IntroCard(m)

        if (m.hintVisible) {
            KLabel(t("player_hint"), 60, 40, 1400, 40, 22, color = Color(0xCCFFFFFF))
        }
        if (m.loadingText.isNotEmpty()) {
            KLabel(m.loadingText, 0, 500, 1920, 60, 30, weight = W.Medium, color = K.White, align = TextAlign.Center)
        }
        if (m.centerIcon != 0) {
            Image(
                painter = painterResource(m.centerIcon),
                contentDescription = null,
                modifier = Modifier.at(880, 460).box(160, 160),
            )
        }
        if (m.toast.isNotEmpty()) {
            KLabel(m.toast, 0, 430, 1920, 70, 40, weight = W.Bold, color = K.White, align = TextAlign.Center)
        }

        if (m.nextVisible) NextCard(m)
        if (m.ctrlVisible) Controls(m)
        if (m.panelOpen) SidePanel(m)
    }
}

@Composable
private fun IntroCard(m: PlayerModel) {
    val info = m.info
    Box(Modifier.fillMaxWidth().height(d(200)).alpha(0.9f).background(K.GradTop))
    KLabel(info.name, 80, 34, 1700, 66, 42, weight = W.Bold, color = K.White)
    Row(Modifier.at(80, 108).height(d(38)), verticalAlignment = Alignment.CenterVertically) {
        if (info.rating.isNotEmpty()) {
            Badge("IMDb " + info.rating, Color(0xFFF5C518), K.Bg)
            Box(Modifier.width(d(16)))
        }
        if (info.cert.isNotEmpty()) {
            Badge(info.cert.uppercase(), Color(0x3CFFFFFF), K.White)
            Box(Modifier.width(d(16)))
        }
        KText(m.introMeta(), 22, weight = W.Medium, color = Color(0xFFE6E6EE))
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Box(
        Modifier.height(d(38)).clip(RoundedCornerShape(d(8))).background(bg).padding(horizontal = d(16)),
        contentAlignment = Alignment.Center,
    ) {
        KText(text, 20, weight = W.Bold, color = fg)
    }
}

@Composable
private fun NextCard(m: PlayerModel) {
    val t = m.t
    val n = m.nextEp ?: return
    var info = t.epCode(n.season, n.episode)
    if (n.title.isNotEmpty()) info += "  " + n.title
    var hint = t("player_next_ok")
    if (m.app.settings.autoNext) hint = t.f("player_next_in", m.nextSeconds.toString()) + "     " + hint
    Box(Modifier.at(1120, 560).box(720, 190).clip(RoundedCornerShape(d(16))).background(Color(0xEE14141A)))
    KLabel(t("player_next"), 1148, 574, 664, 42, 26, weight = W.Bold, color = K.White)
    KLabel(info, 1148, 622, 664, 36, 22, weight = W.Medium, color = K.Meta)
    KLabel(hint, 1148, 678, 664, 40, 22, weight = W.Medium, color = Color(0xFFA78BFA))
}

@Composable
private fun Controls(m: PlayerModel) {
    val scrubbing = m.scrubbing
    Box(Modifier.fillMaxSize().alpha(0.92f).background(K.GradBottom))
    KLabel(m.info.name, 80, 646, 1700, 60, 38, weight = W.Bold, color = K.White)
    KLabel(m.ctlInfo(), 80, 710, 1700, 40, 24, weight = W.Medium, color = K.Meta)

    // Barra de progresso (mais grossa e com bolinha maior no modo "ir para")
    val posSec = if (scrubbing) m.scrubPos else m.pos
    val ratio = if (m.dur > 0) (posSec / m.dur).coerceIn(0f, 1f) else 0f
    val fillW = (1760 * ratio).toInt()
    val th = if (scrubbing) 16 else 10
    val ks = if (scrubbing) 40 else 28
    val barTop = 795 - th / 2
    Box(Modifier.at(80, barTop).box(1760, th).clip(RoundedCornerShape(d(th / 2))).background(Color(0x46FFFFFF)))
    if (fillW >= 12) {
        Box(Modifier.at(80, barTop).box(fillW, th).clip(RoundedCornerShape(d(th / 2))).background(K.White))
    }
    Box(Modifier.at(80 + fillW - ks / 2, 795 - ks / 2).box(ks, ks).clip(CircleShape).background(K.White))

    if (scrubbing) {
        KLabel(m.t("player_scrub_hint"), 1000, 754, 840, 36, 21, weight = W.Medium, color = K.Meta, align = TextAlign.End)
        var txt = formatTime(posSec.toInt())
        if (m.dur > 0) txt += "  /  " + formatTime(m.dur.toInt())
        val tx = (80 + fillW - 125).coerceIn(80, 1590)
        Box(
            Modifier.at(tx, 818).box(250, 44).clip(RoundedCornerShape(d(10))).background(K.White),
            contentAlignment = Alignment.Center,
        ) {
            KText(txt, 21, weight = W.Bold, color = K.Bg, align = TextAlign.Center)
        }
    } else {
        KLabel(formatTime(m.pos.toInt()), 80, 812, 400, 36, 22, weight = W.Medium, color = K.White)
        if (m.dur > 0) {
            KLabel(formatTime(m.dur.toInt()), 1440, 812, 400, 36, 22, weight = W.Medium, color = K.White, align = TextAlign.End)
        }
    }

    // Botoes com icone
    Row(Modifier.at(80, 868), horizontalArrangement = Arrangement.spacedBy(d(12))) {
        m.buttons().forEachIndexed { i, b ->
            val focused = i == m.btnIdx && !scrubbing
            Box(
                Modifier.box(130, 112).clip(RoundedCornerShape(d(14)))
                    .background(if (focused) Color(0x4DFFFFFF) else Color.Transparent)
            ) {
                Image(
                    painter = painterResource(b.icon),
                    contentDescription = null,
                    modifier = Modifier.at(37, 12).box(56, 56),
                )
                KLabel(b.label, 0, 76, 130, 30, 17, weight = W.Medium, color = Color(0xFFE6E6EE), align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SidePanel(m: PlayerModel) {
    Box(Modifier.fillMaxSize().background(Color(0xA0000000)))
    Box(Modifier.at(1060, 80).box(780, 920).clip(RoundedCornerShape(d(24))).background(K.Panel))
    KLabel(m.panelTitle, 1100, 106, 700, 52, 30, weight = W.Bold, color = K.White)
    if (m.panelInfo.isNotEmpty()) {
        KLabel(m.panelInfo, 1100, 170, 700, 330, 20, color = K.Desc, maxLines = 9)
    }
    LazyColumn(
        state = m.menuState,
        modifier = Modifier.at(1100, 520).box(700, 440),
        contentPadding = PaddingValues(vertical = d(4)),
    ) {
        itemsIndexed(m.menuLabels) { idx, label ->
            val sel = idx == m.menuIdx
            Box(
                Modifier.box(700, 60).clip(RoundedCornerShape(d(30)))
                    .background(if (sel) K.White else Color.Transparent)
                    .padding(start = d(24), end = d(16)),
                contentAlignment = Alignment.CenterStart,
            ) {
                KText(label, 22, weight = W.Medium, color = if (sel) K.Bg else K.TextList)
            }
        }
    }
}
