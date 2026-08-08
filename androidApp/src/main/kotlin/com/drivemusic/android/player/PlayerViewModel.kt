package com.drivemusic.android.player

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.drivemusic.android.audio.CrossfadeEngine
import com.drivemusic.android.audio.EqSettings
import com.drivemusic.android.audio.PlaybackSlot
import com.drivemusic.android.data.FileAudioStore
import com.drivemusic.android.data.RoomTrackLibrary
import com.drivemusic.shared.drive.DriveApiClient
import com.drivemusic.shared.model.CachedTrack
import com.drivemusic.shared.model.DriveFile
import com.drivemusic.shared.model.LoopMode
import com.drivemusic.shared.model.ParsedMetadata
import com.drivemusic.shared.model.PlaySource
import com.drivemusic.shared.model.Playlist
import com.drivemusic.shared.model.RecentSource
import com.drivemusic.shared.model.PlaybackSession
import com.drivemusic.shared.playback.PlaybackQueue
import com.drivemusic.shared.playback.PlaybackQueueState
import com.drivemusic.shared.recommendation.Features
import com.drivemusic.shared.recommendation.ModelEvent
import com.drivemusic.shared.recommendation.RecommendationModel
import com.drivemusic.android.audio.TrackAnalysisRunner
import com.drivemusic.shared.analysis.TrackAnalyzer
import com.drivemusic.shared.model.TrackAnalysis
import com.drivemusic.shared.transition.TransitionPlan
import com.drivemusic.shared.transition.TransitionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.io.File

/**
 * The player: the shared queue state machine on one side, the audio engine and the library on the
 * other.
 *
 * Everything here is *wiring*. The decisions — what plays next, how a transition is shaped, where
 * a mix starts and ends — all live in `:shared` and are tested there. That split is deliberate and
 * is the main lesson carried over from the iOS app, where the same decisions were tangled with the
 * engine and could not be tested at all.
 */
