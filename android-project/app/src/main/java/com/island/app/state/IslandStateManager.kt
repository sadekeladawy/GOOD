package com.island.app.state

import androidx.compose.runtime.Immutable
import com.island.app.data.IslandEventType
import com.island.app.data.IslandSettings
import com.island.app.model.IslandDisplayMode
import com.island.app.model.IslandEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class IslandUiState(
    val activeEvent: IslandEvent? = null,
    val queuedCount: Int = 0,
    val displayMode: IslandDisplayMode = IslandDisplayMode.IDLE,
    val persistentMusic: IslandEvent.MusicPlayback? = null,
    val isFullscreenHidden: Boolean = false
)

class IslandStateManager(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> IslandSettings
) {
    private val _uiState = MutableStateFlow(IslandUiState())
    val uiState: StateFlow<IslandUiState> = _uiState.asStateFlow()

    // Accessed strictly on scope's Main.immediate dispatcher to prevent ConcurrentModificationException
    private val eventQueue = ArrayDeque<IslandEvent>()
    private var autoCollapseJob: Job? = null

    fun postEvent(event: IslandEvent) {
        scope.launch {
            handlePostEvent(event)
        }
    }

    private fun handlePostEvent(event: IslandEvent) {
        val settings = settingsProvider()
        if (!settings.masterEnabled || !settings.isEventEnabled(event.type)) {
            return
        }

        if (event is IslandEvent.MusicPlayback) {
            handleMusicUpdate(event)
            return
        }

        if (event is IslandEvent.IncomingCall) {
            eventQueue.removeAll { it is IslandEvent.IncomingCall }
            showImmediate(event, IslandDisplayMode.EXPANDED)
            return
        }

        val current = _uiState.value.activeEvent
        if (current == null || current is IslandEvent.MusicPlayback) {
            showImmediate(event, IslandDisplayMode.COMPACT)
        } else {
            if (eventQueue.size >= 4) {
                eventQueue.removeFirst()
            }
            eventQueue.addLast(event)
            _uiState.update { it.copy(queuedCount = eventQueue.size) }
        }
    }

    fun clearIncomingCall() {
        scope.launch {
            val current = _uiState.value.activeEvent
            if (current is IslandEvent.IncomingCall) {
                advanceOrCollapse()
            } else {
                eventQueue.removeAll { it is IslandEvent.IncomingCall }
                _uiState.update { it.copy(queuedCount = eventQueue.size) }
            }
        }
    }

    private fun handleMusicUpdate(music: IslandEvent.MusicPlayback) {
        _uiState.update { state ->
            val updatedMusic = if (music.isPlaying || state.persistentMusic != null) {
                music
            } else {
                null
            }
            val shouldPromote = state.activeEvent == null || state.activeEvent is IslandEvent.MusicPlayback
            if (shouldPromote) {
                val targetMode = when {
                    !music.isPlaying && state.displayMode == IslandDisplayMode.IDLE -> IslandDisplayMode.IDLE
                    state.displayMode == IslandDisplayMode.EXPANDED ||
                        state.displayMode == IslandDisplayMode.FULL_EXPANDED -> state.displayMode
                    music.isPlaying -> IslandDisplayMode.COMPACT
                    else -> IslandDisplayMode.IDLE
                }
                state.copy(
                    activeEvent = if (music.isPlaying) music else state.activeEvent as? IslandEvent.MusicPlayback,
                    persistentMusic = updatedMusic,
                    displayMode = targetMode
                )
            } else {
                state.copy(persistentMusic = updatedMusic)
            }
        }
    }

    private fun showImmediate(event: IslandEvent, initialMode: IslandDisplayMode) {
        autoCollapseJob?.cancel()
        _uiState.update {
            it.copy(
                activeEvent = event,
                displayMode = initialMode,
                queuedCount = eventQueue.size
            )
        }
        if (!event.isSticky) {
            scheduleAutoCollapse(settingsProvider().displayDuration.millis)
        }
    }

    private fun scheduleAutoCollapse(durationMs: Long) {
        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(durationMs)
            advanceOrCollapse()
        }
    }

    private fun advanceOrCollapse() {
        autoCollapseJob?.cancel()
        if (eventQueue.isNotEmpty()) {
            val next = eventQueue.removeFirst()
            autoCollapseJob = scope.launch {
                _uiState.update { it.copy(queuedCount = eventQueue.size) }
                delay(120L)
                showImmediate(
                    next,
                    if (next is IslandEvent.IncomingCall) IslandDisplayMode.EXPANDED else IslandDisplayMode.COMPACT
                )
            }
        } else {
            val music = _uiState.value.persistentMusic
            if (music != null && music.isPlaying) {
                _uiState.update {
                    it.copy(
                        activeEvent = music,
                        displayMode = IslandDisplayMode.COMPACT,
                        queuedCount = 0
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        activeEvent = null,
                        displayMode = IslandDisplayMode.IDLE,
                        queuedCount = 0
                    )
                }
            }
        }
    }

    fun onIslandTap() {
        scope.launch {
            val state = _uiState.value
            val active = state.activeEvent ?: state.persistentMusic
            when (state.displayMode) {
                IslandDisplayMode.IDLE -> {
                    if (active != null) {
                        autoCollapseJob?.cancel()
                        _uiState.update {
                            it.copy(activeEvent = active, displayMode = IslandDisplayMode.EXPANDED)
                        }
                        if (!active.isSticky) {
                            scheduleAutoCollapse(settingsProvider().displayDuration.millis + 1500L)
                        }
                    }
                }
                IslandDisplayMode.COMPACT -> {
                    autoCollapseJob?.cancel()
                    _uiState.update { it.copy(displayMode = IslandDisplayMode.EXPANDED) }
                    if (active != null && !active.isSticky) {
                        scheduleAutoCollapse(settingsProvider().displayDuration.millis + 2000L)
                    }
                }
                IslandDisplayMode.EXPANDED,
                IslandDisplayMode.FULL_EXPANDED -> collapseInternal()
            }
        }
    }

    fun onIslandLongPress() {
        scope.launch {
            val state = _uiState.value
            val active = state.activeEvent ?: state.persistentMusic ?: return@launch
            autoCollapseJob?.cancel()
            _uiState.update {
                it.copy(
                    activeEvent = active,
                    displayMode = IslandDisplayMode.FULL_EXPANDED
                )
            }
        }
    }

    fun collapse() {
        scope.launch {
            collapseInternal()
        }
    }

    private fun collapseInternal() {
        autoCollapseJob?.cancel()
        val state = _uiState.value
        val music = state.persistentMusic
        if (state.activeEvent is IslandEvent.IncomingCall) {
            _uiState.update { it.copy(displayMode = IslandDisplayMode.COMPACT) }
            return
        }
        if (music != null && music.isPlaying) {
            _uiState.update {
                it.copy(activeEvent = music, displayMode = IslandDisplayMode.COMPACT)
            }
        } else {
            _uiState.update {
                it.copy(activeEvent = null, displayMode = IslandDisplayMode.IDLE)
            }
        }
    }

    fun setFullscreenState(isFullscreen: Boolean) {
        scope.launch {
            _uiState.update { it.copy(isFullscreenHidden = isFullscreen) }
        }
    }

    fun onSettingsChanged(settings: IslandSettings) {
        scope.launch {
            if (!settings.masterEnabled) {
                autoCollapseJob?.cancel()
                eventQueue.clear()
                _uiState.update {
                    it.copy(
                        activeEvent = null,
                        displayMode = IslandDisplayMode.IDLE,
                        queuedCount = 0
                    )
                }
            } else if (!settings.isEventEnabled(IslandEventType.MUSIC_PLAYER)) {
                _uiState.update { state ->
                    val active = if (state.activeEvent is IslandEvent.MusicPlayback) null else state.activeEvent
                    state.copy(
                        activeEvent = active,
                        persistentMusic = null,
                        displayMode = if (active == null) IslandDisplayMode.IDLE else state.displayMode
                    )
                }
            }
        }
    }
}
