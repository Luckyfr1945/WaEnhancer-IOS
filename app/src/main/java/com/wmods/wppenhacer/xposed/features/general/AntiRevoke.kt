package com.wmods.wppenhacer.xposed.features.general

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.FStatusWpp
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.db.DelMessageStore
import com.wmods.wppenhacer.xposed.core.db.MessageStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class AntiRevoke(loader: ClassLoader, preferences:SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private val messageRevokedMap = ConcurrentHashMap<String, MutableSet<String>>()

        private val dateFormatThreadLocal = ThreadLocal.withInitial {
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                Utils.application.resources.configuration.locales[0]
            )
        }

        private fun findObjectFMessage(param: XC_MethodHook.MethodHookParam): FMessageWpp? {
            val safeArgs = param.args?.filterNotNull() ?: return null
            safeArgs.firstOrNull { FMessageWpp.TYPE.isInstance(it) }?.let { return FMessageWpp(it) }
            val arg0 = param.args?.getOrNull(0) ?: return null
            val statusItem = StatusItemWpp.from(arg0) ?: return null
            return statusItem.fMessage
        }


        private fun getJidKey(fMessage: FMessageWpp): String? {
            val remoteJid = fMessage.key.remoteJid
            if (remoteJid.isStatus) return "status@broadcast"
            return remoteJid.phoneRawString ?: remoteJid.userRawString ?: remoteJid.phoneNumber ?: remoteJid.rawJidString
        }

        private fun getRevokedMessagesForJid(fMessage: FMessageWpp): MutableSet<String> {
            val stripJID = getJidKey(fMessage) ?: return Collections.synchronizedSet(HashSet())
            val cached = messageRevokedMap[stripJID]
            if (cached != null) return cached

            val emptySet = Collections.synchronizedSet(HashSet<String>())
            val existing = messageRevokedMap.putIfAbsent(stripJID, emptySet)
            val targetSet = existing ?: emptySet

            if (existing == null) {
                CompletableFuture.runAsync {
                    try {
                        val messages = DelMessageStore.getInstance(Utils.application).getMessagesByJid(stripJID)
                        if (messages.isNotEmpty()) {
                            targetSet.addAll(messages)
                            WppCore.getCurrentActivity()?.runOnUiThread {
                                ConversationItemListener.notifyDataSetChanged()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            return targetSet
        }

        private fun persistRevokedMessage(fMessage: FMessageWpp, messageID: String) {
            val stripJID = getJidKey(fMessage) ?: return
            DelMessageStore.getInstance(Utils.application).insertMessage(
                stripJID,
                messageID,
                System.currentTimeMillis()
            )
        }
    }

    override fun doHook() {
        val antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader)
        val unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader)
        val statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader)
        val antiRevokeFStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(classLoader)

        XposedBridge.hookMethod(antiRevokeFStatusMethod, object : XC_MethodHook() {

            override fun beforeHookedMethod(param: MethodHookParam) {
                val fStatusKey = FStatusWpp.FStatusKey(param.args[1])
                val fstatus = fStatusKey.fStatus ?: return
                val fMessage = fstatus.fMessage ?: return
                if (!fStatusKey.isFromMe && handleRevocationAttempt(
                        fMessage,
                        fStatusKey.messageID
                    ) != 0
                ) {
                    param.result = 0
                }
            }

        })

        XposedBridge.hookMethod(antiRevokeMessageMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val fMessageObj = ReflectionUtils.getArg(args, FMessageWpp.TYPE, 0)
                if (fMessageObj == null) {
                    logDebug("FMessageObj is null in revoke!")
                    return
                }
                val fMessage = FMessageWpp(fMessageObj)
                val messageKey = fMessage.key
                val deviceJid = fMessage.deviceJid
                val messageId = try {
                    fMessage.key.messageID.takeIf { it.isNotEmpty() }
                } catch (_: Throwable) {
                    null
                } ?: try {
                    XposedHelpers.getObjectField(fMessage.getObject(), "A01") as? String
                } catch (_: Throwable) {
                    null
                } ?: return


                val method = param.method as? Method
                val returnType = method?.returnType

                fun setBlockedResult() {
                    if (returnType == null || returnType == java.lang.Void.TYPE || returnType == java.lang.Void::class.java) {
                        param.result = null
                        return
                    }
                    if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) {
                        param.result = true
                        return
                    }
                    if (returnType == java.lang.Integer.TYPE || returnType == java.lang.Integer::class.java) {
                        param.result = 0
                        return
                    }
                    if (returnType == java.lang.Long.TYPE || returnType == java.lang.Long::class.java) {
                        param.result = 0L
                        return
                    }

                    // For any object return type (e.g. X.9jD, X.6ta, etc.):
                    // 1. Try constructor first
                    try {
                        val constr = returnType.constructors.firstOrNull() ?: returnType.declaredConstructors.firstOrNull()
                        if (constr != null) {
                            constr.isAccessible = true
                            val paramTypes = constr.parameterTypes
                            val defaultArgs = arrayOfNulls<Any?>(paramTypes.size)
                            for (i in paramTypes.indices) {
                                val p = paramTypes[i]
                                defaultArgs[i] = when {
                                    p == java.lang.Boolean.TYPE || p == java.lang.Boolean::class.java -> false
                                    p == java.lang.Integer.TYPE || p == java.lang.Integer::class.java -> 0
                                    p == java.lang.Long.TYPE || p == java.lang.Long::class.java -> 0L
                                    p == java.lang.Float.TYPE || p == java.lang.Float::class.java -> 0f
                                    p == java.lang.Double.TYPE || p == java.lang.Double::class.java -> 0.0
                                    p == java.lang.Byte.TYPE || p == java.lang.Byte::class.java -> 0.toByte()
                                    p == java.lang.Short.TYPE || p == java.lang.Short::class.java -> 0.toShort()
                                    p == java.lang.Character.TYPE || p == java.lang.Character::class.java -> ' '
                                    else -> null
                                }
                            }
                            param.result = constr.newInstance(*defaultArgs)
                            return
                        }
                    } catch (_: Throwable) {}

                    // 2. Fallback to Unsafe.allocateInstance for 100% reliable instantiation of any object
                    try {
                        val unsafeClass = Class.forName("sun.misc.Unsafe")
                        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                        theUnsafeField.isAccessible = true
                        val unsafe = theUnsafeField.get(null)
                        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                        param.result = allocateMethod.invoke(unsafe, returnType)
                        return
                    } catch (_: Throwable) {}

                    param.result = null
                }

                if (!messageKey.isFromMe && handleRevocationAttempt(
                        fMessage,
                        messageId
                    ) != 0
                ) {
                    setBlockedResult()
                }
            }
        })

        ConversationItemListener.conversationListeners.add(object :
            ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(
                fMessage: FMessageWpp,
                view: ViewGroup,
                position: Int,
                convertView: View?
            ) {
                val dateTextView = view.findViewById<TextView>(Utils.getID("date", "id"))
                bindRevokedMessageUI(fMessage, dateTextView, "antirevoke", view)
            }
        })

        XposedBridge.hookMethod(unknownStatusPlaybackMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val obj = ReflectionUtils.getArg(param.args, param.method.declaringClass, 0)
                val fMessage = findObjectFMessage(param)
                val field =
                    ReflectionUtils.getFieldByType(param.method.declaringClass, statusPlaybackClass)

                if (obj == null || field == null || fMessage == null) {
                    logDebug("Invalid parameters")
                    return
                }

                val objView = field.get(obj) ?: return
                val textViews =
                    ReflectionUtils.getFieldsByType(statusPlaybackClass, TextView::class.java)

                if (textViews.isEmpty()) {
                    logDebug("No text views found")
                    return
                }

                val dateId = Utils.getID("date", "id")
                for (textViewField in textViews) {
                    val textView = textViewField.get(objView) as? TextView
                    if (textView != null && textView.id == dateId) {
                        bindRevokedMessageUI(fMessage, textView, "antirevokestatus")
                        break
                    }
                }
            }
        })
    }

    private fun bindRevokedMessageUI(
        fMessage: FMessageWpp,
        dateTextView: TextView?,
        antirevokeType: String,
        boundView: View? = null
    ) {
        if (dateTextView == null) return
        val antirevokeValue = prefs.getString(antirevokeType, "0")?.toIntOrNull() ?: 0
        if (antirevokeValue == 0) return

        val key = fMessage.key
        val boundMessageId = key.messageID
        val messageRevokedList = getRevokedMessagesForJid(fMessage)
        val originalMessage =
            XposedHelpers.getAdditionalInstanceField(dateTextView, "originalMessage") as? String

        dateTextView.paint.isUnderlineText = false
        dateTextView.setOnClickListener(null)
        dateTextView.setCompoundDrawables(null, null, null, null)

        val messageID = if (messageRevokedList.contains(key.messageID)) {
            key.messageID
        } else if (messageRevokedList.isNotEmpty() && fMessage.rowId > 0) {
            MessageStore.getInstance().getOriginalMessageKey(fMessage.rowId)
                .takeIf { messageRevokedList.contains(it) }
        } else null

        if (messageID != null) {
            val appInstance = Utils.application
            val timestamp =
                DelMessageStore.getInstance(appInstance).getTimestampByMessageId(messageID)
            if (timestamp > 0) {
                val date = dateFormatThreadLocal.get()?.format(Date(timestamp))
                dateTextView.paint.isUnderlineText = true
                dateTextView.setOnClickListener {
                    if (boundView != null && !ConversationItemListener.isViewBoundToMessage(boundView, boundMessageId)) return@setOnClickListener
                    val toastMessage =
                        Utils.application.getString(R.string.message_removed_on)
                            .format(date)
                    Utils.showToast(toastMessage, Toast.LENGTH_LONG)
                }
            }

            when (antirevokeValue) {
                1 -> {
                    val messageText = originalMessage ?: dateTextView.text
                    var deletedLabel = try {
                        UnobfuscatorCache.getInstance().getString("thismessagewasdeleted")
                    } catch (_: Throwable) { "" }
                    if (deletedLabel.isBlank()) {
                        deletedLabel = try {
                            UnobfuscatorCache.getInstance().getString("messagedeleted")
                        } catch (_: Throwable) { "" }
                    }
                    if (deletedLabel.isBlank()) {
                        deletedLabel = try {
                            Utils.application.getString(R.string.message_deleted_tag)
                        } catch (_: Throwable) { "Dihapus" }
                    }
                    val newTextData = "$deletedLabel | $messageText"
                    dateTextView.text = newTextData
                    XposedHelpers.setAdditionalInstanceField(
                        dateTextView,
                        "originalMessage",
                        messageText.toString()
                    )
                }

                2 -> {
                    val drawable = Utils.application.getDrawable(R.drawable.deleted)
                    dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
                    dateTextView.compoundDrawablePadding = 5
                }
            }
        } else {
            dateTextView.setCompoundDrawables(null, null, null, null)
            if (originalMessage != null) {
                dateTextView.text = originalMessage
            }
            dateTextView.paint.isUnderlineText = false
            dateTextView.setOnClickListener(null)
        }
    }

    private fun handleRevocationAttempt(fMessage: FMessageWpp, messageId: String): Int {
        try {
            handleRevocationAlert(fMessage)
        } catch (e: Exception) {
            log(e)
        }

        val revokeBoolean = prefs.getString(
            if (fMessage.key.remoteJid.isStatus) "antirevokestatus" else "antirevoke",
            "0"
        )?.toIntOrNull() ?: 0

        if (revokeBoolean == 0) return 0

        val messageRevokedList = getRevokedMessagesForJid(fMessage)
        if (messageRevokedList.add(messageId)) {
            CompletableFuture.runAsync {
                try {
                    persistRevokedMessage(fMessage, messageId)
                    val mConversation = WppCore.getCurrentConversation()
                    val currentChatJid = WppCore.getCurrentUserJid()
                    val currentKey = currentChatJid?.phoneRawString ?: currentChatJid?.userRawString ?: currentChatJid?.phoneNumber
                    val msgKey = getJidKey(fMessage)
                    if (mConversation != null && msgKey != null && (msgKey == currentKey || msgKey == currentChatJid?.rawJidString)) {
                        mConversation.runOnUiThread {
                            ConversationItemListener.notifyDataSetChanged()
                        }
                    }
                } catch (e: Exception) {
                    logDebug(e)
                }
            }
        }
        return revokeBoolean
    }

    private fun formatRevocationMessage(fMessage: FMessageWpp): String? {
        var jidAuthor = fMessage.key.remoteJid
        var messageSuffix = Utils.application.getString(R.string.deleted_message)

        if (jidAuthor.isStatus) {
            messageSuffix = Utils.application.getString(R.string.deleted_status)
            jidAuthor = fMessage.userJid
        }
        val waContact = WaContactWpp.getWaContactFromJid(jidAuthor)

        val name = waContact?.displayName
            ?: jidAuthor.phoneNumber

        return if (jidAuthor.isGroup) {
            var participantJid = fMessage.userJid
            if (participantJid.isNull) {
                val deletedAdminUser = try {
                    XposedHelpers.getObjectField(fMessage.getObject(), "A00")
                } catch (_: Throwable) {
                    null
                }
                if (deletedAdminUser != null) {
                    participantJid = FMessageWpp.UserJid(deletedAdminUser)
                }
                if (participantJid.isNull) {
                    val extracted = FMessageWpp.UserJid.extractFrom(fMessage.getObject())
                    if (extracted != null && !extracted.isNull) {
                        participantJid = extracted
                    }
                }
            }
            val participantWaContact = WaContactWpp.getWaContactFromJid(participantJid)

            val participantName = participantWaContact?.displayName
                ?: participantJid.phoneNumber

            Utils.application
                .getString(R.string.deleted_a_message_in_group, participantName, name)
        } else {
            "$name $messageSuffix"
        }
    }

    private fun handleRevocationAlert(fMessage: FMessageWpp) {
        val message = formatRevocationMessage(fMessage) ?: return

        val jidAuthor = fMessage.key.remoteJid
        val actualAuthor = if (jidAuthor.isStatus) fMessage.userJid else jidAuthor
        val waContact = WaContactWpp.getWaContactFromJid(actualAuthor)

        val name = waContact?.displayName ?: actualAuthor.phoneNumber

        val taskerAction = if (jidAuthor.isStatus) "deleted_status" else "deleted_message"

        if (prefs.getBoolean("toastdeleted", false)) {
            Utils.showToast(message, Toast.LENGTH_LONG)
        }

        Tasker.sendTaskerEvent(name, jidAuthor.phoneNumber, taskerAction)
    }

    override fun getPluginName(): String = "Anti Revoke"
}
