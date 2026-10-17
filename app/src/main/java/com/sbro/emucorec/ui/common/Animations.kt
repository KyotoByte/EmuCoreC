package com.sbro.emucorec.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbro.emucorec.ui.theme.neon.neonShape
import kotlinx.coroutines.delay

/**
 * Delays a full-screen loading state so fast work never flashes the spinner.
 *
 * Lists that reload in a few dozen milliseconds (the common case for local
 * scans) used to blink their progress animation on every screen entry and
 * refresh. Only work that actually exceeds [delayMillis] becomes visible;
 * stopping the work hides it immediately.
 */
@Composable
fun rememberDelayedLoading(active: Boolean, delayMillis: Long = 300): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMillis)
            visible = true
        } else {
            visible = false
        }
    }
    return active && visible
}

@Composable
fun EmuCoreLoadingAnimation(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "EmuCoreCLoading")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "coreRotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "centerPulse"
    )

    val glowOpacity by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Strokes follow the requested size so the mark stays legible on phones
    // without turning into a wall of neon: a fixed 5dp ring looked absurd at
    // the old 110dp default.
    val stroke = (size.value / 13f).coerceIn(2f, 4f).dp
    val innerStroke = (stroke.value * 0.7f).dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "EMUCOREC",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp,
                color = color.copy(alpha = glowOpacity)
            )
        )

        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 1.10f)
                    .graphicsLayer {
                        scaleX = pulseScale * 1.12f
                        scaleY = pulseScale * 1.12f
                        alpha = glowOpacity * 0.30f
                    }
                    .background(color.copy(alpha = 0.22f), CircleShape)
            )

            Box(
                modifier = Modifier
                    .size(size * 0.92f)
                    .graphicsLayer { rotationZ = rotation }
                    .border(stroke, color.copy(alpha = 0.90f), neonShape(size * 0.30f))
            )

            Box(
                modifier = Modifier
                    .size(size * 0.66f)
                    .graphicsLayer { rotationZ = -rotation * 1.7f }
                    .border(innerStroke, color.copy(alpha = 0.65f), neonShape(size * 0.22f))
            )

            Text(
                text = "C",
                fontSize = (size.value * 0.46f).sp,
                fontWeight = FontWeight.Black,
                color = color,
                modifier = Modifier.graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
            )
        }
    }
}
