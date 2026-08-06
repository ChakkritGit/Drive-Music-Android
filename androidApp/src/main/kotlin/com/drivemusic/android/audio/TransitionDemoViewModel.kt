package com.drivemusic.android.audio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.drivemusic.shared.transition.TransitionPlan
import com.drivemusic.shared.transition.TransitionPreset
import com.drivemusic.shared.transition.TransitionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State for the transition demo — see `TransitionDemoScreen`.
 *
 * This exists to answer one question the test suite structurally cannot: whether a transition
 * built from these curves actually sounds like a DJ mix. Everything else about the audio path is
 * verified offline; this part needs ears.
 *
 * Deliberately thin, and deliberately not a step toward the real player. The real one has a queue,
 * a library, analysis and a session to keep in sync. This just plays two files against each other.
 */
@UnstableApi
class TransitionDemoViewModel(application: Application) : AndroidViewModel(application) {

    data class State(
        val trackA: Uri? = null,
        val trackB: Uri? = null,
        val preset: TransitionPreset = TransitionPreset.MIX,
        val durationSeconds: Double = 8.0,
        /** Where in each track playback starts, so a mix can be auditioned mid-song. */
        val startSecondsA: Double = 30.0,
        val startSecondsB: Double = 30.0,
        val isPlaying: Boolean = false,
        val isTransitioning: Boolean = false,
        val status: String = "Pick two tracks.",
    ) {
        val canPlay: Boolean get() = trackA != null && trackB != null
    }

    private val engine = CrossfadeEngine(application, viewModelScope)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setTrackA(uri: Uri) = _state.update { it.copy(trackA = uri, status = "Track A ready.") }
    fun setTrackB(uri: Uri) = _state.update { it.copy(trackB = uri, status = "Track B ready.") }
    fun setPreset(preset: TransitionPreset) = _state.update { it.copy(preset = preset) }
    fun setDuration(seconds: Double) = _state.update { it.copy(durationSeconds = seconds) }

    /** Starts track A alone, so there is something to transition *out of*. */
    fun start() {
        val current = _state.value
        val a = current.trackA ?: return
        engine.prepare(PlaybackSlot.A, a.toString())
        engine.player(PlaybackSlot.A).apply {
            seekTo((current.startSecondsA * 1000).toLong())
            playWhenReady = true
        }
        _state.update {
            it.copy(isPlaying = true, status = "Playing A. Run the transition when you're ready.")
        }
    }

    /**
     * Builds a plan the same way playback would and hands it to the engine.
     *
     * `TransitionPlan.resolve` is the shared one — the same code the iOS app runs — with no
     * analysis passed, since the point here is to judge the curves rather than the beat matching.
     * That means no bar-aligned length and no tempo stretch: the duration is whatever the slider
     * says, which is exactly what an unanalyzed library gets in the real player too.
     */
    fun runTransition() {
        val current = _state.value
        val b = current.trackB ?: return
        if (engine.isTransitioning) return

        engine.prepare(engine.activeSlot.other, b.toString())

        val plan = TransitionPlan.resolve(
            settings = TransitionSettings(shape = current.preset.shape),
            outgoing = null,
            incoming = null,
            outgoingDuration = null,
            fallbackDuration = current.durationSeconds,
            autoMixEnabled = true,
            beatmatchEnabledByDefault = false,
        ).copy(incomingStartSeconds = current.startSecondsB)

        _state.update { it.copy(isTransitioning = true, status = "Transitioning…") }
        engine.startTransition(plan) {
            _state.update { it.copy(isTransitioning = false, status = "Done — track B is playing.") }
        }
    }

    fun cancel() {
        engine.cancelTransition()
        _state.update { it.copy(isTransitioning = false, status = "Transition cancelled.") }
    }

    fun stop() {
        engine.cancelTransition()
        PlaybackSlot.entries.forEach { engine.player(it).stop() }
        _state.update { it.copy(isPlaying = false, isTransitioning = false, status = "Stopped.") }
    }

    /**
     * Swaps which file is treated as A and which as B, so the same pair can be auditioned in both
     * directions without re-picking. Which track is leaving and which arriving changes a mix
     * completely — the outgoing one loses its top end and its bass, the incoming one arrives
     * filtered — so hearing it only one way tells you half of what you need.
     */
    fun swapTracks() {
        _state.update {
            it.copy(
                trackA = it.trackB,
                trackB = it.trackA,
                startSecondsA = it.startSecondsB,
                startSecondsB = it.startSecondsA,
                status = "Swapped. Press Play to hear it the other way round.",
            )
        }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
