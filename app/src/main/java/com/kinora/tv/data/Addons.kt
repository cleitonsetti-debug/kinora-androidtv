package com.kinora.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Um addon em memoria: { url, enabled, name, manifest, ok } */
data class Addon(
    val url: String,
    val enabled: Boolean,
    val name: String,
    val manifest: JSONObject?,
    val ok: Boolean,
)

/** Catalogo usado nas linhas da tela inicial / busca. */
data class CatalogRef(
    val base: String,
    val kind: String,
    val id: String,
    val title: String,
    val hasGenre: Boolean,
    val extra: String = "",
)

/** Catalogo disponivel no filtro (Filmes/Series). */
data class FilterCatalog(
    val base: String,
    val kind: String,
    val id: String,
    val name: String,
    val genres: List<String>,
    val hasGenre: Boolean,
    val genreReq: Boolean,
)

object AddonStore {

    fun countEnabled(addons: List<Addon>) = addons.count { it.enabled }

    fun anyFailed(addons: List<Addon>) = addons.any { !it.ok }

    private fun requiredExtras(cat: JSONObject): List<String> {
        val names = ArrayList<String>()
        for (e in cat.optJSONArray("extra").objects()) {
            if (e.optBoolean("isRequired", false)) names.add(e.str("name"))
        }
        names.addAll(cat.optJSONArray("extraRequired").strings())
        return names
    }

    private fun catalogHasExtra(cat: JSONObject, name: String): Boolean {
        if (cat.optJSONArray("extra").objects().any { it.str("name") == name }) return true
        return cat.optJSONArray("extraSupported").strings().contains(name)
    }

    /**
     * Lista catalogos utilizaveis.
     *  needSearch=false -> catalogos sem extras obrigatorios (linhas da tela inicial)
     *  needSearch=true  -> catalogos que aceitam busca
     */
    fun listCatalogs(addons: List<Addon>, kindFilter: String, needSearch: Boolean, maxItems: Int, s: Strings): List<CatalogRef> {
        val out = ArrayList<CatalogRef>()
        val multi = countEnabled(addons) > 1
        for (addon in addons) {
            if (!addon.enabled || !addon.ok) continue
            for (cat in addon.manifest?.optJSONArray("catalogs").objects()) {
                val kind = cat.str("type")
                val cid = cat.str("id")
                var usable = kind.isNotEmpty() && cid.isNotEmpty()
                if (usable && kindFilter.isNotEmpty() && kind != kindFilter) usable = false
                if (usable) {
                    val req = requiredExtras(cat)
                    if (needSearch) {
                        if (!catalogHasExtra(cat, "search")) usable = false
                        if (req.any { it != "search" }) usable = false
                    } else {
                        if (req.isNotEmpty()) usable = false
                    }
                }
                if (usable && out.size < maxItems) {
                    var nm = cat.str("name")
                    if (nm.isEmpty()) nm = cid
                    var title = s.kindLabel(kind) + " - " + nm
                    if (multi) title += " (" + addon.name + ")"
                    out.add(CatalogRef(addon.url, kind, cid, title, catalogHasExtra(cat, "genre")))
                }
            }
        }
        return out
    }

    private fun typeListHas(list: JSONArray?, kind: String): Boolean {
        if (list == null || list.length() == 0) return true
        return list.strings().contains(kind)
    }

    private fun prefixMatches(list: JSONArray?, id: String): Boolean {
        if (list == null || list.length() == 0) return true
        return list.strings().any { id.startsWith(it) }
    }

