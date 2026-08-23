package com.wmods.wppenhacer.xposed.features.general

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.getContactName
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid
import com.wmods.wppenhacer.xposed.core.components.FStatusWpp
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp.Companion.getWaContactFromJid
import com.wmods.wppenhacer.xposed.core.db.MessageStore.Companion.getInstance
import com.wmods.wppenhacer.xposed.core.db.StatusReplayStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.findFirstClassUsingName
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadOnInsertReceipt
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadSeenReceiptForStatus
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadUnknownStatusPlaybackMethod
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

class StatusReplayTracker(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    companion object {
        const val TAG = "StatusReplayTracker"
        @Volatile
        var currentActiveStatusKey: String = ""
    }

    private val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())



    override fun doHook() {
        if (!prefs.getBoolean("status_replay_tracker", false)) return

        logDebug("StatusReplayTracker: Feature enabled")

        // 1. Hook active status playback page to keep track of the current status key
        try {
            val unknownStatusPlaybackMethod = loadUnknownStatusPlaybackMethod(classLoader)
            XposedBridge.hookMethod(unknownStatusPlaybackMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val safeArgs = param.args?.filterNotNull() ?: return
                    var fMessage = safeArgs.firstOrNull { FMessageWpp.TYPE.isInstance(it) }?.let { FMessageWpp(it) }
                    if (fMessage == null) {
                        val arg0 = param.args?.getOrNull(0)
                        val statusItem = StatusItemWpp.from(arg0)
                        fMessage = statusItem?.fMessage
                    }
                    if (fMessage != null) {
                        val msgId = fMessage.key.messageID
                        if (msgId.isNotEmpty()) {
                            currentActiveStatusKey = msgId
                            logDebug("StatusReplayTracker: Active status slide = $currentActiveStatusKey")
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: loadUnknownStatusPlaybackMethod error: ${e.message}")
        }

        // 2. Hook direct seen receipt for status
        try {
            val onSeenReceiptForStatus = loadSeenReceiptForStatus(classLoader)
            XposedBridge.hookMethod(onSeenReceiptForStatus, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val receiptType = param.args[1] as? Int ?: return
                    if (receiptType != 13) return

                    val fStatusField = ReflectionUtils.findFieldUsingFilter(param.thisObject.javaClass) { f ->
                        FStatusWpp.TYPE.isAssignableFrom(f.type)
                    } ?: return

                    val fStatus = FStatusWpp(fStatusField.get(param.thisObject))
                    if (!fStatus.fStatusKey.isFromMe) return

                    val statusKey = fStatus.fStatusKey.messageID
                    if (statusKey.isEmpty()) return

                    val userJid = UserJid(param.args[0])
                    val rawJid = userJid.rawJidString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneRawString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneNumber?.takeIf { it.isNotEmpty() }
                        ?: return

                    val record = StatusReplayStore.getInstance().recordStatusView(statusKey, rawJid)
                    logDebug("StatusReplayTracker: Status $statusKey viewed by $rawJid, total count: ${record.viewCount}")

                    if (record.viewCount > 1 && prefs.getBoolean("toast_status_replay", false)) {
                        val contact = getWaContactFromJid(userJid)
                        val contactName = contact?.displayName?.takeIf { it.isNotEmpty() }
                            ?: getContactName(userJid)?.takeIf { it.isNotEmpty() }
                            ?: userJid.phoneNumber
                            ?: rawJid
                        val msg = Utils.application.getString(R.string.replayed_your_status, contactName, record.viewCount)
                        Utils.showToast(msg, Toast.LENGTH_SHORT)
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: onSeenReceiptForStatus hook error: ${e.message}")
        }

        // 3. Hook batch receipt insertion (backup receipt hook)
        try {
            val onInsertReceipt = loadOnInsertReceipt(classLoader)
            XposedBridge.hookMethod(onInsertReceipt, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    processBatchReceipts(param)
                }
            })
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: onInsertReceipt hook error: ${e.message}")
        }

        // 4. Hook Status Viewers List to show Replay Count badge & details
        try {
            hookStatusViewersList()
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: hookStatusViewersList error: ${e.message}")
        }
    }

    private fun processBatchReceipts(param: XC_MethodHook.MethodHookParam) {
        try {
            val collection = if (param.args[0] !is MutableCollection<*>) {
                mutableSetOf<Any?>(param.args[0])
            } else {
                param.args[0] as MutableCollection<*>
            }
            val jidClass = findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.Jid")

            for (messageStatusUpdateReceipt in collection) {
                if (messageStatusUpdateReceipt == null) continue
                val fieldByType = ReflectionUtils.getFieldByType(
                    messageStatusUpdateReceipt.javaClass,
                    Int::class.javaPrimitiveType
                )
                val fieldId = ReflectionUtils.getFieldByType(
                    messageStatusUpdateReceipt.javaClass,
                    Long::class.javaPrimitiveType
                )
                val fieldByUserJid = ReflectionUtils.getFieldByExtendType(
                    messageStatusUpdateReceipt.javaClass,
                    jidClass
                )
                val type = fieldByType?.getInt(messageStatusUpdateReceipt) ?: continue
                if (type != 13) continue

                val id = fieldId?.getLong(messageStatusUpdateReceipt) ?: continue
                val userJid = UserJid(fieldByUserJid?.get(messageStatusUpdateReceipt))

                CompletableFuture.runAsync {
                    try {
                        val sql = getInstance().getDatabase() ?: return@runAsync
                        checkAndRecordFromDatabase(sql, id, userJid)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun checkAndRecordFromDatabase(sql: SQLiteDatabase, messageRowId: Long, userJid: UserJid) {
        try {
            sql.query(
                "message",
                arrayOf("key_id", "participant_hash", "chat_row_id", "from_me"),
                "_id = ?",
                arrayOf(messageRowId.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return
                val keyId = cursor.getString(0) ?: return
                val participantHash = cursor.getString(1)
                val fromMe = cursor.getInt(3) == 1

                if (participantHash != null && fromMe) {
                    val rawJid = userJid.rawJidString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneRawString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneNumber?.takeIf { it.isNotEmpty() }
                        ?: return

                    val record = StatusReplayStore.getInstance().recordStatusView(keyId, rawJid)
                    logDebug("StatusReplayTracker (DB): Status $keyId viewed by $rawJid, total count: ${record.viewCount}")

                    if (record.viewCount > 1 && prefs.getBoolean("toast_status_replay", false)) {
                        val contact = getWaContactFromJid(userJid)
                        val contactName = contact?.displayName?.takeIf { it.isNotEmpty() }
                            ?: getContactName(userJid)?.takeIf { it.isNotEmpty() }
                            ?: userJid.phoneNumber
                            ?: rawJid
                        val msg = Utils.application.getString(R.string.replayed_your_status, contactName, record.viewCount)
                        Utils.showToast(msg, Toast.LENGTH_SHORT)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookStatusViewersList() {
        val GUARD_TAG = "srt_formatted"
        val setTextHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val textView = param.thisObject as? TextView ?: return
                // Recursion guard: skip views we already processed
                if (textView.getTag(R.id.status_replay_tag) == GUARD_TAG) return

                try {
                    val text = textView.text?.toString() ?: return
                    if (text.isEmpty() || text.contains("🔄") || text.contains(" • ")) return

                    val isTimestamp = text.startsWith("Hari ini", ignoreCase = true) ||
                            text.startsWith("Kemarin", ignoreCase = true) ||
                            text.startsWith("Today", ignoreCase = true) ||
                            text.startsWith("Yesterday", ignoreCase = true) ||
                            Regex("^\\d{1,2}[:.:]\\d{2}").containsMatchIn(text)

                    if (!isTimestamp) return

                    val parent = textView.parent as? ViewGroup ?: return

                    // Find contact name TextView sibling in same row
                    var contactName = ""
                    fun searchInGroup(group: ViewGroup) {
                        for (i in 0 until group.childCount) {
                            val child = group.getChildAt(i)
                            if (child is TextView && child !== textView) {
                                val childText = child.text?.toString()?.trim() ?: ""
                                if (childText.isNotEmpty() && !childText.matches(Regex(".*\\d{1,2}[:.]\\d{2}.*"))) {
                                    contactName = childText
                                    return
                                }
                            } else if (child is ViewGroup) {
                                searchInGroup(child)
                                if (contactName.isNotEmpty()) return
                            }
                        }
                    }
                    searchInGroup(parent)

                    if (contactName.isEmpty()) return

                    val currentStatusId = currentActiveStatusKey
                    val replays = if (currentStatusId.isNotEmpty())
                        StatusReplayStore.getInstance().getAllReplaysForStatus(currentStatusId)
                    else emptyMap()

                    val matchedRecord = replays.values.firstOrNull { record ->
                        val uJid = UserJid(record.viewerJid)
                        val cName = getContactName(uJid)
                            ?: getWaContactFromJid(uJid)?.displayName
                            ?: uJid.phoneNumber ?: ""
                        cName.equals(contactName, ignoreCase = true) ||
                                record.viewerJid.contains(contactName)
                    }

                    if (matchedRecord != null && matchedRecord.viewCount > 1) {
                        // Mark view as processed before setting text (prevents recursion)
                        textView.setTag(R.id.status_replay_tag, GUARD_TAG)
                        textView.text = "$text • 🔄 Dilihat ${matchedRecord.viewCount}x"
                        parent.setOnClickListener {
                            showReplayHistoryDialog(parent, contactName, matchedRecord)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // Hook both 4-arg and 1-arg setText
        XposedHelpers.findAndHookMethod(
            TextView::class.java,
            "setText",
            CharSequence::class.java,
            TextView.BufferType::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            setTextHook
        )

        XposedHelpers.findAndHookMethod(
            TextView::class.java,
            "setText",
            CharSequence::class.java,
            setTextHook
        )
    }

    private fun getActivity(view: View): Activity? {
        var context: Context? = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    private fun showReplayHistoryDialog(anchorView: View, contactName: String, record: StatusReplayStore.ReplayRecord) {
        try {
            val activity = getActivity(anchorView) ?: return

            val sb = StringBuilder()
            sb.append("Status Anda telah diputar ulang sebanyak ${record.viewCount} kali oleh ")
            sb.append(if (contactName.isNotEmpty()) contactName else record.viewerJid)
            sb.append(":\n\n")

            record.history.forEachIndexed { index, ts ->
                val formatted = timeFormat.format(Date(ts))
                sb.append("${index + 1}. $formatted\n")
            }

            AlertDialogWpp(activity)
                .setTitle("Riwayat Putar Ulang Status")
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: Error showing history dialog: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Status Replay Tracker"
    }
}
