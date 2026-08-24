package com.wmods.wppenhacer.xposed.features.privacy

import android.os.Message
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.ProtocolTreeNodeWpp
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.general.Others
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CompletableFuture

class HideSeen(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val MEDIA_TYPE_VOICE_NOTE = 2

        @JvmStatic
        fun generateFMessageKey(protocolTreeNodeWpp: ProtocolTreeNodeWpp): FMessageWpp.Key? {
            try {
                val fromKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "to" || it.key == "from" } ?: return null
                val userJid = fromKV.userJid ?: return null
                val idKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "id" } ?: return null
                val msgId = idKV.value ?: return null
                return FMessageWpp.Key(msgId, userJid, false)
            } catch (e: Throwable) {
                XposedBridge.log(e)
                return null
            }
        }
    }

    override fun doHook() {
        hookSendReadReceiptJob()
        hookReceiptMethod()
        hookSenderPlayed()
        hookSenderPlayedBusiness()
    }

    // --- Unified Privacy Evaluation Helpers ---

    private fun shouldHideReadReceipt(userJid: FMessageWpp.UserJid): Boolean {
        val blueOnReply = Utils.isBlueOnReplyEnabled(prefs)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)
        val isHideRead = prefs.getBoolean("hideread", false) || blueOnReply
        val isHideReadGroup = prefs.getBoolean("hideread_group", false) || blueOnReply

        val privacy = CustomPrivacy.getJSON(userJid.phoneNumber)
        val defaultHide = if (userJid.isGroup) isHideReadGroup else isHideRead
        return privacy.optBoolean("HideSeen", defaultHide) || isGhostMode
    }

    private fun shouldHideReceipt(userJid: FMessageWpp.UserJid): Boolean {
        val isHideReceipt = prefs.getBoolean("hidereceipt", false)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)
        val privacy = CustomPrivacy.getJSON(userJid.phoneNumber)
        return privacy.optBoolean("HideReceipt", isHideReceipt) || isGhostMode
    }

    private fun shouldHideStatusView(statusParticipantJid: FMessageWpp.UserJid): Boolean {
        val isHideStatusView = prefs.getBoolean("hidestatusview", false)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)
        val privacy = CustomPrivacy.getJSON(statusParticipantJid.phoneNumber)
        return privacy.optBoolean("HideViewStatus", isHideStatusView) || isGhostMode
    }

    // --- JID Aliasing Helper (LID & Phone JID persistence) ---

    private fun recordHiddenMessage(
        store: MessageHistoryStore,
        userJid: FMessageWpp.UserJid,
        messageId: String,
        type: MessageHistoryStore.ReceiptType,
        viewed: Boolean
    ) {
        val phoneRaw = userJid.phoneRawString
        val userRaw = userJid.userRawString
        val primaryJid = phoneRaw ?: userRaw ?: return

        store.insertHideSeenMessage(primaryJid, messageId, type, viewed)
        if (userRaw != null && userRaw != primaryJid) {
            store.insertHideSeenMessage(userRaw, messageId, type, viewed)
        }
    }

    // --- Hooks ---

    private fun hookSendReadReceiptJob() {
        val sendReadReceiptJobMethod = Unobfuscator.loadHideViewSendReadJob(classLoader)

        XposedBridge.hookMethod(sendReadReceiptJobMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val job = param.thisObject ?: return
                val hasBlueOnReply =
                    XposedHelpers.getAdditionalInstanceField(job, "blue_on_reply") as? Boolean
                        ?: false

                if (hasBlueOnReply) return

                val userJid = FMessageWpp.UserJid.extractFrom(job)
                if (userJid == null || userJid.isNull) return

                val isInvalidJid =
                    userJid.phoneRawString?.contains("lid_me") == true ||
                    userJid.phoneRawString?.contains("status_me") == true ||
                    userJid.userRawString?.contains("lid_me") == true ||
                    userJid.userRawString?.contains("status_me") == true

                if (isInvalidJid) return

                val isHide = processReadReceiptByType(param, job, userJid)

                if (isHide) {
                    recordHiddenMessages(job, userJid)
                }
            }
        })
    }

    private fun blockMethodExecution(param: XC_MethodHook.MethodHookParam) {
        ReflectionUtils.blockMethodExecution(param)
    }

    private fun processReadReceiptByType(
        param: XC_MethodHook.MethodHookParam,
        job: Any,
        userJid: FMessageWpp.UserJid
    ): Boolean {
        return when {
            userJid.isGroup -> {
                if (shouldHideReadReceipt(userJid)) {
                    blockMethodExecution(param)
                    true
                } else false
            }

            userJid.isStatus -> {
                val participant = XposedHelpers.getObjectField(job, "participant") as? String
                val statusJid = FMessageWpp.UserJid(participant)
                if (shouldHideStatusView(statusJid)) {
                    blockMethodExecution(param)
                }
                false
            }

            else -> {
                if (shouldHideReadReceipt(userJid)) {
                    blockMethodExecution(param)
                    true
                } else false
            }
        }
    }

    private fun recordHiddenMessages(sendReadReceiptJob: Any, userJid: FMessageWpp.UserJid) {
        val messageIds =
            (XposedHelpers.getObjectField(sendReadReceiptJob, "messageIds") as? Array<*>)
                ?: (ReflectionUtils.findFieldUsingFilter(sendReadReceiptJob.javaClass) {
                    it.type == Array<String>::class.java
                }?.get(sendReadReceiptJob) as? Array<*>) ?: return

        CompletableFuture.runAsync {
            val store = MessageHistoryStore.getInstance()
            for (messageId in messageIds) {
                if (messageId is String) {
                    recordHiddenMessage(
                        store,
                        userJid,
                        messageId,
                        MessageHistoryStore.ReceiptType.READ,
                        false
                    )
                }
            }
        }
    }

    private fun hookReceiptMethod() {
        val receiptMethod = Unobfuscator.loadReceiptMethod(classLoader)
        val receiptMessageInfoClass = Unobfuscator.loadReceiptMessageInfoClass(classLoader)
        val onDispatchMessage = Unobfuscator.loadOndispatchMessage(classLoader)

        onDispatchMessage.forEach { method ->
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val message = param.args[0] as Message
                        val type = message.arg1
                        val obj = message.obj
                        if (type != 419 && type != 89) return
                        if (!receiptMessageInfoClass.isInstance(obj)) return

                        val fmessageKeyField = ReflectionUtils.findFieldUsingFilter(obj.javaClass) {
                            FMessageWpp.Key.TYPE.isAssignableFrom(it.type)
                        } ?: return

                        val rawKey = fmessageKeyField.get(obj) ?: return
                        val fmessageKey = FMessageWpp.Key(rawKey)
                        val hideSeenItem = MessageHistoryStore.getInstance().getHideSeenMessage(
                            fmessageKey.remoteJid.phoneRawString,
                            fmessageKey.messageID,
                            MessageHistoryStore.ReceiptType.READ
                        )

                        if (hideSeenItem?.viewed == true) return

                        hideSeenItem?.let {
                            message.arg1 = -1
                            return
                        }
                    }
                })
        }

        XposedBridge.hookMethod(receiptMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val protocolTreeNodeWpp = ProtocolTreeNodeWpp(param.result)
                val typeVal = protocolTreeNodeWpp.getFirstKeyValue("type")
                val isReadReceipt = (typeVal == "read")

                val fmessageKey = generateFMessageKey(protocolTreeNodeWpp) ?: return
                if (fmessageKey.isFromMe || fmessageKey.remoteJid.isNull || fmessageKey.remoteJid.isStatus) return

                val hideSeen = shouldHideReadReceipt(fmessageKey.remoteJid)
                val hideReceipt = shouldHideReceipt(fmessageKey.remoteJid)

                if (hideReceipt || (hideSeen && isReadReceipt)) {
                    protocolTreeNodeWpp.modifyKeyValue("to", "0@s.whatsapp.net")
                    protocolTreeNodeWpp.removeAllKeyValuesByKey("participant")

                    recordHiddenMessage(
                        MessageHistoryStore.getInstance(),
                        fmessageKey.remoteJid,
                        fmessageKey.messageID,
                        MessageHistoryStore.ReceiptType.READ,
                        false
                    )
                }
            }
        })
    }

    private fun hookSenderPlayed() {
        val loadSenderPlayed = Unobfuscator.loadSenderPlayedMethod(classLoader)

        XposedBridge.hookMethod(loadSenderPlayed, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fMessage = FMessageWpp(param.args[0])
                processSenderPlayed(param, fMessage)
            }
        })
    }

    private fun hookSenderPlayedBusiness() {
        val loadSenderPlayedBusiness = Unobfuscator.loadSenderPlayedBusiness(classLoader)

        XposedBridge.hookMethod(loadSenderPlayedBusiness, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val set = param.args[0] as? Set<*>
                if (set.isNullOrEmpty()) return

                val fMessage = FMessageWpp(set.first())
                processSenderPlayed(param, fMessage)
            }
        })
    }

    private fun processSenderPlayed(param: XC_MethodHook.MethodHookParam, fMessage: FMessageWpp) {
        val isHideOnceSeen = prefs.getBoolean("hideonceseen", false)
        val isHideAudioSeen = prefs.getBoolean("hideaudioseen", false)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)

        val isHideViewOnce = (isHideOnceSeen || isGhostMode) && fMessage.isViewOnce
        val isHideVoiceNote =
            (isHideAudioSeen || isGhostMode) && fMessage.mediaType == MEDIA_TYPE_VOICE_NOTE
        val key = fMessage.key

        if (isHideViewOnce || isHideVoiceNote) {
            blockMethodExecution(param)
            recordHiddenMessage(
                MessageHistoryStore.getInstance(),
                key.remoteJid,
                key.messageID,
                MessageHistoryStore.ReceiptType.PLAYED,
                false
            )
        }

        if (fMessage.isViewOnce && !isHideOnceSeen && !isGhostMode) {
            val phoneRaw = key.remoteJid.phoneRawString
            val messageId = key.messageID
            MessageHistoryStore.getInstance().apply {
                updateViewedMessage(
                    phoneRaw,
                    messageId,
                    MessageHistoryStore.ReceiptType.PLAYED,
                    true
                )
                updateViewedMessage(phoneRaw, messageId, MessageHistoryStore.ReceiptType.READ, true)
            }
        }
    }

    override fun getPluginName(): String {
        return "Hide Seen"
    }
}