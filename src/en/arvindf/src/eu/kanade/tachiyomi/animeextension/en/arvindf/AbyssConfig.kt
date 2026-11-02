package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** One playable file named by an Abyss embed: where it lives, how big it is, and how it is split. */
internal data class AbyssSource(
    val label: String,
    val size: Long,
    val partSize: Long,
    /** `<host>/<path>`. Part 0 is this URL; part N appends N. */
    val base: String,
) {
    /** The last path segment. The encrypted head of the file is keyed by the MD5 of this name. */
    val fileName: String get() = base.substringAfterLast('/')

    val partCount: Int get() = ((size + partSize - 1) / partSize).toInt()

    fun partUrl(index: Int): String = if (index == 0) base else base + index

    fun partLength(index: Int): Long = if (index == partCount - 1) size - partSize * index else partSize
}

/** What an embed offers: files this extension can stream, and how many entries it had to leave out. */
internal data class AbyssConfigResult(val sources: List<AbyssSource>, val skipped: Int)

/**
 * Reads the source configuration out of an Abyss embed page.
 *
 * The page carries `const datas = "<base64>"`: a JSON envelope whose `media` field is the source
 * configuration encrypted with AES-256-CTR, keyed by the MD5 of `user_id:slug:md5_id`. All three inputs
 * are in the envelope itself, so this needs nothing but the page. It yields configuration only. The
 * movie's own bytes are fetched separately, and the player's ad overlay never takes part in any of it.
 */
internal object AbyssConfig {
    private val DATAS = Regex("""const datas = "([^"]+)"""")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class Envelope(
        val slug: JsonPrimitive,
        val md5_id: JsonPrimitive,
        val user_id: JsonPrimitive,
        val media: String,
    )

    @Serializable
    private data class Config(val mp4: Mp4? = null)

    @Serializable
    private data class Mp4(val sources: List<Source> = emptyList())

    @Serializable
    private data class Source(
        val label: String? = null,
        val size: Long? = null,
        val url: String? = null,
        val path: String? = null,
        val partSize: Long? = null,
    )

    fun read(embedHtml: String): AbyssConfigResult {
        val encoded = DATAS.find(embedHtml)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("The Abyss embed carried no player data")
        // atob() yields a binary string, so the envelope is read as Latin-1, never as UTF-8.
        val envelope = json.decodeFromString<Envelope>(String(Base64Lite.decode(encoded), Charsets.ISO_8859_1))
        val key = AbyssCrypto.md5Hex("${envelope.user_id.content}:${envelope.slug.content}:${envelope.md5_id.content}")
        val plain = AbyssCrypto.apply(key, envelope.media.toByteArray(Charsets.ISO_8859_1))
        val all = json.decodeFromString<Config>(String(plain, Charsets.UTF_8)).mp4?.sources.orEmpty()

        // A source with no host and path is served through a different, chunked route that this
        // extension does not speak, so it is counted and left out rather than offered and failing.
        val usable = all.mapNotNull { source ->
            val host = source.url?.trimEnd('/')?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val path = source.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val size = source.size?.takeIf { it > 0 } ?: return@mapNotNull null
            val part = source.partSize?.takeIf { it > 0 } ?: size
            AbyssSource(label = source.label ?: "Video", size = size, partSize = part, base = "$host/$path")
        }
        return AbyssConfigResult(usable, all.size - usable.size)
    }
}
