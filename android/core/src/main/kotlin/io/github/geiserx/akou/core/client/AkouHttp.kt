// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one OkHttp client every request to an akou server uses: no cache, so a request carrying the
 * bearer key is never written to disk, and a redirect to another origin is never followed, so a 3xx
 * can never carry the key elsewhere. OkHttp's own redirect handling is off; [SameOriginRedirects]
 * follows a 3xx only to the same scheme, host and port, and answers anything else as the 3xx itself.
 *
 * Each request carries its own read timeout as a [ReadTimeout] tag, applied by [RouteTimeouts].
 * OkHttp's default of 10 s is shorter than a job long poll.
 *
 * OkHttp's own resend on a failed connection is off, as URLSession has none: an upload whose
 * connection drops is one attempt, and the upload queue's attempts and backoff count it as one.
 */
object AkouHttp {
    /** The header the key travels in, as `Bearer <key>`. Never in a URL. */
    const val AUTHORIZATION = "Authorization"

    /** A probe or a history read: a sleeping Mac then shows its "did not answer" line in seconds. */
    const val READ_TIMEOUT_S = 15L

    /** Every job route but the long poll: URLSession's default on iOS. */
    const val JOB_TIMEOUT_S = 60L

    val shared: OkHttpClient by lazy { make() }

    /** A client with the akou rules on [builder] (the tests add their own interceptors after these). */
    fun make(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder
            .cache(null)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(RouteTimeouts)
            .addInterceptor(SameOriginRedirects)
            .build()

    fun bearer(key: String) = "Bearer $key"

    /** A redirect is followed only to the same scheme, host (any case) and effective port. */
    fun allowsRedirect(from: HttpUrl?, to: HttpUrl?): Boolean {
        if (from == null || to == null) return false
        // HttpUrl lowercases the host and fills in the scheme's default port.
        return from.scheme == to.scheme && from.host.equals(to.host, ignoreCase = true) && from.port == to.port
    }
}

/** How long one request waits for each read of its answer. */
data class ReadTimeout(val seconds: Long)

/** Applies a request's [ReadTimeout] tag. */
internal object RouteTimeouts : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val t = chain.request().tag(ReadTimeout::class) ?: return chain.proceed(chain.request())
        return chain.withReadTimeout(Math.toIntExact(TimeUnit.SECONDS.toMillis(t.seconds)), TimeUnit.MILLISECONDS)
            .proceed(chain.request())
    }
}

/** Follows a 3xx only to the original request's origin; any other 3xx is the answer. */
internal object SameOriginRedirects : Interceptor {
    private const val MAX_HOPS = 20

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var request = original
        var response = chain.proceed(request)
        var hops = 0
        while (response.code in REDIRECTS) {
            val target = response.header("Location")?.let { request.url.resolve(it) } ?: return response
            if (!AkouHttp.allowsRedirect(original.url, target) || ++hops > MAX_HOPS) return response
            request = followUp(request, response.code, target)
            response.close()
            response = chain.proceed(request)
        }
        return response
    }

    private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)

    /** 307 and 308 keep the method and body; the others turn into a GET, as browsers and URLSession do. */
    private fun followUp(request: Request, code: Int, target: HttpUrl): Request {
        val b = request.newBuilder().url(target)
        val keep = code == 307 || code == 308 || request.method == "GET" || request.method == "HEAD"
        if (!keep) b.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
        return b.build()
    }
}

/** A whole answer, read on OkHttp's thread so the request's read timeout covers the body too. */
internal class Answer(val status: Int, val headers: Headers, val body: ByteArray)

/** Sends [request] and reads the whole answer; cancelling the coroutine cancels the call. */
internal suspend fun OkHttpClient.fetch(request: Request): Answer = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            val answer = try {
                response.use { Answer(it.code, it.headers, it.body.bytes()) }
            } catch (e: IOException) {
                return cont.resumeWithException(e)
            }
            cont.resume(answer)
        }
    })
}
