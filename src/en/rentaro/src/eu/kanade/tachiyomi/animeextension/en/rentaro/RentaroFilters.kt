package eu.kanade.tachiyomi.animeextension.en.rentaro

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Filters for the TMDB `/discover` endpoints.
 *
 * Every option here maps to a real TMDB query parameter. Filters that only
 * exist for one media type (networks and series status/type for TV, runtime
 * and certification for movies) are ignored when the other type is queried,
 * rather than sent and silently dropped by the API.
 */
object RentaroFilters {

    // ============================== Browse lists ==============================
    /**
     * A browsable TMDB collection.
     *
     * [path] is the endpoint under `/3`, or null for the two synthetic entries
     * ([KEY_DISCOVER] and [KEY_RECENT]) which are assembled from `/discover`
     * instead of a fixed list endpoint.
     */
    class BrowseList(
        val key: String,
        val label: String,
        val path: List<String>?,
    )

    const val KEY_DISCOVER = "discover"
    const val KEY_RECENT = "recently_released"

    val BROWSE_LISTS = listOf(
        BrowseList(KEY_DISCOVER, "Discover (use filters below)", null),
        BrowseList(KEY_RECENT, "Recently Released", null),
        BrowseList("trending_all_day", "Trending Today", listOf("trending", "all", "day")),
        BrowseList("trending_all_week", "Trending This Week", listOf("trending", "all", "week")),
        BrowseList("trending_movie_day", "Trending Movies Today", listOf("trending", "movie", "day")),
        BrowseList("trending_movie_week", "Trending Movies This Week", listOf("trending", "movie", "week")),
        BrowseList("trending_tv_day", "Trending TV Today", listOf("trending", "tv", "day")),
        BrowseList("trending_tv_week", "Trending TV This Week", listOf("trending", "tv", "week")),
        BrowseList("movie_popular", "Popular Movies", listOf("movie", "popular")),
        BrowseList("movie_top_rated", "Top Rated Movies", listOf("movie", "top_rated")),
        BrowseList("movie_now_playing", "Now Playing in Theaters", listOf("movie", "now_playing")),
        BrowseList("movie_upcoming", "Upcoming Movies", listOf("movie", "upcoming")),
        BrowseList("tv_popular", "Popular TV Shows", listOf("tv", "popular")),
        BrowseList("tv_top_rated", "Top Rated TV Shows", listOf("tv", "top_rated")),
        BrowseList("tv_airing_today", "TV Airing Today", listOf("tv", "airing_today")),
        BrowseList("tv_on_the_air", "TV On The Air", listOf("tv", "on_the_air")),
    )

    /** Lists selectable for the Popular/Latest tabs: everything but Discover. */
    val TAB_LISTS = BROWSE_LISTS.filterNot { it.key == KEY_DISCOVER }

    fun listByKey(key: String): BrowseList? = BROWSE_LISTS.firstOrNull { it.key == key }

    class ListFilter :
        AnimeFilter.Select<String>(
            "List",
            BROWSE_LISTS.map { it.label }.toTypedArray(),
        )

