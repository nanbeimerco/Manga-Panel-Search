package com.mangapanel.search.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mangapanel.search.data.model.BoundingBox

@Composable
fun PanelHighlightViewer(
    imageUrl: Any,
    boundingBox: BoundingBox,
    modifier: Modifier = Modifier,
    title: String = "",
    subtitle: String = ""
) {
    var isViewerOpen by remember { mutableStateOf(false) }

    var imageLayoutSize by remember { mutableStateOf(IntSize.Zero) }
    var naturalImageSize by remember { mutableStateOf(IntSize.Zero) }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Subtle breathing pulse effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
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
            .background(Color.Black)
            .clickable { isViewerOpen = true },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Matched Manga Page",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
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

                // High-visibility primary border
                drawRect(
                    color = primaryColor.copy(alpha = alpha),
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 6f)
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

        // Tap to zoom hint badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(9999.dp))
                .background(Color.Black.copy(alpha = 0.70f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "タップで拡大",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Fullscreen Pinch-to-Zoom Dialog
    if (isViewerOpen) {
        Dialog(
            onDismissRequest = { isViewerOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            var fullLayoutSize by remember { mutableStateOf(IntSize.Zero) }
            var fullNatSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                // Interactive Zoom Area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 5.0f)
                                offset = Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Full Matched Manga Page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { layoutCoordinates ->
                                fullLayoutSize = layoutCoordinates.size
                            },
                        onSuccess = { state ->
                            val drawable = state.result.drawable
                            fullNatSize = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
                        }
                    )

                    // Overlay Bounding Box on Zoom View
                    if (fullLayoutSize.width > 0 && fullLayoutSize.height > 0 &&
                        fullNatSize.width > 0 && fullNatSize.height > 0
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val imgScale = minOf(
                                size.width / fullNatSize.width.toFloat(),
                                size.height / fullNatSize.height.toFloat()
                            )

                            val dispW = fullNatSize.width * imgScale
                            val dispH = fullNatSize.height * imgScale

                            val offX = (size.width - dispW) / 2f
                            val offY = (size.height - dispH) / 2f

                            val bLeft = offX + (boundingBox.x * imgScale)
                            val bTop = offY + (boundingBox.y * imgScale)
                            val bW = boundingBox.w * imgScale
                            val bH = boundingBox.h * imgScale

                            // Box fill
                            drawRect(
                                color = primaryColor.copy(alpha = 0.25f * alpha),
                                topLeft = Offset(bLeft, bTop),
                                size = Size(bW, bH)
                            )

                            // Border
                            drawRect(
                                color = primaryColor.copy(alpha = alpha),
                                topLeft = Offset(bLeft, bTop),
                                size = Size(bW, bH),
                                style = Stroke(width = 6f / scale)
                            )
                        }
                    }
                }

                // Top Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title.isNotEmpty()) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "リセット",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { isViewerOpen = false },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "閉じる",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom Hint
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "ピンチ操作でズーム・ドラッグで移動できます",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
