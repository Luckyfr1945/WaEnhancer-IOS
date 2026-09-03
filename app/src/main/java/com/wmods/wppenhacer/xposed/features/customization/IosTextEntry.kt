package com.wmods.wppenhacer.xposed.features.customization

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.utils.Utils

class IosTextEntry(loader: ClassLoader, prefs: SharedPreferences) : Feature(loader, prefs) {

    private var plusBitmap: Bitmap? = null
    private var plusBitmapColor: Int? = null
    private var plusDrawable: BitmapDrawable? = null

    private var stickerBitmap: Bitmap? = null
    private var stickerBitmapColor: Int? = null
    private var stickerDrawable: BitmapDrawable? = null

    companion object {
        // Tag keys for idempotency and resource state tracking
        private const val TAG_GLOBAL_LAYOUT_ADDED = 0x7E110005
        private const val TAG_LISTENERS_SWAPPED = 0x7E1100F3
        private const val TAG_ORIGINAL_EMOJI_LISTENER = 0x7E1100F4
        private const val TAG_ORIGINAL_ATTACH_LISTENER = 0x7E1100F5
        private const val TAG_PLUS_DRAWABLE_SET = 0x7E1100F6

        // Cached Resource IDs (resolved once)
        private val ID_ENTRY by lazy { Utils.getID("entry", "id") }
        private val ID_TEXT_ENTRY_LAYOUT by lazy { Utils.getID("text_entry_layout", "id") }
        private val ID_EDIT_LAYOUT by lazy { Utils.getID("edit_layout", "id") }
        private val ID_INPUT_LAYOUT_CONTENT by lazy { Utils.getID("input_layout_content", "id") }
        private val ID_INPUT_LAYOUT by lazy { Utils.getID("input_layout", "id") }
        private val ID_EMOJI_PICKER_BTN by lazy { Utils.getID("emoji_picker_btn", "id") }
        private val ID_INPUT_ATTACH_BUTTON by lazy { Utils.getID("input_attach_button", "id") }
        private val ID_CAMERA_BTN by lazy { Utils.getID("camera_btn", "id") }
        private val ID_BUTTONS by lazy { Utils.getID("buttons", "id") }

        // Semua nilai "rasa" iOS dikumpulkan di satu tempat biar gampang di-tuning
        // dan tidak ada drift antara restructureInput() dan listener dinamis.
        private const val PILL_RADIUS_DP = 22f
        private const val PILL_STROKE_DP = 1f
        // Fallback kalau lebar tombol belum sempat terukur (width == 0) saat layout pertama.
        private const val BTN_FALLBACK_DP = 44f
        // Jarak "napas" antara tombol +/kamera dengan tepi pill.
        private const val PILL_GAP_DP = 4f
        // Jarak antara pill dan tombol mic/send di sebelah kanan.
        private const val MIC_GAP_DP = -11f

        private const val PILL_DARK = "#1F2C34"
        private const val PILL_LIGHT = "#FFFFFF"
        private const val PILL_BORDER_DARK = "#2A3942"
        private const val PILL_BORDER_LIGHT = "#E9EDEF"
        private const val PLUS_DARK = "#8696A0"
        private const val PLUS_LIGHT = "#54656F"
        private const val BAR_DARK = "#0B141B"
        private const val BAR_LIGHT = "#F0F2F5"
    }

