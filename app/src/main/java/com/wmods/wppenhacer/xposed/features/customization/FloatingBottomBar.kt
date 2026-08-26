package com.wmods.wppenhacer.xposed.features.customization

import android.view.ViewParent
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.ModuleContextWrapper
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import eightbitlab.com.blurview.BlurView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Floating Bottom Bar inspired by KernelSU & MIUIX Liquid Glass Navigation Bar.
 *
 * Features:
 * - Floating Capsule Geometry with customizable corner radius & margins
 * - High-performance Frosted Glass Blur (BlurView with multi-layer specular glass)
 * - Translucent Spotlight Pill Indicator with 3-layer drop shadow & crystalline rim
 * - Silky smooth 120 Hz Spring Physics Engine (Damped Drag & Scrubbing)
 * - Automatic Tab Reordering & Native Indicator clean-up
 * - Zero-lag, battery-efficient event-driven architecture
 */
class FloatingBottomBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val CORNER_RADIUS_DP = 32f
        private const val SIDE_MARGIN_DP = 16f
        private const val BOTTOM_MARGIN_DP = 16f
        private const val ELEVATION_DP = 10f
        private const val FAB_GAP_DP = 12f
        private val FAB_RESOURCE_NAMES = arrayOf(
            "fab",
            "fab_second",
            "fab_auxiliary",
            "extended_mini_fab",
            "status_btn",
            "call_btn",
            "new_chat_btn",
            "camera_fab",
            "text_status_fab"
        )

        // Unique tag keys
        private const val TAG_FLOATING_WRAPPER = 0x46_42_41_52 // 'FBAR'
        private const val TAG_BACKDROP = 0x46_42_42_44 // 'FBBD'
        private const val TAG_FAB_OFFSET = 0x46_41_42_4F // 'FABO'
        private const val TAG_ITEM_INITIALIZED = 0x46_42_49_49 // 'FBII'

        // Visual & Physics Parameters
        private const val BAR_HEIGHT_DP = 66f
        private const val BAR_PADDING_DP = 4f
        private const val INDICATOR_INSET_DP = 4f
        private const val INDICATOR_WIDTH_RATIO = 0.78f
        private const val BLUR_RADIUS = 4f
        private const val PRESSED_SCALE = 1.08f
        private const val RUBBER_BAND_DP = 4f
    }

    private val processedBars = WeakHashMap<ViewGroup, Boolean>()
    private val setupAttempts = WeakHashMap<ViewGroup, Int>()
    private val barStates = WeakHashMap<ViewGroup, BarState>()

    /**
     * Pure Glass Spotlight Pill Indicator:
     * - Pure translucent glass capsule
     * - Multi-layer soft drop shadow
     * - Crisp crystalline 1.2dp border stroke
     * - Silky smooth spring physics & interactive squash/stretch
     */
    private class LiquidIndicatorDrawable(
        private val fillColor: Int,
        private val shadowColor: Int,
        private val strokeColor: Int
    ) : Drawable() {

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Utils.dipToPixels(1.2f).toFloat()
        }
        private val rect = RectF()

        var centerX = 0f
        var halfWidth = 0f
        var top = 0f
        var bottom = 0f
        var scaleX = 1f
        var scaleY = 1f
        var active = false
        var pressProgress = 0f

        override fun draw(canvas: Canvas) {
            if (!active || halfWidth <= 0f || bottom <= top) return

            val centerY = (top + bottom) * 0.5f
            val expansion = 1f + 0.10f * pressProgress
            val halfW = halfWidth * scaleX * expansion
            val halfH = (bottom - top) * 0.5f * scaleY * expansion
            val corner = halfH.coerceAtMost(halfW)

            // 1. Soft multi-layer drop shadow
            shadowPaint.color = shadowColor
            val baseAlpha = Color.alpha(shadowColor)
            for (i in SHADOW_SPREAD.indices) {
                val grow = Utils.dipToPixels(6f) * SHADOW_SPREAD[i]
                shadowPaint.alpha = (baseAlpha * SHADOW_ALPHA[i]).toInt().coerceIn(0, 255)
                val drop = grow * 0.35f
                rect.set(
                    centerX - halfW - grow,
                    centerY - halfH - grow + drop,
                    centerX + halfW + grow,
                    centerY + halfH + grow + drop
                )
                canvas.drawRoundRect(rect, corner + grow, corner + grow, shadowPaint)
            }

            // 2. Pure Translucent Glass Body (No fake gradients)
            bodyPaint.color = fillColor
            bodyPaint.alpha = (Color.alpha(fillColor) * (1f - pressProgress * 0.15f)).toInt().coerceIn(0, 255)
            rect.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
            canvas.drawRoundRect(rect, corner, corner, bodyPaint)

            // 3. Crisp Crystalline Glass Rim Stroke
            val rimAlpha = (Color.alpha(strokeColor) + 40 * pressProgress).toInt().coerceIn(0, 255)
            strokePaint.color = Color.argb(rimAlpha, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {
            bodyPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            private val SHADOW_SPREAD = floatArrayOf(1f, 0.60f, 0.25f)
            private val SHADOW_ALPHA = floatArrayOf(0.06f, 0.12f, 0.22f)
        }
    }

    /** State per bar. Kept in WeakHashMap to avoid Activity memory leaks. */
    private class BarState {
        var indicator: LiquidIndicatorDrawable? = null
        var backdrop: View? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var visibilityGlobalLayout: ViewTreeObserver.OnGlobalLayoutListener? = null
        var items: List<View> = emptyList()
        var selectedIndex = -1
        var originalParent: ViewGroup? = null
        var wrapper: FrameLayout? = null
        var isTabsReordered: Boolean = false
        var lastParentChildCount: Int = -1

        var activeColorState: android.content.res.ColorStateList? = null
        var inactiveColorState: android.content.res.ColorStateList? = null

        // Direct cache from setSelected hook — avoids per-frame drawableState scan
        var checkedViewRef: java.lang.ref.WeakReference<View>? = null

        // --- Named Spring Physics Constants ---
        companion object {
            const val SPRING_CENTER_LERP = 0.50f  // How fast pill center tracks target
            const val SPRING_WIDTH_LERP  = 0.50f  // How fast pill width tracks target
            const val SPRING_SCALE_LERP  = 0.42f  // Squash/stretch recovery speed
            const val SPRING_PRESS_LERP  = 0.38f  // Press progress fade speed
            const val JELLY_STRETCH_MAX  = 0.40f  // Max horizontal stretch at high velocity
            const val JELLY_SQUASH_MAX   = 0.20f  // Max vertical squash at high velocity
            const val JELLY_VEL_SCALE    = 25f    // px/frame threshold for max jelly
            const val SETTLE_THRESHOLD_X = 0.2f   // px delta below which position is settled
            const val SETTLE_THRESHOLD_S = 0.01f  // scale delta below which scale is settled
        }

        // --- Spring Physics Model State ---
        var isChoreographerActive: Boolean = false
        var currentCenterX: Float = 0f
        var currentHalfWidth: Float = 0f
        var targetCenterX: Float = 0f
        var targetHalfWidth: Float = 0f
        var currentScaleX: Float = 1f
        var currentScaleY: Float = 1f
        var targetScaleX: Float = 1f
        var targetScaleY: Float = 1f
        var pressProgress: Float = 0f
        var targetPressProgress: Float = 0f

        var isDragging: Boolean = false
        var isScrubbing: Boolean = false
        var dragStartX: Float = 0f
        var lastDragX: Float = 0f
        var pillStartCenterX: Float = 0f

        /** Layout sync listener */
        var layoutSync: View.OnLayoutChangeListener? = null

        val createdAt: Long = android.os.SystemClock.elapsedRealtime()

        // --- Dirty-check cache for predraw sync (avoids full resync every frame) ---
        var lastSyncWidth: Int = -1
        var lastSyncChildCount: Int = -1
        var lastSyncCheckedRef: java.lang.ref.WeakReference<View>? = null
        var syncSkipStreak: Int = 0
    }

    /**
     * High-Vibrancy Optical AGSL Glass Refraction Shader (Android 13+)
     * Single source of truth in AgslHelper.java.
     */
    private object LiquidGlassShader {
        val SHADER_SRC: String
            get() = com.wmods.wppenhacer.utils.AgslHelper.SHADER_SRC
    }

    override fun doHook() {
        if (!prefs.getBoolean("floating_bottom_bar", false) && !prefs.getBoolean("ios_header", false)) return

        val bottomNavId = Utils.getID("bottom_nav", "id")
        if (bottomNavId <= 0) return
        val fabIds = FAB_RESOURCE_NAMES.mapNotNull { name ->
            Utils.getID(name, "id").takeIf { id -> id > 0 }
        }.toSet()

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view.id == bottomNavId) {
                        val bar = view as? ViewGroup ?: return
                        scheduleSetup(bar)
                        return
                    }
                    if (view.id in fabIds) {
                        view.post { positionFabAboveCurrentBar(view, bottomNavId) }
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view.id != bottomNavId) return
                    val bar = view as? ViewGroup ?: return
                    teardownBarState(bar)
                    setupAttempts.remove(bar)
                    processedBars.remove(bar)
                }
            })

        XposedHelpers.findAndHookMethod(
            ViewGroup::class.java,
            "dispatchTouchEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bar = param.thisObject as? ViewGroup ?: return
                    if (bar.id != bottomNavId) return
                    val state = barStates[bar] ?: return
                    val items = state.items
                    val indicator = state.indicator ?: return
                    if (items.isEmpty() || !indicator.active) return
                    val ev = param.args[0] as? MotionEvent ?: return

                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            state.dragStartX = ev.x
                            state.lastDragX = ev.x
                            state.pillStartCenterX = indicator.centerX
                            state.isDragging = false
                            state.targetPressProgress = 1f
                            startPillPhysics(bar, state)
                            bar.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.x - state.dragStartX
                            val stepDx = ev.x - state.lastDragX
                            state.lastDragX = ev.x

                            if (!state.isDragging && abs(dx) > Utils.dipToPixels(4f)) {
                                state.isDragging = true
                                state.isScrubbing = true
                                try {
                                    val cancelEv = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                                    for (i in 0 until bar.childCount) {
                                        bar.getChildAt(i).dispatchTouchEvent(cancelEv)
                                    }
                                    cancelEv.recycle()
                                } catch (_: Throwable) {}
                            }

                            if (state.isDragging) {
                                val firstItem = items.first()
                                val lastItem = items.last()
                                val minX = offsetInBar(bar, firstItem).first + firstItem.width / 2f
                                val maxX = offsetInBar(bar, lastItem).first + lastItem.width / 2f

                                // Rubber band resistance
                                val rawCenterX = state.pillStartCenterX + dx
                                var newCenterX = when {
                                    rawCenterX < minX -> minX - sqrt((minX - rawCenterX) * Utils.dipToPixels(RUBBER_BAND_DP * 2f))
                                    rawCenterX > maxX -> maxX + sqrt((rawCenterX - maxX) * Utils.dipToPixels(RUBBER_BAND_DP * 2f))
                                    else -> rawCenterX
                                }

                                val closestIndex = items.indices.minByOrNull { idx ->
                                    val item = items[idx]
                                    val center = offsetInBar(bar, item).first + item.width / 2f
                                    abs(center - newCenterX)
                                } ?: state.selectedIndex
                                val targetWidth = items[closestIndex].width * INDICATOR_WIDTH_RATIO / 2f

                                val safeMargin = Utils.dipToPixels(10f).toFloat()
                                val minCenter = safeMargin + targetWidth
                                val maxCenter = if (bar.width > 0) bar.width - safeMargin - targetWidth else minCenter
                                if (maxCenter > minCenter) {
                                    newCenterX = newCenterX.coerceIn(minCenter, maxCenter)
                                }

                                // Velocity-driven stretch and press scale
                                val dragVelocityNorm = (stepDx / Utils.dipToPixels(12f)).coerceIn(-0.35f, 0.35f)
                                val stretchX = PRESSED_SCALE * (1f + abs(dragVelocityNorm) * 0.25f)
                                val squashY = PRESSED_SCALE * (1f - abs(dragVelocityNorm) * 0.10f)

                                state.currentCenterX = newCenterX
                                state.targetCenterX = newCenterX
                                state.currentHalfWidth = targetWidth
                                state.targetHalfWidth = targetWidth
                                state.currentScaleX = stretchX
                                state.currentScaleY = squashY
                                state.targetScaleX = 1f
                                state.targetScaleY = 1f
                                state.targetPressProgress = 1f

                                indicator.centerX = newCenterX
                                indicator.halfWidth = targetWidth
                                indicator.scaleX = stretchX
                                indicator.scaleY = squashY
                                indicator.pressProgress = 1f
                                indicator.invalidateSelf()
                                bar.invalidate()

                                // Interactive tab item hover scale
                                for (item in items) {
                                    val center = offsetInBar(bar, item).first + item.width / 2f
                                    val dist = abs(center - newCenterX)
                                    val maxDist = item.width.toFloat().coerceAtLeast(1f)
                                    val hoverFraction = (1f - dist / maxDist).coerceIn(0f, 1f)
                                    val targetScale = 1f + 0.12f * hoverFraction
                                    item.scaleX = targetScale
                                    item.scaleY = targetScale
                                }

                                param.result = true
                                return
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            state.targetPressProgress = 0f
                            if (state.isDragging) {
                                state.isDragging = false
                                state.isScrubbing = false

                                val currentX = indicator.centerX
                                val closestIndex = items.indices.minByOrNull { idx ->
                                    val item = items[idx]
                                    val center = offsetInBar(bar, item).first + item.width / 2f
                                    abs(center - currentX)
                                } ?: state.selectedIndex

                                if (closestIndex in items.indices) {
                                    items[closestIndex].performClick()
                                    animateToItem(bar, state, items, closestIndex)
                                }
                                param.result = true
                                return
                            } else {
                                startPillPhysics(bar, state)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            state.targetPressProgress = 0f
                            if (state.isDragging) {
                                state.isDragging = false
                                state.isScrubbing = false
                                if (state.selectedIndex in items.indices) {
                                    animateToItem(bar, state, items, state.selectedIndex)
                                }
                            } else {
                                startPillPhysics(bar, state)
                            }
                        }
                    }
                }
            })

        // Hook ViewPager page scroll for smooth sliding indicator during tab swipe
        try {
            val viewPagerClass = classLoader.loadClass("androidx.viewpager.widget.ViewPager")
            XposedBridge.hookAllMethods(viewPagerClass, "A0G", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val position = param.args.getOrNull(0) as? Int ?: return
                    val positionOffset = param.args.getOrNull(1) as? Float ?: 0f

                    for ((bar, state) in barStates) {
                        if (!bar.isShown) continue
                        val items = state.items
                        if (items.size < 2 || state.isDragging) continue
                        val indicator = state.indicator ?: continue

                        val homeActivity = WppCore.getCurrentActivity()
                        val tabIndex1 = try {
                            if (homeActivity != null && homeActivity.javaClass == WppCore.homeActivityClass) {
                                val m = homeActivity.javaClass.getMethod("A5M", Int::class.javaPrimitiveType)
                                m.invoke(homeActivity, position) as Int
                            } else position
                        } catch (_: Throwable) { position }

                        val tabIndex2 = try {
                            if (homeActivity != null && homeActivity.javaClass == WppCore.homeActivityClass) {
                                val m = homeActivity.javaClass.getMethod("A5M", Int::class.javaPrimitiveType)
                                m.invoke(homeActivity, position + 1) as Int
                            } else position + 1
                        } catch (_: Throwable) { position + 1 }

                        val item1 = items.getOrNull(tabIndex1)
                        val item2 = items.getOrNull(tabIndex2)

                        if (item1 != null) {
                            val (offX1, _) = offsetInBar(bar, item1)
                            val center1 = offX1 + item1.width / 2f
                            val halfW1 = item1.width * INDICATOR_WIDTH_RATIO / 2f

                            val (targetCenter, targetHalfW) = if (item2 != null && positionOffset > 0f) {
                                val (offX2, _) = offsetInBar(bar, item2)
                                val center2 = offX2 + item2.width / 2f
                                val halfW2 = item2.width * INDICATOR_WIDTH_RATIO / 2f
                                (center1 + (center2 - center1) * positionOffset) to (halfW1 + (halfW2 - halfW1) * positionOffset)
                            } else {
                                center1 to halfW1
                            }

                            val inset = Utils.dipToPixels(INDICATOR_INSET_DP).toFloat()
                            val barH = (if (bar.height > 0) bar.height else item1.height).toFloat()
                            val pillH = Utils.dipToPixels(44f).toFloat()
                            val centerY = barH / 2f
                            indicator.top = (centerY - pillH / 2f).coerceAtLeast(inset)
                            indicator.bottom = (centerY + pillH / 2f).coerceAtMost(barH - inset)

                            // Only set target – let Choreographer spring-animate to it smoothly
                            state.targetCenterX = targetCenter
                            state.targetHalfWidth = targetHalfW

                            if (!indicator.active) {
                                state.currentCenterX = targetCenter
                                state.currentHalfWidth = targetHalfW
                                indicator.centerX = targetCenter
                                indicator.halfWidth = targetHalfW
                                indicator.active = true
                                indicator.invalidateSelf()
                                bar.invalidate()
                            } else {
                                startPillPhysics(bar, state)
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            val itemClasses = listOf("X.0hl", "X.18n")
            for (clsName in itemClasses) {
                try {
                    val itemClass = classLoader.loadClass(clsName)
                    // Critical: directly drive the pill when an item becomes selected
                    XposedBridge.hookAllMethods(itemClass, "setSelected", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isBarOrChild(view)) return
                            // Always suppress native indicator, regardless of argument type
                            disableNativeActiveIndicator(view)
                            resetAnimations(view)
                            val isNowSelected = param.args.getOrNull(0) as? Boolean ?: return
                            if (isNowSelected) {
                                for ((bar, state) in barStates) {
                                    val items = state.items
                                    val idx = items.indexOf(view)
                                    if (idx >= 0 && !state.isDragging && !state.isScrubbing) {
                                        state.checkedViewRef = java.lang.ref.WeakReference(view)
                                        if (idx != state.selectedIndex) {
                                            state.selectedIndex = idx
                                            view.post { animateToItem(bar, state, items, idx) }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    })
                    XposedBridge.hookAllMethods(itemClass, "refreshDrawableState", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isBarOrChild(view)) return
                            disableNativeActiveIndicator(view)
                            resetAnimations(view)
                        }
                    })
                    XposedBridge.hookAllMethods(itemClass, "getActiveIndicatorDrawable", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isBarOrChild(view)) return
                            param.result = null
                        }
                    })
                    XposedBridge.hookAllMethods(itemClass, "A01", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isBarOrChild(view)) return
                            disableNativeActiveIndicator(view)
                            resetAnimations(view)
                            param.result = null
                        }
                    })
                } catch (e: Throwable) {
                    logDebug("BottomBar: failed to hook item class '$clsName': ${e.message}")
                }
            }
        } catch (e: Throwable) {
            logDebug("BottomBar item hook failed: ${e.message}")
        }
    }

    private fun isBarOrChild(view: View): Boolean {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx.javaClass == WppCore.homeActivityClass) break
            val base = ctx.baseContext
            if (base === ctx) break
            ctx = base
        }
        if (ctx.javaClass != WppCore.homeActivityClass) return false

        val bottomNavId = Utils.getID("bottom_nav", "id")
        if (bottomNavId <= 0) return false
        var current: View? = view
        while (current != null) {
            if (current.id == bottomNavId) return true
            current = current.parent as? View
        }
        return false
    }

    private fun scheduleSetup(bar: ViewGroup) {
        if (processedBars.containsKey(bar)) {
            ensureBarOverlay(bar)
            return
        }

        bar.post {
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
                return@post
            }
            retrySetup(bar)
        }
    }

    private fun retrySetup(bar: ViewGroup) {
        val attempt = setupAttempts[bar] ?: 0
        if (attempt >= 3) return
        setupAttempts[bar] = attempt + 1
        bar.postDelayed({
            if (processedBars.containsKey(bar)) return@postDelayed
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
            } else {
                retrySetup(bar)
            }
        }, 100L)
    }

    private fun ensureBarOverlay(bar: ViewGroup) {
        val container = bar.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) {
            bar.post { setupFloatingBar(bar) }
        }
    }

    private fun setupFloatingBar(bar: ViewGroup): Boolean {
        try {
            val existingParent = bar.parent as? FrameLayout
            if (existingParent?.getTag(TAG_FLOATING_WRAPPER) == true) {
                val rootView = findRootView(bar) ?: return false
                val state = barStates.getOrPut(bar) { BarState() }
                state.wrapper = existingParent
                updateOverlayLayout(rootView, existingParent, bar)
                applyTransparentShadowStyle(rootView, existingParent, bar)
                positionFabsAboveBar(rootView, existingParent)
                setupVisibilitySync(rootView, existingParent, bar, state)
                return true
            }

            val originalParent = bar.parent as? ViewGroup ?: return false
            val rootView = findRootView(bar) ?: return false

            if (originalParent.parent === rootView &&
                originalParent.getTag(TAG_FLOATING_WRAPPER) == true) {
                return true
            }
            originalParent.removeView(bar)

            val state = barStates.getOrPut(bar) { BarState() }
            state.originalParent = originalParent

            val wrapper = FrameLayout(bar.context).apply {
                setTag(TAG_FLOATING_WRAPPER, true)
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
            }
            state.wrapper = wrapper

            val barHeight = Utils.dipToPixels(BAR_HEIGHT_DP)

            val wrapperParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight
            ).apply {
                gravity = Gravity.BOTTOM
            }

            val barParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight
            ).apply {
                gravity = Gravity.BOTTOM
            }
            wrapper.addView(bar, barParams)
            rootView.addView(wrapper, wrapperParams)

            updateOverlayLayout(rootView, wrapper, bar)
            applyTransparentShadowStyle(rootView, wrapper, bar)
            positionFabsAboveBar(rootView, wrapper)
            setupVisibilitySync(rootView, wrapper, bar, state)

            bar.post {
                val rv = findRootView(bar)
                val st = barStates[bar]
                if (rv != null && st?.wrapper != null) {
                    applyTransparentShadowStyle(rv, st.wrapper!!, bar)
                }
            }

            logDebug("FloatingBottomBar: wrapped bar in floating overlay")
            return true
        } catch (e: Throwable) {
            log(e)
            return false
        }
    }

    private fun setupVisibilitySync(
        rootView: FrameLayout,
        wrapper: FrameLayout,
        bar: ViewGroup,
        state: BarState
    ) {
        state.visibilityGlobalLayout?.let {
            if (rootView.viewTreeObserver.isAlive) {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(it)
            }
        }

        fun updateVisibility() {
            val origParent = state.originalParent
            val isOrigParentVisible = origParent == null || (origParent.isShown && origParent.visibility == View.VISIBLE)
            val isBarVisible = bar.visibility == View.VISIBLE
            val insets = ViewCompat.getRootWindowInsets(rootView)
            val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
                    (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 100
            val focusedView = rootView.findFocus()
            val isEditing = focusedView is EditText

            val shouldShow = isOrigParentVisible && isBarVisible && !imeVisible && !isEditing
            val targetVisibility = if (shouldShow) View.VISIBLE else View.GONE

            if (wrapper.visibility != targetVisibility) {
                wrapper.visibility = targetVisibility
            }
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            updateVisibility()
        }

        state.visibilityGlobalLayout = listener
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        updateVisibility()
    }

    private fun updateOverlayLayout(rootView: FrameLayout, container: ViewGroup, bar: ViewGroup) {
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val sideMargin = Utils.dipToPixels(SIDE_MARGIN_DP)
        val barHeight = Utils.dipToPixels(BAR_HEIGHT_DP)
        params.gravity = Gravity.BOTTOM
        params.leftMargin = sideMargin
        params.rightMargin = sideMargin
        params.bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
        params.height = barHeight
        container.layoutParams = params

        val barParams = bar.layoutParams ?: return
        barParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        barParams.height = barHeight
        (barParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        bar.layoutParams = barParams
        bar.setPadding(Utils.dipToPixels(BAR_PADDING_DP), 0, Utils.dipToPixels(BAR_PADDING_DP), 0)
    }

    private fun navigationBarInset(view: View): Int {
        return ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    }

    private fun findRootView(startView: View): FrameLayout? {
        var candidate: FrameLayout? = null
        var p: android.view.ViewParent? = startView.parent
        while (p != null) {
            if (p.javaClass == FrameLayout::class.java) {
                candidate = p as FrameLayout
            }
            p = p.parent
        }
        if (candidate != null) return candidate
        val content = startView.rootView.findViewById<ViewGroup>(android.R.id.content)
        return (content as? FrameLayout) ?: (startView.rootView as? FrameLayout)
    }

    private fun findBlurRoot(rootView: ViewGroup, wrapper: View): ViewGroup? {
        var best: ViewGroup? = null
        var bestArea = 0L
        for (i in 0 until rootView.childCount) {
            val child = rootView.getChildAt(i)
            if (child === wrapper || child !is ViewGroup) continue
            val area = child.width.toLong() * child.height.toLong()
            if (area > bestArea) {
                bestArea = area
                best = child
            }
        }
        return if (bestArea > 0L) best else null
    }

    private fun applyTransparentShadowStyle(
        rootView: FrameLayout,
        container: ViewGroup,
        bar: ViewGroup
    ) {
        container.setBackgroundColor(Color.TRANSPARENT)

        val parent = container.parent as? ViewGroup
        parent?.clipChildren = false
        parent?.clipToPadding = false
        container.clipChildren = false
        container.clipToPadding = false

        val dividerId = Utils.getID("bottom_nav_divider", "id")
        if (dividerId > 0) {
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                if (child.id == dividerId) {
                    child.visibility = View.GONE
                    break
                }
            }
        }

        val barColor = resolveBarColor(bar)
        val isLight = isLightColor(barColor)
        val blurEnabled = prefs.getBoolean("floating_bottom_bar_blur", true)

        val radiusDp = prefs.getInt("floating_bottom_bar_radius", CORNER_RADIUS_DP.toInt()).toFloat()
        val radius = Utils.dipToPixels(radiusDp).toFloat()

        // Pure Crystal Liquid Glass Bar (Barely tinted, 98% transparent, clear refraction)
        val pillAlpha = if (blurEnabled) 6 else 140
        val pillColor = if (isLight) {
            Color.argb(pillAlpha, 255, 255, 255)
        } else {
            Color.argb(pillAlpha, 255, 255, 255)
        }

        val corners = floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        
        // 1. Crystal clear transparent glass body
        val basePill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
            setColor(pillColor)
        }
        
        // 2. Pure crisp crystalline glass rim stroke
        val outerCrystallineRim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
            setColor(Color.TRANSPARENT)
            val strokeAlpha = if (isLight) 40 else 65
            setStroke(Utils.dipToPixels(1.2f), Color.argb(strokeAlpha, 255, 255, 255))
        }

        val pill = LayerDrawable(arrayOf(basePill, outerCrystallineRim))

        val state = barStates.getOrPut(bar) { BarState() }

        val backdrop = obtainBackdrop(rootView, container, bar, blurEnabled, state)
        val elevPx = Utils.dipToPixels(ELEVATION_DP).toFloat()
        backdrop.background = pill
        backdrop.clipToOutline = true
        backdrop.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = radius.coerceAtMost(view.height.toFloat() / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        backdrop.elevation = elevPx

        if (backdrop is BlurView && blurEnabled) {
            val blurRoot2 = findBlurRoot(rootView, container)
            if (blurRoot2 != null) {
                try {
                    backdrop.setupWith(blurRoot2)
                        .setFrameClearDrawable(null)
                        .setBlurRadius(BLUR_RADIUS)
                        .setOverlayColor(Color.TRANSPARENT)
                        .setBlurAutoUpdate(true)
                } catch (_: Throwable) { }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                backdrop.post {
                    if (backdrop.width > 0 && backdrop.height > 0) {
                        try {
                            val w = backdrop.width.toFloat()
                            val h = backdrop.height.toFloat()
                            val r = radius.coerceAtMost(h / 2f)
                            val shader = RuntimeShader(LiquidGlassShader.SHADER_SRC)
                            shader.setFloatUniform("resolution", w, h)
                            shader.setFloatUniform("cornerRadius", r)
                            shader.setFloatUniform("refractionStrength", 5.0f)
                            shader.setFloatUniform("chromaticAberration", 1.8f)
                            shader.setFloatUniform("brightnessBoost", 1.15f)
                            shader.setFloatUniform("rimIntensity", 0.45f)

                            val glassEffect = RenderEffect.createRuntimeShaderEffect(shader, "image")
                            backdrop.setRenderEffect(glassEffect)
                        } catch (t: Throwable) {
                            logDebug("Failed to apply strong AGSL shader: ${t.message}")
                        }
                    }
                }
            }
        }

        bar.clipToOutline = true
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.elevation = elevPx + 2f
        bar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = radius.coerceAtMost(view.height.toFloat() / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bar.setRenderEffect(null)
        }
        bar.bringToFront()

        // Clean spotlight indicator
        val indicatorColor = if (isLight) {
            Color.argb(38, 0, 0, 0)
        } else {
            Color.argb(55, 255, 255, 255)
        }
        val shadowColor = if (isLight) {
            Color.argb(30, 0, 0, 0)
        } else {
            Color.argb(45, 0, 0, 0)
        }
        val indicatorStroke = if (isLight) {
            Color.argb(30, 255, 255, 255)
        } else {
            Color.argb(70, 255, 255, 255)
        }
        val indicator = LiquidIndicatorDrawable(indicatorColor, shadowColor, indicatorStroke)
        state.indicator = indicator
        state.selectedIndex = -1
        bar.background = indicator

        try {
            val setIndicatorEnabled = bar.javaClass
                .getMethod("setItemActiveIndicatorEnabled", Boolean::class.javaPrimitiveType)
            setIndicatorEnabled.invoke(bar, false)
        } catch (e: Exception) {
            logDebug("Failed to disable native active indicator: ${e.message}")
        }

        try {
            val setLabelMode = bar.javaClass
                .getMethod("setLabelVisibilityMode", Int::class.javaPrimitiveType)
            setLabelMode.invoke(bar, 1)
        } catch (e: Exception) { }

        attachSelectionWatcher(bar, state)
    }

    private fun obtainBackdrop(
        rootView: FrameLayout,
        container: ViewGroup,
        bar: ViewGroup,
        blurEnabled: Boolean,
        state: BarState
    ): View {
        state.backdrop?.let { existing ->
            if (existing.parent === container) {
                syncBackdropHeight(bar, existing, state)
                return existing
            }
            (existing.parent as? ViewGroup)?.removeView(existing)
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.getTag(TAG_BACKDROP) == true) {
                state.backdrop = child
                syncBackdropHeight(bar, child, state)
                return child
            }
        }

        val backdrop = createBackdropView(rootView, container, blurEnabled)
        backdrop.setTag(TAG_BACKDROP, true)

        val initialHeight = if (bar.height > 0) bar.height else Utils.dipToPixels(BAR_HEIGHT_DP)
        container.addView(
            backdrop,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                initialHeight
            ).also { lp -> lp.topMargin = 0 }
        )
        state.backdrop = backdrop
        syncBackdropHeight(bar, backdrop, state)
        return backdrop
    }

    private fun syncBackdropHeight(bar: ViewGroup, backdrop: View, state: BarState) {
        state.layoutSync?.let { bar.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val h = bar.height
            if (h > 0) {
                val lp = backdrop.layoutParams as? FrameLayout.LayoutParams
                    ?: return@OnLayoutChangeListener
                if (lp.height != h || lp.topMargin != 0) {
                    lp.height = h
                    lp.topMargin = 0
                    backdrop.layoutParams = lp
                }
            }
        }
        state.layoutSync = listener
        bar.addOnLayoutChangeListener(listener)
        if (bar.height > 0) {
            val lp = backdrop.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.height != bar.height || lp.topMargin != 0) {
                lp.height = bar.height
                lp.topMargin = 0
                backdrop.layoutParams = lp
            }
        }
    }

    private fun createBackdropView(
        rootView: FrameLayout,
        container: ViewGroup,
        blurEnabled: Boolean
    ): View {
        if (!blurEnabled) return View(container.context)

        val blurRoot = findBlurRoot(rootView, container)
        if (blurRoot == null) {
            logDebug("FloatingBottomBar: no blur root found, using plain backdrop")
            return View(container.context)
        }

        return createBlurViewFallback(container, blurRoot)
    }

    private fun createBlurViewFallback(container: ViewGroup, blurRoot: ViewGroup): View {
        return try {
            BlurView(ModuleContextWrapper(container.context)).apply {
                setupWith(blurRoot)
                    .setFrameClearDrawable(null)
                    .setBlurRadius(BLUR_RADIUS)
                    .setOverlayColor(Color.argb(8, 255, 255, 255))
                    .setBlurAutoUpdate(true)
            }
        } catch (e: Throwable) {
            logDebug("FloatingBottomBar: blur unavailable (${e.message}), using plain backdrop")
            View(container.context)
        }
    }

    private fun attachSelectionWatcher(bar: ViewGroup, state: BarState) {
        state.preDraw?.let {
            if (bar.viewTreeObserver.isAlive) bar.viewTreeObserver.removeOnPreDrawListener(it)
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            try {
                if (shouldResync(bar, state)) {
                    syncSelection(bar, state)
                }
            } catch (e: Throwable) {
                logDebug("FloatingBottomBar: selection sync failed: ${e.message}")
            }
            true
        }
        state.preDraw = listener
        bar.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * Cheap pre-check before paying for reorderMenuTabsSafely() + tinting + BFS traversal.
     * - Selalu resync selama drag/scrub/spring animasi jalan (state itu emang berubah tiap frame).
     * - Kalau idle, cuma resync kalau width/childCount/checked-view berubah.
     * - Safety net: paksa resync tiap ~45 frame (≈0.75s @60fps) buat jaga-jaga kalau WA
     *   reset translationX/tint dari internal tanpa lewat jalur yang kita pantau.
     */
    private fun shouldResync(bar: ViewGroup, state: BarState): Boolean {
        if (state.isDragging || state.isScrubbing || state.isChoreographerActive) {
            state.syncSkipStreak = 0
            return true
        }

        val width = bar.width
        val childCount = bar.childCount
        val checkedNow = state.checkedViewRef?.get()
        val needsInit = state.items.isEmpty() || state.items.firstOrNull()?.parent == null

        val changed = width != state.lastSyncWidth ||
                childCount != state.lastSyncChildCount ||
                checkedNow !== state.lastSyncCheckedRef?.get() ||
                needsInit

        if (changed) {
            state.lastSyncWidth = width
            state.lastSyncChildCount = childCount
            state.lastSyncCheckedRef = checkedNow?.let { java.lang.ref.WeakReference(it) }
            state.syncSkipStreak = 0
            return true
        }

        state.syncSkipStreak++
        if (state.syncSkipStreak >= 45) {
            state.syncSkipStreak = 0
            return true
        }
        return false
    }

    private fun getItemLabel(view: View): String {
        if (view is TextView) {
            val t = view.text?.toString() ?: ""
            if (t.isNotEmpty()) return t
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val t = getItemLabel(view.getChildAt(i))
                if (t.isNotEmpty()) return t
            }
        }
        return ""
    }

    private fun getTabRank(view: View): Int {
        val label = getItemLabel(view).lowercase().trim()
        val desc = view.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = "$label $desc"

        // 1. Pembaruan / Updates / Status
        if (text.contains("pembaruan") || text.contains("update") || text.contains("status")) return 1
        // 2. Panggilan / Calls / Telepon
        if (text.contains("panggilan") || text.contains("call") || text.contains("llamada") || text.contains("telepon")) return 2
        // 3. Komunitas / Communities
        if (text.contains("komunitas") || text.contains("communit") || text.contains("comunidad")) return 3
        // 4. Chat / Chats / Obrolan (Di kanan Komunitas)
        if (text.contains("chat") || text.contains("obrolan") || text.contains("percakapan") || text.contains("conversa")) return 4
        // 5. Anda / You / Pengaturan / Settings / Profile / Tools
        if (text.contains("anda") || text.contains("you") || text.contains("tú") || text.contains("voce") || text.contains("você") || text.contains("setting") || text.contains("profil") || text.contains("pengaturan")) return 5

        return 99
    }

    private fun reorderMenuTabsSafely(bar: ViewGroup, state: BarState) {
        val items = findItemViews(bar)
        if (items.size < 3) return
        if (items.first().width == 0) return

        val sortedByRank = items.sortedBy { getTabRank(it) }
        
        val startLeft = items.minOf { it.left }
        var currentLeft = startLeft
        
        for (child in sortedByRank) {
            val tx = currentLeft - child.left
            if (child.translationX != tx.toFloat()) {
                child.translationX = tx.toFloat()
            }
            currentLeft += child.width
        }
        
        state.isTabsReordered = true
        state.items = items
    }

    private data class CheckedAccessor(
        val field: java.lang.reflect.Field,
        val method: java.lang.reflect.Method
    )

    // Class-level cache — struktur field/method itu sama untuk semua instance dari class yang sama,
    // jadi cukup di-resolve sekali per class, bukan di-scan ulang tiap panggilan.
    private val checkedAccessorCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, List<CheckedAccessor>>()

    private fun getCheckedAccessors(view: View): List<CheckedAccessor> {
        return checkedAccessorCache.getOrPut(view.javaClass) {
            val result = mutableListOf<CheckedAccessor>()
            for (field in view.javaClass.declaredFields) {
                field.isAccessible = true
                val obj = try { field.get(view) } catch (_: Throwable) { null } ?: continue
                if (obj is Int || obj is Boolean || obj is String || obj is Drawable) continue
                try {
                    val m = obj.javaClass.getMethod("isChecked")
                    if (m.returnType == Boolean::class.java || m.returnType == Boolean::class.javaObjectType) {
                        result.add(CheckedAccessor(field, m))
                    }
                } catch (_: Throwable) {}
            }
            result
        }
    }

    private fun isViewChecked(view: View): Boolean {
        // 1. Standard Android drawableState
        val states = view.drawableState
        if (states != null && (states.contains(android.R.attr.state_checked) || states.contains(android.R.attr.state_selected))) {
            return true
        }
        if (view.isSelected || view.isActivated) return true

        // 2. XposedHelpers getItemData (WhatsApp-specific)
        try {
            val itemData = de.robv.android.xposed.XposedHelpers.callMethod(view, "getItemData")
            if (itemData != null) {
                val isChecked = de.robv.android.xposed.XposedHelpers.callMethod(itemData, "isChecked") as? Boolean
                if (isChecked == true) return true
            }
        } catch (_: Throwable) {}

        // 3. Cached reflection accessors
        for (accessor in getCheckedAccessors(view)) {
            try {
                val obj = accessor.field.get(view) ?: continue
                if (accessor.method.invoke(obj) == true) return true
            } catch (_: Throwable) {}
        }

        return false
    }

    private fun getTrueSelectedIndex(items: List<View>, state: BarState): Int {
        // Fast path: view cached directly from setSelected hook
        val cached = state.checkedViewRef?.get()
        if (cached != null) {
            val idx = items.indexOf(cached)
            if (idx >= 0) return idx
        }
        // Slow path: scan drawableState of each item
        for (i in items.indices) {
            if (isViewChecked(items[i])) return i
        }
        return state.selectedIndex
    }

    private fun syncSelection(bar: ViewGroup, state: BarState) {
        var items = state.items
        val needsInit = items.isEmpty() || items[0].parent == null
        if (needsInit || bar.childCount != state.lastParentChildCount) {
            reorderMenuTabsSafely(bar, state)
            items = state.items
            state.lastParentChildCount = bar.childCount
        } else {
            reorderMenuTabsSafely(bar, state)
            items = state.items
        }
        
        // Disable Material 3 BottomNavigationView touch delegate so that clicks respect translationX
        if (bar.touchDelegate != null) {
            bar.touchDelegate = null
        }
        
        if (items.isNotEmpty()) {
            items.forEach {
                if (it.getTag(TAG_ITEM_INITIALIZED) != true) {
                    it.setTag(TAG_ITEM_INITIALIZED, true)
                    clearBackgroundsRecursively(it)
                    disableNativeActiveIndicator(it)
                    morphAndaToSettings(it)
                    formatTabItemViews(it)
                } else {
                    if (it.background != null) it.background = null
                    formatTabItemViews(it)
                }
            }
        }
        if (items.isEmpty()) return

        val selected = getTrueSelectedIndex(items, state)
        if (selected < 0) return
        
        if (items[selected].width <= 0) return
        
        // Ensure manual tinting so native wrong selection doesn't bleed through
        val activeColor = resolveBarColor(bar).let { if (isLightColor(it)) Color.BLACK else Color.WHITE }
        val inactiveColor = Color.argb(128, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        
        val activeColorState = state.activeColorState ?: android.content.res.ColorStateList.valueOf(activeColor).also { state.activeColorState = it }
        val inactiveColorState = state.inactiveColorState ?: android.content.res.ColorStateList.valueOf(inactiveColor).also { state.inactiveColorState = it }
        
        for (i in items.indices) {
            val view = items[i]
            val isActive = (i == selected)
            val targetColorState = if (isActive) activeColorState else inactiveColorState
            val targetColor = if (isActive) activeColor else inactiveColor
            
            // Tint ImageView and TextView manually
            val group = view as? ViewGroup
            if (group != null) {
                for (j in 0 until group.childCount) {
                    val child = group.getChildAt(j)
                    if (child is ImageView) {
                        if (child.imageTintList !== targetColorState) {
                            child.imageTintList = targetColorState
                        }
                    } else if (child is TextView) {
                        if (child.currentTextColor != targetColor) {
                            child.setTextColor(targetColor)
                        }
                    }
                }
            }
        }

        // Only animate pill when selected tab actually changes, and not during swipe/drag
        if (selected != state.selectedIndex && !state.isDragging && !state.isScrubbing) {
            state.selectedIndex = selected
            animateToItem(bar, state, items, selected)
        } else if (state.selectedIndex < 0) {
            // First time init
            state.selectedIndex = selected
            animateToItem(bar, state, items, selected)
        }
        state.wrapper?.let { wrap ->
            val rv = findRootView(bar) ?: (bar.rootView as? ViewGroup)
            rv?.let { positionFabsAboveBar(it, wrap) }
        }
    }

    /** Disables native Material 3 / WhatsApp active indicator on item. */
    private fun disableNativeActiveIndicator(item: View) {
        try {
            val indView = XposedHelpers.getObjectField(item, "A0P") as? View
            indView?.let { v ->
                v.visibility = View.GONE
                v.alpha = 0f
                v.background = null
                v.layoutParams?.let { lp ->
                    lp.width = 0
                    lp.height = 0
                    v.layoutParams = lp
                }
                (v.parent as? ViewGroup)?.removeView(v)
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.setObjectField(item, "A04", null)
            XposedHelpers.setObjectField(item, "A0L", null)
            XposedHelpers.setObjectField(item, "A0M", null)
            XposedHelpers.setBooleanField(item, "A08", false)
            XposedHelpers.setBooleanField(item, "A09", false)
            XposedHelpers.setBooleanField(item, "A0A", false)
            XposedHelpers.setBooleanField(item, "A0N", false)
        } catch (_: Throwable) {}
        if (item is ViewGroup) {
            for (i in 0 until item.childCount) {
                val child = item.getChildAt(i)
                if (child is ViewGroup) {
                    val toRemove = mutableListOf<View>()
                    for (j in 0 until child.childCount) {
                        val grandChild = child.getChildAt(j)
                        if (grandChild !is ImageView && grandChild !is TextView) {
                            grandChild.visibility = View.GONE
                            grandChild.alpha = 0f
                            grandChild.background = null
                            grandChild.layoutParams?.let { lp ->
                                lp.width = 0
                                lp.height = 0
                                grandChild.layoutParams = lp
                            }
                            toRemove.add(grandChild)
                        }
                    }
                    toRemove.forEach { child.removeView(it) }
                }
            }
        }
    }

    private fun morphAndaToSettings(item: View) {
        if (getTabRank(item) != 5) return
        try {
            val queue = ArrayDeque<View>()
            queue.add(item)
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (v is TextView) {
                    val t = v.text?.toString()?.lowercase() ?: ""
                    if (t.contains("anda") || t.contains("pengaturan") || t.contains("profil")) {
                        v.text = "Pengaturan"
                    } else if (t.contains("you") || t.contains("setting") || t.contains("profile")) {
                        v.text = "Settings"
                    } else if (t.contains("tú")) {
                        v.text = "Configuración"
                    } else if (t.contains("voce") || t.contains("você")) {
                        v.text = "Configurações"
                    }
                } else if (v is ImageView) {
                    val gear = com.wmods.wppenhacer.xposed.utils.DesignUtils.getDrawableByName("ic_settings")?.mutate()
                    if (gear != null) {
                        // iOS style gear is usually gray, let's tint it
                        gear.setTint(android.graphics.Color.parseColor("#8E8E93"))
                        v.setImageDrawable(gear)
                        v.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        v.setPadding(0, 0, 0, 0)
                    }
                } else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        queue.add(v.getChildAt(i))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun formatTabItemViews(item: View) {
        if (item !is ViewGroup) return
        val density = item.resources.displayMetrics.density

        val queue = ArrayDeque<View>()
        queue.add(item)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val clsName = v.javaClass.simpleName
            val isIcon = v is ImageView || clsName.contains("Icon", ignoreCase = true)
            val isLabel = v is TextView || clsName.contains("Label", ignoreCase = true)

            if (isIcon) {
                val targetY = -6f * density
                if (v.translationY != targetY) {
                    v.translationY = targetY
                }
            } else if (isLabel) {
                val targetY = 6f * density
                if (v.translationY != targetY) {
                    v.translationY = targetY
                }
                if (v is TextView) {
                    if (v.textSize != 10f * density && v.textSize != 10f) {
                        v.textSize = 10f
                    }
                    v.maxLines = 1
                    v.ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    queue.add(v.getChildAt(i))
                }
            }
        }
    }
    
    private fun resetAnimations(v: View) {
        try {
            v.animate()?.cancel()
            v.clearAnimation()

            if (Build.VERSION.SDK_INT >= 21) {
                v.stateListAnimator = null
            }

            v.translationX = 0f
            v.translationY = 0f
            v.translationZ = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.rotation = 0f
            v.rotationX = 0f
            v.rotationY = 0f
            v.alpha = 1f

            try {
                XposedHelpers.callMethod(v, "jumpDrawablesToCurrentState")
            } catch (_: Throwable) {}

            (XposedHelpers.getObjectField(v, "A00") as? android.animation.AnimatorSet)?.cancel()
            (XposedHelpers.getObjectField(v, "A01") as? android.view.ViewPropertyAnimator)?.cancel()
            (XposedHelpers.getObjectField(v, "A03") as? android.animation.ValueAnimator)?.cancel()
        } catch (_: Throwable) {}

        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                resetAnimations(v.getChildAt(i))
            }
        }
    }

    private fun clearBackgroundsRecursively(view: View) {
        var needsReset = false
        if (view.background != null) {
            view.background = null
            needsReset = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null) {
            view.foreground = null
            needsReset = true
        }
        if (needsReset) {
            resetAnimations(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearBackgroundsRecursively(view.getChildAt(i))
            }
        }
    }

    private fun findItemViews(bar: ViewGroup): List<View> {
        val queue = ArrayDeque<ViewGroup>()
        queue.add(bar)
        while (queue.isNotEmpty()) {
            val group = queue.removeFirst()
            val children = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE }
            if (children.size >= 3 && children.distinctBy { it.javaClass }.size == 1) {
                return children
            }
            children.filterIsInstance<ViewGroup>().forEach { queue.add(it) }
        }
        return emptyList()
    }

    private fun selectedIndexOf(items: List<View>): Int {
        for ((index, view) in items.withIndex()) {
            val states = view.drawableState ?: continue
            for (attr in states) {
                if (attr == android.R.attr.state_checked || attr == android.R.attr.state_selected) {
                    return index
                }
            }
        }
        return -1
    }

    /** Posição de [child] no sistema de coordenadas de [bar]. */
    private fun offsetInBar(bar: ViewGroup, child: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var current: View? = child
        while (current != null && current !== bar) {
            x += (current.left + current.translationX).toInt()
            y += (current.top + current.translationY).toInt()
            current = current.parent as? View
        }
        return x to y
    }

    /**
     * Spring Physics Engine via Choreographer (120 Hz)
     */
    private fun startPillPhysics(bar: ViewGroup, state: BarState) {
        if (state.isChoreographerActive) return
        state.isChoreographerActive = true

        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!state.isChoreographerActive || state.indicator == null || !bar.isShown) {
                    state.isChoreographerActive = false
                    return
                }

                val indicator = state.indicator ?: return

                val deltaX = state.targetCenterX - state.currentCenterX
                val deltaW = state.targetHalfWidth - state.currentHalfWidth
                val deltaSx = state.targetScaleX - state.currentScaleX
                val deltaSy = state.targetScaleY - state.currentScaleY
                val deltaP = state.targetPressProgress - state.pressProgress

                val velocity = deltaX * BarState.SPRING_CENTER_LERP
                state.currentCenterX += velocity
                state.currentHalfWidth += deltaW * BarState.SPRING_WIDTH_LERP
                state.currentScaleX += deltaSx * BarState.SPRING_SCALE_LERP
                state.currentScaleY += deltaSy * BarState.SPRING_SCALE_LERP
                state.pressProgress += deltaP * BarState.SPRING_PRESS_LERP

                // Jelly stretch: pill stretches horizontally and squashes vertically at high velocity
                val velocityFactor = abs(velocity) / BarState.JELLY_VEL_SCALE
                val stretchX = 1f + velocityFactor.coerceAtMost(BarState.JELLY_STRETCH_MAX)
                val squashY = 1f - (velocityFactor * 0.5f).coerceAtMost(BarState.JELLY_SQUASH_MAX)

                indicator.centerX = state.currentCenterX
                indicator.halfWidth = state.currentHalfWidth
                indicator.scaleX = state.currentScaleX * stretchX
                indicator.scaleY = state.currentScaleY * squashY
                indicator.pressProgress = state.pressProgress
                indicator.active = true
                indicator.invalidateSelf()
                bar.invalidate()

                // Interactive tab hover scale
                val items = state.items
                for (item in items) {
                    val center = offsetInBar(bar, item).first + item.width / 2f
                    val dist = abs(center - state.currentCenterX)
                    val maxDist = item.width.toFloat().coerceAtLeast(1f)
                    val hoverFraction = (1f - dist / maxDist).coerceIn(0f, 1f)
                    val targetItemScale = 1f + 0.12f * hoverFraction
                    item.scaleX += (targetItemScale - item.scaleX) * 0.35f
                    item.scaleY += (targetItemScale - item.scaleY) * 0.35f
                }

                val isSettled = abs(deltaX) < BarState.SETTLE_THRESHOLD_X &&
                                abs(deltaW) < BarState.SETTLE_THRESHOLD_X &&
                                abs(deltaSx) < BarState.SETTLE_THRESHOLD_S &&
                                abs(deltaSy) < BarState.SETTLE_THRESHOLD_S &&
                                abs(deltaP) < BarState.SETTLE_THRESHOLD_S

                if (!isSettled || state.isScrubbing) {
                    choreographer.postFrameCallback(this)
                } else {
                    state.currentCenterX = state.targetCenterX
                    state.currentHalfWidth = state.targetHalfWidth
                    state.currentScaleX = 1f
                    state.currentScaleY = 1f
                    state.pressProgress = state.targetPressProgress
                    indicator.centerX = state.targetCenterX
                    indicator.halfWidth = state.targetHalfWidth
                    indicator.scaleX = 1f
                    indicator.scaleY = 1f
                    indicator.pressProgress = state.targetPressProgress
                    state.isChoreographerActive = false
                    indicator.invalidateSelf()
                    bar.invalidate()

                    for (item in items) {
                        item.scaleX = 1f
                        item.scaleY = 1f
                    }
                }
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun animateToItem(
        bar: ViewGroup,
        state: BarState,
        items: List<View>,
        newIndex: Int
    ) {
        val indicator = state.indicator ?: return
        val target = items.getOrNull(newIndex) ?: return
        val (offsetX, _) = offsetInBar(bar, target)
        val inset = Utils.dipToPixels(INDICATOR_INSET_DP).toFloat()

        var toCenter = offsetX + target.width / 2f
        val toHalfWidth = target.width * INDICATOR_WIDTH_RATIO / 2f

        val safeMargin = Utils.dipToPixels(10f).toFloat()
        val minCenter = safeMargin + toHalfWidth
        val maxCenter = if (bar.width > 0) bar.width - safeMargin - toHalfWidth else minCenter
        if (maxCenter > minCenter) {
            toCenter = toCenter.coerceIn(minCenter, maxCenter)
        }

        val previousIndex = state.selectedIndex
        val firstRun = previousIndex < 0 || !indicator.active || (android.os.SystemClock.elapsedRealtime() - state.createdAt < 1500)

        state.selectedIndex = newIndex

        val barH = (if (bar.height > 0) bar.height else target.height).toFloat()
        val pillH = Utils.dipToPixels(44f).toFloat()
        val centerY = barH / 2f
        indicator.top = (centerY - pillH / 2f).coerceAtLeast(inset)
        indicator.bottom = (centerY + pillH / 2f).coerceAtMost(barH - inset)

        if (firstRun) {
            state.currentCenterX = toCenter
            state.currentHalfWidth = toHalfWidth
            state.targetCenterX = toCenter
            state.targetHalfWidth = toHalfWidth
            state.currentScaleX = 1f
            state.currentScaleY = 1f
            state.pressProgress = 0f
            indicator.centerX = toCenter
            indicator.halfWidth = toHalfWidth
            indicator.scaleX = 1f
            indicator.scaleY = 1f
            indicator.pressProgress = 0f
            indicator.active = true
            indicator.invalidateSelf()
            bar.invalidate()
        } else {
            state.targetCenterX = toCenter
            state.targetHalfWidth = toHalfWidth
            state.targetScaleX = 1f
            state.targetScaleY = 1f
            startPillPhysics(bar, state)
        }
    }

    private fun teardownBarState(bar: ViewGroup) {
        val state = barStates.remove(bar) ?: return
        state.isChoreographerActive = false
        state.preDraw?.let {
            if (bar.viewTreeObserver.isAlive) bar.viewTreeObserver.removeOnPreDrawListener(it)
        }
        state.visibilityGlobalLayout?.let {
            if (bar.rootView?.viewTreeObserver?.isAlive == true) {
                bar.rootView.viewTreeObserver.removeOnGlobalLayoutListener(it)
            }
        }
        state.layoutSync?.let { bar.removeOnLayoutChangeListener(it) }
        (state.backdrop as? BlurView)?.setBlurAutoUpdate(false)
        state.items.forEach {
            it.translationY = 0f
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    // --- FABs -------------------------------------------------------------------------

    private fun getFabAdditionalMargin(): Int {
        return Utils.dipToPixels(BAR_HEIGHT_DP + BOTTOM_MARGIN_DP)
    }

    private fun positionFabsAboveBar(rootView: ViewGroup, container: ViewGroup) {
        val additionalMargin = getFabAdditionalMargin()
        findAndPositionAllFabs(rootView, additionalMargin)

        // One-shot: nunggu container beneran punya ukuran (bukan nebak lewat delay 150ms),
        // lalu posisikan ulang & lepas listener-nya sendiri.
        container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (bottom - top > 0) {
                    findAndPositionAllFabs(rootView, additionalMargin)
                    v.removeOnLayoutChangeListener(this)
                }
            }
        })
    }

    private fun positionFabAboveCurrentBar(fab: View, bottomNavId: Int) {
        val auxFabIds = setOf(
            Utils.getID("fab_second", "id"),
            Utils.getID("fab_auxiliary", "id"),
            Utils.getID("extended_mini_fab", "id"),
            Utils.getID("text_status_fab", "id")
        ).filter { it > 0 }.toSet()

        if (fab.id in auxFabIds) {
            fab.visibility = View.GONE
            fab.scaleX = 0f
            fab.scaleY = 0f
            fab.alpha = 0f
            return
        }

        val additionalMargin = getFabAdditionalMargin()
        applyFabMargin(fab, additionalMargin)
    }

    private fun unclipParents(view: View) {
        var current: ViewParent? = view.parent
        while (current != null && current is ViewGroup) {
            current.clipChildren = false
            current.clipToPadding = false
            if (current.id == android.R.id.content) break
            current = current.parent
        }
    }

    private fun applyFabMargin(fab: View, additionalMargin: Int) {
        val auxFabIds = setOf(
            Utils.getID("fab_second", "id"),
            Utils.getID("fab_auxiliary", "id"),
            Utils.getID("extended_mini_fab", "id"),
            Utils.getID("text_status_fab", "id")
        ).filter { it > 0 }.toSet()

        if (fab.id in auxFabIds) {
            fab.visibility = View.GONE
            fab.scaleX = 0f
            fab.scaleY = 0f
            fab.alpha = 0f
            return
        }

        fab.translationY = 0f
        val lp = fab.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        // Skip adding margin if this FAB is anchored to another view (like a primary FAB).
        // If it's anchored, the CoordinatorLayout will automatically move it when the anchor moves!
        try {
            if (lp.javaClass.name.contains("CoordinatorLayout\$LayoutParams")) {
                val getAnchorId = lp.javaClass.getMethod("getAnchorId")
                val anchorId = getAnchorId.invoke(lp) as Int
                if (anchorId != View.NO_ID) {
                    val anchorName = fab.context.resources.getResourceEntryName(anchorId) ?: ""
                    // If it's anchored to a FAB, skip it to prevent double-lifting
                    if (anchorName.contains("fab", ignoreCase = true) || anchorName.contains("btn", ignoreCase = true)) {
                        return
                    }
                }
            }
        } catch (_: Throwable) {}

        val currentAdded = fab.getTag(TAG_FAB_OFFSET) as? Int ?: 0
        if (currentAdded != additionalMargin) {
            val delta = additionalMargin - currentAdded
            lp.bottomMargin += delta
            fab.setTag(TAG_FAB_OFFSET, additionalMargin)
            fab.layoutParams = lp
            fab.requestLayout()
        }
        
        unclipParents(fab)
    }

    private fun findAndPositionAllFabs(rootView: View, additionalMargin: Int) {
        val auxFabIds = setOf(
            Utils.getID("fab_second", "id"),
            Utils.getID("fab_auxiliary", "id"),
            Utils.getID("extended_mini_fab", "id"),
            Utils.getID("text_status_fab", "id")
        ).filter { it > 0 }.toSet()

        val fabIds = FAB_RESOURCE_NAMES.mapNotNull { name ->
            Utils.getID(name, "id").takeIf { it > 0 }
        }.toSet()

        fun scan(view: View) {
            if (view.id in auxFabIds) {
                if (view.visibility != View.GONE) {
                    view.visibility = View.GONE
                }
                view.scaleX = 0f
                view.scaleY = 0f
                view.alpha = 0f
                return
            }

            val isFab = view.id in fabIds ||
                    view.javaClass.simpleName.contains("FloatingActionButton", ignoreCase = true) ||
                    view.javaClass.simpleName.contains("ExtendedFloatingActionButton", ignoreCase = true)

            if (isFab && view.id != TAG_FLOATING_WRAPPER) {
                applyFabMargin(view, additionalMargin)
            }

            if (view is ViewGroup && view.id != TAG_FLOATING_WRAPPER) {
                for (i in 0 until view.childCount) {
                    scan(view.getChildAt(i))
                }
            }
        }
        scan(rootView)
    }

    private fun resolveBarColor(bar: ViewGroup): Int {
        val bg = bar.background
        if (bg is ColorDrawable) {
            val c = bg.color
            if (c != 0 && c != Color.TRANSPARENT) return c
        }
        return if (DesignUtils.isNightMode()) {
            Color.parseColor("#121212")
        } else {
            Color.parseColor("#FAFAFA")
        }
    }

    private fun isLightColor(color: Int): Boolean {
        if (color == 0 || color == Color.TRANSPARENT) {
            return !DesignUtils.isNightMode()
        }
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        return brightness >= 128
    }

    override fun getPluginName(): String {
        return "Floating Bottom Bar"
    }
}
