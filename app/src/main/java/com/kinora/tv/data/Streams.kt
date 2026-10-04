package com.kinora.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** Resultado da busca de fontes: fontes diretas (http/https) e quantas foram ignoradas. */
class StreamResult(val found: List<StreamOption>, val unsupported: Int)

/**
 * Resolucao de fontes de video nos addons (usada pela tela de detalhes e pelo player)
 * e de legendas dos addons com o recurso "subtitles".
 */
object Streams {

    private fun headers(s: JSONObject): Map<String, String> {
        val rq = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in rq.keys()) out[k] = rq.str(k)
        return out
    }

    suspend fun fetch(addons: List<Addon>, kind: String, videoId: String): StreamResult = coroutineScope {
        val sources = addons.filter { AddonStore.supportsResource(it, "stream", kind, videoId) }
        val results = sources.map { a ->
            async { Pair(a, Net.getJson(a.url + "/stream/" + urlEncode(kind) + "/" + urlEncode(videoId) + ".json")) }
        }.awaitAll()
        val found = ArrayList<StreamOption>()
        var unsupported = 0
        for ((a, res) in results) {
            if (!res.ok) continue
            for (s in res.data?.optJSONArray("streams").objects()) {
                val url = s.str("url")
                if (url.isNotEmpty() && url.lowercase().startsWith("http")) {
                    val nm = s.str("name").replace("\n", " ")
                    var tt = s.str("title")
                    if (tt.isEmpty()) tt = s.str("description")
                    tt = tt.replace("\n", " | ")
                    var label = "[${a.name}] "
                    if (nm.isNotEmpty()) label += "$nm  "
                    label += tt
                    if (label.length > 120) label = label.take(117) + "..."
                    val subs = ArrayList<SubTrack>()
                    for (sb in s.optJSONArray("subtitles").objects()) {
                        val su = sb.str("url")
                        if (su.isNotEmpty()) subs.add(SubTrack(su, sb.str("lang"), a.name))
                    }
                    found.add(StreamOption(url, label, headers(s), false, a.name, a.url, subs, streamQuality("$label $nm")))
                } else {
                    unsupported++
                }
            }
        }
        StreamResult(found, unsupported)
    }

    /** Qualidade preferida: as fontes mais proximas dela vem primeiro (ex.: 1080 -> 1080, 720, 480, 4K). */
    fun sortByQuality(list: List<StreamOption>, pref: String): List<StreamOption> {
        if (pref == "auto") return list
        val target = pref.toIntOrNull() ?: return list
        return list.sortedBy { if (it.quality > 0) kotlin.math.abs(it.quality - target) else 2000 }
    }

    /** Legendas dos addons com o recurso "subtitles" (no maximo 60, espera ate 6 s). */
    suspend fun fetchSubs(addons: List<Addon>, kind: String, videoId: String): List<SubTrack> = coroutineScope {
        val sources = addons.filter { AddonStore.supportsResource(it, "subtitles", kind, videoId) }
        val results = sources.map { a ->
            async { Pair(a, Net.getJson(a.url + "/subtitles/" + urlEncode(kind) + "/" + urlEncode(videoId) + ".json", 6000)) }
        }.awaitAll()
        val out = ArrayList<SubTrack>()
        for ((a, res) in results) {
            if (!res.ok) continue
            for (sb in res.data?.optJSONArray("subtitles").objects()) {
                val su = sb.str("url")
                if (su.isNotEmpty() && out.size < 60) out.add(SubTrack(su, sb.str("lang"), a.name))
            }
        }
        out
    }

    const val DEMO_URL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
}
