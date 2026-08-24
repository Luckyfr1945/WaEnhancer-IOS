package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import com.wmods.wppenhacer.xposed.core.Feature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

class ChatLockEnhancer(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("enhanced_chat_lock", false)) return

        try {
            // Prototype wrapper: Intercepting Conversation activity onCreate to monitor chat opening
            val conversationClass = XposedHelpers.findClass("com.whatsapp.Conversation", classLoader)
            
            XposedHelpers.findAndHookMethod(
                conversationClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = activity.intent ?: return
                        val jid = intent.getStringExtra("jid")
                        
                        logDebug("Enhanced Chat Lock: Conversation opened for JID $jid")
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
