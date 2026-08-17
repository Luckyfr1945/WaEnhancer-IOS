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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
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
        var isToolbarHooked = false
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

                        // Hook setTitle ke CLASS ASLI toolbar
                        try {
                            if (!isToolbarHooked) {
                                isToolbarHooked = true
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
                                        // Validasi per-tab: cocokkan ke daftar label yang dikenal (ID & EN)
                                        // dulu, baru fallback ke "Chats" — supaya Settings/Status/Communities
                                        // tidak ikut ke-anggap tab Chats hanya gara-gara title kosong.
                                        val newTitle = resolveTabTitle(rawTitle)

                                        val largeTitle = getLargeTitleView(header)
                                        if (largeTitle != null) {
                                            largeTitle.text = newTitle
                                            param2.args[0] = ""
                                        } else {
                                            param2.args[0] = newTitle
                                        }
                                        // Semua tab perlu margin — isChats tidak lagi menentukan apakah
                                        // margin ditambah atau tidak, tapi tetap diteruskan untuk kompatibilitas
                                        val isChats = newTitle == "Chats"
                                        // Post agar Large Title sudah ter-layout sebelum margin dihitung
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
                                        
                                        clearToolbarContent(t)
                                    }
                                })
                            }
                        } catch (e: Throwable) {}

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
                        val globalLayoutListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                clearToolbarContent(toolbar)
                            }
                        }
                        toolbar.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

                        val isNight = DesignUtils.isNightMode()
                        val defaultBg = if (isNight) Color.parseColor("#0B141B") else Color.WHITE
                        val surfaceColor = DesignUtils.getPrimarySurfaceColor()
                        val headerBgColor = if (surfaceColor != -15132398 && surfaceColor != -2 && surfaceColor != 0) surfaceColor else defaultBg

                        header.setBackgroundColor(headerBgColor)
                        toolbar.setBackgroundColor(Color.TRANSPARENT)
                        header.elevation = 0f
                        header.bringToFront()

                        // Navigation icon: titik-tiga bulat khas iOS
                        try {
                            val setNavIcon = toolbar.javaClass.getMethod("setNavigationIcon", Drawable::class.java)
                            setNavIcon.invoke(toolbar, IosMenuDrawable(activity, isNight))

                            val setNavOnClick = toolbar.javaClass.getMethod(
                                "setNavigationOnClickListener", View.OnClickListener::class.java
                            )
                            setNavOnClick.invoke(toolbar, View.OnClickListener { activity.openOptionsMenu() })
                        } catch (e: Exception) {}

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
                                        // Hide gambar logo apapun yang tersisa di header
                                        child.visibility = View.GONE
                                    } else if (child is ViewGroup) {
                                        // Coba sembunyikan TextView bertuliskan WhatsApp di dalam ViewGroup ini
                                        hideWhatsAppTextInViewGroup(child)
                                    }
                                }
                            } catch (e: Exception) {}
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

                                            override fun onPreDraw(): Boolean {
                                                try {
                                                    if (cachedHdr == null && headerId != 0) cachedHdr = activity.findViewById<ViewGroup>(headerId)
                                                    val hdr = cachedHdr
                                                    val largeTitle = if (hdr != null) getLargeTitleView(hdr) else null
                                                    val isChatsTab = largeTitle != null && largeTitle.text == "Chats"
                                                    
                                                    if (fabId != 0) {
                                                        if (cachedFab == null) cachedFab = activity.findViewById<View>(fabId)
                                                        val fab = cachedFab
                                                        if (fab != null) {
                                                            if (isChatsTab) {
                                                                if (fab.scaleX != 0f) fab.scaleX = 0f
                                                                if (fab.scaleY != 0f) fab.scaleY = 0f
                                                                if (fab.alpha != 0f) fab.alpha = 0f
                                                            } else {
                                                                if (fab.scaleX != 1f) fab.scaleX = 1f
                                                                if (fab.scaleY != 1f) fab.scaleY = 1f
                                                                if (fab.alpha != 1f) fab.alpha = 1f
                                                            }
                                                            
                                                            val parent = fab.parent as? ViewGroup
                                                            if (parent != null) {
                                                                val density = activity.resources.displayMetrics.density
                                                                for (k in 0 until parent.childCount) {
                                                                    val c = parent.getChildAt(k)
                                                                    if (c == fab) continue
                                                                    if (c.width > 0 && c.height > 0 && c.height < 100 * density && c.x > parent.width / 2f) {
                                                                        if (isChatsTab) {
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
                                                    
                                                    for (j in 0 until amv.childCount) {
                                                        val btn = amv.getChildAt(j)
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

                                                        val desc = btn.contentDescription
                                                        if (desc != null) {
                                                            if (desc.contains("opsi", ignoreCase = true) || desc.contains("more", ignoreCase = true)) isOverflow = true
                                                            if (desc.contains("cari", ignoreCase = true) || desc.contains("search", ignoreCase = true)) isSearch = true
                                                            if (desc.contains("kamera", ignoreCase = true) || desc.contains("camera", ignoreCase = true)) isCamera = true
                                                            if (desc.contains("panggilan", ignoreCase = true) || desc.contains("call", ignoreCase = true) ||
                                                                desc.contains("telepon", ignoreCase = true) || desc.contains("phone", ignoreCase = true)) isPhone = true
                                                            if (desc.contains("dnd", ignoreCase = true) || desc.contains("pesawat", ignoreCase = true) ||
                                                                desc.contains("ghost", ignoreCase = true) || desc.contains("bekukan", ignoreCase = true) ||
                                                                desc.contains("freeze", ignoreCase = true) || desc.contains("restart", ignoreCase = true) ||
                                                                desc.contains("enhancer", ignoreCase = true) || desc.contains("wae", ignoreCase = true)) {
                                                                isCustomAction = true
                                                            }
                                                        }

                                                        if (!isOverflow && btn.javaClass.name.contains("OverflowMenuButton")) isOverflow = true
                                                        
                                                        if (isOverflow && btn is ImageView) {
                                                            if (isChatsTab) {
                                                                val currentDrawable = btn.drawable
                                                                if (currentDrawable !is IosPlusDrawable) {
                                                                    val size = (32 * activity.resources.displayMetrics.density).toInt()
                                                                    btn.setImageDrawable(IosPlusDrawable(size))
                                                                    btn.setOnClickListener {
                                                                        try {
                                                                            if (fabId != 0) {
                                                                                val realFab = activity.findViewById<View>(fabId)
                                                                                realFab?.performClick()
                                                                            }
                                                                        } catch (e: Exception) {}
                                                                    }
                                                                }
                                                                if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
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
                                                        } else if (isCustomAction || isSearch || isCamera || isPhone) {
                                                            // Biarkan Custom Actions (DND, Ghost, Freeze, Restart, WAE), Search, Camera & Phone tetap visible
                                                            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
                                                            btn.alpha = 1f
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
    private fun resolveTabTitle(rawTitle: String): String {
        val knownTabs = mapOf(
            "chats" to "Chats", "obrolan" to "Chats",
            "status" to "Status", "updates" to "Status", "pembaruan" to "Status",
            "communities" to "Communities", "komunitas" to "Communities",
            "calls" to "Calls", "panggilan" to "Calls",
            "settings" to "Settings", "setelan" to "Settings", "pengaturan" to "Settings"
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
            val headerBottomInParent = header.top + header.height
            val paddingDp = (-2 * density).toInt()

            // AGGRESSIVE: set conversation_container ke 0, rely pada header.elevation/bringToFront
            val ccId = Utils.getID("conversation_container", "id")
            if (ccId != 0) {
                val cc = parent.findViewById<ViewGroup>(ccId)
                if (cc != null && cc.layoutParams is ViewGroup.MarginLayoutParams) {
                    val ccParams = cc.layoutParams as ViewGroup.MarginLayoutParams
                    // Set ke 0 — header will overlap naturally, search_bar akan langsung di bawah toolbar
                    val largeTitle = getLargeTitleView(header)
                    val offset = largeTitle?.height ?: 0
                    
                    if (ccParams.topMargin != offset) {
                        ccParams.topMargin = offset
                        cc.layoutParams = ccParams
                    }
                    return
                }
            }

            // Fallback: old logic
            val searchBarId = Utils.getID("my_search_bar", "id")
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child === header) continue
                val pParams = child.layoutParams
                if (pParams is ViewGroup.MarginLayoutParams) {
                    val target = if (searchBarId != 0 && child.id == searchBarId) {
                        // Search bar: set margin ke header bottom + padding
                        headerBottomInParent + paddingDp
                    } else {
                        // Child lain: hitung overlap seperti sebelumnya
                        val originalMargin = (child.getTag(TAG_ORIGINAL_MARGIN) as? Int) ?: run {
                            child.setTag(TAG_ORIGINAL_MARGIN, pParams.topMargin)
                            pParams.topMargin
                        }

                        val childTopInParent = child.top - pParams.topMargin
                        if (headerBottomInParent > 0 && childTopInParent < headerBottomInParent) {
                            val neededMargin = headerBottomInParent - childTopInParent + paddingDp
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
                
                // 2. Biarkan tombol navigasi di kiri (ikon titik-tiga buatan kita) dan tombol +
                if (child is android.widget.ImageButton) {
                    val d = child.drawable
                    if (d is IosMenuDrawable || d is IosPlusDrawable) continue
                }
                
                // 3. Skip SearchView dengan SEMUA varian
                if (className.contains("SearchView") ||
                    className.contains("SearchAutoComplete") ||
                    child is android.widget.EditText) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }
                
                // 4. Jangan sembunyikan Search, Kamera, dan Phone/Call icon
                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                val searchId = Utils.getID("menuitem_search", "id")
                val cameraId = Utils.getID("menuitem_camera", "id")
                
                if (desc.contains("cari") || desc.contains("search") || (searchId != 0 && child.id == searchId) ||
                    desc.contains("kamera") || desc.contains("camera") || (cameraId != 0 && child.id == cameraId) ||
                    desc.contains("panggilan") || desc.contains("call") ||
                    desc.contains("telepon") || desc.contains("phone")) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }

                // 5. Jangan sembunyikan ViewGroup (bisa berisi search/phone widget)
                if (child is ViewGroup && child !is LinearLayout) {
                    // Cek apakah di dalamnya ada SearchView
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
            if (cn.contains("SearchView") || cn.contains("SearchAutoComplete") || c is android.widget.EditText) {
                return true
            }
            if (c is ViewGroup && hasSearchViewChild(c)) return true
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

    override fun getPluginName(): String {
        return "iOS Header Style"
    }
}
