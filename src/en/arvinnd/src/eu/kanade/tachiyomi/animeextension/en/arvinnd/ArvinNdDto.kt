package eu.kanade.tachiyomi.animeextension.en.arvinnd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Every field is nullable or defaulted on purpose: the site fronts dozens of providers and they do
// not agree on types (ids arrive as strings or numbers), so a field this extension does not need is
// left out rather than risk failing a whole page on one provider's quirk.

/** `GET /home/providers/sections`: the rails of one provider. */
@Serializable
data class SectionsDto(
    val sections: List<SectionDto> = emptyList(),
)

@Serializable
data class SectionDto(
    // Opaque and not stable; always read it from the response, never derive it from the label.
    @SerialName("tab_key") val tabKey: String? = null,
    @SerialName("tab_label") val tabLabel: String? = null,
    val fallback: Boolean? = null,
    val items: List<CardDto> = emptyList(),
)

@Serializable
data class CardDto(
    val title: String? = null,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    // The `/search/import` link that resolves the provider's title to a local detail page.
    @SerialName("watch_url") val watchUrl: String? = null,
    @SerialName("tag_names") val tagNames: JsonElement? = null,
)

/** `GET /search` with `X-Requested-With: XMLHttpRequest`: the local catalogue only. */
@Serializable
data class SearchDto(
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
data class SearchItemDto(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
)

/** One element of the `episodeItemsRaw` array that an episode page embeds as a JavaScript literal. */
@Serializable
data class EpisodeEntryDto(
    val id: JsonElement? = null,
    val number: JsonElement? = null,
    @SerialName("route_episode_number") val routeNumber: JsonElement? = null,
    val title: String? = null,
    @SerialName("watch_url") val watchUrl: String? = null,
    @SerialName("play_url") val playUrl: String? = null,
    // A copy hosted by the site itself. Some providers leave `play_url` empty and mark the entry
    // `is_playable = false` while this still plays, so neither field alone says an entry has no source.
    @SerialName("direct_play_url") val directPlayUrl: String? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    @SerialName("subtitle_url") val subtitleUrl: String? = null,
    @SerialName("multi_subtitles") val subtitles: List<SubtitleDto>? = null,
) {
    val episodeNumber: Float? get() = ((routeNumber ?: number) as? JsonPrimitive)?.content?.toFloatOrNull()
}

@Serializable
data class SubtitleDto(
    @SerialName("language_code") val languageCode: String? = null,
    val label: String? = null,
    @SerialName("subtitle_url") val subtitleUrl: String? = null,
)

/** The reply of an episode's `refresh-source` call: a freshly issued source for one episode. */
@Serializable
data class RefreshDto(
    val ok: Boolean? = null,
    @SerialName("play_url") val playUrl: String? = null,
    @SerialName("direct_play_url") val directPlayUrl: String? = null,
    @SerialName("multi_subtitles") val subtitles: List<SubtitleDto>? = null,
    // Failures answer with a machine-readable code such as `stream_temporarily_unavailable`.
    val error: String? = null,
    val message: String? = null,
)
