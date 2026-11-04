package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real [PortalSource] for ShotShort and MoboReels against a stand-in client that answers with the
 * response shapes of the 2026-11-02 capture and records every request. Nothing here touches the network:
 * both portals sit behind an interactive Cloudflare check, so this is what can be checked without a browser.
 */
class PortalSourceTest {

    private class Seen(val url: HttpUrl, val referer: String?, val range: String?, val method: String = "GET", val body: String? = null)

    private val seen = mutableListOf<Seen>()

    private fun source(answer: (HttpUrl) -> String?): PortalSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val sent = request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }
            seen += Seen(request.url, request.header("Referer"), request.header("Range"), request.method, sent)
            val body = answer(request.url)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200)
                .message("stand-in")
                .body((body ?: "").toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return PortalSource(client, { client }, Headers.headersOf("User-Agent", "test-agent"))
    }

    private fun portal(id: String) = Portals.byId(id) ?: error("no portal $id")

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    // ------------------------------- ShotShort -------------------------------

    private val shotMedia = "https://media.example.test/f0/hd.m3u8?auth_key=1"

    private fun shotshort(
        lock: Boolean = false,
        encrypted: Boolean = false,
        price: Int = 0,
        categoryNeedsType: Boolean = false,
    ) = source { url ->
        if (url.host == "media.example.test") return@source "#EXTM3U\n#EXT-X-VERSION:3\n"
        when (url.queryParameter("api_path")) {
            "/search" -> """{"code":0,"msg":"success","data":{"list":[{"bookId":3519,"bookName":"Love Amidst Hatred","coverUrl":"https://c/1.webp"}]}}"""
            // The live portal answers an empty pageData unless the request carries type=2.
            "/category" -> if (categoryNeedsType && url.queryParameter("type") == "2") {
                """{"code":0,"msg":"success","data":{"localData":[],"pageData":[{"bookId":88,"bookName":"B","coverUrl":"https://c/88.webp"}]}}"""
            } else {
                """{"code":0,"msg":"success","data":{"localData":[],"pageData":[]}}"""
            }
            "/book/3519" -> """{"code":0,"data":{"bookName":"Love Amidst Hatred","recommendIntro":"After an accident","video1stCategory":["Romance"]}}"""
            "/book/3519/episodes" -> """{"code":0,"data":{"episodes":[{"chapterId":56413,"chapterOrder":1},{"chapterId":56414,"chapterOrder":2}]}}"""
            "/book/3519/chapter/56413" -> """{"code":0,"isEncrypt":$encrypted,"data":{"price":$price,"lock":${if (lock) 1 else 0},"hdUrl":"$shotMedia","sdUrl":"","subtitles":[]}}"""
            else -> null
        }
    }

    @Test
    fun `shotshort search lists books as portal references with limit 24 and the english value`() {
        val result = run { shotshort().listing(portal("shotshort"), 1, "love") }
        assertEquals(listOf("p/shotshort/3519"), result.cards.map { it.url })
        assertFalse("one short page is the last", result.hasNext)
        val request = seen.single().url
        assertEquals("/search", request.queryParameter("api_path"))
        assertEquals("love", request.queryParameter("q"))
        assertEquals("24", request.queryParameter("limit"))
        assertEquals("1", request.queryParameter("page"))
        assertEquals("en", request.queryParameter("lang"))
    }

    @Test
    fun `an empty shotshort category is an empty page and not an error`() {
        val result = run { shotshort().listing(portal("shotshort"), 1, null) }
        assertTrue(result.cards.isEmpty())
        assertFalse(result.hasNext)
        assertEquals("All", seen.single().url.queryParameter("category"))
    }

    @Test
    fun `shotshort browse sends type 2 because the category route is empty without it`() {
        val result = run { shotshort(categoryNeedsType = true).listing(portal("shotshort"), 2, null) }
        assertEquals(listOf("p/shotshort/88"), result.cards.map { it.url })
        val request = seen.single().url
        assertEquals("/category", request.queryParameter("api_path"))
        assertEquals("2", request.queryParameter("type"))
        assertEquals("All", request.queryParameter("category"))
        assertEquals("2", request.queryParameter("page"))
        assertEquals("24", request.queryParameter("limit"))
    }

    @Test
    fun `shotshort search does not send a type`() {
        run { shotshort().listing(portal("shotshort"), 1, "love") }
        assertNull(seen.single().url.queryParameter("type"))
    }

    @Test
    fun `shotshort episodes keep the chapter id the player is asked for`() {
        val episodes = run { shotshort().episodes(portal("shotshort"), "3519") }
        assertEquals(listOf("Episode 2", "Episode 1"), episodes.map { it.name })
        assertEquals(listOf("p/shotshort/3519/56414", "p/shotshort/3519/56413"), episodes.map { it.url })
        val detail = run { shotshort().details(portal("shotshort"), "3519") }
        assertEquals("Love Amidst Hatred", detail.title)
        assertEquals("Romance", detail.genre)
    }

    @Test
    fun `shotshort video asks the chapter route and the media host gets the portal origin as referer`() {
        val videos = run { shotshort().videos(portal("shotshort"), "3519/56413") }
        assertEquals(listOf("ShotShort · HD"), videos.map { it.videoTitle })
        assertEquals(shotMedia, videos.single().videoUrl)
        assertEquals("https://shotshort.dramafren.org/", videos.single().headers?.get("Referer"))
        assertEquals("test-agent", videos.single().headers?.get("User-Agent"))
        assertEquals("/book/3519/chapter/56413", seen.first { it.url.host == "shotshort.dramafren.org" }.url.queryParameter("api_path"))
        val probe = seen.first { it.url.host == "media.example.test" }
        assertEquals("https://shotshort.dramafren.org/", probe.referer)
        assertEquals("bytes=0-63", probe.range)
    }

    @Test
    fun `shotshort reports a locked or encrypted episode and never asks the media host`() {
        val locked = assertThrows(Exception::class.java) { run { shotshort(price = 5).videos(portal("shotshort"), "3519/56413") } }
        assertTrue(locked.message!!.contains("unlock"))
        val encrypted = assertThrows(Exception::class.java) { run { shotshort(encrypted = true).videos(portal("shotshort"), "3519/56413") } }
        assertTrue(encrypted.message!!.contains("encrypted"))
        assertTrue(seen.none { it.url.host == "media.example.test" })
        assertThrows(Exception::class.java) { run { shotshort().videos(portal("shotshort"), "3519") } }
    }

    // ------------------------------- MoboReels -------------------------------

    private fun moboreels(firstSourceWorks: Boolean = true) = source { url ->
        if (url.host == "cdnvideo.example.test") {
            return@source if (url.encodedPath == "/dead.mp4" && !firstSourceWorks) null else "\u0000\u0000\u0000\u0018ftypmp42"
        }
        when (url.queryParameter("api_route")) {
            "channels" -> """{"code":200,"data":{"channels":[{"channelId":"148131293978560769","title":"Home","isDefault":1},{"channelId":"113757996389499537","title":"New","isDefault":0}]}}"""
            "list" -> """{"code":200,"data":{"sections":[{"title":"A","series":[{"seriesId":44277322,"seriesName":"Bind By Contract Or Love","coverUrl":"https://c/a.jpg"}]}]}}"""
            "search" -> """{"code":200,"data":{"pageNo":${url.queryParameter("pageNo")},"pageSize":20,"total":45,"items":[{"seriesId":1,"seriesName":"One"}]}}"""
            "detail" -> """{"code":200,"data":{"seriesName":"Bind By Contract Or Love","description":"To secure his inheritance","genres":["CEO"]}}"""
            "episodes" -> """{"code":200,"data":{"items":[{"episNum":1,"isLock":false},{"episNum":10,"isLock":true,"price":88}]}}"""
            "video" -> if (url.queryParameter("fallback") == "1") {
                """{"data":{"mediaUrl":"https://cdnvideo.example.test/f0.mp4?t=1","source":"fallback"}}"""
            } else {
                """{"data":{"episMedia":[{"codec":"h264","resolution":"720","mediaUrl":"https://cdnvideo.example.test/dead.mp4"}]}}"""
            }
            else -> null
        }
    }

    @Test
    fun `the moboreels front page is the default channel's sections and is a single page`() {
        val listing = run { moboreels().listing(portal("moboreels"), 1, null) }
        assertEquals(listOf("p/moboreels/44277322"), listing.cards.map { it.url })
        assertFalse(listing.hasNext)
        assertEquals(listOf("channels", "list"), seen.map { it.url.queryParameter("api_route") })
        assertEquals("148131293978560769", seen[1].url.queryParameter("channelId"))
        seen.clear()
        assertTrue(run { moboreels().listing(portal("moboreels"), 2, null) }.cards.isEmpty())
        assertTrue("page 2 of a sections feed makes no request", seen.isEmpty())
    }

    @Test
    fun `moboreels search pages with pageNo`() {
        val second = run { moboreels().listing(portal("moboreels"), 2, "love") }
        assertEquals("2", seen.single().url.queryParameter("pageNo"))
        assertEquals("love", seen.single().url.queryParameter("q"))
        assertTrue(second.hasNext)
        assertFalse(run { moboreels().listing(portal("moboreels"), 3, "love") }.hasNext)
    }

    @Test
    fun `moboreels marks flagged episodes as locked and keeps the free flag in the reference`() {
        val episodes = run { moboreels().episodes(portal("moboreels"), "44277322") }
        assertEquals(listOf("Episode 10", "Episode 1"), episodes.map { it.name })
        assertEquals(listOf("p/moboreels/44277322/10/0", "p/moboreels/44277322/1/1"), episodes.map { it.url })
        assertEquals(listOf("Locked", null), episodes.map { it.note })
        assertEquals("CEO", run { moboreels().details(portal("moboreels"), "44277322") }.genre)
    }

    @Test
    fun `a locked moboreels episode is reported without asking for its video`() {
        val failure = assertThrows(Exception::class.java) { run { moboreels().videos(portal("moboreels"), "44277322/10/0") } }
        assertTrue(failure.message!!.contains("coin"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `moboreels plays the first source and sends the portal origin to the media host`() {
        val videos = run { moboreels().videos(portal("moboreels"), "44277322/1/1") }
        assertEquals(listOf("MoboReels · 720p"), videos.map { it.videoTitle })
        assertEquals("https://moboreels.dramafren.org/", videos.single().headers?.get("Referer"))
        assertTrue("no fallback when the first source opens", seen.none { it.url.queryParameter("fallback") == "1" })
        val request = seen.first { it.url.queryParameter("api_route") == "video" }.url
        assertEquals("44277322", request.queryParameter("id"))
        assertEquals("1", request.queryParameter("episNum"))
    }

    // ------------------------------- TvSeries -------------------------------

    private val tvMedia = "https://indocast.example.test/api/proxy?url=a&provider=x&type=mp4"

    private fun tvseries(playable: Boolean = true) = source { url ->
        if (url.host == "indocast.example.test") return@source "\u0000\u0000\u0000 ftypisom"
        when (url.queryParameter("api_route")) {
            "home" -> """{"code":0,"message":"ok","data":{"hero_banners":[{"subjectId":"4780148614284956296","detailPath":"dead-end-job-oAmYsY887H5","subjectType":2,"title":"Dead-End Job","cover_url":"https://c/d.jpg"}],"content_sections":[{"items":[{"subjectId":"4780148614284956296","detailPath":"dead-end-job-oAmYsY887H5","title":"Dead-End Job dup"},{"subjectId":"6026412232966389904","detailPath":"spider-man-Aea8ARY1bb7","subjectType":1,"title":"Spider-Man"}]}]}}"""
            "trending", "movie/trending" -> """{"code":0,"data":{"items":[{"subjectId":"1","detailPath":"a-b","title":"A"}],"pager":{"page":${url.queryParameter("page")},"hasMore":true,"nextPage":2}}}"""
            "search" -> """{"code":0,"data":{"items":[{"subjectId":"7007542999475706272","detailPath":"love-67wpXcwCEl8","subjectType":2,"title":"Love (Ft. Marriage & Divorce) S1-S3","cover_url":"https://c/l.jpg"}],"pagination":{"page":1,"hasMore":false}}}"""
            "details" -> if (url.queryParameter("detailPath") == "spider-man-Aea8ARY1bb7") {
                """{"code":0,"message":"ok","data":{"subjectId":"6026412232966389904","subjectType":1,"title":"Spider-Man","seasons_list":[]}}"""
            } else {
                """{"code":0,"message":"ok","data":{"subjectId":"7007542999475706272","detailPath":"love-67wpXcwCEl8","subjectType":2,"title":"Love (Ft. Marriage & Divorce) S1-S3","description":"Semuanya terungkap","releaseDate":"2021-01-23","genre":"Drama,Percintaan","country":"Korea","seasons_list":[{"season":1,"total_episodes":2,"episodes":[1,2]},{"season":2,"total_episodes":1,"episodes":[1]}]}}"""
            }
            "getplay" -> if (playable) {
                """{"code":0,"message":"ok","data":{"quality":480,"format":"MP4","vid_url":"https://bcdn.example.test/x.mp4?sign=1","vid_url_proxy":"$tvMedia","hls":[{"resolutions":"360","url":"https://bcdn.example.test/y.mp4","url_proxy":"https://indocast.example.test/api/proxy?url=b&provider=x&type=mp4"}],"subtitles":[{"lang":"ar","label":"Arabic","url":"https://s/ar.srt","url_proxy":"https://indocast.example.test/s/ar"},{"lang":"en","label":"English","url":"https://s/en.srt","url_proxy":"https://indocast.example.test/s/en"}]}}"""
            } else {
                """{"code":0,"message":"stream video kosong","data":{"hls":[]}}"""
            }
            else -> null
        }
    }

    @Test
    fun `tvseries home is one page of sections and a second page makes no request`() {
        val home = run { tvseries().listing(portal("tvseries"), 1, null) }
        assertEquals(listOf("p/tvseries/4780148614284956296/dead-end-job-oAmYsY887H5", "p/tvseries/6026412232966389904/spider-man-Aea8ARY1bb7"), home.cards.map { it.url })
        assertFalse(home.hasNext)
        assertEquals("home", seen.single().url.queryParameter("api_route"))
        seen.clear()
        assertTrue(run { tvseries().listing(portal("tvseries"), 2, null) }.cards.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `tvseries trending pages start at 0 for series and 1 for movies with 18 per page`() {
        val series = run { tvseries().listing(portal("tvseries"), 1, null, TvOptions(TvFeed.TRENDING)) }
        assertTrue(series.hasNext)
        assertEquals("trending", seen.last().url.queryParameter("api_route"))
        assertEquals("0", seen.last().url.queryParameter("page"))
        assertEquals("18", seen.last().url.queryParameter("perPage"))
        run { tvseries().listing(portal("tvseries"), 3, null, TvOptions(TvFeed.TRENDING)) }
        assertEquals("2", seen.last().url.queryParameter("page"))
        run { tvseries().listing(portal("tvseries"), 1, null, TvOptions(TvFeed.MOVIE_TRENDING)) }
        assertEquals("movie/trending", seen.last().url.queryParameter("api_route"))
        assertEquals("1", seen.last().url.queryParameter("page"))
    }

    @Test
    fun `tvseries search posts json with string values and the chosen type`() {
        val result = run { tvseries().listing(portal("tvseries"), 2, "love", TvOptions(searchType = 2)) }
        assertEquals(listOf("p/tvseries/7007542999475706272/love-67wpXcwCEl8"), result.cards.map { it.url })
        assertFalse(result.hasNext)
        val sent = seen.single()
        assertEquals("POST", sent.method)
        assertEquals("search", sent.url.queryParameter("api_route"))
        assertEquals("""{"keyword":"love","page":"2","perPage":"28","subjectType":"2"}""", sent.body)
        run { tvseries().listing(portal("tvseries"), 1, "love", TvOptions(searchType = 1)) }
        assertTrue(seen.last().body!!.contains(""""subjectType":"1""""))
    }

    @Test
    fun `a tvseries series lists every season episode and a movie is one entry`() {
        val ref = "7007542999475706272/love-67wpXcwCEl8"
        val episodes = run { tvseries().episodes(portal("tvseries"), ref) }
        assertEquals(listOf("S2 E1", "S1 E2", "S1 E1"), episodes.map { it.name })
        assertEquals(listOf(3f, 2f, 1f), episodes.map { it.number })
        assertEquals("p/tvseries/$ref/2/1", episodes.first().url)
        val details = seen.first().url
        assertEquals("7007542999475706272", details.queryParameter("id"))
        assertEquals("love-67wpXcwCEl8", details.queryParameter("detailPath"))
        val movie = run { tvseries().episodes(portal("tvseries"), "6026412232966389904/spider-man-Aea8ARY1bb7") }
        assertEquals(listOf("Movie"), movie.map { it.name })
        assertEquals("p/tvseries/6026412232966389904/spider-man-Aea8ARY1bb7/0/0", movie.single().url)
    }

    @Test
    fun `tvseries details carry the year country genres and the description`() {
        val detail = run { tvseries().details(portal("tvseries"), "7007542999475706272/love-67wpXcwCEl8") }
        assertEquals("Love (Ft. Marriage & Divorce) S1-S3", detail.title)
        assertEquals("Drama, Percintaan", detail.genre)
        assertEquals("2021 · Korea\n\nSemuanya terungkap", detail.description)
    }

    @Test
    fun `tvseries video asks getplay with the ids season episode and the en_us language`() {
        val videos = run { tvseries().videos(portal("tvseries"), "7007542999475706272/love-67wpXcwCEl8/1/2") }
        val request = seen.first { it.url.queryParameter("api_route") == "getplay" }.url
        assertEquals("7007542999475706272", request.queryParameter("subjectId"))
        assertEquals("love-67wpXcwCEl8", request.queryParameter("detailPath"))
        assertEquals("1", request.queryParameter("se"))
        assertEquals("2", request.queryParameter("ep"))
        assertEquals("en_us", request.queryParameter("lang"))
        assertEquals(tvMedia, videos.first { it.videoTitle.endsWith("480p") }.videoUrl)
        // Only the relay opens in this stand-in; the direct hosts are never offered when they do not.
        assertTrue(videos.all { it.videoUrl.startsWith("https://indocast.example.test/") })
        assertEquals(listOf("English", "Arabic"), videos.first().subtitleTracks.map { it.lang })
    }

    @Test
    fun `a tvseries episode with no stream is reported with the portal's own message`() {
        val failure = assertThrows(Exception::class.java) { run { tvseries(playable = false).videos(portal("tvseries"), "7007542999475706272/love-67wpXcwCEl8/1/1") } }
        assertTrue(failure.message!!, failure.message!!.contains("stream video kosong"))
        assertThrows(Exception::class.java) { run { tvseries().videos(portal("tvseries"), "7007542999475706272/love-67wpXcwCEl8") } }
        assertThrows(Exception::class.java) { run { tvseries().episodes(portal("tvseries"), "no-slash") } }
    }

    @Test
    fun `moboreels asks again with fallback when the first source does not open`() {
        val videos = run { moboreels(firstSourceWorks = false).videos(portal("moboreels"), "44277322/1/1") }
        assertEquals(listOf("https://cdnvideo.example.test/f0.mp4?t=1"), videos.map { it.videoUrl })
        assertEquals(listOf(null, "1"), seen.filter { it.url.queryParameter("api_route") == "video" }.map { it.url.queryParameter("fallback") })
    }
}
