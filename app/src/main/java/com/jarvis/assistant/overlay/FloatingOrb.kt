package com.jarvis.assistant.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * The orb as it appears on top of other apps, plus the one line of status text
 * that tells the user what JARVIS is doing without them having to switch back.
 *
 * The caption is deliberately the only text: this floats over someone else's UI,
 * and anything more would be an imposition.
 */
@Composable
fun FloatingOrb(
    state: JarvisState,
    caption: String?,
    orbSize: Dp,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { onLongPress() },
                    )
                }
                // Drag is a separate pointerInput from taps on purpose: combining
                // them in one detector makes a slightly imprecise tap register as
                // a two-pixel drag and swallow the click.
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                    )
                },
        ) {
            OrbCanvas(state = state, size = orbSize)
        }

        AnimatedVisibility(
            visible = !caption.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = caption.orEmpty(),
                color = JarvisColors.OnBackground,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = orbSize * 1.5f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(JarvisColors.Background.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
