package com.island.app.ui.overlay

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.island.app.data.IslandSettings
import com.island.app.model.IslandDisplayMode
import com.island.app.model.IslandEvent
import com.island.app.service.CallCommand
import com.island.app.service.MediaCommand
import com.island.app.state.IslandUiState

@Composable
fun DynamicIslandOverlay(
    uiState: IslandUiState,
    settings: IslandSettings,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onMediaCommand: (MediaCommand) -> Unit,
    onCallCommand: (CallCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHidden = !settings.masterEnabled ||
        (settings.hideInFullscreen && uiState.isFullscreenHidden)

    val visibilityAlpha by animateFloatAsState(
        targetValue = if (isHidden) 0f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "islandVisibilityAlpha"
    )
    val visibilityScale by animateFloatAsState(
        targetValue = if (isHidden) 0.78f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "islandVisibilityScale"
    )

    if (visibilityAlpha <= 0.01f && isHidden) {
        return
    }

    val activeEvent = uiState.activeEvent
    val mode = if (activeEvent == null) IslandDisplayMode.IDLE else uiState.displayMode

    val targetWidth: Dp
    val targetHeight: Dp
    val targetCorner: Dp

    when (mode) {
        IslandDisplayMode.IDLE -> {
            targetWidth = settings.idleWidthDp.dp
            targetHeight = settings.idleHeightDp.dp
            targetCorner = settings.cornerRadiusDp.dp
        }
        IslandDisplayMode.COMPACT -> {
            targetWidth = when (activeEvent) {
                is IslandEvent.MusicPlayback -> 228.dp
                is IslandEvent.IncomingCall -> 248.dp
                is IslandEvent.WifiConnected -> 236.dp
                is IslandEvent.BluetoothChanged -> 244.dp
                else -> 216.dp
            }
            targetHeight = maxOf(settings.idleHeightDp.dp, 40.dp)
            targetCorner = 24.dp
        }
        IslandDisplayMode.EXPANDED -> {
            targetWidth = 344.dp
            targetHeight = when (activeEvent) {
                is IslandEvent.IncomingCall -> 112.dp
                is IslandEvent.MusicPlayback -> 148.dp
                else -> 88.dp
            }
            targetCorner = 30.dp
        }
        IslandDisplayMode.FULL_EXPANDED -> {
            targetWidth = 364.dp
            targetHeight = when (activeEvent) {
                is IslandEvent.MusicPlayback -> 216.dp
                is IslandEvent.IncomingCall -> 146.dp
                else -> 136.dp
            }
            targetCorner = 34.dp
        }
    }

    val springDpSpec = remember {
        spring<Dp>(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow
        )
    }

    val animatedWidth by animateDpAsState(targetWidth, springDpSpec, label = "islandWidth")
    val animatedHeight by animateDpAsState(targetHeight, springDpSpec, label = "islandHeight")
    val animatedCorner by animateDpAsState(targetCorner, springDpSpec, label = "islandCorner")

    // Subtle blur-in impulse whenever the event or display mode morphs
    val morphBlur = remember { Animatable(0f) }
    LaunchedEffect(activeEvent?.id, mode) {
        morphBlur.snapTo(8f)
        morphBlur.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 260)
        )
    }

    val shape = RoundedCornerShape(animatedCorner)

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = visibilityAlpha
                scaleX = visibilityScale
                scaleY = visibilityScale
            }
            .width(animatedWidth)
            .height(animatedHeight)
            .clip(shape)
            .background(Color.Black)
            .border(width = 0.75.dp, color = Color(0x26FFFFFF), shape = shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .pointerInput(mode) {
                var cumulativeDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { cumulativeDrag = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        cumulativeDrag += dragAmount
                        if (cumulativeDrag < -24f && mode != IslandDisplayMode.IDLE) {
                            onSwipeUp()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = Pair(activeEvent, mode),
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                )) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.92f))
            },
            contentKey = { (evt, m) -> "${evt?.type?.name ?: "IDLE"}_${evt?.id ?: "none"}_${m.name}" },
            label = "islandContentTransition"
        ) { (event, displayMode) ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val blurPx = morphBlur.value
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0.5f) {
                            renderEffect = RenderEffect
                                .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (event == null || displayMode == IslandDisplayMode.IDLE) {
                    IdleCapsuleContent()
                } else {
                    EventCardContent(
                        event = event,
                        displayMode = displayMode,
                        onMediaCommand = onMediaCommand,
                        onCallCommand = onCallCommand
                    )
                }
            }
        }

        if (uiState.queuedCount > 0 && mode != IslandDisplayMode.IDLE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 10.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "+${uiState.queuedCount}",
                    color = Color(0xFFD1D5DB),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun IdleCapsuleContent() {
    // Minimal hardware-cam inspired dark sheen inside the idle capsule
    Box(modifier = Modifier.fillMaxSize())
}
