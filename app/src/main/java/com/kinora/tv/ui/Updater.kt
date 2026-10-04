package com.kinora.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import com.kinora.tv.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizacao pelo proprio app: baixa o APK da release e abre o instalador do Android.
 * (No Roku isso era feito por um script no computador; no Android o app consegue sozinho,
 * sempre com a confirmacao do usuario na tela do instalador.)
 */
object Updater {
    fun downloadAndInstall(app: AppState, url: String) {
        val ctx = app.context
        // Android 8+: o Kinora precisa da permissao "instalar apps desconhecidos"
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) {
            }
            app.showMessage(app.t("upd_new_title"), app.t("upd_allow"))
            return
        }
        app.statusText = app.t("upd_downloading")
        app.showMessage(app.t("upd_new_title"), app.t("upd_downloading"))
        app.scope.launch {
            val file = withContext(Dispatchers.IO) { download(url, File(ctx.cacheDir, "updates")) }
            app.statusText = ""
            if (file == null) {
                app.showMessage(app.t("upd_new_title"), app.t("upd_dl_failed"))
                return@launch
            }
            app.closeDialog()
            try {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, "application/vnd.android.package-archive")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) {
                app.showMessage(app.t("upd_new_title"), app.t("upd_dl_failed"))
            }
        }
    }

    private fun download(url: String, dir: File): File? {
        var conn: HttpURLConnection? = null
        return try {
            dir.mkdirs()
            val out = File(dir, "Kinora-AndroidTV.apk")
            var current = url
            // segue redirecionamentos (GitHub -> servidor de arquivos)
            for (hop in 0 until 6) {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", Net.UA)
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    continue
                }
                if (code !in 200..299) return null
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                return if (out.length() > 100_000) out else null
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
