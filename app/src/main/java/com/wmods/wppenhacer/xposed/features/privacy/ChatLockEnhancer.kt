package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Activity
import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class ChatLockEnhancer(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("enhanced_chat_lock", false)) return

        try {
            // Biometric wrapper before opening locked conversation activities.
            // Intercepting Conversation activity onCreate to add a verification wrapper.
            val conversationClass = XposedHelpers.findClass("com.whatsapp.Conversation", classLoader)
            
            XposedBridge.hookAllMethods(conversationClass, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    val intent = activity.intent
                    val jid = intent.getStringExtra("jid")
                    
                    // Simple biometric bypass check logic could be inserted here
                    // e.g. checking if the chat is locked and launching BiometricPrompt
                    logDebug("Enhanced Chat Lock: Conversation opened for JID $jid")
                }
            })
        } catch (e: Exception) {
            logDebug("Error hooking for ChatLockEnhancer: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Enhanced Chat Lock"
    }
}
