package com.wmods.wppenhacer.ui.home.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmods.wppenhacer.activities.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeComposeScreen(
    isModuleActive: Boolean,
    isRootGranted: Boolean,
    wppVersion: String,
    w4bVersion: String,
    deviceModel: String,
    androidSdk: Int,
    initialWallpaperBitmap: Bitmap? = null,
    onRestartWpp: () -> Unit,
    onRestartW4b: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportConfigs: () -> Unit,
    onImportConfigs: () -> Unit,
    onResetConfigs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // State deteksi root dinamis
    var currentRootStatus by remember { mutableStateOf(isRootGranted) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val root = try {
                com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                currentRootStatus = root
            }
        }
    }

    val hasWallpaper = remember {
        MainActivity.customWallpaperBitmap != null || initialWallpaperBitmap != null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (hasWallpaper) Color.Transparent else Color(0xFF121418))
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ========================================================
            // 1. KOTAK HERO STATUS (Modul LSPosed)
            // ========================================================
            LiquidGlassHeroCard(
                isModuleActive = isModuleActive,
                onOpenDiagnostics = onOpenDiagnostics,
                hasWallpaper = hasWallpaper
            )

            // ========================================================
            // 2. KOTAK 2-KOLOM (Seperti Kesehatan, Suhu, Siklus Remielle)
            // ========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // KOTAK KIRI (TINGGI): WhatsApp Standard
                LiquidGlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    hasWallpaper = hasWallpaper
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WhatsApp",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                GlassIconContainer(
                                    icon = Icons.AutoMirrored.Rounded.Chat,
                                    containerColor = Color(0xFF25D366).copy(alpha = 0.20f),
                                    iconColor = Color(0xFF25D366),
                                    size = 30.dp,
                                    iconSize = 15.dp,
                                    cornerRadius = 8.dp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = if (wppVersion.isNotEmpty()) wppVersion else "Belum Terpasang",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 25.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "com.whatsapp",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }

                        // Tombol restart kaca
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF25D366).copy(alpha = 0.20f))
                                .border(1.dp, Color(0xFF25D366).copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                                .clickable { onRestartWpp() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF25D366),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Restart WA",
                                    color = Color(0xFF25D366),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // KOLOM KANAN (Dua Kotak: Suhu & Siklus Style)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // KOTAK KANAN ATAS: WhatsApp Business
                    LiquidGlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        hasWallpaper = hasWallpaper
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "WA Business",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (w4bVersion.isNotEmpty()) Color(0xFF25D366) else Color.White.copy(alpha = 0.4f))
                                )
                            }

                            Text(
                                text = if (w4bVersion.isNotEmpty()) w4bVersion else "Off",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (w4bVersion.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                        .clickable { onRestartW4b() }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Restart W4B",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // KOTAK KANAN BAWAH: Model Perangkat & SDK
                    LiquidGlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        hasWallpaper = hasWallpaper
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Perangkat",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Rounded.PhoneAndroid,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = deviceModel,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (currentRootStatus) "Android SDK $androidSdk (Root)" else "Android (SDK $androidSdk)",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // ========================================================
            // 3. KOTAK DIAGNOSTIK & QUICK ACTIONS
            // ========================================================
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                hasWallpaper = hasWallpaper
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    // Header Diagnostik Clickable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDiagnostics() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Terminal,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Log Diagnostik Sistem",
                                    color = Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Lihat trace hook, package info & log aktif",
                                    color = Color.White.copy(alpha = 0.70f),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quick Action Buttons (Export, Import, Reset)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Export Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF25D366).copy(alpha = 0.20f))
                                .border(1.dp, Color(0xFF25D366).copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                                .clickable { onExportConfigs() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Ekspor",
                                color = Color(0xFF25D366),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Import Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                                .clickable { onImportConfigs() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Impor",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Reset Button
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFF6262).copy(alpha = 0.18f))
                                .border(1.dp, Color(0xFFFF6262).copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickable { onResetConfigs() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Reset",
                                color = Color(0xFFFF8080),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Kartu Hero Status Utama (Modul LSPosed)
 * Desain bersih, lega, liquid glass murni seperti Remielle Kernel Manager.
 */
@Composable
fun LiquidGlassHeroCard(
    isModuleActive: Boolean,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    hasWallpaper: Boolean = true
) {
    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp)
            .clickable { onOpenDiagnostics() },
        hasWallpaper = hasWallpaper
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Baris Atas: Icon status + Tag status Terhubung / Nonaktif
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isModuleActive) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (isModuleActive) Color(0xFF25D366) else Color(0xFFFF6262),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "Status Modul LSPosed",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isModuleActive) Color(0xFF25D366).copy(alpha = 0.22f) else Color(0xFFFF6262).copy(alpha = 0.22f))
                        .border(1.dp, if (isModuleActive) Color(0xFF25D366).copy(alpha = 0.38f) else Color(0xFFFF6262).copy(alpha = 0.38f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isModuleActive) "TERHUBUNG" else "NONAKTIF",
                        color = if (isModuleActive) Color(0xFF25D366) else Color(0xFFFF6262),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Konten Bawah: Judul Modul Aktif & Deskripsi
            Column {
                Text(
                    text = if (isModuleActive) "Modul Aktif" else "Modul Tidak Aktif",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isModuleActive) "Hook LSPosed berhasil dimuat ke WhatsApp" else "Modul belum diaktifkan di LSPosed Manager",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
