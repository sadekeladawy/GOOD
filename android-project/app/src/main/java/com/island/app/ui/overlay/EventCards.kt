package com.island.app.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.AirplanemodeInactive
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.WifiTetheringOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.island.app.model.IslandDisplayMode
import com.island.app.model.IslandEvent
import com.island.app.model.RingerMode
import com.island.app.service.CallCommand
import com.island.app.service.MediaCommand

private val IslandGreen = Color(0xFF30D158)
private val IslandOrange = Color(0xFFFF9F0A)
private val IslandRed = Color(0xFFFF453A)
private val IslandBlue = Color(0xFF0A84FF)
private val IslandTeal = Color(0xFF64D2FF)
private val IslandViolet = Color(0xFFBF5AF2)
private val MutedWhite = Color(0xFFA1A1AA)

@Composable
fun EventCardContent(
    event: IslandEvent,
    displayMode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan = CutoutLayoutPlan(),
    onMediaCommand: (MediaCommand) -> Unit,
    onCallCommand: (CallCommand) -> Unit
) {
    when (event) {
        is IslandEvent.ChargingStarted -> ChargingStartedCard(event, displayMode, cutoutPlan)
        is IslandEvent.ChargingStopped -> ChargingStoppedCard(event, displayMode, cutoutPlan)
        is IslandEvent.BatteryFull -> BatteryFullCard(event, displayMode, cutoutPlan)
        is IslandEvent.LowBattery -> LowBatteryCard(event, displayMode, cutoutPlan)
        is IslandEvent.WifiConnected -> WifiConnectedCard(event, displayMode, cutoutPlan)
        is IslandEvent.WifiDisconnected -> WifiDisconnectedCard(displayMode, cutoutPlan)
        is IslandEvent.HotspotChanged -> HotspotCard(event, displayMode, cutoutPlan)
        is IslandEvent.BluetoothChanged -> BluetoothCard(event, displayMode, cutoutPlan)
        is IslandEvent.AirplaneModeChanged -> AirplaneModeCard(event, displayMode, cutoutPlan)
        is IslandEvent.RingerModeChanged -> RingerModeCard(event, displayMode, cutoutPlan)
        is IslandEvent.IncomingCall -> IncomingCallCard(event, displayMode, cutoutPlan, onCallCommand)
        is IslandEvent.MusicPlayback -> MusicPlayerCard(event, displayMode, cutoutPlan, onMediaCommand)
        is IslandEvent.MusicSessionEnded -> Unit
    }
}

/**
 * Splits compact cards into a left wing and right wing around a physical center camera cutout
 * whenever the pill overlaps a display cutout, ensuring zero text or icons sit behind the lens.
 */
@Composable
private fun CompactSplitRow(
    cutoutPlan: CutoutLayoutPlan,
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit
) {
    if (cutoutPlan.centerGapDp > 0.dp) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(cutoutPlan.leftWingDp)
                    .fillMaxHeight()
                    .padding(start = 12.dp, end = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                leftContent()
            }
            Spacer(modifier = Modifier.width(cutoutPlan.centerGapDp))
            Box(
                modifier = Modifier
                    .width(cutoutPlan.rightWingDp)
                    .fillMaxHeight()
                    .padding(start = 4.dp, end = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                rightContent()
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = maxOf(14.dp, cutoutPlan.startClearanceDp + 10.dp),
                    end = maxOf(14.dp, cutoutPlan.endClearanceDp + 10.dp)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterStart
            ) {
                leftContent()
            }
            Spacer(Modifier.width(8.dp))
            rightContent()
        }
    }
}

@Composable
private fun PopIcon(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    key: Any,
    size: Dp = 20.dp
) {
    val popScale = remember(key) { Animatable(0.55f) }
    LaunchedEffect(key) {
        popScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.62f,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = popScale.value
                scaleY = popScale.value
            }
    )
}

