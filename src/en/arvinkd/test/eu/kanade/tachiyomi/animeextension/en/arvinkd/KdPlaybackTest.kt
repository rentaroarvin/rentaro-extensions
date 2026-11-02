package eu.kanade.tachiyomi.animeextension.en.arvinkd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KdPlaybackTest {
    private val embedded = SourceDescriptorDto(enc = "old-stream", subtitle = SubtitleEnvelopeDto(enc = "old-subtitle"))
    private val fresh = SourceDescriptorDto(enc = "new-stream", subtitle = SubtitleEnvelopeDto(enc = "new-subtitle"))

    @Test
    fun `playable embedded source needs no refresh`() = runBlocking {
        val tried = mutableListOf<SourceDescriptorDto>()
        val streams = resolveKdPlayback(
            initial = embedded,
            refresh = { error("A working source must not be refreshed") },
            resolve = {
                tried += it
                listOf("video")
            },
        )

        assertEquals(listOf("video"), streams)
        assertEquals(listOf(embedded), tried)
    }

    @Test
    fun `rejected embedded link refreshes once and uses fresh subtitles`() = runBlocking {
        var refreshes = 0
        val tried = mutableListOf<SourceDescriptorDto>()
        val streams = resolveKdPlayback(
            initial = embedded,
            refresh = {
                refreshes++
                fresh
            },
            resolve = {
                tried += it
                if (it == fresh) listOf("${it.enc}:${it.subtitle?.enc}") else emptyList()
            },
        )

        assertEquals(listOf("new-stream:new-subtitle"), streams)
        assertEquals(listOf(embedded, fresh), tried)
        assertEquals(1, refreshes)
    }

    @Test
    fun `missing or mismatched embedded source goes straight to refresh`() = runBlocking {
        val tried = mutableListOf<SourceDescriptorDto>()
        val streams = resolveKdPlayback(
            initial = null,
            refresh = { fresh },
            resolve = {
                tried += it
                listOf("video")
            },
        )

        assertEquals(listOf("video"), streams)
        assertEquals(listOf(fresh), tried)
    }

    @Test
    fun `unplayable refreshed source never causes a retry loop`() = runBlocking {
        var refreshes = 0
        var resolves = 0
        val streams = resolveKdPlayback<String>(
            initial = embedded,
            refresh = {
                refreshes++
                fresh
            },
            resolve = {
                resolves++
                emptyList()
            },
        )

        assertTrue(streams.isEmpty())
        assertEquals(1, refreshes)
        assertEquals(2, resolves)
    }

    @Test
    fun `missing refreshed descriptor returns no streams`() = runBlocking {
        var resolves = 0
        val streams = resolveKdPlayback<String>(
            initial = embedded,
            refresh = { null },
            resolve = {
                resolves++
                emptyList()
            },
        )

        assertTrue(streams.isEmpty())
        assertEquals(1, resolves)
    }

    @Test
    fun `episode without refresh metadata still tries its embedded source`() = runBlocking {
        val streams = resolveKdPlayback(
            initial = embedded,
            refresh = null,
            resolve = { listOf("video") },
        )

        assertEquals(listOf("video"), streams)
    }

    @Test
    fun `no descriptor and no refresh metadata returns no streams`() = runBlocking {
        val streams = resolveKdPlayback<String>(
            initial = null,
            refresh = null,
            resolve = { error("There is no descriptor to resolve") },
        )

        assertTrue(streams.isEmpty())
    }

    @Test
    fun `refresh failures remain visible rather than becoming an empty list`() = runBlocking {
        val problem = IOException("KiligDrama answered HTTP 503")
        val result = runCatching {
            resolveKdPlayback<String>(
                initial = embedded,
                refresh = { throw problem },
                resolve = { emptyList() },
            )
        }

        assertSame(problem, result.exceptionOrNull())
    }

    @Test
    fun `cancelled episode resolution must not refresh the previous episode`() = runBlocking {
        val cancelled = CancellationException("Episode changed")
        var refreshes = 0
        val result = runCatching {
            resolveKdPlayback<String>(
                initial = embedded,
                refresh = {
                    refreshes++
                    fresh
                },
                resolve = { throw cancelled },
            )
        }

        assertSame(cancelled, result.exceptionOrNull())
        assertEquals(0, refreshes)
    }
}
