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
// The `enc=2` payload is decrypted in-process by VideasyCipher, so only the
// plaintext shape is modelled; there is no longer a remote envelope to unwrap.
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
    // Explicit JSON `null` here, not just an absent key, for a provider with no
    // match: seen live from k4khdhub and hdhub4u. A non-null type would fail to
    // deserialise and lose the whole response, so it is nullable and normalised.
    @SerialName("sources")
    val sourcesOrNull: List<NexusSourceDto>? = null,
    val error: String? = null,
) {
    val sources: List<NexusSourceDto> get() = sourcesOrNull.orEmpty()
}

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

// ============================ CineJoy ============================
// Fourth independent backend. Its API answers an encrypted body, so two calls
// are needed: enc-dec.app builds the request payload, then api.shegu.st/g
// returns the ciphertext. The reply is decrypted in-process by [CineJoyCipher],
// since the enc step hands back the AES key and additional data in plain.

/** One upstream scraper CineJoy exposes, from `/servers`. */
@Serializable
data class CineJoyServerDto(
    val name: String? = null,
    val status: String? = null,
    // Flag image URL rather than a language code, so it is only usable as a
    // rough origin hint and is deliberately not surfaced in labels.
    val language: String? = null,
    val description: String? = null,
    @SerialName("4k")
    val is4k: Boolean = false,
)

@Serializable
data class CineJoyServersDto(
    val servers: List<CineJoyServerDto> = emptyList(),
)

/**
 * `enc-cinejoy` response: the request body to POST upstream, plus the state
 * needed to decrypt whatever comes back.
 */
@Serializable
data class CineJoyEncDto(
    val status: Int? = null,
    val result: CineJoyEncResultDto? = null,
    val error: String? = null,
)

@Serializable
data class CineJoyEncResultDto(
    // base64url, no padding: decoded to raw bytes for the upstream POST.
    val data: String? = null,
    val state: CineJoyStateDto? = null,
)

/**
 * Decryption material for the reply, returned in plain by the enc step.
 *
 * `aad` binds the reply to the exact request that produced it, which is why it
 * has to be carried through rather than reconstructed.
 */
@Serializable
data class CineJoyStateDto(
    // base64url: the 32-byte AES-256-GCM key.
    val responseKey: String? = null,
    // base64url: the additional data the reply authenticates against.
    val aad: String? = null,
)

@Serializable
data class CineJoyDecResultDto(
    val data: CineJoyDataDto? = null,
    val status: Int? = null,
)

@Serializable
data class CineJoyDataDto(
    // Absent, rather than empty, for a title the server has no source for.
    val stream: List<CineJoyStreamDto> = emptyList(),
)

@Serializable
data class CineJoyStreamDto(
    // "hls" carries `playlist`; "file" carries `qualities`.
    val type: String? = null,
    // Upstream label, e.g. "primary" or "RedeFlix 720p".
    val id: String? = null,
    val playlist: String? = null,
    val qualities: Map<String, CineJoyQualityDto>? = null,
    val captions: List<CineJoyCaptionDto> = emptyList(),
)

@Serializable
data class CineJoyQualityDto(
    val url: String? = null,
    val type: String? = null,
)

@Serializable
data class CineJoyCaptionDto(
    val url: String? = null,
    val language: String? = null,
    val type: String? = null,
    val id: String? = null,
)

// ============================= CineFlix =============================
// Fifth independent backend. Plain JSON throughout: a suggestions endpoint
// resolves a title to the slug its playback API needs, then a proof of work
// releases the stream. No external decryption service is involved.
@Serializable
data class CineFlixSuggestionsDto(
    val items: List<CineFlixSuggestionDto> = emptyList(),
)

@Serializable
data class CineFlixSuggestionDto(
    // Trailing token of the slug; the slug itself arrives in `href`.
    val id: String? = null,
    val href: String? = null,
    val title: String? = null,
    // "movie" or "series" — the only signal separating the two, since a query
    // readily returns both.
    val type: String? = null,
    val year: Int? = null,
    // TMDB path, which is unique per title and so confirms the match.
    val posterUrl: String? = null,
) {
    /** Slug the playback API expects, taken from `href` and verified. */
    val slug: String?
        get() = href?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

@Serializable
data class CineFlixChallengeDto(
    val challengeId: String? = null,
    val challenge: String? = null,
    val difficulty: Int? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class CineFlixStreamResponseDto(
    val stream: CineFlixStreamDto? = null,
    val tracks: List<CineFlixTrackDto> = emptyList(),
)

@Serializable
data class CineFlixStreamDto(
    val url: String? = null,
    // "hls" for every stream seen so far.
    val type: String? = null,
)

@Serializable
data class CineFlixTrackDto(
    // "captions" for subtitles; other kinds are ignored.
    val kind: String? = null,
    val file: String? = null,
    val language: String? = null,
    val label: String? = null,
)
