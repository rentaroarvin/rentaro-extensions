package eu.kanade.tachiyomi.animeextension.en.arvinhd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Every field is nullable or defaulted on purpose, so one odd value on one title cannot fail a page.

/** One element of the `episodes` array that a series page embeds in its React Flight data. */
@Serializable
data class EpisodeDto(
    // The id the play endpoint is keyed by; it is not the episode number.
    val id: String,
    val number: Int,
)

/** The `TVSeries` block of a series page's JSON-LD. */
@Serializable
data class SeriesLdDto(
    @SerialName("@type") val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val image: String? = null,
)

/** `GET /api/play/<episodeId>`: what the site's player is allowed to play for this visitor. */
@Serializable
data class PlayDto(
    val access: AccessDto? = null,
    val sources: List<SourceDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class AccessDto(
    // OPEN, LOGIN_REQUIRED, PREMIUM_REQUIRED or SOURCE_UNAVAILABLE.
    val status: String? = null,
    val via: String? = null,
    // Only read for the "used up" message; its type is unconfirmed, so it is kept as raw JSON.
    val freeResetsAt: JsonElement? = null,
)

@Serializable
data class SourceDto(
    val variant: String? = null,
    val type: String? = null,
    val url: String? = null,
    val height: Int? = null,
)

@Serializable
data class SubtitleDto(
    val url: String? = null,
    val lang: String? = null,
    val label: String? = null,
)
