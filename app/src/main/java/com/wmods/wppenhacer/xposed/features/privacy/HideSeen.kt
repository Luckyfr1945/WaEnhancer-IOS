package com.wmods.wppenhacer.xposed.features.privacy

import android.os.Handler
import android.os.Message
import androidx.room.concurrent.ThreadLocal
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
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.CompletableFuture

class HideSeen(loader: ClassLoader, preferences:SharedPreferences) :
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

    private var hideReceipt = false
    private var ghostMode = false
    private var hideRead = false
    private var hideAudioSeen = false
    private var hideOnceSeen = false
    private var hideReadGroup = false
    private var hideStatusView = false

    override fun doHook() {
        loadPreferences()
        hookSendReadReceiptJob()
        hookReceiptMethod()
        hookSenderPlayed()
        hookSenderPlayedBusiness()
    }

    private fun loadPreferences() {
        ghostMode = WppCore.getPrivBoolean("ghostmode", false)
        hideRead = prefs.getBoolean("hideread", false)
        hideAudioSeen = prefs.getBoolean("hideaudioseen", false)
        hideOnceSeen = prefs.getBoolean("hideonceseen", false)
        hideReadGroup = prefs.getBoolean("hideread_group", false)
        hideStatusView = prefs.getBoolean("hidestatusview", false)
        hideReceipt = prefs.getBoolean("hidereceipt", false)

    }

    private fun hookSendReadReceiptJob() {
        val sendReadReceiptJobMethod = Unobfuscator.loadHideViewSendReadJob(classLoader)
        XposedBridge.log("[WaEnhancer] sendReadReceiptJobMethod = $sendReadReceiptJobMethod, returnType=${sendReadReceiptJobMethod.returnType}")

        val jobClass = XposedHelpers.findClassIfExists("com.whatsapp.jobqueue.job.SendReadReceiptJob", classLoader)
        if (jobClass != null) {
            for (m in jobClass.declaredMethods) {
                XposedBridge.log("[WaEnhancer] SendReadReceiptJob method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) -> ${m.returnType.name}")
            }
        }

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

                val privacy = CustomPrivacy.getJSON(userJid.phoneNumber)
                val isHide = processReadReceiptByType(param, job, userJid, privacy)

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
        userJid: FMessageWpp.UserJid,
        privacy: JSONObject
    ): Boolean {
        val blueOnReply = Utils.isBlueOnReplyEnabled(prefs)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)
        val isHideRead = prefs.getBoolean("hideread", false) || blueOnReply
        val isHideReadGroup = prefs.getBoolean("hideread_group", false) || blueOnReply
        val isHideStatusView = prefs.getBoolean("hidestatusview", false)

        return when {
            userJid.isGroup -> {
                if (privacy.optBoolean("HideSeen", isHideReadGroup) || isGhostMode) {
                    blockMethodExecution(param)
                    true
                } else false
            }

            userJid.isStatus -> {
                val participant = XposedHelpers.getObjectField(job, "participant") as? String
                val statusJid = FMessageWpp.UserJid(participant)
                val customHideStatusView = CustomPrivacy.getJSON(statusJid.phoneNumber)
                    .optBoolean("HideViewStatus", isHideStatusView)

                if (customHideStatusView || isGhostMode) {
                    blockMethodExecution(param)
                }
                false
            }

            else -> {
                if (privacy.optBoolean("HideSeen", isHideRead) || isGhostMode) {
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

        val phoneRaw = userJid.phoneRawString
        val userRaw = userJid.userRawString
        val primaryJid = phoneRaw ?: userRaw ?: return

        CompletableFuture.runAsync {
            val store = MessageHistoryStore.getInstance()
            for (messageId in messageIds) {
                if (messageId is String) {
                    store.insertHideSeenMessage(
                        primaryJid,
                        messageId,
                        MessageHistoryStore.ReceiptType.READ,
                        false
                    )
                    if (userRaw != null && userRaw != primaryJid) {
                        store.insertHideSeenMessage(
                            userRaw,
                            messageId,
                            MessageHistoryStore.ReceiptType.READ,
                            false
                        )
                    }
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
                        // We check if the message is duplicated to avoid sending a tick twice causing congestion in the IQ queue
                        val fmessageKeyField = ReflectionUtils.findFieldUsingFilter(obj.javaClass){
                            FMessageWpp.Key.TYPE.isAssignableFrom(it.type)
                        }
                        val fmessageKey = FMessageWpp.Key(fmessageKeyField.get(obj))
                        val hideSeenItem = MessageHistoryStore.getInstance().getHideSeenMessage(
                            fmessageKey.remoteJid.phoneRawString,
                            fmessageKey.messageID,
                            MessageHistoryStore.ReceiptType.READ
                        )

                        if (hideSeenItem?.viewed ?: false) return

                        hideSeenItem?.let {
                            message.arg1 = -1
                            return
                        }
                    }
                })
        }


        Others.propsBoolean[19148] = false // Change route IQ

        XposedBridge.hookMethod(receiptMethod, object : XC_MethodHook() {

            override fun afterHookedMethod(param: MethodHookParam) {

                val protocolTreeNodeWpp = ProtocolTreeNodeWpp(param.result)
                val typeVal = protocolTreeNodeWpp.getFirstKeyValue("type")
                val isReadReceipt = (typeVal == "read")

                val fmessageKey = generateFMessageKey(protocolTreeNodeWpp) ?: return

                if (fmessageKey.remoteJid.isStatus) return

                val hideSeen = checkPrivacyAndHideSeen(fmessageKey)
                val hideReceipt = checkPrivacyAndHideReceipt(fmessageKey)

                if (hideReceipt) {
                    protocolTreeNodeWpp.modifyKeyValue("type", "inactive")
                    if (protocolTreeNodeWpp.getFirstKeyValue("type") == null) {
                        protocolTreeNodeWpp.addKeyValue("type", "inactive")
                    }
                    protocolTreeNodeWpp.removeAllKeyValuesByKey("sts")
                } else if (hideSeen && isReadReceipt) {
                    protocolTreeNodeWpp.removeAllKeyValuesByKey("sts")
                    protocolTreeNodeWpp.removeAllKeyValuesByKey("type")
                }

                if (hideReceipt || (hideSeen && isReadReceipt)) {
                    val phoneRaw = fmessageKey.remoteJid.phoneRawString
                    val userRaw = fmessageKey.remoteJid.userRawString
                    val primaryJid = phoneRaw ?: userRaw
                    if (primaryJid != null) {
                        MessageHistoryStore.getInstance().insertHideSeenMessage(
                            primaryJid,
                            fmessageKey.messageID,
                            MessageHistoryStore.ReceiptType.READ,
                            false
                        )
                        if (userRaw != null && userRaw != primaryJid) {
                            MessageHistoryStore.getInstance().insertHideSeenMessage(
                                userRaw,
                                fmessageKey.messageID,
                                MessageHistoryStore.ReceiptType.READ,
                                false
                            )
                        }
                    }
                }
            }
        })
    }

    private fun checkPrivacyAndHideReceipt(fmessageKey: FMessageWpp.Key): Boolean {
        val isHideReceipt = prefs.getBoolean("hidereceipt", false)
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)
        val privacy = CustomPrivacy.getJSON(fmessageKey.remoteJid.phoneNumber)
        val customHideReceipt = privacy.optBoolean("HideReceipt", isHideReceipt)
        return customHideReceipt || isGhostMode
    }

    private fun checkPrivacyAndHideSeen(fmessageKey: FMessageWpp.Key): Boolean {
        val blueOnReply = Utils.isBlueOnReplyEnabled(prefs)
        val isHideRead = prefs.getBoolean("hideread", false) || blueOnReply
        val isHideReadGroup = prefs.getBoolean("hideread_group", false) || blueOnReply
        val isGhostMode = WppCore.getPrivBoolean("ghostmode", false)

        val privacy = CustomPrivacy.getJSON(fmessageKey.remoteJid.phoneNumber)
        val hideKey = if (fmessageKey.remoteJid.isGroup) isHideReadGroup else isHideRead
        val shouldHide = privacy.optBoolean("HideSeen", hideKey) || isGhostMode
        return shouldHide
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
            MessageHistoryStore.getInstance().insertHideSeenMessage(
                key.remoteJid.phoneRawString,
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