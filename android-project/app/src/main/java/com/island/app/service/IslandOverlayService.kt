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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.island.app.IslandApplication
import com.island.app.MainActivity
import com.island.app.R
import com.island.app.data.IslandSettings
import com.island.app.model.IslandDisplayMode
import com.island.app.state.IslandStateManager
import com.island.app.ui.overlay.DynamicIslandOverlay
import com.island.app.ui.theme.IslandTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class IslandOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var stateManager: IslandStateManager
    private lateinit var systemMonitor: SystemEventMonitor

    private var overlayView: View? = null
    private var fullscreenProbeView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentSettings: IslandSettings = IslandSettings.Default

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val settingsRepo = (application as IslandApplication).settingsRepository

        stateManager = IslandStateManager(
            scope = serviceScope,
            settingsProvider = { currentSettings }
        )
        systemMonitor = SystemEventMonitor(
            context = this,
            scope = serviceScope,
            stateManager = stateManager
        )

        startForegroundNotification()
        attachOverlayViews()
        systemMonitor.start()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        serviceScope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                stateManager.onSettingsChanged(settings)
                updateOverlayPosition(settings)
            }
        }

        serviceScope.launch {
            stateManager.uiState.collectLatest { uiState ->
                updateOutsideTouchFlag(
                    isExpanded = uiState.displayMode == IslandDisplayMode.EXPANDED ||
                        uiState.displayMode == IslandDisplayMode.FULL_EXPANDED
                )
            }
        }

        serviceScope.launch {
            IslandEventBus.events.collect { event ->
                stateManager.postEvent(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        if (::systemMonitor.isInitialized) {
            systemMonitor.stop()
        }
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        fullscreenProbeView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        fullscreenProbeView = null
        viewModelStore.clear()
        serviceScope.cancel()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun attachOverlayViews() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1px fullscreen detection probe that watches status bar visibility
        val probe = View(this).apply {
            setOnApplyWindowInsetsListener { _, insets ->
                val statusBarVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.isVisible(WindowInsets.Type.statusBars())
                } else {
                    WindowInsetsCompat.toWindowInsetsCompat(insets)
                        .isVisible(WindowInsetsCompat.Type.statusBars())
                }
                stateManager.setFullscreenState(!statusBarVisible)
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
        }
        runCatching {
            windowManager.addView(probe, probeParams)
            fullscreenProbeView = probe
            ViewCompat.requestApplyInsets(probe)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dpToPx(currentSettings.offsetX)
            y = dpToPx(currentSettings.offsetY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val container = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    stateManager.collapse()
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            setContent {
                val settings by (application as IslandApplication)
                    .settingsRepository
                    .settingsFlow
                    .collectAsStateWithLifecycle(initialValue = currentSettings)
                val uiState by stateManager.uiState.collectAsStateWithLifecycle()

                IslandTheme {
                    DynamicIslandOverlay(
                        uiState = uiState,
                        settings = settings,
                        onTap = {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            stateManager.onIslandTap()
                        },
                        onLongPress = {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            stateManager.onIslandLongPress()
                        },
                        onSwipeUp = {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            stateManager.collapse()
                        },
                        onMediaCommand = { IslandEventBus.sendMediaCommand(it) },
                        onCallCommand = { IslandEventBus.sendCallCommand(it) }
                    )
                }
            }
        }

        container.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        overlayParams = params
        overlayView = container
        windowManager.addView(container, params)
    }

    private fun updateOverlayPosition(settings: IslandSettings) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val newX = dpToPx(settings.offsetX)
        val newY = dpToPx(settings.offsetY)
        if (params.x != newX || params.y != newY) {
            params.x = newX
            params.y = newY
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun updateOutsideTouchFlag(isExpanded: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val targetFlags = if (isExpanded) {
            params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            runCatching { windowManager.updateViewLayout(view, params) }
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
            val intent = Intent(context, IslandOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandOverlayService::class.java))
        }
    }
}
