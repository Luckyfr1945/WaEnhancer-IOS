package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.Menu
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.WppCore.getCurrentActivity
import com.wmods.wppenhacer.xposed.core.WppCore.getCurrentUserJid
import com.wmods.wppenhacer.xposed.core.db.MessageStore.Companion.getInstance
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadOnCreatedMenuConversation
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class JumpFirstMessage(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("jump_first_message", false)) return
        val onCreateMenuConversationMethod = loadOnCreatedMenuConversation(classLoader)
        XposedBridge.hookMethod(onCreateMenuConversationMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val menu = param.args[0] as Menu
                    if (menu.findItem(R.string.jump_first_message) != null) {
                        return
                    }
                    val menuItem =
                        menu.add(0, R.string.jump_first_message, 0, R.string.jump_first_message)
                    menuItem.setOnMenuItemClickListener {
                        val activity =
                            getCurrentActivity() ?: return@setOnMenuItemClickListener false
                        jumpToFirstMessage(activity)
                        true
                    }
                } catch (e: Exception) {
                    logDebug(e)
                }
            }
        })
    }

    private fun jumpToFirstMessage(activity: Activity) {
        var userJid = getCurrentUserJid()
        var rawJid = userJid?.rawJidString?.takeIf { it.isNotEmpty() }
            ?: userJid?.phoneRawString?.takeIf { it.isNotEmpty() }
            ?: userJid?.userRawString?.takeIf { it.isNotEmpty() }

        // Fallback: extract JID directly from current Activity intent if getCurrentUserJid is empty
        if (rawJid.isNullOrEmpty()) {
            val intent = activity.intent
            val intentJid = intent?.getStringExtra("jid")
                ?: intent?.getStringExtra("chat_jid")
                ?: (intent?.getParcelableExtra<android.os.Parcelable>("jid")?.let {
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(it, "getRawString") as? String
                    } catch (_: Throwable) { null }
                })
                ?: (intent?.getParcelableExtra<android.os.Parcelable>("chat_jid")?.let {
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(it, "getRawString") as? String
                    } catch (_: Throwable) { null }
                })
            if (!intentJid.isNullOrEmpty()) {
                rawJid = intentJid
                userJid = com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid(intentJid)
            }
        }

        if (rawJid.isNullOrEmpty()) {
            XposedBridge.log("[WaEnhancer] JumpFirstMessage: JID not found")
            Utils.showToast("JID not found", 0)
            return
        }

        val firstMessageInfo = getInstance().getFirstMessageInfoByChatRawJid(rawJid)
        if (firstMessageInfo == null) {
            XposedBridge.log("[WaEnhancer] JumpFirstMessage: No first message found for $rawJid")
            Utils.showToast("No message found", 0)
            return
        }

        // Try direct ConversationDelegate message navigation if available
        val delegate = WppCore.getConversationDelegate()
        if (delegate != null) {
            val remoteJidObj = userJid?.userJid ?: userJid?.phoneJid ?: WppCore.createUserJid(rawJid)
            var keyObj: Any? = null
            try {
                val keyConstructors = com.wmods.wppenhacer.xposed.core.components.FMessageWpp.Key.TYPE.constructors
                keyObj = keyConstructors.firstOrNull { it.parameterCount == 3 && it.parameterTypes[1] == String::class.java }
                    ?.newInstance(remoteJidObj, firstMessageInfo.keyId, firstMessageInfo.fromMe)
            } catch (_: Throwable) {}

            val fMessageObj = if (keyObj != null) WppCore.getFMessageFromKey(keyObj) else null

            // 1. Try navigation with FMessage object
            if (fMessageObj != null) {
                val fClass = (fMessageObj as Any).javaClass
                val methods = delegate.javaClass.declaredMethods.filter { m ->
                    m.parameterCount in 1..2 && m.parameterTypes[0].isAssignableFrom(fClass)
                }
                for (m in methods) {
                    try {
                        m.isAccessible = true
                        if (m.parameterCount == 1) {
                            m.invoke(delegate, fMessageObj)
                            return
                        } else if (m.parameterCount == 2 && m.parameterTypes[1] == Int::class.javaPrimitiveType) {
                            m.invoke(delegate, fMessageObj, 0)
                            return
                        }
                    } catch (_: Throwable) {}
                }
            }

            // 2. Try navigation with Key object
            if (keyObj != null) {
                val kClass = (keyObj as Any).javaClass
                val keyMethods = delegate.javaClass.declaredMethods.filter { m ->
                    m.parameterCount == 1 && m.parameterTypes[0].isAssignableFrom(kClass)
                }
                for (m in keyMethods) {
                    try {
                        m.isAccessible = true
                        m.invoke(delegate, keyObj)
                        return
                    } catch (_: Throwable) {}
                }
            }

            // 3. Try navigation with row_id
            val longMethods = delegate.javaClass.declaredMethods.filter { m ->
                m.parameterCount in 1..2 && (m.parameterTypes[0] == Long::class.javaPrimitiveType || m.parameterTypes[0] == Long::class.java)
            }
            for (m in longMethods) {
                try {
                    m.isAccessible = true
                    if (m.parameterCount == 1) {
                        m.invoke(delegate, firstMessageInfo.rowId)
                    } else if (m.parameterCount == 2 && m.parameterTypes[1] == Boolean::class.javaPrimitiveType) {
                        m.invoke(delegate, firstMessageInfo.rowId, true)
                    }
                } catch (_: Throwable) {}
            }
        }

        // Scroll UI immediately to top
        scrollListViewToTop(activity)

        try {
            val intent = Intent(activity, activity.javaClass).apply {
                action = Intent.ACTION_VIEW

                // Copy existing intent extras to preserve WhatsApp internals (like ChatJid parcelable)
                activity.intent?.extras?.let { putExtras(it) }

                // Set targeting intent parameters for WhatsApp message scrolling
                if (userJid?.phoneJid is android.os.Parcelable) {
                    putExtra("jid", userJid.phoneJid as android.os.Parcelable)
                    putExtra("chat_jid", userJid.phoneJid as android.os.Parcelable)
                } else if (userJid?.userJid is android.os.Parcelable) {
                    putExtra("jid", userJid.userJid as android.os.Parcelable)
                    putExtra("chat_jid", userJid.userJid as android.os.Parcelable)
                } else {
                    putExtra("jid", rawJid)
                    putExtra("chat_jid", rawJid)
                }

                val effectiveSortId = if (firstMessageInfo.sortId > 0) firstMessageInfo.sortId else firstMessageInfo.rowId
                putExtra("sort_id", effectiveSortId)
                putExtra("row_id", firstMessageInfo.rowId)
                putExtra("start_t", SystemClock.uptimeMillis())
                putExtra("mat_entry_point", 64)
                putExtra("args_conversation_screen_entry_point", 64)
            }

            activity.intent = intent

            // Deliver intent directly to onNewIntent so WhatsApp reloads the message list at row_id
            try {
                XposedHelpers.callMethod(activity, "onNewIntent", intent)
            } catch (_: Throwable) {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activity.startActivity(intent)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                scrollListViewToTop(activity)
            }, 100)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                scrollListViewToTop(activity)
            }, 300)
        } catch (e: Exception) {
            XposedBridge.log("[WaEnhancer] JumpFirstMessage error: ${e.message}")
        }
    }

    private fun scrollListViewToTop(activity: Activity): Boolean {
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content)
                ?: activity.window?.decorView ?: return false
            val listView = findFirstListView(root)
            if (listView != null) {
                activity.runOnUiThread {
                    listView.setSelection(0)
                }
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun findFirstListView(view: android.view.View?): android.widget.ListView? {
        if (view == null) return null
        if (view is android.widget.ListView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstListView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun getPluginName(): String {
        return "Jump First Message"
    }
}