package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private fun b64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

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

    @Test
    fun `base64 reads standard, url safe and unpadded text`() {
        assertArrayEquals("hello world".toByteArray(), Base64Lite.decode("aGVsbG8gd29ybGQ="))
        assertArrayEquals("hello world".toByteArray(), Base64Lite.decode("aGVsbG8gd29ybGQ"))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64Lite.decode("+//+"))
        assertArrayEquals(byteArrayOf(-5, -1, -2), Base64Lite.decode("-__-"))
        assertThrows(IllegalArgumentException::class.java) { Base64Lite.decode("abéd") }
    }
}
