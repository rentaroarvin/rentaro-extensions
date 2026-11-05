package eu.kanade.tachiyomi.animeextension.en.arvinkd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search results page streams its grid. The server sends the form and heading first with a loading
 * skeleton in the grid's place (`<!--$?--><template id="B:n">`), and the real grid and pagination arrive
 * later in a hidden `<div id="S:n">` that `$RC("B:n","S:n")` moves into place. These pages are small
 * stand-ins for that shape. No raw HTTP response of a results page was ever captured (the site answers a
 * Cloudflare challenge to plain clients), so the shape is inferred from the saved results DOM, its React
 * Flight payload (a `Suspense` boundary with a skeleton fallback around the grid) and the boundaries of the
 * series and genre pages, which were captured on the wire.
 */
class KdParserTest {
    private val d = "$"

    private fun card(slug: String, title: String) =
        """<article class="group"><a class="relative block" aria-label="$title" href="/en/drama/$slug"><img alt="$title" src="https://img.example/$slug.jpg"></a><h3><a href="/en/drama/$slug">$title</a></h3></article>"""

    private val results = """
        <p class="count">938 results</p>
        <div class="grid grid-cols-2">${card("chased-by-four-fierce-empresses", "Chased by Four Fierce Empresses")}${card("the-empress-owns-her-realm", "The Empress Owns Her Realm")}</div>
        <nav aria-label="Pagination"><span aria-current="page">1</span><a href="/en/search?q=Empress&amp;page=2">2</a><a rel="next" href="/en/search?q=Empress&amp;page=2">Next</a></nav>
    """.trimIndent()

    private fun searchPage(body: String, tail: String = "") = """
        <html><body><main><div class="lg:grid"><div class="min-w-0">
        <form action="/en/search" method="get" role="search"><input name="q" value="Empress"></form>
        <h1>Results for “Empress”</h1>
        $body
        </div><aside><article><a aria-label="Sidebar Pick" href="/en/drama/sidebar-pick"></a></article></aside></div></main>
        $tail
        </body></html>
    """.trimIndent()

    private val skeleton =
        """<!--${d}?--><template id="B:3"></template><div aria-hidden="true"><div class="grid"><div class="animate-pulse"></div></div></div><!--/$d-->"""

    @Test
    fun `search results delivered through a completed boundary are read`() {
        val html = searchPage(skeleton, """<div hidden id="S:3">$results</div><script>${d}RC("B:3","S:3")</script>""")

        val listing = KdParser.parseListing(html, 1)

        assertEquals(
            listOf("/en/drama/chased-by-four-fierce-empresses", "/en/drama/the-empress-owns-her-realm"),
            listing.cards.map { it.path },
        )
        assertEquals("Chased by Four Fierce Empresses", listing.cards.first().title)
        assertEquals("https://img.example/chased-by-four-fierce-empresses.jpg", listing.cards.first().thumbnail)
        assertTrue(listing.hasNext)
    }

    @Test
    fun `the sidebar and the loading skeleton are not results`() {
        val listing = KdParser.parseListing(searchPage(skeleton), 1)

        assertTrue(listing.cards.isEmpty())
        assertFalse(listing.hasNext)
    }

    @Test
    fun `a results page without a next link is the last page`() {
        val last = results.replace(Regex("""<nav.*</nav>""", RegexOption.DOT_MATCHES_ALL), "")
        val html = searchPage(skeleton, """<div hidden id="S:3">$last</div><script>${d}RC("B:3","S:3")</script>""")

        assertFalse(KdParser.parseListing(html, 1).hasNext)
    }

    @Test
    fun `the next link is matched against the page being read`() {
        val html = searchPage(skeleton, """<div hidden id="S:3">$results</div><script>${d}RC("B:3","S:3")</script>""")

        // The links point at page 2, so reading page 2 has no further page.
        assertFalse(KdParser.parseListing(html, 2).hasNext)
    }

    @Test
    fun `nested boundaries inside the fallback do not end it early`() {
        val nested =
            """<!--${d}?--><template id="B:3"></template><!--${d}--><span>inner</span><!--/$d--><div class="animate-pulse"></div><!--/$d-->"""
        val html = searchPage(nested, """<div hidden id="S:3">$results</div><script>${d}RC("B:3","S:3")</script>""")

        val listing = KdParser.parseListing(html, 1)

        assertEquals(2, listing.cards.size)
    }

    @Test
    fun `segments inside boundary content are filled whichever call comes first`() {
        val slot = """<template id="P:9"></template>"""
        val grid = """<div class="grid">${card("a-slug", "First")}$slot</div>"""
        val segment = """<div hidden id="S:9">${card("b-slug", "Second")}</div>"""

        val segmentFirst = searchPage(
            skeleton,
            """<div hidden id="S:3">$grid</div>$segment<script>${d}RS("S:9","P:9")</script><script>${d}RC("B:3","S:3")</script>""",
        )
        val boundaryFirst = searchPage(
            skeleton,
            """<div hidden id="S:3">$grid</div><script>${d}RC("B:3","S:3")</script>$segment<script>${d}RS("S:9","P:9")</script>""",
        )

        listOf(segmentFirst, boundaryFirst).forEach { html ->
            assertEquals(listOf("/en/drama/a-slug", "/en/drama/b-slug"), KdParser.parseListing(html, 1).cards.map { it.path })
        }
    }

    @Test
    fun `plain segment swaps still fill their slots`() {
        val html = searchPage(
            """<div class="grid"><template id="P:1"></template></div>""",
            """<div hidden id="S:1">${card("only-slug", "Only")}</div><script>${d}RS("S:1","P:1")</script>""",
        )

        assertEquals(listOf("/en/drama/only-slug"), KdParser.parseListing(html, 1).cards.map { it.path })
    }

    @Test
    fun `the Cloudflare challenge page is recognised`() {
        assertTrue(KdParser.isChallenge("<html><head><title>Just a moment...</title></head></html>"))
        assertFalse(KdParser.isChallenge(searchPage(skeleton)))
    }
}
