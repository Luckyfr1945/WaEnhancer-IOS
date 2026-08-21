package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class IosSwipeMenu(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    companion object {
        private const val TAG_KEY_POSITION = 0x7E1200A1
        private const val TAG_KEY_CONVERSATION = 0x7E1200A2

        var allowProgrammaticLongClick = false

        private const val SWIPE_THRESHOLD = 24f
        private const val MAX_TRANSLATION = 160f
        private const val ACTION_THRESHOLD_RATIO = 0.40f

        private val INT_COLOR_MORE_LIGHT = Color.parseColor("#8E8E93")
        private val INT_COLOR_MORE_DARK = Color.parseColor("#5A5A5F")
        private val INT_COLOR_ARCHIVE = Color.parseColor("#4CAF50")
        private val INT_COLOR_UNARCHIVE = Color.parseColor("#007AFF")
        
        var currentInstance: IosSwipeMenu? = null
        
        fun closeSwipeMenu() {
            currentInstance?.forceCloseOpenRow()
        }
    }

    init {
        currentInstance = this
    }

    fun forceCloseOpenRow() {
        val row = openRow ?: return
        animateChildrenBack(row)
        animateBackgroundBack(row)
        openRow = null
    }

    private class SwipeState {
        var downX = 0f
        var downY = 0f
        var isSwiping = false
        var swipedRow: View? = null
        var lastSwipeTime = 0L
    }

    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "IosSwipeMenu-Worker").apply {
            isDaemon = true
        }
    }
    private val reflectedFieldsCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()

    private var openRow: View? = null
    private val listSwipeStates = java.util.WeakHashMap<ViewGroup, SwipeState>()
    private fun stateFor(vg: ViewGroup) = listSwipeStates.getOrPut(vg) { SwipeState() }

    private val verifiedChatContainers =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ViewGroup, Boolean>())
    private val hookedInterceptClasses =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())
    private val hookedTouchClasses =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())

    @Volatile
    private var chatAdapter: android.widget.BaseAdapter? = null

    private val interceptHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val vg = param.thisObject as? ViewGroup ?: return
            if (!verifiedChatContainers.contains(vg)) return
            val ev = param.args[0] as? android.view.MotionEvent ?: return
            val state = stateFor(vg)

            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    state.downX = ev.x
                    state.downY = ev.y
                    state.isSwiping = false
                    state.swipedRow = null
                    vg.parent?.requestDisallowInterceptTouchEvent(true)

                    if (openRow != null) {
                        val row = openRow!!
                        val touchX = ev.rawX
                        val loc = IntArray(2)
                        row.getLocationOnScreen(loc)
                        val rowRightEdge = loc[0] + row.width
                        val absDx = -((row.background as? SwipeBackgroundDrawable)?.currentDx ?: 0f)
                        
                        if (touchX >= rowRightEdge - absDx) {
                            // Touch is on the buttons! Don't close the row. Let touchHook handle the click.
                            state.swipedRow = row
                            state.isSwiping = true
                            param.result = true
                            return
                        } else {
                            // Touch is outside the buttons. Close the row.
                            animateChildrenBack(row)
                            animateBackgroundBack(row)
                            openRow = null
                            param.result = true
                            return
                        }
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (state.isSwiping) return
                    val dx = ev.x - state.downX
                    val dy = ev.y - state.downY
                    if (dx < -SWIPE_THRESHOLD && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        val child = hitTestChild(vg, state.downX.toInt(), state.downY.toInt())
                        if (child != null) {
                            if (getJidStr(child) == null) return
                            if (openRow != null && openRow != child) {
                                animateChildrenBack(openRow!!)
                                animateBackgroundBack(openRow!!)
                                openRow = null
                            }
                            state.isSwiping = true
                            state.swipedRow = child
                            param.result = true
                        }
                    }
                }
            }
        }
    }

    private val touchHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val vg = param.thisObject as? ViewGroup ?: return
            if (!verifiedChatContainers.contains(vg)) return
            val state = listSwipeStates[vg] ?: return
            if (!state.isSwiping) return
            val row = state.swipedRow ?: return
            val ev = param.args[0] as? android.view.MotionEvent ?: return

            val maxTrans = Utils.dipToPixels(140f).toFloat()

            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    vg.parent?.requestDisallowInterceptTouchEvent(true)
                    val initialDx = if (openRow == row) -maxTrans else 0f
                    var dx = initialDx + (ev.x - state.downX)
                    dx = dx.coerceIn(-maxTrans, 0f)
                    translateChildren(row, dx)
                    updateSwipeBackground(row, dx)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val bg = row.background as? SwipeBackgroundDrawable
                    val currentDx = bg?.currentDx ?: 0f

                    if (openRow == row && Math.abs(ev.x - state.downX) < 10f) {
                        if (System.currentTimeMillis() - state.lastSwipeTime < 300) {
                            param.result = true
                            return
                        }

                        val touchX = ev.rawX
                        val loc = IntArray(2)
                        row.getLocationOnScreen(loc)
                        val rowRightEdge = loc[0] + row.width
                        val absDx = -currentDx

                        if (touchX >= rowRightEdge - absDx) {
                            val halfDx = absDx / 2f
                            if (touchX < rowRightEdge - halfDx) {
                                animateChildrenBack(row)
                                animateBackgroundBack(row)
                                openRow = null
                                row.postDelayed({ showIOSMenu(row) }, 50)
                            } else {
                                val isArchived = bg?.isArchived ?: isInArchivedView(row)
                                val actionType = if (isArchived) "unarchive" else "archive"
                                executeDirectAction(row, actionType)
                                animateChildrenBack(row)
                                animateBackgroundBack(row)
                                openRow = null
                            }
                        }
                    } else {
                        val threshold = maxTrans * ACTION_THRESHOLD_RATIO
                        if (-currentDx >= threshold) {
                            animateToOpen(row, -maxTrans)
                            openRow = row
                            state.lastSwipeTime = System.currentTimeMillis()
                        } else {
                            animateChildrenBack(row)
                            animateBackgroundBack(row)
                            openRow = null
                        }
                    }
                    state.isSwiping = false
                    state.swipedRow = null
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    animateChildrenBack(row)
                    animateBackgroundBack(row)
                    openRow = null
                    state.isSwiping = false
                    state.swipedRow = null
                }
            }
            param.result = true
        }
    }

    private fun getJidStr(row: View): String? {
        val obj = row.getTag(TAG_KEY_CONVERSATION) ?: row.tag
        if (obj != null) {
            extractJidFromConversation(obj)?.let { return it }
        }
        try {
            val pos = row.getTag(TAG_KEY_POSITION) as? Int
            if (pos != null && chatAdapter != null) {
                val item = chatAdapter?.getItem(pos)
                if (item != null) {
                    extractJidFromConversation(item)?.let { return it }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getChatState(row: View): Triple<Boolean, Boolean, Boolean> {
        var isPinned = false
        var isMuted = false
        var isUnread = false

        // 1. Cek accessibility contentDescription pada row dan child-nya
        val rowDesc = row.contentDescription?.toString()?.lowercase() ?: ""
        if (rowDesc.contains("belum dibaca") || rowDesc.contains("unread") || rowDesc.contains("tidak dibaca")) {
            isUnread = true
        }

        // 2. Cek indikator UI langsung di row (badge angka, dot hijau, pin, mute)
        try {
            val context = row.context
            val res = context.resources
            val packageName = "com.whatsapp"
            
            val pinId = res.getIdentifier("pin_indicator", "id", packageName)
            if (pinId != 0) {
                val v = row.findViewById<View>(pinId)
                if (v != null) isPinned = (v.visibility == View.VISIBLE)
            }
            
            val muteId = res.getIdentifier("mute_indicator", "id", packageName)
            if (muteId != 0) {
                val v = row.findViewById<View>(muteId)
                if (v != null) isMuted = (v.visibility == View.VISIBLE)
            }
            
            if (!isUnread) {
                isUnread = checkUnreadInViewHierarchy(row)
            }
        } catch (_: Exception) {}

        // 3. Cek dari Objek Percakapan (Conversation Object via Reflection)
        val conversationObj = try {
            val pos = row.getTag(TAG_KEY_POSITION) as? Int
            if (pos != null && chatAdapter != null) chatAdapter?.getItem(pos) else null
        } catch (_: Exception) { null }
            ?: row.getTag(TAG_KEY_CONVERSATION)
            ?: row.tag

        if (conversationObj != null) {
            val objResult = readStateFromObject(conversationObj)
            if (objResult != null) {
                isPinned = isPinned || objResult.first
                isMuted = isMuted || objResult.second
                isUnread = isUnread || objResult.third
            }
        }

        // 4. Cek SQLite (chatsettings.db & msgstore.db)
        val jid = getJidStr(row)
        if (jid != null) {
            val contextFallback = row.context

            try {
                val dbFile = java.io.File(contextFallback.filesDir?.parentFile?.parentFile, "databases/chatsettings.db")
                if (dbFile.exists()) {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    ).use { db ->
                        db.rawQuery("SELECT pinned, mute_end, muted_notifications FROM settings WHERE jid = ?", arrayOf(jid)).use { c ->
                            if (c.moveToFirst()) {
                                if (!isPinned) isPinned = c.getInt(0) == 1 || c.getLong(0) > 0
                                if (!isMuted) {
                                    val muteEnd = c.getLong(1)
                                    val mutedNotif = c.getInt(2) == 1
                                    isMuted = mutedNotif || (muteEnd != 0L && (muteEnd == -1L || muteEnd > System.currentTimeMillis()))
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            try {
                val msgStorePath = java.io.File(contextFallback.filesDir?.parentFile?.parentFile, "databases/msgstore.db")
                if (msgStorePath.exists()) {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        msgStorePath.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    ).use { db ->
                        val cleanUser = jid.substringBefore("@").substringBefore(":")
                        db.rawQuery(
                            "SELECT c.unseen_message_count, c.marked_unread, c.unseen_row_count " +
                            "FROM chat c JOIN jid j ON c.jid_row_id = j._id " +
                            "WHERE j.raw_string = ? OR j.raw_string LIKE ? OR j.user = ?",
                            arrayOf(jid, "%$cleanUser%", cleanUser)
                        ).use { c ->
                            if (c.moveToFirst()) {
                                val count = c.getInt(0)
                                val marked = if (c.columnCount > 1) c.getInt(1) else 0
                                val unseenRows = if (c.columnCount > 2) c.getInt(2) else 0
                                if (count != 0 || marked == 1 || unseenRows > 0) {
                                    isUnread = true
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return Triple(isPinned, isMuted, isUnread)
    }

    private fun checkUnreadInViewHierarchy(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        
        val desc = view.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("belum dibaca") || desc.contains("unread") || desc.contains("tidak dibaca")) {
            return true
        }

        val res = view.context.resources
        val idName = try {
            if (view.id != 0 && view.id != -1) res.getResourceEntryName(view.id).lowercase() else ""
        } catch (_: Exception) { "" }

        if (idName.contains("unread") || idName.contains("badge") || idName.contains("counter") || idName.contains("count")) {
            if (view is android.widget.TextView) {
                val txt = view.text?.toString()?.trim() ?: ""
                if (txt.isNotEmpty() && txt != "0") return true
            } else {
                return true
            }
        }

        // Cek jika ini TextView berisi angka kecil (indikator badge jumlah pesan)
        if (view is android.widget.TextView) {
            val txt = view.text?.toString()?.trim() ?: ""
            if (txt.isNotEmpty() && txt.length <= 4 && txt.all { it.isDigit() } && txt != "0") {
                // Pastikan bukan bagian dari jam/menit atau tanggal
                if (!txt.contains(":") && !txt.contains("/") && !txt.contains(".")) {
                    return true
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (checkUnreadInViewHierarchy(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun getAllFields(cls: Class<*>): List<java.lang.reflect.Field> {
        return reflectedFieldsCache.getOrPut(cls) {
            val list = mutableListOf<java.lang.reflect.Field>()
            var current: Class<*>? = cls
            while (current != null && current != Any::class.java) {
                for (f in current.declaredFields) {
                    try {
                        f.isAccessible = true
                        list.add(f)
                    } catch (_: Throwable) {}
                }
                current = current.superclass
            }
            list
        }
    }

    private fun readStateFromObject(obj: Any): Triple<Boolean, Boolean, Boolean>? {
        var isPinned = false
        var isMuted = false
        var isUnread = false
        try {
            val fields = getAllFields(obj.javaClass)
            for (field in fields) {
                try {
                    val name = field.name.lowercase()
                    val value = field.get(obj) ?: continue
                    if (name.contains("unseen") || name.contains("unread")) {
                        if (value is Int && value != 0) isUnread = true
                        if (value is Boolean && value) isUnread = true
                    }
                    if (name.contains("pin")) {
                        if (value is Boolean && value) isPinned = true
                        if (value is Number && value.toLong() > 0) isPinned = true
                    }
                    if (name.contains("mute")) {
                        if (value is Boolean && value) isMuted = true
                        if (value is Number && value.toLong() > 0) isMuted = true
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return Triple(isPinned, isMuted, isUnread)
    }

    private fun showIOSMenu(row: View) {
        val context = row.context
        val activity = findActivity(context) ?: return

        bgExecutor.execute {
            try {
                val (isPinned, isMuted, isUnread) = getChatState(row)
                val isIndonesian = java.util.Locale.getDefault().language == "in" || java.util.Locale.getDefault().language == "id"

                val labelInfo = if (isIndonesian) "Info Kontak" else "Contact Info"
                val labelPin = if (isPinned) (if (isIndonesian) "Lepas Sematan" else "Unpin Chat") else (if (isIndonesian) "Sematkan Chat" else "Pin Chat")
                val labelMute = if (isMuted) (if (isIndonesian) "Bunyikan Notifikasi" else "Unmute") else (if (isIndonesian) "Bisukan Notifikasi" else "Mute")
                val labelRead = if (isUnread) (if (isIndonesian) "Tandai Sudah Dibaca" else "Mark as Read") else (if (isIndonesian) "Tandai Belum Dibaca" else "Mark as Unread")
                val labelShortcut = if (isIndonesian) "Tambah Pintasan" else "Add Shortcut"
                val labelLock = if (isIndonesian) "Kunci Chat" else "Lock Chat"
                val labelSelect = if (isIndonesian) "Pilih Chat" else "Select Chat"
                val labelDelete = if (isIndonesian) "Hapus Chat" else "Delete Chat"

                val menuItems = listOf(
                    labelInfo to { executeDirectAction(row, "info") },
                    labelPin to { executeDirectAction(row, if (isPinned) "unpin" else "pin") },
                    labelMute to { executeDirectAction(row, if (isMuted) "unmute" else "mute") },
                    labelRead to { executeDirectAction(row, if (isUnread) "read" else "unread") },
                    labelShortcut to { executeDirectAction(row, "shortcut") },
                    labelLock to { executeDirectAction(row, "lock") },
                    labelSelect to { triggerProgrammaticLongClick(row) },
                    labelDelete to { executeDirectAction(row, "delete") }
                )

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || !row.isAttachedToWindow) {
                        animateChildrenBack(row)
                        animateBackgroundBack(row)
                        if (openRow == row) openRow = null
                        return@runOnUiThread
                    }
                    val dialog = IOSMenuDialog(context)
                    dialog.setOnDismissListener {
                        animateChildrenBack(row)
                        animateBackgroundBack(row)
                        openRow = null
                    }
                    dialog.show(menuItems, isIndonesian)
                }
            } catch (e: Exception) {
                logDebug("IosSwipeMenu: Failed to show iOS menu: ${e.message}")
                activity.runOnUiThread {
                    animateChildrenBack(row)
                    animateBackgroundBack(row)
                    openRow = null
                }
            }
        }
    }

    private fun triggerProgrammaticLongClick(row: View) {
        allowProgrammaticLongClick = true
        forceLongClickDeep(row)
        allowProgrammaticLongClick = false
    }

    private inner class IOSMenuDialog(ctx: android.content.Context) :
        android.app.Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar) {

        private val menuContainer: android.widget.LinearLayout
        private var dismissListener: (() -> Unit)? = null

        init {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val rootLayout = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val dimView = android.view.View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { dismiss() }
            }
            rootLayout.addView(dimView)

            val menuWidth = (ctx.resources.displayMetrics.widthPixels * 0.85).toInt()
            menuContainer = android.widget.LinearLayout(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    menuWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
                ).apply {
                    bottomMargin = Utils.dipToPixels(16f).toInt()
                }
                orientation = android.widget.LinearLayout.VERTICAL
            }
            rootLayout.addView(menuContainer)

            setContentView(rootLayout)

            window?.apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = attributes?.apply {
                        blurBehindRadius = 80
                    }
                    setBackgroundBlurRadius(80)
                }
                setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
                attributes = attributes?.apply {
                    dimAmount = 0.3f
                    flags = flags or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
                }
            }
        }

        fun show(menuItems: List<Pair<String, () -> Unit>>, isIndonesian: Boolean = false) {
            menuContainer.removeAllViews()

            val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            val blockBgColor = if (isDarkMode) Color.parseColor("#B3252525") else Color.parseColor("#CCF2F2F7")
            val dividerColor = if (isDarkMode) Color.parseColor("#26FFFFFF") else Color.parseColor("#26000000")

            val mainBlock = android.widget.LinearLayout(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = android.widget.LinearLayout.VERTICAL
                clipToOutline = true
                background = createRoundedBackground(blockBgColor, Utils.dipToPixels(18f).toFloat())
            }

            menuItems.forEachIndexed { index, (label, action) ->
                val isDelete = label.equals("Delete Chat", ignoreCase = true) || label.equals("Hapus Chat", ignoreCase = true)

                val itemView = createMenuItem(label, isDelete, isDarkMode) {
                    action()
                    dismiss()
                }
                mainBlock.addView(itemView)

                if (index < menuItems.size - 1) {
                    val divider = android.view.View(context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            1
                        )
                        setBackgroundColor(dividerColor)
                    }
                    mainBlock.addView(divider)
                }
            }
            menuContainer.addView(mainBlock)

            val gap = android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Utils.dipToPixels(8f).toInt()
                )
            }
            menuContainer.addView(gap)

            val cancelBlock = android.widget.LinearLayout(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = android.widget.LinearLayout.VERTICAL
                clipToOutline = true
                background = createRoundedBackground(blockBgColor, Utils.dipToPixels(18f).toFloat())
            }

            val labelCancel = if (isIndonesian) "Batal" else "Cancel"
            val cancelBtn = createMenuItem(labelCancel, false, isDarkMode) { dismiss() }
            cancelBlock.addView(cancelBtn)
            menuContainer.addView(cancelBlock)

            super.show()
            animateMenuIn()
        }

        private fun createMenuItem(label: String, isDelete: Boolean, isDarkMode: Boolean, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(this@IOSMenuDialog.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Utils.dipToPixels(48f).toInt()
                )
                text = label
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setOnClickListener { onClick() }

                val defaultTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

                when {
                    isDelete -> {
                        setTextColor(Color.parseColor("#FF3B30"))
                        typeface = android.graphics.Typeface.DEFAULT
                    }
                    label.equals("Cancel", ignoreCase = true) || label.equals("Batal", ignoreCase = true) -> {
                        setTextColor(if (isDarkMode) Color.WHITE else Color.parseColor("#007AFF"))
                        typeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD
                        )
                    }
                    else -> {
                        setTextColor(defaultTextColor)
                        typeface = android.graphics.Typeface.DEFAULT
                    }
                }
            }
        }

        private fun animateMenuIn() {
            val startY = Utils.dipToPixels(300f).toFloat()
            menuContainer.translationY = startY
            menuContainer.animate()
                .translationY(0f)
                .setDuration(150)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .start()
        }

        override fun dismiss() {
            dismissListener?.invoke()
            super.dismiss()
        }

        fun setOnDismissListener(listener: (() -> Unit)?) {
            dismissListener = listener
        }

        private fun createRoundedBackground(color: Int, radius: Float): android.graphics.drawable.Drawable {
            return android.graphics.drawable.ShapeDrawable(
                android.graphics.drawable.shapes.RoundRectShape(
                    floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius),
                    null,
                    null
                )
            ).apply { paint.color = color }
        }
    }

    private fun executeDirectAction(row: View, action: String) {
        logDebug("IosSwipeMenu: executeDirectAction: $action")
        try {
            allowProgrammaticLongClick = true
            forceLongClickDeep(row)
            allowProgrammaticLongClick = false

            fun tryAction(attempt: Int) {
                val success = silentToolbarAction(row, action)
                if (!success && attempt < 10) {
                    row.postDelayed({ tryAction(attempt + 1) }, 60)
                }
            }
            row.postDelayed({ tryAction(0) }, 100)

            if (action == "lock") {
                row.postDelayed({
                    val activity = findActivity(row.context)
                    if (activity != null) dismissSelection(activity)
                }, 4000)
            }
        } catch (e: Exception) {
            logDebug("IosSwipeMenu: executeDirectAction error: ${e.message}")
        }
    }

    private fun silentToolbarAction(row: View, action: String): Boolean {
        val activity = findActivity(row.context) ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val decorView = activity.window?.decorView as? ViewGroup ?: return false

        val toolbars = mutableListOf<ViewGroup>()
        findAllToolbars(decorView, toolbars)
        if (toolbars.isEmpty()) return false

        val cab = toolbars.find { it.javaClass.name.contains("ActionBarContextView", ignoreCase = true) }
        val activeToolbars = if (cab != null) listOf(cab) else toolbars

        val keywords = when (action) {
            "archive" -> listOf(
                "arsip", "archive", "archived", "archivar", "arquivar", "archivieren", "archiver", "archivia", "أرشفة", "архив", "arşivle"
            )
            "unarchive" -> listOf(
                "unarchive", "buka arsip", "pulih", "restore", "kembalikan", "keluarkan",
                "desarchivar", "desarquivar", "dearchivieren", "désarchiver", "estrai dall'archivio", "إلغاء الأرشفة", "разархивировать", "arşivden çıkar"
            )
            "read" -> listOf(
                "mark as read", "mark read", "read",
                "tandai dibaca", "sudah dibaca", "baca", "tandai sudah dibaca", "tandai telah dibaca",
                "tandai sbg dibaca", "tandai sebagai dibaca", "tandai sbg sudah dibaca", "tandai sebagai sudah dibaca",
                "tandai sebagai telah dibaca", "tandai sbg telah dibaca",
                "marcar como leído", "marcar como leida", "marcar como lida", "marcar como lido",
                "als gelesen markieren", "marquer comme lu", "segna come letto", "وضع علامة كمقروء", "отметить как прочитанное", "okundu olarak işaretle"
            )
            "unread" -> listOf(
                "mark as unread", "mark unread", "unread",
                "tandai belum dibaca", "belum dibaca", "tandai sbg belum dibaca", "tandai sebagai belum dibaca",
                "tandai sbg blm dibaca", "tandai sebagai blm dibaca", "tandai blm dibaca",
                "marcar como no leído", "marcar como no leida", "marcar como não lida", "marcar como não lido",
                "als ungelesen markieren", "marquer comme non lu", "segna come non letto", "وضع علامة كغير مقروء", "отметить как непрочитанное", "okunmadı olarak işaretle"
            )
            "delete" -> listOf(
                "hapus", "delete", "hapus chat", "eliminar", "apagar", "excluir", "löschen", "supprimer", "elimina", "حذف", "удалить", "sil"
            )
            "info" -> listOf(
                "lihat kontak", "view contact", "info grup", "group info", "info del contacto", "dados do contato",
                "kontaktinfo", "infos du contact", "info contatto", "معلومات جهة الاتصال", "данные контакта", "kişi bilgisi"
            )
            "pin" -> listOf(
                "sematkan chat", "sematkan", "pin chat", "pin conversation", "semat", "pin",
                "fijar", "fixar", "anpinnen", "épingler", "fissa", "تثبيت", "закрепить", "sabitle"
            )
            "unpin" -> listOf(
                "lepas sematan chat", "lepas sematan", "unpin", "buka pin", "copot sematan", "hapus sematan", "unpin chat", "unpin conversation",
                "batalkan sematan", "lepaskan sematan", "desfijar", "desafixar", "loslösen", "désépingler", "sblocca", "إلغاء التثبيت", "открепить", "sabitlemeyi kaldır"
            )
            "mute" -> listOf(
                "bisukan", "bisukan notifikasi", "bisukan obrolan", "mute", "silence", "senyap", "senyapkan",
                "silenciar", "stumm schalten", "mettre en sourdine", "disattiva notifiche", "كتم", "без звука", "sessize al"
            )
            "unmute" -> listOf(
                "batal senyapkan", "aktifkan notifikasi", "unmute", "bunyikan", "buka bisukan", "bunyikan notifikasi", "nyalakan notifikasi",
                "desactivar silencio", "reativar", "stummschaltung aufheben", "réactiver les notifications", "attiva notifiche", "إلغاء الكتم", "включить звук", "sesi aç"
            )
            "shortcut" -> listOf(
                "tambah pintasan", "add chat shortcut", "pintasan", "shortcut",
                "añadir acceso directo", "adicionar atalho", "verknüpfung hinzufügen", "ajouter le raccourci", "aggiungi collegamento", "إضافة اختصار", "добавить ярлык", "kestirme ekle"
            )
            "lock" -> listOf(
                "kunci", "lock", "kunci chat", "lock chat", "kunci obrolan", "lock conversation",
                "buka kunci", "unlock", "unlock chat", "buka kunci chat", "kunci percakapan",
                "bloquear chat", "trancar conversa", "chat sperren", "verrouiller la discussion", "blocca chat", "قفل الدردشة", "заблокировать чат", "sohbeti kilitle"
            )
            else -> emptyList()
        }

        val excludeKeywords = listOf("laporkan", "report", "pengaturan", "setting", "pencarian", "search")

        // 1. Cek semua Menu di Toolbar / CAB / ActionMenuView
        val menus = mutableListOf<android.view.Menu>()
        for (tb in activeToolbars) {
            try {
                val m = tb.javaClass.getMethod("getMenu").invoke(tb) as? android.view.Menu
                if (m != null) menus.add(m)
            } catch (_: Exception) {}
            getMenusFromViewGroup(tb, menus)
        }

        for (menu in menus) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val title = item.title?.toString()?.lowercase() ?: ""
                val desc = if (android.os.Build.VERSION.SDK_INT >= 26) item.contentDescription?.toString()?.lowercase() ?: "" else ""

                val isExcluded = excludeKeywords.any { title.contains(it) || desc.contains(it) }

                if (!isExcluded && keywords.any { title.contains(it) || desc.contains(it) }) {
                    try {
                        menu.performIdentifierAction(item.itemId, 0)
                        if (action != "lock" && action != "delete" && action != "info") {
                            dismissSelection(activity)
                        }
                        return true
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. Cek Action views (ikon langsung di toolbar)
        val actionViews = mutableListOf<View>()
        for (tb in activeToolbars) collectActionMenuViews(tb, actionViews)

        for (v in actionViews) {
            val desc = v.contentDescription?.toString()?.lowercase() ?: ""
            val isExcluded = excludeKeywords.any { desc.contains(it) }

            if (!isExcluded && keywords.any { desc.contains(it) }) {
                v.performClick()
                if (action != "lock" && action != "delete" && action != "info") {
                    dismissSelection(activity)
                }
                return true
            }
        }

        // 3. Buka overflow menu jika item ada di dalam popup
        for (tb in activeToolbars) {
            for (j in 0 until tb.childCount) {
                val child = tb.getChildAt(j)
                if (child.javaClass.name.contains("ActionMenuView") || child.javaClass.name.contains("Overflow")) {
                    try {
                        child.javaClass.getMethod("showOverflowMenu").invoke(child)
                    } catch (_: Exception) {
                        try { child.performClick() } catch (_: Exception) {}
                    }
                }
            }
        }

        // Cek popup view yang terbuka
        for (popup in getPopupViews()) {
            val target = findViewWithText(popup as? ViewGroup ?: continue, keywords)
            if (target != null) {
                target.performClick()
                if (action != "lock" && action != "delete" && action != "info") {
                    dismissSelection(activity)
                }
                return true
            }
        }

        return false
    }

    private fun findAllToolbars(vg: ViewGroup, result: MutableList<ViewGroup>) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            val name = child.javaClass.name
            if (name.contains("Toolbar", ignoreCase = true) || name.contains("ActionBar", ignoreCase = true)) {
                if (child is ViewGroup) result.add(child)
            }
            if (child is ViewGroup) findAllToolbars(child, result)
        }
    }

    private fun getMenusFromViewGroup(vg: ViewGroup, menus: MutableList<android.view.Menu>) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child.javaClass.name.contains("ActionMenuView")) {
                try {
                    val m = child.javaClass.getMethod("getMenu").invoke(child) as? android.view.Menu
                    if (m != null) menus.add(m)
                } catch (_: Exception) {}
            }
            if (child is ViewGroup) getMenusFromViewGroup(child, menus)
        }
    }

    private fun collectActionMenuViews(vg: ViewGroup, result: MutableList<View>) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child.javaClass.name.contains("ActionMenuItemView") || child is android.widget.ImageView) {
                result.add(child)
            }
            if (child is ViewGroup) collectActionMenuViews(child, result)
        }
    }

    private fun findActionBar(vg: ViewGroup): ViewGroup? {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            val name = child.javaClass.name
            if (name.contains("ActionBarContextView")) return child as? ViewGroup
            if (name.contains("ActionBarOverlayLayout") && child is ViewGroup) {
                val inner = findActionBar(child)
                if (inner != null) return inner
            }
            if (child is ViewGroup) {
                val result = findActionBar(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun findViewWithText(vg: ViewGroup, keywords: List<String>): View? {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is android.widget.TextView) {
                val text = child.text?.toString()?.lowercase() ?: ""
                for (kw in keywords) { if (text.contains(kw)) return child }
            }
            if (child is ViewGroup) { findViewWithText(child, keywords)?.let { return it } }
        }
        return null
    }

    private fun getPopupViews(): List<View> {
        return try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null)
            val viewsField = wmgClass.getDeclaredField("mViews")
            viewsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (viewsField.get(instance) as? ArrayList<View>)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun dismissSelection(activity: android.app.Activity) {
        try {
            val decorView = activity.window?.decorView as? ViewGroup ?: return
            val cab = findActionBar(decorView)
            if (cab != null) {
                // Coba panggil closeMode (API internal)
                try {
                    cab.javaClass.getMethod("closeMode").invoke(cab)
                    return
                } catch (e: Exception) {}

                // Fallback: Cari tombol close di CAB dan klik
                fun clickCloseBtn(view: ViewGroup): Boolean {
                    for (i in 0 until view.childCount) {
                        val c = view.getChildAt(i)
                        // Lewati ActionMenuView karena itu isinya menu-menu aksi (bukan tombol close)
                        if (c.javaClass.name.contains("ActionMenuView")) continue
                        
                        if (c.isClickable || c.hasOnClickListeners()) {
                            c.performClick()
                            return true
                        }
                        if (c is ViewGroup && clickCloseBtn(c)) return true
                    }
                    return false
                }
                clickCloseBtn(cab)
            }
        } catch (_: Exception) {}
    }

    private fun forceLongClickDeep(view: View): Boolean {
        try {
            var parent: android.view.ViewParent? = view.parent
            var adapterView: android.widget.AdapterView<*>? = null
            while (parent != null) {
                if (parent is android.widget.AdapterView<*>) {
                    adapterView = parent
                    break
                }
                parent = parent.parent
            }

            if (adapterView != null) {
                val pos = (view.getTag(TAG_KEY_POSITION) as? Int) ?: adapterView.getPositionForView(view)
                val id = chatAdapter?.getItemId(pos) ?: view.id.toLong()
                val itemLongClickListener = adapterView.onItemLongClickListener
                if (itemLongClickListener != null && pos >= 0) {
                    allowProgrammaticLongClick = true
                    val handled = itemLongClickListener.onItemLongClick(adapterView, view, pos, id)
                    allowProgrammaticLongClick = false
                    if (handled) return true
                }
            }

            if (view.performLongClick()) return true
            val listenerInfoMethod = android.view.View::class.java.getDeclaredMethod("getListenerInfo")
            listenerInfoMethod.isAccessible = true
            val listenerInfo = listenerInfoMethod.invoke(view)
            if (listenerInfo != null) {
                val mOnLongClickListenerField = listenerInfo.javaClass.getDeclaredField("mOnLongClickListener")
                mOnLongClickListenerField.isAccessible = true
                val listener = mOnLongClickListenerField.get(listenerInfo) as? View.OnLongClickListener
                if (listener != null) {
                    if (listener.onLongClick(view)) return true
                }
            }
        } catch (_: Exception) {}
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (forceLongClickDeep(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun hitTestChild(vg: ViewGroup, x: Int, y: Int): View? {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child.visibility == View.VISIBLE &&
                x >= child.left && x <= child.right &&
                y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    private fun translateChildren(row: View, dx: Float) {
        if (row is ViewGroup) {
            for (i in 0 until row.childCount) row.getChildAt(i).translationX = dx
        } else row.translationX = dx
    }

    private fun animateChildrenBack(row: View) {
        if (row is ViewGroup) {
            for (i in 0 until row.childCount)
                row.getChildAt(i).animate().translationX(0f).setDuration(120).start()
        } else row.animate().translationX(0f).setDuration(120).start()
    }

    private fun animateBackgroundBack(row: View) {
        val bg = row.background as? SwipeBackgroundDrawable ?: return
        val anim = android.animation.ValueAnimator.ofFloat(bg.currentDx, 0f)
        anim.duration = 120
        anim.addUpdateListener { bg.currentDx = it.animatedValue as Float; row.invalidate() }
        anim.start()
    }

    private fun animateToOpen(row: View, targetDx: Float) {
        if (row is ViewGroup) {
            for (i in 0 until row.childCount)
                row.getChildAt(i).animate().translationX(targetDx).setDuration(120).start()
        } else row.animate().translationX(targetDx).setDuration(120).start()

        val bg = row.background as? SwipeBackgroundDrawable ?: return
        val anim = android.animation.ValueAnimator.ofFloat(bg.currentDx, targetDx)
        anim.duration = 120
        anim.addUpdateListener { bg.currentDx = it.animatedValue as Float; row.invalidate() }
        anim.start()
    }

    private fun extractJidFromConversation(obj: Any): String? {
        val fields = getAllFields(obj.javaClass)
        for (field in fields) {
            try {
                val fieldValue = field.get(obj) ?: continue
                try {
                    val rawString = XposedHelpers.callMethod(fieldValue, "getRawString") as? String
                    if (rawString != null && rawString.contains("@")) return rawString
                } catch (_: Throwable) {}

                val strVal = fieldValue.toString()
                if (strVal.contains("@") && (strVal.endsWith("@s.whatsapp.net") || strVal.endsWith("@g.us") || strVal.endsWith("@lid"))) {
                    return strVal
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findActivity(ctx: android.content.Context): android.app.Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    private val archivedStatusCache = java.util.WeakHashMap<android.app.Activity, Pair<Long, Boolean>>()

    private fun isInArchivedView(row: View): Boolean {
        try {
            val activity = findActivity(row.context) ?: return false

            // Explicitly prevent Home Screen from being treated as Archived View
            val activityName = activity.javaClass.simpleName
            if (activityName == "HomeActivity") {
                return false
            }

            val now = System.currentTimeMillis()
            val cached = archivedStatusCache[activity]
            if (cached != null && now - cached.first < 2000L) {
                return cached.second
            }

            val title = activity.title?.toString()?.lowercase() ?: ""
            if (title == "archived" || title == "diarsipkan" || title == "arsip") {
                archivedStatusCache[activity] = now to true
                return true
            }
            val actionBar = activity.actionBar
            if (actionBar != null) {
                val abTitle = actionBar.title?.toString()?.lowercase() ?: ""
                if (abTitle == "archived" || abTitle == "diarsipkan" || abTitle == "arsip") {
                    archivedStatusCache[activity] = now to true
                    return true
                }
            }

            val decorView = activity.window?.decorView as? ViewGroup ?: return false
            var found = false
            fun findToolbarText(v: View) {
                val className = v.javaClass.name
                if (className.endsWith("Toolbar") || className.contains("ActionBar")) {
                    if (v is ViewGroup) {
                        for (i in 0 until v.childCount) {
                            val child = v.getChildAt(i)
                            if (child is android.widget.TextView) {
                                val t = child.text?.toString()?.lowercase() ?: ""
                                if (t == "archived" || t == "diarsipkan" || t == "arsip") {
                                    found = true
                                    return
                                }
                            }
                        }
                    }
                }
                if (v is ViewGroup && !found) {
                    for (i in 0 until v.childCount) {
                        findToolbarText(v.getChildAt(i))
                        if (found) return
                    }
                }
            }
            findToolbarText(decorView)
            archivedStatusCache[activity] = now to found
            return found
        } catch (_: Exception) {
            return false
        }
    }

    override fun doHook() {
        if (!prefs.getBoolean("ios_swipe_menu", true)) return

        try {
            val publishResultsMethod = Unobfuscator.loadGetFiltersMethod(classLoader)
            val baseField = com.wmods.wppenhacer.xposed.utils.ReflectionUtils.getFieldByExtendType(
                publishResultsMethod.declaringClass,
                android.widget.BaseAdapter::class.java
            )
            if (baseField != null) {
                val adapterClass = baseField.type
                XposedBridge.hookAllMethods(adapterClass, "getView", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val convertView = param.args[1] as? View ?: return
                        if (openRow == convertView) {
                            openRow = null
                        }
                        if (convertView.background is SwipeBackgroundDrawable) {
                            val bg = convertView.background as SwipeBackgroundDrawable
                            convertView.background = bg.originalBg
                        }
                        translateChildren(convertView, 0f)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val row = param.result as? View ?: return
                        val position = param.args[0] as Int

                        // Reset swiped state jika row ini di-update (misal ada pesan baru masuk)
                        if (openRow == row) {
                            openRow = null
                        }
                        if (row.background is SwipeBackgroundDrawable) {
                            val bg = row.background as SwipeBackgroundDrawable
                            row.background = bg.originalBg
                        }
                        translateChildren(row, 0f)

                        chatAdapter = param.thisObject as? android.widget.BaseAdapter
                        row.setTag(TAG_KEY_POSITION, position)
                        try {
                            val conversationItem = chatAdapter?.getItem(position)
                            if (conversationItem != null) {
                                row.setTag(TAG_KEY_CONVERSATION, conversationItem)
                            }
                        } catch (_: Exception) {}

                        // OPTIMIZATION: Get parent directly from arguments to avoid post runnable
                        val parent = param.args[2] as? ViewGroup
                        if (parent != null && !verifiedChatContainers.contains(parent)) {
                            verifiedChatContainers.add(parent)
                            hookListViewTouchClasses(parent.javaClass)
                        }

                        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {}
                            override fun onViewDetachedFromWindow(v: View) {
                                if (openRow == row) {
                                    openRow = null
                                }
                                row.removeOnAttachStateChangeListener(this)
                                if (row.background is SwipeBackgroundDrawable) {
                                    val bg = row.background as SwipeBackgroundDrawable
                                    row.background = bg.originalBg
                                }
                                translateChildren(row, 0f)
                            }
                        })
                    }
                })
            }
        } catch (_: Exception) {}

        try {
            XposedBridge.hookAllMethods(android.widget.AbsListView::class.java, "onInterceptTouchEvent", interceptHook)
            XposedBridge.hookAllMethods(android.widget.AbsListView::class.java, "onTouchEvent", touchHook)
            
            XposedBridge.hookAllMethods(android.view.View::class.java, "performLongClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (allowProgrammaticLongClick) return
                    val view = param.thisObject as? View ?: return
                    if (view.getTag(TAG_KEY_CONVERSATION) != null || view.getTag(TAG_KEY_POSITION) != null) {
                        param.result = true
                    }
                }
            })
        } catch (_: Exception) {}
    }

    private fun hookListViewTouchClasses(concreteClass: Class<*>) {
        val me = android.view.MotionEvent::class.java
        findDeclaringClass(concreteClass, "onInterceptTouchEvent", me)?.let { cls ->
            if (hookedInterceptClasses.add(cls)) {
                XposedBridge.hookAllMethods(cls, "onInterceptTouchEvent", interceptHook)
            }
        }
        findDeclaringClass(concreteClass, "onTouchEvent", me)?.let { cls ->
            if (hookedTouchClasses.add(cls)) {
                XposedBridge.hookAllMethods(cls, "onTouchEvent", touchHook)
            }
        }
    }

    private fun findDeclaringClass(cls: Class<*>, method: String, vararg params: Class<*>): Class<*>? {
        var c: Class<*>? = cls
        while (c != null) {
            try { c.getDeclaredMethod(method, *params); return c }
            catch (_: NoSuchMethodException) { c = c.superclass }
        }
        return null
    }

    class SwipeBackgroundDrawable(
        val originalBg: android.graphics.drawable.Drawable?,
        var isArchived: Boolean = false,
        var isDarkMode: Boolean = false
    ) : android.graphics.drawable.Drawable() {
        var currentDx: Float = 0f
        private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val iconFillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = Utils.dipToPixels(12f).toFloat()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val dip2 = Utils.dipToPixels(2f).toFloat()
        private val dip2_5 = Utils.dipToPixels(2.5f).toFloat()
        private val dip3 = Utils.dipToPixels(3f).toFloat()
        private val dip4 = Utils.dipToPixels(4f).toFloat()
        private val dip7 = Utils.dipToPixels(7f).toFloat()
        private val dip8 = Utils.dipToPixels(8f).toFloat()
        private val dip12 = Utils.dipToPixels(12f).toFloat()
        private val dip24 = Utils.dipToPixels(24f).toFloat()
        private val strokeW = Utils.dipToPixels(1.5f).toFloat()

        private val boxRect = android.graphics.RectF()
        private val arrowPath = android.graphics.Path()

        override fun draw(canvas: android.graphics.Canvas) {
            val h = bounds.height().toFloat()
            originalBg?.let { it.bounds = bounds; it.draw(canvas) }
            if (currentDx >= 0f) return

            val absDx = -currentDx
            canvas.save()
            canvas.clipRect(bounds.width().toFloat() - absDx, 0f, bounds.width().toFloat(), h)

            val halfAbs = absDx / 2f
            val w = bounds.width().toFloat()

            // 1. Tombol More di kiri (dari w - absDx sampai w - halfAbs)
            bgPaint.color = if (isDarkMode) INT_COLOR_MORE_DARK else INT_COLOR_MORE_LIGHT
            canvas.drawRect(w - absDx, 0f, w - halfAbs, h, bgPaint)
            if (absDx > 60f) {
                canvas.save()
                canvas.clipRect(w - absDx, 0f, w - halfAbs, h)
                val cx1 = w - absDx + halfAbs / 2f
                val cy = h / 2 - dip8
                drawMoreIcon(canvas, cx1, cy)
                textPaint.color = Color.WHITE
                canvas.drawText("More", cx1, cy + dip24, textPaint)
                canvas.restore()
            }

            // 2. Tombol Archive/Unarchive di kanan (dari w - halfAbs sampai w)
            bgPaint.color = if (isArchived) INT_COLOR_UNARCHIVE else INT_COLOR_ARCHIVE
            canvas.drawRect(w - halfAbs, 0f, w, h, bgPaint)
            if (absDx > 60f) {
                canvas.save()
                canvas.clipRect(w - halfAbs, 0f, w, h)
                val cx2 = w - halfAbs + halfAbs / 2f
                val cy = h / 2 - dip8
                drawArchiveIcon(canvas, cx2, cy, isArchived)
                textPaint.color = Color.WHITE
                canvas.drawText(if (isArchived) "Unarchive" else "Archive", cx2, cy + dip24, textPaint)
                canvas.restore()
            }

            canvas.restore()
        }

        private fun drawMoreIcon(canvas: android.graphics.Canvas, cx: Float, cy: Float) {
            iconFillPaint.color = android.graphics.Color.WHITE
            iconFillPaint.style = android.graphics.Paint.Style.FILL

            canvas.drawCircle(cx - dip7, cy, dip2_5, iconFillPaint)
            canvas.drawCircle(cx, cy, dip2_5, iconFillPaint)
            canvas.drawCircle(cx + dip7, cy, dip2_5, iconFillPaint)
        }

        private fun drawArchiveIcon(canvas: android.graphics.Canvas, cx: Float, cy: Float, isArchived: Boolean) {
            val s = dip8

            iconFillPaint.color = android.graphics.Color.WHITE
            iconFillPaint.style = android.graphics.Paint.Style.STROKE
            iconFillPaint.strokeWidth = strokeW
            iconFillPaint.strokeJoin = android.graphics.Paint.Join.ROUND
            iconFillPaint.strokeCap = android.graphics.Paint.Cap.ROUND

            // Box
            boxRect.set(cx - s, cy - s + dip2, cx + s, cy + s)
            canvas.drawRoundRect(boxRect, dip2, dip2, iconFillPaint)

            // Lid line
            canvas.drawLine(cx - s, cy - s + dip2 + dip3, cx + s, cy - s + dip2 + dip3, iconFillPaint)

            // Arrow
            arrowPath.reset()
            if (isArchived) {
                // Arrow pointing up
                arrowPath.moveTo(cx, cy - Utils.dipToPixels(1f).toFloat())
                arrowPath.lineTo(cx - dip2_5, cy + Utils.dipToPixels(1.5f).toFloat())
                arrowPath.moveTo(cx, cy - Utils.dipToPixels(1f).toFloat())
                arrowPath.lineTo(cx + dip2_5, cy + Utils.dipToPixels(1.5f).toFloat())
                canvas.drawPath(arrowPath, iconFillPaint)
                canvas.drawLine(cx, cy - Utils.dipToPixels(1f).toFloat(), cx, cy + dip4, iconFillPaint)
            } else {
                // Arrow pointing down
                arrowPath.moveTo(cx, cy + dip3)
                arrowPath.lineTo(cx - dip2_5, cy + Utils.dipToPixels(0.5f).toFloat())
                arrowPath.moveTo(cx, cy + dip3)
                arrowPath.lineTo(cx + dip2_5, cy + Utils.dipToPixels(0.5f).toFloat())
                canvas.drawPath(arrowPath, iconFillPaint)
                canvas.drawLine(cx, cy - dip2, cx, cy + dip3, iconFillPaint)
            }

            iconFillPaint.style = android.graphics.Paint.Style.FILL // reset
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun updateSwipeBackground(row: View, dx: Float) {
        var bg = row.background as? SwipeBackgroundDrawable
        if (bg == null) {
            val isArchived = isInArchivedView(row)
            val isDarkMode = (row.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            bg = SwipeBackgroundDrawable(row.background, isArchived, isDarkMode)
            row.background = bg
            
            // Add a listener to reset the swipe state if the row is detached from the window (e.g. changing tabs)
            row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    if (openRow == row) {
                        animateChildrenBack(row)
                        animateBackgroundBack(row)
                        openRow = null
                    }
                    val state = listSwipeStates[row.parent as? ViewGroup]
                    if (state != null && state.swipedRow == row) {
                        state.isSwiping = false
                        state.swipedRow = null
                    }
                }
            })
        }
        bg.currentDx = dx
        row.invalidate()
    }

    override fun getPluginName() = "iOS Swipe Menu"
}
