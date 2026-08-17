package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Rentaro :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Rentaro"

    private val preferences: SharedPreferences by getPreferencesLazy {
        clearOldPrefs()
    }

    // TMDB v3 mirror: API-compatible with api.themoviedb.org/3 but injects the
    // API key server-side, so no key has to ship in the APK.
    override val baseUrl = "https://db.speedracelight.com/3"

    private val apiUrl = baseUrl

    override val lang = "en"
    override val supportsLatest = true

    private val extractor by lazy { RentaroExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET(tabUrl(preferences.popularListPref, PREF_POPULAR_DEFAULT, page))

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    /**
     * Resolves a tab preference to a list endpoint. Both tabs point at real
     * TMDB lists, so a single request is enough; [RentaroFilters.KEY_RECENT]
     * is the one exception and is handled by [getLatestUpdates].
     */
    private fun tabUrl(key: String, fallbackKey: String, page: Int): HttpUrl {
        val path = RentaroFilters.listByKey(key)?.path
            ?: RentaroFilters.listByKey(fallbackKey)?.path
            ?: listOf("trending", "all", "week")
        return listUrl(path, page)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val key = preferences.latestListPref
        // "Recently Released" has no list endpoint: it merges two discover
        // queries sorted by release date.
        if (key == RentaroFilters.KEY_RECENT) {
            val types = if (preferences.latestPref == "movie") {
                listOf("movie", "tv")
            } else {
                listOf("tv", "movie")
            }
            return types.parallelCatchingMapNotNull { mediaType ->
                client.newCall(recentlyReleasedRequest(page, mediaType))
                    .awaitSuccess()
                    .use { latestUpdatesParse(it) }
            }.let { animePages ->
                AnimesPage(
                    animePages.flatMap { it.animes },
                    animePages.any { it.hasNextPage },
                )
            }
        }

        return client.newCall(latestUpdatesRequest(page))
            .awaitSuccess()
            .use { latestUpdatesParse(it) }
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(tabUrl(preferences.latestListPref, PREF_LATEST_LIST_DEFAULT, page))

    /** Newest releases that already aired, with a vote floor to drop noise. */
    private fun recentlyReleasedRequest(page: Int, mediaType: String): Request {
        val isMovie = mediaType == "movie"
        val dateField = if (isMovie) "primary_release_date" else "first_air_date"
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "$dateField.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RECENT_SORT)
            addQueryParameter("$dateField.lte", today())
        }.build()
        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        // The intent filter accepts both schemes, so match both here or an
        // http deep link would fall through to a plain text search.
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = query.toHttpUrl()
            if (url.host !in DEEP_LINK_HOSTS) {
                throw Exception("Unsupported url")
            }
            val type = url.pathSegments.getOrNull(0)
                ?: throw Exception("Unsupported url")
            val rawId = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            if (type !in listOf("movie", "tv")) throw Exception("Unsupported url")
            // TMDB canonical paths append a slug: "/movie/550-fight-club".
            val id = rawId.takeWhile { it.isDigit() }
            if (id.isEmpty()) throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_ID$type/$id", filters)
        }

        // Deep link from RentaroUrlActivity: "id:<type>/<id>"
        if (query.startsWith(PREFIX_ID)) {
            val rawPath = query.substringAfter(PREFIX_ID)
            if (!DEEP_LINK_PATH_REGEX.matches(rawPath)) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$rawPath"
            val tempAnime = SAnime.create().apply { this.url = url }
            return runCatching {
                getAnimeDetails(tempAnime).apply { this.url = url }
                    .let(::listOf)
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val typeIndex = filters.filterIsInstance<RentaroFilters.TypeFilter>()
            .firstOrNull()?.state ?: RentaroFilters.TYPE_ALL
        val isAnimes = typeIndex == RentaroFilters.TYPE_ANIMES
        val isAll = typeIndex == RentaroFilters.TYPE_ALL

        val listIndex = filters.filterIsInstance<RentaroFilters.ListFilter>()
            .firstOrNull()?.state ?: 0
        val selectedList = RentaroFilters.BROWSE_LISTS.getOrNull(listIndex)

        val rawPages: List<PageDto<MediaItemDto>> = if (query.isNotBlank() && isAll) {
            listOfNotNull(fetchMediaPage(textSearchRequest(page, query, "multi")))
        } else if (query.isBlank() && selectedList?.path != null) {
            // A ready-made TMDB list; filters other than Type don't apply.
            listOfNotNull(fetchMediaPage(GET(listUrl(selectedList.path, page))))
        } else {
            val mediaTypes: List<String> = when (typeIndex) {
                RentaroFilters.TYPE_MOVIES -> listOf("movie")
                RentaroFilters.TYPE_TV, RentaroFilters.TYPE_ANIMES -> listOf("tv")
                else -> if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            }
            mediaTypes.parallelMapNotNull { mediaType ->
                val request = if (query.isNotBlank()) {
                    textSearchRequest(page, query, mediaType)
                } else if (selectedList?.key == RentaroFilters.KEY_RECENT) {
                    recentlyReleasedRequest(page, mediaType)
                } else {
                    discoverRequest(page, mediaType, filters, animesOnly = isAnimes)
                }
                fetchMediaPage(request)
            }
        }

        var items = rawPages.flatMap { it.results }
        if (query.isNotBlank() && isAll) {
            items = items.filter { it.mediaType == "movie" || it.mediaType == "tv" }
        }
        // Ready-made lists ignore the discover params, so honour Type here.
        if (query.isBlank() && selectedList?.path != null) {
            items = when (typeIndex) {
                RentaroFilters.TYPE_MOVIES -> items.filter(::isMovieItem)
                RentaroFilters.TYPE_TV, RentaroFilters.TYPE_ANIMES -> items.filterNot(::isMovieItem)
                else -> items
            }
        }
        if (isAnimes) items = items.filter(::isLikelyAnime)
        val hasNextPage = rawPages.any { it.page < it.totalPages }

        return AnimesPage(items.map(::mediaItemToSAnime), hasNextPage)
    }

    /** Curated list endpoints omit `media_type`; fall back to the title field. */
    private fun isMovieItem(media: MediaItemDto): Boolean = media.mediaType?.let { it == "movie" } ?: (media.title != null)

    private fun listUrl(path: List<String>, page: Int): HttpUrl = apiUrl.toHttpUrl().newBuilder().apply {
        path.forEach(::addPathSegment)
        addQueryParameter("language", "en-US")
        addQueryParameter("page", page.toString())
    }.build()

    private suspend fun fetchMediaPage(request: Request): PageDto<MediaItemDto>? = runCatching {
        client.newCall(request).awaitSuccess()
            .parseAs<PageDto<MediaItemDto>>()
    }.getOrNull()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used; getSearchAnime is overridden")

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    private fun textSearchRequest(page: Int, query: String, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
        }.build()
        return GET(url)
    }

    private fun discoverRequest(
        page: Int,
        mediaType: String,
        filters: AnimeFilterList,
        animesOnly: Boolean,
    ): Request {
        val isMovie = mediaType == "movie"

        val sortIndex = filters.filterIsInstance<RentaroFilters.SortFilter>()
            .firstOrNull()?.state ?: RentaroFilters.SORT_POPULAR
        val sortBy = RentaroFilters.sortValue(sortIndex, isMovie)

        val genreMap = if (isMovie) {
            RentaroFilters.MOVIE_GENRE_MAP
        } else {
            RentaroFilters.TV_GENRE_MAP
        }
        val userGenres = filters.filterIsInstance<RentaroFilters.GenreFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.mapNotNull { genreMap[it.name] }
            .orEmpty()
        val genreIds = if (animesOnly) (listOf("16") + userGenres).distinct() else userGenres
        val genreParam = genreIds.joinToString(",")

        val excluded = filters.filterIsInstance<RentaroFilters.ExcludeGenreFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.mapNotNull { genreMap[it.name] }
            ?.filterNot { it in genreIds }
            .orEmpty()
            .joinToString(",")

        val providers = filters.filterIsInstance<RentaroFilters.WatchProviderFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val monetization = filters.filterIsInstance<RentaroFilters.MonetizationFilter>()
            .firstOrNull()?.state
            ?.withIndex()
            ?.filter { it.value.state }
            ?.mapNotNull { RentaroFilters.MONETIZATION_TYPES.getOrNull(it.index)?.second }
            .orEmpty()
            .joinToString("|")

        val regionIndex = filters.filterIsInstance<RentaroFilters.RegionFilter>()
            .firstOrNull()?.state ?: 0
        val region = RentaroFilters.REGIONS.getOrNull(regionIndex)?.second ?: "US"

        val languageIndex = filters.filterIsInstance<RentaroFilters.LanguageFilter>()
            .firstOrNull()?.state ?: 0
        val language = RentaroFilters.LANGUAGES.getOrNull(languageIndex)?.second.orEmpty()

        val year = filters.filterIsInstance<RentaroFilters.YearFilter>()
            .firstOrNull()?.state?.trim().orEmpty()
            .takeIf { it.length == 4 && it.all(Char::isDigit) }
            .orEmpty()

        val minRatingIndex = filters.filterIsInstance<RentaroFilters.MinRatingFilter>()
            .firstOrNull()?.state ?: 0
        val minRating = RentaroFilters.MIN_RATINGS.getOrNull(minRatingIndex).orEmpty()

        val runtimeIndex = filters.filterIsInstance<RentaroFilters.RuntimeFilter>()
            .firstOrNull()?.state ?: 0
        val runtime = RentaroFilters.RUNTIME_RANGES.getOrNull(runtimeIndex)

        val certIndex = filters.filterIsInstance<RentaroFilters.CertificationFilter>()
            .firstOrNull()?.state ?: 0
        val certification = RentaroFilters.CERTIFICATIONS.getOrNull(certIndex).orEmpty()

        val statusIndex = filters.filterIsInstance<RentaroFilters.SeriesStatusFilter>()
            .firstOrNull()?.state ?: 0
        val seriesStatus = RentaroFilters.SERIES_STATUS.getOrNull(statusIndex).orEmpty()

        val seriesTypeIndex = filters.filterIsInstance<RentaroFilters.SeriesTypeFilter>()
            .firstOrNull()?.state ?: 0
        val seriesType = RentaroFilters.SERIES_TYPES.getOrNull(seriesTypeIndex).orEmpty()

        val networks = filters.filterIsInstance<RentaroFilters.NetworkFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())

            if (genreParam.isNotBlank()) addQueryParameter("with_genres", genreParam)
            if (excluded.isNotBlank()) addQueryParameter("without_genres", excluded)

            // "Animes only" already pins the original language to Japanese.
            if (animesOnly) {
                addQueryParameter("with_original_language", "ja")
            } else if (language.isNotBlank()) {
                addQueryParameter("with_original_language", language)
            }

            if (year.isNotBlank()) {
                val yearField = if (isMovie) "primary_release_year" else "first_air_date_year"
                addQueryParameter(yearField, year)
            }

            if (minRating.isNotBlank()) {
                addQueryParameter("vote_average.gte", minRating)
                // Without a vote floor, a single 10/10 vote outranks everything.
                addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RATING_SORT)
            }

            // Availability filters are meaningless without a region.
            if (providers.isNotBlank() || monetization.isNotBlank()) {
                addQueryParameter("watch_region", region)
                if (providers.isNotBlank()) addQueryParameter("with_watch_providers", providers)
                if (monetization.isNotBlank()) {
                    addQueryParameter("with_watch_monetization_types", monetization)
                }
            }

            if (isMovie) {
                runtime?.let { (min, max) ->
                    min?.let { addQueryParameter("with_runtime.gte", it) }
                    max?.let { addQueryParameter("with_runtime.lte", it) }
                }
                if (certification.isNotBlank()) {
                    addQueryParameter("certification_country", "US")
                    addQueryParameter("certification", certification)
                }
            } else {
                if (seriesStatus.isNotBlank()) addQueryParameter("with_status", seriesStatus)
                if (seriesType.isNotBlank()) addQueryParameter("with_type", seriesType)
                if (networks.isNotBlank()) addQueryParameter("with_networks", networks)
            }

            // Rating sorts need a floor; date sorts need an upper bound so
            // unreleased titles don't fill the first pages.
            when {
                RentaroFilters.sortNeedsVoteFloor(sortIndex) && minRating.isBlank() -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RATING_SORT)
                }
                RentaroFilters.sortIsByDate(sortIndex) -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RECENT_SORT)
                    val dateField = if (isMovie) {
                        "primary_release_date.lte"
                    } else {
                        "first_air_date.lte"
                    }
                    addQueryParameter(dateField, today())
                }
            }
        }.build()
        return GET(url)
    }

    private fun today(): String = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.format(Date())
    }

    private fun isLikelyAnime(item: MediaItemDto): Boolean {
        val isJapanese = item.originalLanguage == "ja" || "JP" in item.originCountries
        return isJapanese && ANIMATION_GENRE_ID in item.genreIds
    }

    // ============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = RentaroFilters.getFilterList()

    // ============================== Details ==============================
    // anime.url holds TMDB paths: "/movie/<id>" or "/tv/<id>".
    override fun getAnimeUrl(anime: SAnime): String = TMDB_WEB_URL + anime.url

    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url)
    }

    override fun animeDetailsParse(response: Response): SAnime = try {
        if ("/movie/" in response.request.url.toString()) {
            movieDetailsParse(response)
        } else {
            tvDetailsParse(response)
        }
    } catch (e: Exception) {
        throw Exception("Failed to parse details.", e)
    }

    private fun movieDetailsParse(response: Response): SAnime {
        val movie = response.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/movie/${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            genre = movie.genres.joinToString { it.name }
            status = parseStatus(movie.status)
            description = buildString {
                movie.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    movie.originalTitle?.takeIf {
                        it.isNotBlank() && it.trim() != movie.title.trim()
                    }?.let { "**Original Title:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                movie.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    private fun tvDetailsParse(response: Response): SAnime {
        val tv = response.parseAs<TvDetailDto>()
        return SAnime.create().apply {
            title = tv.name
            url = "/tv/${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            genre = tv.genres.joinToString { it.name }
            status = parseStatus(tv.status)
            description = buildString {
                tv.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf {
                        it.isNotBlank() && it.trim() != tv.name.trim()
                    }?.let { "**Original Name:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                tv.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    // ========================== Related Titles ============================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, _) = animeUrlToId(anime)
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return if (type == "tv") {
            val tv = response.parseAs<TvDetailDto>()
            val extraData = Triple(
                tv.name,
                tv.firstAirDate?.take(4) ?: "",
                tv.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()
            tv.seasons
                .filter { it.seasonNumber > 0 }
                .parallelCatchingFlatMap { season ->
                    val seasonDetail = client.newCall(
                        GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}"),
                    ).awaitSuccess().parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes.map { episode ->
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                            episode_number = episode.episodeNumber.toFloat()
                            scanlator = "Season ${season.seasonNumber}"
                            date_upload = parseDate(episode.airDate)
                            url = "tv/${tv.id}/${season.seasonNumber}/" +
                                "${episode.episodeNumber}#$extraDataEncoded"
                        }
                    }
                }
                .sortedWith(
                    compareByDescending<SEpisode> {
                        it.scanlator?.substringAfter(" ")?.toIntOrNull()
                    }.thenByDescending { it.episode_number },
                )
        } else {
            val movie = response.parseAs<MovieDetailDto>()
            val extraData = Triple(
                movie.title,
                movie.releaseDate?.take(4) ?: "",
                movie.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "movie/${movie.id}#$extraDataEncoded"
                },
            )
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    // ============================ Video Links ============================
    @RequiresApi(Build.VERSION_CODES.N)
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (path, extraDataEncoded) = episode.url.split("#", limit = 2)
        val (title, year, imdbId) =
            extraDataEncoded.parseAs<Triple<String, String, String>>()

        return extractor.videosFromUrl(
            path = path,
            title = title,
            year = year,
            imdbId = imdbId,
            enabledServers = preferences.enabledServerNames,
            subLimit = preferences.subLimitPref.toIntOrNull()
                ?: PREF_SUB_LIMIT_DEFAULT.toInt(),
            qualityPref = preferences.qualityPref,
            enabledNexusProviders = preferences.enabledNexusProviders,
        )
    }

    // ============================== Settings ==============================
    private val SharedPreferences.popularListPref by preferences.delegate(
        PREF_POPULAR_KEY,
        PREF_POPULAR_DEFAULT,
    )
    private val SharedPreferences.latestListPref by preferences.delegate(
        PREF_LATEST_LIST_KEY,
        PREF_LATEST_LIST_DEFAULT,
    )
    private val SharedPreferences.qualityPref by preferences.delegate(
        PREF_QUALITY_KEY,
        PREF_QUALITY_DEFAULT,
    )
    private val SharedPreferences.latestPref by preferences.delegate(
        PREF_LATEST_KEY,
        PREF_LATEST_DEFAULT,
    )
    private val SharedPreferences.subLimitPref by preferences.delegate(
        PREF_SUB_LIMIT_KEY,
        PREF_SUB_LIMIT_DEFAULT,
    )
    private val SharedPreferences.enabledServerNames: Set<String> by preferences.delegate(
        PREF_SERVERS_KEY,
        PREF_SERVERS_DEFAULT,
    )
    private val SharedPreferences.enabledNexusProviders: Set<String> by preferences.delegate(
        PREF_NEXUS_PROVIDERS_KEY,
        RentaroExtractor.NEXUS_PROVIDER_DEFAULT,
    )

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        // Remove the domain setting persisted by earlier versions; the
        // metadata host and player origin are both fixed now.
        if (contains(PREF_DOMAIN_KEY)) {
            edit().remove(PREF_DOMAIN_KEY).apply()
        }

        // Drop server names removed from the catalog; restore defaults
        // if nothing valid remains (catalog pruning happens occasionally).
        val storedServers = getStringSet(PREF_SERVERS_KEY, null)
        if (storedServers != null) {
            val knownServers = RentaroExtractor.SERVER_DISPLAY_NAMES.toSet()
            val validServers = storedServers.intersect(knownServers)
            // Pruning alone can only shrink the set, so newly added servers
            // would stay switched off forever on an existing install. Opt those
            // in once, tracked by key so it happens exactly one time each.
            val pendingOptIn = NEW_SERVERS_OPT_IN.filterNot { (key, _) -> getBoolean(key, false) }
            val added = pendingOptIn.flatMap { (_, names) -> names }.filter { it in knownServers }
            // Conversely, a server force-enabled by an earlier version cannot be
            // pruned once it turns out to be broken, because the name is still
            // valid. Withdraw those once, on the same one-shot basis.
            val pendingOptOut = WITHDRAWN_SERVERS_OPT_OUT
                .filterNot { (key, _) -> getBoolean(key, false) }
            val removed = pendingOptOut.flatMap { (_, names) -> names }.toSet()
            val healed = (validServers + added - removed).ifEmpty { PREF_SERVERS_DEFAULT }

            if (healed != storedServers || pendingOptIn.isNotEmpty() || pendingOptOut.isNotEmpty()) {
                edit().apply {
                    putStringSet(PREF_SERVERS_KEY, healed)
                    pendingOptIn.forEach { (key, _) -> putBoolean(key, true) }
                    pendingOptOut.forEach { (key, _) -> putBoolean(key, true) }
                }.apply()
            }
        }

        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_POPULAR_KEY,
            title = "'Popular' Tab Shows",
            entries = RentaroFilters.TAB_LISTS.map { it.label },
            entryValues = RentaroFilters.TAB_LISTS.map { it.key },
            default = PREF_POPULAR_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LATEST_LIST_KEY,
            title = "'Latest' Tab Shows",
            entries = RentaroFilters.TAB_LISTS.map { it.label },
            entryValues = RentaroFilters.TAB_LISTS.map { it.key },
            default = PREF_LATEST_LIST_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("2160p", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LATEST_KEY,
            title = "Prioritise in mixed lists",
            entries = listOf("Movies", "TV Shows"),
            entryValues = listOf("movie", "tv"),
            default = PREF_LATEST_DEFAULT,
            summary = "Which type comes first when both are shown. Current: %s",
        )

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = "Limit subtitle count. Current: ${preferences.subLimitPref}",
            getSummary = { "Limit subtitle count. Current: $it" },
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
            onChange = { _, newValue ->
                val n = newValue.toIntOrNull()
                (n != null && n >= 0)
            },
        )

        // Display "Name (Language)" but persist bare display names so
        // the catalog code can match them as keys.
        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Servers",
            entries = RentaroExtractor.SERVER_DISPLAY_NAMES.map { name ->
                val suffix = if (name in RentaroExtractor.EXPERIMENTAL_SERVERS) {
                    " - experimental"
                } else {
                    ""
                }
                "$name (${RentaroExtractor.audioLabelFor(name)})$suffix"
            },
            entryValues = RentaroExtractor.SERVER_DISPLAY_NAMES,
            default = PREF_SERVERS_DEFAULT,
            summary = "Select servers to enable. Languages shown per server.",
        )

        // Art fans out to its backend's own scrapers, each a separate upstream
        // request, so they are selectable rather than all-or-nothing.
        screen.addSetPreference(
            key = PREF_NEXUS_PROVIDERS_KEY,
            title = "Art Providers",
            entries = RentaroExtractor.nexusProviderEntries(),
            entryValues = RentaroExtractor.nexusProviderValues(),
            default = RentaroExtractor.NEXUS_PROVIDER_DEFAULT,
            summary = "Which providers Art queries. Each is a separate request, " +
                "so enabling every one slows the video list. Only applies when " +
                "Art is enabled above.",
        )
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results.map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType
            ?: if (media.title != null) "movie" else "tv"
        url = "/$type/${media.id}"
        thumbnail_url = media.posterPath
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun parseStatus(status: String?): Int = when (status) {
        "Released", "Ended" -> SAnime.COMPLETED
        "Returning Series", "In Production" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long = runCatching {
        synchronized(DATE_FORMATTER) {
            DATE_FORMATTER.parse(dateStr ?: "")?.time ?: 0L
        }
    }.getOrDefault(0L)

    companion object {
        // Deep-link prefix shared with RentaroUrlActivity.
        const val PREFIX_ID = "id:"

        private val DEEP_LINK_PATH_REGEX = Regex("""(movie|tv)/\d+""")

        private val animeUrlRegex = Regex("""/(movie|tv)/(\d+)""")

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private const val ANIMATION_GENRE_ID = 16

        // Rating sort uses a higher floor: TMDB has many obscure titles
        // with a handful of perfect-score votes that would dominate.
        private const val MIN_VOTES_FOR_RATING_SORT = "200"
        private const val MIN_VOTES_FOR_RECENT_SORT = "50"

        // Where "Open in browser" sends the user. TMDB ids are native here.
        private const val TMDB_WEB_URL = "https://www.themoviedb.org"

        // Hosts accepted when a URL is pasted into search. Kept in sync with
        // the intent filter in AndroidManifest.xml.
        private val DEEP_LINK_HOSTS = setOf(
            "www.themoviedb.org",
            "themoviedb.org",
        )

        // Retained only so the stale value can be purged from existing installs.
        private const val PREF_DOMAIN_KEY = "pref_domain"

        // Which TMDB list each browse tab shows.
        private const val PREF_POPULAR_KEY = "pref_popular_list"
        private const val PREF_POPULAR_DEFAULT = "trending_all_week"

        private const val PREF_LATEST_LIST_KEY = "pref_latest_list"
        private const val PREF_LATEST_LIST_DEFAULT = RentaroFilters.KEY_RECENT

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val PREF_SERVERS_KEY = "pref_servers_v2"
        private val PREF_SERVERS_DEFAULT =
            setOf("Yoru", "Cypher", "Orion", "Breach", "Vyse", "Art")

        private const val PREF_NEXUS_PROVIDERS_KEY = "pref_nexus_providers"

        /**
         * Servers added after the initial release, keyed by a one-shot marker.
         *
         * Healing the stored set only intersects it with the known catalogue,
         * which can remove names but never add them. Without this, a server
         * introduced later stays disabled on every existing install even though
         * it is in [PREF_SERVERS_DEFAULT]. Each key is set once so a user who
         * deliberately turns the server off is not overridden again.
         */
        private val NEW_SERVERS_OPT_IN = listOf(
            // v8 withdrew Orion while its playback was broken; the DASH fix
            // makes it work, so switch it back on once.
            "pref_optin_orion_dash" to setOf("Orion"),
            // The Nexus backend shipped as "Vega" in v10-v12 and was renamed to
            // "Art" in v13. Pruning drops the stale name and cannot add the new
            // one, and the original opt-in marker is already spent, so this
            // enables it once under the name the catalogue now uses.
            "pref_optin_art" to setOf("Art"),
        )

        /**
         * Servers withdrawn from the defaults, keyed by a one-shot marker.
         *
         * Orion (VidLink) resolves sources correctly but the returned files do
         * not play, so it is no longer enabled out of the box. v6 and v7 had
         * force-enabled it, and pruning cannot undo that because the name is
         * still valid, so it is switched off once here. Anyone who wants to
         * keep testing it can re-enable it and will not be overridden again.
         */
        private val WITHDRAWN_SERVERS_OPT_OUT = emptyList<Pair<String, Set<String>>>()
    }
}
