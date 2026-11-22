package com.island.app.service

import androidx.compose.runtime.Immutable
import com.island.app.data.IslandSettings
import com.island.app.model.IslandEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MediaCommand {
    data object PlayPause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val positionMs: Long) : MediaCommand
    data object OpenApp : MediaCommand
}

sealed interface CallCommand {
    data object Accept : CallCommand
    data object Decline : CallCommand
}

@Immutable
data class CutoutMetrics(
    val hasCutout: Boolean = false,
    val cutoutLeftDp: Float = 0f,
    val cutoutRightDp: Float = 0f,
    val cutoutTopDp: Float = 0f,
    val cutoutBottomDp: Float = 0f,
    val statusBarHeightDp: Float = 0f,
    val screenWidthDp: Float = 0f
)

object IslandEventBus {
    private val _events = MutableSharedFlow<IslandEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<IslandEvent> = _events.asSharedFlow()

    private val _mediaCommands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 8)
    val mediaCommands: SharedFlow<MediaCommand> = _mediaCommands.asSharedFlow()

    private val _callCommands = MutableSharedFlow<CallCommand>(extraBufferCapacity = 4)
    val callCommands: SharedFlow<CallCommand> = _callCommands.asSharedFlow()

    private val _liveSettingsPreview = MutableStateFlow<IslandSettings?>(null)
    val liveSettingsPreview: StateFlow<IslandSettings?> = _liveSettingsPreview.asStateFlow()

    private val _isLiveDraggingSettings = MutableStateFlow(false)
    val isLiveDraggingSettings: StateFlow<Boolean> = _isLiveDraggingSettings.asStateFlow()

    private val _cutoutMetrics = MutableStateFlow(CutoutMetrics())
    val cutoutMetrics: StateFlow<CutoutMetrics> = _cutoutMetrics.asStateFlow()

    fun emitEvent(event: IslandEvent) {
        _events.tryEmit(event)
    }

    fun sendMediaCommand(command: MediaCommand) {
        _mediaCommands.tryEmit(command)
    }

    fun sendCallCommand(command: CallCommand) {
        _callCommands.tryEmit(command)
    }

    fun updateLiveSettingsPreview(settings: IslandSettings?) {
        _liveSettingsPreview.value = settings
    }

    fun setLiveDraggingSettings(dragging: Boolean) {
        _isLiveDraggingSettings.value = dragging
    }

    fun updateCutoutMetrics(metrics: CutoutMetrics) {
        if (_cutoutMetrics.value != metrics) {
            _cutoutMetrics.value = metrics
        }
    }
}
