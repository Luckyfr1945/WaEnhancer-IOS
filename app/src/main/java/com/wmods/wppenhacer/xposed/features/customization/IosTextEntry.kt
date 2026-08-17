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

    companion object {
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

        private const val PILL_DARK = "#2C2C2E"
        private const val PILL_LIGHT = "#FFFFFF"
        private const val PILL_BORDER_LIGHT = "#C7C7CC"
        private const val PLUS_DARK = "#A0A0A5"
        private const val PLUS_LIGHT = "#8E8E93"
        private const val MIC_BLUE = "#007AFF"
        private const val BAR_DARK = "#1C1C1E"
        private const val BAR_LIGHT = "#F2F2F7"
    }

    override fun doHook() {
        if (!prefs.getBoolean("ios_text_entry", false)) return

        logDebug("IosTextEntry Feature Enabled")

        com.wmods.wppenhacer.xposed.core.WppCore.addListenerActivity { activity, type ->
            if (type == com.wmods.wppenhacer.xposed.core.WppCore.ActivityChangeState.ChangeType.RESUMED) {
                logDebug("IosTextEntry: Resumed ${activity.javaClass.name}")

                val rootView = activity.window.decorView.rootView
                val TAG_GLOBAL_LAYOUT_ADDED = 0x7E110005
                if (rootView.getTag(TAG_GLOBAL_LAYOUT_ADDED) == true) return@addListenerActivity
                rootView.setTag(TAG_GLOBAL_LAYOUT_ADDED, true)
                
                rootView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    private var cachedEntry: View? = null
                    private var cachedTextEntryLayout: ViewGroup? = null
                    private var cachedEditLayout: ViewGroup? = null
                    private var cachedEmojiBtn: ImageView? = null
                    private var cachedInputContent: ViewGroup? = null
                    private var cachedCameraBtn: View? = null
                    private var cachedInputLayout: View? = null
                    private var cachedButtonsFrame: ViewGroup? = null
                    private var cachedVoiceBtn: View? = null

                    override fun onGlobalLayout() {
                        try {
                            val entryId = Utils.getID("entry", "id")
                            if (entryId <= 0) return

                            if (cachedEntry == null) cachedEntry = rootView.findViewById<View>(entryId)
                            val entry = cachedEntry ?: return

                            val textEntryLayoutId = Utils.getID("text_entry_layout", "id")
                            if (textEntryLayoutId <= 0) return
                            if (cachedTextEntryLayout == null) cachedTextEntryLayout = rootView.findViewById<ViewGroup>(textEntryLayoutId)
                            val textEntryLayout = cachedTextEntryLayout ?: return

                            val editLayoutId = Utils.getID("edit_layout", "id")
                            if (cachedEditLayout == null) {
                                cachedEditLayout = if (editLayoutId > 0) rootView.findViewById<ViewGroup>(editLayoutId) else textEntryLayout.parent as? ViewGroup
                            }
                            val editLayout = cachedEditLayout

                        // Full restructure only once
                        if (textEntryLayout.tag != "ios_styled") {
                            textEntryLayout.tag = "ios_styled"
                            logDebug("IosTextEntry: Applying iOS style")
                            restructureInput(entry, textEntryLayout, editLayout)
                        }

                        // Always maintain the + icon (WhatsApp overwrites it when emoji panel opens)
                        val emojiId = Utils.getID("emoji_picker_btn", "id")
                        if (cachedEmojiBtn == null && emojiId > 0) cachedEmojiBtn = rootView.findViewById<ImageView>(emojiId)
                        val emojiBtn = cachedEmojiBtn
                        if (emojiBtn != null && plusBitmap != null) {
                            val currentBmp = (emojiBtn.drawable as? BitmapDrawable)?.bitmap
                            if (currentBmp !== plusBitmap) {
                                emojiBtn.setImageDrawable(BitmapDrawable(entry.resources, plusBitmap))
                                emojiBtn.imageTintList = null
                            }
                        }

                        // Dynamically update pill background based on real button widths
                        val inputContentId = Utils.getID("input_layout_content", "id")
                        if (cachedInputContent == null && inputContentId > 0) cachedInputContent = rootView.findViewById<ViewGroup>(inputContentId)
                        val inputContent = cachedInputContent
                        
                        val cameraId = Utils.getID("camera_btn", "id")
                        if (cachedCameraBtn == null && cameraId > 0) cachedCameraBtn = rootView.findViewById<View>(cameraId)
                        val cameraBtn = cachedCameraBtn

                        if (inputContent != null) {
                            val cameraVisible = cameraBtn != null && cameraBtn.visibility == View.VISIBLE
                            val gap = Utils.dipToPixels(PILL_GAP_DP)
                            val fallback = Utils.dipToPixels(BTN_FALLBACK_DP)

                            // Gunakan lebar view + margin (jika ada) + gap. 
                            // getLocationInWindow menyebabkan infinite relayout loop (ngeblink)
                            // karena merubah background juga sedikit menggeser window coordinates.
                            var plusWidth = fallback
                            if (emojiBtn != null && emojiBtn.width > 0) {
                                plusWidth = emojiBtn.width
                                val lp = emojiBtn.layoutParams as? ViewGroup.MarginLayoutParams
                                if (lp != null) plusWidth += lp.leftMargin + lp.rightMargin
                            }
                            
                            var cameraWidth = fallback
                            if (cameraBtn != null && cameraBtn.width > 0) {
                                cameraWidth = cameraBtn.width
                                val lp = cameraBtn.layoutParams as? ViewGroup.MarginLayoutParams
                                if (lp != null) cameraWidth += lp.leftMargin + lp.rightMargin
                            }

                            // Only strip backgrounds if they are actually non-null to prevent layout invalidation loops
                            if (textEntryLayout.background != null) textEntryLayout.background = null

                            val inputLayoutId = Utils.getID("input_layout", "id")
                            if (cachedInputLayout == null && inputLayoutId > 0) {
                                cachedInputLayout = rootView.findViewById<View>(inputLayoutId)
                            }
                            if (cachedInputLayout?.background != null) cachedInputLayout?.background = null
                            if (cameraBtn?.background != null) cameraBtn?.background = null
                            
                            val buttonsId2 = Utils.getID("buttons", "id")
                            if (cachedButtonsFrame == null && buttonsId2 > 0) {
                                cachedButtonsFrame = editLayout?.findViewById<ViewGroup>(buttonsId2)
                            }
                            if (cachedButtonsFrame?.background != null) cachedButtonsFrame?.background = null
                            
                            val voiceNoteId = Utils.getID("voice_note_btn", "id")
                            if (cachedVoiceBtn == null && voiceNoteId > 0) {
                                cachedVoiceBtn = rootView.findViewById<View>(voiceNoteId)
                            }
                            if (cachedVoiceBtn?.background != null) cachedVoiceBtn?.background = null

                            // Jaga jarak konsisten antara pill dan tombol mic/send di kanan.
                            val buttonsFrame = cachedButtonsFrame
                            val bfLp = buttonsFrame?.layoutParams
                            if (bfLp is ViewGroup.MarginLayoutParams) {
                                val micGap = Utils.dipToPixels(MIC_GAP_DP)
                                if (bfLp.marginStart != micGap) {
                                    bfLp.marginStart = micGap
                                    buttonsFrame.layoutParams = bfLp
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logDebug("IosTextEntry Layout error: ${e.message}")
                    }
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
        return GradientDrawable().apply {
            setColor(pillColor)
            cornerRadius = Utils.dipToPixels(PILL_RADIUS_DP).toFloat()
            if (!isDark) {
                setStroke(Utils.dipToPixels(PILL_STROKE_DP), Color.parseColor(PILL_BORDER_LIGHT))
            }
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
            val inputLayoutId = Utils.getID("input_layout", "id")
            textEntryLayout.findViewById<View>(inputLayoutId)?.background = null

            // ── 4. Find buttons ──
            val emojiId = Utils.getID("emoji_picker_btn", "id")
            val emojiBtn = textEntryLayout.findViewById<ImageView>(emojiId)

            val attachId = Utils.getID("input_attach_button", "id")
            val attachBtn = textEntryLayout.findViewById<ImageView>(attachId)

            val cameraId = Utils.getID("camera_btn", "id")
            val cameraBtn = textEntryLayout.findViewById<View>(cameraId)

            // ── 5. Icon swap + function swap ──
            if (emojiBtn != null && attachBtn != null) {
                val emojiClickListener = getClickListenerXposed(emojiBtn)
                val attachClickListener = getClickListenerXposed(attachBtn)
                logDebug("IosTextEntry: emojiListener=${emojiClickListener != null}, attachListener=${attachClickListener != null}")

                val plusColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                plusBitmap = createPlusDrawable(plusColor)
                emojiBtn.setImageDrawable(BitmapDrawable(ctx.resources, plusBitmap))
                emojiBtn.scaleType = ImageView.ScaleType.CENTER
                emojiBtn.background = null
                emojiBtn.imageTintList = null

                val stickerColor = if (isDark) Color.parseColor(PLUS_DARK) else Color.parseColor(PLUS_LIGHT)
                val stickerBitmap = createStickerDrawable(stickerColor)
                attachBtn.setImageDrawable(BitmapDrawable(ctx.resources, stickerBitmap))
                attachBtn.scaleType = ImageView.ScaleType.CENTER
                attachBtn.visibility = View.VISIBLE
                attachBtn.background = null
                attachBtn.imageTintList = null

                if (emojiClickListener != null && attachClickListener != null) {
                    emojiBtn.setOnClickListener { _ -> attachClickListener.onClick(attachBtn) }
                    attachBtn.setOnClickListener { _ -> emojiClickListener.onClick(emojiBtn) }
                    logDebug("IosTextEntry: Click listeners swapped successfully")
                } else {
                    logDebug("IosTextEntry: Click listener swap FAILED, falling back to performClick")
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
                }
            }

            // Camera — just strip background
            cameraBtn?.background = null

            // ── 6. Pill background dengan membungkus entry dan sticker ──
            val inputContentId = Utils.getID("input_layout_content", "id")
            val inputContent = textEntryLayout.findViewById<ViewGroup>(inputContentId)
            if (inputContent != null && entry.parent == inputContent && attachBtn != null && attachBtn.parent == inputContent) {
                inputContent.background = null
                val entryIndex = inputContent.indexOfChild(entry)
                inputContent.removeView(entry)
                inputContent.removeView(attachBtn)

                val pillContainer = android.widget.LinearLayout(ctx).apply {
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

                // Harus buat ulang LayoutParams supaya entry benar-benar expand 
                // dan sticker tetap di sebelah kanan!
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
                inputContent.addView(pillContainer, entryIndex)
            }

            // ── 7. Entry styling — add left padding so text stays inside pill ──
            entry.background = null
            entry.setPadding(
                Utils.dipToPixels(12f),
                entry.paddingTop,
                entry.paddingRight,
                entry.paddingBottom
            )

            // ── 8. Voice Note and Send Button — keep native WhatsApp styling ──
            val buttonsId = Utils.getID("buttons", "id")
            val buttonsFrame = editLayout?.findViewById<ViewGroup>(buttonsId)
            if (buttonsFrame != null) {
                // Remove padding adjustment to let native WhatsApp handle it
            }
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