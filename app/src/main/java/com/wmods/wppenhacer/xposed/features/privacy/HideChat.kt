package com.wmods.wppenhacer.xposed.features.privacy

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class HideChat(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun doHook() {
        if (prefs.getString("typearchive", "0") != "0") {
            try {
                val loadArchiveChatClass = Unobfuscator.loadArchiveChatClass(classLoader)
                val viewField = ReflectionUtils.getFieldByType(loadArchiveChatClass, View::class.java)
                if (viewField == null) {
                    logDebug("HideChat: viewField not found")
                    return
                }

                XposedBridge.hookAllConstructors(loadArchiveChatClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val targetObj = param.thisObject ?: return
                        val existingView = try {
                            viewField.get(targetObj) as? View
                        } catch (_: Throwable) {
                            null
                        }
                        val context = existingView?.context ?: WppCore.getCurrentActivity() ?: Utils.application
                        try {
                            viewField.set(targetObj, HideView(context))
                        } catch (e: Throwable) {
                            logDebug("HideChat: set HideView failed: ${e.message}")
                        }
                    }
                })
            } catch (e: Throwable) {
                logDebug("HideChat: hook failed: ${e.message}")
            }
        }
    }

    override fun getPluginName(): String {
        return "Hide Chats"
    }

    class HideView(context: Context) : View(context) {
        init {
            visibility = GONE
        }

        override fun setVisibility(visibility: Int) {
            // Permanently lock visibility to GONE
        }
    }
}