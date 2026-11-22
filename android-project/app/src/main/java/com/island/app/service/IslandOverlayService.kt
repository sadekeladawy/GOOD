package com.island.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.island.app.IslandApplication
import com.island.app.MainActivity
import com.island.app.R
import com.island.app.data.CrashLogger
import com.island.app.data.IslandSettings
import com.island.app.model.AppTransitionEvent
import com.island.app.model.IslandDisplayMode
import com.island.app.model.IslandEvent
import com.island.app.state.IslandStateManager
import com.island.app.ui.overlay.AppExitTransitionOverlay
import com.island.app.ui.overlay.DynamicIslandOverlay
import com.island.app.ui.theme.IslandTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class IslandOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var stateManager: IslandStateManager? = null
    private var systemMonitor: SystemEventMonitor? = null
    private var usageFallback: ForegroundAppUsageFallback? = null

    private var overlayContainer: FrameLayout? = null
    private var transitionContainer: FrameLayout? = null
    private var fullscreenProbeView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val settingsState = MutableStateFlow(IslandSettings.Default)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Always satisfy Android 12+ foreground service startup contract before any early exit
        startForegroundNotification()

        if (!Settings.canDrawOverlays(this)) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        windowManager = wm

        val settingsRepo = (application as IslandApplication).settingsRepository
        val manager = IslandStateManager(
            scope = serviceScope,
            settingsProvider = { settingsState.value }
        )
        stateManager = manager

        val monitor = SystemEventMonitor(
            context = this,
            scope = serviceScope,
            stateManager = manager
        )
        systemMonitor = monitor

        val fallback = ForegroundAppUsageFallback(
            context = this,
            scope = serviceScope
        )
        usageFallback = fallback

        // Reach RESUMED before attaching ComposeView so WindowRecomposer starts cleanly
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val attached = runCatching { attachOverlayViews(wm, manager) }
            .onFailure { CrashLogger.recordNonFatal(this, "Failed to attach Dynamic Island overlay", it) }
            .isSuccess

        if (!attached) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        runCatching { monitor.start() }
            .onFailure { CrashLogger.recordNonFatal(this, "SystemEventMonitor start error", it) }

        serviceScope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                settingsState.value = settings
                manager.onSettingsChanged(settings)
                updateOverlayPosition(settings)
                if (settings.masterEnabled && settings.appExitAnimationEnabled) {
                    fallback.start()
                } else {
                    fallback.stop()
                }
            }
        }

        serviceScope.launch {
            manager.uiState.collectLatest { uiState ->
                updateOutsideTouchFlag(
                    isExpanded = uiState.displayMode == IslandDisplayMode.EXPANDED ||
                        uiState.displayMode == IslandDisplayMode.FULL_EXPANDED
                )
            }
        }

        // Dynamically attach/detach the full-screen spring morph window only while an app transition runs
        serviceScope.launch {
            manager.uiState
                .map { it.activeTransition }
                .distinctUntilChanged()
                .collectLatest { transition ->
                    if (transition != null) {
                        showTransitionOverlay(wm, manager, transition)
                    } else {
                        removeTransitionOverlay()
                    }
                }
        }

        // Collect live media playback StateFlow directly from MediaListenerService
        serviceScope.launch {
            MediaListenerService.mediaState.collect { playback ->
                if (playback != null) {
                    manager.postEvent(playback)
                } else {
                    manager.postEvent(
                        IslandEvent.MusicPlayback(
                            title = "",
                            artist = "",
                            albumArt = null,
                            isPlaying = false
                        )
                    )
                }
            }
        }

        // Collect foreground->background / background->foreground media app transitions
        serviceScope.launch {
            AppForegroundTracker.transitions.collect { transition ->
                manager.startAppTransition(transition)
            }
        }

        serviceScope.launch {
            IslandEventBus.events.collect { event ->
                manager.postEvent(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (!Settings.canDrawOverlays(this)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { systemMonitor?.stop() }
        runCatching { usageFallback?.stop() }
        removeTransitionOverlay()
        val wm = windowManager
        overlayContainer?.let { container ->
            if (container.isAttachedToWindow) {
                runCatching { wm?.removeViewImmediate(container) }
            }
        }
        fullscreenProbeView?.let { probe ->
            if (probe.isAttachedToWindow) {
                runCatching { wm?.removeViewImmediate(probe) }
            }
        }
        overlayContainer = null
        fullscreenProbeView = null

        transitionLifecycleToDestroyed()
        viewModelStore.clear()
        serviceScope.cancel()
    }

    private fun showTransitionOverlay(
        wm: WindowManager,
        manager: IslandStateManager,
        transition: AppTransitionEvent
    ) {
        removeTransitionOverlay()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Island)
        val container = FrameLayout(themedContext).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
        }

        val composeView = ComposeView(themedContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides this@IslandOverlayService) {
                    val currentSettings by settingsState.collectAsState()
                    IslandTheme(darkTheme = true, dynamicColor = false) {
                        AppExitTransitionOverlay(
                            transition = transition,
                            settings = currentSettings,
                            onReachedIsland = {
                                runCatching {
                                    overlayContainer?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                manager.onExitAnimationReachedIsland()
                            },
                            onFinished = {
                                manager.onAppTransitionFinished(transition.id)
                            }
                        )
                    }
                }
            }
        }

        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        runCatching {
            wm.addView(container, params)
            transitionContainer = container
        }.onFailure {
            CrashLogger.recordNonFatal(this, "Failed to attach AppExitTransitionOverlay", it)
            manager.onAppTransitionFinished(transition.id)
        }
    }

    private fun removeTransitionOverlay() {
        val wm = windowManager ?: return
        transitionContainer?.let { container ->
            if (container.isAttachedToWindow) {
                runCatching { wm.removeViewImmediate(container) }
            }
        }
        transitionContainer = null
    }

    private fun transitionLifecycleToDestroyed() {
        runCatching {
            when (lifecycleRegistry.currentState) {
                Lifecycle.State.RESUMED -> {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
                Lifecycle.State.STARTED -> {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
                Lifecycle.State.CREATED -> {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
                else -> Unit
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun startForegroundNotification() {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(
            this,
            IslandApplication.CHANNEL_OVERLAY_SERVICE
        )
            .setSmallIcon(R.drawable.ic_island_notification)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            CrashLogger.recordNonFatal(this, "startForeground failed", it)
        }
    }

    private fun attachOverlayViews(wm: WindowManager, manager: IslandStateManager) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Transparent 1px fullscreen detection probe that watches status bar visibility
        val probe = View(this).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                runCatching {
                    val statusBarVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.isVisible(WindowInsets.Type.statusBars())
                    } else {
                        WindowInsetsCompat.toWindowInsetsCompat(insets)
                            .isVisible(WindowInsetsCompat.Type.statusBars())
                    }
                    manager.setFullscreenState(!statusBarVisible)
                }
                insets
            }
        }
        val probeParams = WindowManager.LayoutParams(
            1,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }
        runCatching {
            wm.addView(probe, probeParams)
            fullscreenProbeView = probe
            ViewCompat.requestApplyInsets(probe)
        }

        val initialSettings = settingsState.value
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dpToPx(initialSettings.offsetX)
            y = dpToPx(initialSettings.offsetY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Wrap ComposeView inside a themed FrameLayout container (ComposeView itself is final)
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Island)
        val container = object : FrameLayout(themedContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    manager.collapse()
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
        }

        val composeView = ComposeView(themedContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides this@IslandOverlayService) {
                    val settings by settingsState.collectAsState()
                    val uiState by manager.uiState.collectAsState()

                    IslandTheme(darkTheme = true, dynamicColor = false) {
                        DynamicIslandOverlay(
                            uiState = uiState,
                            settings = settings,
                            onTap = {
                                runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                                manager.onIslandTap()
                            },
                            onLongPress = {
                                runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                                manager.onIslandLongPress()
                            },
                            onSwipeUp = {
                                runCatching { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                                manager.collapse()
                            },
                            onMediaCommand = { IslandEventBus.sendMediaCommand(it) },
                            onCallCommand = { IslandEventBus.sendCallCommand(it) }
                        )
                    }
                }
            }
        }

        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        overlayParams = params
        overlayContainer = container
        wm.addView(container, params)
    }

    private fun updateOverlayPosition(settings: IslandSettings) {
        val view = overlayContainer ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        if (!view.isAttachedToWindow) return

        val newX = dpToPx(settings.offsetX)
        val newY = dpToPx(settings.offsetY)
        if (params.x != newX || params.y != newY) {
            params.x = newX
            params.y = newY
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun updateOutsideTouchFlag(isExpanded: Boolean) {
        val view = overlayContainer ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        if (!view.isAttachedToWindow) return

        val targetFlags = if (isExpanded) {
            params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, IslandOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                CrashLogger.recordNonFatal(context, "Unable to start IslandOverlayService", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, IslandOverlayService::class.java))
            }
        }
    }
}
