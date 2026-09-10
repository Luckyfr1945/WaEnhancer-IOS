package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.getPrivBoolean
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.getMethodDescriptor
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadDndModeMethod
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class DndMode(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun doHook() {
        try {
            val dndMethod = loadDndModeMethod(classLoader)
            logDebug(getMethodDescriptor(dndMethod))
            XposedBridge.hookMethod(dndMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val isDnd = getPrivBoolean("dndmode", false) || prefs.getBoolean("dndmode", false)
                    if (isDnd) {
                        ReflectionUtils.blockMethodExecution(param)
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("DndMode: failed to hook dndMethod: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Dnd Mode"
    }
}
