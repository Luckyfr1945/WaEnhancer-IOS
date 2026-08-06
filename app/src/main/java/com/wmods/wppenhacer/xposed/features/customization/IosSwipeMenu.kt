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
        private const val TAG_KEY_SWIPE_BG = 0x7f0a1003
        private const val TAG_KEY_POSITION = 0x7f0a1004
        private const val TAG_KEY_CONVERSATION = 0x7f0a1005

        var allowProgrammaticLongClick = false

        private const val SWIPE_THRESHOLD = 24f
        private const val MAX_TRANSLATION = 160f
        private const val ACTION_THRESHOLD_RATIO = 0.40f

        private const val COLOR_MORE = "#8E8E93"
        private const val COLOR_UNREAD = "#007AFF"
    }

    private class SwipeState {
        var downX = 0f
        var downY = 0f
        var isSwiping = false
        var swipedRow: View? = null
        var lastSwipeTime = 0L
    }

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
                                val isArchived = isInArchivedView(row)
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

        // 1. Try reading state from UI indicators directly (most reliable if views exist)
        try {
            val context = row.context
            val res = context.resources
            val packageName = "com.whatsapp"
            
            val pinId = res.getIdentifier("pin_indicator", "id", packageName)
            if (pinId != 0) {
                val v = row.findViewById<View>(pinId)
                if (v != null) isPinned = (v.visibility == android.view.View.VISIBLE)
            }
            
            val muteId = res.getIdentifier("mute_indicator", "id", packageName)
            if (muteId != 0) {
                val v = row.findViewById<View>(muteId)
                if (v != null) isMuted = (v.visibility == android.view.View.VISIBLE)
            }
            
            // For unread, try multiple common names
            val unreadId1 = res.getIdentifier("unread_indicator", "id", packageName)
            val unreadId2 = res.getIdentifier("conversations_row_unread_count_badge", "id", packageName)
            val unreadId3 = res.getIdentifier("unread_count", "id", packageName)
            val unreadIds = listOf(unreadId1, unreadId2, unreadId3).filter { it != 0 }
            
            for (id in unreadIds) {
                val v = row.findViewById<View>(id)
                if (v != null) {
                    if (v.visibility == android.view.View.VISIBLE) isUnread = true
                    break
                }
            }
        } catch (_: Exception) {}

        // If UI method gave us false for everything, try reading from the conversation object
        if (!isPinned || !isMuted || !isUnread) {
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
        }

        // Fallback: SQLite
        val jid = getJidStr(row) ?: return Triple(isPinned, isMuted, isUnread)
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
                    var jidRowId = -1L
                    db.rawQuery("SELECT _id FROM jid WHERE raw_string = ?", arrayOf(jid)).use { c ->
                        if (c.moveToFirst()) jidRowId = c.getLong(0)
                    }
                    if (jidRowId != -1L) {
                        db.rawQuery("SELECT unseen_message_count FROM chat WHERE jid_row_id = ?", arrayOf(jidRowId.toString())).use { c ->
                            if (c.moveToFirst()) isUnread = c.getInt(0) > 0
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return Triple(isPinned, isMuted, isUnread)
    }

    private fun readStateFromObject(obj: Any): Triple<Boolean, Boolean, Boolean>? {
        var isPinned: Boolean? = null
        var isMuted: Boolean? = null
        var isUnread: Boolean? = null

        try {
            val allFields = mutableListOf<java.lang.reflect.Field>()
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                allFields.addAll(cls.declaredFields)
                cls = cls.superclass
            }

            // DEBUG: Log all fields and values (including nested objects)
            android.util.Log.d("WAE_DEBUG", "=== ConversationObj class: ${obj.javaClass.name} ===")
            for (field in allFields) {
                try {
                    field.isAccessible = true
                    val name = field.name
                    val value = field.get(obj)
                    val typeName = field.type.simpleName
                    android.util.Log.d("WAE_DEBUG", "  field[$typeName] $name = $value (valueClass=${value?.javaClass?.name})")

                    // Inspect nested objects one level deep
                    if (value != null && !typeName.startsWith("String") && !field.type.isPrimitive &&
                        !field.type.isArray && value !is Number && value !is Boolean) {
                        try {
                            val nestedFields = value.javaClass.declaredFields
                            for (nf in nestedFields.take(20)) {
                                try {
                                    nf.isAccessible = true
                                    val nv = nf.get(value)
                                    val nt = nf.type.simpleName
                                    val nn = nf.name
                                    if (nn.lowercase().contains("pin") || nn.lowercase().contains("mute") ||
                                        nn.lowercase().contains("unseen") || nn.lowercase().contains("unread") ||
                                        nt == "boolean" || nt == "Boolean" ||
                                        (nt == "int" && (nv as? Int)?.let { it in 0..10 } == true) ||
                                        (nt == "long" && (nv as? Long)?.let { it in 0L..3L } == true)) {
                                        android.util.Log.d("WAE_DEBUG", "    nested[$nt] ${value.javaClass.simpleName}.$nn = $nv")
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }

            for (field in allFields) {
                try {
                    field.isAccessible = true
                    val name = field.name.lowercase()
                    val value = field.get(obj) ?: continue

                    // Look for pinned field
                    if (isPinned == null && (name == "pinned" || name.endsWith("pinned") || name.startsWith("pinned") || name.contains("ispinned"))) {
                        when (value) {
                            is Boolean -> isPinned = value
                            is Int -> if (value == 0 || value == 1) isPinned = value == 1
                            is Long -> if (value == 0L || value == 1L) isPinned = value == 1L
                                       else if (value > 1000000000000L) isPinned = true // timestamp = pinned
                        }
                    }

                    // Look for muted field
                    if (isMuted == null && (name == "muted" || name.endsWith("muted") || name == "mute" || name.contains("ismuted"))) {
                        when (value) {
                            is Boolean -> isMuted = value
                            is Int -> if (value == 0 || value == 1) isMuted = value == 1
                        }
                    }

                    // Look for mute_end / muteend field
                    if (isMuted == null && (name.contains("muteend") || name.contains("mute_end") || name.contains("mutetime"))) {
                        when (value) {
                            is Long -> isMuted = value != 0L && (value == -1L || value > System.currentTimeMillis())
                            is Int -> isMuted = value != 0
                        }
                    }

                    // Look for unread count
                    if (isUnread == null && (name.contains("unseen") || name.contains("unread") || name == "unreadcount")) {
                        when (value) {
                            is Int -> isUnread = value > 0
                            is Long -> isUnread = value > 0
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        android.util.Log.d("WAE_DEBUG", "Result: isPinned=$isPinned isMuted=$isMuted isUnread=$isUnread")

        // Only return if we found at least pin or muted state
        return if (isPinned != null || isMuted != null) {
            Triple(isPinned ?: false, isMuted ?: false, isUnread ?: false)
        } else null
    }

    private fun showIOSMenu(row: View) {
        val context = row.context
        val activity = findActivity(context) ?: return
        activity.runOnUiThread {
            try {
                val (isPinned, isMuted, isUnread) = getChatState(row)

                val menuItems = listOf(
                    "Contact Info" to { executeDirectAction(row, "info") },
                    if (isPinned) "Unpin Chat" to { executeDirectAction(row, "unpin") }
                    else "Pin Chat" to { executeDirectAction(row, "pin") },
                    if (isMuted) "Unmute" to { executeDirectAction(row, "unmute") }
                    else "Mute" to { executeDirectAction(row, "mute") },
                    if (isUnread) "Mark as Read" to { executeDirectAction(row, "read") }
                    else "Mark as Unread" to { executeDirectAction(row, "unread") },
                    "Add Shortcut" to { executeDirectAction(row, "shortcut") },
                    "Lock Chat" to { executeDirectAction(row, "lock") },
                    "Select Chat" to { triggerProgrammaticLongClick(row) },
                    "Delete Chat" to { executeDirectAction(row, "delete") }
                )

                val dialog = IOSMenuDialog(context)
                dialog.setOnDismissListener {
                    animateChildrenBack(row)
                    animateBackgroundBack(row)
                    openRow = null
                }
                dialog.show(menuItems)
            } catch (e: Exception) {
                logDebug("IosSwipeMenu: Failed to show iOS menu: ${e.message}")
                animateChildrenBack(row)
                animateBackgroundBack(row)
                openRow = null
            }
        }
    }

    private fun triggerProgrammaticLongClick(row: View) {
        allowProgrammaticLongClick = true
        row.performLongClick()
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
                // Remove the manual dim background since WindowManager will handle it
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
                // No background for the wrapper, blocks will have their own backgrounds
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

        fun show(menuItems: List<Pair<String, () -> Unit>>) {
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
                val isDelete = label.equals("Delete Chat", ignoreCase = true)

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

            val cancelBtn = createMenuItem("cancel", false, isDarkMode) { dismiss() }
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
                    label == "cancel" || label == "Cancel" -> {
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

            val delayMillis = if (action == "mute" || action == "unmute" || action == "lock") 200L else 0L
            row.postDelayed({
                try {
                    silentToolbarAction(row, action)
                } catch (e: Exception) {
                    logDebug("IosSwipeMenu: toolbar action error: ${e.message}")
                }
            }, delayMillis)
        } catch (e: Exception) {
            logDebug("IosSwipeMenu: executeDirectAction error: ${e.message}")
        }
    }

    private fun silentToolbarAction(row: View, action: String) {
        val activity = findActivity(row.context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decorView = activity.window?.decorView as? ViewGroup ?: return

        val toolbars = mutableListOf<ViewGroup>()
        findAllToolbars(decorView, toolbars)
        if (toolbars.isEmpty()) {
            dismissSelection(activity)
            return
        }

        val cab = toolbars.find { it.javaClass.name.contains("ActionBarContextView", ignoreCase = true) }
        val activeToolbars = if (cab != null) listOf(cab) else toolbars

        val keywords = when (action) {
            "archive" -> listOf("arsip", "archive", "archived")
            "unarchive" -> listOf("unarchive", "buka arsip", "pulih", "restore", "kembalikan", "keluarkan")
            "read" -> listOf("mark as read", "tandai dibaca", "sudah dibaca")
            "unread" -> listOf("mark as unread", "tandai belum dibaca", "belum dibaca")
            "delete" -> listOf("hapus", "delete", "hapus chat")
            "info" -> listOf("lihat kontak", "view contact", "info grup", "group info")
            "pin" -> listOf("sematkan chat", "sematkan", "pin chat", "pin conversation", "semat")
            "unpin" -> listOf("lepas sematan chat", "lepas sematan", "unpin", "buka pin", "copot sematan", "hapus sematan", "unpin chat", "unpin conversation", "batalkan sematan", "lepaskan sematan")
            "mute" -> listOf("bisukan", "bisukan notifikasi", "bisukan obrolan", "mute", "silence", "senyap", "senyapkan")
            "unmute" -> listOf("batal senyapkan", "aktifkan notifikasi", "unmute", "bunyikan", "buka bisukan", "bunyikan notifikasi", "nyalakan notifikasi")
            "shortcut" -> listOf("tambah pintasan", "add chat shortcut", "pintasan", "shortcut")
            "lock" -> listOf("kunci", "lock", "kunci chat", "lock chat", "kunci obrolan", "lock conversation")
            else -> emptyList()
        }

        val excludeKeywords = listOf("laporkan", "report", "pengaturan", "setting", "pencarian", "search")
        var executed = false

        val menus = mutableListOf<android.view.Menu>()
        for (tb in activeToolbars) getMenusFromViewGroup(tb, menus)

        for (menu in menus) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val title = item.title?.toString()?.lowercase() ?: ""
                val desc = if (android.os.Build.VERSION.SDK_INT >= 26) item.contentDescription?.toString()?.lowercase() ?: "" else ""

                val isExcluded = excludeKeywords.any { title.contains(it) || desc.contains(it) }

                if (!isExcluded && keywords.any { title.contains(it) || desc.contains(it) }) {
                    try {
                        // Buka overflow menu secara native
                        var overflowOpened = false
                        for (tb in activeToolbars) {
                            for (j in 0 until tb.childCount) {
                                val child = tb.getChildAt(j)
                                if (child.javaClass.name.contains("ActionMenuView")) {
                                    try {
                                        child.javaClass.getMethod("showOverflowMenu").invoke(child)
                                        overflowOpened = true
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        
                        if (overflowOpened) {
                            // Retry mechanism to find and click the popup item
                            fun tryClickPopup(attempt: Int) {
                                var clicked = false
                                for (popup in getPopupViews()) {
                                    val target = findViewWithText(popup as ViewGroup, keywords)
                                    if (target != null) {
                                        target.performClick()
                                        clicked = true
                                        break
                                    }
                                }
                                if (!clicked && attempt < 10) {
                                    row.postDelayed({ tryClickPopup(attempt + 1) }, 50)
                                } else if (!clicked) {
                                    menu.performIdentifierAction(item.itemId, 0)
                                }
                            }
                            row.postDelayed({ tryClickPopup(0) }, 50)
                        } else {
                            menu.performIdentifierAction(item.itemId, 0)
                        }
                        executed = true
                        break
                    } catch (_: Exception) {}
                }
            }
            if (executed) break
        }

        if (!executed) {
            val actionViews = mutableListOf<View>()
            for (tb in activeToolbars) collectActionMenuViews(tb, actionViews)

            for (v in actionViews) {
                val desc = v.contentDescription?.toString()?.lowercase() ?: ""
                val isExcluded = excludeKeywords.any { desc.contains(it) }

                if (!isExcluded && keywords.any { desc.contains(it) }) {
                    v.performClick()
                    executed = true
                    break
                }
            }
        }
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
        val cls = obj.javaClass
        for (field in cls.declaredFields) {
            try {
                field.isAccessible = true
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
        var superCls = cls.superclass
        while (superCls != null && superCls != Any::class.java) {
            for (field in superCls.declaredFields) {
                try {
                    field.isAccessible = true
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
            superCls = superCls.superclass
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

    private fun isInArchivedView(row: View): Boolean {
        try {
            val activity = findActivity(row.context) ?: return false
            
            // Explicitly prevent Home Screen from being treated as Archived View
            val activityName = activity.javaClass.simpleName
            if (activityName == "HomeActivity") {
                return false
            }

            val title = activity.title?.toString()?.lowercase() ?: ""
            if (title == "archived" || title == "diarsipkan" || title == "arsip") return true
            val actionBar = activity.actionBar
            if (actionBar != null) {
                val abTitle = actionBar.title?.toString()?.lowercase() ?: ""
                if (abTitle == "archived" || abTitle == "diarsipkan" || abTitle == "arsip") return true
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
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val row = param.result as? View ?: return
                        val position = param.args[0] as Int

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

        override fun draw(canvas: android.graphics.Canvas) {
            val h = bounds.height().toFloat()
            originalBg?.let { it.bounds = bounds; it.draw(canvas) }
            if (currentDx >= 0f) return

            val absDx = -currentDx
            canvas.save()
            canvas.clipRect(bounds.width().toFloat() - absDx, 0f, bounds.width().toFloat(), h)

            val halfAbs = absDx / 2f
            val w = bounds.width().toFloat()

            // 1. Tombol More (Abu-abu) di kiri (dari w - absDx sampai w - halfAbs)
            bgPaint.color = if (isDarkMode) android.graphics.Color.parseColor("#5A5A5F") else android.graphics.Color.parseColor("#8E8E93")
            canvas.drawRect(w - absDx, 0f, w - halfAbs, h, bgPaint)
            if (absDx > 60f) {
                canvas.save()
                canvas.clipRect(w - absDx, 0f, w - halfAbs, h)
                val cx1 = w - absDx + halfAbs / 2f
                val cy = h / 2 - Utils.dipToPixels(8f)
                drawMoreIcon(canvas, cx1, cy)
                textPaint.color = android.graphics.Color.WHITE
                canvas.drawText("More", cx1, cy + Utils.dipToPixels(24f), textPaint)
                canvas.restore()
            }

            // 2. Tombol Archive/Unarchive di kanan (dari w - halfAbs sampai w)
            // Use a softer green (#4CAF50) instead of bright green (#34C759)
            bgPaint.color = if (isArchived) android.graphics.Color.parseColor("#007AFF") else android.graphics.Color.parseColor("#4CAF50")
            canvas.drawRect(w - halfAbs, 0f, w, h, bgPaint)
            if (absDx > 60f) {
                canvas.save()
                canvas.clipRect(w - halfAbs, 0f, w, h)
                val cx2 = w - halfAbs + halfAbs / 2f
                val cy = h / 2 - Utils.dipToPixels(8f)
                drawArchiveIcon(canvas, cx2, cy, isArchived)
                textPaint.color = android.graphics.Color.WHITE
                canvas.drawText(if (isArchived) "Unarchive" else "Archive", cx2, cy + Utils.dipToPixels(24f), textPaint)
                canvas.restore()
            }

            canvas.restore()
        }

        private fun drawMoreIcon(canvas: android.graphics.Canvas, cx: Float, cy: Float) {
            val dotRadius = Utils.dipToPixels(2.5f).toFloat()
            val spacing = Utils.dipToPixels(7f).toFloat()
            
            iconFillPaint.color = android.graphics.Color.WHITE
            iconFillPaint.style = android.graphics.Paint.Style.FILL
            
            canvas.drawCircle(cx - spacing, cy, dotRadius, iconFillPaint)
            canvas.drawCircle(cx, cy, dotRadius, iconFillPaint)
            canvas.drawCircle(cx + spacing, cy, dotRadius, iconFillPaint)
        }

        private fun drawArchiveIcon(canvas: android.graphics.Canvas, cx: Float, cy: Float, isArchived: Boolean) {
            val s = Utils.dipToPixels(8f).toFloat()
            
            iconFillPaint.color = android.graphics.Color.WHITE
            iconFillPaint.style = android.graphics.Paint.Style.STROKE
            iconFillPaint.strokeWidth = Utils.dipToPixels(1.5f).toFloat()
            iconFillPaint.strokeJoin = android.graphics.Paint.Join.ROUND
            iconFillPaint.strokeCap = android.graphics.Paint.Cap.ROUND
            
            // Box
            val rect = android.graphics.RectF(cx - s, cy - s + Utils.dipToPixels(2f), cx + s, cy + s)
            canvas.drawRoundRect(rect, Utils.dipToPixels(2f).toFloat(), Utils.dipToPixels(2f).toFloat(), iconFillPaint)
            
            // Lid line
            canvas.drawLine(cx - s, cy - s + Utils.dipToPixels(2f) + Utils.dipToPixels(3f), cx + s, cy - s + Utils.dipToPixels(2f) + Utils.dipToPixels(3f), iconFillPaint)
            
            // Arrow
            val arrowPath = android.graphics.Path()
            if (isArchived) {
                // Arrow pointing up
                arrowPath.moveTo(cx, cy - Utils.dipToPixels(1f))
                arrowPath.lineTo(cx - Utils.dipToPixels(2.5f), cy + Utils.dipToPixels(1.5f))
                arrowPath.moveTo(cx, cy - Utils.dipToPixels(1f))
                arrowPath.lineTo(cx + Utils.dipToPixels(2.5f), cy + Utils.dipToPixels(1.5f))
                canvas.drawPath(arrowPath, iconFillPaint)
                canvas.drawLine(cx, cy - Utils.dipToPixels(1f), cx, cy + Utils.dipToPixels(4f), iconFillPaint)
            } else {
                // Arrow pointing down
                arrowPath.moveTo(cx, cy + Utils.dipToPixels(3f))
                arrowPath.lineTo(cx - Utils.dipToPixels(2.5f), cy + Utils.dipToPixels(0.5f))
                arrowPath.moveTo(cx, cy + Utils.dipToPixels(3f))
                arrowPath.lineTo(cx + Utils.dipToPixels(2.5f), cy + Utils.dipToPixels(0.5f))
                canvas.drawPath(arrowPath, iconFillPaint)
                canvas.drawLine(cx, cy - Utils.dipToPixels(2f), cx, cy + Utils.dipToPixels(3f), iconFillPaint)
            }
            
            iconFillPaint.style = android.graphics.Paint.Style.FILL // reset
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun updateSwipeBackground(row: View, dx: Float) {
        val isArchived = isInArchivedView(row)
        val isDarkMode = (row.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        var bg = row.background as? SwipeBackgroundDrawable
        if (bg == null) {
            bg = SwipeBackgroundDrawable(row.background, isArchived, isDarkMode)
            row.background = bg
            bg.isArchived = isArchived
            
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
        bg.isDarkMode = isDarkMode
        bg.currentDx = dx
        row.invalidate()
    }

    override fun getPluginName() = "iOS Swipe Menu"
}
