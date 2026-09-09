package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.ProtocolTreeNodeWpp
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType

class HideSeen(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val MEDIA_TYPE_VOICE_NOTE = 2
        private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        @JvmStatic
        fun generateFMessageKey(protocolTreeNodeWpp: ProtocolTreeNodeWpp): FMessageWpp.Key? {
            try {
                if (protocolTreeNodeWpp.tag != "receipt") return null
                val fromKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "to" || it.key == "from" } ?: return null
                val userJid = fromKV.userJid ?: return null
                val idKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "id" } ?: return null
                val idVal = idKV.value ?: return null
                return FMessageWpp.Key(idVal, userJid, false)
            } catch (e: Throwable) {
                return null
            }
        }
    }

    // Per-class cache to avoid IllegalArgumentException when multiple SendReadReceiptJob subclasses exist
    private val cachedMessageIdsFields = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

    override fun doHook() {
        runCatching { hookSendReadReceiptJob() }.onFailure { log(it) }
        runCatching { hookReceiptMethod() }.onFailure { log(it) }
        runCatching { hookSenderPlayed() }.onFailure { log(it) }
        runCatching { hookSenderPlayedBusiness() }.onFailure { log(it) }
    }

    private val isGhostMode: Boolean get() = WppCore.getPrivBoolean("ghostmode", false)
    private val isHideRead: Boolean get() = prefs.getBoolean("hideread", false)
    private val isHideAudioSeen: Boolean get() = prefs.getBoolean("hideaudioseen", false)
    private val isHideOnceSeen: Boolean get() = prefs.getBoolean("hideonceseen", false)
    private val isHideReadGroup: Boolean get() = prefs.getBoolean("hideread_group", false)
    private val isHideStatusView: Boolean get() = prefs.getBoolean("hidestatusview", false)
    private val isHideReceipt: Boolean get() = prefs.getBoolean("hidereceipt", false)

    private fun hookSendReadReceiptJob() {
        val sendReadReceiptJobMethod = Unobfuscator.loadHideViewSendReadJob(classLoader)
        val sendJobClass = try {
            Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "SendReadReceiptJob"
            )
        } catch (_: Throwable) { null }

        XposedBridge.hookMethod(sendReadReceiptJobMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val job = param.thisObject ?: return
                val hasBlueOnReply =
                    XposedHelpers.getAdditionalInstanceField(job, "blue_on_reply") as? Boolean
                        ?: false

                // Job yang dibuat oleh SeenTick sendiri — biarkan lewat
                if (hasBlueOnReply) return

                val jobClassName = job.javaClass.name
                val isSendJob = sendJobClass?.isInstance(job) == true ||
                        jobClassName.contains("SendReadReceiptJob") ||
                        sendReadReceiptJobMethod.declaringClass.isInstance(job)

                if (!isSendJob) return

                val userJid = FMessageWpp.UserJid.extractFrom(job)
                if (userJid == null || userJid.isNull) return

                val isInvalidJid = listOf(userJid.phoneRawString, userJid.userRawString)
                    .any { it?.contains("lid_me") == true || it?.contains("status_me") == true }

                if (isInvalidJid) return

                val blueOnReply = Utils.isBlueOnReplyEnabled(prefs)
                val privacy = CustomPrivacy.getJSON(userJid.phoneNumber)
                val isHide = processReadReceiptByType(param, job, userJid, privacy)

                logDebug("[HideSeen] hookSendReadReceiptJob: job=$jobClassName, userJid=$userJid, isHide=$isHide, blueOnReply=$blueOnReply, hideread=$isHideRead, ghost=$isGhostMode")

                if (blueOnReply) {
                    param.result = null
                    dbExecutor.execute {
                        runCatching { recordHiddenMessages(job, userJid) }.onFailure { log(it) }
                    }
                    return
                }

                if (isHide) {
                    dbExecutor.execute {
                        runCatching { recordHiddenMessages(job, userJid) }.onFailure { log(it) }
                    }
                }
            }
        })
    }

    private fun processReadReceiptByType(
        param: XC_MethodHook.MethodHookParam,
        job: Any,
        userJid: FMessageWpp.UserJid,
        privacy: JSONObject
    ): Boolean {
        val ghost = isGhostMode
        return when {
            userJid.isGroup -> {
                if (privacy.optBoolean("HideSeen", isHideReadGroup) || ghost) {
                    param.result = null
                    true
                } else false
            }

            userJid.isStatus -> {
                val participant = XposedHelpers.getObjectField(job, "participant") as? String
                val statusJid = FMessageWpp.UserJid(participant)
                val customHideStatusView = CustomPrivacy.getJSON(statusJid.phoneNumber)
                    .optBoolean("HideViewStatus", isHideStatusView)

                if (customHideStatusView || ghost) {
                    param.result = null
                }
                false
            }

            else -> {
                if (privacy.optBoolean("HideSeen", isHideRead) || ghost) {
                    param.result = null
                    true
                } else false
            }
        }
    }

    private fun recordHiddenMessages(sendReadReceiptJob: Any, userJid: FMessageWpp.UserJid) {
        val messageIds = try {
            val jobClass = sendReadReceiptJob.javaClass
            val field = cachedMessageIdsFields.getOrPut(jobClass) {
                jobClass.declaredFields.firstOrNull {
                    it.type.isArray && it.type.componentType == String::class.java
                }?.also { it.isAccessible = true } ?: return
            }
            field.get(sendReadReceiptJob) as? Array<*>
        } catch (_: Throwable) {
            null
        } ?: return

        val ids = messageIds.mapNotNull { it as? String }
        if (ids.isEmpty()) return

        val primaryJid = userJid.phoneRawString ?: userJid.userRawString ?: return
        val fullUserRaw = runCatching {
            userJid.userJid?.let { XposedHelpers.callMethod(it, "getRawString") as? String }
        }.getOrNull()

        val jidsToInsert = linkedSetOf<String>()
        jidsToInsert.add(primaryJid)
        if (!userJid.userRawString.isNullOrBlank()) jidsToInsert.add(userJid.userRawString!!)
        if (!fullUserRaw.isNullOrBlank()) jidsToInsert.add(fullUserRaw)

        for (id in ids) {
            for (jid in jidsToInsert) {
                MessageHistoryStore.getInstance().insertHideSeenMessage(
                    jid,
                    id,
                    MessageHistoryStore.ReceiptType.READ,
                    false
                )
            }
        }
    }

    private fun hookReceiptMethod() {
        val receiptMethod = runCatching { Unobfuscator.loadReceiptMethod(classLoader) }.getOrNull() ?: return

        XposedBridge.hookMethod(receiptMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // No global-toggle guard here: checkPrivacyAndHideReceipt / checkPrivacyAndHideSeen
                // already fall back to the global prefs when there is no per-contact override.
                // A guard based on global toggles would silently skip contacts that have an
                // independent CustomPrivacy override even when every global toggle is off.
                runCatching {
                    val protocolTreeNodeWpp = ProtocolTreeNodeWpp(param.result ?: return)

                    // Critical Guard: Only process actual <receipt> stanzas, never <message> or other stanzas!
                    if (protocolTreeNodeWpp.tag != "receipt") return

                    val fmessageKey = generateFMessageKey(protocolTreeNodeWpp) ?: return
                    if (fmessageKey.remoteJid.isStatus) return

                    val primaryJid = fmessageKey.remoteJid.phoneRawString ?: fmessageKey.remoteJid.userRawString ?: return

                    val hideSeenItem = MessageHistoryStore.getInstance().getHideSeenMessage(
                        primaryJid,
                        fmessageKey.messageID,
                        MessageHistoryStore.ReceiptType.READ
                    )

                    if (hideSeenItem?.viewed == true) return

                    val hideReceiptActive = checkPrivacyAndHideReceipt(fmessageKey)
                    val hideSeenActive = checkPrivacyAndHideSeen(fmessageKey)

                    if (hideReceiptActive) {
                        val typeKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "type" }
                        if (typeKV == null) {
                            protocolTreeNodeWpp.addKeyValue("type", "inactive")
                            protocolTreeNodeWpp.removeAllKeyValuesByKey("sts")
                        } else if (typeKV.value == "delivery") {
                            typeKV.value = "inactive"
                            protocolTreeNodeWpp.removeAllKeyValuesByKey("sts")
                        }
                    } else if (hideSeenActive) {
                        val typeKV = protocolTreeNodeWpp.attributes.firstOrNull { it.key == "type" }
                        if (typeKV?.value == "read") {
                            protocolTreeNodeWpp.removeAllKeyValuesByKey("sts")
                            protocolTreeNodeWpp.removeAllKeyValuesByKey("type")
                        }
                    }
                }
            }
        })
    }

    private fun checkPrivacyAndHideReceipt(fmessageKey: FMessageWpp.Key): Boolean {
        val privacy = CustomPrivacy.getJSON(fmessageKey.remoteJid.phoneNumber)
        val customHideReceipt = privacy.optBoolean("HideReceipt", isHideReceipt)
        return customHideReceipt || isGhostMode
    }

    private fun checkPrivacyAndHideSeen(fmessageKey: FMessageWpp.Key): Boolean {
        val privacy = CustomPrivacy.getJSON(fmessageKey.remoteJid.phoneNumber)
        val hideKey = if (fmessageKey.remoteJid.isGroup) isHideReadGroup else isHideRead
        val shouldHide = privacy.optBoolean("HideSeen", hideKey) || isGhostMode
        return shouldHide
    }

    private fun hookSenderPlayed() {
        val loadSenderPlayed = Unobfuscator.loadSenderPlayedMethod(classLoader)

        XposedBridge.hookMethod(loadSenderPlayed, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fMessage = FMessageWpp(param.args[0] ?: return)
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

                val fMessage = FMessageWpp(set.first() ?: return)
                processSenderPlayed(param, fMessage)
            }
        })
    }

    private fun processSenderPlayed(param: XC_MethodHook.MethodHookParam, fMessage: FMessageWpp) {
        val ghost = isGhostMode
        val hideOnce = isHideOnceSeen
        val isHideViewOnce = (hideOnce || ghost) && fMessage.isViewOnce
        val isHideVoiceNote =
            (isHideAudioSeen || ghost) && fMessage.mediaType == MEDIA_TYPE_VOICE_NOTE
        val key = fMessage.key

        val primaryJid = key.remoteJid.phoneRawString ?: key.remoteJid.userRawString ?: return

        if (isHideViewOnce || isHideVoiceNote) {
            param.result = null
            dbExecutor.execute {
                runCatching {
                    MessageHistoryStore.getInstance().insertHideSeenMessage(
                        primaryJid,
                        key.messageID,
                        MessageHistoryStore.ReceiptType.PLAYED,
                        false
                    )
                }.onFailure { log(it) }
            }
        }

        if (fMessage.isViewOnce && !hideOnce && !ghost) {
            dbExecutor.execute {
                runCatching {
                    MessageHistoryStore.getInstance().apply {
                        updateViewedMessage(
                            primaryJid,
                            key.messageID,
                            MessageHistoryStore.ReceiptType.PLAYED,
                            true
                        )
                        updateViewedMessage(primaryJid, key.messageID, MessageHistoryStore.ReceiptType.READ, true)
                    }
                }.onFailure { log(it) }
            }
        }
    }

    override fun getPluginName(): String {
        return "Hide Seen"
    }
}