package com.island.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.island.app.data.DisplayDurationOption
import com.island.app.data.IslandEventType
import com.island.app.data.IslandSettings
import com.island.app.model.IslandDisplayMode
import com.island.app.model.IslandEvent
import com.island.app.state.IslandUiState
import com.island.app.ui.overlay.DynamicIslandOverlay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: IslandSettings,
    hasOverlayPermission: Boolean,
    hasNotificationAccess: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onMasterToggle: (Boolean) -> Unit,
    onAutoStartToggle: (Boolean) -> Unit,
    onHideFullscreenToggle: (Boolean) -> Unit,
    onDurationChange: (DisplayDurationOption) -> Unit,
    onPositionSizeChange: (Float?, Float?, Float?, Float?, Float?) -> Unit,
    onEventToggle: (IslandEventType, Boolean) -> Unit,
    onResetToDefault: () -> Unit,
    onTriggerTestEvent: (IslandEvent) -> Unit
) {
    var previewEvent by remember { mutableStateOf<IslandEvent?>(null) }
    var previewMode by remember { mutableStateOf(IslandDisplayMode.IDLE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Island",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (settings.masterEnabled) "Active" else "Off",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = settings.masterEnabled,
                            onCheckedChange = onMasterToggle
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Live Preview anchored at the top so slider adjustments reflect immediately
            LiveIslandPreviewCard(
                settings = settings,
                previewEvent = previewEvent,
                previewMode = previewMode,
                onTapPreview = {
                    if (previewEvent == null) {
                        previewEvent = IslandEvent.ChargingStarted(84)
                        previewMode = IslandDisplayMode.COMPACT
                    } else if (previewMode == IslandDisplayMode.COMPACT) {
                        previewMode = IslandDisplayMode.EXPANDED
                    } else {
                        previewEvent = null
                        previewMode = IslandDisplayMode.IDLE
                    }
                },
                onLongPressPreview = {
                    if (previewEvent == null) {
                        previewEvent = IslandEvent.MusicPlayback(
                            title = "Midnight Refraction",
                            artist = "Kiasmos",
                            isPlaying = true,
                            positionMs = 74_000L,
                            durationMs = 212_000L
                        )
                    }
                    previewMode = IslandDisplayMode.FULL_EXPANDED
                },
                onCollapsePreview = {
                    previewEvent = null
                    previewMode = IslandDisplayMode.IDLE
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!hasOverlayPermission || !hasNotificationAccess) {
                    item {
                        PermissionsSection(
                            hasOverlayPermission = hasOverlayPermission,
                            hasNotificationAccess = hasNotificationAccess,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestNotificationAccess = onRequestNotificationAccess
                        )
                    }
                }

                item {
                    PositionAndSizeCard(
                        settings = settings,
                        onPositionSizeChange = onPositionSizeChange,
                        onResetToDefault = onResetToDefault
                    )
                }

                item {
                    GeneralBehaviorCard(
                        settings = settings,
                        onAutoStartToggle = onAutoStartToggle,
                        onHideFullscreenToggle = onHideFullscreenToggle,
                        onDurationChange = onDurationChange
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Event Cards",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(
                            onClick = {
                                onTriggerTestEvent(IslandEvent.ChargingStarted(86))
                            }
                        ) {
                            Text("Test Overlay")
                        }
                    }
                }

                items(IslandEventType.entries, key = { it.key }) { eventType ->
                    EventToggleRow(
                        eventType = eventType,
                        enabled = settings.isEventEnabled(eventType),
                        onToggle = { onEventToggle(eventType, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveIslandPreviewCard(
    settings: IslandSettings,
    previewEvent: IslandEvent?,
    previewMode: IslandDisplayMode,
    onTapPreview: () -> Unit,
    onLongPressPreview: () -> Unit,
    onCollapsePreview: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0E1117),
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
        ) {
            Text(
                text = "Live Preview (tap or long-press)",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(
                        x = (settings.offsetX * 0.5f).dp,
                        y = (settings.offsetY * 0.6f).dp
                    )
            ) {
                DynamicIslandOverlay(
                    uiState = IslandUiState(
                        activeEvent = previewEvent,
                        displayMode = previewMode
                    ),
                    settings = settings,
                    onTap = onTapPreview,
                    onLongPress = onLongPressPreview,
                    onSwipeUp = onCollapsePreview,
                    onMediaCommand = {},
                    onCallCommand = {}
                )
            }
        }
    }
}

@Composable
private fun PermissionsSection(
    hasOverlayPermission: Boolean,
    hasNotificationAccess: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationAccess: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Required Permissions", style = MaterialTheme.typography.titleMedium)

            if (!hasOverlayPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Display over other apps", fontWeight = FontWeight.Medium)
                        Text(
                            "Required to float the island above apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onRequestOverlayPermission) {
                        Text("Grant")
                    }
                }
            }

            if (!hasNotificationAccess) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Notification & Media access", fontWeight = FontWeight.Medium)
                        Text(
                            "Required for music player metadata & controls",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onRequestNotificationAccess) {
                        Text("Enable")
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionAndSizeCard(
    settings: IslandSettings,
    onPositionSizeChange: (Float?, Float?, Float?, Float?, Float?) -> Unit,
    onResetToDefault: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Position & Size", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = onResetToDefault,
                    colors = ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("Reset to default")
                }
            }

            SettingSliderRow(
                label = "Vertical position (Y)",
                valueText = "${settings.offsetY.roundToInt()} dp",
                value = settings.offsetY,
                range = 0f..96f,
                onValueChange = { onPositionSizeChange(null, it, null, null, null) }
            )

            SettingSliderRow(
                label = "Horizontal position (X)",
                valueText = "${settings.offsetX.roundToInt()} dp",
                value = settings.offsetX,
                range = -120f..120f,
                onValueChange = { onPositionSizeChange(it, null, null, null, null) }
            )

            SettingSliderRow(
                label = "Idle width",
                valueText = "${settings.idleWidthDp.roundToInt()} dp",
                value = settings.idleWidthDp,
                range = 84f..220f,
                onValueChange = { onPositionSizeChange(null, null, it, null, null) }
            )

            SettingSliderRow(
                label = "Idle height",
                valueText = "${settings.idleHeightDp.roundToInt()} dp",
                value = settings.idleHeightDp,
                range = 24f..54f,
                onValueChange = { onPositionSizeChange(null, null, null, it, null) }
            )

            SettingSliderRow(
                label = "Corner radius",
                valueText = "${settings.cornerRadiusDp.roundToInt()} dp",
                value = settings.cornerRadiusDp,
                range = 12f..36f,
                onValueChange = { onPositionSizeChange(null, null, null, null, it) }
            )
        }
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@Composable
private fun GeneralBehaviorCard(
    settings: IslandSettings,
    onAutoStartToggle: (Boolean) -> Unit,
    onHideFullscreenToggle: (Boolean) -> Unit,
    onDurationChange: (DisplayDurationOption) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Behavior", style = MaterialTheme.typography.titleMedium)

            Text("Card visible duration", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DisplayDurationOption.entries.forEachIndexed { idx, option ->
                    SegmentedButton(
                        selected = settings.displayDuration == option,
                        onClick = { onDurationChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx,
                            count = DisplayDurationOption.entries.size
                        )
                    ) {
                        Text(option.label)
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-start on boot", fontWeight = FontWeight.Medium)
                    Text(
                        "Restore floating island after restart",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoStartOnBoot,
                    onCheckedChange = onAutoStartToggle
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hide in fullscreen", fontWeight = FontWeight.Medium)
                    Text(
                        "Fade out during videos and games",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.hideInFullscreen,
                    onCheckedChange = onHideFullscreenToggle
                )
            }
        }
    }
}

@Composable
private fun EventToggleRow(
    eventType: IslandEventType,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(eventType.displayName, fontWeight = FontWeight.Medium)
                Text(
                    text = eventType.description,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