    /** O addon oferece o recurso (stream/meta/...) para esse tipo e id? */
    fun supportsResource(addon: Addon, resName: String, kind: String, id: String): Boolean {
        if (!addon.enabled || !addon.ok) return false
        val mf = addon.manifest ?: return false
        val resources = mf.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val r = resources.opt(i)
            var rname = ""
            var rtypes = mf.optJSONArray("types")
            var rprefixes = mf.optJSONArray("idPrefixes")
            if (r is String) {
                rname = r
            } else if (r is JSONObject) {
                rname = r.str("name")
                r.optJSONArray("types")?.let { rtypes = it }
                r.optJSONArray("idPrefixes")?.let { rprefixes = it }
            }
            if (rname == resName && typeListHas(rtypes, kind) && prefixMatches(rprefixes, id)) return true
        }
        return false
    }

    fun findMetaBase(addons: List<Addon>, kind: String, id: String, preferred: String): String {
        addons.firstOrNull { it.url == preferred && supportsResource(it, "meta", kind, id) }?.let { return it.url }
        addons.firstOrNull { supportsResource(it, "meta", kind, id) }?.let { return it.url }
        return ""
    }

    private fun genreOptions(cat: JSONObject): List<String> {
        val out = ArrayList<String>()
        for (e in cat.optJSONArray("extra").objects()) {
            if (e.str("name") == "genre") out.addAll(e.optJSONArray("options").strings())
        }
        return out
    }

    /**
     * Catalogos de um tipo (movie/series) que podem ser usados no filtro:
     * sem extras obrigatorios, exceto "genre" (ex.: o catalogo por ano do Cinemeta)
     */
    fun listFilterCatalogs(addons: List<Addon>, kind: String): List<FilterCatalog> {
        val out = ArrayList<FilterCatalog>()
        val multi = countEnabled(addons) > 1
        for (addon in addons) {
            if (!addon.enabled || !addon.ok) continue
            for (cat in addon.manifest?.optJSONArray("catalogs").objects()) {
                if (cat.str("type") != kind || cat.str("id").isEmpty()) continue
                var usable = true
                var genreReq = false
                for (rn in requiredExtras(cat)) {
                    if (rn == "genre") genreReq = true else usable = false
                }
                if (!usable) continue
                var nm = cat.str("name")
                if (nm.isEmpty()) nm = cat.str("id")
                if (multi) nm += " (" + addon.name + ")"
                out.add(FilterCatalog(addon.url, kind, cat.str("id"), nm, genreOptions(cat), catalogHasExtra(cat, "genre"), genreReq))
            }
        }
        return out
    }

    fun findAddon(addons: List<Addon>, url: String): Addon? = addons.firstOrNull { it.url == url }

    /** Texto de detalhes de um addon (varias linhas). */
    fun addonDetailsText(a: Addon, t: Strings): String {
        val lines = ArrayList<String>()
        val st = when {
            !a.ok -> t("state_err")
            !a.enabled -> t("state_off")
            else -> t("state_on")
        }
        lines.add("${a.name}  [$st]")
        val mf = a.manifest
        if (a.ok && mf != null) {
            val v = mf.str("version")
            if (v.isNotEmpty()) lines.add(t("ad_version") + ": " + v)
            var d = mf.str("description")
            if (d.isEmpty()) d = t("ad_nodesc")
            if (d.length > 220) d = d.take(217) + "..."
            lines.add(d)
            val types = joinList(mf.opt("types"), 8)
            if (types.isNotEmpty()) lines.add(t("ad_types") + ": " + types)
            val names = ArrayList<String>()
            val res = mf.optJSONArray("resources")
            if (res != null) {
                for (i in 0 until res.length()) {
                    val r = res.opt(i)
                    if (r is JSONObject) names.add(r.str("name")) else names.add(asStr(r))
                }
            }
            if (names.isNotEmpty()) lines.add(t("ad_resources") + ": " + names.joinToString(", "))
            val cats = mf.optJSONArray("catalogs")
            if (cats != null) lines.add(t("ad_catalogs") + ": " + cats.length())
        }
        lines.add(t("ad_url") + ": " + a.url)
        return lines.joinToString("\n")
    }

    /** Corpo do painel de detalhes do addon (descricao, tipos, recursos, catalogos, endereco). */
    fun addonBody(a: Addon, t: Strings): String {
        val lines = ArrayList<String>()
        val mf = a.manifest
        if (a.ok && mf != null) {
            var d = mf.str("description")
            if (d.isEmpty()) d = t("ad_nodesc")
            if (d.length > 260) d = d.take(257) + "..."
            lines.add(d)
            lines.add("")
            val types = joinList(mf.opt("types"), 8)
            if (types.isNotEmpty()) lines.add(t("ad_types") + ": " + types)
            val names = ArrayList<String>()
            val res = mf.optJSONArray("resources")
            if (res != null) {
                for (i in 0 until res.length()) {
                    val r = res.opt(i)
                    if (r is JSONObject) names.add(r.str("name")) else names.add(asStr(r))
                }
            }
            if (names.isNotEmpty()) lines.add(t("ad_resources") + ": " + names.joinToString(", "))
            val cats = mf.optJSONArray("catalogs")
            if (cats != null) lines.add(t("ad_catalogs") + ": " + cats.length())
        } else {
            lines.add(t("ad_state_err"))
        }
        lines.add(t("ad_url") + ": " + hostOf(a.url))
        return lines.joinToString("\n")
    }

    /** Endereco da pagina de configuracao (addons com behaviorHints.configurable). */
    fun addonConfigUrl(a: Addon): String {
        if (!a.ok) return ""
        val bh = a.manifest?.optJSONObject("behaviorHints") ?: return ""
        return if (bh.optBoolean("configurable", false)) a.url + "/configure" else ""
    }
}
