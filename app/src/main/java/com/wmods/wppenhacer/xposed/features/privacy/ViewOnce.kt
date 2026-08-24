package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.getMethodDescriptor
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadViewOnceMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class ViewOnce(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("viewonce", false)) return

        val methods = try {
            loadViewOnceMethod(classLoader)
        } catch (e: Throwable) {
            logDebug("ViewOnce: method resolver failed: ${e.message}")
            return
        }

        methods.forEach { method ->
            try {
                logDebug(getMethodDescriptor(method))
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val value = param.args.getOrNull(0) as? Int ?: return
                        val fMessage = try {
                            val thisObj = param.thisObject ?: return
                            FMessageWpp(thisObj)
                        } catch (e: Throwable) {
                            logDebug("ViewOnce: invalid message object: ${e.message}")
                            return
                        }

                        if (value == 1 && !fMessage.key.isFromMe) {
                            param.args[0] = 0
                        }
                    }
                })
            } catch (e: Throwable) {
                logDebug("ViewOnce: hook failed: ${e.message}")
            }
        }
    }

    override fun getPluginName(): String {
        return "View Once"
    }
}