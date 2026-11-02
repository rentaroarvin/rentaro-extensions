package eu.kanade.tachiyomi.animeextension.en.arvinkd

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val KD_MEDIA_RELAY = "https://cdn.dramadizilerim.xyz"

/**
 * ShortTV's HLS host refuses direct playback. Use the same relay path as KiligDrama's
 * browser player; its playlists also rewrite segment URLs through that relay.
 * Leave providers that already work directly unchanged.
 */
internal fun kdMediaUrl(link: String): String {
    val url = link.toHttpUrlOrNull() ?: return link
    if (url.host != "volcengine-forward.shorttv.live") return link
    val upstream = url.newBuilder().fragment(null).build().toString()
    return "$KD_MEDIA_RELAY/${upstream.replaceFirst("://", "/")}"
}

internal fun kdMediaHeaders(headers: Headers, siteUrl: String, mediaUrl: String): Headers {
    if (!mediaUrl.startsWith("$KD_MEDIA_RELAY/")) return headers
    return headers.newBuilder()
        .set("Origin", siteUrl.trimEnd('/'))
        .set("Referer", "${siteUrl.trimEnd('/')}/")
        .build()
}

/**
 * A page can embed an expired signed URL even when it describes the correct episode.
 * Try its fallback chain first, then request and resolve a fresh descriptor once.
 */
internal suspend fun <T> resolveKdPlayback(
    initial: SourceDescriptorDto?,
    refresh: (suspend () -> SourceDescriptorDto?)?,
    resolve: suspend (SourceDescriptorDto) -> List<T>,
): List<T> {
    initial?.let { descriptor ->
        resolve(descriptor).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val refreshed = refresh?.invoke() ?: return emptyList()
    return resolve(refreshed)
}
