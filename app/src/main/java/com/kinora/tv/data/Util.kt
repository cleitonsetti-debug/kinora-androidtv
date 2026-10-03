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

// ---------------------------------------------------------------------------
// Idiomas de faixas (legenda/audio): pref = "pt" | "en" | "es"
// ---------------------------------------------------------------------------
fun langMatches(code: String, pref: String): Boolean {
    val c = code.lowercase()
    if (c.isEmpty()) return false
    return when (pref) {
        "pt" -> c == "pt" || c.startsWith("pt-") || c.startsWith("pt_") || c == "por" || c == "pob" || c == "pb"
        "en" -> c == "en" || c.startsWith("en-") || c.startsWith("en_") || c == "eng"
        "es" -> c == "es" || c.startsWith("es-") || c.startsWith("es_") || c == "spa" || c == "esl" || c == "lat"
        else -> false
    }
}

/** Codigo de 2 letras para o player (Media3 usa codigos BCP-47). */
fun toLang2(code: String): String = when {
    langMatches(code, "pt") -> "pt"
    langMatches(code, "en") -> "en"
    langMatches(code, "es") -> "es"
    else -> code.lowercase().ifEmpty { "und" }
}

fun langName(code: String): String = when {
    langMatches(code, "pt") -> "Português"
    langMatches(code, "en") -> "English"
    langMatches(code, "es") -> "Español"
    code.isEmpty() -> "?"
    else -> code.uppercase()
}

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

/** 3725 -> "1:02:05" ; 125 -> "2:05" */
fun formatTime(totalSec: Int): String {
    val t = if (totalSec < 0) 0 else totalSec
    val h = t / 3600
    val mi = (t % 3600) / 60
    val sc = t % 60
    return if (h > 0) "$h:${pad2(mi)}:${pad2(sc)}" else "$mi:${pad2(sc)}"
}
