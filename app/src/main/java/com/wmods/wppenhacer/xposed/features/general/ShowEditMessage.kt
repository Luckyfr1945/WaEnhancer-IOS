package com.wmods.wppenhacer.xposed.features.general

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.adapter.MessageAdapter
import com.wmods.wppenhacer.views.NoScrollListView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore.MessageItem
import com.wmods.wppenhacer.xposed.core.db.MessageStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadCallerMessageEditMethod
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadMessageEditMethod
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener.OnConversationItemListener
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class ShowEditMessage(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private val messageStringMethodCache = ConcurrentHashMap<Class<*>, Method>()
    }

    override fun doHook() {
        if (!prefs.getBoolean("antieditmessages", false)) return

        val onMessageEdit = try {
            loadMessageEditMethod(classLoader)
        } catch (e: Throwable) {
            logDebug("ShowEditMessage: loadMessageEditMethod failed: ${e.message}")
            null
        } ?: return

        val callerMessageEditMethod = try {
            loadCallerMessageEditMethod(classLoader)
        } catch (e: Throwable) {
            logDebug("ShowEditMessage: loadCallerMessageEditMethod failed: ${e.message}")
            null
        }

        XposedBridge.hookMethod(onMessageEdit, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val rawMsgObj = param.args.getOrNull(0) ?: return

                var timestamp: Long = System.currentTimeMillis()
                if (callerMessageEditMethod != null) {
                    try {
                        val invoked = callerMessageEditMethod.invoke(null, rawMsgObj)
                        if (invoked != null) {
                            val rawTime = try {
                                XposedHelpers.getLongField(invoked, "A00")
                            } catch (_: Throwable) {
                                // Scan declared fields + superclasses for a realistic Unix epoch timestamp (ms or s)
                                var foundTime: Long? = null
                                var currClass: Class<*>? = invoked.javaClass
                                while (currClass != null && currClass != Any::class.java) {
                                    for (f in currClass.declaredFields) {
                                        if (f.type == Long::class.javaPrimitiveType || f.type == Long::class.java) {
                                            f.isAccessible = true
                                            val v = try { f.getLong(invoked) } catch (_: Throwable) { continue }
                                            if (v in 1_577_836_800_000L..2_524_608_000_000L) { // 2020 - 2050 ms
                                                foundTime = v
                                                break
                                            } else if (v in 1_577_836_800L..2_524_608_000L) { // 2020 - 2050 s
                                                foundTime = v * 1000L
                                                break
                                            }
                                        }
                                    }
                                    if (foundTime != null) break
                                    currClass = currClass.superclass
                                }
                                foundTime ?: System.currentTimeMillis()
                            }
                            if (rawTime > 0) {
                                timestamp = if (rawTime < 10_000_000_000L) rawTime * 1000L else rawTime
                            }
                        }
                    } catch (e: Throwable) {
                        logDebug("callerMessageEditMethod invoke failed: ${e.message}")
                    }
                }

                val fMessage = FMessageWpp(rawMsgObj)
                val id = fMessage.rowId
                val origMessage = MessageStore.getInstance().getCurrentMessageByID(id)
                var newMessage = fMessage.messageStr

                if (newMessage == null) {
                    val msgClass = rawMsgObj.javaClass
                    val cachedMethod = messageStringMethodCache[msgClass]
                    if (cachedMethod != null) {
                        newMessage = try { cachedMethod.invoke(rawMsgObj) as? String } catch (_: Throwable) { null }
                    } else {
                        val methods = ReflectionUtils.findAllMethodsUsingFilter(msgClass) { method ->
                            method.returnType == String::class.java && ReflectionUtils.isOverridden(method)
                        }
                        for (method in methods) {
                            val res = try { method.invoke(rawMsgObj) as? String } catch (_: Throwable) { null }
                            if (res != null) {
                                newMessage = res
                                messageStringMethodCache[msgClass] = method
                                break
                            }
                        }
                    }
                    if (newMessage == null) return
                }

                try {
                    val historyStore = MessageHistoryStore.getInstance()
                    val existingHistory = historyStore.getMessages(id)

                    if (existingHistory == null) {
                        // Baseline: record the original message first if it exists and differs from the edited version
                        if (!origMessage.isNullOrEmpty() && origMessage != newMessage) {
                            historyStore.insertMessage(id, origMessage, 0L)
                        }
                        historyStore.insertMessage(id, newMessage, timestamp)
                    } else {
                        // Deduplicate: avoid re-inserting identical message + timestamp
                        val isDuplicate = existingHistory.any { it.timestamp == timestamp && it.message == newMessage }
                        if (!isDuplicate) {
                            historyStore.insertMessage(id, newMessage, timestamp)
                        }
                    }
                } catch (e: Exception) {
                    logDebug(e)
                }
            }
        })

        ConversationItemListener.conversationListeners.add(
            object : OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?
                ) {
                    val textView =
                        view.findViewById<View?>(Utils.getID("edit_label", "id")) as TextView?
                    if (textView != null) {
                        textView.paint.isUnderlineText = true
                        val messageId = fMessage.key.messageID
                        val rowId = fMessage.rowId
                        textView.setOnClickListener {
                            if (!ConversationItemListener.isViewBoundToMessage(view, messageId)) return@setOnClickListener
                            try {
                                val messages = MessageHistoryStore.getInstance().getMessages(rowId) ?: ArrayList()
                                showBottomDialog(ArrayList(messages))
                            } catch (exception0: Exception) {
                                logDebug(exception0)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun showBottomDialog(messages: ArrayList<MessageItem>) {
        val currentAct = WppCore.getCurrentActivity() ?: return
        currentAct.runOnUiThread {
            val ctx = WppCore.getCurrentActivity() ?: return@runOnUiThread
            val dialog = WppCore.createBottomDialog(ctx)

            // Main Container
            val linearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val dip = Utils.dipToPixels(20)
                setPadding(dip, dip, dip, Utils.dipToPixels(16))
                background = DesignUtils.createDrawable("rc_dialog_bg", DesignUtils.getPrimarySurfaceColor())
            }

            // Top drag handle indicator
            val handleView = ImageView(ctx).apply {
                val handleParams = LinearLayout.LayoutParams(Utils.dipToPixels(48), Utils.dipToPixels(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, 0, 0, Utils.dipToPixels(12))
                }
                layoutParams = handleParams
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Utils.dipToPixels(3).toFloat()
                    setColor(DesignUtils.getPrimaryTextColor())
                    alpha = 50
                }
            }

            // Title View
            val titleView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, Utils.dipToPixels(12))
                }
                textSize = 17f
                setTextColor(DesignUtils.getPrimaryTextColor())
                setTypeface(null, Typeface.BOLD)
                setText(R.string.edited_history)
            }

            // Message History List View
            val adapter = MessageAdapter(ctx, messages)
            val listView: ListView = NoScrollListView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                this.adapter = adapter
                divider = null
                dividerHeight = 0
            }

            // Scroll Container
            val nestedScrollView = NestedScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    weight = 1f
                }
                isFillViewport = true
                addView(listView)
            }

            // Close Button
            val okButton = TextView(ctx).apply {
                val btnParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Utils.dipToPixels(44)
                ).apply {
                    setMargins(0, Utils.dipToPixels(14), 0, 0)
                }
                layoutParams = btnParams
                gravity = Gravity.CENTER
                text = "OK"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(DesignUtils.getPrimaryTextColor())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = Utils.dipToPixels(12).toFloat()
                    setColor(DesignUtils.getPrimaryTextColor())
                    alpha = 25
                }
                setOnClickListener { dialog.dismissDialog() }
            }

            linearLayout.addView(handleView)
            linearLayout.addView(titleView)
            linearLayout.addView(nestedScrollView)
            linearLayout.addView(okButton)

            dialog.setContentView(linearLayout)
            dialog.setCanceledOnTouchOutside(true)
            dialog.showDialog()
        }
    }

    override fun getPluginName(): String {
        return "Show Edit Message"
    }
}
