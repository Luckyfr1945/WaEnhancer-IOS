package com.wmods.wppenhacer.xposed.features.customization

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

class IosContextMenu(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {

    companion object {
        private const val CORNER_RADIUS_MENU_DP = 16f

        private const val COLOR_MENU_DARK = "#252528"
        private const val COLOR_MENU_LIGHT = "#FFFFFF"
        private const val COLOR_BORDER_DARK = "#38383A"
        private const val COLOR_BORDER_LIGHT = "#E5E5EA"

        private const val COLOR_DESTRUCTIVE_RED = "#FF453A"
        private const val COLOR_TEXT_DARK = "#FFFFFF"
        private const val COLOR_TEXT_LIGHT = "#000000"

        private const val COLOR_ICON_GRAY_DARK = "#98989D"
        private const val COLOR_ICON_GRAY_LIGHT = "#8E8E93"

        private const val TAG_STYLED = 0x7E11009A
        private const val TAG_MENU_CARD = "ios_context_menu_card"
        private const val TAG_WRAP_CONTAINER = "ios_wrap_container"

        // Thread-safe per-popup state holder to avoid static race conditions and memory leaks
        private val popupStateMap: MutableMap<PopupWindow, PopupState> =
            Collections.synchronizedMap(WeakHashMap<PopupWindow, PopupState>())

        // Fast memory caches for resolved WhatsApp resource IDs
        private val stringResIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val viewIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }

    private data class PopupState(
        var fMessage: FMessageWpp? = null,
        var previewBitmap: Bitmap? = null
    )

    enum class ActionType {
        REPLY,
        FORWARD,
        COPY,
        STAR,
        PIN,
        REPORT,
        DELETE
    }

    override fun doHook() {
        val enabled = prefs.getBoolean("floatingmenu", false) || prefs.getBoolean("ios_header", false)
        if (!enabled) return

        logDebug("IosContextMenu Feature Enabled")

        try {
            val popupClass = Unobfuscator.loadPopupWindowMessageClass(classLoader)

            XposedBridge.hookAllConstructors(popupClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val popupWindow = param.thisObject as? PopupWindow ?: return
                    val contentView = popupWindow.contentView as? ViewGroup ?: return
                    val fMessageObj = param.args?.filterIsInstance(FMessageWpp.TYPE)?.firstOrNull()
                    val fMessage = fMessageObj?.let { FMessageWpp(it) }

                    // Store state locally per popup instance
                    val state = popupStateMap.getOrPut(popupWindow) { PopupState() }
                    state.fMessage = fMessage

                    configurePopupWindow(popupWindow)

                    contentView.post {
                        setupIosContextMenu(popupWindow, contentView, fMessage)
                    }
                }
            })

            val showHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val popupWindow = param.thisObject as? PopupWindow ?: return
                    configurePopupWindow(popupWindow)
                    // Fullscreen coordinates to prevent viewport clipping
                    if (param.args.size >= 4) {
                        param.args[1] = Gravity.TOP or Gravity.START
                        param.args[2] = 0
                        param.args[3] = 0
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val popupWindow = param.thisObject as? PopupWindow ?: return
                    val contentView = popupWindow.contentView as? ViewGroup ?: return
                    val state = popupStateMap[popupWindow]
                    contentView.post {
                        setupIosContextMenu(popupWindow, contentView, state?.fMessage)
                    }
                }
            }

            XposedBridge.hookAllMethods(popupClass, "showAsDropDown", showHook)
            XposedBridge.hookAllMethods(popupClass, "showAtLocation", showHook)

            // Make ListView selector in Conversation transparent to eliminate green selection bar
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setSelector",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = WppCore.getCurrentActivity()
                        if (activity?.javaClass?.simpleName == "Conversation") {
                            param.args[0] = ColorDrawable(Color.TRANSPARENT)
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setSelector",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = WppCore.getCurrentActivity()
                        if (activity?.javaClass?.simpleName == "Conversation") {
                            param.args[0] = android.R.color.transparent
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            logDebug("IosContextMenu: Failed to hook popupWindow: ${e.message}", e)
        }
    }

    private fun configurePopupWindow(popupWindow: PopupWindow) {
        try {
            popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popupWindow.isClippingEnabled = false
            popupWindow.width = ViewGroup.LayoutParams.MATCH_PARENT
            popupWindow.height = ViewGroup.LayoutParams.MATCH_PARENT
            popupWindow.isFocusable = true
            popupWindow.isOutsideTouchable = true
        } catch (e: Throwable) {
            logDebug("IosContextMenu: configurePopupWindow error: ${e.message}", e)
        }
    }

    private fun isDarkMode(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun setDimBehind(popupWindow: PopupWindow, dimAmount: Float = 0.5f) {
        try {
            val container = popupWindow.contentView?.rootView ?: return
            val context = popupWindow.contentView.context ?: return
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            lp.dimAmount = dimAmount
            wm.updateViewLayout(container, lp)
        } catch (e: Throwable) {
            logDebug("IosContextMenu: setDimBehind error: ${e.message}", e)
        }
    }

    private fun hideTopActionModeBar(activity: Activity?) {
        if (activity == null) return
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val actionModeBar = decor.findViewById<View>(androidx.appcompat.R.id.action_mode_bar)
            if (actionModeBar != null) {
                actionModeBar.alpha = 0f
            }
        } catch (e: Throwable) {
            logDebug("IosContextMenu: hideTopActionModeBar error: ${e.message}", e)
        }
    }

    private fun restoreTopActionModeBar(activity: Activity?) {
        if (activity == null) return
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val actionModeBar = decor.findViewById<View>(androidx.appcompat.R.id.action_mode_bar)
            if (actionModeBar != null) {
                actionModeBar.alpha = 1f
            }
        } catch (e: Throwable) {
            logDebug("IosContextMenu: restoreTopActionModeBar error: ${e.message}", e)
        }
    }

    private fun setupIosContextMenu(popupWindow: PopupWindow, root: ViewGroup, fMessage: FMessageWpp?) {
        // Guard against double execution for the same popup
        if (root.findViewWithTag<View>(TAG_MENU_CARD) != null) return

        val ctx = root.context ?: return
        val isDark = isDarkMode(ctx)
        val activity = WppCore.getCurrentActivity() ?: (ctx as? Activity) ?: return
        val state = popupStateMap.getOrPut(popupWindow) { PopupState() }
        if (fMessage != null) state.fMessage = fMessage

        val menuBgColor = if (isDark) Color.parseColor(COLOR_MENU_DARK) else Color.parseColor(COLOR_MENU_LIGHT)
        val menuBorderColor = if (isDark) Color.parseColor(COLOR_BORDER_DARK) else Color.parseColor(COLOR_BORDER_LIGHT)
        val textColor = if (isDark) Color.parseColor(COLOR_TEXT_DARK) else Color.parseColor(COLOR_TEXT_LIGHT)
        val iconColor = if (isDark) Color.parseColor(COLOR_ICON_GRAY_DARK) else Color.parseColor(COLOR_ICON_GRAY_LIGHT)
        val destructiveColor = Color.parseColor(COLOR_DESTRUCTIVE_RED)
        val dividerColor = if (isDark) Color.parseColor(COLOR_BORDER_DARK) else Color.parseColor(COLOR_BORDER_LIGHT)

        // 1. Transparent background & dismiss on outside tap
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root.background = null
        root.elevation = 0f
        if (root !is android.widget.AdapterView<*>) {
            try {
                root.setOnClickListener {
                    try { popupWindow.dismiss() } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                logDebug("IosContextMenu: root setOnClickListener error: ${e.message}", e)
            }
        }

        // Apply iOS-style dim behind & hide top Action Bar
        setDimBehind(popupWindow, 0.45f)
        hideTopActionModeBar(activity)

        // Clean up resources safely on dismiss without race condition
        popupWindow.setOnDismissListener {
            restoreTopActionModeBar(activity)
            val currentState = popupStateMap.remove(popupWindow)
            val bmp = currentState?.previewBitmap
            if (bmp != null && !bmp.isRecycled) {
                // Let GC collect safely; clear reference
                currentState.previewBitmap = null
            }
        }

        // 2. Locate the Reaction Tray (keep native WhatsApp background & styling intact)
        val reactionTrayId = Utils.getID("reactions_tray_layout", "id")
        var reactionTray = if (reactionTrayId > 0) root.findViewById<View>(reactionTrayId) else null
        if (reactionTray == null) {
            reactionTray = findReactionTray(root)
        }

        if (reactionTray != null) {
            reactionTray.setTag(TAG_STYLED, true)
        } else {
            logDebug("IosContextMenu: reactionTray not found in root hierarchy")
        }

        // 3. Clear intermediate backgrounds except reaction tray & iOS custom cards
        fun clearAllIntermediateBackgrounds(v: View) {
            if (v === reactionTray || v.tag == TAG_MENU_CARD || v.tag == TAG_WRAP_CONTAINER) {
                return
            }
            v.background = null
            v.elevation = 0f
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    clearAllIntermediateBackgrounds(v.getChildAt(i))
                }
            }
        }
        clearAllIntermediateBackgrounds(root)

        // 4. Inject iOS Context Menu Card
        val cardWidth = Utils.dipToPixels(240f)
        val menuCard = LinearLayout(activity).apply {
            tag = TAG_MENU_CARD
            orientation = LinearLayout.VERTICAL
            val menuDrawable = GradientDrawable().apply {
                setColor(menuBgColor)
                cornerRadius = Utils.dipToPixels(CORNER_RADIUS_MENU_DP).toFloat()
                setStroke(Utils.dipToPixels(1f), menuBorderColor)
            }
            background = menuDrawable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
                elevation = Utils.dipToPixels(8f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Build localized menu items using native WhatsApp resources
        val items = buildLocalizedMenuItems(ctx, state.fMessage)

        val rowHeight = Utils.dipToPixels(44f)
        val rowPaddingH = Utils.dipToPixels(16f)
        val iconSize = Utils.dipToPixels(20f)
        val dividerHeight = Utils.dipToPixels(0.75f).coerceAtLeast(1)

        items.forEachIndexed { index, itemDef ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
                setPadding(rowPaddingH, 0, rowPaddingH, 0)
                background = createRowRipple(isDark)
                isClickable = true
                isFocusable = true

                val label = TextView(activity).apply {
                    text = itemDef.title
                    textSize = 16f
                    setTextColor(if (itemDef.isDestructive) destructiveColor else textColor)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(label)

                val iconView = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    val iconTint = if (itemDef.isDestructive) destructiveColor else iconColor
                    setImageDrawable(IosIconDrawable(itemDef.action, iconTint))
                }
                addView(iconView)

                setOnClickListener {
                    executeAction(activity, itemDef.action, itemDef.title, state.fMessage, popupWindow)
                }
            }
            menuCard.addView(row)

            if (index < items.lastIndex) {
                val divider = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight)
                    setBackgroundColor(dividerColor)
                }
                menuCard.addView(divider)
            }
        }

        // 5. Create Message Bubble Preview if message view is found
        val messageId = state.fMessage?.key?.messageID ?: ""
        var messageView: View? = null
        if (messageId.isNotEmpty()) {
            messageView = com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener.listItems.entries.firstOrNull {
                it.value.messageId == messageId
            }?.key
        }

        val isFromMe = state.fMessage?.key?.isFromMe == true
        val cardGravity = if (isFromMe) Gravity.END else Gravity.START

        var messagePreview: View? = null
        if (messageView != null && messageView.width > 0 && messageView.height > 0) {
            try {
                messageView.background = null
                messageView.isSelected = false
                messageView.isActivated = false
                val bitmap = Bitmap.createBitmap(messageView.width, messageView.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                messageView.draw(canvas)
                state.previewBitmap = bitmap

                messagePreview = ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(
                        messageView.width,
                        messageView.height
                    ).apply {
                        topMargin = Utils.dipToPixels(6f)
                        bottomMargin = Utils.dipToPixels(8f)
                        gravity = cardGravity
                    }
                }
            } catch (e: Throwable) {
                logDebug("IosContextMenu: create message preview error: ${e.message}", e)
            }
        }

        (menuCard.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.gravity = cardGravity
        }

        // 6. Wrap into vertical container
        val wrapContainer = LinearLayout(activity).apply {
            tag = TAG_WRAP_CONTAINER
            orientation = LinearLayout.VERTICAL
            gravity = cardGravity
            background = null
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        if (reactionTray != null) {
            val origParent = reactionTray.parent as? ViewGroup
            origParent?.removeView(reactionTray)
            reactionTray.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = cardGravity
            }
            wrapContainer.addView(reactionTray)
            if (messagePreview != null) {
                wrapContainer.addView(messagePreview)
            }
            wrapContainer.addView(menuCard)

            if (origParent != null && origParent !is android.widget.AdapterView<*>) {
                origParent.addView(wrapContainer)
                origParent.background = null
            } else {
                safeAddView(popupWindow, root, wrapContainer)
            }
        } else {
            if (messagePreview != null) {
                wrapContainer.addView(messagePreview)
            }
            wrapContainer.addView(menuCard)
            safeAddView(popupWindow, root, wrapContainer)
        }

        // 7. Dynamic Smart Centering & Viewport Clamping (Safe insets aware)
        wrapContainer.post {
            try {
                val dm = activity.resources.displayMetrics
                val screenHeight = dm.heightPixels
                val screenWidth = dm.widthPixels

                val loc = IntArray(2)
                val msgLocY = if (messageView != null) {
                    messageView.getLocationOnScreen(loc)
                    loc[1]
                } else {
                    screenHeight / 3
                }

                // Dynamic Safe insets calculation
                val statusBarHeight = getStatusBarHeight(activity)
                val navBarHeight = getNavigationBarHeight(activity)
                val safeTop = (statusBarHeight + Utils.dipToPixels(12f)).coerceAtLeast(Utils.dipToPixels(48f))
                val safeBottom = (screenHeight - navBarHeight - Utils.dipToPixels(16f)).coerceAtLeast(safeTop + Utils.dipToPixels(100f))

                val clusterH = wrapContainer.height
                val idealY = msgLocY - Utils.dipToPixels(50f)
                val clampedY = idealY.coerceIn(safeTop, (safeBottom - clusterH).coerceAtLeast(safeTop))

                wrapContainer.y = clampedY.toFloat()

                val sideMargin = Utils.dipToPixels(16f).toFloat()
                if (isFromMe) {
                    wrapContainer.x = (screenWidth - wrapContainer.width - sideMargin).coerceAtLeast(sideMargin)
                } else {
                    wrapContainer.x = sideMargin
                }
            } catch (e: Throwable) {
                logDebug("IosContextMenu: layout positioning error: ${e.message}", e)
            }
        }

        clearAllIntermediateBackgrounds(root)

        try {
            popupWindow.width = ViewGroup.LayoutParams.MATCH_PARENT
            popupWindow.height = ViewGroup.LayoutParams.MATCH_PARENT
            popupWindow.update()
        } catch (e: Throwable) {
            logDebug("IosContextMenu: popupWindow update error: ${e.message}", e)
        }
    }

    private fun getStatusBarHeight(activity: Activity): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = activity.window?.decorView?.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())
                if (insets != null && insets.top > 0) return insets.top
            }
            val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else Utils.dipToPixels(24f)
        } catch (_: Throwable) {
            Utils.dipToPixels(24f)
        }
    }

    private fun getNavigationBarHeight(activity: Activity): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = activity.window?.decorView?.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())
                if (insets != null && insets.bottom > 0) return insets.bottom
            }
            val resourceId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else Utils.dipToPixels(48f)
        } catch (_: Throwable) {
            Utils.dipToPixels(48f)
        }
    }

    private fun safeAddView(popupWindow: PopupWindow, root: ViewGroup, child: View) {
        try {
            if (root is android.widget.AdapterView<*>) {
                val frame = FrameLayout(root.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                frame.addView(child)
                popupWindow.contentView = frame
            } else {
                root.addView(child)
            }
        } catch (e: Throwable) {
            logDebug("IosContextMenu: safeAddView fallback due to: ${e.message}", e)
            try {
                val frame = FrameLayout(root.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                frame.addView(child)
                popupWindow.contentView = frame
            } catch (e2: Throwable) {
                logDebug("IosContextMenu: safeAddView fatal: ${e2.message}", e2)
            }
        }
    }

    private data class MenuItemDef(
        val action: ActionType,
        val title: String,
        val isDestructive: Boolean
    )

    /**
     * Resolves localized strings dynamically from WhatsApp's native string resources.
     * Falls back to built-in translations if resource keys cannot be resolved.
     */
    private fun getLocalizedActionTitle(ctx: Context, action: ActionType): String {
        // 1. Attempt dynamic resolution from WhatsApp's native string table
        val resourceKeys = when (action) {
            ActionType.REPLY -> listOf("conversation_menu_reply", "reply", "action_reply")
            ActionType.FORWARD -> listOf("conversation_menu_forward", "forward", "action_forward")
            ActionType.COPY -> listOf("conversation_menu_copy", "copy", "action_copy")
            ActionType.STAR -> listOf("conversation_menu_star", "star", "action_star")
            ActionType.PIN -> listOf("pin_message", "conversation_menu_pin", "pin", "action_pin")
            ActionType.REPORT -> listOf("conversation_menu_report", "report", "action_report")
            ActionType.DELETE -> listOf("conversation_menu_delete", "delete", "action_delete")
        }

        for (key in resourceKeys) {
            val resId = stringResIdCache.getOrPut(key) { Utils.getID(key, "string") }
            if (resId > 0) {
                try {
                    val str = ctx.getString(resId)
                    if (!str.isNullOrBlank()) return str
                } catch (_: Throwable) {}
            }
        }

        // 2. Multi-language dictionary fallback
        val lang = ctx.resources.configuration.locales[0].language.lowercase()
        val isIndo = lang.startsWith("in") || lang.startsWith("id")
        val isPt = lang.startsWith("pt")
        val isEs = lang.startsWith("es")
        val isIt = lang.startsWith("it")
        val isFr = lang.startsWith("fr")
        val isAr = lang.startsWith("ar")
        val isDe = lang.startsWith("de")
        val isRu = lang.startsWith("ru")

        return when (action) {
            ActionType.REPLY -> when {
                isIndo -> "Balas"
                isPt -> "Responder"
                isEs -> "Responder"
                isIt -> "Rispondi"
                isFr -> "Répondre"
                isAr -> "رد"
                isDe -> "Antworten"
                isRu -> "Ответить"
                else -> "Reply"
            }
            ActionType.FORWARD -> when {
                isIndo -> "Teruskan"
                isPt -> "Encaminhar"
                isEs -> "Reenviar"
                isIt -> "Inoltra"
                isFr -> "Transférer"
                isAr -> "إعادة توجيه"
                isDe -> "Weiterleiten"
                isRu -> "Переслать"
                else -> "Forward"
            }
            ActionType.COPY -> when {
                isIndo -> "Salin"
                isPt -> "Copiar"
                isEs -> "Copiar"
                isIt -> "Copia"
                isFr -> "Copier"
                isAr -> "نسخ"
                isDe -> "Kopieren"
                isRu -> "Копировать"
                else -> "Copy"
            }
            ActionType.STAR -> when {
                isIndo -> "Beri bintang"
                isPt -> "Favoritar"
                isEs -> "Destacar"
                isIt -> "Aggiungi ai preferiti"
                isFr -> "Ajouter aux favoris"
                isAr -> "تمييز بنجمة"
                isDe -> "Mit Stern markieren"
                isRu -> "В избранное"
                else -> "Star"
            }
            ActionType.PIN -> when {
                isIndo -> "Sematkan"
                isPt -> "Fixar"
                isEs -> "Fijar"
                isIt -> "Fissa"
                isFr -> "Épingler"
                isAr -> "تثبيت"
                isDe -> "Anheften"
                isRu -> "Закрепить"
                else -> "Pin"
            }
            ActionType.REPORT -> when {
                isIndo -> "Laporkan"
                isPt -> "Denunciar"
                isEs -> "Reportar"
                isIt -> "Segnala"
                isFr -> "Signaler"
                isAr -> "إبلاغ"
                isDe -> "Melden"
                isRu -> "Пожаловаться"
                else -> "Report"
            }
            ActionType.DELETE -> when {
                isIndo -> "Hapus"
                isPt -> "Apagar"
                isEs -> "Eliminar"
                isIt -> "Elimina"
                isFr -> "Supprimer"
                isAr -> "حذف"
                isDe -> "Löschen"
                isRu -> "Удалить"
                else -> "Delete"
            }
        }
    }

    private fun buildLocalizedMenuItems(ctx: Context, fMessage: FMessageWpp?): List<MenuItemDef> {
        val hasText = (fMessage?.messageStr ?: "").isNotEmpty()
        val items = mutableListOf<MenuItemDef>()
        items.add(MenuItemDef(ActionType.REPLY, getLocalizedActionTitle(ctx, ActionType.REPLY), false))
        items.add(MenuItemDef(ActionType.FORWARD, getLocalizedActionTitle(ctx, ActionType.FORWARD), false))
        if (hasText) {
            items.add(MenuItemDef(ActionType.COPY, getLocalizedActionTitle(ctx, ActionType.COPY), false))
        }
        items.add(MenuItemDef(ActionType.STAR, getLocalizedActionTitle(ctx, ActionType.STAR), false))
        items.add(MenuItemDef(ActionType.PIN, getLocalizedActionTitle(ctx, ActionType.PIN), false))
        items.add(MenuItemDef(ActionType.REPORT, getLocalizedActionTitle(ctx, ActionType.REPORT), false))
        items.add(MenuItemDef(ActionType.DELETE, getLocalizedActionTitle(ctx, ActionType.DELETE), true))
        return items
    }

    private fun findReactionTray(vg: ViewGroup): View? {
        var imageChildCount = 0
        val totalChildCount = vg.childCount
        for (i in 0 until totalChildCount) {
            if (vg.getChildAt(i) is ImageView) imageChildCount++
        }
        if (totalChildCount in 4..10 && imageChildCount >= 4 &&
            imageChildCount.toFloat() / totalChildCount >= 0.6f) {
            return vg
        }
        for (i in 0 until totalChildCount) {
            val child = vg.getChildAt(i)
            if (child is ViewGroup) {
                val found = findReactionTray(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun createRowRipple(isDark: Boolean): Drawable {
        val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")
        val mask = ColorDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
    }

    private fun executeAction(
        activity: Activity,
        action: ActionType,
        localizedTitle: String,
        fMessage: FMessageWpp?,
        popupWindow: PopupWindow
    ) {
        try {
            popupWindow.dismiss()
        } catch (_: Throwable) {}

        var actionTriggered = false

        when (action) {
            ActionType.REPLY -> {
                val replyView = findActionView(activity, listOf("menuitem_conversations_reply", "reply", "menuitem_reply"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "balas", "reply", "responder", "rispondi", "répondre", "antworten"))
                if (replyView != null) {
                    replyView.performClick()
                    actionTriggered = true
                }
            }
            ActionType.FORWARD -> {
                val forwardView = findActionView(activity, listOf("menuitem_conversations_forward", "forward", "menuitem_forward"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "teruskan", "forward", "encaminhar", "reenviar", "inoltra", "transférer", "weiterleiten"))
                if (forwardView != null) {
                    forwardView.performClick()
                    actionTriggered = true
                }
            }
            ActionType.COPY -> {
                val copyView = findActionView(activity, listOf("menuitem_conversations_copy", "copy", "menuitem_copy"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "salin", "copy", "copiar", "copia", "copier", "kopieren"))
                if (copyView != null) {
                    copyView.performClick()
                    actionTriggered = true
                } else {
                    val text = fMessage?.messageStr ?: ""
                    if (text.isNotEmpty()) {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("WhatsApp Message", text))
                        Utils.showToast(localizedTitle, Toast.LENGTH_SHORT)
                        actionTriggered = true
                        finishActionMode(activity)
                    }
                }
            }
            ActionType.STAR -> {
                val starView = findActionView(activity, listOf("menuitem_conversations_star", "star", "menuitem_conversations_unstar", "unstar"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "bintang", "star", "favorito", "stella", "favoris", "stern"))
                if (starView != null) {
                    starView.performClick()
                    actionTriggered = true
                }
            }
            ActionType.PIN -> {
                val pinView = findActionView(activity, listOf("menuitem_conversations_pin", "pin", "menuitem_pin"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "sematkan", "pin", "fijar", "fixar", "fissa", "épingler", "anheften"))
                if (pinView != null) {
                    pinView.performClick()
                    actionTriggered = true
                }
            }
            ActionType.REPORT -> {
                val reportView = findActionView(activity, listOf("menuitem_conversations_report", "report", "menuitem_report"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "laporkan", "report", "denunciar", "segnala", "signaler", "melden"))
                if (reportView != null) {
                    reportView.performClick()
                    actionTriggered = true
                }
            }
            ActionType.DELETE -> {
                val deleteView = findActionView(activity, listOf("menuitem_conversations_delete", "delete", "menuitem_delete"))
                    ?: findViewByDescription(activity, listOf(localizedTitle, "hapus", "delete", "eliminar", "excluir", "cancella", "supprimer", "apagar", "löschen"))
                if (deleteView != null) {
                    deleteView.performClick()
                    actionTriggered = true
                }
            }
        }

        if (!actionTriggered) {
            logDebug("IosContextMenu: Action $action ($localizedTitle) view not found in active action mode")
            finishActionMode(activity)
        }
    }

    private fun findActionView(activity: Activity, keywords: List<String>): View? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        for (kw in keywords) {
            val id = viewIdCache.getOrPut(kw) { Utils.getID(kw, "id") }
            if (id > 0) {
                val v = decor.findViewById<View>(id)
                if (v != null && v.isShown) return v
            }
        }
        return null
    }

    private fun findViewByDescription(activity: Activity, keywords: List<String>): View? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val lowerKeywords = keywords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        
        fun search(vg: ViewGroup): View? {
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                if (desc.isNotEmpty() && lowerKeywords.any { desc.contains(it) }) {
                    return child
                }
                if (child is ViewGroup) {
                    val res = search(child)
                    if (res != null) return res
                }
            }
            return null
        }
        return search(decor)
    }

    private fun finishActionMode(activity: Activity) {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val closeBtn = decor.findViewById<View>(androidx.appcompat.R.id.action_mode_close_button)
                ?: findViewByDescription(activity, listOf("tutup", "close", "batal", "cancel", "navigate up", "kembali", "zurück"))
            closeBtn?.performClick()
        } catch (e: Throwable) {
            logDebug("IosContextMenu: finishActionMode error: ${e.message}", e)
        }
    }

    /**
     * Vector icon drawable that draws crisp iOS SF Symbols style icons
     */
    private class IosIconDrawable(
        private val type: ActionType,
        private val color: Int
    ) : Drawable() {

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@IosIconDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = Utils.dipToPixels(1.8f).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@IosIconDrawable.color
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val left = b.left.toFloat()
            val top = b.top.toFloat()

            canvas.save()
            canvas.translate(left, top)

            val p = Path()
            when (type) {
                ActionType.REPLY -> {
                    // Curved arrow pointing top-left (↩)
                    p.moveTo(w * 0.40f, h * 0.28f)
                    p.lineTo(w * 0.18f, h * 0.45f)
                    p.lineTo(w * 0.40f, h * 0.62f)
                    p.moveTo(w * 0.20f, h * 0.45f)
                    p.cubicTo(w * 0.50f, h * 0.45f, w * 0.78f, h * 0.55f, w * 0.82f, h * 0.82f)
                    canvas.drawPath(p, strokePaint)
                }
                ActionType.FORWARD -> {
                    // Curved arrow pointing top-right (↪)
                    p.moveTo(w * 0.60f, h * 0.28f)
                    p.lineTo(w * 0.82f, h * 0.45f)
                    p.lineTo(w * 0.60f, h * 0.62f)
                    p.moveTo(w * 0.80f, h * 0.45f)
                    p.cubicTo(w * 0.50f, h * 0.45f, w * 0.22f, h * 0.55f, w * 0.18f, h * 0.82f)
                    canvas.drawPath(p, strokePaint)
                }
                ActionType.COPY -> {
                    // Two overlapping rounded rectangles (❐)
                    val r = Utils.dipToPixels(2.5f).toFloat()
                    val backRect = RectF(w * 0.15f, w * 0.15f, w * 0.65f, w * 0.65f)
                    val frontRect = RectF(w * 0.35f, w * 0.35f, w * 0.85f, w * 0.85f)
                    canvas.drawRoundRect(backRect, r, r, strokePaint)
                    canvas.drawRoundRect(frontRect, r, r, strokePaint)
                }
                ActionType.STAR -> {
                    // 5-pointed star outline (☆)
                    val cx = w / 2f
                    val cy = h / 2f
                    val outerR = w * 0.40f
                    val innerR = outerR * 0.42f
                    for (i in 0 until 10) {
                        val radius = if (i % 2 == 0) outerR else innerR
                        val angle = (Math.PI / 5) * i - Math.PI / 2
                        val x = cx + (radius * Math.cos(angle)).toFloat()
                        val y = cy + (radius * Math.sin(angle)).toFloat()
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                    canvas.drawPath(p, strokePaint)
                }
                ActionType.PIN -> {
                    // Pushpin outline (📌)
                    p.moveTo(w * 0.35f, h * 0.18f)
                    p.lineTo(w * 0.65f, h * 0.18f)
                    p.moveTo(w * 0.40f, h * 0.18f)
                    p.lineTo(w * 0.42f, h * 0.45f)
                    p.lineTo(w * 0.30f, h * 0.58f)
                    p.lineTo(w * 0.70f, h * 0.58f)
                    p.lineTo(w * 0.58f, h * 0.45f)
                    p.lineTo(w * 0.60f, h * 0.18f)
                    p.moveTo(w * 0.50f, h * 0.58f)
                    p.lineTo(w * 0.50f, h * 0.85f)
                    canvas.drawPath(p, strokePaint)
                }
                ActionType.REPORT -> {
                    // Warning triangle with exclamation mark (⚠️)
                    p.moveTo(w * 0.50f, h * 0.18f)
                    p.lineTo(w * 0.85f, h * 0.80f)
                    p.lineTo(w * 0.15f, h * 0.80f)
                    p.close()
                    canvas.drawPath(p, strokePaint)
                    // Exclamation line & dot
                    canvas.drawLine(w * 0.50f, h * 0.40f, w * 0.50f, h * 0.58f, strokePaint)
                    canvas.drawCircle(w * 0.50f, h * 0.70f, Utils.dipToPixels(1.2f).toFloat(), fillPaint)
                }
                ActionType.DELETE -> {
                    // Trash can with lid and ribs (🗑)
                    p.moveTo(w * 0.20f, h * 0.30f)
                    p.lineTo(w * 0.80f, h * 0.30f)
                    p.moveTo(w * 0.38f, h * 0.30f)
                    p.lineTo(w * 0.38f, h * 0.20f)
                    p.lineTo(w * 0.62f, h * 0.20f)
                    p.lineTo(w * 0.62f, h * 0.30f)
                    p.moveTo(w * 0.26f, h * 0.30f)
                    p.lineTo(w * 0.32f, h * 0.82f)
                    p.lineTo(w * 0.68f, h * 0.82f)
                    p.lineTo(w * 0.74f, h * 0.30f)
                    p.moveTo(w * 0.42f, h * 0.42f)
                    p.lineTo(w * 0.44f, h * 0.72f)
                    p.moveTo(w * 0.58f, h * 0.42f)
                    p.lineTo(w * 0.56f, h * 0.72f)
                    canvas.drawPath(p, strokePaint)
                }
            }

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            strokePaint.alpha = alpha
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun getPluginName(): String {
        return "iOS Context Menu"
    }
}