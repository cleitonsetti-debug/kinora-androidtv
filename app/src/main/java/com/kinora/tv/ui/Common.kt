package com.kinora.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import com.kinora.tv.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Teclas do controle
// ---------------------------------------------------------------------------
fun KeyEvent.isDown() = type == KeyEventType.KeyDown
fun KeyEvent.isUp() = type == KeyEventType.KeyUp
fun KeyEvent.isSelect() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
fun KeyEvent.repeatCount(): Int = nativeKeyEvent.repeatCount

/** Tecla equivalente ao "*" (options) do controle Roku: Menu, botao Y, Delete. */
fun KeyEvent.isOptions() = key == Key.Menu || key == Key.ButtonY || key == Key.Delete || key == Key.Backspace ||
    key == Key.ProgramRed || key == Key.Info

private class ClickHolder {
    var down = false
    var long = false
}

/**
 * Clique pelo controle remoto: dispara no SOLTAR do OK, e apenas se o OK foi
 * apertado neste item (evita que o "soltar" da tela anterior dispare aqui).
 * Segurar o OK dispara onLongClick (quando informado). Toque/mouse tambem funciona.
 */
fun Modifier.tvClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val holder = remember { ClickHolder() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    this
        .onKeyEvent { e ->
            if (!e.isSelect()) return@onKeyEvent false
            if (e.isDown()) {
                if (e.repeatCount() == 0) {
                    holder.down = true
                    holder.long = false
                } else if (holder.down && !holder.long && longClick != null && e.repeatCount() >= 3) {
                    holder.long = true
                    longClick?.invoke()
                }
                true
            } else if (e.isUp()) {
                val fire = holder.down && !holder.long
                holder.down = false
                holder.long = false
                if (fire) click()
                true
            } else {
                false
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { click() },
                onLongPress = { longClick?.invoke() },
            )
        }
}

/** Pede o foco depois que a composicao assentar (o item pode ter acabado de ser criado). */
suspend fun FocusRequester.focusSoon() {
    repeat(3) {
        withFrameNanos { }
        val ok = try {
            requestFocus()
            true
        } catch (e: Exception) {
            false
        }
        if (ok) return
    }
}

// ---------------------------------------------------------------------------
// Banner de fundo (backdrop + gradientes)
// ---------------------------------------------------------------------------
@Composable
fun Backdrop(url: String) {
    Box(Modifier.fillMaxSize().background(K.Bg)) {
        if (url.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(300).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(K.GradLeft))
        Box(Modifier.fillMaxSize().background(K.GradBottom))
        Box(Modifier.fillMaxWidth().height(d(200)).background(K.GradTop))
    }
}

