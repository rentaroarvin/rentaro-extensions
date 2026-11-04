@file:JvmName("RequestsKt")

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Test-only stand-in for the host's request helpers. The extensions library on the unit-test classpath is
 * made of stubs that throw "Stub!", so a test that runs code calling [GET] would die in class
 * initialisation. Test classes load before that jar, so these real versions are the ones the code runs.
 * They build the same request the host builds: the URL, the headers and the cache control.
 */
fun GET(url: String, headers: Headers = Headers.Builder().build(), cache: CacheControl = CacheControl.Builder().build()): Request =
    Request.Builder().url(url).headers(headers).cacheControl(cache).build()

fun GET(url: HttpUrl, headers: Headers = Headers.Builder().build(), cache: CacheControl = CacheControl.Builder().build()): Request =
    Request.Builder().url(url).headers(headers).cacheControl(cache).build()
