package com.wmods.wppenhacer.ui.home.compose

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.wmods.wppenhacer.activities.MainActivity

object HomeComposeRenderer {
    @JvmStatic
    fun createHomeView(
        context: Context,
        isModuleActive: Boolean,
        isRootGranted: Boolean,
        wppVersion: String,
        w4bVersion: String,
        deviceModel: String,
        androidSdk: Int,
        onRestartWpp: Runnable,
        onRestartW4b: Runnable,
        onOpenDiagnostics: Runnable,
        onExportConfigs: Runnable,
        onImportConfigs: Runnable,
        onResetConfigs: Runnable
    ): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HomeComposeScreen(
                    isModuleActive = isModuleActive,
                    isRootGranted = isRootGranted,
                    wppVersion = wppVersion,
                    w4bVersion = w4bVersion,
                    deviceModel = deviceModel,
                    androidSdk = androidSdk,
                    initialWallpaperBitmap = MainActivity.customWallpaperBitmap,
                    onRestartWpp = { onRestartWpp.run() },
                    onRestartW4b = { onRestartW4b.run() },
                    onOpenDiagnostics = { onOpenDiagnostics.run() },
                    onExportConfigs = { onExportConfigs.run() },
                    onImportConfigs = { onImportConfigs.run() },
                    onResetConfigs = { onResetConfigs.run() }
                )
            }
        }
    }
}