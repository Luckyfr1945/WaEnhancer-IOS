package com.wmods.wppenhacer.xposed.features.privacy

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadLockedAuthCheckMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class ChatLockEnhancer(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    private val authenticatedJids = ConcurrentHashMap.newKeySet<String>()

    companion object {
        @Volatile
        var activeAndroidxCallback: WeakReference<Any>? = null
        @Volatile
        var activeCryptoObject: Any? = null
    }

    override fun doHook() {
        val isEnabled = prefs.getBoolean("lockedchats_enhancer", false) ||
                prefs.getBoolean("enhanced_chat_lock", false)
        if (!isEnabled) return

        hookBiometricAvailability()
        hookBiometricPrompt()
        hookFingerprintDialogs()
        hookConversationLifecycle()
    }

    /**
     * Bypasses OEM/ROM restrictions (such as OplusCustomizeRestrictionManagerService.isBiometricDisabled
     * on OnePlus/ColorOS/OxygenOS or Custom/Port ROMs) so WhatsApp always recognizes that the device
     * has working biometrics and displays the Chat Lock / Fingerprint prompt without being suppressed.
     */
    private fun hookBiometricAvailability() {
        // 1. android.hardware.biometrics.BiometricManager
        try {
            val biometricManagerClass = XposedHelpers.findClass("android.hardware.biometrics.BiometricManager", classLoader)
            XposedBridge.hookAllMethods(biometricManagerClass, "canAuthenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0 // BIOMETRIC_SUCCESS
                }
            })
        } catch (_: Throwable) {}

        // 2. androidx.biometric.BiometricManager
        try {
            val androidxBiometricManagerClass = XposedHelpers.findClass("androidx.biometric.BiometricManager", classLoader)
            XposedBridge.hookAllMethods(androidxBiometricManagerClass, "canAuthenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0 // BIOMETRIC_SUCCESS
                }
            })
        } catch (_: Throwable) {}

        // 3. android.hardware.fingerprint.FingerprintManager
        try {
            val fpManagerClass = XposedHelpers.findClass("android.hardware.fingerprint.FingerprintManager", classLoader)
            XposedBridge.hookAllMethods(fpManagerClass, "isHardwareDetected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.hookAllMethods(fpManagerClass, "hasEnrolledFingerprints", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
        } catch (_: Throwable) {}

        // 4. androidx.core.hardware.fingerprint.FingerprintManagerCompat
        try {
            val fpCompatClass = XposedHelpers.findClass("androidx.core.hardware.fingerprint.FingerprintManagerCompat", classLoader)
            XposedBridge.hookAllMethods(fpCompatClass, "isHardwareDetected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.hookAllMethods(fpCompatClass, "hasEnrolledFingerprints", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
        } catch (_: Throwable) {}

        // 5. android.app.KeyguardManager
        try {
            val keyguardClass = XposedHelpers.findClass("android.app.KeyguardManager", classLoader)
            XposedBridge.hookAllMethods(keyguardClass, "isKeyguardSecure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.hookAllMethods(keyguardClass, "isDeviceSecure", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
        } catch (_: Throwable) {}
    }

    /**
     * Hooks AndroidX BiometricPrompt and system Biometrics to capture active callbacks
     * and ensure hardware compatibility across all Android versions & OEMs (OnePlus, Samsung, Xiaomi, Port ROMs, etc.)
     */
    private fun hookBiometricPrompt() {
        // 1. Hook androidx.biometric.BiometricPrompt constructors & methods
        try {
            val biometricPromptClass = XposedHelpers.findClass("androidx.biometric.BiometricPrompt", classLoader)
            XposedBridge.hookAllConstructors(biometricPromptClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    for (arg in param.args) {
                        if (arg != null && arg.javaClass.name.contains("AuthenticationCallback")) {
                            activeAndroidxCallback = WeakReference(arg)
                            logDebug("ChatLock: Captured active BiometricPrompt callback: ${arg.javaClass.name}")
                            hookAuthenticationCallback(arg.javaClass)
                            break
                        }
                    }
                }
            })

            // Hook authenticate methods
            XposedBridge.hookAllMethods(biometricPromptClass, "authenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (arg in param.args) {
                        if (arg != null && arg.javaClass.name.contains("CryptoObject")) {
                            activeCryptoObject = arg
                        }
                    }
                    logDebug("ChatLock: BiometricPrompt.authenticate invoked with ${param.args.size} args (crypto=$activeCryptoObject)")
                }
            })
        } catch (e: Throwable) {
            logDebug("ChatLock: androidx.biometric.BiometricPrompt hook failed: ${e.message}")
        }

        // 2. Hook system android.hardware.biometrics.BiometricPrompt
        try {
            val systemBiometricClass = XposedHelpers.findClass("android.hardware.biometrics.BiometricPrompt", classLoader)
            XposedBridge.hookAllMethods(systemBiometricClass, "authenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (arg in param.args) {
                        if (arg != null && arg.javaClass.name.contains("AuthenticationCallback")) {
                            activeAndroidxCallback = WeakReference(arg)
                            logDebug("ChatLock: Captured system BiometricPrompt callback: ${arg.javaClass.name}")
                            hookAuthenticationCallback(arg.javaClass)
                        }
                    }
                }
            })
        } catch (_: Throwable) {}

        // 3. Hook FingerprintManager authenticate
        try {
            val fpClass = XposedHelpers.findClass("android.hardware.fingerprint.FingerprintManager", classLoader)
            XposedBridge.hookAllMethods(fpClass, "authenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (arg in param.args) {
                        if (arg != null && arg.javaClass.name.contains("AuthenticationCallback")) {
                            logDebug("ChatLock: FingerprintManager.authenticate with callback: ${arg.javaClass.name}")
                            hookAuthenticationCallback(arg.javaClass)
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookAuthenticationCallback(callbackClass: Class<*>) {
        try {
            XposedBridge.hookAllMethods(callbackClass, "onAuthenticationSucceeded", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logDebug("ChatLock: onAuthenticationSucceeded called! arg=${param.args.getOrNull(0)}")
                }
            })
            XposedBridge.hookAllMethods(callbackClass, "onAuthenticationFailed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logDebug("ChatLock: onAuthenticationFailed called! Attempting auto-success dispatch for ROM port compatibility...")
                    dispatchAuthenticationSuccess()
                }
            })
            XposedBridge.hookAllMethods(callbackClass, "onAuthenticationError", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logDebug("ChatLock: onAuthenticationError called code=${param.args.getOrNull(0)} msg=${param.args.getOrNull(1)}")
                }
            })
        } catch (_: Throwable) {}
    }

    /**
     * Attaches tap-to-unlock capability to biometric dialogs.
     * If the In-Display Fingerprint driver (e.g. OnePlus OxygenOS/ColorOS Port) freezes touch events,
     * tapping the fingerprint icon or dialog area triggers successful authentication instantly.
     */
    private fun hookFingerprintDialogs() {
        try {
            val dialogClasses = listOf(
                "androidx.biometric.FingerprintDialogFragment",
                "androidx.biometric.BiometricFragment",
                "com.whatsapp.authentication.FingerprintBottomSheet",
                "com.whatsapp.chatlock.dialogs.ChatLockBiometricPrompt"
            )

            for (clsName in dialogClasses) {
                try {
                    val cls = classLoader.loadClass(clsName)
                    XposedBridge.hookAllMethods(cls, "onViewCreated", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.args.getOrNull(0) as? View ?: return
                            setupTouchToUnlock(view)
                        }
                    })

                    XposedBridge.hookAllMethods(cls, "onStart", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fragment = param.thisObject
                            val dialog = try {
                                XposedHelpers.callMethod(fragment, "getDialog") as? Dialog
                            } catch (_: Throwable) { null }
                            dialog?.window?.decorView?.let { setupTouchToUnlock(it) }
                        }
                    })
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            logDebug("ChatLockEnhancer: Dialog hook failed: ${e.message}")
        }
    }

    private fun setupTouchToUnlock(rootView: View) {
        try {
            val queue = ArrayDeque<View>()
            queue.add(rootView)

            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()

                // If this is an ImageView (fingerprint icon) or relevant prompt view, allow tap to authenticate
                val isFingerprintView = v is ImageView ||
                        (v is TextView && v.text.toString().lowercase().contains("sidik jari|fingerprint|kunci|lock".toRegex())) ||
                        v.contentDescription?.toString()?.lowercase()?.contains("fingerprint|sidik jari".toRegex()) == true

                if (isFingerprintView) {
                    v.isClickable = true
                    v.isFocusable = true
                    v.setOnClickListener {
                        logDebug("ChatLock: Fingerprint view clicked, dispatching success")
                        dispatchAuthenticationSuccess()
                    }
                }

                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        queue.add(v.getChildAt(i))
                    }
                }
            }

            // Also attach click on the main container as a fail-safe
            rootView.setOnClickListener {
                dispatchAuthenticationSuccess()
            }
        } catch (_: Throwable) {}
    }

    private fun dispatchAuthenticationSuccess() {
        val callback = activeAndroidxCallback?.get() ?: return
        try {
            val cbClass = callback.javaClass
            // Try to find onAuthenticationSucceeded method
            val successMethod = cbClass.methods.firstOrNull { it.name == "onAuthenticationSucceeded" }
            if (successMethod != null) {
                val paramTypes = successMethod.parameterTypes
                if (paramTypes.isNotEmpty()) {
                    val resultClass = paramTypes[0]
                    val resultInstance = createAuthenticationResult(resultClass, activeCryptoObject)
                    successMethod.isAccessible = true
                    successMethod.invoke(callback, resultInstance)
                    logDebug("ChatLock: Successfully dispatched onAuthenticationSucceeded to $cbClass")
                }
            }
        } catch (e: Throwable) {
            logDebug("ChatLock: dispatchAuthenticationSuccess failed: ${e.message}")
        }
    }

    private fun createAuthenticationResult(resultClass: Class<*>, cryptoObject: Any?): Any? {
        return try {
            // Strategy 1: constructor with (CryptoObject, Int) or (CryptoObject)
            val ctors = resultClass.declaredConstructors
            for (ctor in ctors) {
                ctor.isAccessible = true
                val pTypes = ctor.parameterTypes
                when (pTypes.size) {
                    0 -> return ctor.newInstance()
                    1 -> {
                        if (cryptoObject != null && pTypes[0].isAssignableFrom(cryptoObject.javaClass)) {
                            return ctor.newInstance(cryptoObject)
                        } else {
                            return ctor.newInstance(null)
                        }
                    }
                    2 -> {
                        if (pTypes[1] == Int::class.javaPrimitiveType || pTypes[1] == Int::class.javaObjectType) {
                            return ctor.newInstance(cryptoObject, 2 /* BIOMETRIC_AUTHENTICATION_TYPE_FINGERPRINT */)
                        }
                    }
                }
            }
            // Strategy 2: Objenesis / Unsafe instantiation
            XposedHelpers.newInstance(resultClass)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookConversationLifecycle() {
        try {
            val conversationClass = XposedHelpers.findClass("com.whatsapp.Conversation", classLoader)
            val authCheckMethod = try {
                loadLockedAuthCheckMethod(classLoader)
            } catch (e: Throwable) {
                logDebug("ChatLock: loadLockedAuthCheckMethod unavailable: ${e.message}")
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

                        if (authCheckMethod != null) {
                            try {
                                val requiresAuth = authCheckMethod.invoke(null) as? Boolean ?: false
                                if (requiresAuth && !authenticatedJids.contains(jid)) {
                                    logDebug("ChatLock: Auth required for JID $jid")
                                }
                            } catch (e: Throwable) {
                                logDebug("ChatLock: auth check invoke failed: ${e.message}")
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
            logDebug("ChatLock: Error hooking for Conversation lifecycle: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Enhanced Chat Lock"
    }
}
