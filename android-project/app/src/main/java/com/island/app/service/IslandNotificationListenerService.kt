package com.island.app.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.island.app.model.IslandEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IslandNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var progressJob: Job? = null
    private var mediaCommandJob: Job? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            scope.launch {
                bindController(selectPrimaryController(controllers.orEmpty()))
            }
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            scope.launch { publishCurrentMedia() }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            scope.launch { publishCurrentMedia() }
        }

        override fun onSessionDestroyed() {
            scope.launch { bindController(null) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(this, IslandNotificationListenerService::class.java)
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val initial = mediaSessionManager?.getActiveSessions(component).orEmpty()
            bindController(selectPrimaryController(initial))
        }

        mediaCommandJob?.cancel()
        mediaCommandJob = scope.launch {
            IslandEventBus.mediaCommands.collect { command ->
                runCatching {
                    val controller = activeController ?: return@collect
                    val controls = controller.transportControls
                    when (command) {
                        MediaCommand.PlayPause -> {
                            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                            if (playing) controls.pause() else controls.play()
                        }
                        MediaCommand.Next -> controls.skipToNext()
                        MediaCommand.Previous -> controls.skipToPrevious()
                        is MediaCommand.SeekTo -> controls.seekTo(command.positionMs)
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaCommandJob?.cancel()
        progressJob?.cancel()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        bindController(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Active media sessions are tracked continuously via MediaSessionManager
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val component = ComponentName(this, IslandNotificationListenerService::class.java)
        runCatching {
            val sessions = mediaSessionManager?.getActiveSessions(component).orEmpty()
            scope.launch { bindController(selectPrimaryController(sessions)) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        mediaCommandJob?.cancel()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        scope.cancel()
    }

    private fun selectPrimaryController(controllers: List<MediaController>): MediaController? {
        return controllers.firstOrNull {
            runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
        } ?: controllers.firstOrNull()
    }

    private fun bindController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) {
            publishCurrentMedia()
            return
        }
        runCatching { activeController?.unregisterCallback(controllerCallback) }
        activeController = controller
        runCatching { activeController?.registerCallback(controllerCallback) }
        publishCurrentMedia()
    }

    private fun publishCurrentMedia() {
        val controller = activeController
        if (controller == null) {
            progressJob?.cancel()
            IslandEventBus.emitEvent(
                IslandEvent.MusicPlayback(
                    title = "",
                    artist = "",
                    albumArt = null,
                    isPlaying = false
                )
            )
            return
        }

        val metadata = runCatching { controller.metadata }.getOrNull()
        val state = runCatching { controller.playbackState }.getOrNull()
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        val art = safeDownscaleBitmap(
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        )
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(1L)
        val position = (state?.position ?: 0L).coerceIn(0L, duration)
        val pkg = runCatching { controller.packageName }.getOrDefault("")

        IslandEventBus.emitEvent(
            IslandEvent.MusicPlayback(
                title = title,
                artist = artist,
                albumArt = art,
                isPlaying = isPlaying,
                positionMs = position,
                durationMs = duration,
                packageName = pkg
            )
        )

        progressJob?.cancel()
        if (isPlaying) {
            progressJob = scope.launch {
                while (isActive) {
                    delay(1000L)
                    val current = activeController ?: break
                    val curState = runCatching { current.playbackState }.getOrNull()
                    if (curState?.state != PlaybackState.STATE_PLAYING) break
                    val curMeta = runCatching { current.metadata }.getOrNull()
                    val dur = (curMeta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: duration).coerceAtLeast(1L)
                    val pos = (curState.position).coerceIn(0L, dur)
                    IslandEventBus.emitEvent(
                        IslandEvent.MusicPlayback(
                            title = title,
                            artist = artist,
                            albumArt = art,
                            isPlaying = true,
                            positionMs = pos,
                            durationMs = dur,
                            packageName = pkg
                        )
                    )
                }
            }
        }
    }

    private fun safeDownscaleBitmap(source: Bitmap?): Bitmap? {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
            return null
        }
        return runCatching {
            val maxDim = 160
            val scale = minOf(1f, maxDim.toFloat() / maxOf(source.width, source.height))
            val targetW = (source.width * scale).toInt().coerceAtLeast(1)
            val targetH = (source.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        }.getOrNull()
    }
}
