package eu.kanade.tachiyomi.animeextension.en.arvinrf

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArvinRfFeedTest {
    @Test
    fun `explore uses the English mixed-provider home feed`() {
        val url = ArvinRf.feedUrl(null, page = 1, popular = false)

        assertEquals("api.reelfren.net", url.host)
        assertEquals("/api/home", url.encodedPath)
        assertEquals("en", url.queryParameter("lang"))
        assertEquals("0", url.queryParameter("offset"))
        assertNull(url.queryParameter("provider"))
        assertNull(url.queryParameter("feed"))
        assertNull(url.queryParameter("category"))
    }

    @Test
    fun `explore offsets are page indexes`() {
        assertEquals("1", ArvinRf.feedUrl(null, page = 2, popular = false).queryParameter("offset"))
    }

    @Test
    fun `popular still requests the selected provider's popular feed`() {
        val url = ArvinRf.feedUrl("mydrama", page = 1, popular = true)

        assertEquals("mydrama", url.queryParameter("provider"))
        assertEquals("popular", url.queryParameter("feed"))
        assertNotEquals(url, ArvinRf.feedUrl(null, page = 1, popular = false))
    }

    @Test
    fun `popular fallback stays on its provider rather than switching to explore`() {
        val url = ArvinRf.feedUrl("reelshort", page = 1, popular = false)

        assertEquals("reelshort", url.queryParameter("provider"))
        assertNull(url.queryParameter("feed"))
        assertNotEquals(
            ArvinRf.feedKey(url),
            ArvinRf.feedKey(ArvinRf.feedUrl(null, page = 1, popular = false)),
        )
    }

    @Test
    fun `WeTV retains its working category without restricting explore`() {
        assertEquals(
            "10240",
            ArvinRf.feedUrl("wetv", page = 1, popular = true).queryParameter("category"),
        )
        assertNull(ArvinRf.feedUrl(null, page = 1, popular = false).queryParameter("category"))
    }

    @Test
    fun `popular and explore track seen titles separately`() {
        assertNotEquals(
            ArvinRf.feedKey(ArvinRf.feedUrl("mydrama", page = 1, popular = true)),
            ArvinRf.feedKey(ArvinRf.feedUrl(null, page = 1, popular = false)),
        )
    }

    @Test
    fun `successive explore pages share seen-title tracking`() {
        assertEquals(
            ArvinRf.feedKey(ArvinRf.feedUrl(null, page = 1, popular = false)),
            ArvinRf.feedKey(ArvinRf.feedUrl(null, page = 2, popular = false)),
        )
    }

    @Test
    fun `search and explore do not share seen-title tracking`() {
        val explore = ArvinRf.feedUrl(null, page = 1, popular = false)
        val search = "https://api.reelfren.net/api/search?lang=en&offset=0".toHttpUrl()

        assertNotEquals(ArvinRf.feedKey(explore), ArvinRf.feedKey(search))
        assertNotEquals(
            ArvinRf.feedKey(search.newBuilder().addQueryParameter("q", "love").build()),
            ArvinRf.feedKey(search.newBuilder().addQueryParameter("q", "mafia").build()),
        )
    }

    @Test
    fun `mixed feeds retain provider identity and large string IDs`() {
        val feed = Json { ignoreUnknownKeys = true }.decodeFromString<FeedDto>(
            """
            {
              "data": [
                {"id":"879886","provider":"vibeshort","title":"Charm Or Genius"},
                {"id":"4215945971911590256","provider":"movieboxshorts","title":"Awakening Rejected Mate"}
              ],
              "offset": 0
            }
            """.trimIndent(),
        )

        assertEquals(listOf("vibeshort", "movieboxshorts"), feed.data.map { it.provider })
        assertEquals("4215945971911590256", feed.data.last().id)
        assertNull(feed.hasMore)
    }
}
