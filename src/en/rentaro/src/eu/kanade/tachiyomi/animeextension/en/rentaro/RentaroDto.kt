package eu.kanade.tachiyomi.animeextension.en.rentaro

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ============================== TMDB DTOs ===============================
@Serializable
data class PageDto<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages")
    val totalPages: Int,
)

@Serializable
data class MediaItemDto(
    val id: Int,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_language")
    val originalLanguage: String? = null,
    @SerialName("origin_country")
    val originCountries: List<String> = emptyList(),
    @SerialName("genre_ids")
    val genreIds: List<Int> = emptyList(),
) {
    val realTitle: String
        get() = title ?: name ?: "No Title"
}

@Serializable
data class ExternalIdsDto(
    @SerialName("imdb_id")
    val imdbId: String? = null,
)

@Serializable
data class GenreDto(val name: String)

@Serializable
data class CompanyDto(val name: String)

@Serializable
data class NetworkDto(val name: String)

// ============================= Movie Detail =============================
@Serializable
data class MovieDetailDto(
    val id: Int,
    val title: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("backdrop_path")
    val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
    @SerialName("production_companies")
    val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("origin_country")
    val countries: List<String>? = null,
    @SerialName("original_title")
    val originalTitle: String? = null,
    @SerialName("external_ids")
    val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
    val runtime: Int? = null,
)

// ============================== TV Detail ==============================
@Serializable
data class TvDetailDto(
    val id: Int,
    val name: String,
    val genres: List<GenreDto> = emptyList(),
    val overview: String? = null,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("backdrop_path")
    val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("first_air_date")
    val firstAirDate: String? = null,
    @SerialName("last_air_date")
    val lastAirDate: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val networks: List<NetworkDto> = emptyList(),
    @SerialName("production_companies")
    val productionCompanies: List<CompanyDto> = emptyList(),
    @SerialName("vote_average")
    val voteAverage: Float = 0f,
    @SerialName("origin_country")
    val countries: List<String>? = null,
    @SerialName("original_name")
    val originalName: String? = null,
    @SerialName("external_ids")
    val externalIds: ExternalIdsDto? = null,
    val tagline: String? = null,
    val homepage: String? = null,
)

@Serializable
data class SeasonDto(
    val id: Int,
    val name: String,
    @SerialName("season_number")
    val seasonNumber: Int,
)

// =========================== TV Season Detail ===========================
@Serializable
data class TvSeasonDetailDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val name: String,
    @SerialName("episode_number")
    val episodeNumber: Int,
    @SerialName("air_date")
    val airDate: String? = null,
)

@Serializable
data class SeedDto(
    val seed: String,
)

// ============================ Videasy Decryption ============================
@Serializable
data class VideasyDecryptionDto(
    val status: Int,
    val result: VideasyDecryptedResult,
)

@Serializable
data class VideasyDecryptedResult(
    // Current shape (mb-flix, cdn, myflixerzupcloud, 1movies, lamovie, ...):
    // each source is one playable URL with its own quality label.
    val sources: List<VideasySourceDto>? = null,
    // Legacy: single HLS playlist (some old server paths).
    val url: String? = null,
    // Legacy: multi-quality stream map (primebox, etc.).
    val streams: Map<String, String>? = null,
    // Subtitles for all response types
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class VideasySourceDto(
    val url: String,
    val quality: String? = null,
)

// Subtitle field names vary by Videasy server. Observed/expected variants:
//   {file, label}        — legacy JWPlayer-style
//   {url, lang|language} — most current servers
//   {src, name}          — occasional fallback
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SubtitleDto(
    @JsonNames("file", "src")
    val url: String? = null,
    @JsonNames("label", "lang", "name")
    val language: String? = null,
)

// ======================== Videasy Server ========================
data class VideasyServer(
    val displayName: String,
    val apiBase: String,
    val path: String,
    // Sent as the ?language= query param to Videasy (some backends filter on it).
    val language: String? = null,
    val movieOnly: Boolean = false,
    val mayHave4K: Boolean = false,
    // Display-only audio-language hint shown in the video picker AND
    // alongside each entry in the server preference list.
    val audioLabel: String? = null,
    val qualityFilter: String? = null,
)

// ============================ VidLink ============================
// Independent backend: its own signed-token API, no external decryption
// service. Responses carry either a per-quality map of progressive files or a
// single adaptive HLS playlist, so both shapes are modelled here.
@Serializable
data class VidLinkResponseDto(
    val stream: VidLinkStreamDto? = null,
)

@Serializable
data class VidLinkStreamDto(
    val type: String? = null,
    // Adaptive variant: one master playlist (HLS .m3u8 or DASH .mpd).
    val playlist: String? = null,
    // Progressive variant: quality label ("720") -> file entry.
    val qualities: Map<String, VidLinkQualityDto>? = null,
    val captions: List<VidLinkCaptionDto> = emptyList(),
    // Signed CloudFront cookie required by the DASH CDN. Without it the
    // manifest and every segment return 403.
    val playlistHeaders: Map<String, String>? = null,
    val playbackMetadata: VidLinkPlaybackMetadataDto? = null,
)

@Serializable
data class VidLinkPlaybackMetadataDto(
    val format: String? = null,
    val codecName: String? = null,
    val resolutions: List<String> = emptyList(),
)

@Serializable
data class VidLinkQualityDto(
    val url: String? = null,
    val type: String? = null,
    // "hevc"/"h265" files fail on players without hardware support, so the
    // codec is surfaced in the video label rather than silently offered.
    val codecName: String? = null,
)

@Serializable
data class VidLinkCaptionDto(
    val url: String? = null,
    val language: String? = null,
)

// ============================= Nexus =============================
// Second independent backend. Its API is symmetrically encrypted, so both the
// request and the response bodies are opaque strings; these model the decrypted
// JSON rather than the wire format.
@Serializable
data class NexusEnvelopeDto(
    @SerialName("_hash")
    val hash: String? = null,
)

@Serializable
data class NexusServersDto(
    val servers: List<NexusServerDto> = emptyList(),
)

/**
 * One selectable Nexus scraper.
 *
 * Not a wire type: this is the local catalogue backing the provider
 * preference, so the picker can be built without first calling the API.
 *
 * @param scraper value /api/sources expects as its `provider`
 * @param label backend display name, redundant tag trimmed
 * @param hitRate how many of four probe titles returned a playable source
 */
data class NexusProvider(
    val scraper: String,
    val label: String,
    val hitRate: Int,
)

@Serializable
data class NexusServerDto(
    val id: JsonPrimitive? = null,
    val name: String? = null,
    // The value the /api/sources call expects as its `provider`.
    val scraper: String? = null,
)

@Serializable
data class NexusSourcesDto(
    val sources: List<NexusSourceDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class NexusSourceDto(
    val url: String? = null,
    // Usually a string ("1080p 2.9 GB | BluRay") but rive-flowcast sends a bare
    // number (720). Held as a primitive because `ignoreUnknownKeys` tolerates
    // unexpected fields and never a mismatched type, so declaring String here
    // makes that one provider's whole response unreadable.
    @SerialName("quality")
    val qualityRaw: JsonPrimitive? = null,
    @SerialName("label")
    val labelRaw: JsonPrimitive? = null,
    val type: String? = null,
    // A real JSON boolean, for the same reason as above.
    val isEmbed: Boolean? = null,
    val headers: Map<String, String>? = null,
) {
    /** The quality text, whichever JSON type it arrived as. */
    val quality: String? get() = qualityRaw?.contentOrNull

    val label: String? get() = labelRaw?.contentOrNull
}
