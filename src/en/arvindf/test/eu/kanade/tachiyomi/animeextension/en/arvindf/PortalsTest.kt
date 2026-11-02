package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PortalsTest {

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun portal(id: String) = Portals.byId(id) ?: error("no portal $id")

    // ------------------------------- table -------------------------------

    @Test
    fun `every portal has a unique id and a reachable shape`() {
        val ids = Portals.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Portals.ALL.forEach { portal ->
            assertTrue("${portal.id} base", portal.base.startsWith("https://"))
            val needsPool = portal.resolver in setOf(
                Resolver.DRAMABOX, Resolver.NETSHORT, Resolver.FLICKREELS, Resolver.GOODSHORT,
                Resolver.IDRAMA, Resolver.KALOS, Resolver.SHORTMAX,
            )
            assertEquals("${portal.id} pool", needsPool, portal.pool.isNotEmpty())
        }
    }

    @Test
    fun `the portals left out are not offered`() {
        listOf("radreel", "snackshort", "dreameshort", "viglo", "moboreels", "shotshort", "tvseries").forEach {
            assertNull(it, Portals.byId(it))
        }
    }

    @Test
    fun `portal references round trip and reject anything else`() {
        val gs = portal("goodshort")
        val stored = portalRef(gs, "index.php?page=watch&id=1&ep=0")
        val (back, ref) = parsePortalRef(stored)!!
        assertEquals("goodshort", back.id)
        assertEquals("index.php?page=watch&id=1&ep=0", ref)
        assertNull(parsePortalRef("/series/x/"))
        assertNull(parsePortalRef("p/nosuchportal/x"))
    }

    // ------------------------------ urls ------------------------------

    @Test
    fun `home pagers follow each portal's own parameter`() {
        assertEquals("https://goodshort.dramafren.org/", portal("goodshort").homeUrl(1).toString())
        assertEquals("p_hist", "p_hist".takeIf { portal("goodshort").homeUrl(2).queryParameter("p_hist") == "2" })
        assertNull(portal("goodshort").homeUrl(2).queryParameter("lang"))
        assertEquals("en", portal("flickreels").homeUrl(2).queryParameter("lang"))
        assertEquals("2", portal("stardusttv").homeUrl(2).queryParameter("hp"))
        val flex = portal("flextv").homeUrl(3)
        assertEquals("1", flex.queryParameter("tab"))
        assertEquals("3", flex.queryParameter("p"))
    }

    @Test
    fun `search urls use each portal's route and english value`() {
        val box = portal("dramabox").searchUrl("love", 1)
        assertEquals("search_result", box.queryParameter("page"))
        assertEquals("en", box.queryParameter("lang"))
        assertEquals("en_US", portal("stardusttv").searchUrl("love", 1).queryParameter("lang"))
        assertEquals("3", portal("starshort").searchUrl("love", 1).queryParameter("lang"))
        val idrama = portal("idrama").searchUrl("love", 1)
        assertEquals("search", idrama.queryParameter("page"))
        val flex = portal("flextv").searchUrl("love", 2)
        assertEquals("search", flex.queryParameter("page"))
        assertNull(flex.queryParameter("lang"))
        assertEquals("2", flex.queryParameter("p"))
        assertEquals("2", portal("shortmaxv2").searchUrl("love", 2).queryParameter("s_page"))
        assertNull(portal("goodshort").searchUrl("love", 2).queryParameter("p"))
    }

    @Test
    fun `json portals build their routes against the root`() {
        val url = portal("shortwave").api("detail", "lang" to "en", "id" to "abc")
        assertEquals("https://shortwave.dramafren.org/", url.toString().substringBefore('?'))
        assertEquals("detail", url.queryParameter("api_route"))
        assertEquals("abc", url.queryParameter("id"))
    }

    // ------------------------------ html ------------------------------

    private val card = """
        <a href="index.php?page=detail&amp;id=31001791856&amp;lang=en&amp;slug=x&amp;sv=1" class="c"><div><img src="https://img/x.jpg" alt="Alt Title"></div><div><h3>Real Title</h3></div></a>
        <a href="index.php?page=detail&amp;id=31001791856&amp;lang=en&amp;slug=x&amp;sv=1">dup</a>
        <a href="index.php?page=detail&amp;id=&amp;lang=en"><img src="e.jpg" alt="Blank"></a>
        <a href="index.php?page=detail&amp;id=2"><img src="y.jpg" alt="Only Alt"></a>
        <a href="index.php?page=home&amp;p_hist=2">Next &raquo;</a>
    """

    @Test
    fun `cards need an id, prefer the heading, fall back to alt, and dedupe`() {
        val doc = Jsoup.parse(card)
        val cards = PortalHtml.cards(doc)
        assertEquals(listOf("Real Title", "Only Alt"), cards.map { it.title })
        assertEquals("index.php?page=detail&id=31001791856&lang=en&slug=x&sv=1", cards[0].url)
        assertEquals("https://img/x.jpg", cards[0].thumbnail)
        assertTrue(PortalHtml.hasNext(doc))
        assertFalse(PortalHtml.hasNext(Jsoup.parse("""<a href="index.php?page=detail&id=1">x</a>""")))
    }

    @Test
    fun `episodes are numbered from the link text, with start watching folded in`() {
        val html = """
            <a href="index.php?page=watch&amp;id=9&amp;ep=0&amp;lang=en">Start Watching</a>
            <a href="index.php?page=watch&amp;id=9&amp;ep=0&amp;lang=en"> 001 </a>
            <a href="index.php?page=watch&amp;id=9&amp;ep=1&amp;lang=en"> 002 </a>
        """
        val eps = PortalHtml.episodes(Jsoup.parse(html))
        assertEquals(listOf(1, 2), eps.map { it.number })
        assertEquals("index.php?page=watch&id=9&ep=0&lang=en", eps[0].href)
    }

    @Test
    fun `flextv episodes use the display number from no`() {
        val html = """
            <a href="index.php?page=watch&amp;id=15853&amp;ep_id=1069668&amp;no=1">Start Watching</a>
            <a href="index.php?page=watch&amp;id=15853&amp;ep_id=1069668&amp;no=1"> 1 </a>
            <a href="index.php?page=watch&amp;id=15853&amp;ep_id=1069707&amp;no=40"> 40 </a>
        """
        assertEquals(listOf(1, 40), PortalHtml.episodes(Jsoup.parse(html)).map { it.number })
    }

    @Test
    fun `a detail page gives title, description and only real genres`() {
        val html = """
            <meta property="og:image" content="https://img/poster.jpg">
            <h1>Love Story</h1>
            <span class="bg-blue-900">Total: 60 Eps</span>
            <span class="bg-slate-700">Second Chance, Amnesia, Romance</span>
            <span class="bg-slate-700">Server: Primary</span>
            <p class="text-slate-300 leading-relaxed">A long story.</p>
        """
        val detail = PortalHtml.detail(Jsoup.parse(html))
        assertEquals("Love Story", detail.title)
        assertEquals("https://img/poster.jpg", detail.poster)
        assertEquals("A long story.", detail.description)
        assertEquals("Second Chance, Amnesia, Romance", detail.genre)
        assertNull(PortalHtml.detail(Jsoup.parse("""<h1>x</h1><p class="leading-relaxed">Description unavailable.</p>""")).description)
    }

    @Test
    fun `goodshort chapter ids come from the watch page in order`() {
        val html = "<script>const chapterIds = ['14226156','14226157', \"14226158\"];</script>"
        assertEquals(listOf("14226156", "14226157", "14226158"), PortalHtml.chapterIds(html))
        assertTrue(PortalHtml.chapterIds("<script>var x=1</script>").isEmpty())
    }

    @Test
    fun `embedded sources are unwrapped from the three page shapes`() {
        val m3u8 = "https://acdn.example/prod/1/a.m3u8"
        assertEquals(listOf(m3u8), PortalHtml.embedded("""const encryptedSrc = "${b64(m3u8)}";""").map { it.url })
        assertEquals(listOf(m3u8), PortalHtml.embedded("""videoSrc = atob("${b64(m3u8)}");""").map { it.url })
        val quality = """const availableQualities = [{"quality":"720p","hash64":"${b64("https://v/720.m3u8")}"},{"quality":"0p","hash64":"${b64("https://v/auto.m3u8")}"}];"""
        val streams = PortalHtml.embedded(quality + """ videoSrc = atob("${b64("https://v/main.m3u8")}");""")
        assertEquals(listOf("720p", "Video", "Video"), streams.map { it.label })
        assertEquals(3, streams.size)
        assertTrue(PortalHtml.embedded("""videoSrc = atob("${b64("not a url")}");""").isEmpty())
    }

    @Test
    fun `an empty embedded source is recognised as no video`() {
        assertTrue(PortalHtml.hasEmptySource("""var videoSrc = atob("");"""))
        assertTrue(PortalHtml.hasEmptySource("""const encryptedSrc = "";"""))
        assertFalse(PortalHtml.hasEmptySource("""var videoSrc = atob("aHR0cHM6Ly9h");"""))
    }

    @Test
    fun `dramapops reads the source and unwraps each subtitle proxy`() {
        val html = """
            <video><source src="https://test.example/v/?X-Amz=1"></video>
            <track label="French" src="/index.php?action=proxy_sub&url=https%3A%2F%2Ftest.example%2Ffr.vtt">
            <track label="English" src="/index.php?action=proxy_sub&url=https%3A%2F%2Ftest.example%2Fen.vtt">
        """
        val resolved = PortalHtml.dramapops(Jsoup.parse(html), "https://dramapops.dramafren.org")!!
        assertEquals("https://test.example/v/?X-Amz=1", resolved.streams.single().url)
        assertEquals(listOf("English", "French"), resolved.subs.map { it.label })
        assertEquals("https://test.example/en.vtt", resolved.subs[0].url)
        assertNull(PortalHtml.dramapops(Jsoup.parse("<p>none</p>"), "https://x"))
    }

    // ------------------------------ json ------------------------------

    @Test
    fun `json dramas keep ids as text and drop broken rows`() {
        val root = Js.parse("""{"dramas":[{"id":"2115001430171680770","title":"A","cover":"c"},{"id":"1"},{"title":"no id"},{"id":"2115001430171680770","title":"A again"}]}""").asObject()!!
        val cards = PortalJson.dramas(root)
        assertEquals(1, cards.size)
        assertEquals("2115001430171680770", cards[0].url)
    }

    // ----------------------------- resolvers -----------------------------

    private fun obj(json: String) = Js.parse(json).asObject()!!

    @Test
    fun `dramabox style answers give each quality`() {
        val r = PortalResolvers.pool(
            Resolver.DRAMABOX,
            obj("""{"ok":true,"videoUrl":"https://v/a.mp4","qualities":[{"quality":"Server 1 720p","url":"https://v/a.mp4"},{"quality":"Server 1 1080p","url":"https://v/b.mp4"}],"subtitles":[]}"""),
        )!!
        assertEquals(listOf("Server 1 720p", "Server 1 1080p"), r.streams.map { it.label })
        assertNull(PortalResolvers.pool(Resolver.DRAMABOX, obj("""{"ok":false,"videoUrl":"https://v/a.mp4"}""")))
    }

    @Test
    fun `netshort carries its subtitle and falls back to the single link`() {
        val r = PortalResolvers.pool(
            Resolver.NETSHORT,
            obj("""{"ok":true,"server":1,"videoUrl":"https://v/n.mp4","qualities":[],"subtitles":[{"subtitleLanguage":"en_US","url":"https://s/en.srt"},{"subtitleLanguage":"x","url":"relative"}]}"""),
        )!!
        assertEquals("https://v/n.mp4", r.streams.single().url)
        assertEquals(listOf("en_US"), r.subs.map { it.label })
    }

    @Test
    fun `kalostv and idrama read their own url fields`() {
        val kalos = PortalResolvers.pool(Resolver.KALOS, obj("""{"ok":true,"url":"https://k/a.m3u8","qualities":[{"quality":"H.264 720p","url":"https://k/a.m3u8"},{"quality":"H.264 540p","url":"https://k/b.m3u8"}]}"""))!!
        assertEquals(2, kalos.streams.size)
        val idrama = PortalResolvers.pool(Resolver.IDRAMA, obj("""{"ok":true,"video_url":"https://c/p?url=1","video_urls":["https://c/p?url=1","https://p/p?url=1"]}"""))!!
        assertEquals(listOf("https://c/p?url=1", "https://p/p?url=1"), idrama.streams.map { it.url })
    }

    @Test
    fun `flickreels and goodshort use their own envelopes`() {
        val flick = PortalResolvers.pool(Resolver.FLICKREELS, obj("""{"status_code":1,"data":{"url":"https://f/a.m3u8","subtitles":[]}}"""))!!
        assertEquals("https://f/a.m3u8", flick.streams.single().url)
        assertNull(PortalResolvers.pool(Resolver.FLICKREELS, obj("""{"status_code":0,"data":{"url":"https://f/a.m3u8"}}""")))
        val good = PortalResolvers.pool(Resolver.GOODSHORT, obj("""{"status":"success","video_url":"https://g/p?token=1","qualities":[{"quality":"720P","url":"https://g/p?token=1"}]}"""))!!
        assertEquals("720P", good.streams.single().label)
        assertNull(PortalResolvers.pool(Resolver.GOODSHORT, obj("""{"status":"error"}""")))
    }

    @Test
    fun `shortmax prefers the play url and skips an unavailable server`() {
        val ok = PortalResolvers.pool(
            Resolver.SHORTMAX,
            obj("""{"ok":true,"server":{"available":true,"playUrl":"https://s/720.m3u8","qualities":[{"quality":"720p","url":"https://s/720.m3u8","playUrl":"https://s/720p.m3u8"},{"quality":"1080p","url":"https://s/1080.m3u8"}]}}"""),
        )!!
        assertEquals(listOf("https://s/720p.m3u8", "https://s/1080.m3u8"), ok.streams.map { it.url })
        assertNull(PortalResolvers.pool(Resolver.SHORTMAX, obj("""{"ok":true,"server":{"available":false,"qualities":[]}}""")))
    }

    @Test
    fun `shortwave decodes the wrapped playlist and rejects a failure`() {
        val ok = PortalResolvers.shortwave(obj("""{"code":0,"data":{"play_url_encoded":"${b64("https://api.example/v1/play/x/v.m3u8?k=1")}"}}"""))!!
        assertEquals("https://api.example/v1/play/x/v.m3u8?k=1", ok.streams.single().url)
        assertNull(PortalResolvers.shortwave(obj("""{"code":1,"data":{}}""")))
        assertNull(PortalResolvers.shortwave(obj("""{"code":0,"data":{"play_url_encoded":"${b64("junk")}"}}""")))
    }

    @Test
    fun `microdrama lists every quality of an episode`() {
        val r = PortalResolvers.microdrama(obj("""{"index":1,"videos":[{"quality":"540P","url":"https://m/540.mp4"},{"quality":"720P","url":"https://m/720.mp4"},{"quality":"bad"}]}"""))!!
        assertEquals(listOf("540P", "720P"), r.streams.map { it.label })
    }

    @Test
    fun `pool requests carry each portal's own parameters`() {
        val box = PortalResolvers.poolUrl(Resolver.DRAMABOX, "en", "https://cdn-dramabox.dramafren.org/index.php", "41000122380", "2", null)!!
        assertEquals("get_video", box.queryParameter("action"))
        assertEquals("2", box.queryParameter("ep"))
        assertEquals("1", box.queryParameter("sv"))
        val good = PortalResolvers.poolUrl(Resolver.GOODSHORT, "en", "https://cdn-goodshort.dramafren.org/index.php", "31", "0", "14226156")!!
        assertEquals("14226156", good.queryParameter("chap_id"))
        assertNull(PortalResolvers.poolUrl(Resolver.GOODSHORT, "en", "https://cdn-goodshort.dramafren.org/index.php", "31", "0", null))
        assertEquals("server1", PortalResolvers.poolUrl(Resolver.SHORTMAX, "en", "https://cdn-shortmaxv3.dramafren.org/index.php", "47304", "1", null)!!.queryParameter("server"))
        assertEquals("0", PortalResolvers.poolUrl(Resolver.FLICKREELS, "en", "https://cdn-flickreels.dramafren.org/index.php", "10486", "1", null)!!.queryParameter("server"))
        assertNull(PortalResolvers.poolUrl(Resolver.MICRODRAMA, "en", "https://x/index.php", "1", "1", null))
    }

    @Test
    fun `hls links get a client hint unless they already say so`() {
        assertEquals("https://g/proxy?token=a#.m3u8", PortalResolvers.hlsUrl("https://g/proxy?token=a"))
        assertEquals("https://k/a.m3u8?Expires=1", PortalResolvers.hlsUrl("https://k/a.m3u8?Expires=1"))
        assertEquals("https://g/p#x", PortalResolvers.hlsUrl("https://g/p#x"))
    }

    @Test
    fun `quality ranking reads the largest p value`() {
        assertEquals(1080, PortalResolvers.rank("Server 1 1080p"))
        assertEquals(720, PortalResolvers.rank("H.264 720p"))
        assertEquals(0, PortalResolvers.rank("Video"))
    }

    // ------------------------------ cloudflare ------------------------------

    @Test
    fun `only a real challenge page counts as one`() {
        assertTrue(Cloudflare.isChallenge(403, "<title>Just a moment...</title>"))
        assertFalse(Cloudflare.isChallenge(200, "<title>Just a moment...</title>"))
        assertFalse(Cloudflare.isChallenge(403, "<h1>Forbidden</h1>"))
    }

    // -------------------------------- race --------------------------------

    @Test
    fun `the fastest good answer wins and slow or failing tasks do not matter`() = runBlocking {
        val result = firstSuccess<String>(
            listOf(
                { delay(400); "slow" },
                { throw IllegalStateException("boom") },
                { null },
                { delay(20); "fast" },
            ),
        )
        assertEquals("fast", result)
    }

    @Test
    fun `nothing good gives null and an empty list gives null`() = runBlocking {
        assertNull(firstSuccess<String>(listOf({ null }, { throw RuntimeException() })))
        assertNull(firstSuccess<String>(emptyList()))
        assertNotNull(firstSuccess(listOf<suspend () -> String?>({ "x" })))
    }
}
