package com.wmods.wppenhacer.xposed.features.privacy

import android.content.ContentResolver
import android.provider.Settings
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadCheckCustomRom
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadCheckEmulator
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadRootDetector
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import java.io.File

class AntiWa(classLoader: ClassLoader, preferences:SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("bootloader_spoofer", false)) return
        val rootDetector = loadRootDetector(classLoader)
        for (detector in rootDetector) {
            if (detector.returnType == java.lang.Boolean.TYPE || detector.returnType == java.lang.Boolean::class.java) {
                XposedBridge.hookMethod(detector, XC_MethodReplacement.returnConstant(false))
            } else {
                XposedBridge.hookMethod(detector, ReflectionUtils.DO_NOTHING)
            }
        }
        val settingsGetInt = Settings.Global::class.java.getDeclaredMethod(
            "getInt",
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType
        )
        XposedBridge.hookMethod(settingsGetInt, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[1] as String
                if (key == "adb_enabled") {
                    param.setResult(0)
                }
            }
        })
        val checkEmulator = loadCheckEmulator(classLoader)
        if (checkEmulator.returnType == java.lang.Boolean.TYPE || checkEmulator.returnType == java.lang.Boolean::class.java) {
            XposedBridge.hookMethod(checkEmulator, XC_MethodReplacement.returnConstant(false))
        } else {
            XposedBridge.hookMethod(checkEmulator, ReflectionUtils.DO_NOTHING)
        }
        // File Check
        val FileConstructor = File::class.java.getConstructor(String::class.java)
        XposedBridge.hookMethod(FileConstructor, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = param.args[0] as String
                val fakePath = "/data/fakepath"
                if (path.contains("qemu") || path.contains("superuser")) {
                    param.args[0] = fakePath
                }
            }
        })

        val checkCustomRom = loadCheckCustomRom(classLoader)
        if (checkCustomRom.returnType == java.lang.Boolean.TYPE || checkCustomRom.returnType == java.lang.Boolean::class.java) {
            XposedBridge.hookMethod(checkCustomRom, XC_MethodReplacement.returnConstant(false))
        } else {
            XposedBridge.hookMethod(checkCustomRom, ReflectionUtils.DO_NOTHING)
        }
    }

    override fun getPluginName(): String {
        return "AntiDetector"
    }
}
