package eu.kanade.tachiyomi.animeextension.en.arvinhd

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

/** Category slug to display name, as listed on `/en/categories` (32 entries on 2026-11-01). */
internal val HEPDRAMA_CATEGORIES = listOf(
    "billionaire-ceo" to "Billionaire & CEO",
    "revenge" to "Revenge",
    "hidden-identity" to "Hidden Identity",
    "contract-marriage" to "Contract Marriage",
    "divorce-betrayal" to "Divorce & Betrayal",
    "mafia" to "Mafia",
    "werewolf" to "Werewolf",
    "vampire" to "Vampire",
    "rebirth" to "Rebirth",
    "time-travel" to "Time Travel",
    "secret-baby" to "Secret Baby",
    "enemies-to-lovers" to "Enemies to Lovers",
    "rags-to-riches" to "Rags to Riches",
    "strong-women" to "Strong Women",
    "historical-palace" to "Historical & Palace",
    "fantasy" to "Fantasy",
    "family-drama" to "Family Drama",
    "lgbtq" to "LGBTQ+",
    "romantic-comedy" to "Romantic Comedy",
    "second-chance-regret" to "Second Chance & Regret",
    "marriage-before-love" to "Marriage Before Love",
    "love-triangle" to "Love Triangle",
    "superpowers-system" to "Superpowers & System",
    "action-martial-arts" to "Action & Martial Arts",
    "thriller-mystery" to "Thriller & Mystery",
    "rural-life" to "Rural Life",
    "celebrity-fame" to "Celebrity & Fame",
    "sci-fi-apocalypse" to "Sci-Fi & Apocalypse",
    "horror-supernatural" to "Horror & Supernatural",
    "campus-youth" to "Campus & Youth",
    "medical-drama" to "Medical Drama",
    "amnesia" to "Amnesia",
)

internal class CategoryFilter :
    AnimeFilter.Select<String>(
        "Category",
        arrayOf("All") + HEPDRAMA_CATEGORIES.map { it.second }.toTypedArray(),
    ) {
    /** The category route slug, or null for the all-series listing. */
    val slug: String? get() = HEPDRAMA_CATEGORIES.getOrNull(state - 1)?.first
}

internal class SortFilter :
    AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Popular", "Newest", "Recently updated"),
    ) {
    /** The site's `sirala` value; Popular is the site's default and sends nothing. */
    val value: String? get() = when (state) {
        1 -> SORT_NEWEST
        2 -> SORT_UPDATED
        else -> null
    }

    companion object {
        const val SORT_NEWEST = "yeni"
        const val SORT_UPDATED = "guncel"
    }
}
