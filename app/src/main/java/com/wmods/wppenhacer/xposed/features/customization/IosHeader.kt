package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType

class IosHeader(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    companion object {
        const val TAG = "IosHeader"
        const val TAG_ORIGINAL_MARGIN = 0x7E110001
        const val TAG_SEARCH_ORIGINAL_MARGIN = 0x7E110002
        const val TAG_LARGE_TITLE_VIEW = 0x7E110003
        const val TAG_ACTION_BUTTONS_CONTAINER = 0x7E120099
        const val TAG_CONFIGURED_TYPE = 0x7E12009A
        val hookedToolbarClasses: MutableSet<Class<*>> = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())
    }

    private fun getLargeTitleView(header: ViewGroup): TextView? {
        return header.getTag(TAG_LARGE_TITLE_VIEW) as? TextView
    }

    /**
     * Drawable ikon titik-tiga (⋯) bergaya iOS, dibungkus lingkaran abu-abu.
     * Ukuran sekarang dikonversi ke px sesuai density layar, bukan hardcode 96.
     */
    class IosMenuDrawable(context: android.content.Context, private val isNight: Boolean) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val sizePx = (28 * density).toInt() // ~28dp, mirip ukuran icon toolbar iOS

        private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        }
        private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNight) Color.WHITE else Color.parseColor("#3C3C43")
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.width() / 2f
            val cy = bounds.height() / 2f
            val radius = Math.min(cx, cy) * 0.85f
            canvas.drawCircle(cx, cy, radius, paintBg)
            val dotRadius = radius * 0.14f
            val spacing = dotRadius * 3.2f
            canvas.drawCircle(cx - spacing, cy, dotRadius, paintDot)
            canvas.drawCircle(cx, cy, dotRadius, paintDot)
            canvas.drawCircle(cx + spacing, cy, dotRadius, paintDot)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }

    override fun doHook() {
        if (!prefs.getBoolean("ios_header", false)) return

        // 1. Suntik Large Title + ganti navigation icon jadi titik-tiga iOS
        XposedHelpers.findAndHookMethod(
            WppCore.homeActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    try {
                        val headerId = Utils.getID("header", "id")
                        val toolbarId = Utils.getID("toolbar", "id")

                        if (headerId == 0 || toolbarId == 0) {
                            return
                        }

                        val header = activity.findViewById<ViewGroup>(headerId)
                        val toolbar = activity.findViewById<ViewGroup>(toolbarId)
                        if (header == null || toolbar == null) {
                            return
                        }

                        // Hook setTitle ke CLASS ASLI toolbar (menggunakan Set untuk multi-toolbar support)
                        try {
                            if (hookedToolbarClasses.add(toolbar.javaClass)) {
                                XposedBridge.hookAllMethods(toolbar.javaClass, "setTitle", object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param2: MethodHookParam) {
                                        val t = param2.thisObject as? View ?: return
                                        if (t.id != toolbarId) return
                                        
                                        var context = t.context
                                        while (context is android.content.ContextWrapper) {
                                            if (context.javaClass == WppCore.homeActivityClass) break
                                            val base = context.baseContext
                                            if (base === context) break
                                            context = base
                                        }
                                        if (context.javaClass != WppCore.homeActivityClass) return
                                        
                                        val title = param2.args.getOrNull(0) as? CharSequence
                                        val rawTitle = title?.toString()?.trim() ?: ""
                                        val isSettings = isSettingsTabTitle(rawTitle)

                                        val largeTitle = getLargeTitleView(header)
                                        if (isSettings) {
                                            if (largeTitle != null) {
                                                largeTitle.text = ""
                                                largeTitle.visibility = View.GONE
                                            }
                                        } else {
                                            val newTitle = resolveTabTitle(rawTitle)
                                            if (largeTitle != null) {
                                                largeTitle.text = newTitle
                                                largeTitle.visibility = View.VISIBLE
                                                param2.args[0] = ""
                                            } else {
                                                param2.args[0] = newTitle
                                            }
                                        }
                                        val isChats = !isSettings && resolveTabTitle(rawTitle) == "Chats"
                                        header.post {
                                            setContainerMargin(header, isChats)
                                        }
                                    }
                                    override fun afterHookedMethod(param2: MethodHookParam) {
                                        val t = param2.thisObject as? ViewGroup ?: return
                                        if (t.id != toolbarId) return
                                        
                                        var context = t.context
                                        while (context is android.content.ContextWrapper) {
                                            if (context.javaClass == WppCore.homeActivityClass) break
                                            val base = context.baseContext
                                            if (base === context) break
                                            context = base
                                        }
                                        if (context.javaClass != WppCore.homeActivityClass) return
                                        
                                        val title = param2.args.getOrNull(0) as? CharSequence
                                        val rawTitle = title?.toString()?.trim() ?: ""
                                        if (!isSettingsTabTitle(rawTitle)) {
                                            clearToolbarContent(t)
                                        }
                                    }
                                })
                            }
                        } catch (e: Throwable) {
                            logDebug("IosHeader: hook setTitle failed: ${e.message}")
                        }

                        // Sembunyikan logo & title native
                        val logo = toolbar.findViewById<View>(Utils.getID("toolbar_logo", "id"))
                        if (logo != null) logo.visibility = View.GONE

                        var initialTitle = "Chats"
                        try {
                            val getTitleMethod = toolbar.javaClass.getMethod("getTitle")
                            val currentTitle = getTitleMethod.invoke(toolbar) as? CharSequence
                            if (!currentTitle.isNullOrEmpty()) {
                                initialTitle = resolveTabTitle(currentTitle.toString().trim())
                            }
                        } catch (e: Throwable) {}

                        try {
                            val setTitleMethod = toolbar.javaClass.getMethod("setTitle", CharSequence::class.java)
                            setTitleMethod.invoke(toolbar, "")
                        } catch (e: Exception) {}
                        
                        toolbar.post {
                            clearToolbarContent(toolbar)
                        }
                        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: View?, child: View?) {
                                clearToolbarContent(toolbar)
                            }
                            override fun onChildViewRemoved(parent: View?, child: View?) {}
                        })

                        toolbar.viewTreeObserver.addOnPreDrawListener {
                            val logo = toolbar.findViewById<View>(Utils.getID("toolbar_logo", "id"))
                            if (logo != null && logo.visibility != View.GONE) {
                                logo.visibility = View.GONE
                            }
                            for (i in 0 until toolbar.childCount) {
                                val c = toolbar.getChildAt(i)
                                if (c is TextView && c.visibility != View.GONE) {
                                    c.visibility = View.GONE
                                }
                            }
                            true
                        }

                        val isNight = DesignUtils.isNightMode()
                        val blurEnabled = prefs.getBoolean("ios_header_blur", true)

                        header.setBackgroundColor(Color.TRANSPARENT)
                        toolbar.setBackgroundColor(Color.TRANSPARENT)
                        header.elevation = 0f
                        header.bringToFront()

                        // Tambahkan BlurView/Glassmorphism backdrop di header jika diizinkan
                        if (blurEnabled) {
                            try {
                                val radiusDp = 24f
                                val blurView = eightbitlab.com.blurview.BlurView(com.wmods.wppenhacer.xposed.utils.ModuleContextWrapper(activity)).apply {
                                    val blurRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: activity.window.decorView as ViewGroup
                                    setupWith(blurRoot)
                                        .setFrameClearDrawable(null)
                                        .setBlurRadius(2.5f)
                                        .setOverlayColor(if (isNight) Color.argb(35, 18, 18, 18) else Color.argb(35, 255, 255, 255))
                                        .setBlurAutoUpdate(true)
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    com.wmods.wppenhacer.utils.AgslHelper.applyAgsl(blurView, radiusDp, 2.0f, 0.6f, 1.05f, 0.30f)
                                }

                                val blurParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                header.addView(blurView, 0, blurParams)
                            } catch (e: Throwable) {
                                logDebug("IosHeader BlurView inject error: ${e.message}")
                                val defaultBg = if (isNight) Color.parseColor("#0B141B") else Color.WHITE
                                val surfaceColor = DesignUtils.getPrimarySurfaceColor()
                                val headerBgColor = if (surfaceColor != -15132398 && surfaceColor != -2 && surfaceColor != 0) surfaceColor else defaultBg
                                header.setBackgroundColor(headerBgColor)
                            }
                        } else {
                            val defaultBg = if (isNight) Color.parseColor("#0B141B") else Color.WHITE
                            val surfaceColor = DesignUtils.getPrimarySurfaceColor()
                            val headerBgColor = if (surfaceColor != -15132398 && surfaceColor != -2 && surfaceColor != 0) surfaceColor else defaultBg
                            header.setBackgroundColor(headerBgColor)
                        }

                        // Navigation icon: titik-tiga bulat khas iOS
                        try {
                            val menuDrawable = IosMenuDrawable(activity, isNight)
                            val setNavIcon = com.wmods.wppenhacer.xposed.utils.ReflectionUtils.findMethodUsingFilter(toolbar.javaClass) { m ->
                                m.name == "setNavigationIcon" && m.parameterCount == 1 && Drawable::class.java.isAssignableFrom(m.parameterTypes[0])
                            }
                            if (setNavIcon != null) {
                                setNavIcon.isAccessible = true
                                setNavIcon.invoke(toolbar, menuDrawable)
                            } else {
                                XposedHelpers.callMethod(toolbar, "setNavigationIcon", menuDrawable)
                            }

                            // Tandai navigation button secara eksplisit dengan tag agar tidak ter-hide oleh pembersih toolbar
                            for (i in 0 until toolbar.childCount) {
                                val c = toolbar.getChildAt(i)
                                if (c is ImageButton && (c.drawable === menuDrawable || c.drawable is IosMenuDrawable)) {
                                    c.tag = "ios_nav_icon"
                                    c.visibility = View.VISIBLE
                                }
                            }

                            val setNavOnClick = com.wmods.wppenhacer.xposed.utils.ReflectionUtils.findMethodUsingFilter(toolbar.javaClass) { m ->
                                m.name == "setNavigationOnClickListener" && m.parameterCount == 1 && View.OnClickListener::class.java.isAssignableFrom(m.parameterTypes[0])
                            }
                            val clickListener = View.OnClickListener { activity.openOptionsMenu() }
                            if (setNavOnClick != null) {
                                setNavOnClick.isAccessible = true
                                setNavOnClick.invoke(toolbar, clickListener)
                            } else {
                                XposedHelpers.callMethod(toolbar, "setNavigationOnClickListener", clickListener)
                            }
                        } catch (e: Throwable) {
                            logDebug("IosHeader: setNavigationIcon failed: ${e.message}")
                        }

                        // Suntikkan Action Buttons (DND, Ghost, Freeze, Restart, WAE) ke toolbar
                        try {
                            injectActionButtons(activity, toolbar)
                        } catch (e: Exception) {
                            logDebug("IosHeader: injectActionButtons failed: ${e.message}")
                        }

                        // Buat Large Title
                        val largeTitle = TextView(activity).apply {
                            text = initialTitle
                            textSize = 34f
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            letterSpacing = -0.01f
                            setTextColor(DesignUtils.getPrimaryTextColor())
                            setBackgroundColor(Color.TRANSPARENT)
                        }

                        val padding = (16 * activity.resources.displayMetrics.density).toInt()
                        val bottomPadding = (6 * activity.resources.displayMetrics.density).toInt()
                        largeTitle.setPadding(padding, 0, padding, bottomPadding)

                        val toolbarIndex = header.indexOfChild(toolbar)
                        val newParams: ViewGroup.LayoutParams = if (toolbarIndex != -1) {
                            val toolbarParams = toolbar.layoutParams
                            val layoutParamsClass = toolbarParams.javaClass
                            try {
                                val p = layoutParamsClass.getConstructor(Int::class.java, Int::class.java)
                                    .newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) as ViewGroup.LayoutParams
                                val getScrollFlags = layoutParamsClass.getMethod("getScrollFlags")
                                val flags = getScrollFlags.invoke(toolbarParams) as Int
                                val setScrollFlags = layoutParamsClass.getMethod("setScrollFlags", Int::class.java)
                                setScrollFlags.invoke(p, flags)
                                p
                            } catch (e: Exception) {
                                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            }
                        } else {
                            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }

                        if (newParams is ViewGroup.MarginLayoutParams) {
                            newParams.topMargin = 0
                            newParams.bottomMargin = 0
                        }

                        largeTitle.translationY = 0f

                        if (toolbarIndex != -1) {
                            header.addView(largeTitle, toolbarIndex + 1, newParams)
                        } else {
                            header.addView(largeTitle, newParams)
                        }

                        header.setTag(TAG_LARGE_TITLE_VIEW, largeTitle)
                        // Post dua kali: pertama setelah view di-attach, kedua setelah layout selesai
                        // sehingga tinggi Large Title sudah terukur dengan benar
                        header.post {
                            setContainerMargin(header, initialTitle == "Chats")
                            largeTitle.post {
                                setContainerMargin(header, getLargeTitleView(header)?.text?.toString() == "Chats")
                            }

                            // AGGRESSIVE: Sembunyikan sisa "WhatsApp" text/logo yang mungkin ada di header (di luar toolbar)
                            val cleanHeaderAction = {
                                try {
                                    for (i in 0 until header.childCount) {
                                        val child = header.getChildAt(i)
                                        // Lewati toolbar, largeTitle, dan tab layout
                                        if (child == toolbar || child == largeTitle) continue
                                        if (child.javaClass.name.contains("Tab", ignoreCase = true)) continue
                                        if (child.javaClass.name.contains("Pager", ignoreCase = true)) continue

                                        // Jika itu TextView dan isinya WhatsApp, hide
                                        if (child is TextView) {
                                            if (child.text.toString().contains("WhatsApp", ignoreCase = true)) {
                                                child.visibility = View.GONE
                                            }
                                        } else if (child is ImageView) {
                                            val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                                            if (desc.contains("whatsapp", ignoreCase = true) || child.id == Utils.getID("toolbar_logo", "id")) {
                                                child.visibility = View.GONE
                                            }
                                        } else if (child is ViewGroup) {
                                            hideWhatsAppTextInViewGroup(child)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            cleanHeaderAction()
                            header.viewTreeObserver.addOnPreDrawListener {
                                cleanHeaderAction()
                                true
                            }
                        }

                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: ERROR saat inject header: ${e.message}")
                    }
                }
            }
        )

        // 3. Sembunyikan icon native, KECUALI Kamera dan Pencarian
        XposedHelpers.findAndHookMethod(
            WppCore.homeActivityClass,
            "onCreateOptionsMenu",
            android.view.Menu::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    try {
                        val toolbarId = Utils.getID("toolbar", "id")
                        if (toolbarId == 0) return
                        val toolbar = activity.findViewById<ViewGroup>(toolbarId) ?: return

                        toolbar.post {
                            try {
                                val fabId = activity.resources.getIdentifier("fab", "id", "com.whatsapp")
                                val overflowId = activity.resources.getIdentifier("menuitem_overflow", "id", "com.whatsapp")
                                val searchId = activity.resources.getIdentifier("menuitem_search", "id", "com.whatsapp")
                                val cameraId = activity.resources.getIdentifier("menuitem_camera", "id", "com.whatsapp")
                                val callId = activity.resources.getIdentifier("menuitem_call", "id", "com.whatsapp")
                                val phoneId = activity.resources.getIdentifier("menuitem_phone", "id", "com.whatsapp")
                                val headerId = Utils.getID("header", "id")

                                for (i in 0 until toolbar.childCount) {
                                    val child = toolbar.getChildAt(i)
                                    if (child.javaClass.name.contains("ActionMenuView")) {
                                        val amv = child as ViewGroup
                                        
                                        val TAG_PREDRAW_ADDED = 0x7E110004
                                        if (amv.getTag(TAG_PREDRAW_ADDED) == true) continue
                                        amv.setTag(TAG_PREDRAW_ADDED, true)
                                        
                                        amv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                                            private var cachedHdr: ViewGroup? = null
                                            private var cachedFab: View? = null
                                            private var previousTitle = ""
                                            private var lastIsChatsTab: Boolean? = null

                                            override fun onPreDraw(): Boolean {
                                                try {
                                                    if (cachedHdr == null && headerId != 0) cachedHdr = activity.findViewById<ViewGroup>(headerId)
                                                    val hdr = cachedHdr
                                                    val largeTitle = if (hdr != null) getLargeTitleView(hdr) else null
                                                    val currentTitle = largeTitle?.text?.toString() ?: ""
                                                    
                                                    if (currentTitle != previousTitle) {
                                                        if (previousTitle.isNotEmpty()) {
                                                            try {
                                                                com.wmods.wppenhacer.xposed.features.customization.IosSwipeMenu.closeSwipeMenu()
                                                            } catch (_: Exception) {}
                                                        }
                                                        previousTitle = currentTitle
                                                    }
                                                    
                                                    val isSettingsTab = isSettingsTabTitle(currentTitle)
                                                    val showPlusButton = !isSettingsTab
                                                    val tabChanged = lastIsChatsTab != showPlusButton
                                                    lastIsChatsTab = showPlusButton
                                                    
                                                    if (tabChanged && hdr != null) {
                                                        setContainerMargin(hdr, showPlusButton)
                                                    }
                                                    
                                                    try {
                                                        val fakePlus = toolbar.findViewWithTag<ImageView>("fake_plus_btn")
                                                        if (fakePlus != null) {
                                                            val targetVis = if (showPlusButton) View.VISIBLE else View.GONE
                                                            if (fakePlus.visibility != targetVis) {
                                                                fakePlus.visibility = targetVis
                                                            }
                                                        }
                                                    } catch (_: Exception) {}
                                                    
                                                    if (fabId != 0) {
                                                        if (cachedFab == null) cachedFab = activity.findViewById<View>(fabId)
                                                        val fab = cachedFab
                                                        if (fab != null) {
                                                            if (fab.scaleX != 0f) fab.scaleX = 0f
                                                            if (fab.scaleY != 0f) fab.scaleY = 0f
                                                            if (fab.alpha != 0f) fab.alpha = 0f
                                                            
                                                            val parent = fab.parent as? ViewGroup
                                                            if (parent != null) {
                                                                val density = activity.resources.displayMetrics.density
                                                                for (k in 0 until parent.childCount) {
                                                                    val c = parent.getChildAt(k)
                                                                    if (c == fab) continue
                                                                    if (c.width > 0 && c.height > 0 && c.height < 100 * density && c.x > parent.width / 2f) {
                                                                        if (showPlusButton) {
                                                                            if (fab.top > 0 && fab.height > 0) {
                                                                                val fabVisualCenterY = fab.top + fab.translationY + (fab.height / 2f)
                                                                                val targetY = fabVisualCenterY - (c.height / 2f)
                                                                                val neededTranslation = targetY - c.top
                                                                                if (Math.abs(c.translationY - neededTranslation) > 1f) {
                                                                                    c.translationY = neededTranslation
                                                                                }
                                                                            } else {
                                                                                if (c.translationY != 0f) {
                                                                                    c.translationY = 0f
                                                                                }
                                                                            }
                                                                        } else {
                                                                            if (c.translationY != 0f) {
                                                                                c.translationY = 0f
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    
                                                    val currentCount = amv.childCount
                                                    for (j in 0 until currentCount) {
                                                        val btn = amv.getChildAt(j)
                                                        var type = btn.getTag(TAG_CONFIGURED_TYPE) as? Int
                                                        if (type == null) {
                                                            var isOverflow = false
                                                            var isSearch = false
                                                            var isCamera = false
                                                            var isPhone = false
                                                            val btnId = btn.id
                                                            var isCustomAction = (btnId == com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_DND ||
                                                                                  btnId == com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_GHOST ||
                                                                                  btnId == com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_FREEZE ||
                                                                                  btnId == com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_RESTART ||
                                                                                  btnId == com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_OPEN_WAE)

                                                            if (overflowId != 0 && btn.id == overflowId) isOverflow = true
                                                            if (searchId != 0 && btn.id == searchId) isSearch = true
                                                            if (cameraId != 0 && btn.id == cameraId) isCamera = true
                                                            if (callId != 0 && btn.id == callId) isPhone = true
                                                            if (phoneId != 0 && btn.id == phoneId) isPhone = true
                                                            val desc = btn.contentDescription?.toString() ?: ""
                                                            if (desc.isNotEmpty()) {
                                                                if (desc.contains("opsi", ignoreCase = true) || desc.contains("more", ignoreCase = true) || desc.contains("lainnya", ignoreCase = true)) isOverflow = true
                                                                if (desc.contains("cari", ignoreCase = true) || desc.contains("search", ignoreCase = true)) isSearch = true
                                                                if (desc.contains("kamera", ignoreCase = true) || desc.contains("camera", ignoreCase = true)) isCamera = true
                                                                if (desc.contains("panggilan", ignoreCase = true) || desc.contains("call", ignoreCase = true) ||
                                                                    desc.contains("telepon", ignoreCase = true) || desc.contains("phone", ignoreCase = true)) isPhone = true
                                                                if (desc.contains("dnd", ignoreCase = true) || desc.contains("pesawat", ignoreCase = true) || desc.contains("ganggu", ignoreCase = true) ||
                                                                    desc.contains("ghost", ignoreCase = true) || desc.contains("hantu", ignoreCase = true) ||
                                                                    desc.contains("bekukan", ignoreCase = true) || desc.contains("freeze", ignoreCase = true) || desc.contains("terakhir", ignoreCase = true) ||
                                                                    desc.contains("restart", ignoreCase = true) || desc.contains("ulang", ignoreCase = true) ||
                                                                    desc.contains("enhancer", ignoreCase = true) || desc.contains("wae", ignoreCase = true)) {
                                                                    isCustomAction = true
                                                                }
                                                            }

                                                            if (!isOverflow && btn.javaClass.name.contains("OverflowMenuButton")) isOverflow = true

                                                            type = if (isOverflow) 1 else if (isCustomAction || isSearch || isCamera || isPhone) 2 else 3
                                                            btn.setTag(TAG_CONFIGURED_TYPE, type)
                                                        }

                                                        if (type == 1 && btn is ImageView) {
                                                            btn.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                                                            if (btn.alpha != 0f) btn.alpha = 0f
                                                            val targetTransX = - (activity.resources.displayMetrics.widthPixels).toFloat() + (150 * activity.resources.displayMetrics.density)
                                                            if (btn.translationX != targetTransX) btn.translationX = targetTransX
                                                            btn.setOnTouchListener(null)
                                                            
                                                            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
                                                            val params = btn.layoutParams
                                                            if (params != null && (params.width == 0 || params.height == 0)) {
                                                                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                                                                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                                                btn.layoutParams = params
                                                            }
                                                        } else if (type == 2) {
                                                            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
                                                            if (btn.alpha != 1f) btn.alpha = 1f
                                                            val density = activity.resources.displayMetrics.density
                                                            val pad = (4 * density).toInt()
                                                            if (btn.paddingLeft != pad || btn.paddingRight != pad) {
                                                                btn.setPadding(pad, btn.paddingTop, pad, btn.paddingBottom)
                                                            }
                                                            val params = btn.layoutParams
                                                            if (params != null && (params.width == 0 || params.height == 0)) {
                                                                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                                                                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                                                btn.layoutParams = params
                                                            }
                                                        } else {
                                                            if (btn.visibility != View.GONE) btn.visibility = View.GONE
                                                            val params = btn.layoutParams
                                                            if (params != null && (params.width != 0 || params.height != 0)) {
                                                                params.width = 0
                                                                params.height = 0
                                                                btn.layoutParams = params
                                                            }
                                                        }
                                                    }
                                                    
                                                    val oldBtnId = 0x7F0A0999
                                                    val oldBtn = amv.findViewById<View>(oldBtnId)
                                                    if (oldBtn != null && oldBtn.visibility != View.GONE) {
                                                        oldBtn.visibility = View.GONE
                                                        val p = oldBtn.layoutParams
                                                        if (p != null) {
                                                            p.width = 0
                                                            p.height = 0
                                                            oldBtn.layoutParams = p
                                                        }
                                                    }
                                                } catch (e: Exception) {}
                                                return true
                                            }
                                        })
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    } catch (e: Exception) {}
                }
            }
        )
    }

    /**
     * Validasi judul toolbar per-tab.
     *
     * Sebelumnya SEMUA title yang kosong atau kebetulan mengandung kata "WhatsApp"
     * langsung dipaksa jadi "Chats". Akibatnya tab lain (Status, Communities,
     * Settings) yang title toolbar-nya kosong ikut dianggap sebagai tab Chats —
     * FAB & tombol "+" pun ikut muncul dan diposisikan seolah-olah lagi di tab
     * Chats, padahal kontennya beda. Sekarang title dicocokkan dulu ke daftar
     * label tab yang dikenal (ID & EN), baru fallback ke "Chats" kalau memang
     * tidak ada judul valid sama sekali.
     */
    private fun isSettingsTabTitle(rawTitle: String): Boolean {
        val lower = rawTitle.lowercase().trim()
        val youTitle = try { Utils.getYouTabString(Utils.application).lowercase().trim() } catch (_: Throwable) { "anda" }
        return lower == "settings" || lower == "setelan" || lower == "pengaturan" ||
               lower == "anda" || lower == "you" || lower == "profil" || lower == "profile" ||
               lower == youTitle
    }

    private fun resolveTabTitle(rawTitle: String): String {
        val youTitle = try { Utils.getYouTabString(Utils.application) } catch (_: Throwable) { "Anda" }
        val knownTabs = mapOf(
            "chats" to "Chats", "obrolan" to "Chats", "chat" to "Chats",
            "status" to "Status", "updates" to "Status", "pembaruan" to "Status",
            "communities" to "Communities", "komunitas" to "Communities",
            "calls" to "Calls", "panggilan" to "Calls",
            "settings" to youTitle, "setelan" to youTitle, "pengaturan" to youTitle,
            "anda" to youTitle, "you" to youTitle, "profil" to youTitle, "profile" to youTitle
        )

        knownTabs[rawTitle.lowercase()]?.let { return it }

        return when {
            rawTitle.isEmpty() -> "Chats" // fallback terakhir, biasanya tab default (Chats)
            rawTitle.contains("WhatsApp", ignoreCase = true) -> "Chats" // nama aplikasi, bukan nama tab
            else -> rawTitle // title lain yang valid (mis. label custom), biarkan apa adanya
        }
    }

    private fun hideWhatsAppTextInViewGroup(vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is TextView) {
                if (child.text.toString().contains("WhatsApp", ignoreCase = true)) {
                    child.visibility = View.GONE
                }
            } else if (child is ViewGroup) {
                hideWhatsAppTextInViewGroup(child)
            }
        }
    }

    private fun setContainerMargin(header: ViewGroup, isChats: Boolean) {
        try {
            val parent = header.parent as? ViewGroup ?: return
            val density = header.resources.displayMetrics.density
            val largeTitle = getLargeTitleView(header)
            val titleH = if (largeTitle == null || largeTitle.visibility == View.GONE) 0 else (largeTitle.height.takeIf { it > 0 } ?: (48 * density).toInt())
            val toolbarView = header.findViewById<View>(Utils.getID("toolbar", "id"))
            val toolbarH = toolbarView?.height?.takeIf { it > 0 } ?: (56 * density).toInt()
            val headerHeight = if (header.height > 0) header.height else (header.measuredHeight.takeIf { it > 0 } ?: (toolbarH + titleH))
            val headerBottomInParent = header.top + headerHeight

            // Gap final yang diinginkan antara batas bawah teks "Chats" dan search bar (bisa disesuaikan)
            val desiredGapDp = 4f
            val desiredGapPx = (desiredGapDp * density).toInt()

            // 1. Shift pager_holder (parent tunggal untuk semua tab termasuk Chats, Calls, Updates, Communities, Anda)
            val pagerHolderId = Utils.getID("pager_holder", "id")
            if (pagerHolderId != 0) {
                val ph = parent.findViewById<ViewGroup>(pagerHolderId)
                if (ph != null) {
                    val shiftY = titleH.toFloat()
                    if (ph.translationY != shiftY) {
                        ph.translationY = shiftY
                    }
                }
            }
            // Reset conversation_container agar TIDAK double-shift karena dia adalah anak di dalam pager_holder
            val ccId = Utils.getID("conversation_container", "id")
            if (ccId != 0) {
                val cc = parent.findViewById<ViewGroup>(ccId)
                if (cc != null && cc.translationY != 0f) {
                    cc.translationY = 0f
                }
            }

            // 2. SELALU jalankan juga logika posisi search bar & child lain, TIDAK di-return lebih awal
            val searchBarId = Utils.getID("my_search_bar", "id")
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child === header) continue
                if (child.id == pagerHolderId || child.id == ccId) continue

                val pParams = child.layoutParams
                if (pParams is ViewGroup.MarginLayoutParams) {
                    val target = if (searchBarId != 0 && child.id == searchBarId) {
                        // Ukur langsung posisi bawah largeTitle di koordinat layar,
                        // lalu konversi ke koordinat parent, supaya presisi tanpa tebak-tebak
                        val titleLoc = IntArray(2)
                        val parentLoc = IntArray(2)
                        var t: Int
                        if (largeTitle != null && largeTitle.height > 0) {
                            largeTitle.getLocationOnScreen(titleLoc)
                            parent.getLocationOnScreen(parentLoc)
                            val titleBottomInParent = (titleLoc[1] - parentLoc[1]) + largeTitle.height
                            val searchBarInternalTopPad = child.paddingTop
                            t = titleBottomInParent + desiredGapPx - searchBarInternalTopPad
                            logDebug("IosHeader: PRECISE searchBar target=$t (titleBottomInParent=$titleBottomInParent, gap=$desiredGapPx, internalPad=$searchBarInternalTopPad)")
                        } else {
                            t = headerBottomInParent - (16 * density).toInt()
                        }
                        t
                    } else {
                        // Child lain: hitung overlap seperti sebelumnya
                        val originalMargin = (child.getTag(TAG_ORIGINAL_MARGIN) as? Int) ?: run {
                            child.setTag(TAG_ORIGINAL_MARGIN, pParams.topMargin)
                            pParams.topMargin
                        }

                        val childTopInParent = child.top - pParams.topMargin
                        if (headerBottomInParent > 0 && childTopInParent < headerBottomInParent) {
                            val neededMargin = headerBottomInParent - childTopInParent - (16 * density).toInt()
                            originalMargin + neededMargin
                        } else if (headerBottomInParent <= 0) {
                            val fallback = (52 * density).toInt()
                            originalMargin + fallback
                        } else {
                            originalMargin
                        }
                    }

                    if (pParams.topMargin != target) {
                        pParams.topMargin = target
                        child.layoutParams = pParams
                    }
                }
            }
        } catch (e: Throwable) {}
    }


    private fun clearToolbarContent(toolbar: ViewGroup) {
        try {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                val className = child.javaClass.name
                
                // 1. Biarkan menu ikon di kanan (kamera, tombol +, dll)
                if (className.contains("ActionMenuView")) continue
                
                // 1b. Biarkan Action Buttons Container (DND, Ghost, Freeze, Restart, WAE)
                if (child.id == TAG_ACTION_BUTTONS_CONTAINER || child.tag == TAG_ACTION_BUTTONS_CONTAINER) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }

                // 1c. Biarkan fake_plus_btn (tombol + buatan kita)
                if (child.tag == "fake_plus_btn" || (child is ImageView && child.drawable is IosPlusDrawable)) {
                    continue
                }
                
                // 2. Biarkan tombol navigasi di kiri (ikon titik-tiga buatan kita) dan tombol +
                if (child is android.widget.ImageButton || child.tag == "ios_nav_icon") {
                    val d = (child as? android.widget.ImageButton)?.drawable
                    if (d is IosMenuDrawable || d is IosPlusDrawable || child.tag == "ios_nav_icon") {
                        if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                        continue
                    }
                }
                
                // 3. Skip SearchView dengan SEMUA varian
                if (className.contains("SearchView") ||
                    className.contains("SearchAutoComplete") ||
                    child is android.widget.EditText) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }
                
                // 4. Jangan sembunyikan Search, Kamera, Meta AI, dan Phone/Call icon
                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                val searchId = Utils.getID("menuitem_search", "id")
                val cameraId = Utils.getID("menuitem_camera", "id")
                
                if (desc.contains("cari") || desc.contains("search") || (searchId != 0 && child.id == searchId) ||
                    desc.contains("kamera") || desc.contains("camera") || (cameraId != 0 && child.id == cameraId) ||
                    desc.contains("panggilan") || desc.contains("call") ||
                    desc.contains("telepon") || desc.contains("phone") ||
                    className.contains("MetaAi", ignoreCase = true) || desc.contains("meta") || desc.contains("ai")) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }

                // 5. Jangan sembunyikan ViewGroup (bisa berisi search/phone/meta widget)
                if (child is ViewGroup && child !is LinearLayout) {
                    // Cek apakah di dalamnya ada SearchView atau Meta AI
                    val hasSearch = hasSearchViewChild(child)
                    if (hasSearch) {
                        if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                        continue
                    }
                }
                
                // 6. Sembunyikan sisanya (Teks judul asli, Subjudul, atau Logo gambar)
                if (child is TextView || (child is View && !className.contains("ActionMenuView"))) {
                    if (child.visibility != View.GONE) {
                        child.visibility = View.GONE
                    }
                }
            }
        } catch (e: Throwable) {}
    }

    private fun hasSearchViewChild(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            val cn = c.javaClass.name
            val desc = c.contentDescription?.toString()?.lowercase() ?: ""
            if (cn.contains("SearchView") || cn.contains("SearchAutoComplete") || c is android.widget.EditText ||
                cn.contains("MetaAi", ignoreCase = true) || desc.contains("meta") || desc.contains("ai")) {
                return true
            }
            if (c is ViewGroup && hasSearchViewChild(c)) return true
        }
        return false
    }

    private fun clickNewCommunityItem(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            val desc = child.contentDescription?.toString()?.lowercase() ?: ""
            val text = (child as? TextView)?.text?.toString()?.lowercase() ?: ""
            val idName = try { child.resources.getResourceEntryName(child.id).lowercase() } catch (_: Throwable) { "" }

            val hasCommunityKeyword = idName.contains("community") || idName.contains("komunitas") ||
                    desc.contains("community") || desc.contains("komunitas") ||
                    text.contains("community") || text.contains("komunitas")

            val hasActionKeyword = idName.contains("create") || idName.contains("new") || idName.contains("add") ||
                    desc.contains("baru") || desc.contains("new") || desc.contains("create") || desc.contains("buat") || desc.contains("add") ||
                    text.contains("baru") || text.contains("new") || text.contains("create") || text.contains("buat") || text.contains("add")

            val isExplicitNewCommunity = hasCommunityKeyword && hasActionKeyword

            if (isExplicitNewCommunity) {
                var target: View? = child
                var depth = 0
                while (target != null && depth < 5) {
                    if (target.isClickable || target.hasOnClickListeners()) {
                        try {
                            if (target.performClick()) return true
                        } catch (_: Throwable) {}
                    }
                    target = target.parent as? View
                    depth++
                }
                try {
                    if (child.performClick()) return true
                } catch (_: Throwable) {}
            }

            if (child is ViewGroup && clickNewCommunityItem(child)) {
                return true
            }
        }
        return false
    }

    private fun clickFirstItemOfRecyclerView(vg: ViewGroup): Boolean {
        val className = vg.javaClass.name
        if (className.contains("RecyclerView") || className.contains("ListView") || className.contains("AbsListView")) {
            if (vg.childCount > 0) {
                val firstItem = vg.getChildAt(0)
                if (firstItem != null) {
                    var target: View? = firstItem
                    var depth = 0
                    while (target != null && depth < 5) {
                        if (target.isClickable || target.hasOnClickListeners()) {
                            try {
                                if (target.performClick()) return true
                            } catch (_: Throwable) {}
                        }
                        target = target.parent as? View
                        depth++
                    }
                    try {
                        if (firstItem.performClick()) return true
                    } catch (_: Throwable) {}
                }
            }
        }
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is ViewGroup && clickFirstItemOfRecyclerView(child)) {
                return true
            }
        }
        return false
    }

    private fun performFallbackClickOnActiveTab(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            val desc = child.contentDescription?.toString()?.lowercase() ?: ""
            val text = (child as? TextView)?.text?.toString()?.lowercase() ?: ""
            val idName = try { child.resources.getResourceEntryName(child.id).lowercase() } catch (_: Throwable) { "" }

            val isCallMatch = (desc.contains("panggilan") || text.contains("panggilan") || idName.contains("call")) &&
                    (desc.contains("baru") || text.contains("baru") || desc.contains("new") || text.contains("new") || idName.contains("new") || idName.contains("create"))

            if (isCallMatch) {
                var curr: View? = child
                var depth = 0
                while (curr != null && depth < 5) {
                    if (curr.isClickable || curr.hasOnClickListeners()) {
                        try {
                            if (curr.performClick()) return true
                        } catch (_: Throwable) {}
                    }
                    curr = curr.parent as? View
                    depth++
                }
            }

            if (child is ViewGroup && performFallbackClickOnActiveTab(child)) {
                return true
            }
        }
        return false
    }


    private class IosPlusDrawable(private val sizePx: Int) : android.graphics.drawable.Drawable() {
        private val paintCircle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#34C759")
            style = android.graphics.Paint.Style.FILL
        }
        private val paintPlus = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
        override fun draw(canvas: android.graphics.Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = (bounds.width().coerceAtMost(bounds.height()) / 2f) * 0.8f
            canvas.drawCircle(cx, cy, radius, paintCircle)
            val lineLen = radius * 0.45f
            canvas.drawLine(cx, cy - lineLen, cx, cy + lineLen, paintPlus)
            canvas.drawLine(cx - lineLen, cy, cx + lineLen, cy, paintPlus)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun injectActionButtons(activity: Activity, toolbar: ViewGroup) {
        val existing = toolbar.findViewById<View>(TAG_ACTION_BUTTONS_CONTAINER)
        if (existing != null) {
            refreshActionButtons(activity, existing as ViewGroup)
            return
        }

        val density = activity.resources.displayMetrics.density
        val btnSize = (36 * density).toInt()
        val btnPadding = (6 * density).toInt()

        val lp = try {
            val defaultLp = XposedHelpers.callMethod(toolbar, "generateDefaultLayoutParams") as ViewGroup.LayoutParams
            XposedHelpers.setIntField(defaultLp, "gravity", Gravity.END or Gravity.CENTER_VERTICAL)
            defaultLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            defaultLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            (defaultLp as? ViewGroup.MarginLayoutParams)?.marginEnd = (4 * density).toInt()
            defaultLp
        } catch (_: Throwable) {
            try {
                val lpClass = toolbar.javaClass.classLoader?.loadClass("androidx.appcompat.widget.Toolbar\$LayoutParams")
                    ?: toolbar.javaClass.classLoader?.loadClass("android.widget.Toolbar\$LayoutParams")
                val constructor = lpClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                val newLp = constructor?.newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL) as? ViewGroup.LayoutParams
                (newLp as? ViewGroup.MarginLayoutParams)?.marginEnd = (4 * density).toInt()
                newLp ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } catch (_: Throwable) {
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        val fakePlusBtn = ImageView(activity).apply {
            tag = "fake_plus_btn"
            background = null
            scaleType = ImageView.ScaleType.CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            val plusLp = try {
                val pClass = toolbar.javaClass.classLoader?.loadClass("androidx.appcompat.widget.Toolbar\$LayoutParams")
                    ?: toolbar.javaClass.classLoader?.loadClass("android.widget.Toolbar\$LayoutParams")
                val constructor = pClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                val newLp = constructor?.newInstance(btnSize, btnSize, Gravity.END or Gravity.CENTER_VERTICAL) as? ViewGroup.LayoutParams
                (newLp as? ViewGroup.MarginLayoutParams)?.marginEnd = (4 * density).toInt()
                newLp ?: ViewGroup.MarginLayoutParams(btnSize, btnSize)
            } catch (_: Throwable) {
                ViewGroup.MarginLayoutParams(btnSize, btnSize)
            }
            layoutParams = plusLp
            
            val size = (32 * density).toInt()
            setImageDrawable(IosPlusDrawable(size))
            setOnClickListener {
                try {
                    val currentTitle = try {
                        val hdrId = Utils.getID("header", "id")
                        val hdr = if (hdrId != 0) activity.findViewById<ViewGroup>(hdrId) else null
                        val largeTitle = if (hdr != null) getLargeTitleView(hdr) else null
                        largeTitle?.text?.toString() ?: ""
                    } catch (_: Throwable) { "" }

                    val tabType = resolveTabTitle(currentTitle)
                    var clicked = false

                    if (tabType == "Communities") {
                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            clicked = clickNewCommunityItem(decor)
                            if (!clicked) {
                                clicked = clickFirstItemOfRecyclerView(decor)
                            }
                        }

                        if (!clicked) {
                            val possibleClasses = arrayOf(
                                "com.whatsapp.community.NewCommunityActivity",
                                "com.whatsapp.community.CreateCommunityActivity",
                                "com.whatsapp.community.CommunityNavigationActivity",
                                "com.whatsapp.community.home.CommunityHomeActivity",
                                "com.whatsapp.community.AddMembersWithLinksActivity"
                            )
                            for (clsName in possibleClasses) {
                                try {
                                    val cls = activity.classLoader.loadClass(clsName)
                                    val intent = android.content.Intent(activity, cls)
                                    activity.startActivity(intent)
                                    clicked = true
                                    break
                                } catch (_: Throwable) {}
                            }
                        }
                    }

                    if (!clicked) {
                        val fabId = activity.resources.getIdentifier("fab", "id", activity.packageName)
                        if (fabId != 0) {
                            val fabView = activity.findViewById<View>(fabId)
                            if (fabView != null) {
                                clicked = try { fabView.performClick() } catch (_: Throwable) { false }
                            }
                        }
                    }

                    if (!clicked) {
                        val fabNames = arrayOf(
                            "call_btn", "new_chat_btn", "status_btn", "camera_fab",
                            "community_fab", "create_community_button", "create_community",
                            "text_status_fab", "fab_second", "fab_auxiliary", "extended_mini_fab"
                        )
                        for (name in fabNames) {
                            val resId = activity.resources.getIdentifier(name, "id", activity.packageName)
                            if (resId != 0) {
                                val v = activity.findViewById<View>(resId)
                                if (v != null) {
                                    clicked = try { v.performClick() } catch (_: Throwable) { false }
                                    if (clicked) break
                                }
                            }
                        }
                    }

                    if (!clicked) {
                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            performFallbackClickOnActiveTab(decor)
                        }
                    }
                } catch (e: Exception) {
                    logDebug("IosHeader: fake_plus_btn onClick error: ${e.message}")
                }
            }
            visibility = View.VISIBLE
        }
        toolbar.addView(fakePlusBtn)

        val container = LinearLayout(activity).apply {
            id = TAG_ACTION_BUTTONS_CONTAINER
            tag = TAG_ACTION_BUTTONS_CONTAINER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = lp
        }

        // 1. DND Mode
        val dndBtn = ImageButton(activity).apply {
            id = com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_DND
            contentDescription = "DND Mode Pesawat"
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener {
                val dndmode = WppCore.getPrivBoolean("dndmode", false)
                if (!dndmode) {
                    AlertDialogWpp(activity)
                        .setTitle(activity.getString(R.string.dnd_mode_title))
                        .setMessage(activity.getString(R.string.dnd_message))
                        .setPositiveButton(activity.getString(R.string.activate)) { _, _ ->
                            WppCore.setPrivBoolean("dndmode", true)
                            Utils.doRestart(activity)
                        }
                        .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ -> dialog?.dismiss() }
                        .create().show()
                } else {
                    WppCore.setPrivBoolean("dndmode", false)
                    Utils.doRestart(activity)
                }
            }
        }
        container.addView(dndBtn)

        // 2. Ghost Mode
        val ghostBtn = ImageButton(activity).apply {
            id = com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_GHOST
            contentDescription = "Ghost Mode"
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener {
                val ghostmode = WppCore.getPrivBoolean("ghostmode", false)
                if (!ghostmode) {
                    AlertDialogWpp(activity).setTitle(
                        activity.getString(
                            R.string.ghost_mode_s,
                            "OFF"
                        )
                    ).setMessage(activity.getString(R.string.ghost_mode_message))
                        .setPositiveButton(activity.getString(R.string.activate)) { _, _ ->
                            WppCore.setPrivBoolean("ghostmode", true)
                            Utils.doRestart(activity)
                        }
                        .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ -> dialog?.dismiss() }
                        .show()
                } else {
                    WppCore.setPrivBoolean("ghostmode", false)
                    Utils.doRestart(activity)
                }
            }
        }
        container.addView(ghostBtn)

        // 3. Freeze Last Seen
        val freezeBtn = ImageButton(activity).apply {
            id = com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_FREEZE
            contentDescription = "Freeze Last Seen"
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener {
                val freezelastseen = WppCore.getPrivBoolean("freezelastseen", false)
                if (!freezelastseen) {
                    AlertDialogWpp(activity)
                        .setTitle(activity.getString(R.string.freezelastseen_title))
                        .setMessage(activity.getString(R.string.freezelastseen_message))
                        .setPositiveButton(activity.getString(R.string.activate)) { _, _ ->
                            WppCore.setPrivBoolean("freezelastseen", true)
                            Utils.doRestart(activity)
                        }
                        .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ -> dialog?.dismiss() }
                        .create().show()
                } else {
                    WppCore.setPrivBoolean("freezelastseen", false)
                    Utils.doRestart(activity)
                }
            }
        }
        container.addView(freezeBtn)

        // 4. Restart Button
        val restartBtn = ImageButton(activity).apply {
            id = com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_RESTART
            contentDescription = "Restart WhatsApp"
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = (2 * density).toInt()
            }
            val icon = activity.getDrawable(R.drawable.refresh)?.mutate()
            icon?.setTint(DesignUtils.getPrimaryTextColor())
            setImageDrawable(icon)
            setOnClickListener {
                Utils.doRestart(activity)
            }
        }
        container.addView(restartBtn)

        // 5. Open WaEnhancer
        val waeBtn = ImageButton(activity).apply {
            id = com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_OPEN_WAE
            contentDescription = "Open WaEnhancer"
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = (2 * density).toInt()
            }
            val icon = DesignUtils.getDrawableByName("ic_settings")?.mutate()
            icon?.setTint(DesignUtils.getPrimaryTextColor())
            setImageDrawable(icon)
            setOnClickListener {
                try {
                    val intent = activity.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Utils.showToast(e.message)
                }
            }
        }
        container.addView(waeBtn)
        

        // Tempelkan container tepat di sebelah kiri ActionMenuView (kamera)
        var cachedAmvForContainer: ViewGroup? = null
        var cachedFakePlus: ImageView? = null
        container.viewTreeObserver.addOnPreDrawListener {
            if (cachedAmvForContainer == null || cachedAmvForContainer?.parent !== toolbar) {
                for (i in 0 until toolbar.childCount) {
                    val child = toolbar.getChildAt(i)
                    if (child.javaClass.name.contains("ActionMenuView")) {
                        cachedAmvForContainer = child as ViewGroup
                        break
                    }
                }
            }
            val amv = cachedAmvForContainer
            if (amv != null && amv.visibility == View.VISIBLE && amv.width > 0 && container.width > 0) {
                val amvLeft = amv.left + amv.translationX
                val targetTranslation = amvLeft - container.left - container.width
                if (Math.abs(container.translationX - targetTranslation) > 0.5f) {
                    container.translationX = targetTranslation
                }
                
                if (cachedFakePlus == null || cachedFakePlus?.parent !== toolbar) {
                    cachedFakePlus = toolbar.findViewWithTag<ImageView>("fake_plus_btn")
                }
                val fakePlus = cachedFakePlus
                if (fakePlus != null) {
                    val targetPlusX = amv.right - fakePlus.right.toFloat()
                    if (Math.abs(fakePlus.translationX - targetPlusX) > 0.5f) {
                        fakePlus.translationX = targetPlusX
                    }
                }
            }
            true
        }

        toolbar.addView(container, lp)
        refreshActionButtons(activity, container)
    }

    private fun refreshActionButtons(activity: Activity, container: ViewGroup) {
        val textColor = DesignUtils.getPrimaryTextColor()

        // 1. DND
        val showDnd = prefs.getBoolean("show_dndmode", false)
        val dndmode = WppCore.getPrivBoolean("dndmode", false)
        val dndBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_DND)
        if (dndBtn != null) {
            dndBtn.visibility = if (showDnd) View.VISIBLE else View.GONE
            val dndDrawable = activity.getDrawable(if (dndmode) R.drawable.airplane_enabled else R.drawable.airplane_disabled)?.mutate()
            dndDrawable?.setTint(textColor)
            dndBtn.setImageDrawable(dndDrawable)
        }

        // 2. Ghost
        val showGhost = prefs.getBoolean("ghostmode", true)
        val ghostmode = WppCore.getPrivBoolean("ghostmode", false)
        val ghostBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_GHOST)
        if (ghostBtn != null) {
            ghostBtn.visibility = if (showGhost) View.VISIBLE else View.GONE
            val ghostDrawable = activity.getDrawable(if (ghostmode) R.drawable.ghost_enabled else R.drawable.ghost_disabled)?.mutate()
            ghostDrawable?.setTint(textColor)
            ghostBtn.setImageDrawable(ghostDrawable)
        }

        // 3. Freeze
        val showFreeze = prefs.getBoolean("show_freezeLastSeen", true)
        val freezelastseen = WppCore.getPrivBoolean("freezelastseen", false)
        val freezeBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_FREEZE)
        if (freezeBtn != null) {
            freezeBtn.visibility = if (showFreeze) View.VISIBLE else View.GONE
            val freezeDrawable = activity.getDrawable(if (freezelastseen) R.drawable.eye_disabled else R.drawable.eye_enabled)?.mutate()
            freezeDrawable?.setTint(textColor)
            freezeBtn.setImageDrawable(freezeDrawable)
        }

        // 4. Restart
        val showRestart = prefs.getBoolean("restartbutton", true)
        val restartBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_RESTART)
        if (restartBtn != null) {
            restartBtn.visibility = if (showRestart) View.VISIBLE else View.GONE
            val restartDrawable = activity.getDrawable(R.drawable.refresh)?.mutate()
            restartDrawable?.setTint(textColor)
            restartBtn.setImageDrawable(restartDrawable)
        }

        // 5. WAE
        val showWae = prefs.getBoolean("open_wae", true)
        val waeBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_OPEN_WAE)
        if (waeBtn != null) {
            waeBtn.visibility = if (showWae) View.VISIBLE else View.GONE
            val waeDrawable = DesignUtils.getDrawableByName("ic_settings")?.mutate()
            waeDrawable?.setTint(textColor)
            waeBtn.setImageDrawable(waeDrawable)
        }
    }

    override fun getPluginName(): String {
        return "iOS Header Style"
    }
}
