package com.kinora.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Converte qualquer valor JSON em String de forma segura (numeros viram texto). */
fun asStr(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is String -> v
    is Int, is Long -> v.toString()
    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    is Float -> if (v == Math.floor(v.toDouble()).toFloat()) v.toLong().toString() else v.toString()
    is Number -> v.toString()
    else -> ""
}

fun toInt(v: Any?): Int = when (v) {
    null, JSONObject.NULL -> 0
    is Number -> v.toInt()
    is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
    else -> 0
}

fun JSONObject.str(key: String): String = asStr(opt(key))
fun JSONObject.int(key: String): Int = toInt(opt(key))

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val o = opt(i)
        if (o is JSONObject) out.add(o)
    }
    return out
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val s = asStr(opt(i))
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}

/** Junta ate maxItems strings de um array JSON (ex.: generos). */
fun joinList(v: Any?, maxItems: Int): String {
    if (v !is JSONArray) return ""
    return v.strings().take(maxItems).joinToString(", ")
}

/** Percent-encoding no estilo do Roku (espaco vira %20). */
fun urlEncode(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")

/** Normaliza a URL de um addon: aceita stremio://, remove /manifest.json e a barra final. */
fun normalizeAddonUrl(u: String): String {
    var s = u.trim()
    if (s.lowercase().startsWith("stremio://")) s = "https://" + s.substring(10)
    if (s.lowercase().endsWith("/manifest.json")) s = s.substring(0, s.length - 14)
    s = s.trimEnd('/')
    val low = s.lowercase()
    if (low.startsWith("http://") || low.startsWith("https://")) return s
    return ""
}

fun guessStreamFormat(url: String): String {
    val low = url.lowercase()
    return when {
        ".m3u8" in low -> "hls"
        ".mpd" in low -> "dash"
        ".mkv" in low -> "mkv"
        ".mp4" in low -> "mp4"
        else -> ""
    }
}
