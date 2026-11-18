package com.island.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.island.app.data.CrashLogger
import com.island.app.data.IslandSettings
import com.island.app.service.IslandEventBus
import com.island.app.service.IslandNotificationListenerService
import com.island.app.service.IslandOverlayService
import com.island.app.ui.screens.SettingsScreen
import com.island.app.ui.theme.IslandTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)
    private var hasNotificationAccess by mutableStateOf(false)
    private var lastCrashLog by mutableStateOf<String?>(null)
    private var permissionsRequestedOnce = false

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionsAndService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }

        refreshPermissionsAndService()
        lastCrashLog = CrashLogger.readLastCrash(this)

        if (savedInstanceState == null && !permissionsRequestedOnce) {
            permissionsRequestedOnce = true
            requestMissingRuntimePermissions()
        }

        val repo = (application as IslandApplication).settingsRepository

        setContent {
            val settings by repo.settingsFlow.collectAsStateWithLifecycle(
                initialValue = IslandSettings.Default
            )
            val scope = rememberCoroutineScope()

            LaunchedEffect(settings.masterEnabled, hasOverlayPermission) {
                if (settings.masterEnabled && hasOverlayPermission) {
                    IslandOverlayService.start(this@MainActivity)
                } else {
                    IslandOverlayService.stop(this@MainActivity)
                }
            }

            IslandTheme {
                SettingsScreen(
                    settings = settings,
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationAccess = hasNotificationAccess,
                    lastCrashLog = lastCrashLog,
                    onClearCrashLog = {
                        CrashLogger.clearLastCrash(this@MainActivity)
                        lastCrashLog = null
                    },
                    onRequestOverlayPermission = { openOverlaySettings() },
                    onRequestNotificationAccess = { openNotificationListenerSettings() },
                    onMasterToggle = { enabled ->
                        scope.launch { repo.setMasterEnabled(enabled) }
                    },
                    onAutoStartToggle = { enabled ->
                        scope.launch { repo.setAutoStartOnBoot(enabled) }
                    },
                    onHideFullscreenToggle = { enabled ->
                        scope.launch { repo.setHideInFullscreen(enabled) }
                    },
                    onDurationChange = { option ->
                        scope.launch { repo.setDisplayDuration(option) }
                    },
                    onPositionSizeChange = { x, y, w, h, r ->
                        scope.launch { repo.setPositionAndSize(x, y, w, h, r) }
                    },
                    onEventToggle = { type, enabled ->
                        scope.launch { repo.setEventEnabled(type, enabled) }
                    },
                    onResetToDefault = {
                        scope.launch { repo.resetToDefault() }
                    },
                    onTriggerTestEvent = { event ->
                        IslandEventBus.emitEvent(event)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsAndService()
        lastCrashLog = CrashLogger.readLastCrash(this)
    }

    private fun refreshPermissionsAndService() {
        hasOverlayPermission = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        hasNotificationAccess = runCatching { isNotificationListenerEnabled() }.getOrDefault(false)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, IslandNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it) == cn }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun requestMissingRuntimePermissions() {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }
        val missing = candidates.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runCatching { runtimePermissionsLauncher.launch(missing.toTypedArray()) }
        }
    }
}
