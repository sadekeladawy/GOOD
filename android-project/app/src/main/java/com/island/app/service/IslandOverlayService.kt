package com.island.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.island.app.state.IslandUiState
import com.island.app.ui.overlay.AppExitTransitionOverlay
import com.island.app.ui.overlay.DynamicIslandOverlay
import com.island.app.ui.overlay.computeIslandLayoutTarget
import com.island.app.ui.theme.IslandTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    private var activeWindowManager: WindowManager? = null
    private var attachedWithAccessibility = false
    private var stateManager: IslandStateManager? = null
    private var systemMonitor: SystemEventMonitor? = null
    private var usageFallback: ForegroundAppUsageFallback? = null

    private var overlayContainer: FrameLayout? = null
    private var transitionContainer: FrameLayout? = null
    private var fullscreenProbeView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val settingsState = MutableStateFlow(IslandSettings.Default)
    private var windowShrinkJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Always satisfy Android 12+ foreground service startup contract before any early exit
        startForegroundNotification()

        if (!canDisplayOverlay(this)) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        val settingsRepo = (application as IslandApplication).settingsRepository
        val manager = IslandStateManager(
            scope = serviceScope,
            settingsProvider = { settingsState.value }
        )
        stateManager = manager

        val monitor = SystemEventMonitor(
            context = this,
            scope = serviceScope,
            stateManager = manager,
            settingsProvider = { settingsState.value }
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

        // Re-attach overlay cleanly when AccessibilityService connects or disconnects so we can
        // elevate from TYPE_APPLICATION_OVERLAY to TYPE_ACCESSIBILITY_OVERLAY (above status bar).
        serviceScope.launch {
            AppForegroundTracker.activeService.collectLatest { a11yService ->
                if (!canDisplayOverlay(this@IslandOverlayService)) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                val attached = attachOrReattachOverlay(manager, a11yService)
                if (!attached) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        runCatching { monitor.start() }
            .onFailure { CrashLogger.recordNonFatal(this, "SystemEventMonitor start error", it) }

        serviceScope.launch {
            combine(
                settingsRepo.settingsFlow,
                IslandEventBus.liveSettingsPreview
            ) { persisted, live ->
                live ?: persisted
            }.collectLatest { activeSettings ->
                settingsState.value = activeSettings
                manager.onSettingsChanged(activeSettings)
                syncOverlayWindowParams(
                    uiState = manager.uiState.value,
                    settings = activeSettings,
                    cutout = IslandEventBus.cutoutMetrics.value,
                    isLiveDragging = IslandEventBus.isLiveDraggingSettings.value
                )
                if (activeSettings.masterEnabled && activeSettings.appExitAnimationEnabled) {
                    fallback.start()
                } else {
                    fallback.stop()
                }
            }
        }

        serviceScope.launch {
            combine(
                manager.uiState,
                IslandEventBus.cutoutMetrics
            ) { uiState, cutout -> uiState to cutout }
                .collectLatest { (uiState, cutout) ->
                    syncOverlayWindowParams(
                        uiState = uiState,
                        settings = settingsState.value,
                        cutout = cutout,
                        isLiveDragging = IslandEventBus.isLiveDraggingSettings.value
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
                        showTransitionOverlay(manager, transition)
                    } else {
                        removeTransitionOverlay()
                    }
                }
        }

        // Collect live media playback StateFlow directly from MediaListenerService
        serviceScope.launch {
            var hadMedia = false
            MediaListenerService.mediaState.collect { playback ->
                if (playback != null) {
                    hadMedia = true
                    manager.postEvent(playback)
                } else if (hadMedia) {
                    hadMedia = false
                    manager.postEvent(IslandEvent.MusicSessionEnded())
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
        if (!canDisplayOverlay(this)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        windowShrinkJob?.cancel()
        runCatching { systemMonitor?.stop() }
        runCatching { usageFallback?.stop() }
        removeTransitionOverlay()
        detachOverlayViews()
        transitionLifecycleToDestroyed()
        viewModelStore.clear()
        serviceScope.cancel()
    }

    private fun attachOrReattachOverlay(
        manager: IslandStateManager,
        a11yService: IslandAccessibilityService?
    ): Boolean {
        val wantA11y = a11yService != null
        if (overlayContainer != null && attachedWithAccessibility == wantA11y) {
            return true
        }
        detachOverlayViews()

        if (a11yService != null) {
            val a11yWm = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (a11yWm != null) {
                val ok = runCatching {
                    attachOverlayViews(
                        hostContext = a11yService,
                        wm = a11yWm,
                        windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        manager = manager
                    )
                    attachedWithAccessibility = true
                }.onFailure {
                    CrashLogger.recordNonFatal(this, "TYPE_ACCESSIBILITY_OVERLAY failed; falling back", it)
                    detachOverlayViews()
                }.isSuccess
                if (ok) return true
            }
        }

        if (!Settings.canDrawOverlays(this)) return false
        val appWm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val appType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return runCatching {
            attachOverlayViews(
                hostContext = this,
                wm = appWm,
                windowType = appType,
                manager = manager
            )
            attachedWithAccessibility = false
        }.onFailure {
            CrashLogger.recordNonFatal(this, "Failed to attach Dynamic Island overlay", it)
            detachOverlayViews()
        }.isSuccess
    }

    private fun detachOverlayViews() {
        windowShrinkJob?.cancel()
        val wm = activeWindowManager
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
        overlayParams = null
        activeWindowManager = null
    }

    private fun showTransitionOverlay(
        manager: IslandStateManager,
        transition: AppTransitionEvent
    ) {
        removeTransitionOverlay()
        val a11yService = AppForegroundTracker.activeService.value
        val hostContext: Context
        val wm: WindowManager
        val type: Int
        if (a11yService != null) {
            val a11yWm = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (a11yWm != null) {
                hostContext = a11yService
                wm = a11yWm
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                hostContext = this
                wm = activeWindowManager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return)
                type = defaultAppOverlayType()
            }
        } else {
            hostContext = this
            wm = activeWindowManager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return)
            type = defaultAppOverlayType()
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
            applyCutoutMode(this)
        }

        val themedContext = ContextThemeWrapper(hostContext, R.style.Theme_Island)
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
        val wm = activeWindowManager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
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

    private fun attachOverlayViews(
        hostContext: Context,
        wm: WindowManager,
        windowType: Int,
        manager: IslandStateManager
    ) {
        activeWindowManager = wm

        // Full-width transparent probe view to read fullscreen status AND exact displayCutout rects
        val probe = View(hostContext).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                runCatching {
                    val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
                    val statusBarVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.isVisible(WindowInsets.Type.statusBars())
                    } else {
                        compat.isVisible(WindowInsetsCompat.Type.statusBars())
                    }
                    manager.setFullscreenState(!statusBarVisible)

                    val statusTopPx = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
                    publishCutoutMetrics(compat, statusTopPx)
                }
                insets
            }
        }
        val probeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            applyCutoutMode(this)
        }
        runCatching {
            wm.addView(probe, probeParams)
            fullscreenProbeView = probe
            ViewCompat.requestApplyInsets(probe)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val compat = WindowInsetsCompat.toWindowInsetsCompat(wm.maximumWindowMetrics.windowInsets)
                val statusTopPx = compat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
                publishCutoutMetrics(compat, statusTopPx)
            }
        }

        val initialSettings = settingsState.value
        val initialCutout = IslandEventBus.cutoutMetrics.value
        val (initW, initH) = computeWindowBoxPx(manager.uiState.value, initialSettings, initialCutout)

        val params = WindowManager.LayoutParams(
            initW,
            initH,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dpToPx(initialSettings.offsetX)
            y = dpToPx(initialSettings.offsetY)
            applyCutoutMode(this)
        }

        val themedContext = ContextThemeWrapper(hostContext, R.style.Theme_Island)
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
                    val cutout by IslandEventBus.cutoutMetrics.collectAsState()
                    val isLiveDragging by IslandEventBus.isLiveDraggingSettings.collectAsState()

                    IslandTheme(darkTheme = true, dynamicColor = false) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            DynamicIslandOverlay(
                                uiState = uiState,
                                settings = settings,
                                cutoutMetrics = cutout,
                                isLiveDragging = isLiveDragging,
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
                                onMediaCommand = { command ->
                                    if (command == MediaCommand.OpenApp) {
                                        manager.collapse()
                                    }
                                    IslandEventBus.sendMediaCommand(command)
                                },
                                onCallCommand = { IslandEventBus.sendCallCommand(it) }
                            )
                        }
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

        overlayParams = params
        overlayContainer = container
        wm.addView(container, params)
    }

    private fun publishCutoutMetrics(compat: WindowInsetsCompat, statusTopPx: Int) {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val cutout = compat.displayCutout
        val topRect: Rect? = cutout?.boundingRects
            ?.filter { !it.isEmpty && it.top <= statusTopPx + dpToPx(24f) }
            ?.minByOrNull { it.top }

        val metrics = if (topRect != null) {
            CutoutMetrics(
                hasCutout = true,
                cutoutLeftDp = topRect.left / density,
                cutoutRightDp = topRect.right / density,
                cutoutTopDp = topRect.top / density,
                cutoutBottomDp = topRect.bottom / density,
                statusBarHeightDp = statusTopPx / density,
                screenWidthDp = screenWidthDp
            )
        } else {
            CutoutMetrics(
                hasCutout = false,
                statusBarHeightDp = statusTopPx / density,
                screenWidthDp = screenWidthDp
            )
        }
        IslandEventBus.updateCutoutMetrics(metrics)
    }

    private fun computeWindowBoxPx(
        uiState: IslandUiState,
        settings: IslandSettings,
        cutout: CutoutMetrics
    ): Pair<Int, Int> {
        val effectiveMode = if (uiState.activeEvent == null) IslandDisplayMode.IDLE else uiState.displayMode
        val target = computeIslandLayoutTarget(
            activeEvent = uiState.activeEvent,
            mode = effectiveMode,
            settings = settings,
            cutout = cutout
        )
        val padW = if (effectiveMode == IslandDisplayMode.IDLE) 4f else 28f
        val padH = if (effectiveMode == IslandDisplayMode.IDLE) 4f else 20f
        return dpToPx(target.widthDp + padW) to dpToPx(target.heightDp + padH)
    }

    private fun syncOverlayWindowParams(
        uiState: IslandUiState,
        settings: IslandSettings,
        cutout: CutoutMetrics,
        isLiveDragging: Boolean
    ) {
        val view = overlayContainer ?: return
        val params = overlayParams ?: return
        val wm = activeWindowManager ?: return
        if (!view.isAttachedToWindow) return

        val isHidden = !settings.masterEnabled ||
            (settings.hideInFullscreen && uiState.isFullscreenHidden)
        val effectiveMode = if (uiState.activeEvent == null) IslandDisplayMode.IDLE else uiState.displayMode
        val isActiveCard = !isHidden && effectiveMode != IslandDisplayMode.IDLE

        var targetFlags = params.flags
        targetFlags = if (isHidden) {
            targetFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            targetFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        targetFlags = if (isActiveCard) {
            targetFlags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            targetFlags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }

        val targetX = dpToPx(settings.offsetX)
        val targetY = dpToPx(settings.offsetY)
        val (targetW, targetH) = computeWindowBoxPx(uiState, settings, cutout)

        var layoutChanged = false
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            layoutChanged = true
        }
        if (params.x != targetX || params.y != targetY) {
            params.x = targetX
            params.y = targetY
            layoutChanged = true
        }

        val needsGrow = targetW > params.width || targetH > params.height
        val needsShrink = targetW < params.width || targetH < params.height

        if (needsGrow || (isLiveDragging && (targetW != params.width || targetH != params.height))) {
            windowShrinkJob?.cancel()
            params.width = targetW
            params.height = targetH
            layoutChanged = true
        } else if (needsShrink) {
            // Keep the larger surface while spring shrinks the Compose pill inside (zero relayout jank!),
            // then tighten WindowManager bounds once settled so touches pass through outside the pill.
            windowShrinkJob?.cancel()
            windowShrinkJob = serviceScope.launch {
                val settleDelayMs = (420f / settings.animationSpeed.coerceIn(0.5f, 2.0f)).toLong().coerceIn(220L, 850L)
                delay(settleDelayMs)
                val v = overlayContainer ?: return@launch
                val p = overlayParams ?: return@launch
                val w = activeWindowManager ?: return@launch
                if (!v.isAttachedToWindow) return@launch
                val (latestW, latestH) = computeWindowBoxPx(
                    stateManager?.uiState?.value ?: uiState,
                    settingsState.value,
                    IslandEventBus.cutoutMetrics.value
                )
                if (p.width != latestW || p.height != latestH) {
                    p.width = latestW
                    p.height = latestH
                    runCatching { w.updateViewLayout(v, p) }
                }
            }
        }

        if (layoutChanged) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun applyCutoutMode(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun defaultAppOverlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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

        fun canDisplayOverlay(context: Context): Boolean =
            Settings.canDrawOverlays(context) || AppForegroundTracker.activeService.value != null

        fun start(context: Context) {
            if (!canDisplayOverlay(context)) return
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
