package eu.kanade.tachiyomi.animeextension.en.arvinhp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Every field is nullable or defaulted on purpose, so one odd value on one title cannot fail a page.

/** One title in a listing or search result. `slug` is the identity; `episode_id` is not (it follows resume state). */
@Serializable
data class CardDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
)

/** `GET /{locale}/discover-more`: top-level `{items, total, done}`, with no `data` wrapper. */
@Serializable
data class DiscoverPageDto(
    val items: List<CardDto> = emptyList(),
    val done: Boolean? = null,
)

/** `GET /api/v1/search`: `{data: {items}}`. */
@Serializable
data class SearchResponseDto(val data: SearchDataDto? = null)

@Serializable
data class SearchDataDto(val items: List<CardDto> = emptyList())

/** `GET /api/v1/pages/show`: the title and its complete episode list. */
@Serializable
data class ShowResponseDto(val data: ShowPageDto? = null)

@Serializable
data class ShowPageDto(
    val show: ShowDto? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class ShowDto(
    val title: String? = null,
    val synopsis: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    // Null on every title seen, so its type is unconfirmed; it is only used when it turns out to be text.
    @SerialName("genre_label") val genreLabel: JsonElement? = null,
    @SerialName("planned_episodes") val plannedEpisodes: Int? = null,
    @SerialName("episodes_published") val episodesPublished: Int? = null,
)

@Serializable
data class EpisodeDto(
    // The playback session is keyed by this UUID, not by the episode number or the show id.
    val id: String? = null,
    @SerialName("episode_no") val episodeNo: Int? = null,
    @SerialName("display_order") val displayOrder: Int? = null,
    val title: String? = null,
    @SerialName("has_media") val hasMedia: Boolean? = null,
)

/** Body of `POST /api/v1/playback/sessions`. */
@Serializable
data class PlaybackRequestDto(@SerialName("episode_id") val episodeId: String)

/**
 * `POST /api/v1/playback/sessions`. HTTP 201 does not mean playable, so `gate`, `mode` and the URL are read.
 * The response also carries the AES key and a heartbeat token; neither is modelled, so neither is read.
 */
@Serializable
data class PlaybackResponseDto(val data: PlaybackDto? = null)

@Serializable
data class PlaybackDto(
    val access: String? = null,
    val gate: String? = null,
    val mode: String? = null,
    val entitlement: String? = null,
    @SerialName("playback_url") val playbackUrl: String? = null,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class SubtitleDto(
    val locale: String? = null,
    val url: String? = null,
)

/** `GET /api/v1/session`: `user` is null for a guest. */
@Serializable
data class SessionResponseDto(val data: SessionDto? = null)

@Serializable
data class SessionDto(
    val user: JsonElement? = null,
    val kicked: Boolean? = null,
)

/** Failed calls answer `{"error": {"code": "..."}}`; it is read as raw JSON so a differently shaped error cannot throw. */
@Serializable
data class ErrorEnvelopeDto(val error: JsonElement? = null)

/** Body of `POST /api/v1/auth/email-otp`. No defaults: the host's Json may skip default-valued fields. */
@Serializable
data class OtpRequestDto(
    val email: String,
    val locale: String,
)

/** Body of `POST /api/v1/auth/email-otp/verify`. */
@Serializable
data class OtpVerifyDto(
    val email: String,
    val code: String,
    @SerialName("accept_terms") val acceptTerms: Boolean,
    @SerialName("terms_version") val termsVersion: String,
)
