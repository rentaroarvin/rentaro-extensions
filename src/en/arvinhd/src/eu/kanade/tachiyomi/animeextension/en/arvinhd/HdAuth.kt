package eu.kanade.tachiyomi.animeextension.en.arvinhd

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** What an account action ended with, in words fit to show the user as they are. */
internal data class AuthResult(val ok: Boolean, val message: String)

/** What the sign-in action answered: `ok`, a top-level error key, or one error key per field. */
internal data class ActionResult(val ok: Boolean, val error: String?, val fieldErrors: List<String>)

/** `GET /api/me`: the session the cookies belong to. `user` is `null` when nobody is signed in. */
@Serializable
internal data class MeDto(val user: JsonElement? = null)

/**
 * HepDrama's email-and-password sign-in, the same call the site's own login form makes, plus a status
 * check and a local sign-out.
 *
 * The form is a Next.js Server Action: a multipart POST to the page with a `Next-Action` header that names
 * the action. That name is a build-specific hash and it changes when the site is redeployed (the one in
 * the 2026-11-03 analysis was already gone two days later), so it is read from the site's own scripts
 * instead of being written down here.
 *
 * Every sign-in is one request the user asked for by confirming the password dialog. A refusal is reported
 * and never retried, because the site counts failed attempts and locks out. The single exception is the
 * server saying it does not know the action at all, which means nothing ran and the credentials were not
 * looked at, so the action is looked up again and the request is sent once more. The password is used for
 * that request and is not kept anywhere. The session is the cookie the site sets, held by the host's cookie
 * jar, which is also what the player's later requests use, so nothing here keeps a token of its own.
 *
 * A reply of HTTP 200 does not mean signed in (a refused login is a 200 as well), so a success is only
 * reported once `/api/me` shows a user.
 *
 * This file does not register accounts, handle the emailed verification code, or touch the ad, Premium
 * or unlock endpoints.
 */
