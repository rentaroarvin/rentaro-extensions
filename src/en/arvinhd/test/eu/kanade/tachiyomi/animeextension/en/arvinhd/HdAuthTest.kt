package eu.kanade.tachiyomi.animeextension.en.arvinhd

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in call is built from one analysis capture (the form's field names, the React multipart
 * encoding, the shape of the reply) and from the way a live script declares its action. No test reaches
 * the network: an interceptor answers every request and records what was sent.
 *
 * What these cannot show is a real successful login. Nobody has captured one, so the success branch rests
 * on the site's own client code (an `ok` flag, then `/api/me` showing a user), not on an observed reply.
 */
class HdAuthTest {

    // The reply the analysis captured for an empty login, verbatim.
    private val emptyLoginReply = "0:{\"a\":\"${'$'}@1\",\"q\":\"\",\"i\":false,\"b\":\"5rnVdSmh9fmVy08DGuBd9\"}\n" +
        "1:{\"fieldErrors\":{\"email\":\"emailInvalid\",\"password\":\"passwordRequired\"},\"values\":{\"email\":\"\"}}\n"

    private val oldHash = "602e5d357f0c103c627af57e55fc3acc6157a23852"
    private val newHash = "601de301aa9feaaaebbf1b828ec7776b9672896ddb"

    // How the site's login script declares its actions (shape from a live script).
    private fun script(loginHash: String) = "let h=(0,m.createServerReference)(\"$loginHash\",m.callServer,void 0,m.findSourceMapURL,\"loginAction\")," +
        "p=(0,m.createServerReference)(\"60a34c8cdd1b8714b62415b9eb0d4cd0b3ba6f343d\",m.callServer,void 0,m.findSourceMapURL,\"registerAction\")"

    private val home = """<script src="/_next/static/chunks/0a_4dm86u-sup.js"></script><script src="/_next/static/chunks/0avz3iabta7pc.js"></script>"""

    private class Sent(val method: String, val url: String, val headers: Headers, val body: String, val contentType: String?)

