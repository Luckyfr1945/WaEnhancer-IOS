package com.wmods.wppenhacer.xposed.features.customization

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.children
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class IosContextMenu(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {

    companion object {
        private const val CORNER_RADIUS_MENU_DP = 16f
        private const val CORNER_RADIUS_REACTION_DP = 24f

        private const val COLOR_MENU_DARK = "#252528"
        private const val COLOR_MENU_LIGHT = "#FFFFFF"
        private const val COLOR_BORDER_DARK = "#38383A"
        private const val COLOR_BORDER_LIGHT = "#E5E5EA"

        private const val COLOR_REACTION_DARK = "#2C2C2E"
        private const val COLOR_REACTION_LIGHT = "#F2F2F7"
        private const val COLOR_REACTION_BORDER_DARK = "#3A3A3C"
        private const val COLOR_REACTION_BORDER_LIGHT = "#D1D1D6"

        private const val COLOR_DESTRUCTIVE_RED = "#FF453A"
        private const val COLOR_TEXT_DARK = "#FFFFFF"
        private const val COLOR_TEXT_LIGHT = "#000000"

        // iOS secondary-label gray, used for icons on non-destructive rows
        private const val COLOR_ICON_GRAY_DARK = "#98989D"
        private const val COLOR_ICON_GRAY_LIGHT = "#8E8E93"

        private val DESTRUCTIVE_KEYWORDS = listOf(
            "hapus", "delete", "eliminar", "excluir", "cancella", "supprimer", "borrar"
        )

        // Custom tag key used to mark a popup's elevated group as already
        // repositioned, so we don't re-run the correction on every layout pass.
        private const val TAG_REPOSITIONED = 0x7E110099
        private const val TAG_STYLED = 0x7E11009A
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

                    contentView.post {
                        styleContextMenuHierarchy(popupWindow, contentView, null, null)
                    }

                    contentView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        private var cachedReactionTray: View? = null
                        private var cachedMenuContainer: View? = null
                        private var isStyled = false

                        override fun onGlobalLayout() {
                            try {
                                if (!isStyled) {
                                    val result = styleContextMenuHierarchy(popupWindow, contentView, cachedReactionTray, cachedMenuContainer)
                                    cachedReactionTray = result.first
                                    cachedMenuContainer = result.second
                                    if (cachedReactionTray != null || cachedMenuContainer != null) {
                                        isStyled = true
                                    }
                                }
                            } catch (e: Throwable) {
                                logDebug("IosContextMenu: layout listener error: ${e.message}")
                            }
                        }
                    })
                }
            })
        } catch (e: Throwable) {
            logDebug("IosContextMenu: Failed to hook popupWindow: ${e.message}")
        }
    }

    private fun isDarkMode(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun styleContextMenuHierarchy(popupWindow: PopupWindow, root: ViewGroup, cachedReactionTray: View?, cachedMenuContainer: View?): Pair<View?, View?> {
        val ctx = root.context ?: return Pair(null, null)
        val isDark = isDarkMode(ctx)

        val menuBgColor = if (isDark) Color.parseColor(COLOR_MENU_DARK) else Color.parseColor(COLOR_MENU_LIGHT)
        val menuBorderColor = if (isDark) Color.parseColor(COLOR_BORDER_DARK) else Color.parseColor(COLOR_BORDER_LIGHT)

        val reactionBgColor = if (isDark) Color.parseColor(COLOR_REACTION_DARK) else Color.parseColor(COLOR_REACTION_LIGHT)
        val reactionBorderColor = if (isDark) Color.parseColor(COLOR_REACTION_BORDER_DARK) else Color.parseColor(COLOR_REACTION_BORDER_LIGHT)

        // 1. Style Reaction Tray Layout
        val reactionTrayId = Utils.getID("reactions_tray_layout", "id")
        val reactionTray = cachedReactionTray ?: (if (reactionTrayId > 0) root.findViewById<View>(reactionTrayId) else null)
        if (reactionTray != null && reactionTray.getTag(TAG_STYLED) != true) {
            val reactionDrawable = GradientDrawable().apply {
                setColor(reactionBgColor)
                cornerRadius = Utils.dipToPixels(CORNER_RADIUS_REACTION_DP).toFloat()
                setStroke(Utils.dipToPixels(1f), reactionBorderColor)
            }
            reactionTray.background = reactionDrawable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                reactionTray.clipToOutline = true
                reactionTray.elevation = Utils.dipToPixels(4f).toFloat()
            }
            reactionTray.setTag(TAG_STYLED, true)
        }

        // 2. Style DropDown Menu Container & Items (returns the matched
        //    container, if any was found in THIS popup's hierarchy)
        val menuContainer = cachedMenuContainer ?: styleMenuContainers(root, menuBgColor, menuBorderColor, isDark)

        return Pair(reactionTray, menuContainer)
    }

    private fun styleMenuContainers(
        viewGroup: ViewGroup,
        bgColor: Int,
        borderColor: Int,
        isDark: Boolean
    ): View? {
        var found: View? = null
        for (child in viewGroup.children) {
            val className = child.javaClass.name

            // Detect RecyclerView / ListView or Menu container
            if (className.contains("DropDown", ignoreCase = true) ||
                className.contains("RecyclerView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ||
                className.contains("MessageSelection", ignoreCase = true)
            ) {
                if (child.getTag(TAG_STYLED) != true) {
                    val menuDrawable = GradientDrawable().apply {
                        setColor(bgColor)
                        cornerRadius = Utils.dipToPixels(CORNER_RADIUS_MENU_DP).toFloat()
                        setStroke(Utils.dipToPixels(1f), borderColor)
                    }
                    child.background = menuDrawable
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        child.clipToOutline = true
                        child.elevation = Utils.dipToPixels(6f).toFloat()
                    }
                    child.setTag(TAG_STYLED, true)
                }

                if (child is ViewGroup) {
                    styleMenuItems(child, isDark)
                }
                found = child
            } else if (child is ViewGroup) {
                val childFound = styleMenuContainers(child, bgColor, borderColor, isDark)
                if (childFound != null) found = childFound
            }
        }
        return found
    }

    /**
     * Walk the ENTIRE subtree (not just direct children of [container]) and style
     * every individual row it finds. Whichever container ended up matching in
     * styleMenuContainers() might be several levels above the actual row views
     * (e.g. a wrapper whose only direct child is the RecyclerView holding all the
     * rows) — so we recurse until we hit a ViewGroup that directly owns a TextView,
     * treat that as one row, style it, and stop descending into it.
     */
    private fun styleMenuItems(container: ViewGroup, isDark: Boolean) {
        fun findRows(view: View) {
            if (view !is ViewGroup) return
            val rowText = view.children.firstOrNull { it is TextView } as? TextView
            if (rowText != null) {
                styleRow(view, rowText, isDark)
                return // this ViewGroup IS a row, don't recurse into it further
            }
            for (child in view.children) {
                findRows(child)
            }
        }
        for (child in container.children) {
            findRows(child)
        }
    }

    /** Style a single menu row: its label text and (if present) its icon. */
    private fun styleRow(row: ViewGroup, textView: TextView, isDark: Boolean) {
        var iconView: ImageView? = null
        fun findIcon(v: View) {
            if (iconView != null) return
            if (v is ImageView) {
                iconView = v
            } else if (v is ViewGroup) {
                for (c in v.children) findIcon(c)
            }
        }
        findIcon(row)

        val textStr = textView.text?.toString()?.lowercase() ?: ""
        val isDestructive = DESTRUCTIVE_KEYWORDS.any { textStr.contains(it) }

        if (isDestructive) {
            val redColor = Color.parseColor(COLOR_DESTRUCTIVE_RED)
            textView.setTextColor(redColor)
            iconView?.imageTintList = ColorStateList.valueOf(redColor)
        } else {
            val textColor = if (isDark) Color.parseColor(COLOR_TEXT_DARK) else Color.parseColor(COLOR_TEXT_LIGHT)
            val iconColor = if (isDark) Color.parseColor(COLOR_ICON_GRAY_DARK) else Color.parseColor(COLOR_ICON_GRAY_LIGHT)
            textView.setTextColor(textColor)
            iconView?.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    override fun getPluginName(): String {
        return "iOS Context Menu"
    }
}