@UnstableApi
class PlayerViewModel(
    application: Application,
    private val library: RoomTrackLibrary,
    private val files: FileAudioStore,
    private val drive: DriveApiClient,
) : AndroidViewModel(application) {

    data class UiState(
        val queue: PlaybackQueueState = PlaybackQueueState(),
        val source: PlaySource? = null,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val metadata: ParsedMetadata? = null,
        val artwork: ByteArray? = null,
        val error: String? = null,
        val crossfadeEnabled: Boolean = true,
        val autoMixEnabled: Boolean = true,
        val gaplessEnabled: Boolean = true,
        val ambientGlowEnabled: Boolean = true,
        val beatmatchEnabled: Boolean = false,
        val autoAnalyzeEnabled: Boolean = true,
        val spatialAudioEnabled: Boolean = false,
        val spatialAudioIntensity: Double = 50.0,
        /** Tempo, key and mix points per file id, for the tracks that have been analysed. */
        val analyses: Map<String, TrackAnalysis> = emptyMap(),
        val volumeNormalizationEnabled: Boolean = true,
        val crossfadeSeconds: Double = 8.0,
        val eq: EqSettings = EqSettings.flat,
        val downloadedIds: Set<String> = emptySet(),
        val cachedTracks: List<CachedTrack> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val recentSources: List<RecentSource> = emptyList(),
        val cacheBytes: Long = 0,
        val downloadProgress: Pair<Int, Int>? = null,
        /** How many times the model has been trained — Home says so until it has enough. */
        val trainingEvents: Int = 0,
        /**
         * Average weight magnitude per feature group — what the recommender has actually learned
         * to care about. Summarised into state rather than exposing the model, so the analytics
         * screen reads numbers instead of reaching into the learner.
         */
        val featureWeights: List<Pair<String, Double>> = emptyList(),
        /** When the model last learned something. */
        val modelUpdatedAt: kotlinx.datetime.Instant? = null,
        /** Layer sizes, as `inputs → hidden → 1`. */
        val modelArchitecture: String = "",
        /** Euclidean norm of every weight in both layers — how much the model has grown overall. */
        val modelWeightNorm: Double = 0.0,
        val modelMinWeight: Double = 0.0,
        val modelMaxWeight: Double = 0.0,
        /** Recent training steps, newest first. */
        val modelEvents: List<ModelEvent> = emptyList(),
        /** Both layers' weights, for the network diagram. */
        val modelW1: List<List<Double>> = emptyList(),
        val modelW2: List<Double> = emptyList(),
    ) {
        val currentTrack: DriveFile? get() = queue.currentTrack

        /** Whether the playing track is in the Favourites playlist. */
        val isCurrentFavorite: Boolean get() = isFavorite(currentTrack?.id)

        fun isFavorite(fileId: String?): Boolean {
            val id = fileId ?: return false
            return playlists.firstOrNull { it.name == FAVORITES }?.tracks?.any { it.id == id } == true
        }

        /** Downloaded tracks by id, for rows that need metadata and artwork for one file. */
        val cachedById: Map<String, CachedTrack> get() = cachedTracks.associateBy { it.fileId }
        val upNext get() = PlaybackQueue.upNext(queue)

        /**
         * Downloaded tracks grouped by artist, most-represented first and capped.
         *
         * By count rather than alphabetically, and capped at [ARTIST_SHELF_LIMIT]: this is a
         * "browse by" shelf, not a complete index, and an alphabetical list buries the artists
         * the library is actually made of behind whoever happens to start with an A.
         *
         * Tracks with no artist tag are left out rather than collected under a placeholder —
         * "Unknown artist" is not an artist anyone wants to browse to.
         */
        val artists: List<Pair<String, List<CachedTrack>>>
            get() = cachedTracks
                .mapNotNull { track ->
                    track.parsedMeta.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { it to track }
                }
                .groupBy({ it.first }, { it.second })
                .toList()
                .sortedByDescending { it.second.size }
                .take(ARTIST_SHELF_LIMIT)
    }

    private val engine = CrossfadeEngine(application, viewModelScope)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings = SettingsStore(application)
    private var model = RecommendationModel.createDefault()
    private var tickJob: Job? = null
    private var analysisJob: Job? = null

    /**
     * Tracks waiting to be analysed. A plain queue rather than a set of parallel jobs — see
     * [analyzeInBackground].
     */
    private val pendingAnalysis = ArrayDeque<CachedTrack>()

    /** The track already loaded into the idle slot, waiting for the current one to end. */
    private var armedGapless: Pair<Int, CachedTrack>? = null
    private var isArmingGapless = false
    private var loadJob: Job? = null

    /** True while a transition has been started for the current track, so it fires only once. */
    private var transitionArmedFor: String? = null

    init {
        // Duration comes from a listener rather than from the progress tick, because the tick only
        // runs while playing — so a restored session, which loads paused, showed "0:00" for its
        // length and an empty scrubber until the user pressed play. Registered on both slots and
        // filtered to the active one, since the inactive slot is routinely holding a different
        // track mid-transition.
        PlaybackSlot.entries.forEach { slot ->
            engine.player(slot).addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_READY || engine.activeSlot != slot) return
                    val player = engine.player(slot)
                    _state.update {
                        it.copy(
                            durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                            positionMs = player.currentPosition,
                        )
                    }
                }
            })
        }

        val stored = settings.eq
        engine.eq = stored
        engine.spatialWet =
            if (settings.spatialAudioEnabled) settings.spatialAudioIntensity else 0.0
        _state.update {
            it.copy(
                crossfadeEnabled = settings.crossfadeEnabled,
                crossfadeSeconds = settings.crossfadeSeconds,
                autoMixEnabled = settings.autoMixEnabled,
                gaplessEnabled = settings.gaplessEnabled,
                ambientGlowEnabled = settings.ambientGlowEnabled,
                beatmatchEnabled = settings.beatmatchEnabled,
                autoAnalyzeEnabled = settings.autoAnalyzeEnabled,
                spatialAudioEnabled = settings.spatialAudioEnabled,
                spatialAudioIntensity = settings.spatialAudioIntensity,
                volumeNormalizationEnabled = settings.volumeNormalizationEnabled,
                eq = stored,
            )
        }

        viewModelScope.launch {
            model = library.loadModel()
            _state.update { it.copy(trainingEvents = model.trainingEvents) }
            publishModelSummary()
            // Anything the previous analyser produced is dropped rather than trusted: the version
            // exists precisely because a stored BPM from an older detector is worse than none —
            // it looks authoritative and shapes every mix out of that track.
            library.deleteStaleAnalyses(TrackAnalyzer.VERSION)
            _state.update { it.copy(modelEvents = library.listModelEvents()) }
            refreshLibrary()
            restoreSession()
            analyzeBacklog()
        }
    }

    // MARK: - Transport

    fun play(tracks: List<DriveFile>, startIndex: Int, source: PlaySource?) {
        // Picking a specific row is a request to hear the list *from there* — leaving shuffle on
        // would play the tapped track and then jump somewhere random, which reads as the tap
        // having chosen the wrong thing.
        val withoutShuffle = PlaybackQueue.setShuffle(_state.value.queue, false, weightsFor(tracks))
        val queue = PlaybackQueue.play(withoutShuffle, tracks, startIndex, weightsFor(tracks))
        _state.update { it.copy(queue = queue, source = source) }
        recordSource(source, tracks)
        loadCurrent(autoplay = true)
    }

    fun shufflePlay(tracks: List<DriveFile>, source: PlaySource?) {
        val weights = weightsFor(tracks)
        val start = RecommendationModel.weightedRandomIndex(weights)
        val shuffled = PlaybackQueue.setShuffle(_state.value.queue, true, weights)
        val queue = PlaybackQueue.play(shuffled, tracks, start, weights)
        _state.update { it.copy(queue = queue, source = source) }
        recordSource(source, tracks)
        loadCurrent(autoplay = true)
    }

    private fun recordSource(source: PlaySource?, tracks: List<DriveFile>) {
        if (source == null || tracks.isEmpty()) return
        viewModelScope.launch {
            library.recordRecentSource(source, tracks)
            _state.update { it.copy(recentSources = library.listRecentSources()) }
        }
    }

    fun togglePlayPause() {
        val player = engine.player(engine.activeSlot)
        if (_state.value.isPlaying) {
            player.pause()
            engine.cancelTransition()
            _state.update { it.copy(isPlaying = false) }
            stopTicking()
            persistSession()
        } else {
            if (_state.value.currentTrack == null) return
            player.play()
            _state.update { it.copy(isPlaying = true) }
            startTicking()
        }
    }

    fun next() {
        engine.cancelTransition()
        val advance = PlaybackQueue.next(_state.value.queue, currentWeights())
        if (!advance.changed) return
        _state.update { it.copy(queue = advance.state) }
        loadCurrent(autoplay = true)
    }

    fun previous() {
        engine.cancelTransition()
        // Restarting the track is what a first press means anywhere past the opening seconds.
        if (_state.value.positionMs > 3_000) {
            seekTo(0)
            return
        }
        val advance = PlaybackQueue.previous(_state.value.queue, currentWeights())
        _state.update { it.copy(queue = advance.state) }
        loadCurrent(autoplay = true)
    }

    fun seekTo(positionMs: Long) {
        engine.cancelTransition()
        engine.player(engine.activeSlot).seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun jumpTo(index: Int) {
        engine.cancelTransition()
        _state.update { it.copy(queue = PlaybackQueue.jumpTo(it.queue, index)) }
        loadCurrent(autoplay = true)
    }

    fun addToQueue(file: DriveFile) {
        _state.update { it.copy(queue = PlaybackQueue.addToQueue(it.queue, file)) }
        if (_state.value.queue.tracks.size == 1) loadCurrent(autoplay = true)
    }

    fun removeFromQueue(index: Int) {
        _state.update { it.copy(queue = PlaybackQueue.removeFromQueue(it.queue, index)) }
    }

    fun toggleShuffle() {
        val queue = _state.value.queue
        _state.update {
            it.copy(queue = PlaybackQueue.setShuffle(queue, !queue.shuffle, currentWeights()))
        }
        persistSession()
    }

    fun cycleLoopMode() {
        _state.update { it.copy(queue = it.queue.copy(loopMode = it.queue.loopMode.next)) }
        persistSession()
    }

    // MARK: - Settings

    fun setCrossfadeEnabled(enabled: Boolean) {
        settings.crossfadeEnabled = enabled
        _state.update { it.copy(crossfadeEnabled = enabled) }
    }

    fun setBeatmatchEnabled(enabled: Boolean) {
        settings.beatmatchEnabled = enabled
        _state.update { it.copy(beatmatchEnabled = enabled) }
    }

    fun setAutoAnalyzeEnabled(enabled: Boolean) {
        settings.autoAnalyzeEnabled = enabled
        _state.update { it.copy(autoAnalyzeEnabled = enabled) }
        // Turning it back on picks up whatever was skipped while it was off, rather than leaving
        // those tracks unanalysed until they happen to be downloaded again.
        if (enabled) viewModelScope.launch { analyzeBacklog() }
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        settings.spatialAudioEnabled = enabled
        _state.update { it.copy(spatialAudioEnabled = enabled) }
        applySpatialAudio()
    }

    fun setSpatialAudioIntensity(percent: Double) {
        val clamped = percent.coerceIn(0.0, MAX_SPATIAL_INTENSITY)
        settings.spatialAudioIntensity = clamped
        _state.update { it.copy(spatialAudioIntensity = clamped) }
        applySpatialAudio()
    }

    /**
     * The playback gain for one track: its measured loudness gain, or 1.
     *
     * 1 when normalisation is off, and 1 when the track has not been analysed yet — an unmeasured
     * track is left exactly as it is rather than guessed at, so the worst case is that it is the
     * odd one out rather than wrong.
     */
    private fun loudnessGainFor(fileId: String): Double {
        val current = _state.value
        if (!current.volumeNormalizationEnabled) return 1.0
        return current.analyses[fileId]?.loudnessGain ?: 1.0
    }

    private fun applySpatialAudio() {
        val current = _state.value
        engine.spatialWet = if (current.spatialAudioEnabled) current.spatialAudioIntensity else 0.0
    }

    fun setAmbientGlowEnabled(enabled: Boolean) {
        settings.ambientGlowEnabled = enabled
        _state.update { it.copy(ambientGlowEnabled = enabled) }
    }

    /**
     * The current output level, 0..1 — read straight from the audio chain rather than published
     * through [state].
     *
     * The glow that uses this redraws every frame; routing a per-frame value through a `StateFlow`
     * would invalidate every collector of the whole player state 60 times a second to move one
     * gradient. Callers poll this from their own frame loop instead, exactly as the iOS version
     * reads `levelTap.currentLevel` from inside its `TimelineView`.
     */
    fun audioLevel(): Float = engine.currentLevel()

    fun setAutoMixEnabled(enabled: Boolean) {
        settings.autoMixEnabled = enabled
        _state.update { it.copy(autoMixEnabled = enabled) }
    }

    fun setCrossfadeSeconds(seconds: Double) {
        // Clamped here rather than only in the slider: the stored value outlives the control that
        // set it, and one already past the ceiling would survive every launch after.
        val clamped = seconds.coerceIn(0.0, MAX_CROSSFADE_SECONDS)
        settings.crossfadeSeconds = clamped
        _state.update { it.copy(crossfadeSeconds = clamped) }
    }

    fun setGaplessEnabled(enabled: Boolean) {
        settings.gaplessEnabled = enabled
        _state.update { it.copy(gaplessEnabled = enabled) }
        if (!enabled) cancelGaplessArm()
    }

    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        settings.volumeNormalizationEnabled = enabled
        _state.update { it.copy(volumeNormalizationEnabled = enabled) }
        // Applied to what is already playing, not only to whatever loads next — a switch whose
        // effect you cannot hear until the next track is a switch you cannot tell you have flipped.
        val playing = _state.value.currentTrack?.id
        if (playing != null) {
            engine.player(engine.activeSlot).volume = loudnessGainFor(playing).toFloat()
        }
    }

    fun setEq(eq: EqSettings) {
        settings.eq = eq
        engine.eq = eq
        _state.update { it.copy(eq = eq) }
    }

    // MARK: - Library and playlists

    /** Re-reads what is downloaded and what playlists exist. */
    fun refreshLibrary() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    cachedTracks = library.listCachedTracks(),
                    downloadedIds = library.listCachedTrackIds(),
                    playlists = library.listPlaylists(),
                    recentSources = library.listRecentSources(),
                    cacheBytes = files.totalBytes(),
                    analyses = library.listAnalyses(),
                )
            }
        }
    }

    /**
     * Analyses [file] if it has not been analysed by the current analyser, and stores the result.
     *
     * Fire and forget, one track at a time. Analysis is an enhancement — without it the player
     * falls back to a plain crossfade — so it must never be on the path between tapping a track
     * and hearing it. [analysisJob] holding a single job is the throttle: downloading a folder
     * queues dozens of these, and running them together would put every core on decoding audio
     * while the user is trying to listen to it.
     */
    /**
     * Queues every downloaded track that has no current analysis.
     *
     * Run once at startup so a library downloaded before analysis existed catches up on its own,
     * rather than each track staying unanalysed until the next time it happens to be re-downloaded.
     * It is the same single-file-at-a-time queue as everything else, so it costs one core in the
     * background and finishes whenever it finishes.
     */
    /**
     * Analyses everything downloaded that has not been analysed yet.
     *
     * Public because it is offered as a one-off action next to the numbers it fills in, and it
     * runs regardless of the automatic setting — asking for it *is* the request.
     */
    fun analyzeAll() {
        viewModelScope.launch {
            val analysed = _state.value.analyses
            library.listCachedTracks()
                .filter { analysed[it.fileId]?.version != TrackAnalyzer.VERSION }
                .forEach { enqueueAnalysis(it) }
        }
    }

    private suspend fun analyzeBacklog() {
        val analysed = _state.value.analyses
        library.listCachedTracks()
            .filter { analysed[it.fileId]?.version != TrackAnalyzer.VERSION }
            .forEach { analyzeInBackground(it) }
    }

    private fun analyzeInBackground(track: CachedTrack) {
        if (!_state.value.autoAnalyzeEnabled) return
        enqueueAnalysis(track)
    }

    /** Queues [track] for analysis whatever the automatic setting says. */
    private fun enqueueAnalysis(track: CachedTrack) {
        val existing = _state.value.analyses[track.fileId]
        if (existing != null && existing.version == TrackAnalyzer.VERSION) return
        if (analysisJob?.isActive == true) {
            pendingAnalysis += track
            return
        }
        analysisJob = viewModelScope.launch {
            var next: CachedTrack? = track
            while (next != null) {
                val current = next
                val result = runCatching {
                    TrackAnalysisRunner.analyze(files.file(current.relativeFilePath), current.fileId)
                }.getOrNull()
                if (result != null) {
                    library.putAnalysis(result)
                    _state.update { it.copy(analyses = it.analyses + (result.fileId to result)) }
                }
                next = pendingAnalysis.removeFirstOrNull()
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            library.createPlaylist(name)
            refreshLibrary()
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            library.deletePlaylist(id)
            refreshLibrary()
        }
    }

    fun addToPlaylist(playlistId: String, file: DriveFile) {
        viewModelScope.launch {
            library.addTrackToPlaylist(playlistId, file)
            refreshLibrary()
        }
    }

    fun removeFromPlaylist(playlistId: String, fileId: String) {
        viewModelScope.launch {
            library.removeTrackFromPlaylist(playlistId, fileId)
            refreshLibrary()
        }
    }

    /** Downloads every track in [files] that is not already cached, reporting progress. */
    fun downloadAll(tracks: List<DriveFile>) {
        viewModelScope.launch {
            val pending = tracks.filterNot { it.id in _state.value.downloadedIds }
            if (pending.isEmpty()) return@launch
            _state.update { it.copy(downloadProgress = 0 to pending.size) }
            pending.forEachIndexed { index, file ->
                runCatching { ensureCached(file) }
                _state.update { it.copy(downloadProgress = (index + 1) to pending.size) }
            }
            _state.update { it.copy(downloadProgress = null) }
            refreshLibrary()
        }
    }

    fun removeDownload(fileId: String) {
        viewModelScope.launch {
            library.getCachedTrack(fileId)?.let { files.delete(it.relativeFilePath) }
            library.deleteCachedTrack(fileId)
            refreshLibrary()
        }
    }

    /** The danger-zone action: every download, every playlist, the model, the session. */
    fun clearAllData() {
        viewModelScope.launch {
            engine.cancelTransition()
            PlaybackSlot.entries.forEach { engine.player(it).stop() }
            stopTicking()
            library.clearAll()
            files.clearAll()
            model = RecommendationModel.createDefault()
            _state.value = UiState(
                crossfadeEnabled = settings.crossfadeEnabled,
                crossfadeSeconds = settings.crossfadeSeconds,
                autoMixEnabled = settings.autoMixEnabled,
                gaplessEnabled = settings.gaplessEnabled,
                ambientGlowEnabled = settings.ambientGlowEnabled,
                beatmatchEnabled = settings.beatmatchEnabled,
                autoAnalyzeEnabled = settings.autoAnalyzeEnabled,
                spatialAudioEnabled = settings.spatialAudioEnabled,
                spatialAudioIntensity = settings.spatialAudioIntensity,
                volumeNormalizationEnabled = settings.volumeNormalizationEnabled,
                eq = settings.eq,
            )
        }
    }

    /**
     * Adds or removes the track from Favourites, creating the playlist the first time.
     *
     * Favourites is an ordinary playlist rather than a flag on the track: it then shows up in the
     * Playlists tab and on Home for free, and there is one concept of "a set of tracks" rather
     * than two.
     */
    /** Whether the model has seen enough plays for its ranking to mean anything. */
    val isModelTrained: Boolean get() = model.trainingEvents >= TRAINED_THRESHOLD

    /**
     * Summarises the model's first-layer weights by feature group.
     *
     * Mean of the absolute value, not the sum: the groups have very different sizes — one bias
     * against dozens of artist buckets — and a sum would rank them by how many dimensions they
     * happen to occupy rather than by how much the model leans on them.
     */
    private fun publishModelSummary() {
        var offset = 0
        val weights = Features.groups.map { group ->
            var total = 0.0
            var count = 0
            for (index in offset until offset + group.size) {
                model.w1.getOrNull(index)?.forEach { total += kotlin.math.abs(it); count++ }
            }
            offset += group.size
            group.label to if (count == 0) 0.0 else total / count
        }
        val flat = model.w1.flatten() + model.w2
        _state.update {
            it.copy(
                featureWeights = weights,
                modelUpdatedAt = model.updatedAt,
                modelArchitecture =
                    "${Features.FEATURE_SIZE} → ${RecommendationModel.HIDDEN_SIZE} → 1",
                modelWeightNorm = kotlin.math.sqrt(flat.sumOf { it * it }),
                modelMinWeight = flat.minOrNull() ?: 0.0,
                modelMaxWeight = flat.maxOrNull() ?: 0.0,
                modelW1 = model.w1,
                modelW2 = model.w2,
            )
        }
    }

    fun toggleFavorite(file: DriveFile) {
        viewModelScope.launch {
            val favorites = library.listPlaylists().firstOrNull { it.name == FAVORITES }
                ?: library.createPlaylist(FAVORITES)
            if (favorites.tracks.any { it.id == file.id }) {
                library.removeTrackFromPlaylist(favorites.id, file.id)
            } else {
                library.addTrackToPlaylist(favorites.id, file)
            }
            refreshLibrary()
        }
    }

    /** Cover art for one track, by id — never carried on the track itself. */
    suspend fun artworkFor(fileId: String): ByteArray? = library.artwork(fileId)

    /**
     * Up to four covers for a collection's collage.
     *
     * Stops as soon as it has four rather than reading every track: a shelf of a dozen cards would
     * otherwise pull the artwork of the entire library to draw twelve thumbnails.
     */
    suspend fun coversFor(tracks: List<DriveFile>, limit: Int = 4): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        for (file in tracks) {
            library.artwork(file.id)?.let(result::add)
            if (result.size >= limit) break
        }
        return result
    }

    /** The tracks the model thinks the listener wants right now — the Home "Made for you" shelf. */
    fun recommended(limit: Int = 12): List<CachedTrack> {
        val now = Clock.System.now()
        return _state.value.cachedTracks
            .map { it to RecommendationModel.predict(model, Features.extract(it.driveMeta, it.parsedMeta, now)) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // MARK: - Loading

    /**
     * @param seekToMs where to start. Non-zero only when resuming a saved session — every other
     *   load is a fresh selection and starts at the top.
     */
    private fun loadCurrent(autoplay: Boolean, seekToMs: Long = 0) {
        // Anything armed was armed for a handover that is no longer the one happening — a skip, a
        // jump, or a new queue. Left in place it would fire at the end of the *new* track and play
        // whatever was next before the change.
        cancelGaplessArm()
        // What actually happened to the track being left behind, before anything overwrites it.
        trainOnOutgoing()
        val file = _state.value.currentTrack ?: return
        loadJob?.cancel()
        transitionArmedFor = null

        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, positionMs = 0, durationMs = 0) }
            try {
                val track = ensureCached(file)
                val slot = engine.activeSlot
                engine.prepare(slot, files.uri(track.relativeFilePath), loudnessGainFor(track.fileId))
                val player = engine.player(slot)
                if (seekToMs > 0) player.seekTo(seekToMs)
                player.playWhenReady = autoplay

                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = autoplay,
                        positionMs = seekToMs,
                        metadata = track.parsedMeta,
                        artwork = library.artwork(track.fileId),
                    )
                }
                if (autoplay) startTicking()
                persistSession()
                prefetchUpcoming()
            } catch (error: Exception) {
                _state.update { it.copy(isLoading = false, error = error.message ?: "Playback failed") }
            }
        }
    }

    /** The track on disk, downloading and reading its tags first if this is the first play. */
    private suspend fun ensureCached(file: DriveFile): CachedTrack {
        library.getCachedTrack(file.id)?.let { return it }

        // Streamed straight to disk. The bytes are on their way to a file, so there is no moment
        // where the whole track needs to exist in memory — which is what used to kill the process
        // on a large one.
        val relativePath = files.store(file) { sink -> drive.downloadFile(file.id, sink) }
        val uri = files.uri(relativePath)
        val (metadata, artwork) = readTags(uri)

        val track = CachedTrack(
            fileId = file.id,
            relativeFilePath = relativePath,
            mimeType = file.mimeType,
            driveMeta = file,
            parsedMeta = metadata,
            cachedAt = Clock.System.now(),
        )
        library.putCachedTrack(track)
        // Stored separately from the metadata — see the note on `ArtworkEntity`.
        artwork?.let { library.putArtwork(file.id, it) }
        _state.update { it.copy(downloadedIds = it.downloadedIds + file.id) }
        analyzeInBackground(track)
        return track
    }

    /** Tags and cover art from the downloaded file, via the platform extractor. */
    /**
     * Reads a file's tags and embedded cover.
     *
     * On the IO dispatcher, not the caller's. This opens the file, parses its container and pulls
     * out a cover that is routinely hundreds of kilobytes — none of which belongs on the thread
     * drawing the screen, and all of which used to happen there.
     */
    private suspend fun readTags(uri: String): Pair<ParsedMetadata, ByteArray?> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(File(java.net.URI(uri)).absolutePath)
            val metadata = ParsedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                durationSec = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { it / 1000.0 },
            )
            metadata to retriever.embeddedPicture
        } catch (_: Exception) {
            // A file whose tags cannot be read still plays; the file name is the fallback title.
            ParsedMetadata() to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Downloads what is coming up, so a skip does not wait on the network. */
    private fun prefetchUpcoming(limit: Int = 2) {
        viewModelScope.launch {
            PlaybackQueue.upNext(_state.value.queue, limit).forEach { entry ->
                runCatching { ensureCached(entry.file) }
            }
        }
    }

    // MARK: - Progress and transitions

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val player = engine.player(engine.activeSlot)
                val position = player.currentPosition
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                _state.update { it.copy(positionMs = position, durationMs = duration) }

                maybeStartTransition(position, duration)
                maybeArmGapless(position, duration)
                maybeAdvanceOnEnd(player, position, duration)
                delay(250)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    /**
     * Starts the transition when the current track reaches its mix-out point.
     *
     * The plan comes from `:shared`, so the length, the shape and the incoming start are the same
     * decisions the iOS app makes. Without analysis the mix-out point is unknown and this falls
     * back to "the transition finishes as the track ends", which is the behavior an unanalyzed
     * library gets on both platforms.
     */
    private fun maybeStartTransition(positionMs: Long, durationMs: Long) {
        val state = _state.value
        if (!state.crossfadeEnabled || engine.isTransitioning || durationMs <= 0) return
        val current = state.currentTrack ?: return
        if (transitionArmedFor == current.id) return
        if (state.queue.loopMode == LoopMode.ONE) return

        val nextIndex = PlaybackQueue.peekNextIndex(state.queue) ?: return
        val nextFile = state.queue.tracks.getOrNull(nextIndex) ?: return

        val outgoing = state.analyses[current.id]
        val incoming = state.analyses[nextFile.id]

        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = outgoing,
            incoming = incoming,
            outgoingDuration = durationMs / 1000.0,
            fallbackDuration = state.crossfadeSeconds,
            autoMixEnabled = state.autoMixEnabled,
            beatmatchEnabledByDefault = state.beatmatchEnabled,
        )

        // Where the transition begins. With analysis, the outgoing track's mix-out point — the
        // moment its arrangement drops away — is where a listener would expect the next track to
        // arrive. Without it, the only answer is "so that the transition finishes as the track
        // ends", which is what an unanalysed library got before and still gets.
        val duration = durationMs / 1000.0
        val mixOut = outgoing?.mixOutSeconds?.takeIf { it > 0 && it < duration }
        val startSeconds = plan.startSeconds
            ?: mixOut
            ?: (duration - plan.duration)
        val startMs = (startSeconds * 1000).toLong()
        if (positionMs < startMs) return

        transitionArmedFor = current.id
        viewModelScope.launch {
            val track = runCatching { ensureCached(nextFile) }.getOrNull() ?: return@launch
            if (engine.isTransitioning) return@launch
            engine.prepare(
                engine.activeSlot.other,
                files.uri(track.relativeFilePath),
                loudnessGainFor(track.fileId),
            )
            engine.startTransition(plan) { promoted ->
                onTransitionCommitted(nextIndex, track, promoted)
            }
        }
    }

    /** Moves the app's idea of "current" to match what the engine is already playing. */
    private fun onTransitionCommitted(index: Int, track: CachedTrack, slot: PlaybackSlot) {
        viewModelScope.launch {
            val queue = PlaybackQueue.consumePlayNext(
                _state.value.queue.copy(currentIndex = index), index
            )
            val player = engine.player(slot)
            _state.update {
                it.copy(
                    queue = queue,
                    metadata = track.parsedMeta,
                    artwork = library.artwork(track.fileId),
                    isPlaying = true,
                    // Read here as well as from the listener: the incoming slot reached READY
                    // while it was still the *inactive* one, so the listener declined it.
                    durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                    positionMs = player.currentPosition,
                )
            }
            transitionArmedFor = null
            trainOn(track.driveMeta, fraction = 1.0)
            persistSession()
            prefetchUpcoming()
        }
    }

    /** The plain hand-off, for when crossfade is off or a transition did not arm in time. */
    /**
     * Loads the next track into the idle slot before the current one ends.
     *
     * This is what "gapless" means here: the silence it removes is the time spent opening and
     * buffering the next file, which happens *after* the current track has finished if nothing
     * prepares it first. A player that is already prepared starts on the next buffer.
     *
     * Only when crossfade is off. A crossfade already prepares the incoming track — earlier, and
     * with a curve — so arming a second handover underneath it would have two paths racing to
     * advance the same queue.
     */
    private fun maybeArmGapless(positionMs: Long, durationMs: Long) {
        val state = _state.value
        if (!state.gaplessEnabled || state.crossfadeEnabled) return
        if (engine.isTransitioning || durationMs <= 0 || !state.isPlaying) return
        if (state.queue.loopMode == LoopMode.ONE) return
        if (armedGapless != null || isArmingGapless) return
        if (durationMs - positionMs > GAPLESS_ARM_LEAD_MS) return

        val nextIndex = PlaybackQueue.peekNextIndex(state.queue) ?: return
        val nextFile = state.queue.tracks.getOrNull(nextIndex) ?: return

        isArmingGapless = true
        viewModelScope.launch {
            val track = runCatching { ensureCached(nextFile) }.getOrNull()
            isArmingGapless = false
            if (track == null || engine.isTransitioning) return@launch
            // The queue may have moved while the file was being fetched, in which case what was
            // armed is no longer what comes next.
            if (PlaybackQueue.peekNextIndex(_state.value.queue) != nextIndex) return@launch
            engine.prepare(
                engine.activeSlot.other,
                files.uri(track.relativeFilePath),
                loudnessGainFor(track.fileId),
            )
            armedGapless = nextIndex to track
        }
    }

    /** Drops anything armed — the queue moved, or the setting went off. */
    private fun cancelGaplessArm() {
        armedGapless = null
    }

    private fun maybeAdvanceOnEnd(player: Player, positionMs: Long, durationMs: Long) {
        if (engine.isTransitioning || durationMs <= 0) return
        if (player.playbackState != Player.STATE_ENDED) return

        if (_state.value.queue.loopMode == LoopMode.ONE) {
            player.seekTo(0)
            player.play()
            return
        }
        _state.value.currentTrack?.let { trainOn(it, fraction = 1.0) }

        val armed = armedGapless
        if (armed != null && PlaybackQueue.peekNextIndex(_state.value.queue) == armed.first) {
            armedGapless = null
            startArmedGapless(armed.first, armed.second)
            return
        }
        next()
    }

    /** Hands over to the slot [maybeArmGapless] already prepared. */
    private fun startArmedGapless(index: Int, track: CachedTrack) {
        val slot = engine.activeSlot.other
        engine.promoteToActive(slot)
        onTransitionCommitted(index, track, slot)
    }

    // MARK: - Model and session

    private fun weightsFor(tracks: List<DriveFile>): List<Double> {
        val now = Clock.System.now()
        return tracks.map { RecommendationModel.predict(model, Features.extract(it, null, now)) }
    }

    private fun currentWeights(): List<Double> = weightsFor(_state.value.queue.tracks)

    /** Teaches the model how much of a track was actually played. */
    /**
     * Trains on how much of the track being replaced actually played.
     *
     * Without this the model only ever saw `1.0` — training happened solely when a track ran to
     * its end, so every label it had said "this was played all the way through" and it could not
     * learn anything from the strongest signal a listener gives: skipping. A track abandoned after
     * five seconds now teaches that, with the fraction it earned.
     *
     * The position is read from the player rather than from state, because state is about to be
     * replaced and a value mid-update would be the wrong track's.
     */
    private fun trainOnOutgoing() {
        val file = _state.value.currentTrack ?: return
        val player = engine.player(engine.activeSlot)
        val duration = player.duration
        if (duration <= 0) return
        val fraction = (player.currentPosition.toDouble() / duration).coerceIn(0.0, 1.0)
        // Nothing to learn from a track that was never really started — loading one and moving on
        // is not a judgement about it.
        if (fraction < MINIMUM_TRAINABLE_FRACTION) return
        trainOn(file, fraction)
    }

    private fun trainOn(file: DriveFile, fraction: Double) {
        viewModelScope.launch {
            val now = Clock.System.now()
            val features = Features.extract(file, _state.value.metadata, now)
            val target = fraction.coerceIn(0.0, 1.0)
            // Recorded *before* the step, which is the whole point of the number: what the model
            // thought was going to happen while it still did not know the answer. Read after
            // training it would only tell you how well it remembers what it was just told.
            val predicted = RecommendationModel.predict(model, features)
            model = RecommendationModel.trainStep(model, features, target)
            library.saveModel(model)
            library.recordModelEvent(
                ModelEvent(
                    id = "${file.id}-${now.toEpochMilliseconds()}",
                    trackId = file.id,
                    title = _state.value.metadata?.title?.takeIf { it.isNotBlank() } ?: file.displayName,
                    fraction = target,
                    predicted = predicted,
                    at = now,
                )
            )
            // Home re-ranks "Made For You" off this, and says "Learning your taste…" until the
            // count clears the threshold.
            _state.update {
                it.copy(
                    trainingEvents = model.trainingEvents,
                    modelEvents = library.listModelEvents(),
                )
            }
            publishModelSummary()
        }
    }

    private fun persistSession() {
        val state = _state.value
        val session = PlaybackSession.from(
            state.queue, state.source, state.positionMs / 1000.0, volume = 1.0
        ) ?: return
        viewModelScope.launch { library.savePlaybackSession(session) }
    }

    /** Resumes the last session, loaded but paused — a launch that starts playing is a surprise. */
    private suspend fun restoreSession() {
        val session = library.loadPlaybackSession() ?: return
        val queue = session.toQueueState()
        if (queue.currentTrack == null) return
        _state.update { it.copy(queue = queue, source = session.source) }
        // Resumed where it left off, but paused — a launch that starts playing on its own is a
        // surprise, and the position is what makes "resume" mean anything.
        loadCurrent(autoplay = false, seekToMs = (session.progress * 1000).toLong())
    }

    companion object {
        const val FAVORITES = "Favourites"

        /** Plays before the model's ranking is worth showing as a recommendation. */
        const val TRAINED_THRESHOLD = 5
        const val ARTIST_SHELF_LIMIT = 20

        /** The spatial slider's ceiling, matching iOS's `maxSpatialIntensity`. */
        const val MAX_SPATIAL_INTENSITY = 100.0

        /**
         * The longest a crossfade may be, matching iOS's `maxCrossfadeSeconds`.
         *
         * The Android slider allowed 20s, so the two platforms disagreed about what the same
         * setting means — and a 20s value written here would be read back on iOS as something it
         * would never have produced.
         */
        const val MAX_CROSSFADE_SECONDS = 12.0

        /**
         * How far before the end the next track is loaded for a gapless handover.
         *
         * Long enough to open and buffer a local file comfortably, short enough that a queue edit
         * made while listening is unlikely to land inside the window and arm the wrong track.
         */
        const val GAPLESS_ARM_LEAD_MS = 10_000L

        /**
         * Below this the track barely played, and treating that as a verdict would punish tracks
         * for being passed over on the way somewhere else.
         */
        const val MINIMUM_TRAINABLE_FRACTION = 0.02
    }

    override fun onCleared() {
        stopTicking()
        engine.release()
        super.onCleared()
    }
}
