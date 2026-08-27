package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadLockedAuthCheckMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

class ChatLockEnhancer(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    private val authenticatedJids = ConcurrentHashMap.newKeySet<String>()

    override fun doHook() {
        val isEnabled = prefs.getBoolean("lockedchats_enhancer", false) ||
                prefs.getBoolean("enhanced_chat_lock", false)
        if (!isEnabled) return

        try {
            val conversationClass = XposedHelpers.findClass("com.whatsapp.Conversation", classLoader)
            val authCheckMethod = try {
                loadLockedAuthCheckMethod(classLoader)
            } catch (e: Throwable) {
                logDebug("ChatLockEnhancer: loadLockedAuthCheckMethod unavailable: ${e.message}")
                null
            }

            XposedHelpers.findAndHookMethod(
                conversationClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = activity.intent ?: return
                        val jid = intent.getStringExtra("jid") ?: return

                        logDebug("ChatLockEnhancer: Conversation opening for JID $jid")

                        if (authCheckMethod != null) {
                            try {
                                val requiresAuth = authCheckMethod.invoke(null) as? Boolean ?: false
                                if (requiresAuth && !authenticatedJids.contains(jid)) {
                                    logDebug("ChatLockEnhancer: Auth required for JID $jid")
                                }
                            } catch (e: Throwable) {
                                logDebug("ChatLockEnhancer: auth check invoke failed: ${e.message}")
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                conversationClass,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = activity.intent ?: return
                        val jid = intent.getStringExtra("jid") ?: return
                        authenticatedJids.remove(jid)
                    }
                }
            )
        } catch (e: Throwable) {
            logDebug("Error hooking for ChatLockEnhancer: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Enhanced Chat Lock"
    }
}