internal class HdAuth(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val headers: Headers,
) {

    internal sealed interface Step {
        data class Done(val result: ActionResult) : Step
        data object ActionMissing : Step
        data object Challenge : Step
        data class Failed(val message: String) : Step
    }

    /** Thrown while looking up the sign-in action when the site answers a Cloudflare check instead of a page. */
    private class ChallengeException : IOException("Cloudflare check")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var loginAction: String? = null

    /** Signs in with the user's own account. Succeeds only when the site then reports a signed-in user. */
    fun signIn(email: String, password: String): AuthResult {
        val address = email.trim()
        if (!EMAIL_REGEX.matches(address)) return AuthResult(false, "Set a valid email address first.")
        if (password.isEmpty()) return AuthResult(false, "Enter your password.")

        var step = submit(address, password, refreshAction = false)
        if (step is Step.ActionMissing) step = submit(address, password, refreshAction = true)
        return when (step) {
            is Step.Done -> interpret(step.result)
            Step.ActionMissing -> AuthResult(false, "HepDrama did not recognise its own sign-in form. The site may have changed; try again later.")
            Step.Challenge -> AuthResult(false, CHALLENGE_MESSAGE)
            is Step.Failed -> AuthResult(false, step.message)
        }
    }

    /** One sign-in request. Not retried here: see the class comment. */
    internal fun submit(email: String, password: String, refreshAction: Boolean): Step {
        val action = try {
            actionId(refreshAction)
        } catch (e: ChallengeException) {
            return Step.Challenge
        } catch (e: IOException) {
            return Step.Failed("Could not reach HepDrama (${e.message ?: "network error"}).")
        } ?: return Step.Failed("Could not find HepDrama's sign-in form on its site. Try again later.")

        // The same parts the site's form sends: React encodes the action arguments (the previous state and
        // the form) as part 0, and the form's fields travel with the `_1_` prefix.
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("_1_next", NEXT_PATH)
            .addFormDataPart("_1_email", email)
            .addFormDataPart("_1_password", password)
            .addFormDataPart("0", ARGUMENTS)
            .build()
        val request = Request.Builder()
            .url("$baseUrl$NEXT_PATH")
            .headers(
                headers.newBuilder()
                    .set("Accept", "text/x-component")
                    .set("Next-Action", action)
                    .set("Origin", baseUrl)
                    .set("Referer", "$baseUrl$NEXT_PATH")
                    .build(),
            )
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    isChallenge(response, text) -> Step.Challenge
                    response.header("x-nextjs-action-not-found") != null -> Step.ActionMissing
                    else -> parseActionResult(text)?.let { Step.Done(it) }
                        ?: Step.Failed("HepDrama answered (HTTP ${response.code}) in a form this app does not recognise, so the sign-in was not confirmed.")
                }
            }
        } catch (e: IOException) {
            Step.Failed("Could not reach HepDrama (${e.message ?: "network error"}).")
        }
    }

    private fun interpret(result: ActionResult): AuthResult = when {
        result.ok -> confirmSession()
        result.fieldErrors.isNotEmpty() -> AuthResult(false, result.fieldErrors.distinct().joinToString(" ") { describe(it) })
        result.error != null -> AuthResult(false, describe(result.error))
        else -> AuthResult(false, "HepDrama did not sign you in.")
    }

    /** The action said ok; the session is the proof. */
    private fun confirmSession(): AuthResult {
        val me = fetchMe() ?: return AuthResult(false, "HepDrama accepted the sign-in, but the session could not be checked. Tap “check status”.")
        val user = me.user as? JsonObject
            ?: return AuthResult(false, "HepDrama accepted the sign-in, but this app has no session yet. Try again, or tap “check status”.")
        return AuthResult(true, "Signed in to HepDrama.${unverifiedNote(user)}")
    }

    /** Whether the cookie jar currently holds a HepDrama session with a user on it. */
    fun status(): AuthResult {
        val me = fetchMe() ?: return AuthResult(false, "Could not reach HepDrama to check the session.")
        val user = me.user as? JsonObject
            ?: return AuthResult(false, "Not signed in. Browsing and the free episodes still work.")
        return AuthResult(true, "Signed in.${unverifiedNote(user)}")
    }

    /**
     * An unverified account is asked for a six-digit emailed code by the site's own overlay. That branch is
     * not handled here (it has never been exercised), so the user is told where to finish it.
     */
    private fun unverifiedNote(user: JsonObject): String = if ((user["emailVerified"] as? JsonPrimitive)?.booleanOrNull == false) {
        " HepDrama says this email is not verified yet: open the site, enter the six-digit code it emails you, then check status."
    } else {
        ""
    }

    private fun fetchMe(): MeDto? = try {
        val request = Request.Builder()
            .url("$baseUrl/api/me")
            .headers(headers.newBuilder().set("Accept", "application/json").build())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful || isChallenge(response, text)) null else json.decodeFromString<MeDto>(text)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Forgets the HepDrama session on this device by expiring its cookies. Cloudflare's own cookies are left
     * alone so a site check does not have to be passed again.
     *
     * The site has a logout action, but it was never exercised, so none is called and the session is not
     * ended on the site's side.
     */
    fun signOutLocally(): AuthResult {
        val url = baseUrl.toHttpUrl()
        val jar = client.cookieJar
        val session = jar.loadForRequest(url).filterNot { it.name.startsWith("cf_") || it.name.startsWith("__cf") }
        if (session.isEmpty()) return AuthResult(true, "No HepDrama session was stored on this device.")
        jar.saveFromResponse(url, session.map(::expired))
        return AuthResult(
            true,
            "Signed out on this device. HepDrama is not told, so the session stays valid on its side; use the site's account page to end it everywhere.",
        )
    }

    private fun expired(cookie: Cookie): Cookie = Cookie.Builder()
        .name(cookie.name)
        .value("")
        .apply { if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain) }
        .path(cookie.path)
        .expiresAt(0L)
        .build()

    // ============================ Finding the action ============================

    private fun actionId(refresh: Boolean): String? {
        if (!refresh) loginAction?.let { return it }
        return discoverLoginAction()?.also { loginAction = it }
    }

    /**
     * Reads the sign-in action's name from the site's scripts: the page lists its script files, and the one
     * that holds the login form declares `createServerReference("<hash>", ..., "loginAction")`.
     */
    private fun discoverLoginAction(): String? {
        val page = fetchText("$baseUrl$NEXT_PATH") ?: return null
        val scripts = SCRIPT_REGEX.findAll(page).map { it.value }.distinct().take(MAX_SCRIPTS)
        for (path in scripts) {
            val script = fetchText(baseUrl + path) ?: continue
            findLoginAction(script)?.let { return it }
        }
        return null
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder().url(url).headers(headers).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (isChallenge(response, text)) throw ChallengeException()
            if (response.isSuccessful) text else null
        }
    }

    // ================================= Messages =================================

    /** The wording follows the strings the site's own form shows for the same keys. */
    private fun describe(code: String): String = MESSAGES[code] ?: "HepDrama refused the sign-in ($code)."

    companion object {
        const val CHALLENGE_MESSAGE = "HepDrama is asking for a Cloudflare check. Open the site once in WebView, pass it, and retry."

        private const val NEXT_PATH = "/en"
        private const val ARGUMENTS = "[{},\"\$K1\"]"
        private const val MAX_SCRIPTS = 25

        private val EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        private val SCRIPT_REGEX = Regex("""/_next/static/chunks/[A-Za-z0-9_.~-]+\.js""")
        private val ACTION_REGEX = Regex(""""([0-9a-f]{32,})"[^"]{0,160}"loginAction"""")

        private val MESSAGES = mapOf(
            "emailInvalid" to "That doesn't look like a valid email address.",
            "passwordRequired" to "Enter your password.",
            "passwordMin" to "The password must be at least 8 characters.",
            "passwordMax" to "That password is too long.",
            "badCredentials" to "Incorrect email or password.",
            "tooManyAttempts" to "Too many attempts. Try again in a few minutes.",
            "tooManyFailed" to "Too many failed attempts. Try again in a few minutes.",
            "captchaFailed" to "HepDrama wants a security check this app cannot complete. Sign in on the site instead.",
            "sessionExpired" to "The session expired. Sign in again.",
        )

        fun isChallenge(response: Response, body: String): Boolean = response.header("cf-mitigated") == "challenge" ||
            body.contains("<title>Just a moment", ignoreCase = true)

        /** The hash a script declares for `loginAction`, if it declares one. */
        fun findLoginAction(script: String): String? = ACTION_REGEX.find(script)?.groupValues?.get(1)

        /**
         * The result row of a React Server Components reply. Row 0 is the framework's own record; the
         * action's return value is a later row holding `ok`, `error` or `fieldErrors`.
         */
        fun parseActionResult(body: String): ActionResult? {
            for (line in body.lineSequence()) {
                val separator = line.indexOf(':')
                if (separator <= 0 || line.substring(0, separator).any { !it.isLetterOrDigit() }) continue
                val value = line.substring(separator + 1)
                if (!value.startsWith("{")) continue
                val row = try {
                    Json.parseToJsonElement(value) as? JsonObject
                } catch (e: IllegalArgumentException) {
                    null
                } ?: continue

                val ok = (row["ok"] as? JsonPrimitive)?.booleanOrNull == true
                val error = (row["error"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
                val fields = (row["fieldErrors"] as? JsonObject)?.values.orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeUnless { value -> value is JsonNull }?.contentOrNull }
                if (ok || error != null || fields.isNotEmpty()) return ActionResult(ok, error, fields)
            }
            return null
        }
    }
}
