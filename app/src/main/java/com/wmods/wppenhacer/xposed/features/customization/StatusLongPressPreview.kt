package com.wmods.wppenhacer.xposed.features.customization

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

class StatusLongPressPreview(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {

    companion object {
        @Volatile
        private var lastLongPressedCardRef: WeakReference<View>? = null
        @Volatile
        private var lastLongPressedTimestamp: Long = 0L
        @Volatile
        private var isShowingCustomPreview = false
    }

    override fun getPluginName(): String = "StatusLongPressPreview"

    override fun doHook() {
        val statusStyle = prefs.getString("status_style", "0")?.toIntOrNull() ?: 0
        logDebug("StatusLongPressPreview: doHook() called with status_style=$statusStyle")
        // Fitur ini aktif khusus saat style Facebook 1 row (status_style == 1)
        if (statusStyle != 1) return

        logDebug("StatusLongPressPreview: Initializing iOS-style compact status preview for Facebook 1 row")

        // 1. Hook Activity.dispatchTouchEvent pada ACTION_DOWN
        try {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "dispatchTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val actName = activity.javaClass.name
                        // Batasi hanya di main/home screen WhatsApp agar tidak ada overhead di screen lain
                        if (!actName.contains("HomeActivity") && !actName.contains("Main")) return

                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            val decor = activity.window?.decorView ?: return
                            val leaf = findLeafViewUnder(decor, ev.rawX, ev.rawY)
                            if (leaf != null) {
                                val isMy = isMyStatusItem(leaf)
                                val rootCard = findRootStatusCard(leaf)
                                val isStatusDim = isStatusCardDimensions(rootCard)
                                if (isStatusDim && !isMy) {
                                    lastLongPressedCardRef = WeakReference(rootCard)
                                    lastLongPressedTimestamp = System.currentTimeMillis()
                                    logDebug("StatusLongPressPreview: [Activity] Captured valid card ${rootCard.javaClass.simpleName} size=${rootCard.width}x${rootCard.height}")
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logDebug("StatusLongPressPreview: Failed to hook Activity.dispatchTouchEvent: ${e.message}")
        }

        // 2. Tangkap via performLongClick sebagai lapisan cadangan
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "performLongClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = WppCore.getCurrentActivity()
                        val actName = activity?.javaClass?.name ?: ""
                        if (!actName.contains("HomeActivity") && !actName.contains("Main")) return

                        val v = param.thisObject as? View ?: return
                        val isMy = isMyStatusItem(v)
                        val rootCard = findRootStatusCard(v)
                        val isStatusDim = isStatusCardDimensions(rootCard)

                        if (!isMy && isStatusDim) {
                            lastLongPressedCardRef = WeakReference(rootCard)
                            lastLongPressedTimestamp = System.currentTimeMillis()
                            logDebug("StatusLongPressPreview: performLongClick saved rootCard=${rootCard.javaClass.simpleName} size=${rootCard.width}x${rootCard.height}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logDebug("StatusLongPressPreview: Failed to hook performLongClick: ${e.message}")
        }

        // 3. Intersepsi Mute Status Dialog asli WhatsApp secara SINKRON (Zero-Flicker)
        try {
            XposedHelpers.findAndHookMethod(
                Dialog::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isShowingCustomPreview) return

                        val dialog = param.thisObject as? Dialog ?: return
                        val window = dialog.window ?: return
                        val decor = window.decorView

                        // Sembunyikan window dialog secara SINKRON HANYA JIKA touch terjadi dalam 1000ms terakhir
                        val capturedCard = lastLongPressedCardRef?.get()
                        val isRecentStatusCardTouch = capturedCard != null && 
                            isStatusCardDimensions(capturedCard) && 
                            (System.currentTimeMillis() - lastLongPressedTimestamp < 1000L)

                        if (isRecentStatusCardTouch) {
                            window.setDimAmount(0f)
                            decor.alpha = 0f
                            decor.visibility = View.INVISIBLE
                        }

                        decor.post {
                            try {
                                val textViews = findAllTextViews(decor)
                                val fullText = textViews.joinToString(" | ") { it.text?.toString() ?: "" }
                                logDebug("StatusLongPressPreview: Dialog.show class=${dialog.javaClass.name} fullText='$fullText'")

                                if (isMuteStatusDialog(fullText)) {
                                    lastLongPressedCardRef = null
                                    lastLongPressedTimestamp = 0L

                                    val activity = WppCore.getCurrentActivity() ?: dialog.context as? Activity ?: return@post
                                    val contactName = extractContactNameFromDialog(fullText)
                                    logDebug("StatusLongPressPreview: Successfully detected Mute Dialog for contact='$contactName'")

                                    decor.visibility = View.INVISIBLE
                                    decor.alpha = 0f
                                    window.setDimAmount(0f)

                                    val cardView = if (capturedCard != null && capturedCard.isAttachedToWindow && isStatusCardDimensions(capturedCard)) {
                                        logDebug("StatusLongPressPreview: Using captured card from touch reference (${capturedCard.width}x${capturedCard.height})")
                                        capturedCard
                                    } else {
                                        val matched = findMatchingStatusCard(activity, contactName)
                                        logDebug("StatusLongPressPreview: Fallback matched card: $matched")
                                        matched
                                    }

                                    val positiveBtn = findPositiveButton(decor)

                                    showInPlaceStatusPreview(
                                        activity = activity,
                                        cardView = cardView,
                                        contactNameFallback = contactName,
                                        onMuteClicked = {
                                            if (positiveBtn != null) {
                                                positiveBtn.performClick()
                                            } else {
                                                dialog.dismiss()
                                            }
                                        },
                                        onDismissed = {
                                            dialog.dismiss()
                                        }
                                    )
                                } else {
                                    // BUKAN dialog mute status WhatsApp -> pulihkan visibilitas dialog asli
                                    decor.visibility = View.VISIBLE
                                    decor.alpha = 1f
                                    window.setDimAmount(0.6f)
                                }
                            } catch (e: Throwable) {
                                logDebug("StatusLongPressPreview: Dialog intercept error: ${e.message}")
                                decor.visibility = View.VISIBLE
                                decor.alpha = 1f
                                window.setDimAmount(0.6f)
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logDebug("StatusLongPressPreview: Failed to hook Dialog.show: ${e.message}")
        }
    }

    private fun findLeafViewUnder(root: View, rawX: Float, rawY: Float): View? {
        if (!root.isShown) return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + root.width
        val bottom = top + root.height

        if (rawX < left || rawX > right || rawY < top || rawY > bottom) {
            return null
        }

        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val leaf = findLeafViewUnder(child, rawX, rawY)
                if (leaf != null) return leaf
            }
        }
        return root
    }

    private fun isMyStatusItem(view: View): Boolean {
        var isMy = false
        fun check(v: View) {
            val resName = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: ""
            if (resName.contains("add_button") || resName.contains("my_status") || resName.contains("camera")) {
                isMy = true
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    check(v.getChildAt(i))
                }
            }
        }
        check(view)
        return isMy
    }

    private fun isStatusCardDimensions(view: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
        val widthDp = view.width / density
        val heightDp = view.height / density
        // Di semua resolusi device: lebar kartu 70..240dp, tinggi kartu 110..420dp
        return widthDp in 70f..240f && heightDp in 110f..420f
    }

    private fun findRootStatusCard(view: View): View {
        var curr: View = view
        val chain = mutableListOf<String>()
        var innermostCard: View? = null

        while (curr.parent is View) {
            val density = curr.resources.displayMetrics.density.coerceAtLeast(1f)
            val wDp = (curr.width / density).toInt()
            val hDp = (curr.height / density).toInt()
            chain.add("${curr.javaClass.simpleName}(${curr.width}x${curr.height} | ${wDp}x${hDp}dp)")

            // Ambil kartu pertama (innermost) yang cocok dengan dimensi status card
            if (innermostCard == null && isStatusCardDimensions(curr)) {
                innermostCard = curr
            }
            val parent = curr.parent as View
            curr = parent
        }
        val result = innermostCard ?: view
        logDebug("StatusLongPressPreview: Hierarchy chain: ${chain.joinToString(" -> ")} | Selected innermost card: ${result.javaClass.simpleName}(${result.width}x${result.height})")
        return result
    }

    private fun isMuteStatusDialog(text: String): Boolean {
        val lower = text.lowercase()
        val isMute = lower.contains("sembunyikan status") ||
                lower.contains("mute status") ||
                lower.contains("silenciar status") ||
                lower.contains("silenciar estados") ||
                (lower.contains("status") && (lower.contains("sembunyikan") || lower.contains("mute") || lower.contains("silenciar")))
        val isNotOtherDialog = !lower.contains("laporkan") && !lower.contains("report") && !lower.contains("hapus") && !lower.contains("delete")
        return isMute && isNotOtherDialog
    }

    private fun extractContactNameFromDialog(text: String): String {
        val patternIndo = Regex("""Sembunyikan status\s+(.+?)(?:\?|\||$)""", RegexOption.IGNORE_CASE)
        val matchIndo = patternIndo.find(text)
        if (matchIndo != null) return matchIndo.groupValues[1].trim()

        val patternEn = Regex("""Mute\s+(.+?)(?:'s)?\s+status""", RegexOption.IGNORE_CASE)
        val matchEn = patternEn.find(text)
        if (matchEn != null) return matchEn.groupValues[1].trim()

        return ""
    }

    private fun findMatchingStatusCard(activity: Activity, contactName: String): View? {
        val decor = activity.window?.decorView ?: return null
        val allRvs = findAllRecyclerViews(decor)
        for (rv in allRvs) {
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val textViews = findAllTextViews(child)
                for (tv in textViews) {
                    val t = tv.text?.toString() ?: ""
                    if (contactName.isNotEmpty() && (t.contains(contactName, ignoreCase = true) || contactName.contains(t, ignoreCase = true))) {
                        val card = findRootStatusCard(child)
                        logDebug("StatusLongPressPreview: findMatchingStatusCard matched card ${card.javaClass.simpleName}(${card.width}x${card.height}) with text='$t'")
                        return card
                    }
                }
            }
        }
        return null
    }

    private fun findAllRecyclerViews(view: View): List<RecyclerView> {
        val list = mutableListOf<RecyclerView>()
        fun check(v: View) {
            if (v is RecyclerView) {
                list.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    check(v.getChildAt(i))
                }
            }
        }
        check(view)
        return list
    }

    private fun findPositiveButton(view: View): View? {
        val allViews = findAllViews(view)
        for (v in allViews) {
            if (v is TextView) {
                val t = v.text?.toString()?.lowercase() ?: ""
                if (t == "sembunyikan" || t == "mute" || t == "silenciar") {
                    return v
                }
            }
        }
        return null
    }

    private fun findAllViews(view: View): List<View> {
        val result = mutableListOf<View>()
        fun collect(v: View) {
            result.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    collect(v.getChildAt(i))
                }
            }
        }
        collect(view)
        return result
    }

    private fun findAllImageViews(view: View): List<ImageView> {
        val result = mutableListOf<ImageView>()
        fun collect(v: View) {
            if (v is ImageView) {
                result.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    collect(v.getChildAt(i))
                }
            }
        }
        collect(view)
        return result
    }

    private fun findAllTextViews(view: View): List<TextView> {
        val result = mutableListOf<TextView>()
        fun collect(v: View) {
            if (v is TextView) {
                result.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    collect(v.getChildAt(i))
                }
            }
        }
        collect(view)
        return result
    }

    private fun captureViewBitmap(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Throwable) {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInPlaceStatusPreview(
        activity: Activity,
        cardView: View?,
        contactNameFallback: String,
        onMuteClicked: () -> Unit,
        onDismissed: () -> Unit
    ) {
        runCatching {
            cardView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        val dp = { value: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics).toInt()
        }

        val displayMetrics = activity.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Ukuran asli kartu di WhatsApp (1:1 persis seperti aslinya)
        val location = IntArray(2)
        val cardWidth: Int
        val cardHeight: Int
        val cardX: Int
        val cardY: Int

        if (cardView != null && cardView.width > 0 && cardView.height > 0) {
            cardView.getLocationOnScreen(location)
            cardX = location[0]
            cardY = location[1]
            cardWidth = cardView.width
            cardHeight = cardView.height
        } else {
            cardWidth = dp(76f)
            cardHeight = dp(135f)
            cardX = (screenWidth - cardWidth) / 2
            cardY = dp(160f)
        }

        // Ukuran tombol pill Sembunyikan
        val pillHeight = dp(38f)
        val pillWidth = cardWidth.coerceAtLeast(dp(130f))

        // Koreksi alignment X: kurangi offset setengah dari selisih lebar tombol agar kartu tepat 100% di koordinat cardX
        val groupLeftMargin = (cardX - (pillWidth - cardWidth) / 2).coerceIn(dp(6f), screenWidth - pillWidth - dp(6f))

        logDebug("StatusLongPressPreview: Showing iOS-style in-place preview at ($cardX, $cardY), groupLeft=$groupLeftMargin size=${cardWidth}x${cardHeight}")

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            
            // Native Hardware Background Blur di Android 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = dp(26f) // Frosted glass blur radius
                window.attributes = params
                window.setDimAmount(0.18f) // Minimal dimming untuk nuansa iOS glassmorphism
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.55f)
            }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Root Backdrop Fullscreen
        val rootLayout = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        // Ambil snapshot persis 1-to-1 dari card asli (foto, avatar, dan teks asli WhatsApp)
        val capturedBmp = cardView?.let { captureViewBitmap(it) }
        val snapshotDrawable = if (capturedBmp != null) {
            BitmapDrawable(activity.resources, capturedBmp)
        } else null

        // Container grup kartu + tombol di posisinya (In-Place iOS Haptic Touch)
        val groupContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = groupLeftMargin
                topMargin = cardY
            }
        }

        // 1. KARTU STATUS PERSIS 1:1 SEPERTI NATIVE WHATSAPP
        val cardContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
            }
            clipToOutline = true
        }

        if (snapshotDrawable != null) {
            val snapshotIv = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_XY
                setImageDrawable(snapshotDrawable)
            }
            cardContainer.addView(snapshotIv)
        } else if (cardView != null) {
            val imageViews = findAllImageViews(cardView)
            val thumbnailIv = imageViews.maxByOrNull { it.width * it.height }
            val avatarIv = imageViews.firstOrNull { it != thumbnailIv && it.width > 0 }
            val textViews = findAllTextViews(cardView)
            val nameText = textViews.firstOrNull { it.text?.isNotEmpty() == true }?.text?.toString() ?: contactNameFallback

            if (thumbnailIv?.drawable != null) {
                val bgIv = ImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(thumbnailIv.drawable.constantState?.newDrawable() ?: thumbnailIv.drawable)
                }
                cardContainer.addView(bgIv)
            }

            if (avatarIv?.drawable != null) {
                val avIv = ImageView(activity).apply {
                    val avSize = dp(30f)
                    layoutParams = FrameLayout.LayoutParams(avSize, avSize).apply {
                        gravity = Gravity.TOP or Gravity.START
                        setMargins(dp(6f), dp(6f), 0, 0)
                    }
                    setImageDrawable(avatarIv.drawable.constantState?.newDrawable() ?: avatarIv.drawable)
                }
                cardContainer.addView(avIv)
            }

            val nameTv = TextView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(6f)
                }
                text = nameText
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            cardContainer.addView(nameTv)
        } else {
            val nameTv = TextView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(6f)
                }
                text = contactNameFallback
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
            cardContainer.addView(nameTv)
        }

        groupContainer.addView(cardContainer)

        // 2. TOMBOL PILL "Sembunyikan" TEPAT DI BAWAH KARTU (iOS Action Pill)
        val pillButton = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(pillWidth, pillHeight).apply {
                topMargin = dp(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#E6202024")) // Frosted obsidian glass
                setStroke(dp(1f), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(12f), 0, dp(12f), 0)
            isClickable = true
            isFocusable = true

            // Teks Label "Sembunyikan" / "Mute" (Multi-language)
            val labelTv = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = getLocalizedMuteLabel()
                setTextColor(Color.parseColor("#F2F2F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            // Icon Mute / Eye-slash
            val iconIv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(17f), dp(17f))
                val iconDrawable = ContextCompat.getDrawable(Utils.application, R.drawable.eye_disabled)
                    ?: DesignUtils.getIconByName("ic_action_mute", false)
                if (iconDrawable != null) {
                    val colored = DesignUtils.coloredDrawable(iconDrawable.mutate(), Color.parseColor("#D1D1D6"))
                    setImageDrawable(colored)
                }
            }

            addView(labelTv)
            addView(iconIv)

            setOnClickListener {
                runCatching {
                    cardView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                dialog.dismiss()
                onMuteClicked()
            }
        }

        groupContainer.addView(pillButton)
        rootLayout.addView(groupContainer)

        // Dismiss saat tap di luar area tombol/kartu
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                dialog.dismiss()
                onDismissed()
                true
            } else {
                false
            }
        }

        dialog.setContentView(rootLayout)

        // Animasi iOS Spring Pop: terangkat sedikit (-8dp) dan membesar mikro (1.04x)
        groupContainer.alpha = 0f
        groupContainer.translationY = dp(4f).toFloat()
        groupContainer.scaleX = 0.98f
        groupContainer.scaleY = 0.98f

        isShowingCustomPreview = true
        dialog.setOnDismissListener {
            isShowingCustomPreview = false
        }

        dialog.show()

        val groupAlpha = ObjectAnimator.ofFloat(groupContainer, "alpha", 0f, 1.0f)
        val groupLift = ObjectAnimator.ofFloat(groupContainer, "translationY", dp(4f).toFloat(), dp(-8f).toFloat())
        val groupScaleX = ObjectAnimator.ofFloat(groupContainer, "scaleX", 0.98f, 1.04f)
        val groupScaleY = ObjectAnimator.ofFloat(groupContainer, "scaleY", 0.98f, 1.04f)

        AnimatorSet().apply {
            playTogether(groupAlpha, groupLift, groupScaleX, groupScaleY)
            duration = 200
            interpolator = OvershootInterpolator(1.1f)
            start()
        }
    }

    private fun getLocalizedMuteLabel(): String {
        val lang = java.util.Locale.getDefault().language
        return when (lang) {
            "in", "id" -> "Sembunyikan"
            "es" -> "Silenciar"
            "pt" -> "Silenciar"
            "ru" -> "Без звука"
            "ar" -> "كتم"
            "de" -> "Stummschalten"
            "fr" -> "Masquer"
            "it" -> "Disattiva"
            "tr" -> "Sessize al"
            "zh" -> "静音"
            "iw", "he" -> "השתק"
            else -> "Mute"
        }
    }
}
