package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl

/** One `#EXT-X-STREAM-INF` entry of a master playlist, with its URL resolved against the master. */
internal data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
) {
    /**
     * The shorter side as "NNNp". These streams are portrait (1080x1920), so the height alone would read
     * as 1920p.
     */
    val label: String
        get() = if (width != null && height != null) "${minOf(width, height)}p" else "Video"
}

/** One `#EXT-X-MEDIA:TYPE=AUDIO` rendition, which a variant on its own does not include. */
internal data class HlsAudio(val url: String, val name: String)

internal data class HlsMasterInfo(val variants: List<HlsVariant>, val audio: List<HlsAudio>)

/**
 * Reads a master playlist. DramaWave keeps audio in a separate rendition, so a variant URL handed to a
 * player without its audio track plays silent; callers either pass the master itself or attach [HlsAudio].
 */
internal object HlsMaster {
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun parse(text: String, masterUrl: HttpUrl): HlsMasterInfo {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = ArrayList<HlsVariant>()
        val audio = ArrayList<HlsAudio>()
        for ((index, line) in lines.withIndex()) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val target = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                    val resolved = masterUrl.resolve(target) ?: continue
                    val attributes = attributes(line.substringAfter(':'))
                    val resolution = attributes["RESOLUTION"]?.split('x', 'X')
                    variants += HlsVariant(
                        url = resolved.toString(),
                        bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = resolution?.getOrNull(0)?.toIntOrNull(),
                        height = resolution?.getOrNull(1)?.toIntOrNull(),
                    )
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attributes = attributes(line.substringAfter(':'))
                    if (attributes["TYPE"] != "AUDIO") continue
                    val uri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: continue
                    val resolved = masterUrl.resolve(uri) ?: continue
                    audio += HlsAudio(resolved.toString(), attributes["NAME"] ?: attributes["LANGUAGE"] ?: "Audio")
                }
            }
        }
        return HlsMasterInfo(variants.sortedByDescending { it.bandwidth }, audio)
    }

    private fun attributes(list: String): Map<String, String> = ATTRIBUTE.findAll(list).associate { match ->
        match.groupValues[1] to match.groupValues[2].trim('"')
    }
}
