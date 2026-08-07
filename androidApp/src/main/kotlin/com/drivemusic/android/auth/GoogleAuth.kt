package com.drivemusic.android.auth

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.drivemusic.shared.data.AccessTokenProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authorization for Drive, via Play Services' `AuthorizationClient`.
 *
 * Deliberately *not* Credential Manager plus a separate token exchange. What this app needs is an
 * OAuth access token carrying the Drive scope; `AuthorizationClient` returns exactly that and
 * handles account selection, consent and refresh itself. Credential Manager answers a different
 * question — "who is signed in" — and would need a Web client ID on top.
 *
 * No client ID appears in this file, and that is correct rather than an omission: an Android OAuth
 * client is identified by the app's package name and signing certificate, which Play Services
 * reads directly. It follows that a build signed with a different key — a different developer
 * machine's debug keystore, or a release build — is a different client as far as Google is
 * concerned, and its SHA-1 has to be registered too or authorization fails with a bare
 * `ApiException`.
 */
class GoogleAuth(private val context: Context) : AccessTokenProvider {

    data class Account(val email: String?)

    sealed interface State {
        data object SignedOut : State
        /** Consent is needed; the caller has to launch [request] and report the result back. */
        data class NeedsConsent(val request: IntentSenderRequest) : State
        data class Authorized(val account: Account) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var cachedToken: String? = null

    private val authorizationRequest: AuthorizationRequest
        get() = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    // Read-only: this app plays a library, it never modifies one, and asking for
                    // write access it does not use is a worse consent screen for no benefit.
                    Scope(DRIVE_READONLY),
                    Scope(USERINFO_EMAIL),
                )
            )
            .build()

    /**
     * Attempts authorization without showing any UI.
     *
     * Returns [State.NeedsConsent] when the user has to be asked — the caller launches that intent
     * and calls [onConsentResult]. Splitting it this way keeps every Activity dependency at the
     * call site: this class needs no Activity, so it can live for the whole process.
     */
    suspend fun authorize(): State {
        val result = runCatching { requestAuthorization() }
            .getOrElse { return State.Failed(it.message ?: "Authorization failed").also { s -> _state.value = s } }

        val state = when {
            result.hasResolution() -> {
                val pendingIntent = result.pendingIntent
                if (pendingIntent == null) State.Failed("Consent required but no intent was provided")
                else State.NeedsConsent(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
            else -> {
                cachedToken = result.accessToken
                if (result.accessToken == null) State.Failed("Authorized but no access token was returned")
                else State.Authorized(Account(email = null))
            }
        }
        _state.value = state
        return state
    }

    /** Reports the outcome of the consent screen the caller launched. */
    fun onConsentResult(data: Intent?): State {
        val state = runCatching {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            cachedToken = result.accessToken
            if (result.accessToken == null) State.Failed("Consent completed without an access token")
            else State.Authorized(Account(email = null))
        }.getOrElse { State.Failed(it.message ?: "Consent failed") }

        _state.value = state
        return state
    }

    fun signOut() {
        cachedToken = null
        _state.value = State.SignedOut
    }

    fun setEmail(email: String?) {
        val current = _state.value
        if (current is State.Authorized) _state.value = State.Authorized(Account(email))
    }

    /**
     * A token that is valid right now.
     *
     * Re-authorizes silently when there is nothing cached. Play Services owns the refresh cycle,
     * so "ask again" is the correct way to get a fresh token rather than tracking expiry here —
     * and asking is cheap once consent has been granted.
     */
    override suspend fun freshAccessToken(): String {
        cachedToken?.let { return it }
        val result = requestAuthorization()
        val token = result.accessToken
            ?: throw IllegalStateException("Drive authorization is required")
        cachedToken = token
        return token
    }

    /** Invalidates the cached token so the next call fetches a new one. */
    fun invalidateToken() {
        cachedToken = null
    }

    private suspend fun requestAuthorization(): AuthorizationResult =
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context)
                .authorize(authorizationRequest)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    private companion object {
        const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        const val USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    }
}
