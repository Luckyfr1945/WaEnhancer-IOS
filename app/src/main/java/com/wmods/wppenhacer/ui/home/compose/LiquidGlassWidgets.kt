package com.wmods.wppenhacer.ui.home.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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

/**
 * Modifier liquid glass:
 * Translucent frosted glass card with specular crystal border and subtle top sheen.
 * Desain tidak terlalu gelap ("ga terlalu gelap"), membiarkan wallpaper blur di belakang bersinar lembut,
 * dengan kontras yang tetap nyaman untuk teks putih dan ikon.
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    hasWallpaper: Boolean = true,
    borderAlphaTop: Float = 0.38f,
    borderAlphaBottom: Float = 0.12f,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(
        brush = if (hasWallpaper) {
            // Translucent frosted glass tint (subtle, not dark)
            Brush.verticalGradient(
                colors = listOf(
                    Color(0x4D202632), // ~30% alpha soft slate glass
                    Color(0x2E141820)  // ~18% alpha translucent glass
                )
            )
        } else {
            // Deep OLED Glass tone when no wallpaper
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xE6181C22),
                    Color(0xE60E1014)
                )
            )
        },
        shape = shape
    )
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.14f),
                Color.Transparent
            ),
            startY = 0f,
            endY = 90f
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
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    hasWallpaper: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(
            shape = shape,
            hasWallpaper = hasWallpaper
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