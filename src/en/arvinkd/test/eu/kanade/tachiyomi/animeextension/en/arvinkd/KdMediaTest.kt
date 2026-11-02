package eu.kanade.tachiyomi.animeextension.en.arvinkd

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class KdMediaTest {
    @Test
    fun `ShortTV uses the browser player's media relay route`() {
        val path = "/hls/c896e20a-6136-4d90-8968-2b052f4873e5_1080/main.m3u8"

        assertEquals(
            "$KD_MEDIA_RELAY/https/volcengine-forward.shorttv.live$path",
            kdMediaUrl("https://volcengine-forward.shorttv.live$path"),
        )
    }

    @Test
    fun `signed query encoding and parameter order are preserved`() {
        val link = "https://volcengine-forward.shorttv.live/hls/video/main.m3u8?token=a%2Fb%2Bc%3D&expires=1793610000"

        assertEquals(
            "$KD_MEDIA_RELAY/https/volcengine-forward.shorttv.live/hls/video/main.m3u8?token=a%2Fb%2Bc%3D&expires=1793610000",
            kdMediaUrl(link),
        )
    }

    @Test
    fun `client-only fragment is not forwarded as part of the relay path`() {
        assertEquals(
            "$KD_MEDIA_RELAY/https/volcengine-forward.shorttv.live/hls/video/main.m3u8",
            kdMediaUrl("https://volcengine-forward.shorttv.live/hls/video/main.m3u8#client"),
        )
    }

    @Test
    fun `already relayed links are not relayed twice`() {
        val link = "$KD_MEDIA_RELAY/https/volcengine-forward.shorttv.live/hls/video/main.m3u8"

        assertEquals(link, kdMediaUrl(link))
    }

    @Test
    fun `other providers keep their existing direct links`() {
        val links = listOf(
            "https://example.com/video.mp4?token=123",
            "https://volcengine-forward.shorttv.live.example.com/video.m3u8",
            "https://127.0.0.1/video.m3u8",
        )

        links.forEach { assertEquals(it, kdMediaUrl(it)) }
    }

    @Test
    fun `ShortTV subtitle links can follow the same media route`() {
        assertEquals(
            "$KD_MEDIA_RELAY/https/volcengine-forward.shorttv.live/subtitles/video/en.vtt",
            kdMediaUrl("https://volcengine-forward.shorttv.live/subtitles/video/en.vtt"),
        )
    }

    @Test
    fun `relay requests carry the site context without losing the user agent`() {
        val original = Headers.Builder().set("User-Agent", "WatchBox test").build()
        val headers = kdMediaHeaders(original, "https://kiligdrama.com", "$KD_MEDIA_RELAY/https/host/video.m3u8")

        assertEquals("https://kiligdrama.com", headers["Origin"])
        assertEquals("https://kiligdrama.com/", headers["Referer"])
        assertEquals("WatchBox test", headers["User-Agent"])
        assertNull(headers["Range"])
    }

    @Test
    fun `direct provider headers remain unchanged`() {
        val original = Headers.Builder().set("User-Agent", "WatchBox test").build()

        assertSame(original, kdMediaHeaders(original, "https://kiligdrama.com", "https://example.com/video.mp4"))
    }
}
