package com.kinora.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de uma requisicao JSON: { ok, status, data, error } como no JsonTask do Roku. */
data class JsonResult(val ok: Boolean, val status: Int, val data: JSONObject?, val error: String, val array: JSONArray? = null)

/** Um erro de rede para a tela de diagnostico. */
data class NetLogEntry(val t: String, val target: String, val msg: String)

object NetLog {
    private val list = ArrayList<NetLogEntry>()

    @Synchronized
    fun add(url: String, status: Int, err: String) {
        list.add(NetLogEntry(formatClock(), netTarget(url), "$err $status".trim()))
        while (list.size > 30) list.removeAt(0)
    }

    @Synchronized
    fun entries(): List<NetLogEntry> = ArrayList(list)
}

object Net {
    const val UA = "KinoraAndroidTV/1.5"

    suspend fun getJson(url: String, timeoutMs: Int = 15000): JsonResult = withContext(Dispatchers.IO) {
        val r = fetch(url, timeoutMs)
        if (!r.ok) NetLog.add(url, r.status, r.error)
        r
    }

    private fun fetch(url: String, timeoutMs: Int): JsonResult {
        if (url.isEmpty()) return JsonResult(false, 0, null, "url vazia")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val trimmed = text.trimStart()
                try {
                    if (trimmed.startsWith("[")) JsonResult(true, code, null, "", JSONArray(trimmed))
                    else JsonResult(true, code, JSONObject(trimmed), "")
                } catch (e: Exception) {
                    JsonResult(false, code, null, "json invalido")
                }
            } else {
                JsonResult(false, code, null, "http $code")
            }
        } catch (e: java.net.SocketTimeoutException) {
            JsonResult(false, 0, null, "timeout")
        } catch (e: Exception) {
            JsonResult(false, 0, null, e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
