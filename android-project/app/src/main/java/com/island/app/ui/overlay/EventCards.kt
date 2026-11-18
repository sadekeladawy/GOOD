package com.island.app.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
    onMediaCommand: (MediaCommand) -> Unit,
    onCallCommand: (CallCommand) -> Unit
) {
    when (event) {
        is IslandEvent.ChargingStarted -> ChargingStartedCard(event, displayMode)
        is IslandEvent.ChargingStopped -> ChargingStoppedCard(event, displayMode)
        is IslandEvent.BatteryFull -> BatteryFullCard(event, displayMode)
        is IslandEvent.LowBattery -> LowBatteryCard(event, displayMode)
        is IslandEvent.WifiConnected -> WifiConnectedCard(event, displayMode)
        is IslandEvent.WifiDisconnected -> WifiDisconnectedCard(displayMode)
        is IslandEvent.HotspotChanged -> HotspotCard(event, displayMode)
        is IslandEvent.BluetoothChanged -> BluetoothCard(event, displayMode)
        is IslandEvent.AirplaneModeChanged -> AirplaneModeCard(event, displayMode)
        is IslandEvent.RingerModeChanged -> RingerModeCard(event, displayMode)
        is IslandEvent.IncomingCall -> IncomingCallCard(event, displayMode, onCallCommand)
        is IslandEvent.MusicPlayback -> MusicPlayerCard(event, displayMode, onMediaCommand)
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
    val popScale = remember(key) { Animatable(0.52f) }
    LaunchedEffect(key) {
        popScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.55f,
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
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "batterySweep"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "ringPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (animatePulse) 0.35f else 0.18f,
        targetValue = if (animatePulse) 0.9f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

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
            color = color.copy(alpha = if (animatePulse) pulseAlpha else 1f),
            startAngle = -90f,
            sweepAngle = animatedSweep,
            useCenter = false,
            style = stroke
        )
    }
}

