package com.kinora.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Dialogo no estilo do Kinora. O foco fica preso dentro dele: as setas sao
 * tratadas aqui (campo de texto em cima, botoes embaixo).
 */
@Composable
fun DialogView(app: AppState, spec: DialogSpec) {
    val hasInput = spec.input != null
    var value by remember(spec) {
        val txt = spec.input ?: ""
        mutableStateOf(TextFieldValue(txt, TextRange(txt.length)))
    }
    val fieldReq = remember(spec) { FocusRequester() }
    val btnReqs = remember(spec) { List(spec.buttons.size) { FocusRequester() } }
    var btnIdx by remember(spec) { mutableStateOf(0) }
    var inField by remember(spec) { mutableStateOf(hasInput) }

    fun press(i: Int) {
        val text = value.text
        app.closeDialog()
        spec.onButton(i, text)
    }

    BackHandler { app.closeDialog() }

    LaunchedEffect(spec) {
        if (hasInput) fieldReq.focusSoon() else btnReqs.firstOrNull()?.focusSoon()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Scrim)
            .onPreviewKeyEvent { e ->
                if (!e.isDown()) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        if (inField) return@onPreviewKeyEvent false
                        if (btnIdx > 0) btnReqs[btnIdx - 1].requestFocus()
                        true
                    }
                    Key.DirectionRight -> {
                        if (inField) return@onPreviewKeyEvent false
                        if (btnIdx < btnReqs.size - 1) btnReqs[btnIdx + 1].requestFocus()
                        true
                    }
                    Key.DirectionDown -> {
                        if (inField) btnReqs.firstOrNull()?.requestFocus()
                        true
                    }
                    Key.DirectionUp -> {
                        if (!inField && hasInput) fieldReq.requestFocus()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(d(1000))
                .clip(RoundedCornerShape(d(24)))
                .background(K.Panel)
                .padding(horizontal = d(56), vertical = d(48)),
            verticalArrangement = Arrangement.spacedBy(d(24)),
        ) {
            KText(spec.title, 34, weight = W.Bold, color = K.White, maxLines = 2)
            if (spec.message.isNotEmpty()) {
                KText(spec.message, 24, color = K.Meta, maxLines = 6)
            }
            if (hasInput) {
                var fieldFocused by remember { mutableStateOf(false) }
                val fs = s(26)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    cursorBrush = SolidColor(K.White),
                    textStyle = TextStyle(fontFamily = K.Poppins, fontWeight = FontWeight.Medium, fontSize = fs, color = K.White),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { btnReqs.firstOrNull()?.requestFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(d(76))
                        .focusRequester(fieldReq)
                        .onFocusChanged {
                            fieldFocused = it.isFocused
                            if (it.isFocused) inField = true
                        }
                        .clip(RoundedCornerShape(d(14)))
                        .background(K.Surface)
                        .border(d(3), if (fieldFocused) K.White else K.Surface, RoundedCornerShape(d(14)))
                        .padding(horizontal = d(24), vertical = d(18)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(d(20))) {
                spec.buttons.forEachIndexed { i, label ->
                    ListPill(
                        text = label,
                        w = 260,
                        h = 64,
                        size = 24,
                        centered = true,
                        dimWhenIdle = true,
                        modifier = Modifier.focusRequester(btnReqs[i]),
                        onFocus = {
                            btnIdx = i
                            inField = false
                        },
                        onClick = { press(i) },
                    )
                }
            }
        }
    }
}

/**
 * Teclado de PIN (4 digitos), igual em todos os aparelhos: setas escolhem a tecla,
 * OK digita; as teclas numericas do controle tambem funcionam.
 */
@Composable
fun PinDialog(app: AppState, spec: PinSpec) {
    var typed by remember(spec) { mutableStateOf("") }
    var idx by remember(spec) { mutableStateOf(0) }
    val req = remember(spec) { FocusRequester() }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")

    fun finish() {
        if (typed.length < 4) return
        val value = typed
        app.closePin()
        spec.onPin(value)
    }

    fun press(k: String) {
        when (k) {
            "⌫" -> if (typed.isNotEmpty()) typed = typed.dropLast(1)
            "OK" -> finish()
            else -> if (typed.length < 4) {
                typed += k
                if (typed.length == 4) idx = 11
            }
        }
    }

    BackHandler { app.closePin() }
    LaunchedEffect(spec) { req.focusSoon() }

    Box(Modifier.fillMaxSize().background(K.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(d(620))
                .clip(RoundedCornerShape(d(24)))
                .background(K.Panel)
                .padding(horizontal = d(56), vertical = d(44))
                .focusRequester(req)
                .focusable()
                .onKeyEvent { e ->
                    if (!e.isDown()) return@onKeyEvent e.isSelect()
                    when (e.key) {
                        Key.DirectionLeft -> if (idx % 3 > 0) idx--
                        Key.DirectionRight -> if (idx % 3 < 2) idx++
                        Key.DirectionUp -> if (idx >= 3) idx -= 3
                        Key.DirectionDown -> if (idx < 9) idx += 3
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.repeatCount() == 0) press(keys[idx])
                        Key.Backspace, Key.Delete -> press("⌫")
                        else -> {
                            val cp = e.utf16CodePoint
                            if (cp in '0'.code..'9'.code) press(cp.toChar().toString()) else return@onKeyEvent false
                        }
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d(22)),
        ) {
            KText(spec.title, 32, weight = W.Bold, color = K.White, align = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(d(22))) {
                for (i in 0 until 4) {
                    Box(
                        Modifier.box(26, 26).clip(RoundedCornerShape(d(13)))
                            .background(if (i < typed.length) K.White else Color(0x40FFFFFF))
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(d(12))) {
                for (r in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                        for (c in 0 until 3) {
                            val i = r * 3 + c
                            val sel = i == idx
                            Box(
                                Modifier.box(150, 76).clip(RoundedCornerShape(d(14)))
                                    .background(if (sel) K.White else K.Surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                KText(keys[i], 30, weight = W.Bold, color = if (sel) K.Bg else K.White, align = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
