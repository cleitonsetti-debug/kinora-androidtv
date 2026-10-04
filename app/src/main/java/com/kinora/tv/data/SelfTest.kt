package com.kinora.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Autoteste: confere no proprio aparelho as funcoes puras do app.
 * Mostrado em Ajustes > Sobre e ajuda > Diagnostico e autoteste.
 */
object SelfTest {
    class Result(val total: Int, val failures: List<String>)

    fun run(store: Store, t: Strings, currentRelease: String): Result {
        var total = 0
        val fails = ArrayList<String>()
        fun chk(name: String, cond: Boolean) {
            total++
            if (!cond) fails.add(name)
        }
        fun safe(name: String, block: () -> Boolean) {
            val ok = try {
                block()
            } catch (e: Exception) {
                false
            }
            chk(name, ok)
        }

        // texto e URLs
        chk("urlEncode espaco", urlEncode("a b") == "a%20b")
        chk("urlEncode dois-pontos", urlEncode("tt1:1:2") == "tt1%3A1%3A2")
        chk("urlEncode acento (UTF-8)", urlEncode("é") == "%C3%A9")
        chk("normalizeAddonUrl stremio://", normalizeAddonUrl("stremio://x.com/manifest.json") == "https://x.com")
        chk("normalizeAddonUrl barra final", normalizeAddonUrl("https://x.com/a/") == "https://x.com/a")
        chk("normalizeAddonUrl esquema invalido", normalizeAddonUrl("ftp://x.com") == "")
        chk("hostOf", hostOf("https://abc.example.com/token/stream/x.json") == "abc.example.com")
        chk("netTarget sem caminho de configuracao", netTarget("https://abc.example.com/token123/stream/movie/tt1.json") == "abc.example.com/stream/")

        // tempo
        chk("formatTime 125", formatTime(125) == "2:05")
        chk("formatTime 3725", formatTime(3725) == "1:02:05")
        chk("formatTime 5", formatTime(5) == "0:05")
        chk("formatClock", formatClock().length == 8)

        // conversoes
        chk("asStr numero", asStr(5) == "5")
        chk("asStr nulo", asStr(null) == "")
        chk("toInt string", toInt("12") == 12)
        chk("toInt float", toInt(3.7) == 3)
        safe("joinList") { joinList(JSONArray(listOf("a", "b", "c")), 2) == "a, b" }

        // fontes, qualidade e formatos
        chk("guessStreamFormat hls", guessStreamFormat("http://x/a.m3u8?t=1") == "hls")
        chk("guessStreamFormat mp4", guessStreamFormat("http://x/a.mp4") == "mp4")
        chk("guessStreamFormat mkv", guessStreamFormat("http://x/a.mkv") == "mkv")
        chk("guessStreamFormat desconhecido", guessStreamFormat("http://x/a") == "")
        chk("streamQuality 1080p", streamQuality("Filme 1080p WEB") == 1080)
        chk("streamQuality 4K", streamQuality("Filme 4K HDR") == 2160)
        chk("streamQuality desconhecida", streamQuality("Filme") == 0)

        // idiomas
        chk("langMatches por/pt", langMatches("por", "pt"))
        chk("langMatches pob/pt", langMatches("pob", "pt"))
        chk("langMatches eng/pt", !langMatches("eng", "pt"))
        chk("langMatches est/es (estonio)", !langMatches("est", "es"))
        chk("langMatches spa/es", langMatches("spa", "es"))
        chk("prefCode pob", prefCode("pob") == "pt")
        chk("prefCode fra", prefCode("fra") == "fra")

        // conteudo adulto, PIN
        safe("isAdultMeta sim") { isAdultMeta(JSONObject().put("genres", JSONArray(listOf("Drama", "Adult")))) }
        safe("isAdultMeta nao") { !isAdultMeta(JSONObject().put("genres", JSONArray(listOf("Drama")))) }
        chk("pinHash tamanho", pinHash("1234").length == 64)
        chk("pinHash difere", pinHash("1234") != pinHash("1235"))

        // traducoes
        chk("i18n traduz", t("nav_home") != "nav_home")
        chk("i18n categorias", t("cat_general") != "cat_general" && t("st_lang") != "st_lang")

        // perfis, cores e ajustes
        chk("accentFor estavel", accentFor("tt1") == accentFor("tt1"))
        chk("initialOf", initialOf("  kinora") == "K")
        chk("ajustes padrao: salto", Settings().jump == 10)
        safe("perfis padrao") { store.loadProfiles().isNotEmpty() }
        chk("versionNewer maior", versionNewer("1.5.10", "1.5.2") && versionNewer("2.0", "1.9.9"))
        chk("versionNewer igual/menor", !versionNewer("1.5.2", "1.5.2") && !versionNewer("1.4.9", "1.5.0"))
        chk("versao instalada", currentRelease.isNotEmpty())
        safe("ajustes ida e volta") {
            val s = store.loadSettings()
            store.saveSettings(s)
            store.loadSettings() == s
        }

        return Result(total, fails)
    }
}
