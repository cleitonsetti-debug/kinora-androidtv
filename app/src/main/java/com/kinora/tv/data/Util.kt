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

// ---------------------------------------------------------------------------
// Qualidade, conteudo adulto, PIN, preferencias de faixa
// ---------------------------------------------------------------------------
fun streamQuality(text: String): Int {
    val t = text.lowercase()
    return when {
        "2160" in t || "4k" in t || "uhd" in t -> 2160
        "1080" in t -> 1080
        "720" in t -> 720
        "480" in t -> 480
        "360" in t -> 360
        else -> 0
    }
}

fun isAdultMeta(meta: JSONObject): Boolean =
    meta.optJSONArray("genres").strings().any { val g = it.lowercase(); g == "adult" || g == "erotic" || g == "erotica" }

fun filterAdultGenres(list: List<String>): List<String> = list.filter { it.lowercase() != "adult" }

/** SHA-256 de "kinora:" + PIN, em hexadecimal (igual ao app Roku). */
fun pinHash(pin: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(("kinora:" + pin).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** "pob" -> "pt", "eng" -> "en"; outros idiomas ficam com o codigo original. */
fun prefCode(lang: String): String = when {
    langMatches(lang, "pt") -> "pt"
    langMatches(lang, "en") -> "en"
    langMatches(lang, "es") -> "es"
    else -> lang.lowercase()
}

// ---------------------------------------------------------------------------
// Cor de destaque (acento): cada titulo/perfil ganha uma cor da paleta
// ---------------------------------------------------------------------------
val ACCENTS: LongArray = longArrayOf(
    0xFFFF4D6D, 0xFFFF7A29, 0xFFFFB703, 0xFF2EC4B6, 0xFF3A86FF, 0xFF8338EC,
    0xFFFF006E, 0xFF06D6A0, 0xFFEF476F, 0xFF118AB2, 0xFF9B5DE5, 0xFFF15BB5,
)

fun accentByIndex(i: Int): Long {
    val n = ACCENTS.size
    return ACCENTS[((i % n) + n) % n]
}

fun accentFor(id: String): Long {
    var total = 0
    for (c in id) total += c.code
    return accentByIndex(total)
}

fun initialOf(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    return t.substring(0, 1).uppercase()
}

/** true se a versao "a" for maior que "b" (ex.: "1.5.10" > "1.5.2"). */
fun versionNewer(a: String, b: String): Boolean {
    val pa = a.split(".")
    val pb = b.split(".")
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = toInt(pa.getOrNull(i) ?: "0")
        val y = toInt(pb.getOrNull(i) ?: "0")
        if (x > y) return true
        if (x < y) return false
    }
    return false
}

// ---------------------------------------------------------------------------
// Registro de falhas de rede (sem expor caminhos de configuracao dos addons)
// ---------------------------------------------------------------------------
fun hostOf(url: String): String {
    var u = url
    val p = u.indexOf("://")
    if (p >= 0) u = u.substring(p + 3)
    val q = u.indexOf('/')
    if (q >= 0) u = u.substring(0, q)
    return u
}

fun netTarget(url: String): String {
    val res = listOf("/manifest.json", "/catalog/", "/meta/", "/stream/", "/subtitles/").firstOrNull { it in url } ?: ""
    return hostOf(url) + res
}

fun formatClock(): String {
    val c = java.util.Calendar.getInstance()
    fun p(n: Int) = if (n < 10) "0$n" else n.toString()
    return p(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + p(c.get(java.util.Calendar.MINUTE)) + ":" + p(c.get(java.util.Calendar.SECOND))
}
