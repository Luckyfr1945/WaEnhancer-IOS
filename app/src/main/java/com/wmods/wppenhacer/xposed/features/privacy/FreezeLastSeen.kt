package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.getPrivBoolean
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadFreezeSeenMethod
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XposedBridge

class FreezeLastSeen(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    override fun doHook() {
        val freezeLastSeen = prefs.getBoolean("freezelastseen", false)
        val freezeLastSeenOption = getPrivBoolean("freezelastseen", false)
        val ghostmode = getPrivBoolean("ghostmode", false) && prefs.getBoolean("ghostmode", false)

        if (freezeLastSeen || freezeLastSeenOption || ghostmode) {
            try {
                val method = loadFreezeSeenMethod(classLoader)
                XposedBridge.hookMethod(method, ReflectionUtils.DO_NOTHING)
            } catch (e: Throwable) {
                logDebug("FreezeLastSeen: hook failed: ${e.message}")
            }
        }
    }

    override fun getPluginName(): String {
        return "Freeze Last Seen"
    }
}
