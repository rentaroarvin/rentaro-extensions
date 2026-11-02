package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private fun b64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

    private val listing = """
        <div class="los">
        <article class="box"><div class="bx"><a class="tip" rel="1" href="https://dramafren.org/series/one/" title="One">
          <img src="https://i0.wp.com/x/one.jpg?resize=200,300"><h2 class="entry-title">One &amp; Two</h2></a></div></article>
        <article class="box"><div class="bx"><a class="tip" rel="2" href="https://dramafren.org/watch/two-full/" title="Two Full">
          <img src="https://i0.wp.com/x/two.jpg"><h2 class="entry-title">Two</h2></a></div></article>
        <article class="box"><div class="bx"><a class="tip" rel="3" href="https://dramafren.org/series/one/" title="Dup">
          <h2 class="entry-title">One again</h2></a></div></article>
        <article class="box"><div class="bx"><a class="tip" rel="4" href="https://elsewhere.test/x/" title="Off"><h2 class="entry-title">Off</h2></a></div></article>
        </div>
        <div class="pagination"><span class="page-numbers current">1</span><a class="next page-numbers" href="https://dramafren.org/page/2/">Next</a></div>
    """

    @Test
    fun `listing keeps series and watch cards once each and reads the pager`() {
        val page = DramaFrenParser.listing(Jsoup.parse(listing))
        assertEquals(listOf("/series/one/", "/watch/two-full/"), page.cards.map { it.url })
        assertEquals("One & Two", page.cards[0].title)
        assertEquals("https://i0.wp.com/x/one.jpg?resize=200,300", page.cards[0].thumbnail)
        assertTrue(page.hasNext)
    }

    @Test
    fun `the last listing page has no next`() {
        assertFalse(DramaFrenParser.listing(Jsoup.parse(listing.replace("next page-numbers", "page-numbers"))).hasNext)
    }

    @Test
    fun `a series page gives its episodes with numbers titles and dates`() {
        val html = """
            <div class="epsdlist"><ul>
              <li data-ID="2"><a href="https://dramafren.org/watch/x-part-2-end/"><div class="l"><div class="epl-num">EP 2</div>
                <div class="epl-title">Part 2 END</div><div class="epl-date">March 23, 2025</div></div></a></li>
              <li class='tseplsfrst' data-ID="1"><a href="https://dramafren.org/watch/x-part-1/"><div class="l"><div class="epl-num">EP 1</div>
                <div class="epl-title">FULL EPISODE</div><div class="epl-date">March 23, 2025</div></div></a></li>
            </ul></div>
            <ul class="data"><li><b>Status:</b> <span class="colspan"><span>Completed</span></span></li></ul>
        """
        val document = Jsoup.parse(html)
        val rows = DramaFrenParser.episodes(document)
        assertEquals(listOf("/watch/x-part-2-end/", "/watch/x-part-1/"), rows.map { it.path })
        assertEquals(listOf(2f, 1f), rows.map { it.number })
        assertEquals("FULL EPISODE", rows[1].title)
        assertEquals("March 23, 2025", rows[0].date)
        assertEquals("Completed", DramaFrenParser.status(document))
    }

    @Test
    fun `a watch page names its series in the breadcrumb`() {
        val html = """
            <a class="breadcrumb-link" href="https://dramafren.org/">Home</a>
            <a class="breadcrumb-link" href="https://dramafren.org/series">TV Series</a>
            <a class="breadcrumb-link" href="https://dramafren.org/series/return-of-his-majesty-chinese-drama/">x</a>
        """
        assertEquals("/series/return-of-his-majesty-chinese-drama/", DramaFrenParser.seriesPath(Jsoup.parse(html)))
        assertNull(DramaFrenParser.seriesPath(Jsoup.parse("<h1>No breadcrumb</h1>")))
    }

    @Test
    fun `mirrors are unwrapped from their base64 iframe`() {
        val abyss = b64("""<iframe src="https://player.abyssplayer.com/2f9I-IVIh" width="100%"></iframe>""")
        val upper = b64("""<IFRAME SRC="https://Smoothpre.com/embed/4gomaqdudh0a" FRAMEBORDER=0></IFRAME>""")
        val html = """<a href="#/" data-em="$upper" data-index="1"> VidHide </a><a href="#/" data-em="$abyss" data-index="2"> Abyss </a><a data-em="@@@@">bad</a>"""
        val mirrors = DramaFrenParser.mirrors(Jsoup.parse(html))
        assertEquals(listOf("VidHide", "Abyss"), mirrors.map { it.label })
        assertEquals("https://Smoothpre.com/embed/4gomaqdudh0a", mirrors[0].url)
        assertEquals("https://player.abyssplayer.com/2f9I-IVIh", mirrors[1].url)
    }

    @Test
    fun `dramawave cards need an id and a title and the pager needs a live next link`() {
        val html = """
            <div id="recent-watched">
            <a href="index.php?page=detail&amp;id=nMiVP1ELMy&amp;lang=en-US"><img src="https://static/c1.jpg"><h3>First</h3></a>
            <a href="index.php?page=detail&amp;id=nMiVP1ELMy&amp;lang=en-US"><img src="https://static/c1.jpg"><h3>First</h3></a>
            <a href="index.php?page=detail&amp;id=q2yZgjdvW3&amp;lang=en-US"><h3>Second</h3></a>
            <a href="index.php?page=detail&amp;id=noTitle"><img src="x"></a>
            <a href="index.php?hp=2#recent-watched">Next &raquo;</a>
            </div>
        """
        val page = DramaWaveParser.listing(Jsoup.parse(html))
        assertEquals(listOf("nMiVP1ELMy", "q2yZgjdvW3"), page.cards.map { it.url })
        assertTrue(page.hasNext)
        val last = DramaWaveParser.listing(Jsoup.parse(html.replace("""<a href="index.php?hp=2#recent-watched">Next &raquo;</a>""", "<span>Next &raquo;</span>")))
        assertFalse(last.hasNext)
    }

    @Test
    fun `dramawave episodes are distinct and ordered with the start button folded in`() {
        val html = """
            <a href="index.php?page=watch&amp;id=F&amp;ep=1&amp;lang=en-US">Start Watching</a>
            <a href="index.php?page=watch&amp;id=F&amp;ep=1&amp;lang=en-US">1</a>
            <a href="index.php?page=watch&amp;id=F&amp;ep=10&amp;lang=en-US">10</a>
            <a href="index.php?page=watch&amp;id=F&amp;ep=2&amp;lang=en-US">2</a>
        """
        assertEquals(listOf(1, 2, 10), DramaWaveParser.episodes(Jsoup.parse(html)))
    }

    @Test
    fun `advanced search writes indexed array keys and pages by path`() {
        val first = Routes.advancedSearch(1, "popular", null)
        assertEquals("/advanced-search/", first.encodedPath)
        assertEquals("series", first.queryParameter("type[0]"))
        assertNull(first.queryParameter("status[0]"))
        val second = Routes.advancedSearch(2, "title", "completed")
        assertEquals("/advanced-search/page/2/", second.encodedPath)
        assertEquals("completed", second.queryParameter("status[0]"))
        assertEquals("title", second.queryParameter("order"))
    }

    @Test
    fun `keyword search pages by path and keeps the query`() {
        assertEquals("https://dramafren.org/?s=Spring%20Time", Routes.keywordSearch(1, "Spring Time").toString().replace("+", "%20"))
        val two = Routes.keywordSearch(2, "Spring")
        assertEquals("/page/2/", two.encodedPath)
        assertEquals("Spring", two.queryParameter("s"))
    }

    @Test
    fun `dramawave list pages with hp and a search carries no page`() {
        assertNull(Routes.dramaWaveList(1, "en-US", null).queryParameter("hp"))
        assertEquals("3", Routes.dramaWaveList(3, "en-US", null).queryParameter("hp"))
        val search = Routes.dramaWaveList(1, "fr-FR", "Sage")
        assertEquals("Sage", search.queryParameter("q"))
        assertEquals("fr-FR", search.queryParameter("lang"))
        assertNull(search.queryParameter("hp"))
    }

    @Test
    fun `watch source requests carry every parameter the site sends`() {
        val url = Routes.watchSource("https://cdn-dramawavev2.dramafren.org/index.php", "FJNTZC0SRa", "3", 2, "en-US")
        assertEquals("watch_source", url.queryParameter("action"))
        assertEquals("FJNTZC0SRa", url.queryParameter("id"))
        assertEquals("3", url.queryParameter("ep"))
        assertEquals("2", url.queryParameter("server"))
        assertEquals("en-US", url.queryParameter("lang"))
    }

    @Test
    fun `a proxied subtitle link is turned back into its open upstream address`() {
        val upstream = "https://video-v6.mydramawave.com/ut/25285/3_3d66e310.srt"
        assertEquals(upstream, Routes.subtitleUrl("?proxy_sub=" + b64(upstream)))
        assertEquals(upstream, Routes.subtitleUrl("?proxy_sub=" + b64(upstream) + "&ext=.vtt"))
        assertNull(Routes.subtitleUrl("?other=1"))
        // Something that decodes but is not a web address is kept on the site's own proxy.
        assertEquals("https://dramawave.dramafren.org/index.php?proxy_sub=" + b64("junk"), Routes.subtitleUrl("?proxy_sub=" + b64("junk")))
    }

    @Test
    fun `hls masters give variants by bandwidth with portrait labels and the audio rendition`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="a/en.aac.m3u8",GROUP-ID="g",NAME="en-US",DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=308577,CODECS="avc1.640015,mp4a.40.2",RESOLUTION=240x426,AUDIO="g"
            v/240.m3u8?x=1
            #EXT-X-STREAM-INF:BANDWIDTH=1336847,CODECS="avc1.640033,mp4a.40.2",RESOLUTION=1080x1920,AUDIO="g"
            v/1080.m3u8?x=1
            #EXT-X-STREAM-INF:BANDWIDTH=483146,RESOLUTION=640x360
            v/none.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5
            v/unknown.m3u8
        """.trimIndent()
        val info = HlsMaster.parse(master, "https://cdn.example.test/x/master.m3u8".toHttpUrl())
        assertEquals(listOf("1080p", "360p", "240p", "Video"), info.variants.map { it.label })
        assertEquals("https://cdn.example.test/x/v/1080.m3u8?x=1", info.variants[0].url)
        assertEquals(1, info.audio.size)
        assertEquals("https://cdn.example.test/x/a/en.aac.m3u8", info.audio[0].url)
        assertEquals("en-US", info.audio[0].name)
    }
}
