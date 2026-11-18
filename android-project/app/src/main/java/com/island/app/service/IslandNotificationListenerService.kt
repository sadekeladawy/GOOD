package com.island.app.service

import android.content.ComponentName
import android.content.Context
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

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindController(selectPrimaryController(controllers.orEmpty()))
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishCurrentMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishCurrentMedia()
        }

        override fun onSessionDestroyed() {
            bindController(null)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, IslandNotificationListenerService::class.java)
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val initial = mediaSessionManager?.getActiveSessions(component).orEmpty()
            bindController(selectPrimaryController(initial))
        }

        scope.launch {
            IslandEventBus.mediaCommands.collect { command ->
                val controls = activeController?.transportControls ?: return@collect
                when (command) {
                    MediaCommand.PlayPause -> {
                        val playing = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
                        if (playing) controls.pause() else controls.play()
                    }
                    MediaCommand.Next -> controls.skipToNext()
                    MediaCommand.Previous -> controls.skipToPrevious()
                    is MediaCommand.SeekTo -> controls.seekTo(command.positionMs)
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        bindController(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Active media sessions are tracked continuously via MediaSessionManager
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Refresh active sessions when media notifications are dismissed
        val component = ComponentName(this, IslandNotificationListenerService::class.java)
        runCatching {
            val sessions = mediaSessionManager?.getActiveSessions(component).orEmpty()
            bindController(selectPrimaryController(sessions))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        activeController?.unregisterCallback(controllerCallback)
        scope.cancel()
    }

    private fun selectPrimaryController(controllers: List<MediaController>): MediaController? {
        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
    }

    private fun bindController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) {
            publishCurrentMedia()
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        activeController?.registerCallback(controllerCallback)
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

        val metadata = controller.metadata
        val state = controller.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(1L)
        val position = (state?.position ?: 0L).coerceIn(0L, duration)

        IslandEventBus.emitEvent(
            IslandEvent.MusicPlayback(
                title = title,
                artist = artist,
                albumArt = art,
                isPlaying = isPlaying,
                positionMs = position,
                durationMs = duration,
                packageName = controller.packageName
            )
        )

        progressJob?.cancel()
        if (isPlaying) {
            progressJob = scope.launch {
                while (isActive) {
                    delay(1000L)
                    val current = activeController ?: break
                    val curState = current.playbackState
                    if (curState?.state != PlaybackState.STATE_PLAYING) break
                    val curMeta = current.metadata
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
                            packageName = current.packageName
                        )
                    )
                }
            }
        }
    }
}
