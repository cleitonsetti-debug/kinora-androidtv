package com.kinora.tv.ui

import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kinora.tv.data.PlayRequest
import kotlinx.coroutines.delay

// =============================================================================
// PlayerView: toca o stream escolhido e mantem o "Continuar assistindo":
//  - salva o progresso a cada 15 s e ao sair
//  - ao terminar um episodio, a serie continua na lista apontando para o PROXIMO
//    episodio; ao terminar um filme (ou o ultimo episodio), sai da lista
// =============================================================================

private class PlayerSession(val app: AppState, val req: PlayRequest, val player: ExoPlayer) {
    var done = false
    var finished = false

    private fun buildEntry(videoId: String, season: Int, episode: Int, pos: Int, dur: Int) =
        req.info.copy(
            videoId = videoId,
            season = season,
            episode = episode,
            position = pos,
            duration = dur,
            description = req.info.description.take(120),
            ts = System.currentTimeMillis() / 1000,
        )

    /** Terminou: remove o item atual e, se houver proximo episodio, deixa a serie apontando para ele. */
    fun finishEpisode() {
        if (finished) return
        finished = true
        app.store.removeHistory(req.videoId)
        req.nextEp?.let { nxt ->
            app.store.upsertHistory(buildEntry(nxt.id, nxt.season, nxt.episode, 0, 0))
        }
        app.bumpHistory()
    }

    fun saveNow() {
        if (finished) return
        val pos = (player.currentPosition / 1000).toInt()
        val durMs = player.duration
        if (durMs <= 0) return
        val dur = (durMs / 1000).toInt()
        if (dur <= 0 || pos < 10) return
        if (pos >= dur * 0.95) {
            finishEpisode()
        } else {
            app.store.upsertHistory(buildEntry(req.videoId, req.season, req.episode, pos, dur))
            app.bumpHistory()
        }
    }

    fun close() {
        if (done) return
        done = true
        player.stop()
        if (app.stack.lastOrNull() is Screen.Player) app.pop()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(app: AppState, req: PlayRequest) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val session = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("KinoraAndroidTV/1.2")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(req.headers)
        val dataSource = DefaultDataSource.Factory(context, http)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        PlayerSession(app, req, player)
    }
    val player = session.player

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    session.finishEpisode()
                    session.close()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val t = app.t
                session.saveNow()
                session.close()
                app.showMessage(
                    t("play_error_title"),
                    t.f2("play_error_body", error.message ?: error.errorCodeName, error.errorCode.toString()),
                )
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.saveNow()
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
        val builder = MediaItem.Builder()
            .setUri(req.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
        when (req.format) {
            "hls" -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "dash" -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            "mkv" -> builder.setMimeType(MimeTypes.VIDEO_MATROSKA)
            "mp4" -> builder.setMimeType(MimeTypes.VIDEO_MP4)
        }
        val item = builder.build()
        val start = if (app.settings.resume) app.store.getSavedPosition(req.videoId) else 0
        if (start > 0) player.setMediaItem(item, start * 1000L) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true

        // Salva o progresso a cada 15 s
        while (true) {
            delay(15000)
            if (player.isPlaying) session.saveNow()
        }
    }

    BackHandler {
        session.saveNow()
        session.close()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    setBackgroundColor(AColor.BLACK)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