    // ============================== Type ==============================
    class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            arrayOf("All (Movies + TV)", "Movies only", "TV Shows only", "Animes only"),
        )

    const val TYPE_ALL = 0
    const val TYPE_MOVIES = 1
    const val TYPE_TV = 2
    const val TYPE_ANIMES = 3

    // ============================== Sort ==============================
    class SortFilter :
        AnimeFilter.Select<String>(
            "Sort",
            SORT_OPTIONS.map { it.first }.toTypedArray(),
        )

    /**
     * Label to `sort_by` value. TV has no `revenue`/`title` equivalents, so
     * those fall back to a supported field in [sortValue].
     */
    private val SORT_OPTIONS = listOf(
        "Most popular" to "popularity.desc",
        "Least popular" to "popularity.asc",
        "Highest rated" to "vote_average.desc",
        "Lowest rated" to "vote_average.asc",
        "Most voted" to "vote_count.desc",
        "Newest" to "release.desc",
        "Oldest" to "release.asc",
        "Highest revenue" to "revenue.desc",
        "Title (A-Z)" to "title.asc",
        "Title (Z-A)" to "title.desc",
    )

    const val SORT_POPULAR = 0
    const val SORT_RATING = 2
    const val SORT_RECENT = 5

    /** Resolves a sort index to a `sort_by` value valid for [isMovie]. */
    fun sortValue(index: Int, isMovie: Boolean): String {
        val raw = SORT_OPTIONS.getOrNull(index)?.second ?: "popularity.desc"
        return when {
            raw == "release.desc" -> if (isMovie) "primary_release_date.desc" else "first_air_date.desc"
            raw == "release.asc" -> if (isMovie) "primary_release_date.asc" else "first_air_date.asc"
            // TV has no revenue field; approximate with popularity.
            raw == "revenue.desc" && !isMovie -> "popularity.desc"
            raw == "title.asc" && !isMovie -> "name.asc"
            raw == "title.desc" && !isMovie -> "name.desc"
            else -> raw
        }
    }

    /** True when the sort needs a vote-count floor to avoid obscure titles. */
    fun sortNeedsVoteFloor(index: Int): Boolean = SORT_OPTIONS.getOrNull(index)?.second in setOf("vote_average.desc", "vote_average.asc")

    fun sortIsByDate(index: Int): Boolean = SORT_OPTIONS.getOrNull(index)?.second?.startsWith("release.") == true

    // ============================== Genres ==============================
    private class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name)

    class GenreFilter(
        name: String,
        genres: Array<String>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, genres.map { GenreCheckBox(it) })

    /** Genres to exclude, sent as `without_genres`. */
    class ExcludeGenreFilter(
        genres: Array<String>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>("Exclude Genres", genres.map { GenreCheckBox(it) })

    // ========================= Watch providers =========================
    class WatchProviderCheckBox(
        name: String,
        val id: String,
    ) : AnimeFilter.CheckBox(name)

    class WatchProviderFilter :
        AnimeFilter.Group<WatchProviderCheckBox>(
            "Streaming Platforms",
            listOf(
                WatchProviderCheckBox("Netflix", "8"),
                WatchProviderCheckBox("Disney+", "337"),
                WatchProviderCheckBox("Amazon Prime Video", "9"),
                WatchProviderCheckBox("Apple TV+", "350"),
                WatchProviderCheckBox("Hulu", "15"),
                WatchProviderCheckBox("HBO Max", "1899"),
                WatchProviderCheckBox("Paramount+", "531"),
                WatchProviderCheckBox("Peacock", "386"),
                WatchProviderCheckBox("Crunchyroll", "283"),
                WatchProviderCheckBox("Starz", "43"),
                WatchProviderCheckBox("fuboTV", "257"),
                WatchProviderCheckBox("YouTube", "192"),
                WatchProviderCheckBox("The Roku Channel", "207"),
                WatchProviderCheckBox("Tubi TV", "73"),
                WatchProviderCheckBox("Pluto TV", "300"),
                WatchProviderCheckBox("VIX", "457"),
                WatchProviderCheckBox("HiDive", "430"),
            ),
        )

    /** `with_watch_monetization_types`; only applied alongside a watch region. */
    class MonetizationFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Availability",
            MONETIZATION_TYPES.map { GenreCheckBox(it.first) },
        )

    val MONETIZATION_TYPES = listOf(
        "Subscription" to "flatrate",
        "Free" to "free",
        "With ads" to "ads",
        "Rent" to "rent",
        "Buy" to "buy",
    )

    /**
     * `watch_region`. TMDB exposes 139 regions; this is the common subset.
     * Availability filters are region-scoped, so the wrong value silently
     * returns another country's catalogue.
     */
    class RegionFilter :
        AnimeFilter.Select<String>(
            "Watch Region",
            REGIONS.map { it.first }.toTypedArray(),
        )

    val REGIONS = listOf(
        "United States" to "US",
        "Philippines" to "PH",
        "United Kingdom" to "GB",
        "Canada" to "CA",
        "Australia" to "AU",
        "Japan" to "JP",
        "South Korea" to "KR",
        "India" to "IN",
        "Indonesia" to "ID",
        "Singapore" to "SG",
        "Thailand" to "TH",
        "Germany" to "DE",
        "France" to "FR",
        "Spain" to "ES",
        "Italy" to "IT",
        "Brazil" to "BR",
        "Mexico" to "MX",
    )

    // ============================ Language ============================
    class LanguageFilter :
        AnimeFilter.Select<String>(
            "Original Language",
            LANGUAGES.map { it.first }.toTypedArray(),
        )

    val LANGUAGES = listOf(
        "Any" to "",
        "English" to "en",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese" to "zh",
        "Filipino" to "tl",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Italian" to "it",
        "Portuguese" to "pt",
        "Hindi" to "hi",
        "Thai" to "th",
        "Indonesian" to "id",
        "Russian" to "ru",
        "Arabic" to "ar",
        "Turkish" to "tr",
    )

    // ======================= Year / rating / runtime =======================

    /** `primary_release_year` (movie) or `first_air_date_year` (TV). */
    class YearFilter : AnimeFilter.Text("Year (e.g. 2024)")

    class MinRatingFilter :
        AnimeFilter.Select<String>(
            "Minimum Rating",
            arrayOf("Any", "5+", "6+", "7+", "8+", "9+"),
        )

    val MIN_RATINGS = listOf("", "5", "6", "7", "8", "9")

    /** `with_runtime.gte` / `.lte`, in minutes. Movies only. */
    class RuntimeFilter :
        AnimeFilter.Select<String>(
            "Runtime (movies)",
            arrayOf("Any", "Under 90 min", "90-120 min", "120-150 min", "Over 150 min"),
        )

    val RUNTIME_RANGES = listOf(
        null,
        null to "90",
        "90" to "120",
        "120" to "150",
        "150" to null,
    )

    // ========================== Certification ==========================

    /** `certification` with `certification_country=US`. Movies only. */
    class CertificationFilter :
        AnimeFilter.Select<String>(
            "US Rating (movies)",
            arrayOf("Any", "G", "PG", "PG-13", "R", "NC-17"),
        )

    val CERTIFICATIONS = listOf("", "G", "PG", "PG-13", "R", "NC-17")

    // ======================== TV-only: status/type ========================
    class SeriesStatusFilter :
        AnimeFilter.Select<String>(
            "Series Status (TV)",
            arrayOf("Any", "Returning", "Planned", "In production", "Ended", "Cancelled", "Pilot"),
        )

    // Index 0 is "Any"; the rest map to TMDB's with_status 0..5.
    val SERIES_STATUS = listOf("", "0", "1", "2", "3", "4", "5")

    class SeriesTypeFilter :
        AnimeFilter.Select<String>(
            "Series Type (TV)",
            arrayOf(
                "Any",
                "Documentary",
                "News",
                "Miniseries",
                "Reality",
                "Scripted",
                "Talk Show",
                "Video",
            ),
        )

    val SERIES_TYPES = listOf("", "0", "1", "2", "3", "4", "5", "6")

    // ========================== TV-only: networks ==========================
    class NetworkCheckBox(
        name: String,
        val id: String,
    ) : AnimeFilter.CheckBox(name)

    class NetworkFilter :
        AnimeFilter.Group<NetworkCheckBox>(
            "Networks (TV)",
            listOf(
                NetworkCheckBox("Netflix", "213"),
                NetworkCheckBox("HBO", "49"),
                NetworkCheckBox("BBC One", "4"),
                NetworkCheckBox("AMC", "174"),
                NetworkCheckBox("Showtime", "67"),
                NetworkCheckBox("Cartoon Network", "56"),
                NetworkCheckBox("Adult Swim", "80"),
                NetworkCheckBox("Fuji TV", "1"),
                NetworkCheckBox("TV Tokyo", "94"),
                NetworkCheckBox("Tokyo MX", "94"),
                NetworkCheckBox("TBS", "45"),
                NetworkCheckBox("Nippon TV", "5"),
                NetworkCheckBox("tvN", "882"),
                NetworkCheckBox("Apple TV+", "2552"),
                NetworkCheckBox("Disney+", "2739"),
                NetworkCheckBox("Prime Video", "1024"),
            ),
        )

    // ============================== Genres ==============================
    private val ALL_GENRES = arrayOf(
        "Action",
        "Action & Adventure",
        "Adventure",
        "Animation",
        "Comedy",
        "Crime",
        "Documentary",
        "Drama",
        "Family",
        "Fantasy",
        "History",
        "Horror",
        "Kids",
        "Music",
        "Mystery",
        "News",
        "Reality",
        "Romance",
        "Sci-Fi & Fantasy",
        "Science Fiction",
        "Soap",
        "TV Movie",
        "Talk",
        "Thriller",
        "War",
        "War & Politics",
        "Western",
    ).sortedArray()

    val MOVIE_GENRE_MAP = mapOf(
        "Action" to "28",
        "Adventure" to "12",
        "Animation" to "16",
        "Comedy" to "35",
        "Crime" to "80",
        "Documentary" to "99",
        "Drama" to "18",
        "Family" to "10751",
        "Fantasy" to "14",
        "History" to "36",
        "Horror" to "27",
        "Music" to "10402",
        "Mystery" to "9648",
        "Romance" to "10749",
        "Science Fiction" to "878",
        "TV Movie" to "10770",
        "Thriller" to "53",
        "War" to "10752",
        "Western" to "37",
    )

    val TV_GENRE_MAP = mapOf(
        "Action & Adventure" to "10759",
        "Animation" to "16",
        "Comedy" to "35",
        "Crime" to "80",
        "Documentary" to "99",
        "Drama" to "18",
        "Family" to "10751",
        "Kids" to "10762",
        "Mystery" to "9648",
        "News" to "10763",
        "Reality" to "10764",
        "Sci-Fi & Fantasy" to "10765",
        "Soap" to "10766",
        "Talk" to "10767",
        "War & Politics" to "10768",
        "Western" to "37",
    )

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Pick a ready-made list, or Discover to use the filters"),
        ListFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Type narrows both search (with query) and browse (without)"),
        AnimeFilter.Header("Everything below applies to Discover only, with no search query"),
        TypeFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        LanguageFilter(),
        YearFilter(),
        MinRatingFilter(),
        GenreFilter("Genres", ALL_GENRES),
        ExcludeGenreFilter(ALL_GENRES),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Availability is region-scoped"),
        RegionFilter(),
        WatchProviderFilter(),
        MonetizationFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Movies only"),
        RuntimeFilter(),
        CertificationFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("TV only"),
        SeriesStatusFilter(),
        SeriesTypeFilter(),
        NetworkFilter(),
    )
}
