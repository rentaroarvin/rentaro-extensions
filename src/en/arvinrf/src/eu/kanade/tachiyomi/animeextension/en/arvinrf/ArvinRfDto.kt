package eu.kanade.tachiyomi.animeextension.en.arvinrf

import kotlinx.serialization.Serializable

// Ids are strings on purpose: some providers use 19-digit ids that overflow a JS number, and the
// API itself returns them as strings.

/** `/api/home` and `/api/search` (search adds [hasMore]). */
@Serializable
data class FeedDto(
    val data: List<TitleDto> = emptyList(),
    val hasMore: Boolean? = null,
)

@Serializable
data class TitleDto(
    val id: String,
    val provider: String,
    val title: String? = null,
    val cover: String? = null,
    val intro: String? = null,
)

/** `/api/detail`: the title plus its episode matrix. */
@Serializable
data class DetailDto(
    val title: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    val episodes: Int? = null,
    val videos: List<EpisodeRefDto> = emptyList(),
)

@Serializable
data class EpisodeRefDto(
    val episode: Int,
    val available: Boolean = true,
)

/** `/api/video`: the playable sources of one episode. */
@Serializable
data class VideoDto(
    val locked: Boolean = false,
    val videoUrl: String? = null,
    val qualityList: List<QualityDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
)

@Serializable
data class QualityDto(
    val label: String? = null,
    val url: String? = null,
    // Present only on keyed streams that the site decrypts client-side; those cannot be played here.
    val kid: String? = null,
)

@Serializable
data class SubtitleDto(
    val label: String? = null,
    val srclang: String? = null,
    val url: String? = null,
)
