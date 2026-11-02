package eu.kanade.tachiyomi.animeextension.en.arvinhp

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException

/** What an account action ended with, in words fit to show the user as they are. */
internal data class AuthResult(val ok: Boolean, val message: String)

/**
 * Hotplot's email-code sign-in, the same two calls the site's own sign-in form makes, plus a status check
 * and a local sign-out.
 *
 * Every step is one request the user asked for by tapping a setting. A wrong or expired code is reported
 * and never retried, because the site locks a code after too many wrong guesses (`OTP_LOCKED`). The code
 * itself comes from the user's own inbox and is never stored. The session lives in the host's cookie jar,
 * which is also what the player's later requests use, so nothing here keeps a token of its own.
 *
 * This file does not touch the site's Google, Telegram or app-pairing sign-in routes, and it does not look
 * at the coin, ad or unlock endpoints.
 */
internal class HpAuth(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiHeaders: Headers,
) {

    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        data class Rejected(val status: Int, val code: String?) : Reply
        data object Challenge : Reply
        data class Offline(val reason: String?) : Reply
    }

    /** Asks Hotplot to email a six-digit code to [email]. */
    fun requestCode(email: String): AuthResult {
        val body = OtpRequestDto(email = email, locale = LOCALE).toJsonRequestBody()
        return when (val reply = send("api/v1/auth/email-otp", body)) {
            is Reply.Ok -> AuthResult(
                true,
                "Code sent to $email. Enter it under “Enter sign-in code”. Hotplot lets you ask again after 60 seconds.",
            )
            else -> failure(reply)
        }
    }

    /** Sends the code the user read from their inbox. Succeeds only when the site then reports a signed-in user. */
    fun verifyCode(email: String, code: String, acceptedTerms: Boolean): AuthResult {
        // The site refuses a sign-in without this, and it has to be the user's own choice. It is never assumed.
        if (!acceptedTerms) {
            return AuthResult(false, "Turn on “I accept Hotplot's Terms and Privacy Policy” first. Hotplot requires it to sign in.")
        }
        if (!CODE_REGEX.matches(code)) return AuthResult(false, "The code is six digits.")

        val body = OtpVerifyDto(email = email, code = code, acceptTerms = true, termsVersion = TERMS_VERSION).toJsonRequestBody()
        val reply = send("api/v1/auth/email-otp/verify", body)
        if (reply !is Reply.Ok) return failure(reply)

        // A 200 from the verify call is not the proof; a populated user on the session is.
        return if (isSignedIn()) {
            AuthResult(true, "Signed in to Hotplot.")
        } else {
            AuthResult(
                false,
                "Hotplot accepted the code, but this app has no session yet. Open the site once in WebView, pass its check, then ask for a new code.",
            )
        }
    }

    /** Whether the cookie jar currently holds a Hotplot session with a user on it. */
    fun status(): AuthResult {
        val session = fetchSession() ?: return AuthResult(false, "Could not reach Hotplot to check the session.")
        return when {
            session.kicked == true -> AuthResult(false, "Hotplot says this account was signed in on another device. Sign in again here.")
            hasUser(session) -> AuthResult(true, "Signed in.")
            else -> AuthResult(false, "Not signed in. Browsing and free episodes still work.")
        }
    }

    private fun isSignedIn(): Boolean = fetchSession()?.let(::hasUser) == true

    private fun hasUser(session: SessionDto) = session.user != null && session.user !is JsonNull

    private fun fetchSession(): SessionDto? = try {
        client.newCall(GET("$baseUrl/api/v1/session", apiHeaders)).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful || isChallenge(response, text)) null else text.parseAs<SessionResponseDto>().data
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Forgets the Hotplot session on this device by expiring its cookies. Cloudflare's own cookies are left
     * alone so the site's check does not have to be passed again.
     *
     * No sign-out call turned up in the client code that was captured (public pages only, not the account
     * page), so none is made and the session is not revoked on the site's side.
     */
    fun signOutLocally(): AuthResult {
        val url = baseUrl.toHttpUrl()
        val jar = client.cookieJar
        val session = jar.loadForRequest(url).filterNot { it.name.startsWith("cf_") || it.name.startsWith("__cf") }
        if (session.isEmpty()) return AuthResult(true, "No Hotplot session was stored on this device.")
        jar.saveFromResponse(url, session.map(::expired))
        return AuthResult(
            true,
            "Signed out on this device. Hotplot is not told, so the session stays valid on its side; use the site's account page to end it everywhere.",
        )
    }

    private fun expired(cookie: Cookie): Cookie = Cookie.Builder()
        .name(cookie.name)
        .value("")
        .apply { if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain) }
        .path(cookie.path)
        .expiresAt(0L)
        .build()

    private fun send(path: String, body: RequestBody): Reply {
        val request = POST("$baseUrl/$path", apiHeaders.newBuilder().set("Origin", baseUrl).build(), body)
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    isChallenge(response, text) -> Reply.Challenge
                    response.isSuccessful -> Reply.Ok(text)
                    else -> Reply.Rejected(response.code, errorCode(text))
                }
            }
        } catch (e: IOException) {
            Reply.Offline(e.message)
        }
    }

    private fun errorCode(text: String): String? = try {
        val error = text.parseAs<ErrorEnvelopeDto>().error as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.contentOrNull
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun failure(reply: Reply): AuthResult = AuthResult(
        false,
        when (reply) {
            is Reply.Ok -> "Done."
            Reply.Challenge -> CHALLENGE_MESSAGE
            is Reply.Offline -> "Could not reach Hotplot${reply.reason?.let { " ($it)" }.orEmpty()}."
            is Reply.Rejected -> describe(reply.code) ?: "Hotplot refused the request (HTTP ${reply.status})."
        },
    )

    /** The wording follows the messages the site's own sign-in form shows for the same codes. */
    private fun describe(code: String?): String? = when (code) {
        "INVALID_EMAIL" -> "That doesn't look like a valid email."
        "RATE_LIMITED" -> "Too many attempts. Wait a few minutes and try again."
        "OTP_INVALID" -> "That code is incorrect or expired. Ask for a new one."
        "OTP_LOCKED" -> "Too many incorrect codes. Ask for a new one."
        "TERMS_REQUIRED" -> "Hotplot needs the Terms accepted to sign in."
        "ACCOUNT_SUSPENDED" -> "This Hotplot account is suspended. Contact Hotplot support."
        "email_conflict" -> "An account with this email exists but isn't verified. Try email code sign-in on the site, then connect Google from its Settings."
        else -> null
    }

    companion object {
        const val LOCALE = "en-US"
        const val CHALLENGE_MESSAGE = "Hotplot is asking for a Cloudflare check. Open the site once in WebView, pass it, and retry."
        private const val TERMS_VERSION = "current"
        private val CODE_REGEX = Regex("""\d{6}""")

        fun isChallenge(response: Response, body: String): Boolean = response.header("cf-mitigated") == "challenge" ||
            body.contains("<title>Just a moment", ignoreCase = true)
    }
}
