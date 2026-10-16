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
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
            val safeArgs = param.args?.filterNotNull() ?: emptyList()
            safeArgs.firstOrNull { FMessageWpp.TYPE.isInstance(it) }?.let { return FMessageWpp(it) }
            for (arg in safeArgs) {
                val statusItem = StatusItemWpp.from(arg)
                if (statusItem?.fMessage != null) return statusItem.fMessage
            }
            if (param.thisObject != null) {
                val statusItem = StatusItemWpp.from(param.thisObject)
                if (statusItem?.fMessage != null) return statusItem.fMessage

                for (f in param.thisObject.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = runCatching { f.get(param.thisObject) }.getOrNull() ?: continue
                    if (FMessageWpp.TYPE.isInstance(v)) return FMessageWpp(v)
                    val item = StatusItemWpp.from(v)
                    if (item?.fMessage != null) return item.fMessage
                }
            }
            return null
        }



        private fun getJidKeys(fMessage: FMessageWpp): Set<String> {
            val remoteJid = fMessage.key.remoteJid
            if (remoteJid.isStatus) return setOf("status@broadcast")

            val keys = linkedSetOf<String>()
            remoteJid.phoneRawString?.let { keys.add(it) }
            remoteJid.userRawString?.let { keys.add(it) }
            remoteJid.phoneNumber?.let { keys.add(it) }
            remoteJid.rawJidString?.let { keys.add(it) }
            val fullUserRaw = runCatching {
                remoteJid.userJid?.let { XposedHelpers.callMethod(it, "getRawString") as? String }
            }.getOrNull()
            if (!fullUserRaw.isNullOrBlank()) keys.add(fullUserRaw)
            return keys
        }

        private fun getRevokedMessagesForJid(fMessage: FMessageWpp): MutableSet<String> {
            val stripJIDs = getJidKeys(fMessage)
            if (stripJIDs.isEmpty()) return Collections.synchronizedSet(HashSet())

            for (jid in stripJIDs) {
                messageRevokedMap[jid]?.let { return it }
            }

            val targetJid = stripJIDs.firstOrNull() ?: return Collections.synchronizedSet(HashSet())
            val targetSet = messageRevokedMap.computeIfAbsent(targetJid) {
                val set = Collections.synchronizedSet(HashSet<String>())
                for (jid in stripJIDs) {
                    val messages = DelMessageStore.getInstance(Utils.application).getMessagesByJid(jid)
                    if (messages.isNotEmpty()) {
                        set.addAll(messages)
                    }
                }
                set
            }
            for (jid in stripJIDs) {
                messageRevokedMap.putIfAbsent(jid, targetSet)
            }
            return targetSet
        }

        private fun persistRevokedMessage(fMessage: FMessageWpp, messageID: String) {
            val stripJIDs = getJidKeys(fMessage)
            val store = DelMessageStore.getInstance(Utils.application)
            val now = System.currentTimeMillis()
            for (jid in stripJIDs) {
                store.insertMessage(
                    jid,
                    messageID,
                    now
                )
            }
        }
    }

    override fun doHook() {
        // 1. Hook Revoke Status via FStatusManager
        try {
            val antiRevokeFStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(classLoader)
            XposedBridge.hookMethod(antiRevokeFStatusMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val rawKey = param.args?.firstOrNull { it != null && FStatusWpp.FStatusKey.TYPE.isInstance(it) }
                            ?: param.args?.getOrNull(0)
                            ?: param.args?.getOrNull(1)
                            ?: return

                        val fStatusKey = FStatusWpp.FStatusKey(rawKey)
                        if (fStatusKey.isFromMe) return

                        val statusMsgId = fStatusKey.messageID
                        if (statusMsgId.isBlank()) return

                        val antirevokeStatusVal = getAntirevokeValue("antirevokestatus")
                        if (antirevokeStatusVal == 0) return

                        val fstatus = fStatusKey.fStatus
                        val fMessage = fstatus?.fMessage

                        if (fMessage != null) {
                            handleRevocationAttempt(fMessage, setOf(statusMsgId))
                        } else {
                            handleStatusRevocationFallback(fStatusKey, statusMsgId)
                        }

                        val returnType = (param.method as? Method)?.returnType
                        param.result = when {
                            returnType == java.lang.Integer.TYPE -> 0
                            returnType == java.lang.Boolean.TYPE -> false
                            returnType == java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                        logDebug("[AntiRevoke] Blocked status revocation for msgId=$statusMsgId, sender=${fStatusKey.senderJid.phoneNumber}")
                    } catch (t: Throwable) {
                        log(t)
                    }
                }
            })
        } catch (t: Throwable) {
            log(t)
        }

        // 2. Hook Revoke Message via MsgStore
        try {
            val antiRevokeMessageMethod = Unobfuscator.loadAntiRevokeMessageMethod(classLoader)
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
                    if (messageKey.isFromMe) return

                    val myNumber = Utils.getMyNumber()
                    if (!myNumber.isNullOrBlank()) {
                        if (messageKey.remoteJid.phoneNumber == myNumber) return
                        val senderJid = fMessage.userJid
                        if (senderJid.phoneNumber == myNumber) return
                        val senderRaw = senderJid.userRawString ?: ""
                        if (senderRaw.contains("lid_me") || senderRaw.contains("status_me")) return
                    }

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

                    val origMessageId = runCatching { fMessage.originalKey.messageID }.getOrNull()?.takeIf { it.isNotEmpty() }

                    val candidateIds = linkedSetOf<String>()
                    candidateIds.add(messageId)
                    origMessageId?.let { candidateIds.add(it) }

                    try {
                        val obj = fMessage.getObject()
                        val cls = obj.javaClass
                        for (f in cls.fields + cls.declaredFields) {
                            f.isAccessible = true
                            val v = f.get(obj)
                            if (v is String && v.length >= 16) {
                                candidateIds.add(v)
                            }
                        }
                    } catch (_: Throwable) {}

                    logDebug("[AntiRevoke] Revoke attempt detected for candidateIds=$candidateIds from JID=${messageKey.remoteJid}, sender=${fMessage.userJid}, method=${param.method.name}")

                    val method = param.method as? Method
                    val returnType = method?.returnType
                    logDebug("[AntiRevoke] antiRevokeMessageMethod: class=${method?.declaringClass?.name}, name=${method?.name}, returnType=$returnType")

                    fun setBlockedResult() {
                        if (returnType == null || returnType == java.lang.Void.TYPE || returnType == java.lang.Void::class.java) {
                            param.result = null
                            return
                        }
                        if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) {
                            param.result = false
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

                        // Instantiate non-null result object to avoid NPE in callers expecting EwP / result class
                        try {
                            val constructor = returnType.constructors.firstOrNull()
                            if (constructor != null) {
                                val cArgs = Array<Any?>(constructor.parameterCount) { idx ->
                                    val pType = constructor.parameterTypes[idx]
                                    when {
                                        pType == java.lang.Boolean.TYPE -> false
                                        pType == java.lang.Integer.TYPE -> 0
                                        pType == java.lang.Long.TYPE -> 0L
                                        else -> null
                                    }
                                }
                                param.result = constructor.newInstance(*cArgs)
                                return
                            }
                        } catch (_: Throwable) {}

                        try {
                            val unsafeClass = Class.forName("sun.misc.Unsafe")
                            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                            val unsafe = theUnsafeField.get(null)
                            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                            param.result = allocateMethod.invoke(unsafe, returnType)
                            return
                        } catch (_: Throwable) {}

                        param.result = null
                    }

                    if (!messageKey.isFromMe && handleRevocationAttempt(
                            fMessage,
                            candidateIds
                        ) != 0
                    ) {
                        setBlockedResult()
                        logDebug("[AntiRevoke] Blocked revocation of candidateIds=$candidateIds, param.result=${param.result}")
                    }
                }
            })
        } catch (t: Throwable) {
            log(t)
        }

        // 3. Hook Conversation row bind for chat messages
        try {
            ConversationItemListener.conversationListeners.add(object :
                ConversationItemListener.OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?
                ) {
                    val dateTextView = findDateTextView(view)
                    bindRevokedMessageUI(fMessage, dateTextView, "antirevoke", view)
                }
            })
        } catch (t: Throwable) {
            log(t)
        }

        // 4. Hook Status Playback UI for status deleted indicator
        try {
            val unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader)
            val statusPlaybackClass = Unobfuscator.loadStatusPlaybackViewClass(classLoader)

            XposedBridge.hookMethod(unknownStatusPlaybackMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val fMessage = findObjectFMessage(param) ?: return
                        val declaringClass = param.method.declaringClass
                        val obj = ReflectionUtils.getArg(param.args, declaringClass, 0)
                            ?: param.thisObject

                        var objView: Any? = null
                        if (obj != null) {
                            val field = ReflectionUtils.findFieldUsingFilterIfExists(obj.javaClass) { f ->
                                statusPlaybackClass.isAssignableFrom(f.type)
                            } ?: ReflectionUtils.findFieldUsingFilterIfExists(declaringClass) { f ->
                                statusPlaybackClass.isAssignableFrom(f.type)
                            } ?: ReflectionUtils.getFieldByType(declaringClass, statusPlaybackClass)
                            objView = field?.get(obj)
                        }
                        if (objView == null && param.thisObject != null) {
                            val field = ReflectionUtils.findFieldUsingFilterIfExists(param.thisObject.javaClass) { f ->
                                statusPlaybackClass.isAssignableFrom(f.type)
                            }
                            objView = field?.get(param.thisObject)
                        }

                        var dateTextView: TextView? = null

                        if (objView != null) {
                            for (f in objView.javaClass.declaredFields) {
                                if (TextView::class.java.isAssignableFrom(f.type)) {
                                    f.isAccessible = true
                                    val tv = f.get(objView) as? TextView
                                    if (tv != null) {
                                        val resName = runCatching { tv.resources.getResourceEntryName(tv.id) }.getOrNull() ?: ""
                                        if (resName.contains("date", true) || resName.contains("time", true) ||
                                            resName.contains("subtitle", true) || resName.contains("sub_title", true) ||
                                            resName.contains("status_date", true)) {
                                            dateTextView = tv
                                            break
                                        }
                                    }
                                }
                            }
                            if (dateTextView == null && objView is ViewGroup) {
                                dateTextView = findDateTextView(objView)
                            }
                        }

                        if (dateTextView == null) {
                            val rootView = runCatching {
                                (param.thisObject as? androidx.fragment.app.Fragment)?.view
                                    ?: (param.thisObject as? View)
                            }.getOrNull()
                            if (rootView is ViewGroup) {
                                dateTextView = findDateTextView(rootView)
                            }
                        }

                        if (dateTextView != null) {
                            bindRevokedMessageUI(fMessage, dateTextView, "antirevokestatus")
                        } else {
                            logDebug("[AntiRevoke] Date/subtitle TextView not found in status playback")
                        }
                    } catch (t: Throwable) {
                        logDebug(t)
                    }
                }
            })
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun findDateTextView(view: ViewGroup): TextView? {
        val targets = listOf("date", "time", "subtitle", "sub_title", "status_date", "header_subtitle")
        for (target in targets) {
            val dateId = Utils.getID(target, "id")
            if (dateId != 0) {
                val tv = view.findViewById<TextView>(dateId)
                if (tv != null) return tv
            }
        }
        for (target in targets) {
            val tv = findTextViewByEntryName(view, target)
            if (tv != null) return tv
        }
        return null
    }

    private fun findTextViewByEntryName(view: ViewGroup, target: String): TextView? {
        val count = view.childCount
        for (i in 0 until count) {
            val child = view.getChildAt(i)
            if (child is TextView) {
                val entryName = runCatching { child.resources.getResourceEntryName(child.id) }.getOrNull()
                if (entryName?.contains(target, ignoreCase = true) == true) {
                    return child
                }
            } else if (child is ViewGroup) {
                val found = findTextViewByEntryName(child, target)
                if (found != null) return found
            }
        }
        return null
    }

    private fun getAntirevokeValue(type: String): Int {
        val raw = runCatching { prefs.getString(type, null) }.getOrNull()
            ?: runCatching { if (prefs.getBoolean(type, false)) "1" else null }.getOrNull()
        if (raw != null) {
            return raw.toIntOrNull() ?: 0
        }
        // If not explicitly disabled in settings, default to 1 (Show text)
        return 1
    }

    private fun bindRevokedMessageUI(
        fMessage: FMessageWpp,
        dateTextView: TextView?,
        antirevokeType: String,
        boundView: View? = null
    ) {
        if (dateTextView == null) return
        val antirevokeValue = getAntirevokeValue(antirevokeType)
        if (antirevokeValue == 0) return

        val isStatusMessage = fMessage.key.remoteJid.isStatus || antirevokeType == "antirevokestatus"
        val key = fMessage.key
        val boundMessageId = key.messageID
        val messageRevokedList = getRevokedMessagesForJid(fMessage)
        val originalMessage =
            XposedHelpers.getAdditionalInstanceField(dateTextView, "originalMessage") as? String

        dateTextView.paint.isUnderlineText = false
        dateTextView.setOnClickListener(null)
        dateTextView.setCompoundDrawables(null, null, null, null)

        val origKey = runCatching { fMessage.originalKey.messageID }.getOrNull()

        val messageID = when {
            messageRevokedList.contains(boundMessageId) -> boundMessageId
            !origKey.isNullOrEmpty() && messageRevokedList.contains(origKey) -> origKey
            messageRevokedList.isNotEmpty() && fMessage.rowId > 0 -> {
                val orig = MessageStore.getInstance().getOriginalMessageKey(fMessage.rowId)
                if (messageRevokedList.contains(orig)) orig else null
            }
            else -> null
        } ?: run {
            if (DelMessageStore.getInstance(Utils.application).getTimestampByMessageId(boundMessageId) > 0) {
                boundMessageId
            } else null
        }

        logDebug("[AntiRevoke] bindRevokedMessageUI: msgId=$boundMessageId, isRevoked=${messageID != null}, antirevokeValue=$antirevokeValue, isStatus=$isStatusMessage, revokedCount=${messageRevokedList.size}")

        if (messageID != null) {
            val appInstance = Utils.application
            val timestamp =
                DelMessageStore.getInstance(appInstance).getTimestampByMessageId(messageID)
            if (timestamp > 0) {
                val date = dateFormatThreadLocal.get()?.format(Date(timestamp))
                dateTextView.paint.isUnderlineText = true
                dateTextView.setOnClickListener {
                    if (boundView != null && !ConversationItemListener.isViewBoundToMessage(boundView, boundMessageId)) return@setOnClickListener
                    val baseMsg = Utils.application.getString(R.string.message_removed_on).format(date)
                    val toastMessage = if (isStatusMessage) {
                        baseMsg.replace("Pesan", "Status", ignoreCase = true)
                            .replace("This message", "This status", ignoreCase = true)
                            .replace("Mensagem", "Status", ignoreCase = true)
                    } else {
                        baseMsg
                    }
                    Utils.showToast(toastMessage, Toast.LENGTH_LONG)
                }
            }

            var deletedLabel = try {
                Utils.application.getString(R.string.message_deleted_tag)
            } catch (_: Throwable) { "" }
            if (deletedLabel.isBlank()) {
                deletedLabel = try {
                    UnobfuscatorCache.getInstance().getString("thismessagewasdeleted")
                } catch (_: Throwable) { "" }
            }
            if (deletedLabel.isBlank()) {
                deletedLabel = try {
                    UnobfuscatorCache.getInstance().getString("messagedeleted")
                } catch (_: Throwable) { "" }
            }
            if (deletedLabel.isBlank()) {
                deletedLabel = "Dihapus"
            }

            val currentText = originalMessage ?: dateTextView.text.toString()

            if (isStatusMessage) {
                // Tampilan Opsi A untuk Status Playback: Tag merah terang [Dihapus] di samping waktu
                val cleanTime = if (currentText.contains(deletedLabel)) {
                    currentText.replace("[$deletedLabel]", "")
                        .replace(deletedLabel, "")
                        .replace("•", "")
                        .replace("|", "")
                        .trim()
                } else {
                    currentText.trim()
                }

                val tag = "[$deletedLabel]"
                val combinedText = if (cleanTime.isNotEmpty()) "$tag • $cleanTime" else tag
                val spannable = SpannableStringBuilder(combinedText)
                val redColor = Color.parseColor("#FF5252")

                spannable.setSpan(
                    ForegroundColorSpan(redColor),
                    0,
                    tag.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    tag.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                dateTextView.text = spannable
                XposedHelpers.setAdditionalInstanceField(
                    dateTextView,
                    "originalMessage",
                    cleanTime
                )

                if (antirevokeValue == 2) {
                    val drawable = Utils.application.getDrawable(R.drawable.deleted)
                    dateTextView.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
                    dateTextView.compoundDrawablePadding = Utils.dipToPixels(4f).toInt()
                }
            } else {
                when (antirevokeValue) {
                    1 -> {
                        val messageText = originalMessage ?: dateTextView.text
                        val newTextData = if (messageText.contains(deletedLabel)) messageText.toString() else "$deletedLabel | $messageText"
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

    private fun handleRevocationAttempt(fMessage: FMessageWpp, candidateIds: Set<String>): Int {
        try {
            handleRevocationAlert(fMessage)
        } catch (e: Exception) {
            log(e)
        }

        val revokeBoolean = getAntirevokeValue(
            if (fMessage.key.remoteJid.isStatus) "antirevokestatus" else "antirevoke"
        )

        if (revokeBoolean == 0) return 0

        val messageRevokedList = getRevokedMessagesForJid(fMessage)
        var addedAny = false
        for (id in candidateIds) {
            if (messageRevokedList.add(id)) {
                addedAny = true
                CompletableFuture.runAsync {
                    try {
                        persistRevokedMessage(fMessage, id)
                    } catch (e: Exception) {
                        logDebug(e)
                    }
                }
            }
        }

        if (addedAny) {
            val mConversation = WppCore.getCurrentConversation()
            val currentChatJid = WppCore.getCurrentUserJid()
            val currentKeys = currentChatJid?.let {
                val set = linkedSetOf<String>()
                it.phoneRawString?.let { s -> set.add(s) }
                it.userRawString?.let { s -> set.add(s) }
                it.phoneNumber?.let { s -> set.add(s) }
                it.rawJidString?.let { s -> set.add(s) }
                set
            } ?: emptySet()
            val msgKeys = getJidKeys(fMessage)
            val isCurrentChat = msgKeys.any { it in currentKeys }
            if (mConversation != null && isCurrentChat) {
                mConversation.runOnUiThread {
                    ConversationItemListener.notifyDataSetChanged()
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

    private fun handleStatusRevocationFallback(fStatusKey: FStatusWpp.FStatusKey, statusMsgId: String) {
        try {
            val authorJid = fStatusKey.senderJid
            val waContact = WaContactWpp.getWaContactFromJid(authorJid)
            val name = waContact?.displayName ?: authorJid.phoneNumber ?: "Someone"
            val messageSuffix = Utils.application.getString(R.string.deleted_status)
            val alertMsg = "$name $messageSuffix"

            if (prefs.getBoolean("toastdeleted", false)) {
                Utils.showToast(alertMsg, Toast.LENGTH_LONG)
            }
            Tasker.sendTaskerEvent(name, authorJid.phoneNumber, "deleted_status")
        } catch (e: Throwable) {
            log(e)
        }

        val statusJid = "status@broadcast"
        val messageRevokedList = messageRevokedMap.computeIfAbsent(statusJid) {
            Collections.synchronizedSet(HashSet())
        }
        if (messageRevokedList.add(statusMsgId)) {
            CompletableFuture.runAsync {
                try {
                    val store = DelMessageStore.getInstance(Utils.application)
                    store.insertMessage(statusJid, statusMsgId, System.currentTimeMillis())
                } catch (e: Throwable) {
                    logDebug(e)
                }
            }
        }
    }

    override fun getPluginName(): String = "Anti Revoke"
}
