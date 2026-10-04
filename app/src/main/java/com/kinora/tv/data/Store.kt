package com.kinora.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AddonConfig(val url: String, val enabled: Boolean)

/** Ajustes (por perfil). O PIN fica fora, compartilhado entre os perfis. */
data class Settings(
    val lang: String = "pt",
    val resume: Boolean = true,
    val autoPick: Boolean = false,
    val subLang: String = "off",      // off | pt | en | es
    val audioLang: String = "auto",   // auto | pt | en | es
    val autoNext: Boolean = true,
    val intro: Boolean = true,
    val quality: String = "auto",     // auto | 1080 | 720 | 480
    val hideAdult: Boolean = true,
    val jump: Int = 10,               // 10 | 15 | 30
    val zoom: Boolean = false,
    val ambient: Boolean = true,
    val carousel: Boolean = true,
    val p2p: Boolean = true,          // fontes P2P (infoHash)
)

/**
 * Persistencia local (equivale ao registry do Roku).
 * Chaves compartilhadas entre os perfis: addons, profiles, pin, lastprofile, update.
 * As demais ganham o prefixo do perfil; o perfil "main" usa as chaves sem prefixo
 * (assim os dados de antes viram o perfil Principal).
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kinora", Context.MODE_PRIVATE)

    /** Perfil em uso (define o prefixo das chaves). */
    var profileId: String = "main"

    private fun key(k: String): String {
        if (k in SHARED) return k
        if (profileId.isEmpty() || profileId == "main") return k
        return profileId + "_" + k
    }

    private fun readRaw(k: String): String? = prefs.getString(key(k), null)
    private fun writeRaw(k: String, v: String) = prefs.edit().putString(key(k), v).apply()

    private fun readArray(k: String): JSONArray? = try {
        readRaw(k)?.let { JSONArray(it) }
    } catch (e: Exception) {
        null
    }

    private fun readObject(k: String): JSONObject? = try {
        readRaw(k)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    // ---------------- addons (compartilhados) ----------------
    fun loadAddonConfig(): List<AddonConfig> {
        val arr = readArray("addons") ?: return defaultAddonConfig()
        val list = arr.objects().map { AddonConfig(it.str("url"), it.optBoolean("enabled", false)) }
            .filter { it.url.isNotEmpty() }
        return if (list.isEmpty()) defaultAddonConfig() else list
    }

    fun saveAddonConfig(list: List<AddonConfig>) {
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("url", a.url).put("enabled", a.enabled))
        writeRaw("addons", arr.toString())
    }

    // ---------------- ajustes (por perfil) ----------------
    fun loadSettings(): Settings {
        val o = readObject("settings") ?: return Settings()
        val d = Settings()
        val lang = o.str("lang").takeIf { it in I18n.codes } ?: d.lang
        val sl = o.str("subLang").takeIf { it in listOf("off", "pt", "en", "es") } ?: d.subLang
        val al = o.str("audioLang").takeIf { it in listOf("auto", "pt", "en", "es") } ?: d.audioLang
        val q = o.str("quality").takeIf { it in listOf("auto", "1080", "720", "480") } ?: d.quality
        val j = o.int("jump").takeIf { it == 10 || it == 15 || it == 30 } ?: d.jump
        return Settings(
            lang = lang,
            resume = o.optBoolean("resume", d.resume),
            autoPick = o.optBoolean("autoPick", d.autoPick),
            subLang = sl,
            audioLang = al,
            autoNext = o.optBoolean("autoNext", d.autoNext),
            intro = o.optBoolean("intro", d.intro),
            quality = q,
            hideAdult = o.optBoolean("hideAdult", d.hideAdult),
            jump = j,
            zoom = o.optBoolean("zoomV2", d.zoom),
            ambient = o.optBoolean("ambient", d.ambient),
            carousel = o.optBoolean("carousel", d.carousel),
            p2p = o.optBoolean("p2p", d.p2p),
        )
    }

    fun saveSettings(s: Settings) {
        val o = JSONObject()
            .put("lang", s.lang).put("resume", s.resume).put("autoPick", s.autoPick)
            .put("subLang", s.subLang).put("audioLang", s.audioLang)
            .put("autoNext", s.autoNext).put("intro", s.intro)
            .put("quality", s.quality).put("hideAdult", s.hideAdult).put("jump", s.jump)
            .put("zoomV2", s.zoom).put("ambient", s.ambient).put("carousel", s.carousel)
            .put("p2p", s.p2p)
        writeRaw("settings", o.toString())
    }

    // ---------------- PIN (compartilhado; guarda so o hash) ----------------
    fun loadPinHash(): String = readObject("pin")?.str("h") ?: ""

    fun savePinHash(h: String) = writeRaw("pin", JSONObject().put("h", h).toString())

    // ---------------- perfis ----------------
    fun loadProfiles(): List<Profile> {
        val list = readArray("profiles").objects().map {
            Profile(it.str("id"), it.str("name"), it.int("color"), it.optBoolean("kids", false))
        }.filter { it.id.isNotEmpty() }
        return list.ifEmpty { listOf(Profile("main", "Principal", 0, false)) }
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("kids", p.kids))
        writeRaw("profiles", arr.toString())
    }

    fun findProfile(id: String): Profile? = loadProfiles().firstOrNull { it.id == id }

    fun lastProfile(): String = readObject("lastprofile")?.str("id") ?: ""

    fun saveLastProfile(id: String) = writeRaw("lastprofile", JSONObject().put("id", id).toString())

    /** Apaga os dados de um perfil (todas as chaves com o prefixo dele). */
    fun deleteProfileData(id: String) {
        if (id.isEmpty() || id == "main") return
        val ed = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith(id + "_")) ed.remove(k)
        ed.apply()
    }

    // ---------------- aviso de versao (compartilhado) ----------------
    fun seenUpdate(): String = readObject("update")?.str("seen") ?: ""

    fun saveSeenUpdate(v: String) = writeRaw("update", JSONObject().put("seen", v).toString())

    // ---------------- historico (continuar assistindo) ----------------
    fun loadHistory(): List<Info> = readArray("history").objects().map { Info.fromJson(it) }

    private fun saveHistory(list: List<Info>) {
        val arr = JSONArray()
        for (h in list) arr.put(h.toJson())
        writeRaw("history", arr.toString())
    }

    fun upsertHistory(entry: Info) {
        val out = ArrayList<Info>()
        out.add(entry)
        for (h in loadHistory()) {
            if (h.videoId != entry.videoId) out.add(h)
            if (out.size >= 20) break
        }
        saveHistory(out)
    }

    fun removeHistory(videoId: String) {
        saveHistory(loadHistory().filter { it.videoId != videoId })
    }

    fun clearHistory() = saveHistory(emptyList())

    fun getSavedPosition(videoId: String): Int =
        loadHistory().firstOrNull { it.videoId == videoId }?.position ?: 0

    // ---------------- Minha lista (entradas enxutas) ----------------
    fun loadFavorites(): List<Info> = readArray("favorites").objects().map { Info.fromJson(it) }.filter { it.id.isNotEmpty() }

    fun isFavorite(id: String): Boolean = loadFavorites().any { it.id == id }

    /** Adiciona/remove; devolve true se ficou na lista. */
    fun toggleFavorite(info: Info): Boolean {
        val list = loadFavorites()
        val found = list.any { it.id == info.id }
        val out = ArrayList(list.filter { it.id != info.id })
        if (!found) {
            out.add(0, Info(id = info.id, kind = info.kind, name = info.name, poster = info.poster, year = info.year, rating = info.rating, addon = info.addon))
            while (out.size > 30) out.removeAt(out.size - 1)
        }
        val arr = JSONArray()
        for (f in out) {
            arr.put(
                JSONObject().put("id", f.id).put("kind", f.kind).put("name", f.name).put("poster", f.poster)
                    .put("year", f.year).put("rating", f.rating).put("addon", f.addon)
            )
        }
        writeRaw("favorites", arr.toString())
        return !found
    }

    fun clearFavorites() = writeRaw("favorites", "[]")

    // ---------------- episodios assistidos ----------------
    fun loadWatched(): Set<String> = readArray("watched").strings().toHashSet()

    fun markWatched(videoId: String) {
        if (videoId.isEmpty()) return
        val list = ArrayList(readArray("watched").strings())
        if (videoId in list) return
        list.add(videoId)
        while (list.size > 300) list.removeAt(0)
        writeRaw("watched", JSONArray(list).toString())
    }

    fun clearWatched() = writeRaw("watched", "[]")

    // ---------------- pesquisas recentes ----------------
    fun loadSearches(): List<String> = readArray("searches").strings()

    fun addSearch(q: String) {
        if (q.length < 2) return
        val out = ArrayList<String>()
        out.add(q)
        for (x in loadSearches()) if (x.lowercase() != q.lowercase() && out.size < 8) out.add(x)
        writeRaw("searches", JSONArray(out).toString())
    }

    fun clearSearches() = writeRaw("searches", "[]")

    // ---------------- legenda/audio escolhidos por titulo ----------------
    fun loadTrackPrefs(id: String): Pair<String, String> {
        val e = readObject("trackprefs")?.optJSONObject(id) ?: return Pair("", "")
        return Pair(e.str("subLang"), e.str("audioLang"))
    }

    fun saveTrackPref(id: String, k: String, value: String) {
        if (id.isEmpty()) return
        val d = readObject("trackprefs") ?: JSONObject()
        val e = d.optJSONObject(id) ?: JSONObject()
        e.put(k, value)
        d.put(id, e)
        if (d.length() > 60) {
            val first = d.keys().asSequence().firstOrNull { it != id }
            if (first != null) d.remove(first)
        }
        writeRaw("trackprefs", d.toString())
    }

    companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io"
        private val SHARED = setOf("addons", "profiles", "pin", "lastprofile", "update")
        fun defaultAddonConfig() = listOf(AddonConfig(CINEMETA, true))
    }
}