    private class Fake(private val answer: (Request) -> Response) {
        val sent = mutableListOf<Sent>()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val text = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
                    sent += Sent(request.method, request.url.toString(), request.headers, text, request.body?.contentType()?.toString())
                    answer(request)
                },
            )
            .build()

        fun posts() = sent.filter { it.method == "POST" }
    }

    private fun respond(request: Request, body: String, code: Int = 200, type: String = "text/plain", headers: Map<String, String> = emptyMap()): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    private fun auth(fake: Fake) = HdAuth(fake.client, "https://hepdrama.com", Headers.headersOf("User-Agent", "test"))

    // ============================== reply parsing ==============================

    @Test
    fun `the captured empty-login reply reads as two field errors`() {
        val result = HdAuth.parseActionResult(emptyLoginReply)

        assertNotNull(result)
        assertFalse(result!!.ok)
        assertEquals(listOf("emailInvalid", "passwordRequired"), result.fieldErrors)
    }

    @Test
    fun `an ok reply and a top-level error are told apart`() {
        assertTrue(HdAuth.parseActionResult("0:{\"a\":\"x\"}\n1:{\"ok\":true}\n")!!.ok)

        val refused = HdAuth.parseActionResult("0:{\"a\":\"x\"}\n1:{\"error\":\"badCredentials\",\"values\":{\"email\":\"a@b.co\"}}\n")!!
        assertFalse(refused.ok)
        assertEquals("badCredentials", refused.error)
    }

    @Test
    fun `a reply with no action result is not mistaken for a success`() {
        assertNull(HdAuth.parseActionResult("0:{\"a\":\"${'$'}@1\",\"q\":\"\",\"i\":false}\n"))
        assertNull(HdAuth.parseActionResult("<html>not a flight reply</html>"))
        assertNull(HdAuth.parseActionResult(""))
    }

    @Test
    fun `the login action is read from a script and another action is not taken for it`() {
        assertEquals(newHash, HdAuth.findLoginAction(script(newHash)))
        assertNull(HdAuth.findLoginAction("createServerReference(\"60a34c8cdd1b8714b62415b9eb0d4cd0b3ba6f343d\",m.callServer,void 0,m.findSourceMapURL,\"registerAction\")"))
        assertNull(HdAuth.findLoginAction("var x=1;"))
    }

    // ============================== the request ==============================

    private fun signInFake(loginReply: String, me: String): Fake = Fake { request ->
        val path = request.url.encodedPath
        when {
            request.method == "POST" -> respond(request, loginReply, type = "text/x-component")
            path == "/en" -> respond(request, home, type = "text/html")
            path.endsWith("0avz3iabta7pc.js") -> respond(request, script(newHash))
            path.startsWith("/_next/") -> respond(request, "var a=1;")
            path == "/api/me" -> respond(request, me, type = "application/json")
            else -> respond(request, "", code = 404)
        }
    }

    private val member = """{"paywall":false,"premiumOffer":false,"user":{"id":"u","emailVerified":true}}"""
    private val anonymous = """{"paywall":false,"premiumOffer":false,"user":null}"""

    @Test
    fun `the sign-in request carries the discovered action and the form's own fields`() {
        val fake = signInFake("0:{}\n1:{\"ok\":true}\n", member)

        val result = auth(fake).signIn("  me@example.com  ", "s3cret pass")

        assertTrue(result.message, result.ok)
        val post = fake.posts().single()
        assertEquals("https://hepdrama.com/en", post.url)
        assertEquals(newHash, post.headers["Next-Action"])
        assertEquals("text/x-component", post.headers["Accept"])
        assertEquals("https://hepdrama.com", post.headers["Origin"])
        assertTrue(post.contentType.orEmpty(), post.contentType.orEmpty().startsWith("multipart/form-data"))
        listOf(
            "name=\"_1_next\"\r\n\r\n/en",
            "name=\"_1_email\"\r\n\r\nme@example.com",
            "name=\"_1_password\"\r\n\r\ns3cret pass",
            "name=\"0\"\r\n\r\n[{},\"${'$'}K1\"]",
        ).forEach { assertTrue("missing part: $it\n${post.body}", post.body.contains(it)) }
    }

    @Test
    fun `an ok reply is only a success once the session shows a user`() {
        assertTrue(auth(signInFake("1:{\"ok\":true}\n", member)).signIn("me@example.com", "password1").ok)

        val result = auth(signInFake("1:{\"ok\":true}\n", anonymous)).signIn("me@example.com", "password1")
        assertFalse(result.ok)
        assertTrue(result.message, result.message.contains("no session"))
    }

    @Test
    fun `an unverified account is signed in but told to finish verification on the site`() {
        val unverified = """{"user":{"id":"u","emailVerified":false}}"""

        val result = auth(signInFake("1:{\"ok\":true}\n", unverified)).signIn("me@example.com", "password1")

        assertTrue(result.ok)
        assertTrue(result.message, result.message.contains("not verified"))
    }

    @Test
    fun `a refused login is reported in the site's words and sent only once`() {
        val fake = signInFake("1:{\"error\":\"badCredentials\",\"values\":{\"email\":\"me@example.com\"}}\n", anonymous)

        val result = auth(fake).signIn("me@example.com", "wrong-password")

        assertFalse(result.ok)
        assertEquals("Incorrect email or password.", result.message)
        assertEquals("a refusal must not be retried", 1, fake.posts().size)
        assertTrue("no session check after a refusal", fake.sent.none { it.url.endsWith("/api/me") })
    }

    @Test
    fun `every field error is shown`() {
        val result = auth(signInFake(emptyLoginReply, anonymous)).signIn("me@example.com", "password1")

        assertFalse(result.ok)
        assertEquals("That doesn't look like a valid email address. Enter your password.", result.message)
    }

    @Test
    fun `a redeployed site is handled by finding the action again and sending once more`() {
        var scriptFetches = 0
        val fake = Fake { request ->
            val path = request.url.encodedPath
            when {
                request.method == "POST" -> if (request.header("Next-Action") == newHash) {
                    respond(request, "1:{\"error\":\"badCredentials\"}\n", type = "text/x-component")
                } else {
                    respond(request, "Server action not found.", code = 404, headers = mapOf("x-nextjs-action-not-found" to "1"))
                }
                path == "/en" -> respond(request, home, type = "text/html")
                // The first lookup still sees the old build; the second sees the new one.
                path.endsWith("0avz3iabta7pc.js") -> respond(request, script(if (++scriptFetches == 1) oldHash else newHash))
                else -> respond(request, "var a=1;")
            }
        }

        val result = auth(fake).signIn("me@example.com", "password1")

        // The site answered for real on the second try, so its verdict is what the user sees.
        assertEquals("Incorrect email or password.", result.message)
        assertEquals(listOf(oldHash, newHash), fake.posts().map { it.headers["Next-Action"] })
    }

    @Test
    fun `an action the site never recognises stops after one repeat`() {
        val fake = Fake { request ->
            val path = request.url.encodedPath
            when {
                request.method == "POST" -> respond(request, "Server action not found.", code = 404, headers = mapOf("x-nextjs-action-not-found" to "1"))
                path == "/en" -> respond(request, home, type = "text/html")
                path.endsWith("0avz3iabta7pc.js") -> respond(request, script(newHash))
                else -> respond(request, "var a=1;")
            }
        }

        val result = auth(fake).signIn("me@example.com", "password1")

        assertFalse(result.ok)
        assertEquals(2, fake.posts().size)
    }

    @Test
    fun `a Cloudflare challenge is named and not mistaken for a refusal`() {
        val fake = Fake { request ->
            respond(request, "<title>Just a moment...</title>", code = 403, type = "text/html", headers = mapOf("cf-mitigated" to "challenge"))
        }

        val result = auth(fake).signIn("me@example.com", "password1")

        assertFalse(result.ok)
        assertEquals(HdAuth.CHALLENGE_MESSAGE, result.message)
        assertTrue("nothing is posted while challenged", fake.posts().isEmpty())
    }

    @Test
    fun `a challenge answering the sign-in itself is named`() {
        val fake = Fake { request ->
            val path = request.url.encodedPath
            when {
                request.method == "POST" -> respond(request, "<title>Just a moment...</title>", code = 403, type = "text/html", headers = mapOf("cf-mitigated" to "challenge"))
                path == "/en" -> respond(request, home, type = "text/html")
                path.endsWith("0avz3iabta7pc.js") -> respond(request, script(newHash))
                else -> respond(request, "var a=1;")
            }
        }

        val result = auth(fake).signIn("me@example.com", "password1")

        assertFalse(result.ok)
        assertEquals(HdAuth.CHALLENGE_MESSAGE, result.message)
        assertEquals(1, fake.posts().size)
    }

    @Test
    fun `a missing email or password sends nothing`() {
        val fake = Fake { request -> respond(request, "") }

        assertFalse(auth(fake).signIn("not-an-email", "password1").ok)
        assertFalse(auth(fake).signIn("me@example.com", "").ok)
        assertTrue(fake.sent.isEmpty())
    }

    // ================================ status ================================

    @Test
    fun `status tells a signed-in session from an anonymous one`() {
        val guest = Fake { request -> respond(request, anonymous, type = "application/json") }
        assertFalse(auth(guest).status().ok)

        val signedIn = Fake { request -> respond(request, member, type = "application/json") }
        assertTrue(auth(signedIn).status().ok)
        assertEquals("https://hepdrama.com/api/me", signedIn.sent.single().url)
    }
}
