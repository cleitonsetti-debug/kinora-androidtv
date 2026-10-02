package com.kinora.tv.data

import org.json.JSONObject

/**
 * Estrutura enxuta de um titulo (equivalente ao "info" do app Roku).
 * Itens de "Continuar assistindo" tambem trazem videoId/season/episode/position/duration.
 */
data class Info(
    val id: String,
    val kind: String,
    val name: String,
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val year: String = "",
    val rating: String = "",
    val genres: String = "",
    val addon: String = "",
    val videoId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val position: Int = 0,
    val duration: Int = 0,
    val ts: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("id", id)
        put("kind", kind)
        put("name", name)
        put("poster", poster)
        put("background", background)
        put("description", description)
        put("year", year)
        put("rating", rating)
        put("genres", genres)
        put("addon", addon)
        put("season", season)
        put("episode", episode)
        put("position", position)
        put("duration", duration)
        put("ts", ts)
    }

    companion object {
        fun fromJson(o: JSONObject): Info = Info(
            id = o.str("id"),
            kind = o.str("kind"),
            name = o.str("name"),
            poster = o.str("poster"),
            background = o.str("background"),
            description = o.str("description"),
            year = o.str("year"),
            rating = o.str("rating"),
            genres = o.str("genres"),
            addon = o.str("addon"),
            videoId = o.str("videoId"),
            season = o.int("season"),
            episode = o.int("episode"),
            position = o.int("position"),
            duration = o.int("duration"),
            ts = o.optLong("ts", 0L),
        )

        /** Conversao Stremio meta -> Info. */
        fun fromMeta(meta: JSONObject, base: String, defKind: String): Info {
            var kind = meta.str("type")
            if (kind.isEmpty()) kind = defKind
            return Info(
                id = meta.str("id"),
                kind = kind,
                name = meta.str("name"),
                poster = meta.str("poster"),
                background = meta.str("background"),
                description = meta.str("description"),
                year = meta.str("releaseInfo"),
                rating = meta.str("imdbRating"),
                genres = joinList(meta.opt("genres"), 3),
                addon = base,
            )
        }
    }
}

data class Episode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String,
    val overview: String,
)

data class StreamOption(
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    val demo: Boolean = false,
)

/** Pedido de reproducao enviado da tela de detalhes para o player. */
data class PlayRequest(
    val url: String,
    val format: String,
    val title: String,
    val headers: Map<String, String>,
    val videoId: String,
    val info: Info,
    val season: Int,
    val episode: Int,
    val nextEp: Episode?,
)
