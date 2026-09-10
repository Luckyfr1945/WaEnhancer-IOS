package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.WppCore.getPrivBoolean
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadFreezeSeenMethod
import de.robv.android.xposed.XposedBridge

class FreezeLastSeen(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    override fun doHook() {
        try {
            val method = loadFreezeSeenMethod(classLoader)
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val freezeLastSeenMain = prefs.getBoolean("freezelastseen", false)
                    val showFreezeOption = prefs.getBoolean("show_freezeLastSeen", true)
                    val freezeLastSeenPriv = getPrivBoolean("freezelastseen", false)
                    val ghostmode = getPrivBoolean("ghostmode", false) && prefs.getBoolean("ghostmode", false)

                    // Sync main toggle to in-app priv flag
                    if (freezeLastSeenMain && !freezeLastSeenPriv && showFreezeOption) {
                        WppCore.setPrivBoolean("freezelastseen", true)
                    } else if (!freezeLastSeenMain && freezeLastSeenPriv) {
                        WppCore.setPrivBoolean("freezelastseen", false)
                    }

                    val isFrozen = freezeLastSeenMain || (showFreezeOption && freezeLastSeenPriv) || ghostmode

                    XposedBridge.log("[WaEnhancer] FreezeLastSeen: isFrozen=$isFrozen (main=$freezeLastSeenMain, priv=$freezeLastSeenPriv, ghost=$ghostmode)")

                    if (isFrozen) {
                        val returnType = (param.method as? java.lang.reflect.Method)?.returnType
                        param.result = when (returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            java.lang.Void.TYPE -> null
                            else -> null
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("FreezeLastSeen: hook failed: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Freeze Last Seen"
    }
}
