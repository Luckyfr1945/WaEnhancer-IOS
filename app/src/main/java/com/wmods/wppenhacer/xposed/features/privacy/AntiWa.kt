package com.wmods.wppenhacer.xposed.features.privacy

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.Settings
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadCheckCustomRom
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadCheckEmulator
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadRootDetector
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.io.File

class AntiWa(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("bootloader_spoofer", false)) return

        try {
            val rootDetectors = loadRootDetector(classLoader)
            for (detector in rootDetectors) {
                try {
                    if (detector.returnType == java.lang.Boolean.TYPE || detector.returnType == java.lang.Boolean::class.java) {
                        XposedBridge.hookMethod(detector, XC_MethodReplacement.returnConstant(false))
                    } else {
                        XposedBridge.hookMethod(detector, ReflectionUtils.DO_NOTHING)
                    }
                } catch (e: Throwable) {
                    logDebug("AntiDetector: rootDetector item hook failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            logDebug("AntiDetector: loadRootDetector failed: ${e.message}")
        }

        try {
            val settingsGetInt = Settings.Global::class.java.getDeclaredMethod(
                "getInt",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(settingsGetInt, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(1) as? String ?: return
                    if (key == "adb_enabled") {
                        param.setResult(0)
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("AntiDetector: settingsGetInt hook failed: ${e.message}")
        }

        try {
            val checkEmulator = loadCheckEmulator(classLoader)
            if (checkEmulator.returnType == java.lang.Boolean.TYPE || checkEmulator.returnType == java.lang.Boolean::class.java) {
                XposedBridge.hookMethod(checkEmulator, XC_MethodReplacement.returnConstant(false))
            } else {
                XposedBridge.hookMethod(checkEmulator, ReflectionUtils.DO_NOTHING)
            }
        } catch (e: Throwable) {
            logDebug("AntiDetector: checkEmulator hook failed: ${e.message}")
        }

        // File Check
        try {
            val fileConstructor = File::class.java.getConstructor(String::class.java)
            XposedBridge.hookMethod(fileConstructor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0) as? String ?: return
                    if (path.contains("qemu", ignoreCase = true) || path.contains("superuser", ignoreCase = true)) {
                        param.args[0] = "/system/non_existent_path"
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("AntiDetector: fileConstructor hook failed: ${e.message}")
        }

        try {
            val checkCustomRom = loadCheckCustomRom(classLoader)
            if (checkCustomRom.returnType == java.lang.Boolean.TYPE || checkCustomRom.returnType == java.lang.Boolean::class.java) {
                XposedBridge.hookMethod(checkCustomRom, XC_MethodReplacement.returnConstant(false))
            } else {
                XposedBridge.hookMethod(checkCustomRom, ReflectionUtils.DO_NOTHING)
            }
        } catch (e: Throwable) {
            logDebug("AntiDetector: checkCustomRom hook failed: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "AntiDetector"
    }
}
