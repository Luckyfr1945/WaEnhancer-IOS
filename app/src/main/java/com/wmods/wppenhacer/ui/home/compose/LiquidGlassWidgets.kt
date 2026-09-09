package com.wmods.wppenhacer.ui.home.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * Modifier liquid glass identik dengan Remielle Kernel Manager.
 * Mem-blur background secara real-time di bawah kotak dengan tepi kristal putih berkilau
 * dan pantulan specular halus di bagian atas kartu.
 */
fun Modifier.liquidGlass(
    hazeState: HazeState,
    shape: Shape = RoundedCornerShape(28.dp),
    backgroundColor: Color = Color(0xFF1E222A).copy(alpha = 0.35f),
    blurRadius: Dp = 32.dp,
    borderAlphaTop: Float = 0.35f,
    borderAlphaBottom: Float = 0.08f,
    borderWidth: Dp = 1.dp
): Modifier = this
    .zIndex(1f)
    .clip(shape)
    .hazeEffect(
        state = hazeState,
        style = HazeStyle(
            backgroundColor = backgroundColor,
            blurRadius = blurRadius,
            noiseFactor = 0f,
            tints = emptyList()
        )
    ) {
        // Fix mutlak untuk Android 12+ (API 31-35) RenderEffect pre-draw invalidation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            forceInvalidateOnPreDraw = true
        }
    }
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.09f),
                Color.Transparent
            )
        ),
        shape = shape
    )
    .border(
        width = borderWidth,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = borderAlphaTop),
                Color.White.copy(alpha = borderAlphaBottom)
            )
        ),
        shape = shape
    )

/**
 * Kotak Card Liquid Glass yang siap menampung konten di dalamnya.
 */
@Composable
fun LiquidGlassCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    backgroundColor: Color = Color(0xFF1E222A).copy(alpha = 0.35f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(
            hazeState = hazeState,
            shape = shape,
            backgroundColor = backgroundColor
        ),
        content = content
    )
}

/**
 * Icon container berbentuk kapsul/squircle halus untuk icon di dalam kartu.
 */
@Composable
fun GlassIconContainer(
    icon: ImageVector,
    containerColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    cornerRadius: Dp = 12.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor, shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}