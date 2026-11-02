package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.serialization.Serializable

// Every field is nullable or defaulted on purpose, so one odd value on one title cannot fail a page.

/** `GET <cdn>/index.php?action=watch_source&id=&ep=&server=&lang=` on the DramaWave subsite. */
@Serializable
data class WatchSourceDto(
    val status: String? = null,
    val message: String? = null,
    val data: WatchSourceDataDto? = null,
)

@Serializable
data class WatchSourceDataDto(
    // The HLS master playlist. Audio is a separate rendition inside it.
    val url: String? = null,
    // The site's own relay for the same stream, used when the direct host refuses.
    val proxy_rescue_url: String? = null,
    val subtitle_options: List<WatchSubtitleDto> = emptyList(),
)

@Serializable
data class WatchSubtitleDto(
    val lang_code: String? = null,
    val label: String? = null,
    // `?proxy_sub=<base64 of the upstream .srt URL>`
    val url: String? = null,
)
