package com.mangapanel.search.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangapanel.search.data.model.BoundingBox

@Composable
fun PanelHighlightViewer(
    imageUrl: Any,
    boundingBox: BoundingBox,
    modifier: Modifier = Modifier
) {
    var imageLayoutSize by remember { mutableStateOf(IntSize.Zero) }
    var naturalImageSize by remember { mutableStateOf(IntSize.Zero) }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Subtle breathing pulse effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Matched Manga Page",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    imageLayoutSize = layoutCoordinates.size
                },
            onSuccess = { state ->
                val drawable = state.result.drawable
                naturalImageSize = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
        )

        // Overlay Bounding Box Frame
        if (imageLayoutSize.width > 0 && imageLayoutSize.height > 0 &&
            naturalImageSize.width > 0 && naturalImageSize.height > 0
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = minOf(
                    size.width / naturalImageSize.width.toFloat(),
                    size.height / naturalImageSize.height.toFloat()
                )

                val displayedW = naturalImageSize.width * scale
                val displayedH = naturalImageSize.height * scale

                val offsetX = (size.width - displayedW) / 2f
                val offsetY = (size.height - displayedH) / 2f

                val boxLeft = offsetX + (boundingBox.x * scale)
                val boxTop = offsetY + (boundingBox.y * scale)
                val boxW = boundingBox.w * scale
                val boxH = boundingBox.h * scale

                // Semi-transparent highlight fill
                drawRect(
                    color = primaryColor.copy(alpha = 0.20f * alpha),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxW, boxH)
                )

                // High-visibility primary border with shadow
                drawRect(
                    color = primaryColor.copy(alpha = alpha),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 8f)
                )

                // Outer contrast ring (black shadow)
                drawRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(boxLeft - 2f, boxTop - 2f),
                    size = Size(boxW + 4f, boxH + 4f),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}
