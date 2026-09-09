package com.wmods.wppenhacer.ui.home.compose

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
import com.wmods.wppenhacer.activities.MainActivity

/**
 * Modifier liquid glass serasi dengan Kustomisasi Tampilan & Remielle Kernel Manager.
 * Memadukan latar belakang kristal kaca gelap bergradasi dengan pantulan specular sheen di bagian atas
 * dan border specular putih mengkilap di keliling kartu.
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(22.dp),
    borderAlphaTop: Float = 0.35f,
    borderAlphaBottom: Float = 0.08f,
    borderWidth: Dp = 1.2.dp
): Modifier {
    val hasWallpaper = MainActivity.customWallpaperBitmap != null
    val backgroundBrush = if (hasWallpaper) {
        // Frosted translucent dark glass tint identik dengan CardPreferenceItemDecoration
        Brush.verticalGradient(
            colors = listOf(
                Color(0xD01A1E26), // argb(208, 26, 30, 38)
                Color(0xA812161C)  // argb(168, 18, 22, 28)
            )
        )
    } else {
        // Deep OLED Glass tone
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFA181C22),
                Color(0xFA0E1014)
            )
        )
    }

    return this
        .clip(shape)
        .background(backgroundBrush, shape = shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = 80f
            ),
            shape = shape
        )
        .border(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = borderAlphaTop),
                    Color.White.copy(alpha = borderAlphaBottom)
                )
            ),
            shape = shape
        )
}

/**
 * Kotak Card Liquid Glass yang siap menampung konten di dalamnya.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(shape = shape),
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
            .border(1.dp, Color.White.copy(alpha = 0.20f), shape),
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