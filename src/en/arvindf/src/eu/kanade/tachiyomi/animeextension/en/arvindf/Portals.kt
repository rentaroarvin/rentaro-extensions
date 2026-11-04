package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** How a portal hands out its video. Each value is one contract recorded in the 2026-11-02 capture. */
internal enum class Resolver {
    // HTML/PHP portals whose source comes from an open `cdn-*` resolver pool.
    DRAMABOX,
    NETSHORT,
    FLICKREELS,
    GOODSHORT,
    IDRAMA,
    KALOS,
    SHORTMAX,

    // HTML/PHP portals that put the source in the watch page itself.
    EMBEDDED,
    DRAMAPOPS,

    // Same-origin JSON portals.
    MICRODRAMA,
    SHORTWAVE,
    MOBOREELS,
    SHOTSHORT,
    TVSERIES,
}

/**
 * The browse tabs of TvSeries, with the first page number the portal's own script requests for each.
 * `home` and `movie` answer one page of sections; the two trending tabs are the paged ones.
 */
internal enum class TvFeed(val label: String, val route: String, val paged: Boolean, val firstPage: Int) {
    HOME("Home", "home", false, 1),
    TRENDING("Trending series", "trending", true, 0),
    MOVIES("Movies", "movie", false, 1),
    MOVIE_TRENDING("Trending movies", "movie/trending", true, 1),
}

/** What the user chose for TvSeries: the browse tab, and whether a keyword search looks for series (2) or movies (1). */
internal data class TvOptions(val feed: TvFeed = TvFeed.HOME, val searchType: Int = 2)

/** The query parameter a portal's home page uses to reach its next page. */
internal enum class HomePager { P_HIST, HP, TAB }

/** The query parameter a portal's search results use to reach their next page, if they page at all. */
internal enum class SearchPager { NONE, P, S_PAGE }

