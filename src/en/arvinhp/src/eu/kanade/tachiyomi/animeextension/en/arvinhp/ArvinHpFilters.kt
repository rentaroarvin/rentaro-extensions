package eu.kanade.tachiyomi.animeextension.en.arvinhp

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/**
 * Display name to the site's `rail` value, as the Discover page's provider buttons use them (11 on
 * 2026-11-01). The rail ids are historical and do not match the names: `melolo` is StoryReel, `moboreels`
 * is StardustTV and `vip` is FreeReels.
 */
internal val HOTPLOT_PROVIDERS = listOf(
    "ReelShort" to "new",
    "DramaBox" to "trending",
    "GoodShort" to "for_you",
    "ShortMax" to "shortmax",
    "VibeShort" to "idrama-2",
    "StoryReel" to "melolo",
    "NetShort" to "free",
    "FlickReels" to "idrama",
    "DramaWave" to "dramawave",
    "FreeReels" to "vip",
    "StardustTV" to "moboreels",
)

internal class ProviderFilter :
    AnimeFilter.Select<String>(
        "Provider",
        arrayOf("All") + HOTPLOT_PROVIDERS.map { it.first }.toTypedArray(),
    ) {
    /** The `rail` query value, or null for every provider. */
    val rail: String? get() = HOTPLOT_PROVIDERS.getOrNull(state - 1)?.second
}

internal class SortFilter :
    AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Popular", "Newest", "Recently updated"),
    ) {
    /** The site's `sort` value; Popular is the site's default and sends nothing. */
    val value: String? get() = when (state) {
        1 -> SORT_NEWEST
        2 -> SORT_UPDATED
        else -> null
    }

    companion object {
        const val SORT_NEWEST = "new"
        const val SORT_UPDATED = "updated"
    }
}
