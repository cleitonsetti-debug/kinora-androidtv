package com.kinora.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AddonConfig(val url: String, val enabled: Boolean)

data class Settings(
    val lang: String = "pt",
    val resume: Boolean = true,
    val autoPick: Boolean = false,
    val subLang: String = "off",      // off | pt | en | es
    val audioLang: String = "auto",   // auto | pt | en | es
    val autoNext: Boolean = true,
    val intro: Boolean = true,
)

/** Persistencia local (equivale ao registry do Roku): addons, ajustes e historico. */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kinora", Context.MODE_PRIVATE)

    // ---------------- addons ----------------
    fun loadAddonConfig(): List<AddonConfig> {
        val raw = prefs.getString("addons", null) ?: return defaultAddonConfig()
        return try {
            val arr = JSONArray(raw)
            val list = arr.objects().map { AddonConfig(it.str("url"), it.optBoolean("enabled", false)) }
                .filter { it.url.isNotEmpty() }
            if (list.isEmpty()) defaultAddonConfig() else list
        } catch (e: Exception) {
            defaultAddonConfig()
        }
    }

    fun saveAddonConfig(list: List<AddonConfig>) {
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("url", a.url).put("enabled", a.enabled))
        prefs.edit().putString("addons", arr.toString()).apply()
    }

    // ---------------- ajustes ----------------
    fun loadSettings(): Settings {
        val raw = prefs.getString("settings", null) ?: return Settings()
        return try {
            val o = JSONObject(raw)
            val lang = o.str("lang").takeIf { it in I18n.codes } ?: "pt"
            val sl = o.str("subLang").takeIf { it in listOf("off", "pt", "en", "es") } ?: "off"
            val al = o.str("audioLang").takeIf { it in listOf("auto", "pt", "en", "es") } ?: "auto"
            Settings(
                lang, o.optBoolean("resume", true), o.optBoolean("autoPick", false),
                sl, al, o.optBoolean("autoNext", true), o.optBoolean("intro", true),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    fun saveSettings(s: Settings) {
        val o = JSONObject().put("lang", s.lang).put("resume", s.resume).put("autoPick", s.autoPick)
            .put("subLang", s.subLang).put("audioLang", s.audioLang)
            .put("autoNext", s.autoNext).put("intro", s.intro)
        prefs.edit().putString("settings", o.toString()).apply()
    }

    // ---------------- historico (continuar assistindo) ----------------
    fun loadHistory(): List<Info> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        return try {
            JSONArray(raw).objects().map { Info.fromJson(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<Info>) {
        val arr = JSONArray()
        for (h in list) arr.put(h.toJson())
        prefs.edit().putString("history", arr.toString()).apply()
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

    companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io"
        fun defaultAddonConfig() = listOf(AddonConfig(CINEMETA, true))
    }
}
