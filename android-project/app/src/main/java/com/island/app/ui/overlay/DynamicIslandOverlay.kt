package com.island.app.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.island.app.service.CutoutMetrics
import com.island.app.service.MediaCommand
import com.island.app.state.IslandUiState
import kotlin.math.abs

@Immutable
data class CutoutLayoutPlan(
    val leftWingDp: Dp = 0.dp,
    val centerGapDp: Dp = 0.dp,
    val rightWingDp: Dp = 0.dp,
    val startClearanceDp: Dp = 0.dp,
    val endClearanceDp: Dp = 0.dp,
    val topClearanceDp: Dp = 0.dp
)

@Immutable
data class IslandLayoutTarget(
    val widthDp: Float,
    val heightDp: Float,
    val cornerDp: Float,
    val cutoutPlan: CutoutLayoutPlan
)

fun computeIslandLayoutTarget(
    activeEvent: IslandEvent?,
    mode: IslandDisplayMode,
    settings: IslandSettings,
    cutout: CutoutMetrics
): IslandLayoutTarget {
    val effectiveMode = if (activeEvent == null) IslandDisplayMode.IDLE else mode
    val baseWidth: Float
    val baseHeight: Float
    val baseCorner: Float

    when (effectiveMode) {
        IslandDisplayMode.IDLE -> {
            return IslandLayoutTarget(
                widthDp = settings.idleWidthDp,
                heightDp = settings.idleHeightDp,
                cornerDp = settings.cornerRadiusDp,
                cutoutPlan = CutoutLayoutPlan()
            )
        }
        IslandDisplayMode.COMPACT -> {
            baseWidth = when (activeEvent) {
                is IslandEvent.MusicPlayback -> 236f
                is IslandEvent.IncomingCall -> 252f
                is IslandEvent.WifiConnected -> 240f
                is IslandEvent.BluetoothChanged -> 248f
                else -> 220f
            }
            baseHeight = maxOf(settings.idleHeightDp, 40f)
            baseCorner = 24f
        }
        IslandDisplayMode.EXPANDED -> {
            baseWidth = 344f
            baseHeight = when (activeEvent) {
                is IslandEvent.IncomingCall -> 116f
                is IslandEvent.MusicPlayback -> 184f
                else -> 92f
            }
            baseCorner = 30f
        }
        IslandDisplayMode.FULL_EXPANDED -> {
            baseWidth = 364f
            baseHeight = when (activeEvent) {
                is IslandEvent.MusicPlayback -> 228f
                is IslandEvent.IncomingCall -> 148f
                else -> 136f
            }
            baseCorner = 34f
        }
    }

    if (!cutout.hasCutout || cutout.screenWidthDp <= 0f) {
        return IslandLayoutTarget(
            widthDp = baseWidth,
            heightDp = baseHeight,
            cornerDp = baseCorner,
            cutoutPlan = CutoutLayoutPlan()
        )
    }

    val safeCutoutLeft = cutout.cutoutLeftDp - 6f
    val safeCutoutRight = cutout.cutoutRightDp + 6f
    val safeCutoutBottom = cutout.cutoutBottomDp + 6f
    val pillTop = settings.offsetY
    val pillCenterX = cutout.screenWidthDp / 2f + settings.offsetX
    val maxWidth = (cutout.screenWidthDp - 16f).coerceAtLeast(240f)

    if (effectiveMode == IslandDisplayMode.COMPACT) {
        val vertOverlap = pillTop < safeCutoutBottom && (pillTop + baseHeight) > cutout.cutoutTopDp
        if (!vertOverlap) {
            return IslandLayoutTarget(baseWidth, baseHeight, baseCorner, CutoutLayoutPlan())
        }

        val provisionalLeft = pillCenterX - baseWidth / 2f
        val provisionalRight = pillCenterX + baseWidth / 2f
        val horizOverlap = safeCutoutRight > provisionalLeft && safeCutoutLeft < provisionalRight
        if (!horizOverlap) {
            return IslandLayoutTarget(baseWidth, baseHeight, baseCorner, CutoutLayoutPlan())
        }

        val cutoutCenter = (cutout.cutoutLeftDp + cutout.cutoutRightDp) / 2f
        val distFromCenter = abs(cutoutCenter - pillCenterX)

        if (distFromCenter < baseWidth * 0.28f) {
            val gap = (safeCutoutRight - safeCutoutLeft).coerceIn(28f, 72f)
            val resolvedWidth = (baseWidth + gap).coerceAtMost(maxWidth)
            val actualLeft = pillCenterX - resolvedWidth / 2f
            val actualRight = pillCenterX + resolvedWidth / 2f
            var leftWing = (safeCutoutLeft - actualLeft).coerceAtLeast(32f)
            var rightWing = (actualRight - safeCutoutRight).coerceAtLeast(32f)
            val availableForWings = (resolvedWidth - gap).coerceAtLeast(64f)
            val wingSum = leftWing + rightWing
            if (wingSum > 0f) {
                val scale = availableForWings / wingSum
                leftWing *= scale
                rightWing *= scale
            }
            return IslandLayoutTarget(
                widthDp = resolvedWidth,
                heightDp = baseHeight,
                cornerDp = baseCorner,
                cutoutPlan = CutoutLayoutPlan(
                    leftWingDp = leftWing.dp,
                    centerGapDp = gap.dp,
                    rightWingDp = rightWing.dp
                )
            )
        } else if (cutoutCenter < pillCenterX) {
            val startClear = (safeCutoutRight - provisionalLeft).coerceIn(0f, baseWidth * 0.42f)
            val resolvedWidth = (baseWidth + startClear).coerceAtMost(maxWidth)
            return IslandLayoutTarget(
                widthDp = resolvedWidth,
                heightDp = baseHeight,
                cornerDp = baseCorner,
                cutoutPlan = CutoutLayoutPlan(startClearanceDp = startClear.dp)
            )
        } else {
            val endClear = (provisionalRight - safeCutoutLeft).coerceIn(0f, baseWidth * 0.42f)
            val resolvedWidth = (baseWidth + endClear).coerceAtMost(maxWidth)
            return IslandLayoutTarget(
                widthDp = resolvedWidth,
                heightDp = baseHeight,
                cornerDp = baseCorner,
                cutoutPlan = CutoutLayoutPlan(endClearanceDp = endClear.dp)
            )
        }
    }

    // EXPANDED / FULL_EXPANDED: push content safely below any overlapping camera cutout
    val expandedWidth = baseWidth.coerceAtMost(maxWidth)
    val expLeft = pillCenterX - expandedWidth / 2f
    val expRight = pillCenterX + expandedWidth / 2f
    val horizOverlap = safeCutoutRight > expLeft && safeCutoutLeft < expRight
    val vertOverlap = pillTop < safeCutoutBottom && (pillTop + baseHeight) > cutout.cutoutTopDp
    val topClearance = if (horizOverlap && vertOverlap) {
        (safeCutoutBottom - pillTop).coerceIn(0f, 44f)
    } else {
        0f
    }
    val extraHeight = (topClearance - 14f).coerceAtLeast(0f)

    return IslandLayoutTarget(
        widthDp = expandedWidth,
        heightDp = baseHeight + extraHeight,
        cornerDp = baseCorner,
        cutoutPlan = CutoutLayoutPlan(topClearanceDp = topClearance.dp)
    )
}