internal data class Portal(
    val id: String,
    val label: String,
    val base: String,
    val resolver: Resolver,
    /** The value the portal calls English: `en`, `en_US`, or a number. */
    val lang: String = "en",
    val homePager: HomePager = HomePager.P_HIST,
    /** Whether the portal's own home pager links carry `lang=`. */
    val homeLang: Boolean = false,
    val searchRoute: String = "search_result",
    val searchLang: Boolean = true,
    val searchPager: SearchPager = SearchPager.NONE,
    /** Resolver endpoints, raced together; the first good answer wins. */
    val pool: List<String> = emptyList(),
    /** The query key a JSON portal's routes go under: `api_route`, or `api_path` for ShotShort. */
    val apiParam: String = "api_route",
    /**
     * Whether the media host is sent the portal's own origin as Referer, as a browser playing the portal's
     * page would. Not shown to be required: the only media links captured had expired before they were tried.
     */
    val mediaReferer: Boolean = false,
) {
    val isJson: Boolean
        get() = resolver == Resolver.MICRODRAMA || resolver == Resolver.SHORTWAVE ||
            resolver == Resolver.MOBOREELS || resolver == Resolver.SHOTSHORT || resolver == Resolver.TVSERIES

    /** The portal's own page for a title, which is where "open in browser" should land. */
    fun webUrl(ref: String): String = when {
        resolver == Resolver.SHOTSHORT -> "$base/?bookId=$ref"
        // TvSeries titles are stored as `<subjectId>/<detailPath>` and its page wants them as two parameters.
        resolver == Resolver.TVSERIES -> "$base/".toHttpUrl().newBuilder()
            .addQueryParameter("id", ref.substringBefore('/'))
            .addQueryParameter("detailPath", ref.substringAfter('/', ""))
            .build().toString()
        isJson -> "$base/?id=$ref"
        else -> "$base/$ref"
    }

    /** The home listing. Page 1 is the plain front page, which is what the portal serves first. */
    fun homeUrl(page: Int): HttpUrl {
        if (page <= 1) return "$base/".toHttpUrl()
        val builder = "$base/index.php".toHttpUrl().newBuilder()
        when (homePager) {
            HomePager.P_HIST -> {
                builder.addQueryParameter("page", "home").addQueryParameter("p_hist", page.toString())
                if (homeLang) builder.addQueryParameter("lang", lang)
            }
            HomePager.HP -> builder.addQueryParameter("hp", page.toString())
            HomePager.TAB -> builder.addQueryParameter("page", "home").addQueryParameter("tab", "1").addQueryParameter("p", page.toString())
        }
        return builder.build()
    }

    fun searchUrl(query: String, page: Int): HttpUrl {
        val builder = "$base/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("page", searchRoute)
            .addQueryParameter("q", query)
        if (searchLang) builder.addQueryParameter("lang", lang)
        if (page > 1) {
            when (searchPager) {
                SearchPager.P -> builder.addQueryParameter("p", page.toString())
                SearchPager.S_PAGE -> builder.addQueryParameter("s_page", page.toString())
                SearchPager.NONE -> Unit
            }
        }
        return builder.build()
    }

    /** The portal's JSON route, for the same-origin JSON portals. */
    fun api(route: String, vararg params: Pair<String, String>): HttpUrl {
        val builder = "$base/".toHttpUrl().newBuilder().addQueryParameter(apiParam, route)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    val searchPages: Boolean get() = searchPager != SearchPager.NONE
}

internal object Portals {
    private fun pool(vararg hosts: String) = hosts.map { "https://$it.dramafren.org/index.php" }

    /**
     * The portals of dramafren.org's navigation that answered in the capture and are played here.
     * Left out: RadReel, SnackShort and DreameShort (their hostnames did not resolve) and Viglo (its
     * API answered 404). MoboReels, ShotShort and TvSeries were added from the 2026-11-04 package,
     * which holds their JSON responses; all three sit behind an interactive Cloudflare check, so they
     * are covered by those saved shapes only. TvSeries is landscape TV and films rather than short
     * dramas, added at the user's request.
     */
    val ALL: List<Portal> = listOf(
        Portal("dramabox", "DramaBox", "https://dramabox.dramafren.org", Resolver.DRAMABOX, pool = pool("cdn-dramabox", "cdn-dramaboxv2", "cdn-dramaboxv3")),
        Portal("dramaboxv2", "DramaBox 2", "https://dramaboxv2.dramafren.org", Resolver.DRAMABOX, pool = pool("cdn-dramabox", "cdn-dramaboxv2", "cdn-dramaboxv3")),
        Portal("goodshort", "GoodShort", "https://goodshort.dramafren.org", Resolver.GOODSHORT, pool = pool("cdn-goodshort")),
        Portal("netshort", "NetShort", "https://netshort.dramafren.org", Resolver.NETSHORT, pool = pool("cdn-netshort", "cdn-netshortv2", "cdn-netshortv3")),
        Portal("netshortv2", "NetShort 2", "https://netshortv2.dramafren.org", Resolver.NETSHORT, pool = pool("cdn-netshort", "cdn-netshortv2", "cdn-netshortv3")),
        Portal("flickreels", "FlickReels", "https://flickreels.dramafren.org", Resolver.FLICKREELS, homeLang = true, pool = pool("cdn-flickreels", "cdn-flickreelsv2")),
        Portal("stardusttv", "StarDustTV", "https://stardusttv.dramafren.org", Resolver.EMBEDDED, lang = "en_US", homePager = HomePager.HP, searchPager = SearchPager.P),
        Portal("shortmax", "ShortMax", "https://shortmax.shorttw.live", Resolver.SHORTMAX, pool = pool("cdn-shortmaxv3", "cdn-shortmaxv5", "cdn-shortmaxv6")),
        Portal("shortmaxv2", "ShortMax 2", "https://shortmaxv2.shorttw.live", Resolver.SHORTMAX, searchPager = SearchPager.S_PAGE, pool = pool("cdn-shortmaxv4", "cdn-shortmax")),
        Portal("reelshort", "ReelShort", "https://reelshort.dramafren.org", Resolver.EMBEDDED, homeLang = true),
        Portal("idrama", "iDrama", "https://idrama.dramafren.org", Resolver.IDRAMA, homeLang = true, searchRoute = "search", pool = pool("cdn-idramav2")),
        Portal("starshort", "StarShort", "https://starshort.dramafren.org", Resolver.EMBEDDED, lang = "3"),
        Portal("kalostv", "KalosTV", "https://kalostv.dramafren.org", Resolver.KALOS, pool = pool("cdn-kalostv")),
        Portal("dramabite", "DramaBite", "https://dramabite.dramafren.org", Resolver.EMBEDDED),
        Portal("flextv", "FlexTV", "https://flextv.dramafren.org", Resolver.EMBEDDED, homePager = HomePager.TAB, searchRoute = "search", searchLang = false, searchPager = SearchPager.P),
        Portal("dramapops", "DramaPops", "https://dramapops.dramafren.org", Resolver.DRAMAPOPS, homeLang = true),
        Portal("microdrama", "MicroDrama", "https://microdrama.dramafren.org", Resolver.MICRODRAMA),
        Portal("shortwave", "ShortWave", "https://shortwave.dramafren.org", Resolver.SHORTWAVE),
        Portal("moboreels", "MoboReels", "https://moboreels.dramafren.org", Resolver.MOBOREELS, mediaReferer = true),
        Portal("shotshort", "ShotShort", "https://shotshort.dramafren.org", Resolver.SHOTSHORT, apiParam = "api_path", mediaReferer = true),
        // `en_us` is what its own script sends for English when it asks for a stream and its subtitles.
        Portal("tvseries", "TvSeries", "https://tvseries.dramafren.org", Resolver.TVSERIES, lang = "en_us"),
    )

    fun byId(id: String): Portal? = ALL.firstOrNull { it.id == id }
}

/** Titles and episodes of a portal are stored as `p/<portal id>/<reference>`. */
internal const val PORTAL_PREFIX = "p/"

internal fun portalRef(portal: Portal, reference: String): String = "$PORTAL_PREFIX${portal.id}/$reference"

/** The portal and its own reference out of a stored `p/<id>/<reference>`, or null if it is not one. */
internal fun parsePortalRef(url: String): Pair<Portal, String>? {
    if (!url.startsWith(PORTAL_PREFIX)) return null
    val rest = url.removePrefix(PORTAL_PREFIX)
    val portal = Portals.byId(rest.substringBefore('/')) ?: return null
    return portal to rest.substringAfter('/', "")
}