@Composable
private fun ChargingStartedCard(event: IslandEvent.ChargingStarted, mode: IslandDisplayMode) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(Icons.Rounded.Bolt, IslandGreen, "Charging", event.id, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text("Charging", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${event.batteryPercent}%", color = IslandGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                BatteryRing(event.batteryPercent, IslandGreen, animatePulse = true, size = 20.dp)
            }
        }
    } else {
        StandardExpandedCard(
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
private fun ChargingStoppedCard(event: IslandEvent.ChargingStopped, mode: IslandDisplayMode) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(Icons.Rounded.BatteryStd, MutedWhite, "Unplugged", event.id, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Unplugged", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text("${event.batteryPercent}%", color = MutedWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        StandardExpandedCard(
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
private fun BatteryFullCard(event: IslandEvent.BatteryFull, mode: IslandDisplayMode) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(Icons.Rounded.CheckCircle, IslandGreen, "Battery Full", event.id, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Battery Full", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text("100%", color = IslandGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        StandardExpandedCard(
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
private fun LowBatteryCard(event: IslandEvent.LowBattery, mode: IslandDisplayMode) {
    val accent = if (event.isCritical) IslandRed else IslandOrange
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(Icons.Rounded.BatteryAlert, accent, "Low Battery", event.id, 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (event.isCritical) "Critical Battery" else "Low Battery",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("${event.batteryPercent}%", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        StandardExpandedCard(
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
private fun WifiConnectedCard(event: IslandEvent.WifiConnected, mode: IslandDisplayMode) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                PopIcon(Icons.Rounded.Wifi, IslandBlue, "Wi-Fi", event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.ssid,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Wi-Fi", color = IslandBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        StandardExpandedCard(
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
private fun WifiDisconnectedCard(mode: IslandDisplayMode) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(Icons.Rounded.WifiOff, IslandRed, "Wi-Fi Off", "wifi_off", 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Wi-Fi Disconnected", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    } else {
        StandardExpandedCard(
            icon = { PopIcon(Icons.Rounded.WifiOff, IslandRed, "Wi-Fi Disconnected", "wifi_off", 28.dp) },
            title = "Wi-Fi Disconnected",
            subtitle = "No wireless network connection",
            trailing = {}
        )
    }
}

@Composable
private fun HotspotCard(event: IslandEvent.HotspotChanged, mode: IslandDisplayMode) {
    val accent = if (event.enabled) IslandGreen else MutedWhite
    val icon = if (event.enabled) Icons.Rounded.WifiTethering else Icons.Rounded.WifiTetheringOff
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(icon, accent, "Hotspot", event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (event.enabled) "Hotspot On" else "Hotspot Off",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(if (event.enabled) "Active" else "Off", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        StandardExpandedCard(
            icon = { PopIcon(icon, accent, "Hotspot", event.id, 28.dp) },
            title = if (event.enabled) "Personal Hotspot On" else "Personal Hotspot Off",
            subtitle = if (event.enabled) "Sharing mobile connection" else "Tethering disabled",
            trailing = {}
        )
    }
}

@Composable
private fun BluetoothCard(event: IslandEvent.BluetoothChanged, mode: IslandDisplayMode) {
    val icon = when {
        !event.connected -> Icons.Rounded.BluetoothDisabled
        event.isAudioDevice -> Icons.Rounded.Headphones
        else -> Icons.Rounded.BluetoothConnected
    }
    val accent = if (event.connected) IslandTeal else MutedWhite

    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                PopIcon(icon, accent, "Bluetooth", event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.deviceName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (event.connected) "Connected" else "Off",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        StandardExpandedCard(
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
private fun AirplaneModeCard(event: IslandEvent.AirplaneModeChanged, mode: IslandDisplayMode) {
    val accent = if (event.enabled) IslandOrange else IslandGreen
    val icon = if (event.enabled) Icons.Rounded.AirplanemodeActive else Icons.Rounded.AirplanemodeInactive
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(icon, accent, "Airplane Mode", event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (event.enabled) "Airplane On" else "Airplane Off",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
        StandardExpandedCard(
            icon = { PopIcon(icon, accent, "Airplane Mode", event.id, 28.dp) },
            title = if (event.enabled) "Airplane Mode On" else "Airplane Mode Off",
            subtitle = if (event.enabled) "Wireless radios suspended" else "Cellular & Wi-Fi restored",
            trailing = {}
        )
    }
}

@Composable
private fun RingerModeCard(event: IslandEvent.RingerModeChanged, mode: IslandDisplayMode) {
    val (label, icon, accent) = when (event.mode) {
        RingerMode.SILENT -> Triple("Silent", Icons.Rounded.NotificationsOff, IslandRed)
        RingerMode.VIBRATE -> Triple("Vibrate", Icons.Rounded.Vibration, IslandOrange)
        RingerMode.RING -> Triple("Ring", Icons.Rounded.NotificationsActive, IslandGreen)
    }
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIcon(icon, accent, label, event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text("Mode", color = MutedWhite, fontSize = 11.sp)
        }
    } else {
        StandardExpandedCard(
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
    onCallCommand: (CallCommand) -> Unit
) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                PopIcon(Icons.Rounded.Call, IslandGreen, "Incoming Call", event.id, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.callerName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Incoming", color = IslandGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(IslandRed)
                        .clickable { onCallCommand(CallCommand.Decline) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(IslandGreen)
                        .clickable { onCallCommand(CallCommand.Accept) },
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
    onMediaCommand: (MediaCommand) -> Unit
) {
    if (mode == IslandDisplayMode.COMPACT) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AlbumArtBox(event = event, size = 24.dp, corner = 7.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.title.ifBlank { "Now Playing" },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            AnimatedEqualizer(isPlaying = event.isPlaying, color = IslandViolet)
        }
    } else {
        val rawProgress = event.positionMs.toFloat() / event.durationMs.coerceAtLeast(1L).toFloat()
        val progress = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtBox(
                    event = event,
                    size = if (mode == IslandDisplayMode.FULL_EXPANDED) 56.dp else 46.dp,
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
                AnimatedEqualizer(isPlaying = event.isPlaying, color = IslandViolet)
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color.White,
                trackColor = Color(0x33FFFFFF)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onMediaCommand(MediaCommand.Previous) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x26FFFFFF))
                        .clickable { onMediaCommand(MediaCommand.PlayPause) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (event.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (event.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onMediaCommand(MediaCommand.Next) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
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
    val transition = rememberInfiniteTransition(label = "eq")
    val bar1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq3"
    )
    val bar4 by transition.animateFloat(
        initialValue = 0.70f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq4"
    )

    val levels = if (isPlaying) listOf(bar1, bar2, bar3, bar4) else listOf(0.25f, 0.25f, 0.25f, 0.25f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)
    ) {
        levels.forEach { level ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer { scaleY = level }
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun StandardExpandedCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
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
