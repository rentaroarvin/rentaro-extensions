package eu.kanade.tachiyomi.animeextension.en.rentaro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
// Independent Nexus backend. Its API is symmetrically encrypted, so both the
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
// Independent CineJoy backend. Its API answers an encrypted body, so one call is
// needed: api.wing.st/g returns the ciphertext for a body that [CineJoyCipher]
// seals and opens in-process, using standard P-256 ECDH, HKDF and AES-GCM.

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

/** The decrypted reply from `api.wing.st/g`. */
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
    val display: String? = null,
    val type: String? = null,
    val id: String? = null,
)

/** Plain-JSON subtitle catalogue served separately from the encrypted video API. */
@Serializable
data class CineJoySubtitleResponseDto(
    val total: Int = 0,
    val subtitles: List<CineJoyCaptionDto> = emptyList(),
)

// ============================= CineFlix =============================
// Independent CineFlix backend. Plain JSON throughout: a suggestions endpoint
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

// ============================ VidLove ============================
// VidLove's public JSON response. Metadata is intentionally omitted: Rentaro already owns the
// TMDB title and only needs the source plus subtitle tracks.
@Serializable
data class VidLoveResponseDto(
    val subtitles: List<VidLoveSubtitleDto> = emptyList(),
    val source: VidLoveSourceDto? = null,
)

@Serializable
data class VidLoveSourceDto(
    val source: String? = null,
    val label: String? = null,
    val url: String? = null,
    // Inline HLS master. Its signed variants are parsed directly because `url` may point to a
    // progressive fallback or an MP4 init fragment rather than to this playlist.
    val manifest: String? = null,
    val qualities: List<VidLoveQualityDto> = emptyList(),
)

@Serializable
data class VidLoveQualityDto(
    val quality: String? = null,
    val url: String? = null,
    val codec: String? = null,
)

@Serializable
data class VidLoveSubtitleDto(
    val label: String? = null,
    val file: String? = null,
    val type: String? = null,
    val source: String? = null,
)

// ============================ VidFast ============================
// VidFast backend, and the only one that still needs enc-dec.app.
//
// Its responses are encrypted by a bytecode VM embedded in the player bundle:
// the payload is handed to an interpreter along with a virtualised global
// environment, so there is no cipher to lift out and reimplement. The site also
// detects devtools and stalls, which rules out capturing the algorithm from a
// running page. Two calls per resolve therefore go through enc-dec.app — the
// only remote dependency left in this extension.
//
// What *is* local: the request flow, CSRF handling, server selection, subtitle
// lookup, and stream formatting. Only the two cipher transformations are
// delegated. See the VIDFAST_* declarations in [RentaroExtractor] for the
// endpoints and protocol notes.

/** `enc-vidfast` response: the two request URLs plus the CSRF token. */
@Serializable
data class VidFastEncDto(
    val status: Int? = null,
    val result: VidFastEncResultDto? = null,
    val error: String? = null,
)

@Serializable
data class VidFastEncResultDto(
    // Absolute URL the server list is POSTed to.
    val servers: String? = null,
    // Absolute URL prefix; the chosen server's `data` is appended as a segment.
    val stream: String? = null,
    // Echoed back as the X-CSRF-Token header on both POSTs.
    val token: String? = null,
)

/** `dec-vidfast` response for the server list. */
@Serializable
data class VidFastServersDto(
    val status: Int? = null,
    val result: List<VidFastServerDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class VidFastServerDto(
    // Upstream label. Only "Bravo" is retained by Rentaro.
    val name: String? = null,
    // Free text such as "Original audio, 4K" — the only 4K hint on the list
    // itself, and sometimes hedged ("4K?"), so the stream's own flag wins.
    val description: String? = null,
    // Flag or badge image; unused, kept so the shape is documented.
    val image: String? = null,
    // Opaque; appended to the stream URL to resolve this server.
    val data: String? = null,
)

/** `dec-vidfast` response for one server's stream. */
@Serializable
data class VidFastStreamResponseDto(
    val status: Int? = null,
    val result: VidFastStreamDto? = null,
    val error: String? = null,
)

@Serializable
data class VidFastStreamDto(
    // HLS master playlist, or a progressive file when `mp4` is set.
    val url: String? = null,
    // True when the CDN rejects a Referer, so the header must be omitted.
    val noReferrer: Boolean = false,
    // True when `url` is a progressive MP4 rather than an HLS master.
    val mp4: Boolean = false,
    // Authoritative 4K flag; `description` only hints at it.
    @SerialName("4kAvailable")
    val is4k: Boolean = false,
    // Always empty in every response observed so far, so the element shape is
    // unverified. Modelled as raw JSON rather than guessed at: a wrong field
    // name would fail deserialisation for whatever title finally carries one.
    val tracks: List<JsonElement> = emptyList(),
)

/**
 * One subtitle from `/wyzie`.
 *
 * That endpoint is plain JSON with no cipher and no CSRF token, so subtitles are
 * fetched directly and are unaffected by the enc-dec.app dependency the stream
 * chain carries. Files are SubRip rather than WebVTT.
 */
@Serializable
data class VidFastSubtitleDto(
    // Human-readable name, e.g. "English" or "Brazilian Portuguese".
    val display: String? = null,
    // Two-letter code, plus "pb" for Brazilian Portuguese.
    val language: String? = null,
    val url: String? = null,
    val encoding: String? = null,
)
