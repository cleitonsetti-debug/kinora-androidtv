package com.kinora.tv.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import com.kinora.tv.R

/** Paleta do Kinora (igual ao app Roku). */
object K {
    val Bg = Color(0xFF0A0A0D)
    val Panel = Color(0xFF14141A)
    val Surface = Color(0xFF1C1C22)
    val Dim = Color(0xFF2C2C34)
    val White = Color(0xFFFFFFFF)
    val Text = Color(0xFFF2F2F5)
    val TextList = Color(0xFFD8D8E0)
    val Meta = Color(0xFFC9C9D2)
    val Desc = Color(0xFFB4B4BF)
    val Hint = Color(0xFF9A9AA6)
    val Muted = Color(0xFF7A7A86)
    val Scrim = Color(0xC0000000)

    /** Gradientes do banner (mesmos valores dos PNGs grad_left/grad_bottom/grad_top do Roku). */
    val GradLeft = Brush.horizontalGradient(
        0.00f to Bg.copy(alpha = 0.98f),
        0.17f to Bg.copy(alpha = 0.82f),
        0.33f to Bg.copy(alpha = 0.47f),
        0.50f to Bg.copy(alpha = 0.13f),
        0.67f to Bg.copy(alpha = 0f),
        1.00f to Bg.copy(alpha = 0f),
    )
    val GradBottom = Brush.verticalGradient(
        0.00f to Bg.copy(alpha = 0f),
        0.22f to Bg.copy(alpha = 0f),
        0.33f to Bg.copy(alpha = 0.47f),
        0.42f to Bg.copy(alpha = 0.91f),
        0.50f to Bg,
        1.00f to Bg,
    )
    val GradTop = Brush.verticalGradient(
        0.00f to Bg.copy(alpha = 0.75f),
        0.40f to Bg.copy(alpha = 0.48f),
        1.00f to Bg.copy(alpha = 0f),
    )

    val Poppins = FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_medium, FontWeight.Medium),
        Font(R.font.poppins_bold, FontWeight.Bold),
    )
}

/**
 * O layout foi desenhado em 1920x1080 (igual ao Roku). LocalScale converte
 * "pixels de projeto" em dp para qualquer TV (720p, 1080p, 4K).
 */
val LocalScale = staticCompositionLocalOf { 0.5f }

/** Pixels de projeto (1920x1080) -> Dp */
@Composable
@ReadOnlyComposable
fun d(v: Number): Dp = (v.toFloat() * LocalScale.current).dp

/** Tamanho de fonte em pixels de projeto -> sp (ignora a escala de fonte do sistema para manter o layout). */
@Composable
@ReadOnlyComposable
fun s(v: Number): TextUnit = (v.toFloat() * LocalScale.current / LocalDensity.current.fontScale).sp

/** Posiciona em coordenadas de projeto, como translation no Roku. */
@Composable
fun Modifier.at(x: Number, y: Number): Modifier = this.offset(d(x), d(y))

@Composable
fun Modifier.box(w: Number, h: Number): Modifier = this.size(d(w), d(h))

enum class W { Regular, Medium, Bold }

@Composable
fun KText(
    text: String,
    size: Number,
    modifier: Modifier = Modifier,
    weight: W = W.Regular,
    color: Color = K.Text,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    val fw = when (weight) {
        W.Regular -> FontWeight.Normal
        W.Medium -> FontWeight.Medium
        W.Bold -> FontWeight.Bold
    }
    val fs = s(size)
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = K.Poppins,
            fontWeight = fw,
            fontSize = fs,
            lineHeight = fs * 1.3f,
            color = color,
            textAlign = align,
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        maxLines = maxLines,
    )
}

/** Texto alinhado verticalmente dentro de uma caixa de tamanho fixo (Label com vertAlign). */
@Composable
fun KLabel(
    text: String,
    x: Number,
    y: Number,
    w: Number,
    h: Number,
    size: Number,
    weight: W = W.Regular,
    color: Color = K.Text,
    maxLines: Int = 1,
    vAlign: Alignment.Vertical = Alignment.Top,
    align: TextAlign = TextAlign.Start,
) {
    val boxAlign = when (vAlign) {
        Alignment.Bottom -> when (align) {
            TextAlign.Center -> Alignment.BottomCenter
            TextAlign.End -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
        Alignment.CenterVertically -> when (align) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
        else -> when (align) {
            TextAlign.Center -> Alignment.TopCenter
            TextAlign.End -> Alignment.TopEnd
            else -> Alignment.TopStart
        }
    }
    Box(Modifier.at(x, y).width(d(w)).height(d(h)), contentAlignment = boxAlign) {
        KText(text, size, weight = weight, color = color, maxLines = maxLines, align = align)
    }
}
