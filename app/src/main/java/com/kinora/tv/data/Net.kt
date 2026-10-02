package com.kinora.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de uma requisicao JSON: { ok, status, data, error } como no JsonTask do Roku. */
data class JsonResult(val ok: Boolean, val status: Int, val data: JSONObject?, val error: String)

object Net {
    private const val UA = "KinoraAndroidTV/1.2"

    suspend fun getJson(url: String, timeoutMs: Int = 20000): JsonResult = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext JsonResult(false, 0, null, "url vazia")
        var conn: HttpURLConnection? = null
        try {
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
                try {
                    JsonResult(true, code, JSONObject(text), "")
                } catch (e: Exception) {
                    JsonResult(false, code, null, "json invalido")
                }
            } else {
                JsonResult(false, code, null, "http $code")
            }
        } catch (e: Exception) {
            JsonResult(false, 0, null, e.message ?: "erro de rede")
        } finally {
            conn?.disconnect()
        }
    }
}