@Composable
private fun BatteryRing(
    percent: Int,
    color: Color,
    animatePulse: Boolean,
    size: Dp = 24.dp
) {
    val targetSweep = (percent.coerceIn(0, 100) / 100f) * 360f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "batterySweep"
    )
    val pulseState: State<Float>? = if (animatePulse) {
        val infiniteTransition = rememberInfiniteTransition(label = "ringPulse")
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
    } else {
        null
    }

    Canvas(modifier = Modifier.size(size)) {
        val stroke = Stroke(width = size.toPx() * 0.14f, cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke
        )
        drawArc(
            color = color.copy(alpha = pulseState?.value ?: 1f),
            startAngle = -90f,
            sweepAngle = animatedSweep,
            useCenter = false,
            style = stroke
        )
    }
}

@Composable
private fun ChargingStartedCard(
    event: IslandEvent.ChargingStarted,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.Bolt, IslandGreen, "Charging", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Charging",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${event.batteryPercent}%", color = IslandGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(5.dp))
                    BatteryRing(event.batteryPercent, IslandGreen, animatePulse = true, size = 18.dp)
                }
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = {
                Box(contentAlignment = Alignment.Center) {
                    BatteryRing(event.batteryPercent, IslandGreen, animatePulse = true, size = 44.dp)
                    PopIcon(Icons.Rounded.Bolt, IslandGreen, "Charging", event.id, 22.dp)
                }
            },
            title = "Charging Connected",
            subtitle = if (mode == IslandDisplayMode.FULL_EXPANDED) "Fast power delivery active" else "Power adapter attached",
            trailing = {
                Text("${event.batteryPercent}%", color = IslandGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        )
    }
}

@Composable
private fun ChargingStoppedCard(
    event: IslandEvent.ChargingStopped,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.BatteryStd, MutedWhite, "Unplugged", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Unplugged",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Text("${event.batteryPercent}%", color = MutedWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(Icons.Rounded.BatteryStd, Color.White, "Battery", event.id, 30.dp) },
            title = "Charger Disconnected",
            subtitle = "Running on battery power",
            trailing = {
                Text("${event.batteryPercent}%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        )
    }
}

@Composable
private fun BatteryFullCard(
    event: IslandEvent.BatteryFull,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.CheckCircle, IslandGreen, "Battery Full", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Battery Full",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Text("100%", color = IslandGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(Icons.Rounded.BatteryChargingFull, IslandGreen, "Battery Full", event.id, 32.dp) },
            title = "Battery Full",
            subtitle = "100% charged · Safe to unplug",
            trailing = {
                PopIcon(Icons.Rounded.CheckCircle, IslandGreen, "Done", event.id, 24.dp)
            }
        )
    }
}

