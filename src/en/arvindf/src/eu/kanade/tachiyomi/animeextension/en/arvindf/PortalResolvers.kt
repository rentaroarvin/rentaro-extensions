package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class Stream(val label: String, val url: String)

internal data class Sub(val label: String, val url: String)

internal data class Resolved(val streams: List<Stream>, val subs: List<Sub> = emptyList())

internal object Js {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

/** A primitive's text, or null for a missing, null or blank value. */
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** True/false for a boolean or a 0/1 number, null for anything else. */
internal fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { number -> number != 0 } }

/**
 * Turns the JSON each portal's source API returns into streams. Every portal has its own shape, all
 * read from what the 2026-11-02 capture recorded; a response that does not fit gives null, never a
 * guess.
 */
internal object PortalResolvers {

    /** The request each pool endpoint is asked, as the portal's own watch page sends it. */
    fun poolUrl(resolver: Resolver, lang: String, endpoint: String, id: String, episode: String, chapterId: String?): HttpUrl? {
        val builder = endpoint.toHttpUrlOrNull()?.newBuilder() ?: return null
        when (resolver) {
            Resolver.DRAMABOX -> builder.add("action" to "get_video", "id" to id, "ep" to episode, "lang" to lang, "sv" to "1")
            Resolver.NETSHORT -> builder.add("id" to id, "ep" to episode, "action" to "resolve_watch", "server" to "1")
            Resolver.FLICKREELS -> builder.add("action" to "watch_stream", "id" to id, "ep" to episode, "lang" to lang, "server" to "0")
            Resolver.GOODSHORT -> builder.add("action" to "get_video_url", "id" to id, "chap_id" to (chapterId ?: return null), "sv" to "1", "lang" to lang)
            Resolver.IDRAMA -> builder.add("action" to "watch_stream", "id" to id, "ep" to episode, "server" to "1", "lang" to lang)
            Resolver.KALOS -> builder.add("id" to id, "ep" to episode, "action" to "resolve_watch", "server" to "1", "lang" to lang)
            Resolver.SHORTMAX -> builder.add("id" to id, "ep" to episode, "action" to "video_server", "server" to "server1", "lang" to lang)
            else -> return null
        }
        return builder.build()
    }

    private fun HttpUrl.Builder.add(vararg params: Pair<String, String>) = params.forEach { (key, value) -> addQueryParameter(key, value) }

    /**
     * Media3 picks HLS from the URL path, and these portals serve playlists from token and proxy paths
     * that do not end in `.m3u8`. A fragment is never sent, so `#.m3u8` is a safe client-side hint.
     */
    fun hlsUrl(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        return if (path.endsWith(".m3u8", ignoreCase = true) || '#' in url) url else "$url#.m3u8"
    }

    fun pool(resolver: Resolver, root: JsonObject): Resolved? = when (resolver) {
        Resolver.DRAMABOX, Resolver.NETSHORT, Resolver.KALOS, Resolver.IDRAMA -> okShape(root)
        Resolver.FLICKREELS -> flickreels(root)
        Resolver.GOODSHORT -> goodshort(root)
        Resolver.SHORTMAX -> shortmax(root)
        else -> null
    }?.takeIf { it.streams.isNotEmpty() }

    /** `{ok, videoUrl|url|video_url, video_urls, qualities[{quality,url}], subtitles[...]}` */
    private fun okShape(root: JsonObject): Resolved? {
        if (root["ok"].flag() != true) return null
        val listed = qualities(root["qualities"])
        val single = listOfNotNull(root["videoUrl"].text(), root["url"].text(), root["video_url"].text()) +
            root["video_urls"].asArray().mapNotNull { it.text() }
        val streams = listed.ifEmpty {
            single.distinct().mapIndexed { index, url -> Stream(if (index == 0) "Video" else "Mirror ${index + 1}", url) }
        }
        return Resolved(streams, subs(root["subtitles"]))
    }

    /** `{status_code: 1, data: {url, subtitles[]}}` with an HLS playlist. */
    private fun flickreels(root: JsonObject): Resolved? {
        if (root["status_code"].flag() != true) return null
        val data = root["data"].asObject() ?: return null
        val url = data["url"].text() ?: return null
        return Resolved(listOf(Stream("HLS", url)), subs(data["subtitles"]))
    }

    /** `{status: "success", video_url, qualities[{quality,url}]}` */
    private fun goodshort(root: JsonObject): Resolved? {
        if (root["status"].text() != "success") return null
        val streams = qualities(root["qualities"]).ifEmpty { listOfNotNull(root["video_url"].text()?.let { Stream("Video", it) }) }
        return Resolved(streams)
    }

    /** `{ok, server: {available, playUrl, qualities[{quality,url,playUrl}]}}` */
    private fun shortmax(root: JsonObject): Resolved? {
        if (root["ok"].flag() != true) return null
        val server = root["server"].asObject() ?: return null
        if (server["available"].flag() == false) return null
        val streams = qualities(server["qualities"]).ifEmpty { listOfNotNull(server["playUrl"].text()?.let { Stream("Video", it) }) }
        return Resolved(streams)
    }

    /** ShortWave `unlock` for a free chapter: the playlist address comes Base64-wrapped. */
    fun shortwave(root: JsonObject): Resolved? {
        if (root["code"].text() != "0") return null
        val data = root["data"].asObject() ?: return null
        val wrapped = data["play_url_encoded"].text()?.let { runCatching { String(Base64Lite.decode(it), Charsets.UTF_8) }.getOrNull() }
        val url = (wrapped ?: data["play_url_720p"].text())?.takeIf { it.startsWith("http") } ?: return null
        return Resolved(listOf(Stream("HLS", url)))
    }

    /** MicroDrama keeps every quality of every episode in its detail response, so no play call is needed. */
    fun microdrama(episode: JsonObject): Resolved? {
        val streams = episode["videos"].asArray().mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val url = o["url"].text() ?: return@mapNotNull null
            Stream(o["quality"].text() ?: "Video", url)
        }
        return Resolved(streams).takeIf { it.streams.isNotEmpty() }
    }

    private fun qualities(array: JsonElement?): List<Stream> = array.asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val url = o["playUrl"].text() ?: o["url"].text() ?: return@mapNotNull null
        Stream(o["quality"].text() ?: "Video", url)
    }

    private fun subs(array: JsonElement?): List<Sub> = array.asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val url = o["url"].text()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
        Sub(o["subtitleLanguage"].text() ?: o["label"].text() ?: o["lang"].text() ?: "Subtitles", url)
    }

    /** Biggest "NNNp" in a label, for ordering; 0 when it has none. */
    fun rank(label: String): Int = RANK.findAll(label).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

    private val RANK = Regex("""(\d{3,4})\s*[pP]""")
}
