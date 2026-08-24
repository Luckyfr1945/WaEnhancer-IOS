package com.wmods.wppenhacer.xposed.features.general

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.getContactName
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp.Companion.getWaContactFromJid
import com.wmods.wppenhacer.xposed.core.db.StatusReplayStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class StatusReplayTracker(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    companion object {
        const val TAG = "StatusReplayTracker"

        @Volatile
        var currentActiveStatusKey: String = ""
            set(value) {
                field = value
                if (value.isNotEmpty()) {
                    // Warm in-memory cache di background SEBELUM viewer list dirender,
                    // supaya hookViewerRowBind() di bawah tidak pernah menyentuh SQLite.
                    StatusReplayStore.getInstance().preloadStatusReplaysAsync(value)
                }
            }

        // Tracks whether we already dynamically hooked the live adapter's onBindViewHolder
        // so we don't re-hook every time the Fragment view is recreated.
        @Volatile
        private var dynamicBindHookInstalled = AtomicBoolean(false)
    }

    private val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    override fun doHook() {
        if (!prefs.getBoolean("status_replay_tracker", false)) return
        logDebug("StatusReplayTracker: Feature enabled")

        runCatching { hookActiveStatusTracking() }
            .onFailure { logDebug("StatusReplayTracker: activeStatusTracking error: ${it.message}") }
        runCatching { hookSeenReceipt() }
            .onFailure { logDebug("StatusReplayTracker: seenReceipt hook error: ${it.message}") }
        runCatching { hookViewerRowBind() }
            .onFailure { logDebug("StatusReplayTracker: viewerRowBind hook error: ${it.message}") }
    }

    // 1. Lacak slide status yang sedang aktif ---------------------------------
    private fun hookActiveStatusTracking() {
        val method = Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val safeArgs = param.args?.filterNotNull() ?: return
                var fMessage = safeArgs.firstOrNull { FMessageWpp.TYPE.isInstance(it) }?.let { FMessageWpp(it) }
                if (fMessage == null) {
                    val statusItem = StatusItemWpp.from(param.args?.getOrNull(0))
                    fMessage = statusItem?.fMessage
                }
                val msgId = fMessage?.key?.messageID
                if (!msgId.isNullOrEmpty()) {
                    currentActiveStatusKey = msgId
                    StatusReplayStore.getInstance().preloadStatusReplaysAsync(msgId)
                    logDebug("StatusReplayTracker: Active status slide = $msgId")
                }
            }
        })
    }

    // 2. Catat seen receipt asli (receiptType 13)
    private fun hookSeenReceipt() {
        val method = Unobfuscator.loadSeenReceiptForStatus(classLoader)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val userJidArg = param.args.getOrNull(0)
                    val statusObj = param.args.getOrNull(1)
                    val receiptType = param.args.getOrNull(2) as? Int ?: (param.args.firstOrNull { it is Int } as? Int ?: -1)
                    val timestamp = param.args.getOrNull(3) as? Long ?: System.currentTimeMillis()

                    if (receiptType != 13) return // abaikan delivery receipt (5) & lainnya

                    logDebug(
                        "StatusReplayTracker: seenReceipt invoked: userJid=$userJidArg " +
                        "statusObj=${statusObj?.javaClass?.name} receiptType=$receiptType ts=$timestamp"
                    )

                    val statusId = statusObj?.let { extractStatusIdFromObject(it) } ?: run {
                        logDebug("StatusReplayTracker: seenReceipt statusId could not be extracted from $statusObj")
                        return
                    }

                    val userJid = UserJid(userJidArg)
                    val rawJid = userJid.rawJidString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneRawString?.takeIf { it.isNotEmpty() }
                        ?: userJid.phoneNumber?.takeIf { it.isNotEmpty() }
                        ?: run {
                            logDebug("StatusReplayTracker: seenReceipt rawJid could not be resolved from $userJidArg")
                            return
                        }

                    val record = StatusReplayStore.getInstance().recordStatusView(statusId, rawJid)
                    logDebug("StatusReplayTracker: view tercatat statusId=$statusId jid=$rawJid count=${record.viewCount}")

                    if (record.viewCount > 1 && prefs.getBoolean("toast_status_replay", false)) {
                        val contact = getWaContactFromJid(userJid)
                        val contactName = contact?.displayName?.takeIf { it.isNotEmpty() }
                            ?: getContactName(userJid)?.takeIf { it.isNotEmpty() }
                            ?: userJid.phoneNumber ?: rawJid
                        Utils.showToast(
                            Utils.application.getString(R.string.replayed_your_status, contactName, record.viewCount),
                            Toast.LENGTH_SHORT
                        )
                    }
                } catch (e: Throwable) {
                    logDebug("StatusReplayTracker: seenReceipt processing error: ${e.message}")
                }
            }
        })
    }

    private fun extractStatusIdFromObject(statusObj: Any): String? {
        if (statusObj is String && statusObj.length >= 10) return statusObj

        // 1. Cek semua declared field String di hierarki class
        var cls: Class<*>? = statusObj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type == String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    try {
                        f.isAccessible = true
                        val v = f.get(statusObj) as? String
                        if (!v.isNullOrEmpty() && v.length >= 10) {
                            return v
                        }
                    } catch (_: Throwable) {}
                }
            }
            cls = cls.superclass
        }

        // 2. Cek nested object fields (mis. Key, Model, FMessage)
        cls = statusObj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!f.type.isPrimitive && f.type != String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    try {
                        f.isAccessible = true
                        val child = f.get(statusObj) ?: continue
                        var childCls: Class<*>? = child.javaClass
                        while (childCls != null && childCls != Any::class.java) {
                            for (cf in childCls.declaredFields) {
                                if (cf.type == String::class.java && !java.lang.reflect.Modifier.isStatic(cf.modifiers)) {
                                    try {
                                        cf.isAccessible = true
                                        val cv = cf.get(child) as? String
                                        if (!cv.isNullOrEmpty() && cv.length >= 10) {
                                            return cv
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                            childCls = childCls.superclass
                        }
                    } catch (_: Throwable) {}
                }
            }
            cls = cls.superclass
        }

        // 3. Fallback: regex search hex message ID di toString()
        val str = statusObj.toString()
        val hexMatch = Regex("[A-F0-9]{16,32}").find(str)
        if (hexMatch != null) return hexMatch.value

        // 4. Fallback ke currentActiveStatusKey jika ada
        if (currentActiveStatusKey.isNotEmpty()) return currentActiveStatusKey

        return null
    }

    // 3. Hook BIND row penonton status — pendekatan dua-fase.
    //
    // Fase A (string-anchor): Unobfuscator mencoba resolve onBindViewHolder secara statik
    //   menggunakan string "StatusDetailsAdapter/getPrimaryName" sebagai anchor.
    //   Kalau berhasil, firstParam bukan View → hook langsung per-baris (idealnya).
    //
    // Fase B (runtime probe): Kalau resolver jatuh ke fallback
    //   "StatusPlaybackPage/onViewCreated", param pertama method itu adalah View
    //   (bukan model data). Dalam kasus ini kita gunakan callback itu SEKALI untuk:
    //     1. Scan hierarki View mencari RecyclerView viewer list.
    //     2. Ambil adapter live-nya via RecyclerView.getAdapter().
    //     3. Hook onBindViewHolder class adapter tersebut secara dinamis (late-hook).
    //   Setelah hook dipasang, flag dynamicBindHookInstalled mencegah re-hook.
    private fun hookViewerRowBind() {
        val resolvedMethod = Unobfuscator.loadStatusViewerRowBindMethod(classLoader)
        val firstParamIsView = resolvedMethod.parameterCount >= 1 &&
            resolvedMethod.parameterTypes[0] == View::class.java

        logDebug(
            "StatusReplayTracker: Bind method resolved: " +
            "${resolvedMethod.declaringClass.name}#${resolvedMethod.name}" +
            "(${resolvedMethod.parameterTypes.joinToString { it.simpleName }}) " +
            "probeMode=$firstParamIsView"
        )

        if (!firstParamIsView) {
            // Fase A: method adalah onBindViewHolder atau custom bind — hook langsung
            installDirectBindHook(resolvedMethod)
        } else {
            // Fase B: method adalah Fragment lifecycle probe.
            // Method X.571#A0p(View) dipanggil saat StatusPlaybackPage dibuat — bukan saat
            // viewer bottom sheet dibuka. Karena itu RecyclerView belum ada di hierarki view
            // pada saat callback ini fired.
            //
            // Strategi: scan field-field thisObject (Fragment/Page) untuk menemukan RecyclerView
            // atau Adapter yang tersimpan sebagai field. Jika tidak ada, log semua field class
            // untuk diagnosis, dan gunakan view.post{} agar View sudah ter-layout.
            XposedBridge.hookMethod(resolvedMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (dynamicBindHookInstalled.get()) return
                    try {
                        val pageObj = param.thisObject
                        val rootView = param.args.getOrNull(0) as? View

                        // Attempt 1: scan fields of the Page/Fragment object for RecyclerView or Adapter
                        val rvFromField = findRecyclerViewInFields(pageObj)
                        if (rvFromField != null) {
                            tryInstallHookFromRecyclerView(rvFromField)
                            return
                        }

                        // Attempt 2: deferred — post to rootView handler after layout is done
                        // This handles the case where the viewer list is lazy-inflated
                        if (rootView != null) {
                            rootView.post {
                                if (dynamicBindHookInstalled.get()) return@post
                                // Try view hierarchy again after layout
                                val rv = (rootView as? ViewGroup)?.let { findRecyclerView(it) }
                                    ?: findRecyclerViewInFields(pageObj)
                                if (rv != null) {
                                    tryInstallHookFromRecyclerView(rv)
                                } else {
                                    // Diagnostic: dump all field names + types of thisObject
                                    val sb = StringBuilder("StatusReplayTracker: probe fields of ${pageObj.javaClass.name}: ")
                                    var cls: Class<*>? = pageObj.javaClass
                                    while (cls != null && cls != Any::class.java) {
                                        for (f in cls.declaredFields) {
                                            f.isAccessible = true
                                            val v = try { f.get(pageObj) } catch (_: Throwable) { null }
                                            sb.append("${f.name}(${f.type.simpleName})=${v?.javaClass?.simpleName} ")
                                        }
                                        cls = cls.superclass
                                    }
                                    logDebug(sb.toString())
                                }
                            }
                        } else {
                            // Diagnostic: dump fields immediately
                            val sb = StringBuilder("StatusReplayTracker: probe fields of ${pageObj.javaClass.name}: ")
                            var cls: Class<*>? = pageObj.javaClass
                            while (cls != null && cls != Any::class.java) {
                                for (f in cls.declaredFields) {
                                    f.isAccessible = true
                                    val v = try { f.get(pageObj) } catch (_: Throwable) { null }
                                    sb.append("${f.name}(${f.type.simpleName})=${v?.javaClass?.simpleName} ")
                                }
                                cls = cls.superclass
                            }
                            logDebug(sb.toString())
                        }
                    } catch (e: Throwable) {
                        logDebug("StatusReplayTracker: probe error: ${e.message}")
                    }
                }
            })
        }
    }

    /** Temukan RecyclerView pertama dalam hierarki ViewGroup secara rekursif. */
    private fun findRecyclerView(root: ViewGroup): RecyclerView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is RecyclerView) return child
            if (child is ViewGroup) {
                val found = findRecyclerView(child)
                if (found != null) return found
            }
        }
        return null
    }

    /** Scan semua field dari sebuah object (termasuk superclass) mencari RecyclerView. */
    private fun findRecyclerViewInFields(obj: Any): RecyclerView? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!RecyclerView::class.java.isAssignableFrom(f.type)) continue
                return try {
                    f.isAccessible = true
                    f.get(obj) as? RecyclerView
                } catch (_: Throwable) { null }
            }
            cls = cls.superclass
        }
        return null
    }

    /** Dari RecyclerView yang sudah diketahui, ambil adapter dan pasang hook. */
    private fun tryInstallHookFromRecyclerView(rv: RecyclerView) {
        val adapter = rv.adapter ?: run {
            logDebug("StatusReplayTracker: probe — RecyclerView has no adapter yet")
            return
        }
        logDebug("StatusReplayTracker: probe — found adapter class: ${adapter.javaClass.name}")

        val bindVH = adapter.javaClass.declaredMethods.firstOrNull { m ->
            m.name == "onBindViewHolder" && m.parameterCount == 2 &&
            m.parameterTypes[1] == Int::class.java
        } ?: run {
            logDebug("StatusReplayTracker: probe — onBindViewHolder not found in ${adapter.javaClass.name}")
            return
        }

        if (dynamicBindHookInstalled.compareAndSet(false, true)) {
            logDebug("StatusReplayTracker: installing dynamic bind hook on ${adapter.javaClass.name}#${bindVH.name}")
            installDirectBindHook(bindVH)
        }
    }

    /** Hook onBindViewHolder yang sudah diketahui class-nya (Fase A atau hasil probe Fase B). */
    private fun installDirectBindHook(bindMethod: java.lang.reflect.Method) {
        XposedBridge.hookMethod(bindMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // param.thisObject = Adapter instance (e.g. X.49F)
                    // param.args[0] = ViewHolder (e.g. X.4ZC extending RecyclerView.ViewHolder)
                    // param.args[1] = position (Int)
                    val viewHolder = param.args.getOrNull(0) ?: return
                    val position = (param.args.getOrNull(1) as? Int) ?: -1
                    val adapter = param.thisObject

                    // Ambil itemView dari ViewHolder
                    val itemView = (try {
                        XposedHelpers.getObjectField(viewHolder, "itemView") as? View
                    } catch (_: Throwable) { null }) ?: (viewHolder as? View)

                    // A06 = Time TextView ("Baru saja", "1 menit yang lalu")
                    // A07 = Name TextView ("bintang Coy Bintang Di Langit")
                    val timeTextView = (try {
                        XposedHelpers.getObjectField(viewHolder, "A06") as? TextView
                    } catch (_: Throwable) { null }) ?: (itemView as? ViewGroup)?.let { findTimeTextView(it) }

                    val nameTextView = (try {
                        XposedHelpers.getObjectField(viewHolder, "A07") as? TextView
                    } catch (_: Throwable) { null }) ?: (itemView as? ViewGroup)?.let { findNameTextView(it) }

                    // Ambil row data model dari adapter.A00[position]
                    val list = try { (XposedHelpers.getObjectField(adapter, "A00") as? List<*>) } catch (_: Throwable) { null }
                    val rowModel = list?.getOrNull(position) ?: viewHolder

                    val viewerJid = extractJidFromRowModel(rowModel)
                        ?: extractJidFromRowModel(viewHolder)
                        ?: param.args.getOrNull(0)?.let { extractJidFromRowModel(it) }

                    val statusId = currentActiveStatusKey
                    val record = if (statusId.isNotEmpty() && !viewerJid.isNullOrEmpty())
                        StatusReplayStore.getInstance().getReplayRecordCached(statusId, viewerJid)
                            ?: StatusReplayStore.getInstance().getReplayRecord(statusId, viewerJid)
                    else null

                    logDebug(
                        "StatusReplayTracker: bind row pos=$position jid=$viewerJid " +
                        "time='${timeTextView?.text}' name='${nameTextView?.text}' viewCount=${record?.viewCount ?: 0}"
                    )

                    if (timeTextView != null && record != null && record.viewCount > 1) {
                        val applyBadge = {
                            val current = timeTextView.text?.toString().orEmpty()
                            val countSuffix = " • 🔄 Dilihat ${record.viewCount}x"
                            if (!current.contains("🔄")) {
                                val original = (timeTextView.getTag(R.id.status_replay_original_text) as? String)
                                    ?: current.also { if (it.isNotEmpty()) timeTextView.setTag(R.id.status_replay_original_text, it) }
                                timeTextView.text = if (original.isNotEmpty()) {
                                    Utils.application.getString(R.string.status_replay_count_suffix, original, record.viewCount)
                                } else {
                                    countSuffix.trimStart()
                                }
                            }
                            itemView?.setOnClickListener {
                                showReplayHistoryDialog(itemView, nameTextView?.text?.toString().orEmpty(), record)
                            }
                            itemView?.isClickable = true
                        }
                        applyBadge()
                        timeTextView.post { applyBadge() }
                    } else if (timeTextView != null && itemView != null) {
                        resetRow(timeTextView, itemView)
                    }
                } catch (e: Throwable) {
                    logDebug("StatusReplayTracker: viewerRowBind error: ${e.message}")
                }
            }
        })
    }

    private fun resetRow(timeTextView: TextView, itemView: View) {
        val original = timeTextView.getTag(R.id.status_replay_original_text) as? String
        if (original != null) {
            timeTextView.text = original
            timeTextView.setTag(R.id.status_replay_original_text, null)
        }
        itemView.setOnClickListener(null)
        itemView.isClickable = false
    }

    private fun extractJidFromRowModel(rowModel: Any): String? {
        if (rowModel is UserJid) return rowModel.rawJidString
        // Try scanning all fields for JID object
        UserJid.extractFrom(rowModel)?.rawJidString?.takeIf { it.isNotEmpty() }?.let { return it }

        var cls: Class<*>? = rowModel.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(rowModel) ?: continue
                    val uJid = UserJid(v)
                    val raw = uJid.rawJidString?.takeIf { it.isNotEmpty() }
                        ?: uJid.phoneRawString?.takeIf { it.isNotEmpty() }
                        ?: uJid.phoneNumber
                    if (raw != null && raw.isNotEmpty()) return raw
                } catch (_: Throwable) {}
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findTimeTextView(root: ViewGroup): TextView? {
        val candidateIds = listOf("date_time", "receipt_date", "date", "time", "status_date", "view_time")
        for (resName in candidateIds) {
            val tv = findTextViewById(root, resName)
            if (tv != null) return tv
        }
        // Fallback: search all child TextViews for timestamp / relative time pattern
        return findTextViewByPattern(root) { text ->
            text.startsWith("Hari ini", true) || text.startsWith("Kemarin", true) ||
            text.startsWith("Today", true) || text.startsWith("Yesterday", true) ||
            text.startsWith("Baru saja", true) || text.startsWith("Just now", true) ||
            text.contains("lalu", true) || text.contains("ago", true) ||
            Regex("\\d{1,2}[:.]\\d{2}").containsMatchIn(text)
        }
    }

    private fun findNameTextView(root: ViewGroup): TextView? {
        val candidateIds = listOf("name", "contact_name", "title", "conversations_row_contact_name")
        for (resName in candidateIds) {
            val tv = findTextViewById(root, resName)
            if (tv != null) return tv
        }
        // Fallback: find any TextView that is not the timestamp
        val timeTv = findTimeTextView(root)
        return findTextViewByPattern(root) { _, tv -> tv !== timeTv }
    }

    private fun findTextViewById(root: ViewGroup, resName: String): TextView? {
        return try {
            val id = Utils.getID(resName, "id")
            if (id == 0) null else root.findViewById(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findTextViewByPattern(
        group: ViewGroup,
        predicate: (String, TextView) -> Boolean = { text, _ -> true }
    ): TextView? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView) {
                val text = child.text?.toString()?.trim().orEmpty()
                if (predicate(text, child)) return child
            } else if (child is ViewGroup) {
                val found = findTextViewByPattern(child, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findTextViewByPattern(group: ViewGroup, predicate: (String) -> Boolean): TextView? {
        return findTextViewByPattern(group) { text, _ -> text.isNotEmpty() && predicate(text) }
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
            val displayName = contactName.ifEmpty { record.viewerJid }

            val sb = StringBuilder()
            sb.append(
                Utils.application.getString(
                    R.string.status_replay_history_message, record.viewCount, displayName
                )
            )
            record.history.forEachIndexed { index, ts ->
                sb.append("${index + 1}. ${timeFormat.format(Date(ts))}\n")
            }

            AlertDialogWpp(activity)
                .setTitle(Utils.application.getString(R.string.status_replay_history_title))
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Throwable) {
            logDebug("StatusReplayTracker: Error showing history dialog: ${e.message}")
        }
    }

    override fun getPluginName(): String = "Status Replay Tracker"
}