@Composable
private fun LowBatteryCard(
    event: IslandEvent.LowBattery,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    val accent = if (event.isCritical) IslandRed else IslandOrange
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.BatteryAlert, accent, "Low Battery", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (event.isCritical) "Critical" else "Low Battery",
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Text("${event.batteryPercent}%", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = {
                Box(contentAlignment = Alignment.Center) {
                    BatteryRing(event.batteryPercent, accent, animatePulse = true, size = 40.dp)
                    PopIcon(Icons.Rounded.BatteryAlert, accent, "Low Battery", event.id, 18.dp)
                }
            },
            title = if (event.isCritical) "Critical Battery (${event.batteryPercent}%)" else "Low Battery (${event.batteryPercent}%)",
            subtitle = if (event.isCritical) "Connect charger immediately" else "Consider enabling Battery Saver",
            trailing = {
                Text("${event.batteryPercent}%", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        )
    }
}

@Composable
private fun WifiConnectedCard(
    event: IslandEvent.WifiConnected,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.Wifi, IslandBlue, "Wi-Fi", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Wi-Fi", color = IslandBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            rightContent = {
                Text(
                    text = event.ssid,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(Icons.Rounded.Wifi, IslandBlue, "Wi-Fi Connected", event.id, 28.dp) },
            title = event.ssid,
            subtitle = "Wi-Fi connected",
            trailing = {
                Box(
                    modifier = Modifier
                        .background(IslandBlue.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Online", color = IslandBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun WifiDisconnectedCard(
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.WifiOff, IslandRed, "Wi-Fi Off", "wifi_off", 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Wi-Fi", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            },
            rightContent = {
                Text("Offline", color = IslandRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(Icons.Rounded.WifiOff, IslandRed, "Wi-Fi Disconnected", "wifi_off", 28.dp) },
            title = "Wi-Fi Disconnected",
            subtitle = "No wireless network connection",
            trailing = {}
        )
    }
}

@Composable
private fun HotspotCard(
    event: IslandEvent.HotspotChanged,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    val accent = if (event.enabled) IslandGreen else MutedWhite
    val icon = if (event.enabled) Icons.Rounded.WifiTethering else Icons.Rounded.WifiTetheringOff
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(icon, accent, "Hotspot", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Hotspot", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            },
            rightContent = {
                Text(if (event.enabled) "Active" else "Off", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(icon, accent, "Hotspot", event.id, 28.dp) },
            title = if (event.enabled) "Personal Hotspot On" else "Personal Hotspot Off",
            subtitle = if (event.enabled) "Sharing mobile connection" else "Tethering disabled",
            trailing = {}
        )
    }
}

@Composable
private fun BluetoothCard(
    event: IslandEvent.BluetoothChanged,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    val icon = when {
        !event.connected -> Icons.Rounded.BluetoothDisabled
        event.isAudioDevice -> Icons.Rounded.Headphones
        else -> Icons.Rounded.BluetoothConnected
    }
    val accent = if (event.connected) IslandTeal else MutedWhite

    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(icon, accent, "Bluetooth", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = event.deviceName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Text(
                    text = if (event.connected) "Connected" else "Off",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(icon, accent, "Bluetooth", event.id, 28.dp) },
            title = event.deviceName,
            subtitle = if (event.connected) {
                if (event.isAudioDevice) "Audio device connected" else "Bluetooth connected"
            } else {
                "Device disconnected"
            },
            trailing = {
                event.batteryLevel?.let { lvl ->
                    Text("$lvl%", color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun AirplaneModeCard(
    event: IslandEvent.AirplaneModeChanged,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    val accent = if (event.enabled) IslandOrange else IslandGreen
    val icon = if (event.enabled) Icons.Rounded.AirplanemodeActive else Icons.Rounded.AirplanemodeInactive
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(icon, accent, "Airplane Mode", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Airplane", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            },
            rightContent = {
                Text(if (event.enabled) "On" else "Off", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(icon, accent, "Airplane Mode", event.id, 28.dp) },
            title = if (event.enabled) "Airplane Mode On" else "Airplane Mode Off",
            subtitle = if (event.enabled) "Wireless radios suspended" else "Cellular & Wi-Fi restored",
            trailing = {}
        )
    }
}

@Composable
private fun RingerModeCard(
    event: IslandEvent.RingerModeChanged,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan
) {
    val (label, icon, accent) = when (event.mode) {
        RingerMode.SILENT -> Triple("Silent", Icons.Rounded.NotificationsOff, IslandRed)
        RingerMode.VIBRATE -> Triple("Vibrate", Icons.Rounded.Vibration, IslandOrange)
        RingerMode.RING -> Triple("Ring", Icons.Rounded.NotificationsActive, IslandGreen)
    }
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(icon, accent, label, event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            },
            rightContent = {
                Text("Mode", color = MutedWhite, fontSize = 11.sp)
            }
        )
    } else {
        StandardExpandedCard(
            cutoutPlan = cutoutPlan,
            icon = { PopIcon(icon, accent, label, event.id, 28.dp) },
            title = "$label Mode",
            subtitle = when (event.mode) {
                RingerMode.SILENT -> "Calls and alerts muted"
                RingerMode.VIBRATE -> "Haptic alerts active"
                RingerMode.RING -> "Ringtone volume active"
            },
            trailing = {}
        )
    }
}

@Composable
private fun IncomingCallCard(
    event: IslandEvent.IncomingCall,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan,
    onCallCommand: (CallCommand) -> Unit
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PopIcon(Icons.Rounded.Call, IslandGreen, "Incoming Call", event.id, 18.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = event.callerName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                Text("Incoming", color = IslandGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        )
    } else {
        val topPad = maxOf(14.dp, cutoutPlan.topClearanceDp)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, top = topPad, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Incoming Call",
                    color = IslandGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.callerName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (mode == IslandDisplayMode.FULL_EXPANDED) {
                    Text(
                        text = event.phoneNumber,
                        color = MutedWhite,
                        fontSize = 13.sp
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures { /* keep stray taps from collapsing call controls */ }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(IslandRed)
                        .clickable(role = Role.Button) { onCallCommand(CallCommand.Decline) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(IslandGreen)
                        .clickable(role = Role.Button) { onCallCommand(CallCommand.Accept) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Call, "Accept", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun MusicPlayerCard(
    event: IslandEvent.MusicPlayback,
    mode: IslandDisplayMode,
    cutoutPlan: CutoutLayoutPlan,
    onMediaCommand: (MediaCommand) -> Unit
) {
    if (mode == IslandDisplayMode.COMPACT) {
        CompactSplitRow(
            cutoutPlan = cutoutPlan,
            leftContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumArtBox(event = event, size = 24.dp, corner = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = event.title.ifBlank { "Now Playing" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            rightContent = {
                AnimatedEqualizer(isPlaying = event.isPlaying, color = IslandViolet)
            }
        )
    } else {
        val topPad = maxOf(14.dp, cutoutPlan.topClearanceDp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, top = topPad, bottom = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onMediaCommand(MediaCommand.OpenApp) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtBox(
                    event = event,
                    size = if (mode == IslandDisplayMode.FULL_EXPANDED) 54.dp else 44.dp,
                    corner = 12.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.title.ifBlank { "Unknown Track" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = event.artist.ifBlank { "Unknown Artist" },
                        color = MutedWhite,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                AnimatedEqualizer(isPlaying = event.isPlaying, color = IslandViolet)
            }

            MusicProgressSection(
                event = event,
                onMediaCommand = onMediaCommand
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { /* absorb stray taps between buttons so controls never collapse */ }
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onMediaCommand(MediaCommand.Previous) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0x2BFFFFFF))
                        .clickable(role = Role.Button) { onMediaCommand(MediaCommand.PlayPause) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (event.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (event.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onMediaCommand(MediaCommand.Next) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/**
 * Draw-phase GPU progress bar with zero per-frame recompositions while playing.
 * Time label only recomposes when whole seconds change.
 */
@Composable
private fun MusicProgressSection(
    event: IslandEvent.MusicPlayback,
    onMediaCommand: (MediaCommand) -> Unit
) {
    val safeDuration = event.durationMs.coerceAtLeast(1L)
    val basePositionMs = event.positionMs.coerceIn(0L, safeDuration)
    val baseFraction = (basePositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    var isScrubbing by remember(event.packageName, event.title) { mutableStateOf(false) }
    var scrubFraction by remember(event.packageName, event.title) { mutableFloatStateOf(baseFraction) }

    // Mutated in frame loop, read ONLY inside Canvas onDraw (zero recompositions)
    val liveFractionState = remember(
        event.packageName,
        event.title,
        event.positionMs,
        event.lastPositionUpdateTimeMs,
        event.isPlaying,
        event.playbackSpeed
    ) {
        mutableFloatStateOf(baseFraction)
    }
    var displayedSeconds by remember(
        event.packageName,
        event.title,
        event.positionMs,
        event.lastPositionUpdateTimeMs,
        event.isPlaying,
        event.playbackSpeed
    ) {
        mutableLongStateOf(basePositionMs / 1000L)
    }

    LaunchedEffect(
        event.packageName,
        event.title,
        event.positionMs,
        event.lastPositionUpdateTimeMs,
        event.isPlaying,
        event.playbackSpeed,
        isScrubbing
    ) {
        if (!event.isPlaying || isScrubbing) {
            liveFractionState.floatValue = baseFraction
            displayedSeconds = basePositionMs / 1000L
            return@LaunchedEffect
        }
        val anchorElapsedMs = if (event.lastPositionUpdateTimeMs > 0L) {
            event.lastPositionUpdateTimeMs
        } else {
            SystemClock.elapsedRealtime()
        }
        val speed = if (event.playbackSpeed > 0f && event.playbackSpeed.isFinite()) {
            event.playbackSpeed
        } else {
            1f
        }
        while (true) {
            withFrameMillis {
                val elapsedMs = (SystemClock.elapsedRealtime() - anchorElapsedMs).coerceAtLeast(0L)
                val posMs = (event.positionMs + (elapsedMs * speed).toLong()).coerceIn(0L, safeDuration)
                liveFractionState.floatValue = (posMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                val sec = posMs / 1000L
                if (sec != displayedSeconds) {
                    displayedSeconds = sec
                }
            }
            if (liveFractionState.floatValue >= 1f) {
                break
            }
        }
    }

    val barThickness by animateDpAsState(
        targetValue = if (isScrubbing) 8.dp else 5.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
        label = "seekBarHeight"
    )
    val displayedPositionMs = if (isScrubbing) {
        (scrubFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
    } else {
        displayedSeconds * 1000L
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(safeDuration) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val frac = (offset.x / w).coerceIn(0f, 1f)
                        val targetMs = (frac * safeDuration).toLong().coerceIn(0L, safeDuration)
                        liveFractionState.floatValue = frac
                        displayedSeconds = targetMs / 1000L
                        onMediaCommand(MediaCommand.SeekTo(targetMs))
                    }
                }
                .pointerInput(safeDuration) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            isScrubbing = true
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            scrubFraction = (startOffset.x / w).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            scrubFraction = (change.position.x / w).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val targetMs = (scrubFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
                            isScrubbing = false
                            liveFractionState.floatValue = scrubFraction
                            displayedSeconds = targetMs / 1000L
                            onMediaCommand(MediaCommand.SeekTo(targetMs))
                        },
                        onDragCancel = {
                            isScrubbing = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barThickness)
            ) {
                val trackH = size.height
                val radius = CornerRadius(trackH / 2f, trackH / 2f)
                drawRoundRect(
                    color = Color(0x33FFFFFF),
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = radius
                )
                val fraction = if (isScrubbing) scrubFraction else liveFractionState.floatValue
                val fillW = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) trackH else 0f)
                if (fillW > 0f) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset.Zero,
                        size = Size(fillW, trackH),
                        cornerRadius = radius
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMediaTime(displayedPositionMs),
                color = MutedWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatMediaTime(safeDuration),
                color = MutedWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatMediaTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun AlbumArtBox(event: IslandEvent.MusicPlayback, size: Dp, corner: Dp) {
    val art = event.albumArt
    val validBitmap = remember(art) {
        if (art != null && !art.isRecycled) runCatching { art.asImageBitmap() }.getOrNull() else null
    }
    if (validBitmap != null) {
        Image(
            bitmap = validBitmap,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Color(0xFF27272A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MusicNote, "Music", tint = IslandViolet, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
private fun AnimatedEqualizer(isPlaying: Boolean, color: Color) {
    if (!isPlaying) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(16.dp)
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(14.dp)
                        .graphicsLayer { scaleY = 0.25f }
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(color)
                )
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "eq")
    val barStates: List<State<Float>> = listOf(
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
            label = "eq1"
        ),
        transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse),
            label = "eq2"
        ),
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse),
            label = "eq3"
        ),
        transition.animateFloat(
            initialValue = 0.70f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse),
            label = "eq4"
        )
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)
    ) {
        barStates.forEach { levelState ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer { scaleY = levelState.value }
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun StandardExpandedCard(
    cutoutPlan: CutoutLayoutPlan,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    val topPad = maxOf(14.dp, cutoutPlan.topClearanceDp)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 18.dp, end = 18.dp, top = topPad, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MutedWhite,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
