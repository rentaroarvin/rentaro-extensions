package eu.kanade.tachiyomi.animeextension.en.arvinkd

import kotlinx.serialization.Serializable

// Everything is nullable or defaulted: KiligDrama fronts dozens of sources and they do not agree on
// which fields they fill, so a field this extension does not need is left out rather than risk failing
// a whole episode on one source's quirk.

/**
 * What the player is told about one episode's stream, found in the page's React Flight data
 * (`sourceFirst`) or in the `descriptor` field of `/api/episode-source/{bookId}/{serial}`.
 *
 * Two shapes exist and the site's own client accepts both: a `chain` of fallbacks, or a single flat
 * `enc`/`type` pair left over from an older format.
 */
@Serializable
data class SourceDescriptorDto(
    val source: String? = null,
    val chain: List<ChainEntryDto> = emptyList(),
    val subtitle: SubtitleEnvelopeDto? = null,
    val enc: String? = null,
    val type: String? = null,
) {
    /** The chain, or the flat pair as a one-entry chain, like the site's client normalises it. */
    fun entries(): List<ChainEntryDto> = when {
        chain.isNotEmpty() -> chain
        !enc.isNullOrBlank() -> listOf(ChainEntryDto(enc = enc, type = type, source = source))
        else -> emptyList()
    }
}

@Serializable
data class ChainEntryDto(
    // The wrapped stream URL; open it with KdCipher.
    val enc: String? = null,
    // Which player branch the site uses for it: "dramawave", "mp4", ...
    val type: String? = null,
    val source: String? = null,
)

@Serializable
data class SubtitleEnvelopeDto(
    val enc: String? = null,
    val format: String? = null,
    val language: String? = null,
)

/** The reply of `GET /api/episode-source/{bookId}/{serial}`. */
@Serializable
data class EpisodeSourceDto(
    val descriptor: SourceDescriptorDto? = null,
)
