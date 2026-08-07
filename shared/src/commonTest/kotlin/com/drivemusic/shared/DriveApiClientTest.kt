package com.drivemusic.shared

import com.drivemusic.shared.drive.DriveApiClient
import com.drivemusic.shared.drive.DriveApiException
import com.drivemusic.shared.drive.driveHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Drive client against a mock transport. Worth testing thoroughly because its failure modes
 * are all silent: a dropped page is a folder that looks smaller than it is, and a swallowed 503
 * is a download that reports success with a hole in it.
 */
class DriveApiClientTest {
    private fun page(files: String, nextPageToken: String? = null): String {
        val token = nextPageToken?.let { ""","nextPageToken":"$it"""" } ?: ""
        return """{"files":[$files]$token}"""
    }

    private fun file(id: String, name: String = "$id.mp3", mime: String = "audio/mpeg") =
        """{"id":"$id","name":"$name","mimeType":"$mime"}"""

    private fun client(
        handler: MockEngine,
        retry: DriveApiClient.RetryPolicy = DriveApiClient.RetryPolicy(initialDelayMs = 1),
    ) = DriveApiClient(
        tokens = { "token" },
        http = driveHttpClient(HttpClient(handler)),
        retry = retry,
    )

    private fun json(body: String) = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()) to body

    @Test
    fun listsASinglePage() = runTest {
        val engine = MockEngine { respond(page(file("a") + "," + file("b")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val files = client(engine).listFolder("root")

        assertEquals(listOf("a", "b"), files.map { it.id })
    }

    /** Pagination: a folder larger than one page must come back whole, not truncated. */
    @Test
    fun followsPaginationToTheEnd() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            val body = when (call) {
                1 -> page(file("a"), nextPageToken = "p2")
                2 -> page(file("b"), nextPageToken = "p3")
                else -> page(file("c"))
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        assertEquals(listOf("a", "b", "c"), client(engine).listFolder("root").map { it.id })
        assertEquals(3, call)
    }

    /**
     * A server echoing back the token it was given would otherwise spin forever, accumulating the
     * same page over and over until the process died.
     */
    @Test
    fun aRepeatedPageTokenTerminatesTheLoop() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            respond(page(file("a"), nextPageToken = "same"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val files = client(engine).listFolder("root")
        assertTrue(call <= 2, "made $call requests against a server echoing its page token")
        assertTrue(files.isNotEmpty())
    }

    /** Drive returns 503 routinely under bulk load; one must not lose the whole listing. */
    @Test
    fun retriesTransientServerErrors() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            if (call < 3) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(page(file("a")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        assertEquals(listOf("a"), client(engine).listFolder("root").map { it.id })
        assertEquals(3, call, "should have retried twice before succeeding")
    }

    /** 403 is how Drive reports rate limiting, so it is retryable despite looking like a refusal. */
    @Test
    fun retriesRateLimiting() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            if (call == 1) respondError(HttpStatusCode.Forbidden)
            else respond(page(file("a")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        client(engine).listFolder("root")
        assertEquals(2, call)
    }

    /** A 404 will still be a 404 — retrying only delays the error and burns quota. */
    @Test
    fun doesNotRetryPermanentFailures() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            respondError(HttpStatusCode.NotFound)
        }

        val error = assertFailsWith<DriveApiException> { client(engine).getFile("missing") }
        assertEquals(404, error.status)
        assertEquals(1, call, "a 404 must not be retried")
    }

    @Test
    fun givesUpAfterTheAttemptLimit() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<DriveApiException> {
            client(engine, DriveApiClient.RetryPolicy(maxAttempts = 3, initialDelayMs = 1))
                .listFolder("root")
        }
        assertEquals(3, call)
    }

    @Test
    fun downloadsRawBytes() = runTest {
        val engine = MockEngine { respond(byteArrayOf(1, 2, 3, 4), HttpStatusCode.OK) }
        assertEquals(listOf<Byte>(1, 2, 3, 4), client(engine).downloadFile("a").toList())
    }

    /** Drive adds fields over time; an unknown one must not fail the whole page. */
    @Test
    fun toleratesUnknownFields() = runTest {
        val body = """{"files":[{"id":"a","name":"a.mp3","mimeType":"audio/mpeg","somethingNew":42}]}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

        assertEquals(listOf("a"), client(engine).listFolder("root").map { it.id })
    }

    @Test
    fun readsTheSignedInAccount() = runTest {
        val engine = MockEngine {
            respond("""{"email":"someone@example.com","name":"Someone"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals("someone@example.com", client(engine).userInfo()?.email)
    }

    /** The email is a nicety; failing to read it must not take playback down with it. */
    @Test
    fun aFailedAccountLookupReturnsNullRatherThanThrowing() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        assertEquals(null, client(engine).userInfo())
    }
}