    override fun doHook() {
        if (!prefs.getBoolean("ios_text_entry", false)) return

        logDebug("IosTextEntry Feature Enabled")

        com.wmods.wppenhacer.xposed.core.WppCore.addListenerActivity { activity, type ->
            if (type == com.wmods.wppenhacer.xposed.core.WppCore.ActivityChangeState.ChangeType.RESUMED) {
                logDebug("IosTextEntry: Resumed ${activity.javaClass.name}")

                val rootView = activity.window.decorView.rootView
                if (rootView.getTag(TAG_GLOBAL_LAYOUT_ADDED) == true) return@addListenerActivity
                rootView.setTag(TAG_GLOBAL_LAYOUT_ADDED, true)
                
                val layoutListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    private var cachedEntry: View? = null
                    private var cachedTextEntryLayout: ViewGroup? = null
                    private var cachedEditLayout: ViewGroup? = null
                    private var cachedEmojiBtn: ImageView? = null
                    private var cachedInputContent: ViewGroup? = null

                    override fun onGlobalLayout() {
                        try {
                            if (ID_ENTRY <= 0 || ID_TEXT_ENTRY_LAYOUT <= 0) return

                            if (cachedEntry == null) cachedEntry = rootView.findViewById<View>(ID_ENTRY)
                            val entry = cachedEntry ?: return

                            if (cachedTextEntryLayout == null) cachedTextEntryLayout = rootView.findViewById<ViewGroup>(ID_TEXT_ENTRY_LAYOUT)
                            val textEntryLayout = cachedTextEntryLayout ?: return

                            if (cachedEditLayout == null) {
                                cachedEditLayout = if (ID_EDIT_LAYOUT > 0) rootView.findViewById<ViewGroup>(ID_EDIT_LAYOUT) else textEntryLayout.parent as? ViewGroup
                            }
                            val editLayout = cachedEditLayout

                            if (cachedInputContent == null && ID_INPUT_LAYOUT_CONTENT > 0) cachedInputContent = rootView.findViewById<ViewGroup>(ID_INPUT_LAYOUT_CONTENT)
                            val inputContent = cachedInputContent

                            val isStyled = textEntryLayout.tag == "ios_styled"
                            val pillExists = inputContent?.findViewWithTag<View>("ios_pill") != null

                            // Full restructure ONLY if not yet styled or if pill was removed/rebuilt by WhatsApp
                            if (!isStyled || !pillExists) {
                                textEntryLayout.tag = "ios_styled"
                                logDebug("IosTextEntry: Applying iOS style")
                                restructureInput(entry, textEntryLayout, editLayout)
                                return
                            }

                            // Lightweight maintenance of the + icon and sticker icon (always maintain iOS icons after picker closed/opened)
                            if (cachedEmojiBtn == null && ID_EMOJI_PICKER_BTN > 0) cachedEmojiBtn = rootView.findViewById<ImageView>(ID_EMOJI_PICKER_BTN)
                            val emojiBtn = cachedEmojiBtn
                            if (emojiBtn != null) {
                                val ctx = emojiBtn.context
                                val isDark = isDarkMode(ctx)
                                val plusColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                                if (plusDrawable == null || plusBitmapColor != plusColor) {
                                    plusBitmapColor = plusColor
                                    plusBitmap?.recycle()
                                    plusBitmap = createPlusDrawable(plusColor)
                                    plusDrawable = BitmapDrawable(ctx.resources, plusBitmap)
                                }
                                if (emojiBtn.drawable !== plusDrawable) {
                                    emojiBtn.setImageDrawable(plusDrawable)
                                    emojiBtn.imageTintList = null
                                    emojiBtn.background = null
                                }
                            }

                            val attachBtn = if (ID_INPUT_ATTACH_BUTTON > 0) textEntryLayout.findViewById<ImageView>(ID_INPUT_ATTACH_BUTTON) else null
                            if (attachBtn != null) {
                                val ctx = attachBtn.context
                                val isDark = isDarkMode(ctx)
                                val stickerColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                                if (stickerDrawable == null || stickerBitmapColor != stickerColor) {
                                    stickerBitmapColor = stickerColor
                                    stickerBitmap?.recycle()
                                    stickerBitmap = createStickerDrawable(stickerColor)
                                    stickerDrawable = BitmapDrawable(ctx.resources, stickerBitmap)
                                }
                                if (attachBtn.drawable !== stickerDrawable) {
                                    attachBtn.setImageDrawable(stickerDrawable)
                                    attachBtn.imageTintList = null
                                    attachBtn.background = null
                                }
                            }
                        } catch (e: Exception) {
                            logDebug("IosTextEntry Layout error: ${e.message}")
                        }
                    }
                }

                rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                rootView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        try {
                            v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                            v.removeOnAttachStateChangeListener(this)
                            v.setTag(TAG_GLOBAL_LAYOUT_ADDED, null)
                        } catch (_: Throwable) {}
                    }
                })
            }
        }
    }

    private fun isDarkMode(ctx: android.content.Context): Boolean {
        return (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** Satu-satunya tempat pembuatan pill drawable, dipakai baik saat restructure maupun update dinamis. */
    private fun buildPillDrawable(isDark: Boolean): GradientDrawable {
        val pillColor = if (isDark) Color.parseColor(PILL_DARK) else Color.parseColor(PILL_LIGHT)
        val strokeColor = if (isDark) Color.parseColor(PILL_BORDER_DARK) else Color.parseColor(PILL_BORDER_LIGHT)
        return GradientDrawable().apply {
            setColor(pillColor)
            cornerRadius = Utils.dipToPixels(PILL_RADIUS_DP).toFloat()
            setStroke(Utils.dipToPixels(PILL_STROKE_DP), strokeColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun restructureInput(entry: View, textEntryLayout: ViewGroup, editLayout: ViewGroup?) {
        try {
            val ctx = textEntryLayout.context
            val isDark = isDarkMode(ctx)

            // ── 1. Solid bottom bar ──
            if (editLayout != null) {
                val barColor = if (isDark) Color.parseColor(BAR_DARK) else Color.parseColor(BAR_LIGHT)
                editLayout.background = ColorDrawable(barColor)
                editLayout.elevation = 0f
                editLayout.setPadding(0, Utils.dipToPixels(6f), 0, Utils.dipToPixels(6f))
            }

            // ── 2. Strip WhatsApp bubble from text_entry_layout ──
            textEntryLayout.background = null
            textEntryLayout.elevation = 0f
            val telLp = textEntryLayout.layoutParams
            if (telLp is ViewGroup.MarginLayoutParams) {
                val rightMargin = if (MIC_GAP_DP < 0) Utils.dipToPixels(-MIC_GAP_DP) else 0
                telLp.setMargins(Utils.dipToPixels(6f), 0, rightMargin, 0)
                textEntryLayout.layoutParams = telLp
            }

            // ── 3. Strip input_layout background ──
            if (ID_INPUT_LAYOUT > 0) {
                textEntryLayout.findViewById<View>(ID_INPUT_LAYOUT)?.background = null
            }

            // ── 4. Find buttons ──
            val emojiBtn = if (ID_EMOJI_PICKER_BTN > 0) textEntryLayout.findViewById<ImageView>(ID_EMOJI_PICKER_BTN) else null
            val attachBtn = if (ID_INPUT_ATTACH_BUTTON > 0) textEntryLayout.findViewById<ImageView>(ID_INPUT_ATTACH_BUTTON) else null
            val cameraBtn = if (ID_CAMERA_BTN > 0) textEntryLayout.findViewById<View>(ID_CAMERA_BTN) else null

            // ── 5. Icon swap + function swap (idempotent with tags) ──
            if (emojiBtn != null && attachBtn != null) {
                val plusColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                if (plusDrawable == null || plusBitmapColor != plusColor) {
                    plusBitmapColor = plusColor
                    plusBitmap?.recycle()
                    plusBitmap = createPlusDrawable(plusColor)
                    plusDrawable = BitmapDrawable(ctx.resources, plusBitmap)
                }
                emojiBtn.setImageDrawable(plusDrawable)
                emojiBtn.scaleType = ImageView.ScaleType.CENTER
                emojiBtn.background = null
                emojiBtn.imageTintList = null

                val stickerColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                if (stickerDrawable == null || stickerBitmapColor != stickerColor) {
                    stickerBitmapColor = stickerColor
                    stickerBitmap?.recycle()
                    stickerBitmap = createStickerDrawable(stickerColor)
                    stickerDrawable = BitmapDrawable(ctx.resources, stickerBitmap)
                }
                attachBtn.setImageDrawable(stickerDrawable)
                attachBtn.scaleType = ImageView.ScaleType.CENTER
                attachBtn.background = null
                attachBtn.imageTintList = null

                val alreadySwapped = emojiBtn.getTag(TAG_LISTENERS_SWAPPED) == true
                if (!alreadySwapped) {
                    val emojiClickListener = getClickListenerXposed(emojiBtn)
                    val attachClickListener = getClickListenerXposed(attachBtn)
                    if (emojiClickListener != null) emojiBtn.setTag(TAG_ORIGINAL_EMOJI_LISTENER, emojiClickListener)
                    if (attachClickListener != null) attachBtn.setTag(TAG_ORIGINAL_ATTACH_LISTENER, attachClickListener)

                    val origEmoji = (emojiBtn.getTag(TAG_ORIGINAL_EMOJI_LISTENER) as? View.OnClickListener) ?: emojiClickListener
                    val origAttach = (attachBtn.getTag(TAG_ORIGINAL_ATTACH_LISTENER) as? View.OnClickListener) ?: attachClickListener

                    if (origEmoji != null && origAttach != null) {
                        emojiBtn.setOnClickListener { _ -> origAttach.onClick(attachBtn) }
                        attachBtn.setOnClickListener { _ -> origEmoji.onClick(emojiBtn) }
                        emojiBtn.setTag(TAG_LISTENERS_SWAPPED, true)
                        attachBtn.setTag(TAG_LISTENERS_SWAPPED, true)
                        logDebug("IosTextEntry: Click listeners swapped successfully")
                    } else {
                        logDebug("IosTextEntry: Click listener swap falling back to callOnClick")
                        val swapFlag = booleanArrayOf(false)
                        emojiBtn.setOnClickListener {
                            if (!swapFlag[0]) {
                                swapFlag[0] = true
                                attachBtn.callOnClick()
                                swapFlag[0] = false
                            }
                        }
                        attachBtn.setOnClickListener {
                            if (!swapFlag[0]) {
                                swapFlag[0] = true
                                emojiBtn.callOnClick()
                                swapFlag[0] = false
                            }
                        }
                        emojiBtn.setTag(TAG_LISTENERS_SWAPPED, true)
                        attachBtn.setTag(TAG_LISTENERS_SWAPPED, true)
                    }
                }
            }

            // Camera — just strip background
            cameraBtn?.background = null

            // ── 6. Pill background dengan membungkus entry dan sticker ──
            val inputContent = (if (ID_INPUT_LAYOUT_CONTENT > 0) textEntryLayout.findViewById<ViewGroup>(ID_INPUT_LAYOUT_CONTENT) else null)
                ?: (if (ID_INPUT_LAYOUT > 0) textEntryLayout.findViewById<ViewGroup>(ID_INPUT_LAYOUT) else null)
                ?: (entry.parent as? ViewGroup)

            if (inputContent != null && attachBtn != null) {
                inputContent.background = null
                val entryIndex = if (entry.parent == inputContent) inputContent.indexOfChild(entry) else 0

                try {
                    val existingPill = inputContent.findViewWithTag<View>("ios_pill")
                    if (existingPill == null && entry.parent != null) {
                        val pillContainer = android.widget.LinearLayout(ctx).apply {
                            tag = "ios_pill"
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                0, 
                                ViewGroup.LayoutParams.WRAP_CONTENT, 
                                1f
                            ).apply {
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            background = buildPillDrawable(isDark)
                            setPadding(0, Utils.dipToPixels(2f), Utils.dipToPixels(4f), Utils.dipToPixels(2f))
                        }

                        (entry.parent as? ViewGroup)?.removeView(entry)
                        (attachBtn.parent as? ViewGroup)?.removeView(attachBtn)

                        entry.layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, 
                            ViewGroup.LayoutParams.WRAP_CONTENT, 
                            1f
                        )
                        
                        attachBtn.layoutParams = android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, 
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        
                        pillContainer.addView(entry)
                        pillContainer.addView(attachBtn)
                        inputContent.addView(pillContainer, entryIndex.coerceAtLeast(0))
                    }
                } catch (e: Throwable) {
                    logDebug("IosTextEntry: Pill wrap failed: ${e.message}")
                }
            }

            // ── 7. Entry styling — add left padding so text stays inside pill ──
            entry.background = null
            entry.setPadding(
                Utils.dipToPixels(12f),
                entry.paddingTop,
                entry.paddingRight,
                entry.paddingBottom
            )
        } catch (e: Exception) {
            logDebug("IosTextEntry CSS error: ${e.message}")
        }
    }

    /** Get OnClickListener from a View via XposedHelpers (more reliable) */
    private fun getClickListenerXposed(view: View): View.OnClickListener? {
        return try {
            val listenerInfo = de.robv.android.xposed.XposedHelpers.getObjectField(view, "mListenerInfo")
            if (listenerInfo != null) {
                de.robv.android.xposed.XposedHelpers.getObjectField(listenerInfo, "mOnClickListener") as? View.OnClickListener
            } else {
                logDebug("IosTextEntry: mListenerInfo is null for ${view.javaClass.simpleName}")
                null
            }
        } catch (e: Exception) {
            logDebug("getClickListenerXposed error: ${e.message}")
            null
        }
    }

    /** Create a clean "+" bitmap icon */
    private fun createPlusDrawable(color: Int): Bitmap {
        val size = Utils.dipToPixels(28f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = Utils.dipToPixels(2.5f).toFloat()
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val cx = size / 2f
        val cy = size / 2f
        val arm = size * 0.32f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, paint) // horizontal
        canvas.drawLine(cx, cy - arm, cx, cy + arm, paint) // vertical
        return bitmap
    }

    /** Create a sticker icon (rounded square with folded corner) */
    private fun createStickerDrawable(color: Int): Bitmap {
        val size = Utils.dipToPixels(24f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = Utils.dipToPixels(1.5f).toFloat()
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }

        val m = size * 0.1f
        val fold = size * 0.25f
        val r = size * 0.18f

        val path = Path()
        path.moveTo(m + r, m)
        path.lineTo(size - m - r, m)
        path.arcTo(RectF(size - m - 2 * r, m, size - m, m + 2 * r), -90f, 90f, false)
        path.lineTo(size - m, size - m - fold)
        path.lineTo(size - m - fold, size - m)
        path.lineTo(m + r, size - m)
        path.arcTo(RectF(m, size - m - 2 * r, m + 2 * r, size - m), 90f, 90f, false)
        path.lineTo(m, m + r)
        path.arcTo(RectF(m, m, m + 2 * r, m + 2 * r), 180f, 90f, false)
        canvas.drawPath(path, paint)

        canvas.drawLine(size - m - fold, size - m, size - m - fold, size - m - fold, paint)
        canvas.drawLine(size - m - fold, size - m - fold, size - m, size - m - fold, paint)

        return bitmap
    }

    override fun getPluginName(): String {
        return "iOS Text Entry"
    }
}