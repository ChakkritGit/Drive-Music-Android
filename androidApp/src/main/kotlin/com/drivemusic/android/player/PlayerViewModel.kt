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
import com.drivemusic.shared.recommendation.RecommendationModel
import com.drivemusic.shared.transition.TransitionPlan
import com.drivemusic.shared.transition.TransitionSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        val volumeNormalizationEnabled: Boolean = true,
        val crossfadeSeconds: Double = 8.0,
        val eq: EqSettings = EqSettings.flat,
        val downloadedIds: Set<String> = emptySet(),
        val cachedTracks: List<CachedTrack> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val recentSources: List<RecentSource> = emptyList(),
        val cacheBytes: Long = 0,
        val downloadProgress: Pair<Int, Int>? = null,
    ) {
        val currentTrack: DriveFile? get() = queue.currentTrack

        /** Whether the playing track is in the Favourites playlist. */
        val isCurrentFavorite: Boolean
            get() {
                val id = currentTrack?.id ?: return false
                return playlists.firstOrNull { it.name == FAVORITES }?.tracks?.any { it.id == id } == true
            }
        val upNext get() = PlaybackQueue.upNext(queue)

        /** Downloaded tracks grouped by artist, for the Home shelves. */
        val artists: List<Pair<String, List<CachedTrack>>>
            get() = cachedTracks
                .groupBy { it.parsedMeta.artist?.takeIf(String::isNotBlank) ?: "Unknown artist" }
                .toList()
                .sortedBy { it.first.lowercase() }
    }

    private val engine = CrossfadeEngine(application, viewModelScope)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings = SettingsStore(application)
    private var model = RecommendationModel.createDefault()
    private var tickJob: Job? = null
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
        _state.update {
            it.copy(
                crossfadeEnabled = settings.crossfadeEnabled,
                crossfadeSeconds = settings.crossfadeSeconds,
                autoMixEnabled = settings.autoMixEnabled,
                gaplessEnabled = settings.gaplessEnabled,
                volumeNormalizationEnabled = settings.volumeNormalizationEnabled,
                eq = stored,
            )
        }

        viewModelScope.launch {
            model = library.loadModel()
            refreshLibrary()
            restoreSession()
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

    fun setAutoMixEnabled(enabled: Boolean) {
        settings.autoMixEnabled = enabled
        _state.update { it.copy(autoMixEnabled = enabled) }
    }

    fun setCrossfadeSeconds(seconds: Double) {
        settings.crossfadeSeconds = seconds
        _state.update { it.copy(crossfadeSeconds = seconds) }
    }

    fun setGaplessEnabled(enabled: Boolean) {
        settings.gaplessEnabled = enabled
        _state.update { it.copy(gaplessEnabled = enabled) }
    }

    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        settings.volumeNormalizationEnabled = enabled
        _state.update { it.copy(volumeNormalizationEnabled = enabled) }
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
                )
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
        val file = _state.value.currentTrack ?: return
        loadJob?.cancel()
        transitionArmedFor = null

        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, positionMs = 0, durationMs = 0) }
            try {
                val track = ensureCached(file)
                val slot = engine.activeSlot
                engine.prepare(slot, files.uri(track.relativeFilePath))
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

        val bytes = drive.downloadFile(file.id)
        val relativePath = files.store(bytes, file)
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
        return track
    }

    /** Tags and cover art from the downloaded file, via the platform extractor. */
    private fun readTags(uri: String): Pair<ParsedMetadata, ByteArray?> {
        val retriever = MediaMetadataRetriever()
        return try {
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

        val plan = TransitionPlan.resolve(
            settings = TransitionSettings.AUTO,
            outgoing = null,
            incoming = null,
            outgoingDuration = durationMs / 1000.0,
            fallbackDuration = state.crossfadeSeconds,
            autoMixEnabled = state.autoMixEnabled,
            beatmatchEnabledByDefault = false,
        )

        val startMs = ((durationMs / 1000.0 - plan.duration) * 1000).toLong()
        if (positionMs < startMs) return

        transitionArmedFor = current.id
        viewModelScope.launch {
            val track = runCatching { ensureCached(nextFile) }.getOrNull() ?: return@launch
            if (engine.isTransitioning) return@launch
            engine.prepare(engine.activeSlot.other, files.uri(track.relativeFilePath))
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
    private fun maybeAdvanceOnEnd(player: Player, positionMs: Long, durationMs: Long) {
        if (engine.isTransitioning || durationMs <= 0) return
        if (player.playbackState != Player.STATE_ENDED) return

        if (_state.value.queue.loopMode == LoopMode.ONE) {
            player.seekTo(0)
            player.play()
            return
        }
        _state.value.currentTrack?.let { trainOn(it, fraction = 1.0) }
        next()
    }

    // MARK: - Model and session

    private fun weightsFor(tracks: List<DriveFile>): List<Double> {
        val now = Clock.System.now()
        return tracks.map { RecommendationModel.predict(model, Features.extract(it, null, now)) }
    }

    private fun currentWeights(): List<Double> = weightsFor(_state.value.queue.tracks)

    /** Teaches the model how much of a track was actually played. */
    private fun trainOn(file: DriveFile, fraction: Double) {
        viewModelScope.launch {
            val features = Features.extract(file, _state.value.metadata, Clock.System.now())
            model = RecommendationModel.trainStep(model, features, fraction.coerceIn(0.0, 1.0))
            library.saveModel(model)
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
    }

    override fun onCleared() {
        stopTicking()
        engine.release()
        super.onCleared()
    }
}
