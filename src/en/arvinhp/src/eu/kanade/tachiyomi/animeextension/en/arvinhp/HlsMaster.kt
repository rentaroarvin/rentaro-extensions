package eu.kanade.tachiyomi.animeextension.en.arvinhp

import okhttp3.HttpUrl

/** One `#EXT-X-STREAM-INF` entry of a master playlist, with its URL already resolved against the master. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
) {
    /**
     * The shorter side as "NNNp". These streams are portrait (720x1280), so the height alone would
     * read as 1280p.
     */
    val label: String?
        get() = if (width != null && height != null) "${minOf(width, height)}p" else null
}

/**
 * Reads a master playlist for its variants.
 *
 * The delivered URL carries a signed first path segment (`bcdn_token=...&token_path=...&expires=...`) that
 * every playlist and segment under it must keep. [HttpUrl.resolve] does keep it; a hand-built prefix is
 * what would risk dropping or re-encoding it.
 */
object HlsMaster {
    private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)[xX](\d+)""")

    fun parse(text: String, masterUrl: HttpUrl): List<HlsVariant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = ArrayList<HlsVariant>()
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val target = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val resolved = masterUrl.resolve(target) ?: continue
            val resolution = RESOLUTION.find(line)
            variants += HlsVariant(
                url = resolved.toString(),
                bandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull(),
                width = resolution?.groupValues?.get(1)?.toIntOrNull(),
                height = resolution?.groupValues?.get(2)?.toIntOrNull(),
            )
        }
        return variants
    }
}