// ---------------------------------------------------------------------------
// Poster com cantos arredondados, anel de foco branco, progresso e titulo
// ---------------------------------------------------------------------------
@Composable
fun PosterCard(
    url: String,
    w: Int,
    h: Int,
    modifier: Modifier = Modifier,
    title: String = "",
    progress: Float = 0f,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ph = if (title.isNotEmpty()) h - 66 else h
    val radius = d(12)
    val gap = d(6)
    val stroke = d(4)
    val scope = rememberCoroutineScope()
    var attempt by remember(url) { mutableIntStateOf(0) }

    Box(
        modifier
            .box(w, h)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvClick(onLongClick, onClick)
    ) {
        Box(
            Modifier
                .box(w, ph)
                .drawWithContent {
                    drawContent()
                    if (focused) {
                        val g = gap.toPx()
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(-g, -g),
                            size = Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = CornerRadius(radius.toPx() + g),
                            style = Stroke(width = stroke.toPx()),
                        )
                    }
                }
                .clip(RoundedCornerShape(radius))
                .background(K.Surface)
        ) {
            if (url.isNotEmpty()) {
                key(attempt) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = {
                            if (attempt < 3) {
                                scope.launch {
                                    delay(1500)
                                    attempt += 1
                                }
                            }
                        },
                    )
                }
            }
            if (progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(d(6))
                        .background(Color(0x99000000))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
                }
            }
        }
        if (title.isNotEmpty()) {
            Box(Modifier.at(2, ph + 6).width(d(w - 4)).height(d(58))) {
                KText(title, 17, weight = W.Medium, color = K.Text, maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pilula de lista (LabelList do Roku): branca com texto escuro quando em foco
// ---------------------------------------------------------------------------
@Composable
fun ListPill(
    text: String,
    w: Int,
    h: Int,
    modifier: Modifier = Modifier,
    textOffset: Int = 24,
    size: Int = 24,
    centered: Boolean = false,
    dimWhenIdle: Boolean = false,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d(h / 2))
    val bg = when {
        focused -> K.White
        dimWhenIdle -> K.Dim
        else -> Color.Transparent
    }
    Box(
        modifier
            .box(w, h)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvClick(onLongClick, onClick)
            .clip(shape)
            .background(bg)
            .padding(start = if (centered) d(0) else d(textOffset), end = d(16)),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        KText(
            text, size,
            weight = W.Medium,
            color = if (focused) K.Bg else K.TextList,
            maxLines = 1,
            align = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Chip (filtros, temporadas): fundo cinza; em foco ganha anel branco. */
@Composable
fun Chip(
    text: String,
    w: Int,
    h: Int,
    modifier: Modifier = Modifier,
    fontSize: Int = 21,
    selected: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val radius = d(10)
    val gap = d(4)
    val stroke = d(3)
    Box(
        modifier
            .box(w, h)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvClick(null, onClick)
            .drawWithContent {
                drawContent()
                if (focused) {
                    val g = gap.toPx()
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(radius.toPx() + g),
                        style = Stroke(width = stroke.toPx()),
                    )
                }
            }
            .clip(RoundedCornerShape(radius))
            .background(if (focused) Color(0xFF34343C) else if (selected) Color(0xFF26262E) else K.Surface),
        contentAlignment = Alignment.Center,
    ) {
        KText(text, fontSize, weight = W.Medium, color = K.Text, maxLines = 1, align = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------
// v1.5: vidro, luz ambiente, botoes em pilula, selos, indicador de carregamento
// ---------------------------------------------------------------------------

/** Cartao de vidro (card.9 do Roku): fundo escuro translucido com borda clara fina. */
@Composable
fun Modifier.glassCard(radius: Int = 18, highlighted: Boolean = false): Modifier {
    val shape = RoundedCornerShape(d(radius))
    return this
        .clip(shape)
        .background(if (highlighted) Color(0xF528283A) else Color(0xEB1A1A20))
        .border(d(2), if (highlighted) Color(0x5AFFFFFF) else Color(0x26FFFFFF), shape)
}

/** Brilho colorido (luz ambiente) que muda com o titulo/perfil. */
@Composable
fun AccentGlow(color: Long, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
    val c = Color(color)
    Box(
        Modifier.at(x, y).box(w, h).background(
            Brush.radialGradient(
                0.0f to c.copy(alpha = 0.40f * alpha / 0.5f),
                0.45f to c.copy(alpha = 0.18f * alpha / 0.5f),
                1.0f to c.copy(alpha = 0f),
            )
        )
    )
}

/** Selo arredondado (tipo, IMDb, classificacao). */
@Composable
fun Badge(text: String, bg: Color, fg: Color, height: Int = 38, size: Int = 20) {
    Box(
        Modifier.height(d(height)).clip(RoundedCornerShape(d(10))).background(bg).padding(horizontal = d(15)),
        contentAlignment = Alignment.Center,
    ) {
        KText(text, size, weight = W.Bold, color = fg)
    }
}

/** Indicador de carregamento girando (spinner.png do Roku). */
@Composable
fun Spinner(x: Int, y: Int, size: Int = 96) {
    val tr = rememberInfiniteTransition(label = "spin")
    val angle by tr.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "angle",
    )
    Image(
        painter = painterResource(R.drawable.spinner),
        contentDescription = null,
        modifier = Modifier.at(x, y).box(size, size).rotate(angle),
    )
}

/**
 * Botao em pilula com icone (HeroButton 320x72 do banner e PillButton 290x64 da ficha):
 * vidro com texto branco em repouso; branco com texto escuro em foco.
 */
@Composable
fun IconPill(
    text: String,
    icon: Int,
    iconDark: Int,
    w: Int,
    h: Int,
    modifier: Modifier = Modifier,
    iconSize: Int = 34,
    fontSize: Int = 26,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d(h / 2))
    Box(
        modifier
            .box(w, h)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvClick(null, onClick)
            .clip(shape)
            .background(if (focused) K.White else Color(0x46FFFFFF))
            .border(d(2), if (focused) K.White else Color(0xB3FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(if (focused) iconDark else icon),
                contentDescription = null,
                modifier = Modifier.box(iconSize, iconSize),
            )
            Box(Modifier.width(d(14)))
            KText(text, fontSize, weight = W.Bold, color = if (focused) K.Bg else K.White)
        }
    }
}

/** Botao com icone em cima e rotulo embaixo (botoes do player e acoes dos addons). */
@Composable
fun IconButtonTile(label: String, icon: Int, w: Int, h: Int, focused: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.box(w, h).clip(RoundedCornerShape(d(14)))
            .background(if (focused) Color(0x4DFFFFFF) else Color.Transparent)
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.at((w - 56) / 2, 12).box(56, 56),
        )
        KLabel(label, 0, 76, w, 30, 17, weight = W.Medium, color = Color(0xFFE6E6EE), align = TextAlign.Center)
    }
}
