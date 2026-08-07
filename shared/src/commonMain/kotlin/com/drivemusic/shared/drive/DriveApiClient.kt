package com.drivemusic.shared.drive

import com.drivemusic.shared.data.AccessTokenProvider
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.FOLDER_MIME_TYPE
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DriveApiException(val status: Int, message: String) : Exception(message)

/**
 * The Drive v3 client, in `commonMain` so both platforms speak to Drive the same way.
 *
 * Unlike the iOS client this retries. Drive returns 403 `userRateLimitExceeded` and 500/503
 * routinely on bulk listing and downloading, and Google's own guidance is exponential backoff.
 * Without it a single transient failure in the middle of a bulk download is swallowed by the
 * caller, the progress counter advances anyway, and the user is shown a completed download with a
 * hole in it — which is exactly what the iOS version does today.
 */
class DriveApiClient(
    private val tokens: AccessTokenProvider,
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    /**
     * How a failed request is retried.
     *
     * Only failures that can plausibly succeed on a second attempt are retried — a 404 will still
     * be a 404, and retrying a 401 without a new token just burns quota. Everything else fails
     * fast and loudly, which is what a caller needs to be able to report honestly.
     */
    data class RetryPolicy(
        val maxAttempts: Int = 4,
        val initialDelayMs: Long = 500,
        val multiplier: Double = 2.0,
    ) {
        fun isRetryable(status: Int): Boolean =
            status == 429 || status == 403 || status in 500..599
    }

    @Serializable
    private data class FileListResponse(
        val files: List<DriveFile> = emptyList(),
        @SerialName("nextPageToken") val nextPageToken: String? = null,
    )

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var delayMs = retry.initialDelayMs
        var lastError: DriveApiException? = null

        repeat(retry.maxAttempts) { attempt ->
            try {
                return block()
            } catch (error: DriveApiException) {
                if (!retry.isRetryable(error.status) || attempt == retry.maxAttempts - 1) throw error
                lastError = error
                delay(delayMs)
                delayMs = (delayMs * retry.multiplier).toLong()
            }
        }
        throw lastError ?: DriveApiException(-1, "Drive request failed with no attempts made")
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val detail = runCatching { bodyAsText() }.getOrDefault("")
        throw DriveApiException(status.value, "Drive request failed: ${status.value} $detail")
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    /**
     * Subfolders and audio files directly inside [folderId], folders first then files, both
     * name-sorted — matching what the iOS app and the web app both show.
     */
    suspend fun listFolder(folderId: String): List<DriveFile> {
        val query = "'$folderId' in parents and trashed = false and " +
            "(mimeType = '$FOLDER_MIME_TYPE' or mimeType contains 'audio/')"

        val results = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var pages = 0

        do {
            val token = tokens.freshAccessToken()
            val currentPage = pageToken
            val response: FileListResponse = withRetry {
                http.get(FILES_ENDPOINT) {
                    header("Authorization", "Bearer $token")
                    parameter("q", query)
                    parameter("fields", "nextPageToken, files($FIELDS)")
                    parameter("orderBy", "folder,name")
                    parameter("pageSize", 1000)
                    parameter("spaces", "drive")
                    if (currentPage != null) parameter("pageToken", currentPage)
                }.orThrow().body()
            }

            results += response.files
            // A server that echoes back the token it was given would otherwise spin forever,
            // accumulating duplicates. The page cap is the second belt for the same problem.
            pageToken = response.nextPageToken?.takeIf { it != currentPage }
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        return results
    }

    suspend fun getFile(fileId: String): DriveFile {
        val token = tokens.freshAccessToken()
        return withRetry {
            http.get("$FILES_ENDPOINT/$fileId") {
                header("Authorization", "Bearer $token")
                parameter("fields", FIELDS)
            }.orThrow().body()
        }
    }

    /** The raw bytes of an audio file. */
    suspend fun downloadFile(fileId: String): ByteArray {
        val token = tokens.freshAccessToken()
        return withRetry {
            http.get("$FILES_ENDPOINT/$fileId") {
                header("Authorization", "Bearer $token")
                parameter("alt", "media")
            }.orThrow().readRawBytes()
        }
    }

/** Who is signed in: enough to show an account row, no more. */
    suspend fun userInfo(): UserInfo? {
        val token = tokens.freshAccessToken()
        return runCatching {
            http.get(USERINFO_ENDPOINT) {
                header("Authorization", "Bearer $token")
            }.orThrow().body<UserInfo>()
        }.getOrNull()
    }

    @Serializable
    data class UserInfo(
        val email: String? = null,
        val name: String? = null,
        /** Avatar URL. Google serves it unauthenticated, so any image loader can fetch it. */
        val picture: String? = null,
    )

    companion object {
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val FIELDS = "id,name,mimeType,size,modifiedTime,thumbnailLink,iconLink"

        /** Belt to the `pageToken` echo guard above; a real library never comes close. */
        private const val MAX_PAGES = 100

        /** Lenient decoding — Drive adds fields, and an unknown one must not fail a whole page. */
        fun defaultJson(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun configure(client: HttpClient): HttpClient = client
    }
}

/** Builds a client configured for Drive's JSON. */
fun driveHttpClient(engineClient: HttpClient): HttpClient = engineClient.config {
    install(ContentNegotiation) { json(DriveApiClient.defaultJson()) }
}
