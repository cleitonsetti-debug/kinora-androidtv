package com.kinora.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.kinora.tv.BuildConfig
import com.kinora.tv.data.NetLog
import com.kinora.tv.data.SelfTest
import com.kinora.tv.data.hostOf

// =============================================================================
// DiagView: versao, aparelho, status dos addons, ultimos erros de rede e autoteste.
// Tire uma foto desta tela ao relatar um problema.
// =============================================================================

@Composable
fun DiagScreen(app: AppState) {
    val t = app.t
    var testLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val req0 = remember { FocusRequester() }

    LaunchedEffect(app.focusTick) { req0.focusSoon() }

    val lines = remember(refresh, testLines, app.addonsRev, t) {
        val out = ArrayList<String>()
        out.add("Kinora " + BuildConfig.VERSION_NAME)
        out.add(t("diag_device") + ": " + Build.MANUFACTURER + " " + Build.MODEL + "   |   Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        out.add("")
        out.add(t("diag_addons") + ":")
        for (a in app.addons) {
            val st = when {
                !a.ok -> t("state_err")
                !a.enabled -> t("state_off")
                else -> t("state_on")
            }
            out.add("  [" + st + "] " + a.name + "  (" + hostOf(a.url) + ")")
        }
        out.add("")
        val entries = NetLog.entries()
        out.add(t("diag_errors") + " (" + entries.size + "):")
        if (entries.isEmpty()) {
            out.add("  " + t("diag_none"))
        } else {
            for (e in entries.reversed().take(7)) out.add("  " + e.t + "  " + e.target + "  " + e.msg)
        }
        if (testLines.isNotEmpty()) {
            out.add("")
            out.addAll(testLines)
        }
        out
    }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        KLabel(t("diag_title"), 100, 50, 1700, 70, 42, weight = W.Bold, color = K.White)
        KLabel(t("diag_hint"), 100, 134, 1700, 40, 22, color = K.Hint)
        ListPill(
            text = t("diag_run"), w = 520, h = 60,
            modifier = Modifier.at(100, 196).focusRequester(req0)
                .onPreviewKeyEvent { e -> e.isDown() && (e.key == Key.DirectionUp || e.key == Key.DirectionLeft || e.key == Key.DirectionRight) },
            onClick = {
                val r = SelfTest.run(app.store, t, app.currentRelease)
                val l = ArrayList<String>()
                l.add(t("diag_selftest") + ": " + (r.total - r.failures.size) + "/" + r.total)
                for (f in r.failures) l.add("  X $f")
                testLines = l
            },
        )
        ListPill(
            text = t("diag_refresh"), w = 520, h = 60,
            modifier = Modifier.at(100, 260)
                .onPreviewKeyEvent { e -> e.isDown() && (e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight) },
            onClick = {
                testLines = emptyList()
                refresh++
            },
        )
        KLabel(lines.joinToString("\n"), 100, 340, 1700, 700, 21, color = K.TextList, maxLines = 24)
    }
}
