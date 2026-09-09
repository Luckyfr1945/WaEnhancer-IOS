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
            var intentJid: String? = intent?.getStringExtra("jid") ?: intent?.getStringExtra("chat_jid")
            if (intentJid.isNullOrEmpty() && intent?.extras != null) {
                // Scan all extras to find any JID object or string regardless of obfuscated key name
                for (key in intent.extras!!.keySet()) {
                    val extra = intent.extras!!.get(key)
                    if (extra != null) {
                        val str = try {
                            XposedHelpers.callMethod(extra, "getRawString") as? String
                        } catch (_: Throwable) {
                            if (extra is String && (extra.endsWith("@s.whatsapp.net") || extra.endsWith("@g.us") || extra.endsWith("@lid"))) extra else null
                        }
                        if (!str.isNullOrBlank() && (str.contains("@s.whatsapp.net") || str.contains("@g.us") || str.contains("@lid"))) {
                            intentJid = str
                            break
                        }
                    }
                }
            }
            if (!intentJid.isNullOrEmpty()) {
                rawJid = intentJid
                userJid = com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid(intentJid)
            }
        }

        if (rawJid.isNullOrEmpty()) {
            logDebug("JumpFirstMessage: JID not found")
            Utils.showToast("JID not found", 0)
            return
        }

        logDebug("JumpFirstMessage: Target rawJid=$rawJid")

        val firstMessageInfo = getInstance().getFirstMessageInfoByChatRawJid(rawJid)
        if (firstMessageInfo == null) {
            logDebug("JumpFirstMessage: No first message found in DB for $rawJid")
            Utils.showToast("No message found", 0)
            return
        }

        logDebug("JumpFirstMessage: Found firstMessageInfo [rowId=${firstMessageInfo.rowId}, sortId=${firstMessageInfo.sortId}, keyId=${firstMessageInfo.keyId}, fromMe=${firstMessageInfo.fromMe}, chatRowId=${firstMessageInfo.chatRowId}]")

        // 1. Try targeted WhatsApp ConversationScrollApi navigation
        val remoteJidObj = userJid?.userJid ?: userJid?.phoneJid ?: WppCore.createUserJid(rawJid)
        var keyObj: Any? = null
        try {
            val keyConstructors = com.wmods.wppenhacer.xposed.core.components.FMessageWpp.Key.TYPE.constructors
            keyObj = keyConstructors.firstOrNull { it.parameterCount == 3 && it.parameterTypes[1] == String::class.java }
                ?.newInstance(remoteJidObj, firstMessageInfo.keyId, firstMessageInfo.fromMe)
        } catch (e: Throwable) {
            logDebug("JumpFirstMessage: Key construction error: ${e.message}")
        }

        val fMessageObj = if (keyObj != null) WppCore.getFMessageFromKey(keyObj) else null
        val fMessageRaw = if (fMessageObj is com.wmods.wppenhacer.xposed.core.components.FMessageWpp) fMessageObj.getObject() else fMessageObj
        logDebug("JumpFirstMessage: keyObj=${keyObj != null}, fMessageRaw=${fMessageRaw?.javaClass?.name ?: "null"}")

        val delegate = WppCore.getConversationDelegate()
        val scrollApi = findConversationScrollApi(activity, delegate)
        logDebug("JumpFirstMessage: ConversationDelegate=${delegate?.javaClass?.name ?: "null"}, ConversationScrollApi=${scrollApi?.javaClass?.name ?: "null"}")

        if (scrollApi != null && fMessageRaw != null) {
            val fClass = fMessageRaw.javaClass
            val scrollMethod = scrollApi.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 1 && (m.parameterTypes[0].isAssignableFrom(fClass) || fClass.interfaces.any { m.parameterTypes[0].isAssignableFrom(it) })
            }
            if (scrollMethod != null) {
                try {
                    scrollMethod.isAccessible = true
                    scrollMethod.invoke(scrollApi, fMessageRaw)
                    logDebug("JumpFirstMessage: Successfully navigated via ScrollApi.${scrollMethod.name}(FMessage)")
                    return
                } catch (e: Throwable) {
                    logDebug("JumpFirstMessage: ScrollApi invocation failed: ${e.message}")
                }
            }
        }

        logDebug("JumpFirstMessage: ScrollApi navigation not executed, using Intent rebuild & ListView/RecyclerView scroll")

        // Scroll UI immediately to top
        scrollListToTop(activity)

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
                putExtra("extra_quoted_message_row_id", firstMessageInfo.rowId)
                putExtra("extra_center_initial_message", true)
                putExtra("start_t", SystemClock.uptimeMillis())
                putExtra("mat_entry_point", 64)
                putExtra("args_conversation_screen_entry_point", 64)
            }

            activity.intent = intent

            // Deliver intent directly to onNewIntent so WhatsApp reloads the message list at row_id
            try {
                XposedHelpers.callMethod(activity, "onNewIntent", intent)
                logDebug("JumpFirstMessage: Dispatched onNewIntent")
            } catch (_: Throwable) {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activity.startActivity(intent)
                logDebug("JumpFirstMessage: Dispatched startActivity")
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                scrollListToTop(activity)
            }, 100)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                scrollListToTop(activity)
            }, 300)
        } catch (e: Exception) {
            logDebug("JumpFirstMessage error: ${e.message}")
        }
    }

    private fun findConversationScrollApi(activity: Activity, delegate: Any?): Any? {
        // 1. Check getConversationScrollApi() on activity or delegate
        try {
            val actMethod = activity.javaClass.methods.firstOrNull { it.name == "getConversationScrollApi" && it.parameterCount == 0 }
            if (actMethod != null) {
                actMethod.isAccessible = true
                val res = actMethod.invoke(activity)
                if (res != null) return res
            }
        } catch (_: Throwable) {}

        try {
            if (delegate != null) {
                val delMethod = delegate.javaClass.methods.firstOrNull { it.name == "getConversationScrollApi" && it.parameterCount == 0 }
                if (delMethod != null) {
                    delMethod.isAccessible = true
                    val res = delMethod.invoke(delegate)
                    if (res != null) return res
                }
            }
        } catch (_: Throwable) {}

        // 2. Scan fields on delegate for scroll API instance or Lazy/Provider
        if (delegate != null) {
            var cur: Class<*>? = delegate.javaClass
            while (cur != null && cur != Any::class.java) {
                for (f in cur.declaredFields) {
                    try {
                        f.isAccessible = true
                        var obj = f.get(delegate) ?: continue
                        // If it's a Provider/Lazy
                        val getM = obj.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
                        if (getM != null) {
                            try {
                                getM.isAccessible = true
                                val resolved = getM.invoke(obj)
                                if (resolved != null) {
                                    obj = resolved
                                }
                            } catch (_: Throwable) {}
                        }

                        val methods = obj.javaClass.methods
                        val methodNames = methods.map { it.name }.toSet()
                        // Matches LX/3iA or LX/24w interface methods (CIV, CIW, etc.)
                        val isScrollApi = methodNames.contains("CIV") || methodNames.contains("CIW") ||
                                methodNames.contains("C9S") || methodNames.contains("CIT") || methodNames.contains("CPX") ||
                                methods.any { m -> m.parameterCount == 4 && m.parameterTypes[3] == Int::class.javaPrimitiveType }
                        if (isScrollApi) {
                            logDebug("JumpFirstMessage: Found ConversationScrollApi in field ${f.name} (${obj.javaClass.name})")
                            return obj
                        }
                    } catch (_: Throwable) {}
                }
                cur = cur.superclass
            }
        }
        return null
    }

    private fun scrollListToTop(activity: Activity): Boolean {
        try {
            val root = activity.findViewById<android.view.View>(android.R.id.content)
                ?: activity.window?.decorView ?: return false
            val scrollableView = findFirstScrollableView(root)
            if (scrollableView != null) {
                activity.runOnUiThread {
                    when (scrollableView) {
                        is android.widget.ListView -> {
                            logDebug("JumpFirstMessage: Scrolling ListView to index 0")
                            scrollableView.setSelection(0)
                        }
                        is android.widget.AbsListView -> {
                            logDebug("JumpFirstMessage: Scrolling AbsListView to index 0")
                            scrollableView.setSelection(0)
                        }
                        else -> {
                            try {
                                val scrollToPositionMethod = scrollableView.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType)
                                logDebug("JumpFirstMessage: Scrolling RecyclerView via scrollToPosition(0)")
                                scrollToPositionMethod.invoke(scrollableView, 0)
                            } catch (_: Throwable) {
                                try {
                                    val smoothScrollToPositionMethod = scrollableView.javaClass.getMethod("smoothScrollToPosition", Int::class.javaPrimitiveType)
                                    smoothScrollToPositionMethod.invoke(scrollableView, 0)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                return true
            }
        } catch (e: Throwable) {
            logDebug("JumpFirstMessage scrollListToTop error: ${e.message}")
        }
        return false
    }

    private fun findFirstScrollableView(view: android.view.View?): android.view.View? {
        if (view == null) return null
        if (view is android.widget.ListView || view is android.widget.AbsListView) return view
        if (view.javaClass.name.contains("RecyclerView", ignoreCase = true) || view is androidx.recyclerview.widget.RecyclerView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstScrollableView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun getPluginName(): String {
        return "Jump First Message"
    }
}