@Composable
fun DynamicIslandOverlay(
    uiState: IslandUiState,
    settings: IslandSettings,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
    onMediaCommand: (MediaCommand) -> Unit,
    onCallCommand: (CallCommand) -> Unit,
    modifier: Modifier = Modifier,
    cutoutMetrics: CutoutMetrics = CutoutMetrics(),
    isLiveDragging: Boolean = false
) {
    val isHidden = !settings.masterEnabled ||
        (settings.hideInFullscreen && uiState.isFullscreenHidden)

    val visibilityAlpha by animateFloatAsState(
        targetValue = if (isHidden) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "islandVisibilityAlpha"
    )
    val visibilityScale by animateFloatAsState(
        targetValue = if (isHidden) 0.78f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 440f),
        label = "islandVisibilityScale"
    )

    if (visibilityAlpha <= 0.01f && isHidden) {
        return
    }

    val activeEvent = uiState.activeEvent
    val mode = if (activeEvent == null) IslandDisplayMode.IDLE else uiState.displayMode

    val layoutTarget = remember(
        activeEvent?.type,
        mode,
        settings.idleWidthDp,
        settings.idleHeightDp,
        settings.cornerRadiusDp,
        settings.offsetX,
        settings.offsetY,
        cutoutMetrics
    ) {
        computeIslandLayoutTarget(
            activeEvent = activeEvent,
            mode = mode,
            settings = settings,
            cutout = cutoutMetrics
        )
    }

    val speed = settings.animationSpeed.coerceIn(0.5f, 2.0f)
    val sizeSpec = remember(speed, isLiveDragging, mode) {
        if (isLiveDragging && mode == IslandDisplayMode.IDLE) {
            snap<Dp>()
        } else {
            spring<Dp>(
                dampingRatio = 0.78f,
                stiffness = (440f * speed).coerceIn(220f, 900f)
            )
        }
    }

    val animatedWidth by animateDpAsState(layoutTarget.widthDp.dp, sizeSpec, label = "islandWidth")
    val animatedHeight by animateDpAsState(layoutTarget.heightDp.dp, sizeSpec, label = "islandHeight")
    val animatedCorner by animateDpAsState(layoutTarget.cornerDp.dp, sizeSpec, label = "islandCorner")

    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 650f),
        label = "islandPressScale"
    )

    val safeWidth = animatedWidth.coerceAtLeast(1.dp)
    val safeHeight = animatedHeight.coerceAtLeast(1.dp)
    val safeCorner = animatedCorner.coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(safeCorner)

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = visibilityAlpha.coerceIn(0f, 1f)
                val combinedScale = (visibilityScale * pressScale).coerceAtLeast(0.1f)
                scaleX = combinedScale
                scaleY = combinedScale
            }
            .width(safeWidth)
            .height(safeHeight)
            .clip(shape)
            .background(Color.Black)
            .border(width = 0.75.dp, color = Color(0x29FFFFFF), shape = shape)
            .pointerInput(mode) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .pointerInput(mode) {
                var cumulativeDrag = 0f
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = {
                        cumulativeDrag = 0f
                        triggered = false
                    },
                    onDragEnd = { isPressed = false },
                    onDragCancel = { isPressed = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (triggered) return@detectVerticalDragGestures
                        cumulativeDrag += dragAmount
                        if (cumulativeDrag < -22f && mode != IslandDisplayMode.IDLE) {
                            triggered = true
                            isPressed = false
                            onSwipeUp()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val enterFadeMs = (160f / speed).toInt().coerceIn(80, 260)
        val enterDelayMs = (35f / speed).toInt().coerceIn(15, 70)
        val exitFadeMs = (90f / speed).toInt().coerceIn(50, 150)

        AnimatedContent(
            targetState = Pair(activeEvent, mode),
            transitionSpec = {
                (fadeIn(tween(durationMillis = enterFadeMs, delayMillis = enterDelayMs)) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = (460f * speed).coerceIn(220f, 920f)
                    )
                )) togetherWith (fadeOut(tween(durationMillis = exitFadeMs)) + scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(durationMillis = exitFadeMs)
                ))
            },
            contentKey = { (evt, m) -> "${evt?.type?.name ?: "IDLE"}_${evt?.id ?: "none"}_${m.name}" },
            label = "islandContentTransition"
        ) { (event, displayMode) ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (event == null || displayMode == IslandDisplayMode.IDLE) {
                    IdleCapsuleContent()
                } else {
                    EventCardContent(
                        event = event,
                        displayMode = displayMode,
                        cutoutPlan = layoutTarget.cutoutPlan,
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
                    .padding(
                        top = maxOf(6.dp, layoutTarget.cutoutPlan.topClearanceDp),
                        end = maxOf(10.dp, layoutTarget.cutoutPlan.endClearanceDp + 6.dp)
                    )
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
    Box(modifier = Modifier.fillMaxSize())
}
