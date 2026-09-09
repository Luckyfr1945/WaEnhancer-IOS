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
import android.view.ViewTreeObserver
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
        const val TAG_ACTIVE_TAB_NAME = 0x7E110005
        const val TAG_HIERARCHY_SET = 0x7E110009
        const val TAG_UNIFIED_PREDRAW = 0x7E11000A
        const val TAG_ACTION_BUTTONS_CONTAINER = 0x7E120099
        const val TAG_CONFIGURED_TYPE = 0x7E12009A
        val hookedToolbarClasses: MutableSet<Class<*>> = java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())
        var instance: IosHeader? = null

        @JvmStatic
        fun updateTabFromBottomBar(targetTitle: String) {
            val inst = instance ?: return
            inst.applyTabSelection(targetTitle)
        }
    }

    fun applyTabSelection(targetTitle: String) {
        val activity = WppCore.getCurrentActivity() ?: return
        activity.runOnUiThread {
            try {
                val headerId = Utils.getID("header", "id")
                val header = if (headerId > 0) activity.findViewById<ViewGroup>(headerId) else null ?: return@runOnUiThread
                val largeTitle = getLargeTitleView(header)
                val toolbar = header.findViewById<ViewGroup>(Utils.getID("toolbar", "id"))

                if (toolbar != null) {
                    ensureNavigationIcon(activity, toolbar)
                    injectActionButtons(activity, toolbar)
                }

                if (targetTitle == "Settings") {
                    header.setTag(TAG_ACTIVE_TAB_NAME, "Settings")
                    if (largeTitle != null) {
                        largeTitle.text = ""
                        largeTitle.visibility = View.GONE
                    }
                    val settingsTitle = toolbar?.findViewWithTag<TextView>("ios_settings_title")
                    settingsTitle?.visibility = View.GONE
                    settingsTitle?.alpha = 0f
                    settingsTitle?.text = ""
                } else {
                    val newTitle = resolveTabTitle(targetTitle)
                    header.setTag(TAG_ACTIVE_TAB_NAME, newTitle)
                    if (largeTitle != null) {
                        largeTitle.text = newTitle
                        largeTitle.visibility = View.VISIBLE
                    }
                    val settingsTitle = toolbar?.findViewWithTag<TextView>("ios_settings_title")
                    settingsTitle?.visibility = View.GONE
                    settingsTitle?.alpha = 0f
                    settingsTitle?.text = ""
                }
                val isChats = targetTitle == "Chats" || targetTitle == "Chat"
                setContainerMargin(header, isChats)
            } catch (_: Throwable) {}
        }
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
        instance = this

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
                                        val currentActiveTab = (header.getTag(TAG_ACTIVE_TAB_NAME) as? String) ?: ""
                                        val isMainTab = isMainContentTab(rawTitle)
                                        val isSettings = isSettingsTabTitle(rawTitle) || (!isMainTab && (currentActiveTab == "Settings" || isSettingsTabTitle(currentActiveTab)))

                                        val largeTitle = getLargeTitleView(header)
                                        if (isSettings || (!isMainTab && rawTitle.isNotEmpty())) {
                                            header.setTag(TAG_ACTIVE_TAB_NAME, "Settings")
                                            if (largeTitle != null) {
                                                largeTitle.text = ""
                                                largeTitle.visibility = View.GONE
                                            }
                                            // Jangan tampilkan teks judul native "Anda" di toolbar
                                            param2.args[0] = ""

                                            // Tampilkan nama user di settingsTitleView secara langsung
                                            val settingsTitle = toolbar.findViewWithTag<TextView>("ios_settings_title")
                                            if (settingsTitle != null) {
                                                val isTabTitle = isSettingsTabTitle(rawTitle) || rawTitle.isEmpty()
                                                if (!isTabTitle && rawTitle.isNotEmpty()) {
                                                    // rawTitle adalah nama user (mis. "Kiki.") saat scroll ke bawah
                                                    settingsTitle.text = rawTitle
                                                    settingsTitle.alpha = 1f
                                                    settingsTitle.setTextColor(DesignUtils.getPrimaryTextColor())
                                                    settingsTitle.visibility = View.VISIBLE
                                                    toolbar.post {
                                                        val targetX = (toolbar.width - settingsTitle.width) / 2f - settingsTitle.left.toFloat()
                                                        settingsTitle.translationX = targetX
                                                    }
                                                } else {
                                                    // "Anda" / tab label - sembunyikan saat di posisi paling atas
                                                    settingsTitle.visibility = View.GONE
                                                    settingsTitle.alpha = 0f
                                                    settingsTitle.text = ""
                                                }
                                            }
                                        } else if (isMainTab) {
                                            val newTitle = resolveTabTitle(rawTitle)
                                            header.setTag(TAG_ACTIVE_TAB_NAME, newTitle)
                                            if (largeTitle != null) {
                                                largeTitle.text = newTitle
                                                largeTitle.visibility = View.VISIBLE
                                                param2.args[0] = ""
                                            } else {
                                                param2.args[0] = newTitle
                                            }
                                        }
                                        val isChats = isMainTab && resolveTabTitle(rawTitle) == "Chats"
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
                        } catch (e: Throwable) {
                            logDebug("IosHeader: hook setTitle failed: ${e.message}")
                        }

                // Sembunyikan logo & title native
                val logo = toolbar.findViewById<View>(Utils.getID("toolbar_logo", "id"))
                logo?.visibility = View.GONE
                val logoText = toolbar.findViewById<View>(Utils.getID("toolbar_logo_text", "id"))
                logoText?.visibility = View.GONE
                val initialTitle = "Chats"
                        try {
                            val setTitleMethod = toolbar.javaClass.getMethod("setTitle", CharSequence::class.java)
                            setTitleMethod.invoke(toolbar, "")
                        } catch (e: Throwable) {
                            logDebug("IosHeader: setTitle initial clear failed: ${e.message}", e)
                        }

                        // Tambahkan centered Title TextView untuk navigasi scroll di tab Settings
                        val titleLp = try {
                            val lpClass = toolbar.javaClass.classLoader?.loadClass("androidx.appcompat.widget.Toolbar\$LayoutParams")
                                ?: toolbar.javaClass.classLoader?.loadClass("android.widget.Toolbar\$LayoutParams")
                            val constructor = lpClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                            val newLp = constructor?.newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER) as? ViewGroup.LayoutParams
                            newLp ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        } catch (_: Throwable) {
                            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        val settingsTitleView = TextView(activity).apply {
                            tag = "ios_settings_title"
                            textSize = 17f
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setTextColor(DesignUtils.getPrimaryTextColor())
                            gravity = Gravity.CENTER
                            alpha = 0f // Sembunyikan secara default saat di posisi atas
                            text = ""
                            layoutParams = titleLp
                        }
                        toolbar.addView(settingsTitleView)
                        
                        toolbar.post {
                            clearToolbarContent(toolbar)
                        }
                        if (toolbar.getTag(TAG_HIERARCHY_SET) != true) {
                            toolbar.setTag(TAG_HIERARCHY_SET, true)
                            toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                                override fun onChildViewAdded(parent: View?, child: View?) {
                                    clearToolbarContent(toolbar)
                                }
                                override fun onChildViewRemoved(parent: View?, child: View?) {}
                            })
                        }

                        // Satu listener preDraw terpadu untuk efisiensi maksimal (0ms idle overhead)
                        setupUnifiedToolbarPreDraw(activity, toolbar, header)


                        val isNight = DesignUtils.isNightMode()
                        val defaultBg = if (isNight) Color.parseColor("#0B141B") else Color.WHITE
                        val surfaceColor = DesignUtils.getPrimarySurfaceColor()
                        val headerBgColor = if (surfaceColor != -15132398 && surfaceColor != -2 && surfaceColor != 0) surfaceColor else defaultBg

                        header.setBackgroundColor(headerBgColor)
                        toolbar.setBackgroundColor(Color.TRANSPARENT)
                        header.elevation = 0f
                        header.bringToFront()

                        // Navigation icon: titik-tiga bulat khas iOS
                        ensureNavigationIcon(activity, toolbar)

                        // Suntikkan Action Buttons (DND, Ghost, Freeze, Restart, WAE) ke toolbar
                        injectActionButtons(activity, toolbar)

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
                                } catch (e: Throwable) {
                                    logDebug("IosHeader: cleanHeaderAction error: ${e.message}", e)
                                }
                            }

                            cleanHeaderAction()
                            header.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                                override fun onChildViewAdded(parent: View?, child: View?) {
                                    cleanHeaderAction()
                                }
                                override fun onChildViewRemoved(parent: View?, child: View?) {}
                            })
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
                                val headerId = Utils.getID("header", "id")
                                val header = if (headerId != 0) activity.findViewById<ViewGroup>(headerId) else null
                                if (header != null) {
                                    setupUnifiedToolbarPreDraw(activity, toolbar, header)
                                }
                            } catch (e: Throwable) {
                                logDebug("IosHeader: onCreateOptionsMenu post error: ${e.message}", e)
                            }
                        }
                    } catch (e: Throwable) {
                        logDebug("IosHeader: onCreateOptionsMenu error: ${e.message}", e)
                    }
                }
            }
        )

        // 4. Refresh action buttons on onResume
        XposedHelpers.findAndHookMethod(
            WppCore.homeActivityClass,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    try {
                        val toolbarId = Utils.getID("toolbar", "id")
                        if (toolbarId == 0) return
                        val toolbar = activity.findViewById<ViewGroup>(toolbarId) ?: return
                        val container = toolbar.findViewById<ViewGroup>(TAG_ACTION_BUTTONS_CONTAINER)
                        if (container != null) {
                            refreshActionButtons(activity, container)
                        }
                    } catch (_: Throwable) {}
                }
            }
        )

        // 4. Hook ViewPager setCurrentItem untuk deteksi tab berbasis posisi / ID numerik (independen bahasa)
        try {
            val viewPagerClass = classLoader.loadClass("androidx.viewpager.widget.ViewPager")
            XposedBridge.hookAllMethods(viewPagerClass, "setCurrentItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pos = param.args.getOrNull(0) as? Int ?: return
                    handleViewPagerPageSelected(pos)
                }
            })
        } catch (e: Throwable) {
            logDebug("IosHeader: hook ViewPager failed: ${e.message}", e)
        }
    }

    private fun handleViewPagerPageSelected(position: Int) {
        val homeActivity = WppCore.getCurrentActivity() ?: return
        if (homeActivity.javaClass != WppCore.homeActivityClass) return

        val tabId = try {
            val m = homeActivity.javaClass.getMethod("A5M", Int::class.javaPrimitiveType)
            m.invoke(homeActivity, position) as? Int ?: position
        } catch (_: Throwable) {
            when (position) {
                0 -> 600
                1 -> 200
                2 -> 300
                3 -> 400
                4 -> 700
                else -> -1
            }
        }

        val tabTitle = when (tabId) {
            200 -> "Chats"
            300 -> "Status"
            400 -> "Calls"
            600 -> "Communities"
            700 -> "Settings"
            else -> null
        }

        if (tabTitle != null) {
            applyTabSelection(tabTitle)
        }
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
    private fun cleanTabTitle(rawTitle: String): String {
        return rawTitle.replace(Regex("""[\(\[\{]\s*\d+\+?\s*[\)\]\}]"""), "").trim().lowercase()
    }

    private fun isMainContentTab(rawTitle: String): Boolean {
        val clean = cleanTabTitle(rawTitle)
        if (clean.isEmpty()) return true
        if (clean == "whatsapp" || clean.startsWith("whatsapp")) return true

        val mainTabKeywords = listOf(
            "chat", "chats", "obrolan", "conversas", "conversaciones", "discussions", "чаты", "دردشات",
            "status", "updates", "pembaruan", "novedades", "atualizações", "actu", "статус", "الحالة",
            "communities", "komunitas", "comunidades", "communautés", "сообщества", "المجتمعات",
            "calls", "panggilan", "chamadas", "llamadas", "appels", "звонки", "المكالمات"
        )
        return mainTabKeywords.any { clean == it }
    }

    private fun isSettingsTabTitle(rawTitle: String): Boolean {
        val clean = cleanTabTitle(rawTitle)
        val youTitle = try { cleanTabTitle(Utils.getYouTabString(Utils.application)) } catch (_: Throwable) { "anda" }
        if (clean == youTitle || clean == "anda" || clean == "you") return true

        val settingsKeywords = listOf(
            "settings", "setelan", "pengaturan", "ajustes", "configurações", "paramètres",
            "einstellungen", "impostazioni", "настройки", "الإعدادات", "profil", "profile"
        )
        return settingsKeywords.any { clean == it }
    }

    private fun resolveTabTitle(rawTitle: String): String {
        val youTitle = try { Utils.getYouTabString(Utils.application) } catch (_: Throwable) { "Anda" }
        val clean = cleanTabTitle(rawTitle)

        if (isSettingsTabTitle(clean)) return youTitle

        val knownTabs = mapOf(
            "chats" to "Chats", "obrolan" to "Chats", "chat" to "Chats", "conversas" to "Chats", "conversaciones" to "Chats",
            "status" to "Status", "updates" to "Status", "pembaruan" to "Status", "novedades" to "Status", "atualizações" to "Status",
            "communities" to "Communities", "komunitas" to "Communities", "comunidades" to "Communities",
            "calls" to "Calls", "panggilan" to "Calls", "chamadas" to "Calls", "llamadas" to "Calls"
        )

        knownTabs[clean]?.let { return it }

        return when {
            rawTitle.isEmpty() -> {
                val activeTab = try {
                    val hdrId = Utils.getID("header", "id")
                    val hdr = if (hdrId != 0) WppCore.getCurrentActivity()?.findViewById<ViewGroup>(hdrId) else null
                    hdr?.getTag(TAG_ACTIVE_TAB_NAME) as? String
                } catch (_: Throwable) { null }
                activeTab?.takeIf { it.isNotEmpty() } ?: "Chats"
            }
            rawTitle.contains("WhatsApp", ignoreCase = true) -> "Chats"
            else -> rawTitle
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

            // 2. Posisi search bar HANYA untuk searchBarId jika ada dan menjadi direct child dari parent
            val searchBarId = Utils.getID("my_search_bar", "id")
            if (searchBarId != 0) {
                val searchBar = parent.findViewById<View>(searchBarId)
                if (searchBar != null && searchBar.parent === parent) {
                    val pParams = searchBar.layoutParams
                    if (pParams is ViewGroup.MarginLayoutParams) {
                        val target = if (largeTitle != null && largeTitle.visibility != View.GONE && largeTitle.height > 0) {
                            val titleLoc = IntArray(2)
                            val parentLoc = IntArray(2)
                            largeTitle.getLocationOnScreen(titleLoc)
                            parent.getLocationOnScreen(parentLoc)
                            val titleBottomInParent = (titleLoc[1] - parentLoc[1]) + largeTitle.height
                            val searchBarInternalTopPad = searchBar.paddingTop
                            titleBottomInParent + desiredGapPx - searchBarInternalTopPad
                        } else {
                            0
                        }
                        if (pParams.topMargin != target) {
                            pParams.topMargin = target
                            searchBar.layoutParams = pParams
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logDebug("IosHeader: setContainerMargin error: ${e.message}", e)
        }
    }


    private fun clearToolbarContent(toolbar: ViewGroup) {
        try {
            val act = toolbar.context as? Activity ?: WppCore.getCurrentActivity()
            if (act != null) {
                ensureNavigationIcon(act, toolbar)
            }
            val hdrId = Utils.getID("header", "id")
            val hdr = if (hdrId != 0) (toolbar.parent as? ViewGroup)?.findViewById<ViewGroup>(hdrId) ?: toolbar.rootView.findViewById<ViewGroup>(hdrId) else null
            val largeTitle = if (hdr != null) getLargeTitleView(hdr) else null
            val actionButtons = toolbar.findViewById<View>(TAG_ACTION_BUTTONS_CONTAINER)
            val fakePlus = toolbar.findViewWithTag<View>("fake_plus_btn")
            val isSearchActive = hasSearchViewChild(toolbar)

            if (isSearchActive) {
                // When search view is active in toolbar, hide decorative iOS elements
                if (largeTitle != null && largeTitle.visibility != View.GONE) largeTitle.visibility = View.GONE
                if (actionButtons != null && actionButtons.visibility != View.GONE) actionButtons.visibility = View.GONE
                if (fakePlus != null && fakePlus.visibility != View.GONE) fakePlus.visibility = View.GONE

                val navIcon = toolbar.findViewWithTag<View>("ios_nav_icon")
                if (navIcon != null && navIcon.visibility != View.GONE) navIcon.visibility = View.GONE

                // Ensure all search views, input fields, and back arrows remain VISIBLE
                for (i in 0 until toolbar.childCount) {
                    val child = toolbar.getChildAt(i)
                    if (child.id == TAG_ACTION_BUTTONS_CONTAINER || child.tag == TAG_ACTION_BUTTONS_CONTAINER ||
                        child.tag == "fake_plus_btn" || child.tag == "ios_settings_title" || child.tag == "ios_nav_icon") continue
                    if (child.visibility != View.VISIBLE) {
                        child.visibility = View.VISIBLE
                    }
                }
                return
            }

            val activeTabTag = try { (hdr?.getTag(TAG_ACTIVE_TAB_NAME) as? String) ?: "" } catch (_: Throwable) { "" }
            val isSettings = isSettingsTabTitle(activeTabTag) || activeTabTag == "Settings" || !isMainContentTab(activeTabTag)

            if (!isSettings && largeTitle != null && largeTitle.text.isNotEmpty()) {
                if (largeTitle.visibility != View.VISIBLE) largeTitle.visibility = View.VISIBLE
            }
            if (actionButtons != null && actionButtons.visibility != View.VISIBLE) {
                actionButtons.visibility = View.VISIBLE
            }

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
                
                // 1d. Biarkan ios_settings_title
                if (child.tag == "ios_settings_title") continue
                
                // 2. Biarkan tombol navigasi di kiri (ikon titik-tiga buatan kita) dan semua ImageButton
                if (child is android.widget.ImageButton || className.contains("ImageButton", ignoreCase = true) || child.tag == "ios_nav_icon") {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }
                
                // 3. Skip SearchView dengan SEMUA varian
                if (className.contains("SearchView", ignoreCase = true) ||
                    className.contains("SearchAutoComplete", ignoreCase = true) ||
                    className.contains("SearchBar", ignoreCase = true) ||
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
                    desc.contains("kembali") || desc.contains("back") || desc.contains("navigas") || desc.contains("up") ||
                    className.contains("MetaAi", ignoreCase = true) || desc.contains("meta") || desc.contains("ai")) {
                    if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    continue
                }

                // 5. Jangan sembunyikan ViewGroup yang berisi search widget
                if (child is ViewGroup) {
                    if (hasSearchViewChild(child)) {
                        if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                        continue
                    }
                    val childLogo = child.findViewById<View>(Utils.getID("toolbar_logo", "id"))
                    childLogo?.visibility = View.GONE
                    val childLogoText = child.findViewById<View>(Utils.getID("toolbar_logo_text", "id"))
                    childLogoText?.visibility = View.GONE
                    hideWhatsAppTextInViewGroup(child)
                }
                
                // 6. Sembunyikan TextView native (title ios_settings_title dikelola khusus)
                if (child is TextView) {
                    if (child.tag != "ios_settings_title") {
                        if (child.visibility != View.GONE) {
                            child.visibility = View.GONE
                        }
                    }
                } else if (child is View && !className.contains("ActionMenuView")) {
                    if (child.visibility != View.GONE) {
                        child.visibility = View.GONE
                    }
                }
            }
        } catch (e: Throwable) {
            logDebug("IosHeader: clearToolbarContent error: ${e.message}", e)
        }
    }



    private fun hasSearchViewChild(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            val cn = c.javaClass.name
            val desc = c.contentDescription?.toString()?.lowercase() ?: ""
            if (cn.contains("SearchView", ignoreCase = true) ||
                cn.contains("SearchAutoComplete", ignoreCase = true) ||
                cn.contains("SearchBar", ignoreCase = true) ||
                cn.contains("TokenizedSearch", ignoreCase = true) ||
                c is android.widget.EditText ||
                c.id == Utils.getID("search_holder", "id") ||
                c.id == Utils.getID("search_view", "id") ||
                c.id == Utils.getID("search_bar", "id") ||
                c.id == Utils.getID("search_src_text", "id") ||
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

    /**
     * Helper untuk membuat background transparan tanpa bulatan dengan ripple effect
     */
    private fun createBorderlessRipple(isNight: Boolean): Drawable {
        val rippleColor = if (isNight) Color.parseColor("#33FFFFFF") else Color.parseColor("#33000000")
        val maskDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor),
            null,
            maskDrawable
        )
    }

    /**
     * Memastikan icon navigasi titik-tiga (IosMenuDrawable) dan onClick listener selalu terpasang
     * dan tidak hilang saat WhatsApp melakukan pergantian fragment / tab.
     */
    private fun ensureNavigationIcon(activity: Activity, toolbar: ViewGroup) {
        try {
            val isNight = DesignUtils.isNightMode()
            var navIconFound = false
            for (i in 0 until toolbar.childCount) {
                val c = toolbar.getChildAt(i)
                if (c.tag == "ios_nav_icon" || (c is ImageButton && (c.drawable is IosMenuDrawable))) {
                    c.tag = "ios_nav_icon"
                    if (c.visibility != View.VISIBLE) c.visibility = View.VISIBLE
                    navIconFound = true
                    break
                }
            }

            if (!navIconFound) {
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
            }
        } catch (e: Throwable) {
            logDebug("IosHeader: ensureNavigationIcon error: ${e.message}", e)
        }
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
        val btnMargin = (2 * density).toInt()
        val isNight = DesignUtils.isNightMode()
        val defaultIconColor = DesignUtils.getPrimaryTextColor()

        val lp = try {
            val defaultLp = XposedHelpers.callMethod(toolbar, "generateDefaultLayoutParams") as ViewGroup.LayoutParams
            XposedHelpers.setIntField(defaultLp, "gravity", Gravity.END or Gravity.CENTER_VERTICAL)
            defaultLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            defaultLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            (defaultLp as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
            defaultLp
        } catch (_: Throwable) {
            try {
                val lpClass = toolbar.javaClass.classLoader?.loadClass("androidx.appcompat.widget.Toolbar\$LayoutParams")
                    ?: toolbar.javaClass.classLoader?.loadClass("android.widget.Toolbar\$LayoutParams")
                val constructor = lpClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                val newLp = constructor?.newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL) as? ViewGroup.LayoutParams
                (newLp as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
                newLp ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } catch (_: Throwable) {
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        val fakePlusBtn = ImageView(activity).apply {
            tag = "fake_plus_btn"
            background = null
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            val plusLp = try {
                val pClass = toolbar.javaClass.classLoader?.loadClass("androidx.appcompat.widget.Toolbar\$LayoutParams")
                    ?: toolbar.javaClass.classLoader?.loadClass("android.widget.Toolbar\$LayoutParams")
                val constructor = pClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                val newLp = constructor?.newInstance(btnSize, btnSize, Gravity.END or Gravity.CENTER_VERTICAL) as? ViewGroup.LayoutParams
                (newLp as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
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
                        val tag = hdr?.getTag(TAG_ACTIVE_TAB_NAME) as? String
                        if (!tag.isNullOrEmpty()) {
                            tag
                        } else {
                            val largeTitle = if (hdr != null) getLargeTitleView(hdr) else null
                            largeTitle?.text?.toString() ?: ""
                        }
                    } catch (_: Throwable) { "" }

                    if (isSettingsTabTitle(currentTitle) || currentTitle == "Settings" || currentTitle.isEmpty()) {
                        return@setOnClickListener
                    }

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
                            clicked = performFallbackClickOnActiveTab(decor)
                        }
                    }

                    if (!clicked) {
                        logDebug("IosHeader: fake_plus_btn could not find a target action to click for tab '$tabType'")
                    }
                } catch (e: Throwable) {
                    logDebug("IosHeader: fake_plus_btn onClick error: ${e.message}", e)
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
            background = createBorderlessRipple(isNight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = btnMargin
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
            background = createBorderlessRipple(isNight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = btnMargin
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
            background = createBorderlessRipple(isNight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = btnMargin
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
            background = createBorderlessRipple(isNight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = btnMargin
            }
            val icon = activity.getDrawable(R.drawable.refresh)?.mutate()
            icon?.setTint(defaultIconColor)
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
            background = createBorderlessRipple(isNight)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = btnMargin
            }
            val icon = DesignUtils.getDrawableByName("ic_settings")?.mutate()
            icon?.setTint(defaultIconColor)
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
        

        toolbar.addView(container, lp)
        refreshActionButtons(activity, container)
    }

    /**
     * Satu listener onPreDraw tunggal di level Toolbar untuk menghitung layout semua elemen
     * (settingsTitle, fakePlusBtn, AMV icons, ActionButtons container) dalam satu pass.
     */
    private fun setupUnifiedToolbarPreDraw(activity: Activity, toolbar: ViewGroup, header: ViewGroup) {
        if (toolbar.getTag(TAG_UNIFIED_PREDRAW) == true) return
        toolbar.setTag(TAG_UNIFIED_PREDRAW, true)

        val headerId = Utils.getID("header", "id")
        val fabId = activity.resources.getIdentifier("fab", "id", activity.packageName)
        val overflowId = activity.resources.getIdentifier("menuitem_overflow", "id", "com.whatsapp")
        val searchId = activity.resources.getIdentifier("menuitem_search", "id", "com.whatsapp")
        val cameraId = activity.resources.getIdentifier("menuitem_camera", "id", "com.whatsapp")
        val callId = activity.resources.getIdentifier("menuitem_call", "id", "com.whatsapp")
        val phoneId = activity.resources.getIdentifier("menuitem_phone", "id", "com.whatsapp")

        var cachedHdr: ViewGroup? = null
        var cachedFab: View? = null
        var cachedAmv: ViewGroup? = null
        var cachedFakePlus: ImageView? = null
        var cachedContainer: ViewGroup? = null
        var cachedSettingsTitle: TextView? = null

        var previousTitle = ""
        var lastIsChatsTab: Boolean? = null

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            try {
                if (!toolbar.isAttachedToWindow || toolbar.width <= 0) return@OnPreDrawListener true

                val currentWidth = toolbar.width
                if (cachedHdr == null && headerId != 0) cachedHdr = activity.findViewById<ViewGroup>(headerId)
                val hdr = cachedHdr ?: header
                val largeTitle = getLargeTitleView(hdr)
                val currentTitle = largeTitle?.text?.toString() ?: ""
                val activeTabTag = (hdr.getTag(TAG_ACTIVE_TAB_NAME) as? String) ?: ""

                // 1. Deteksi perubahan title / tab
                if (currentTitle != previousTitle) {
                    if (previousTitle.isNotEmpty()) {
                        try {
                            com.wmods.wppenhacer.xposed.features.customization.IosSwipeMenu.closeSwipeMenu()
                        } catch (_: Throwable) {}
                    }
                    previousTitle = currentTitle
                    setContainerMargin(hdr, currentTitle == "Chats")
                }

                val isSettingsTab = isSettingsTabTitle(currentTitle) || 
                                    activeTabTag == "Settings" || 
                                    isSettingsTabTitle(activeTabTag) ||
                                    (largeTitle != null && (largeTitle.visibility == View.GONE || currentTitle.isEmpty()))
                val showPlusButton = !isSettingsTab
                if (lastIsChatsTab != showPlusButton) {
                    lastIsChatsTab = showPlusButton
                    setContainerMargin(hdr, showPlusButton)
                }

                // 2. Sembunyikan toolbar logo & native TextViews
                val logo = toolbar.findViewById<View>(Utils.getID("toolbar_logo", "id"))
                if (logo != null && logo.visibility != View.GONE) {
                    logo.visibility = View.GONE
                }
                val logoText = toolbar.findViewById<View>(Utils.getID("toolbar_logo_text", "id"))
                if (logoText != null && logoText.visibility != View.GONE) {
                    logoText.visibility = View.GONE
                }
                val isSearchActive = hasSearchViewChild(toolbar)
                for (i in 0 until toolbar.childCount) {
                    val c = toolbar.getChildAt(i)
                    if (c.tag == "ios_settings_title") continue
                    if (c is TextView && !isSearchActive && c.visibility != View.GONE) {
                        c.visibility = View.GONE
                    }
                    if (c is ViewGroup && !isSearchActive) {
                        hideWhatsAppTextInViewGroup(c)
                    }
                }

                // 3. Settings title centering on "Anda" tab
                if (cachedSettingsTitle == null || cachedSettingsTitle?.parent !== toolbar) {
                    cachedSettingsTitle = toolbar.findViewWithTag<TextView>("ios_settings_title")
                }
                val settingsTitle = cachedSettingsTitle
                if (settingsTitle != null) {
                    if (!isSettingsTab) {
                        if (settingsTitle.visibility != View.GONE) {
                            settingsTitle.visibility = View.GONE
                            settingsTitle.text = ""
                        }
                    } else {
                        if (settingsTitle.visibility == View.VISIBLE && settingsTitle.text.isNotEmpty() && settingsTitle.width > 0) {
                            val targetX = (currentWidth - settingsTitle.width) / 2f - settingsTitle.left.toFloat()
                            if (Math.abs(settingsTitle.translationX - targetX) > 1.5f) {
                                settingsTitle.translationX = targetX
                            }
                        }
                    }
                }

                // 0. Pastikan navigasi titik-3 dan action buttons container selalu terpasang
                ensureNavigationIcon(activity, toolbar)

                // 4. Update cached view references
                if (cachedFakePlus == null || cachedFakePlus?.parent !== toolbar) {
                    cachedFakePlus = toolbar.findViewWithTag<ImageView>("fake_plus_btn")
                }
                val fakePlus = cachedFakePlus
                if (fakePlus != null) {
                    val targetVis = if (showPlusButton) View.VISIBLE else View.GONE
                    if (fakePlus.visibility != targetVis) {
                        fakePlus.visibility = targetVis
                    }
                }

                if (cachedContainer == null || cachedContainer?.parent !== toolbar) {
                    cachedContainer = toolbar.findViewById<ViewGroup>(TAG_ACTION_BUTTONS_CONTAINER)
                    if (cachedContainer == null) {
                        injectActionButtons(activity, toolbar)
                        cachedContainer = toolbar.findViewById<ViewGroup>(TAG_ACTION_BUTTONS_CONTAINER)
                    }
                }
                val container = cachedContainer
                if (container != null && container.visibility != View.VISIBLE) {
                    container.visibility = View.VISIBLE
                }

                if (cachedAmv == null || cachedAmv?.parent !== toolbar) {
                    for (i in 0 until toolbar.childCount) {
                        val child = toolbar.getChildAt(i)
                        if (child.javaClass.name.contains("ActionMenuView")) {
                            cachedAmv = child as ViewGroup
                            break
                        }
                    }
                }
                val amv = cachedAmv

                // 5. FAB visibility guard
                if (fabId != 0) {
                    if (cachedFab == null) cachedFab = activity.findViewById<View>(fabId)
                    val fab = cachedFab
                    if (fab != null) {
                        if (fab.scaleX != 0f) fab.scaleX = 0f
                        if (fab.scaleY != 0f) fab.scaleY = 0f
                        if (fab.alpha != 0f) fab.alpha = 0f
                    }
                }

                // 6. AMV button item initial setup (one-off per button)
                if (amv != null) {
                    for (j in 0 until amv.childCount) {
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

                            if (type == 1 && btn is ImageView) {
                                btn.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                                btn.alpha = 0f
                                val targetTransX = - (activity.resources.displayMetrics.widthPixels).toFloat() + (150 * activity.resources.displayMetrics.density)
                                btn.translationX = targetTransX
                                btn.setOnTouchListener(null)
                                btn.visibility = View.VISIBLE
                            } else if (type == 2) {
                                btn.visibility = View.VISIBLE
                                btn.alpha = 1f
                                val density = activity.resources.displayMetrics.density
                                val pad = (4 * density).toInt()
                                btn.setPadding(pad, btn.paddingTop, pad, btn.paddingBottom)
                            } else {
                                btn.visibility = View.GONE
                            }
                        }
                    }
                }

                // 7. Right-to-Left Position Pass (Single coherent layout calculation)
                val density = activity.resources.displayMetrics.density
                val rightMargin = (6 * density).toInt()
                val itemSpacing = (2 * density).toInt()
                var nextRightBoundary = (currentWidth - rightMargin).toFloat()

                // 7a. fakePlusBtn
                if (fakePlus != null && fakePlus.visibility == View.VISIBLE && fakePlus.width > 0) {
                    val targetPlusX = nextRightBoundary - fakePlus.right.toFloat()
                    if (Math.abs(fakePlus.translationX - targetPlusX) > 1.5f) {
                        fakePlus.translationX = targetPlusX
                    }
                    nextRightBoundary = (fakePlus.left + targetPlusX) - itemSpacing
                }

                // 7b. AMV visible items
                if (amv != null && amv.visibility == View.VISIBLE && amv.width > 0) {
                    var leftmostVisibleAmvChild: View? = null
                    var rightmostVisibleAmvChild: View? = null
                    var visibleChildCount = 0
                    for (j in 0 until amv.childCount) {
                        val child = amv.getChildAt(j)
                        val type = child.getTag(TAG_CONFIGURED_TYPE) as? Int
                        if (type == 1) continue
                        if (child.visibility == View.VISIBLE && child.alpha > 0.1f && child.width > 0) {
                            visibleChildCount++
                            if (leftmostVisibleAmvChild == null || child.left < leftmostVisibleAmvChild.left) {
                                leftmostVisibleAmvChild = child
                            }
                            if (rightmostVisibleAmvChild == null || child.right > rightmostVisibleAmvChild.right) {
                                rightmostVisibleAmvChild = child
                            }
                        }
                    }

                    if (visibleChildCount > 0 && rightmostVisibleAmvChild != null && leftmostVisibleAmvChild != null) {
                        val targetAmvX = nextRightBoundary - (amv.left + rightmostVisibleAmvChild.right)
                        if (Math.abs(amv.translationX - targetAmvX) > 1.5f) {
                            amv.translationX = targetAmvX
                        }
                        nextRightBoundary = (amv.left + targetAmvX + leftmostVisibleAmvChild.left) - itemSpacing
                    } else {
                        if (Math.abs(amv.translationX) > 1.5f) {
                            amv.translationX = 0f
                        }
                    }
                }

                // 7c. Action Buttons container (Restart, DND, Ghost, etc.)
                if (container != null && container.visibility == View.VISIBLE && container.width > 0) {
                    var rightmostVisibleChild: View? = null
                    for (j in 0 until container.childCount) {
                        val child = container.getChildAt(j)
                        if (child.visibility == View.VISIBLE && child.width > 0) {
                            if (rightmostVisibleChild == null || child.right > rightmostVisibleChild.right) {
                                rightmostVisibleChild = child
                            }
                        }
                    }
                    val actualContainerRight = if (rightmostVisibleChild != null) {
                        val childMarginEnd = (rightmostVisibleChild.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
                        container.left + rightmostVisibleChild.right - childMarginEnd
                    } else {
                        container.left + container.width
                    }
                    val targetContainerX = nextRightBoundary - actualContainerRight
                    if (Math.abs(container.translationX - targetContainerX) > 1.5f) {
                        container.translationX = targetContainerX
                    }
                }
            } catch (e: Throwable) {
                logDebug("IosHeader: unified preDraw error: ${e.message}", e)
            }
            true
        }

        toolbar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                try {
                    v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                    v.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                } catch (e: Throwable) {
                    logDebug("IosHeader: re-add preDrawListener failed: ${e.message}", e)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                try {
                    v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                } catch (e: Throwable) {
                    logDebug("IosHeader: remove preDrawListener failed: ${e.message}", e)
                }
            }
        })

        if (toolbar.isAttachedToWindow) {
            toolbar.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    private fun refreshActionButtons(activity: Activity, container: ViewGroup) {
        val isNight = DesignUtils.isNightMode()
        val textColor = DesignUtils.getPrimaryTextColor()
        val activeColor = Color.parseColor("#34C759")

        // 1. DND
        val showDnd = prefs.getBoolean("show_dndmode", false)
        val dndmode = WppCore.getPrivBoolean("dndmode", false)
        val dndBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_DND)
        if (dndBtn != null) {
            dndBtn.visibility = if (showDnd) View.VISIBLE else View.GONE
            dndBtn.background = createBorderlessRipple(isNight)
            val dndDrawable = activity.getDrawable(if (dndmode) R.drawable.airplane_enabled else R.drawable.airplane_disabled)?.mutate()
            dndDrawable?.setTint(if (dndmode) activeColor else textColor)
            dndBtn.setImageDrawable(dndDrawable)
        }

        // 2. Ghost
        val showGhost = prefs.getBoolean("ghostmode", true)
        val ghostmode = WppCore.getPrivBoolean("ghostmode", false)
        val ghostBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_GHOST)
        if (ghostBtn != null) {
            ghostBtn.visibility = if (showGhost) View.VISIBLE else View.GONE
            ghostBtn.background = createBorderlessRipple(isNight)
            val ghostDrawable = activity.getDrawable(if (ghostmode) R.drawable.ghost_enabled else R.drawable.ghost_disabled)?.mutate()
            ghostDrawable?.setTint(if (ghostmode) activeColor else textColor)
            ghostBtn.setImageDrawable(ghostDrawable)
        }

        // 3. Freeze
        val showFreeze = prefs.getBoolean("show_freezeLastSeen", true)
        val freezelastseen = WppCore.getPrivBoolean("freezelastseen", false)
        val freezeBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_FREEZE)
        if (freezeBtn != null) {
            freezeBtn.visibility = if (showFreeze) View.VISIBLE else View.GONE
            freezeBtn.background = createBorderlessRipple(isNight)
            val freezeDrawable = activity.getDrawable(if (freezelastseen) R.drawable.eye_disabled else R.drawable.eye_enabled)?.mutate()
            freezeDrawable?.setTint(if (freezelastseen) activeColor else textColor)
            freezeBtn.setImageDrawable(freezeDrawable)
        }

        // 4. Restart
        val showRestart = prefs.getBoolean("restartbutton", true)
        val restartBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_RESTART)
        if (restartBtn != null) {
            restartBtn.visibility = if (showRestart) View.VISIBLE else View.GONE
            restartBtn.background = createBorderlessRipple(isNight)
            val restartDrawable = activity.getDrawable(R.drawable.refresh)?.mutate()
            restartDrawable?.setTint(textColor)
            restartBtn.setImageDrawable(restartDrawable)
        }

        // 5. WAE
        val showWae = prefs.getBoolean("open_wae", true)
        val waeBtn = container.findViewById<ImageButton>(com.wmods.wppenhacer.xposed.features.others.MenuHome.ID_OPEN_WAE)
        if (waeBtn != null) {
            waeBtn.visibility = if (showWae) View.VISIBLE else View.GONE
            waeBtn.background = createBorderlessRipple(isNight)
            val waeDrawable = DesignUtils.getDrawableByName("ic_settings")?.mutate()
            waeDrawable?.setTint(textColor)
            waeBtn.setImageDrawable(waeDrawable)
        }
    }

    override fun getPluginName(): String {
        return "iOS Header Style"
    }
}
