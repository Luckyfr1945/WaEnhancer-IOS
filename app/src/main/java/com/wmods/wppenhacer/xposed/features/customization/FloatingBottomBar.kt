package com.wmods.wppenhacer.xposed.features.customization

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.utils.ModuleContextWrapper
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import eightbitlab.com.blurview.BlurView
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class FloatingBottomBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val CORNER_RADIUS_DP = 36f      // Kapsul halus
        private const val SIDE_MARGIN_DP = 16f
        private const val BOTTOM_MARGIN_DP = 16f
        private const val ELEVATION_DP = 10f           // Shadow halus
        private const val FAB_GAP_DP = 8f
        private val FAB_RESOURCE_NAMES = arrayOf("fab", "fab_second", "extended_mini_fab")

        // Unique tag key to identify our floating wrapper FrameLayout
        private const val TAG_FLOATING_WRAPPER = 0x46_42_41_52 // 'FBAR'
        private const val TAG_BACKDROP = 0x46_42_42_44 // 'FBBD'
        private const val TAG_SCRUB_OVERLAY = 0x46_42_53_43 // 'FBSC'

        // --- Ajustes da animação líquida (todo valor visual mora aqui) ---
        private const val ITEM_LIFT_DP = 0f
        private const val ITEM_SCALE = 1.0f          // Sem zoom estranho
        private const val INDICATOR_SQUASH = 1.0f
        private const val INDICATOR_WIDTH_RATIO = 0.88f // Pill membungkus ikon dan label secara pas
        private const val INDICATOR_INSET_DP = 6f      // Inset 6dp dari atas dan bawah bar
        private const val INDICATOR_STRETCH_RATIO = 0.16f  // Alongamento suave na direção do tab
        private const val SLIDE_DURATION_MS = 320L
        private const val SHADOW_IDLE_DP = 4f
        private const val SHADOW_ACTIVE_DP = 10f
        private const val BLUR_RADIUS = 25f            // Desfoque profundo de vidro
        private const val BLUR_RADIUS_LIGHT_BOOST = 0f

        /** Deslocamento do indicador entre abas: mola relativamente calma. */
        private val SLIDE_SPRING = SpringInterpolator(0.5f)

        /** Morph da bolha (ela "estoura" ao chegar) e subida do ícone: mais nervosa. */
        private val MORPH_SPRING = SpringInterpolator(0.36f)
    }

    private val processedBars = WeakHashMap<ViewGroup, Boolean>()
    private val setupAttempts = WeakHashMap<ViewGroup, Int>()
    private val barStates = WeakHashMap<ViewGroup, BarState>()
    /** Posisi horizontal pill disimpan di memori; dipertahankan selama proses hidup. */
    private var pillTranslationX = 0f

    /**
     * Curva "elastic out", substituindo `androidx.dynamicanimation` sem nova dependência.
     * f(0) = 0, f(1) = 1, e o excesso natural acima de 1.0 no meio do caminho é o repique
     * da mola. [p] menor = mais oscilação.
     */
    private class SpringInterpolator(private val p: Float) : Interpolator {
        override fun getInterpolation(input: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f
            val decay = 2.0.pow(-10.0 * input)
            return (decay * sin((input - p / 4.0) * (2.0 * PI) / p) + 1.0).toFloat()
        }
    }

    /**
     * O indicador é desenhado como background do próprio `bottom_nav`.
     * Utiliza deslocamento suave e reflexo de vidro com sombra suave.
     */
    private class LiquidIndicatorDrawable(
        private val fillColor: Int,
        private val shadowColor: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        var centerX = 0f
        var halfWidth = 0f
        var top = 0f
        var bottom = 0f
        var lift = 0f
        var scale = 1f
        var shadowRadius = 0f
        var active = false

        override fun draw(canvas: Canvas) {
            if (!active || halfWidth <= 0f || bottom <= top) return

            val centerY = (top + bottom) * 0.5f - lift
            val halfW = halfWidth * scale
            val halfH = (bottom - top) * 0.5f * scale
            val corner = halfH * 0.85f

            // Sombra suave multicamada
            paint.color = shadowColor
            val baseAlpha = Color.alpha(shadowColor)
            for (i in SHADOW_SPREAD.indices) {
                val grow = (8f) * SHADOW_SPREAD[i]
                paint.alpha = (baseAlpha * SHADOW_ALPHA[i]).toInt().coerceIn(0, 255)
                val drop = grow * 0.3f
                rect.set(
                    centerX - halfW - grow,
                    centerY - halfH - grow + drop,
                    centerX + halfW + grow,
                    centerY + halfH + grow + drop
                )
                canvas.drawRoundRect(rect, corner + grow, corner + grow, paint)
            }

            // Corpo principal da bolha translúcida
            paint.color = fillColor
            paint.alpha = Color.alpha(fillColor)
            rect.set(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }

        override fun setAlpha(alpha: Int) { /* controlado pelas cores fixas */ }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            private val SHADOW_SPREAD = floatArrayOf(1f, 0.62f, 0.28f)
            private val SHADOW_ALPHA = floatArrayOf(0.05f, 0.10f, 0.18f)
        }
    }

    /** Estado por barra. Em [WeakHashMap] para não segurar a Activity. */
    private class BarState {
        var indicator: LiquidIndicatorDrawable? = null
        var backdrop: View? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var visibilityListener: ViewTreeObserver.OnPreDrawListener? = null
        var animator: ValueAnimator? = null
        var items: List<View> = emptyList()
        var selectedIndex = -1
        var originalParent: ViewGroup? = null
        var wrapper: FrameLayout? = null

        // --- Physics Spring Engine State ---
        var isChoreographerActive: Boolean = false
        var currentCenterX: Float = 0f
        var currentHalfWidth: Float = 0f
        var targetCenterX: Float = 0f
        var targetHalfWidth: Float = 0f
        var velocityCenter: Float = 0f
        var velocityWidth: Float = 0f
        var isScrubbing: Boolean = false

        /** Sincroniza a altura do backdrop com a barra após cada layout pass. */
        var layoutSync: View.OnLayoutChangeListener? = null

        /** Overlay de scrubbing do pill e o listener que sincroniza sua altura com a barra. */
        var scrubOverlay: View? = null
        var scrubLayoutSync: View.OnLayoutChangeListener? = null
    }

    /** Pipeline de Refração e Shader AGSL (Android 13+) estilo iOS 26 Liquid Glass */
    private object LiquidGlassShader {
        const val SHADER_SRC = """
            uniform shader image;
            uniform float2 resolution;
            uniform float cornerRadius;
            uniform float refractionStrength;
            uniform float brightnessBoost;
            uniform float rimIntensity;
            uniform float2 fluidOffset;
            uniform float fluidMomentum;

            float sdRoundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            vec4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                float2 halfRes = resolution * 0.5;
                float2 p = fragCoord - halfRes;
                float2 boxSize = halfRes - float2(1.0);
                
                float dist = sdRoundedBox(p, boxSize, cornerRadius);
                if (dist > 0.0) {
                    return vec4(0.0);
                }
                
                // Normal vector for glass edge curvature & refraction
                float2 normal = float2(0.0);
                if (dist > -cornerRadius) {
                    float2 eps = float2(1.0, 0.0);
                    normal = normalize(float2(
                        sdRoundedBox(p + eps.xy, boxSize, cornerRadius) - sdRoundedBox(p - eps.xy, boxSize, cornerRadius),
                        sdRoundedBox(p + eps.yx, boxSize, cornerRadius) - sdRoundedBox(p - eps.yx, boxSize, cornerRadius)
                    ));
                }
                
                // 1. Permanent Optical Convex Lens (Center Magnification / Pembesaran Kaca)
                float2 center = float2(0.5, 0.5);
                float2 delta = uv - center;
                float r2 = dot(delta, delta);
                float lensBulge = (1.0 - clamp(r2 * 2.2, 0.0, 1.0));
                float2 lensDistortion = -delta * lensBulge * 0.038;
                
                // 2. Dynamic Fluid Inertia Displacement (Pull on drag/scroll)
                float2 normCoord = p / halfRes;
                float centerWeight = 1.0 - dot(normCoord, normCoord) * 0.45;
                float2 fluidDisplacement = fluidOffset * centerWeight;
                
                // 3. Edge Optical Refraction (Curved boundary light bend)
                float edge = smoothstep(-cornerRadius * 1.2, 0.0, dist);
                float edgeRefract = pow(edge, 1.2) * refractionStrength * 0.035;
                
                // Composite optical sampling (Lens + Fluid + Edge Refraction)
                float2 sampleUV = uv + lensDistortion + fluidDisplacement - normal * edgeRefract;
                vec4 col = image.eval(clamp(sampleUV, 0.0, 1.0) * resolution);
                col.rgb = pow(col.rgb, vec3(0.95)) * brightnessBoost;
                
                // Subtle crystalline glass tint
                col.rgb += vec3(0.015);
                
                // Top specular highlight line dynamically responding to fluid tilt
                float topGlow = smoothstep(0.0, 1.0, -p.y / halfRes.y + fluidOffset.y * 2.0) * smoothstep(-6.0, 0.0, dist) * 0.25;
                float rim = smoothstep(-2.5, 0.0, dist) * rimIntensity;
                
                col.rgb += vec3(topGlow + rim);
                return col;
            }
        """
    }

    /** Backdrop nativo com RenderNode e AGSL (Android 12+) */
    private class LiquidGlassBackdropView(
        context: Context,
        private val blurRoot: ViewGroup,
        private val container: ViewGroup
    ) : View(context) {

        private val renderNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("GlassBackdrop") else null
        private val rootLocation = IntArray(2)
        private val viewLocation = IntArray(2)
        private val clipPath = Path()
        private val clipRect = RectF()
        private var radius = 0f
        private var isPreDrawAttached = false
        private var runtimeShader: RuntimeShader? = null

        // --- Physics Engine State ---
        private var currentFluidX = 0f
        private var currentFluidY = 0f
        private var targetFluidX = 0f
        private var targetFluidY = 0f
        private var fluidVelocityX = 0f
        private var fluidVelocityY = 0f
        private var isPhysicsActive = false

        private val physicsChoreographer = object : Runnable {
            override fun run() {
                if (!isPhysicsActive && !isShown) return

                // Deslocamento suave sem oscilação/vibração (Smooth Monotonic Easing)
                currentFluidX += (targetFluidX - currentFluidX) * 0.22f
                currentFluidY += (targetFluidY - currentFluidY) * 0.22f

                targetFluidX *= 0.80f
                targetFluidY *= 0.80f

                updateFluidUniforms()
                invalidate()

                val isSettled = kotlin.math.abs(currentFluidX) < 0.0003f &&
                                kotlin.math.abs(currentFluidY) < 0.0003f &&
                                kotlin.math.abs(targetFluidX) < 0.0003f &&
                                kotlin.math.abs(targetFluidY) < 0.0003f

                if (!isSettled) {
                    postOnAnimation(this)
                } else {
                    currentFluidX = 0f
                    currentFluidY = 0f
                    targetFluidX = 0f
                    targetFluidY = 0f
                    isPhysicsActive = false
                    updateFluidUniforms()
                    invalidate()
                }
            }
        }

        fun applyScrollImpulse(dy: Float) {
            val impulse = (dy * 0.0008f).coerceIn(-0.04f, 0.04f)
            targetFluidY += impulse
            startPhysicsLoop()
        }

        fun applyDragImpulse(dx: Float) {
            val impulse = (dx * 0.0008f).coerceIn(-0.05f, 0.05f)
            targetFluidX += impulse
            startPhysicsLoop()
        }

        private fun startPhysicsLoop() {
            if (!isPhysicsActive) {
                isPhysicsActive = true
                removeCallbacks(physicsChoreographer)
                postOnAnimation(physicsChoreographer)
            }
        }

        private fun updateFluidUniforms() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runtimeShader?.let { shader ->
                    shader.setFloatUniform("fluidOffset", currentFluidX, currentFluidY)
                    shader.setFloatUniform("fluidMomentum", kotlin.math.hypot(fluidVelocityX, fluidVelocityY))
                }
            }
        }

        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (isShown) {
                invalidate()
            }
            true
        }

        fun updateRadius(newRadius: Float) {
            radius = newRadius
            updateClipPath()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && width > 0 && height > 0) {
                setupShader(width.toFloat(), height.toFloat())
            }
        }

        private fun updateClipPath() {
            if (width > 0 && height > 0 && radius > 0f) {
                clipPath.reset()
                clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
                clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!isPreDrawAttached) {
                blurRoot.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                isPreDrawAttached = true
            }
            attachScrollWatcher(blurRoot)
        }

        private fun attachScrollWatcher(root: ViewGroup) {
            fun findAndAttach(view: View) {
                if (view is ViewGroup) {
                    view.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                        val dy = (scrollY - oldScrollY).toFloat()
                        if (dy != 0f) {
                            applyScrollImpulse(dy)
                        }
                    }
                    for (i in 0 until view.childCount) {
                        findAndAttach(view.getChildAt(i))
                    }
                }
            }
            findAndAttach(root)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (isPreDrawAttached) {
                blurRoot.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                isPreDrawAttached = false
            }
            removeCallbacks(physicsChoreographer)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                updateClipPath()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    renderNode?.setPosition(0, 0, w, h)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setupShader(w.toFloat(), h.toFloat())
                }
            }
        }

        private fun setupShader(w: Float, h: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val shader = RuntimeShader(LiquidGlassShader.SHADER_SRC)
                    shader.setFloatUniform("resolution", w, h)
                    shader.setFloatUniform("cornerRadius", radius)
                    shader.setFloatUniform("refractionStrength", 2.0f)
                    shader.setFloatUniform("brightnessBoost", 1.06f)
                    shader.setFloatUniform("rimIntensity", 0.15f)
                    shader.setFloatUniform("fluidOffset", currentFluidX, currentFluidY)
                    shader.setFloatUniform("fluidMomentum", 0f)
                    runtimeShader = shader

                    val blurEffect = RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP)
                    val glassEffect = RenderEffect.createRuntimeShaderEffect(shader, "image")
                    val chain = RenderEffect.createChainEffect(glassEffect, blurEffect)
                    renderNode?.setRenderEffect(chain)
                } catch (t: Throwable) {
                    Log.w("FBAR", "RuntimeShader setup failed: ${t.message}")
                    val tileMode = Shader.TileMode.CLAMP
                    val blurEffect = RenderEffect.createBlurEffect(16f, 16f, tileMode)
                    renderNode?.setRenderEffect(blurEffect)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val tileMode = Shader.TileMode.CLAMP
                    val blurEffect = RenderEffect.createBlurEffect(16f, 16f, tileMode)
                    renderNode?.setRenderEffect(blurEffect)
                } catch (t: Throwable) {
                    Log.w("FBAR", "Blur effect setup failed: ${t.message}")
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderNode != null) {
                if (width <= 0 || height <= 0) return

                blurRoot.getLocationInWindow(rootLocation)
                getLocationInWindow(viewLocation)

                val dx = (rootLocation[0] - viewLocation[0]).toFloat()
                val dy = (rootLocation[1] - viewLocation[1]).toFloat()

                val recordingCanvas = renderNode.beginRecording(width, height)
                recordingCanvas.save()
                recordingCanvas.translate(dx, dy)

                blurRoot.draw(recordingCanvas)

                recordingCanvas.restore()
                renderNode.endRecording()

                canvas.save()
                if (!clipPath.isEmpty) {
                    canvas.clipPath(clipPath)
                }
                canvas.drawRenderNode(renderNode)
                canvas.restore()
            } else {
                super.onDraw(canvas)
            }
        }
    }

    override fun doHook() {
        if (!prefs.getBoolean("floating_bottom_bar", false)) return

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

        try {
            val itemClass = classLoader.loadClass("X.0hl")
            XposedBridge.hookAllMethods(itemClass, "setSelected", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    disableNativeActiveIndicator(view)
                    resetAnimations(view)
                }
            })
            XposedBridge.hookAllMethods(itemClass, "refreshDrawableState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    disableNativeActiveIndicator(view)
                    resetAnimations(view)
                }
            })
            XposedBridge.hookAllMethods(itemClass, "getActiveIndicatorDrawable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
            XposedBridge.hookAllMethods(itemClass, "A01", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    disableNativeActiveIndicator(view)
                    resetAnimations(view)
                    param.result = null
                }
            })
        } catch (e: Throwable) {
            logDebug("FloatingBottomBar: Hook X.0hl failed: ${e.message}")
        }
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
            // Check if already wrapped in our floating wrapper
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

            // Detach bar from its current parent
            val originalParent = bar.parent as? ViewGroup ?: return false

            // Debug: dump bar position before we move it
            android.util.Log.i("FBAR", "BAR: ${bar.javaClass.name} h=${bar.height} w=${bar.width} top=${bar.top}")
            var p: android.view.ViewParent? = bar.parent
            while (p is View) {
                android.util.Log.i("FBAR", "  parent: ${p.javaClass.name} id=${p.id} h=${p.height} w=${p.width}")
                p = p.parent
            }

            val rootView = findRootView(bar) ?: return false
            android.util.Log.i("FBAR", "ROOT: ${rootView.javaClass.name} h=${rootView.height}")

            if (originalParent.parent === rootView &&
                originalParent.getTag(TAG_FLOATING_WRAPPER) == true) {
                return true
            }
            originalParent.removeView(bar)

            val state = barStates.getOrPut(bar) { BarState() }
            state.originalParent = originalParent

            // Create a minimal wrapper FrameLayout sized just for bar
            val wrapper = FrameLayout(bar.context).apply {
                setTag(TAG_FLOATING_WRAPPER, true)
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = true
                clipToPadding = true
            }
            state.wrapper = wrapper

            val wrapperParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            // A barra fica MATCH_PARENT dentro do wrapper. O gravity BOTTOM é uma salvaguarda:
            // se algum filho voltar a esticar o wrapper, a barra permanece colada embaixo.
            val barParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            wrapper.addView(bar, barParams)
            rootView.addView(wrapper, wrapperParams)

            updateOverlayLayout(rootView, wrapper, bar)
            applyTransparentShadowStyle(rootView, wrapper, bar)
            positionFabsAboveBar(rootView, wrapper)
            setupVisibilitySync(rootView, wrapper, bar, state)

            logDebug("FloatingBottomBar: wrapped bar in floating overlay")
            return true
        } catch (e: Throwable) {
            log(e)
            return false
        }
    }

    /**
     * Permite que o usuário arraste a pílula horizontalmente para reposicioná-la.
     * A lógica discrimina toque puro (dx < slop) e arrasto horizontal claro
     * (dx > slop E dx > 2×dy), para que os cliques nas abas continuem funcionando.
     */
    private fun setupVisibilitySync(
        rootView: FrameLayout,
        wrapper: FrameLayout,
        bar: ViewGroup,
        state: BarState
    ) {
        state.visibilityListener?.let {
            if (rootView.viewTreeObserver.isAlive) {
                rootView.viewTreeObserver.removeOnPreDrawListener(it)
            }
        }

        val listener = ViewTreeObserver.OnPreDrawListener {
            val origParent = state.originalParent
            // 1. O contêiner original de navegação foi ocultado pelo WhatsApp (ex: busca ou tela de chat)
            val isOrigParentVisible = origParent == null || (origParent.isShown && origParent.visibility == View.VISIBLE)

            // 2. A própria barra foi ocultada
            val isBarVisible = bar.visibility == View.VISIBLE

            // 3. Teclado virtual (IME) aberto
            val insets = ViewCompat.getRootWindowInsets(rootView)
            val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
                    (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 100

            // 4. Campo de texto (busca/mensagem) focado
            val focusedView = rootView.findFocus()
            val isEditing = focusedView is EditText

            val shouldShow = isOrigParentVisible && isBarVisible && !imeVisible && !isEditing
            val targetVisibility = if (shouldShow) View.VISIBLE else View.GONE

            if (wrapper.visibility != targetVisibility) {
                wrapper.visibility = targetVisibility
            }
            true
        }

        state.visibilityListener = listener
        rootView.viewTreeObserver.addOnPreDrawListener(listener)
    }


    private fun updateOverlayLayout(rootView: FrameLayout, container: ViewGroup, bar: ViewGroup) {
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val sideMargin = Utils.dipToPixels(SIDE_MARGIN_DP)
        params.gravity = Gravity.BOTTOM
        params.leftMargin = sideMargin
        params.rightMargin = sideMargin
        params.bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
        container.layoutParams = params

        val barParams = bar.layoutParams ?: return
        barParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        barParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        (barParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        bar.layoutParams = barParams
    }

    private fun navigationBarInset(view: View): Int {
        return ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    }

    private fun findRootView(startView: View): FrameLayout? {
        // From logcat hierarchy:
        // WDSBottomBar -> LinearLayout(h=221) -> ... LinearLayouts ... -> FrameLayout(root_view, h=2356)
        // -> ContentFrameLayout(h=2356) -> FitWindowsFrameLayout(h=2356)
        // -> FrameLayout(-1, h=2356)  ← PURE FrameLayout, target!
        // -> LinearLayout -> DecorView(h=2400)
        //
        // We want the highest pure android.widget.FrameLayout (not ContentFrameLayout, not DecorView)
        // since those subclasses have special inset/padding behavior.
        var candidate: FrameLayout? = null
        var p: android.view.ViewParent? = startView.parent
        while (p != null) {
            // Only pure android.widget.FrameLayout — not subclasses
            if (p.javaClass == android.widget.FrameLayout::class.java) {
                candidate = p as FrameLayout
            }
            p = p.parent
        }
        android.util.Log.i("FBAR", "findRootView -> ${candidate?.javaClass?.simpleName} id=${candidate?.id} h=${candidate?.height}")
        if (candidate != null) return candidate

        // Fallback: android.R.id.content
        val content = startView.rootView.findViewById<ViewGroup>(android.R.id.content)
        return (content as? FrameLayout) ?: (startView.rootView as? FrameLayout)
    }

    /**
     * Raiz cujo conteúdo será borrado. Precisa ser um irmão do nosso wrapper — nunca a
     * própria [rootView] — porque o BlurView desenha `rootView.draw(internalCanvas)` inteiro
     * no snapshot. Se o wrapper entrasse nesse desenho, os ícones apareceriam como um
     * fantasma borrado atrás deles mesmos.
     */
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
        container.clipChildren = true
        container.clipToPadding = true

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

        // Blur adaptivo: fundo claro precisa de mais desfoque para o efeito ser visível
        val brightness = (Color.red(barColor) * 299 + Color.green(barColor) * 587 +
                          Color.blue(barColor) * 114) / 1000f / 255f
        val adaptiveBlurRadius = BLUR_RADIUS + brightness * BLUR_RADIUS_LIGHT_BOOST

        // Base líquida pura (sem cinza, vidro cristalino como HookVip)
        val pillAlpha = if (blurEnabled) 10 else 225
        val pillColor = if (isLight) {
            Color.argb(pillAlpha, 255, 255, 255)
        } else {
            Color.argb(pillAlpha, 255, 255, 255)
        }

        // Borda externa de vidro
        val strokeColor = Color.argb(35, 255, 255, 255)

        // Gradiente de topo com linha de brilho especular e glow
        val sheenTop = Color.argb(55, 255, 255, 255)
        val sheenGlow = Color.argb(20, 255, 255, 255)
        val sheenBot = Color.TRANSPARENT

        val corners = floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        val basePill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
            setColor(pillColor)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(sheenTop, sheenGlow, sheenBot, sheenBot)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
        }
        val innerStroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
            setColor(Color.TRANSPARENT)
            setStroke(Utils.dipToPixels(1f), Color.argb(20, 255, 255, 255))
        }
        val rim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = corners
            setColor(Color.TRANSPARENT)
            setStroke(Utils.dipToPixels(1f), strokeColor)
        }

        val pill = LayerDrawable(arrayOf(basePill, innerStroke, rim))

        val state = barStates.getOrPut(bar) { BarState() }

        val backdrop = obtainBackdrop(rootView, container, bar, blurEnabled, state)
        val elevPx = Utils.dipToPixels(ELEVATION_DP).toFloat()
        backdrop.background = pill
        backdrop.clipToOutline = true
        backdrop.outlineProvider = ViewOutlineProvider.BACKGROUND
        backdrop.elevation = elevPx  // shadow dari pill

        if (backdrop is LiquidGlassBackdropView) {
            backdrop.updateRadius(radius)
        } else if (backdrop is BlurView && blurEnabled) {
            val blurRoot2 = findBlurRoot(rootView, container)
            if (blurRoot2 != null) {
                try {
                    backdrop.setupWith(blurRoot2)
                        .setFrameClearDrawable(null)
                        .setBlurRadius(BLUR_RADIUS)
                        .setOverlayColor(Color.argb(8, 255, 255, 255))
                        .setBlurAutoUpdate(true)
                } catch (_: Throwable) { /* ignore re-setup failure */ }
            }
        }

        bar.clipToOutline = false
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.elevation = elevPx + 2f
        bar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setEmpty()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            bar.setRenderEffect(null)
        }
        bar.bringToFront()

        // Indicador suave e calmo
        val indicatorColor = if (isLight) {
            Color.argb(25, 0, 0, 0)
        } else {
            Color.argb(32, 255, 255, 255)
        }
        val shadowColor = Color.argb(25, 0, 0, 0)
        val indicator = LiquidIndicatorDrawable(indicatorColor, shadowColor)
        state.indicator = indicator
        state.selectedIndex = -1
        bar.background = indicator

        // Indicador nativo do Material desligado: ele só aparece/desaparece na posição nova,
        // sem deslizar. Quem desenha agora é o LiquidIndicatorDrawable.
        try {
            val setIndicatorEnabled = bar.javaClass
                .getMethod("setItemActiveIndicatorEnabled", Boolean::class.javaPrimitiveType)
            setIndicatorEnabled.invoke(bar, false)
        } catch (e: Exception) {
            logDebug("Failed to disable native active indicator: ${e.message}")
        }

        // Always-labeled
        try {
            val setLabelMode = bar.javaClass
                .getMethod("setLabelVisibilityMode", Int::class.javaPrimitiveType)
            setLabelMode.invoke(bar, 1)
        } catch (e: Exception) { /* ignore */ }

        setupPillScrubbing(container, bar, state)
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

        // O backdrop tem sempre a mesma altura que a barra (bar.height) e topMargin 0
        val initialHeight = if (bar.height > 0) bar.height else Utils.dipToPixels(56)
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

    /**
     * Mantém a altura do [backdrop] sempre igual a [bar.height] a cada passagem de layout.
     * O backdrop nunca muda de proporção ou desloca verticalmente.
     */
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
        // Força sync imediato se bar já tem altura
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                LiquidGlassBackdropView(container.context, blurRoot, container)
            } catch (e: Throwable) {
                logDebug("FloatingBottomBar: native glass failed (${e.message}), falling back to BlurView")
                createBlurViewFallback(container, blurRoot)
            }
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

    // --- Troca de aba e animação ------------------------------------------------------

    /**
     * Observa qual item está marcado a cada frame de desenho. Deliberadamente não usamos
     * `setOnItemSelectedListener` (substituiria o listener do WhatsApp) nem um hook em
     * `setChecked` (a classe do item é ofuscada). O custo é percorrer ~5 `drawableState`
     * por frame.
     */
    private fun attachSelectionWatcher(bar: ViewGroup, state: BarState) {
        state.preDraw?.let {
            if (bar.viewTreeObserver.isAlive) bar.viewTreeObserver.removeOnPreDrawListener(it)
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            try {
                syncSelection(bar, state)
            } catch (e: Throwable) {
                logDebug("FloatingBottomBar: selection sync failed: ${e.message}")
            }
            true
        }
        state.preDraw = listener
        bar.viewTreeObserver.addOnPreDrawListener(listener)
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
        // 2. Panggilan / Calls
        if (text.contains("panggilan") || text.contains("call") || text.contains("llamada")) return 2
        // 3. Komunitas / Communities
        if (text.contains("komunitas") || text.contains("communit") || text.contains("comunidad")) return 3
        // 4. Chat / Chats / Obrolan / Percakapan
        if (text.contains("chat") || text.contains("obrolan") || text.contains("percakapan") || text.contains("conversa")) return 4
        // 5. Anda / You / Pengaturan / Settings / Profile / Tools
        if (text.contains("anda") || text.contains("you") || text.contains("tú") || text.contains("voce") || text.contains("você") || text.contains("setting") || text.contains("profil") || text.contains("pengaturan")) return 5

        return 99
    }

    private fun reorderMenuTabs(bar: ViewGroup, state: BarState) {
        val items = findItemViews(bar)
        if (items.size < 4) return
        val parent = items[0].parent as? ViewGroup ?: return

        val currentChildren = (0 until parent.childCount).map { parent.getChildAt(it) }
        val sorted = currentChildren.sortedBy { getTabRank(it) }
        
        if (currentChildren == sorted) return

        logDebug("FloatingBottomBar: Reordering tabs to: ${sorted.map { getItemLabel(it) }}")
        parent.removeAllViews()
        for (child in sorted) {
            parent.addView(child)
        }
        state.items = sorted
        parent.requestLayout()
        parent.invalidate()
    }

    private fun syncSelection(bar: ViewGroup, state: BarState) {
        reorderMenuTabs(bar, state)
        var items = state.items
        if (items.isEmpty() || items[0].parent == null) {
            items = findItemViews(bar)
            state.items = items
            if (items.isNotEmpty()) {
                logItemHierarchy(items)
            }
        }
        if (items.isEmpty()) return

        // Remove backgrounds, foregrounds/ripples e active indicator nativo
        items.forEach {
            clearBackgroundsRecursively(it)
            disableNativeActiveIndicator(it)
        }

        val selected = selectedIndexOf(items)
        if (selected < 0 || selected == state.selectedIndex) return
        if (items[selected].width <= 0) return
        animateToItem(bar, state, items, selected)
    }

    /** Desativa o active indicator nativo do Material 3/WhatsApp no item. */
    private fun disableNativeActiveIndicator(item: View) {
        // 1. Acesso direto ao campo A0P (Active Indicator View em X.0hl)
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

        // 2. Limpar drawables e flags booleanas em X.0hl
        try {
            XposedHelpers.setObjectField(item, "A04", null)
            XposedHelpers.setObjectField(item, "A0L", null)
            XposedHelpers.setObjectField(item, "A0M", null)
            XposedHelpers.setBooleanField(item, "A08", false)
            XposedHelpers.setBooleanField(item, "A09", false)
            XposedHelpers.setBooleanField(item, "A0A", false)
            XposedHelpers.setBooleanField(item, "A0N", false)
        } catch (_: Throwable) {}

        // 3. Procura no ViewGroup do container do ícone
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

    /** Reseta todas as animações e transformações em uma View e seus filhos. */
    private fun resetAnimations(v: View) {
        try {
            v.animate()?.cancel()
            v.clearAnimation()

            if (android.os.Build.VERSION.SDK_INT >= 21) {
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

            // Cancela animadores internos de X.0hp e X.0hl
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

    /** Limpa backgrounds e foregrounds/ripples de uma view e de todos os seus filhos recursivamente. */
    private fun clearBackgroundsRecursively(view: View) {
        view.background = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            view.foreground = null
        }
        resetAnimations(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearBackgroundsRecursively(view.getChildAt(i))
            }
        }
    }

    private fun logItemHierarchy(items: List<View>) {
        if (items.isEmpty()) return
        val first = items[0]
        val bgName = first.background?.javaClass?.name ?: "null"
        val fgName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            first.foreground?.javaClass?.name ?: "null"
        } else "N/A"
        val info1 = "FloatingBottomBar: Found ${items.size} items of class=${first.javaClass.name}, super=${first.javaClass.superclass?.name}, bg=$bgName, fg=$fgName"
        logDebug(info1)
        Log.i("Vector-lsposed", info1)

        // Log declared fields & methods
        var c: Class<*>? = first.javaClass
        while (c != null && c != ViewGroup::class.java && c != View::class.java && c != Any::class.java) {
            val fieldList = c.declaredFields.map { f ->
                try {
                    f.isAccessible = true
                    "${f.name}: ${f.type.simpleName}=${f.get(first)?.javaClass?.simpleName ?: "null"}"
                } catch (_: Throwable) {
                    "${f.name}: ${f.type.simpleName}"
                }
            }.joinToString(", ")
            val info2 = "FloatingBottomBar: Fields of ${c.name}: $fieldList"
            logDebug(info2)
            Log.i("Vector-lsposed", info2)

            val methodList = c.declaredMethods.take(15).map { m ->
                "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}): ${m.returnType.simpleName}"
            }.joinToString(", ")
            val infoMethods = "FloatingBottomBar: Methods of ${c.name}: $methodList"
            logDebug(infoMethods)
            Log.i("Vector-lsposed", infoMethods)

            c = c.superclass
        }

        if (first is ViewGroup) {
            val childInfo = (0 until first.childCount).map { i ->
                val ch = first.getChildAt(i)
                val chBg = ch.background?.javaClass?.simpleName ?: "null"
                var grandInfo = ""
                if (ch is ViewGroup) {
                    grandInfo = " -> [" + (0 until ch.childCount).map { j ->
                        val gc = ch.getChildAt(j)
                        "${gc.javaClass.simpleName}(id=${gc.id}, bg=${gc.background?.javaClass?.simpleName})"
                    }.joinToString(", ") + "]"
                }
                "${ch.javaClass.simpleName}(id=${ch.id}, bg=$chBg)$grandInfo"
            }.joinToString("; ")
            val info3 = "FloatingBottomBar: Item[0] hierarchy: $childInfo"
            logDebug(info3)
            Log.i("Vector-lsposed", info3)
        }

        Log.i("FBAR", "--- DUMP ITEM[0] ---")
        dump(first)
        Log.i("FBAR", "--------------------")

        if (!xmlDumped) {
            xmlDumped = true
            Log.i("FBAR_XML", "================ BOTTOM BAR XML DUMP ================")
            val rootToDump = (first.parent?.parent as? View) ?: (first.parent as? View) ?: first
            dumpXml(rootToDump)
            Log.i("FBAR_XML", "=====================================================")
        }
    }

    private var xmlDumped = false

    private fun dumpXml(view: View, depth: Int = 0) {
        val pad = "  ".repeat(depth)
        val resName = try {
            if (view.id > 0) view.resources.getResourceEntryName(view.id) else "id_${view.id}"
        } catch (_: Throwable) { "id_${view.id}" }
        val bounds = "l=${view.left},t=${view.top},w=${view.width},h=${view.height},tY=${view.translationY}"
        val bg = view.background?.let { it.javaClass.simpleName } ?: "none"
        val fg = if (android.os.Build.VERSION.SDK_INT >= 23) (view.foreground?.let { it.javaClass.simpleName } ?: "none") else "N/A"
        if (view is ViewGroup && view.childCount > 0) {
            Log.i("FBAR_XML", "$pad<${view.javaClass.simpleName} id=\"$resName\" bounds=\"$bounds\" bg=\"$bg\" fg=\"$fg\">")
            for (i in 0 until view.childCount) {
                dumpXml(view.getChildAt(i), depth + 1)
            }
            Log.i("FBAR_XML", "$pad</${view.javaClass.simpleName}>")
        } else {
            val text = if (view is TextView) " text=\"${view.text}\"" else ""
            Log.i("FBAR_XML", "$pad<${view.javaClass.simpleName} id=\"$resName\" bounds=\"$bounds\" bg=\"$bg\" fg=\"$fg\"$text />")
        }
    }

    private fun dump(view: View, depth: Int = 0) {
        val pad = "  ".repeat(depth)
        Log.i(
            "FBAR",
            "$pad${view.javaClass.name} " +
            "id=${view.id} " +
            "bg=${view.background?.javaClass?.name} " +
            "fg=${if (android.os.Build.VERSION.SDK_INT >= 23) view.foreground?.javaClass?.name else "N/A"}"
        )

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dump(view.getChildAt(i), depth + 1)
            }
        }
    }

    /** Busca em largura o primeiro ViewGroup com >= 3 filhos visíveis da mesma classe. */
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
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        return x to y
    }

    /**
     * Motor de Física (Spring Physics Engine) via Choreographer (120 Hz).
     * Conduz deslocamento suave e natural da cápsula sem ValueAnimator.
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

                // Deslocamento suave e direto sem oscilação, mola ou vibração (Smooth Monotonic Easing)
                val deltaX = state.targetCenterX - state.currentCenterX
                val deltaW = state.targetHalfWidth - state.currentHalfWidth

                state.currentCenterX += deltaX * 0.28f
                state.currentHalfWidth += deltaW * 0.28f

                indicator.centerX = state.currentCenterX
                indicator.halfWidth = state.currentHalfWidth
                indicator.active = true
                indicator.invalidateSelf()
                bar.invalidate()

                val isSettled = kotlin.math.abs(deltaX) < 0.2f &&
                                kotlin.math.abs(deltaW) < 0.2f

                if (!isSettled || state.isScrubbing) {
                    choreographer.postFrameCallback(this)
                } else {
                    state.currentCenterX = state.targetCenterX
                    state.currentHalfWidth = state.targetHalfWidth
                    indicator.centerX = state.targetCenterX
                    indicator.halfWidth = state.targetHalfWidth
                    state.isChoreographerActive = false
                    indicator.invalidateSelf()
                    bar.invalidate()
                }
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun setupPillScrubbing(wrapper: ViewGroup, bar: ViewGroup, state: BarState) {
        // Esta função é reexecutada a cada setupFloatingBar(); sem limpar o overlay anterior
        // eles se acumulariam sobre a barra (e vazariam listeners de layout).
        for (i in wrapper.childCount - 1 downTo 0) {
            if (wrapper.getChildAt(i).getTag(TAG_SCRUB_OVERLAY) == true) {
                wrapper.removeViewAt(i)
            }
        }
        state.scrubLayoutSync?.let { bar.removeOnLayoutChangeListener(it) }
        state.scrubLayoutSync = null
        state.scrubOverlay = null

        val overlay = View(wrapper.context)
        overlay.setTag(TAG_SCRUB_OVERLAY, true)
        // A altura acompanha a barra, nunca MATCH_PARENT: o wrapper é WRAP_CONTENT, e uma View
        // simples medida sob AT_MOST se expande até a altura inteira disponível. Isso faria o
        // wrapper ocupar a tela toda, anulando seu gravity BOTTOM e jogando a barra para o topo.
        overlay.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (bar.height > 0) bar.height else Utils.dipToPixels(56)
        )

        var dragStartX = 0f
        var dragStartY = 0f
        var pillStartCenterX = 0f
        var isPillTouch = false
        var isDragging = false
        val slopPx = Utils.dipToPixels(6f).toFloat()

        overlay.setOnTouchListener { v, event ->
            val items = state.items
            val indicator = state.indicator
            if (items.isEmpty() || indicator == null || !indicator.active) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val dx = event.x - indicator.centerX
                    val pillCenterY = (indicator.top + indicator.bottom) * 0.5f
                    val dy = event.y - pillCenterY
                    val halfH = (indicator.bottom - indicator.top) * 0.5f

                    // Verifica se o toque inicial foi exatamente dentro da cápsula do pill
                    val insidePill = kotlin.math.abs(dx) <= indicator.halfWidth &&
                                     kotlin.math.abs(dy) <= halfH

                    if (!insidePill) {
                        isPillTouch = false
                        isDragging = false
                        return@setOnTouchListener false // Permite que itens fora da cápsula recebam o toque normal!
                    }

                    isPillTouch = true
                    isDragging = false
                    dragStartX = event.x
                    dragStartY = event.y
                    pillStartCenterX = indicator.centerX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true // Consome o DOWN, capturando a stream de touch!
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isPillTouch) return@setOnTouchListener false

                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    if (!isDragging && (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx)) {
                        isDragging = true
                        state.isScrubbing = true
                    }

                    if (isDragging) {
                        val firstItem = items.first()
                        val lastItem = items.last()
                        val minX = offsetInBar(bar, firstItem).first + firstItem.width / 2f
                        val maxX = offsetInBar(bar, lastItem).first + lastItem.width / 2f
                        val newCenterX = (pillStartCenterX + dx).coerceIn(minX, maxX)

                        state.currentCenterX = newCenterX
                        state.targetCenterX = newCenterX
                        indicator.centerX = newCenterX
                        indicator.invalidateSelf()
                        bar.invalidate()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isPillTouch) return@setOnTouchListener false

                    val wasDragging = isDragging
                    isPillTouch = false
                    isDragging = false
                    state.isScrubbing = false

                    if (wasDragging) {
                        // Snap suave para o item mais próximo do centro atual do pill
                        val currentX = indicator.centerX
                        val closestIndex = items.indices.minByOrNull { idx ->
                            val item = items[idx]
                            val center = offsetInBar(bar, item).first + item.width / 2f
                            kotlin.math.abs(center - currentX)
                        } ?: state.selectedIndex

                        if (closestIndex in items.indices) {
                            items[closestIndex].performClick()
                            animateToItem(bar, state, items, closestIndex)
                        }
                    } else {
                        // Apenas um tap direto no pill atual
                        val currentIndex = state.selectedIndex
                        if (currentIndex in items.indices) {
                            items[currentIndex].performClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        
        wrapper.addView(overlay)
        state.scrubOverlay = overlay
        syncScrubOverlayHeight(bar, overlay, state)
    }

    /** Mantém o overlay de scrubbing exatamente do tamanho da barra após cada layout pass. */
    private fun syncScrubOverlayHeight(bar: ViewGroup, overlay: View, state: BarState) {
        state.scrubLayoutSync?.let { bar.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val h = bar.height
            if (h > 0) {
                val lp = overlay.layoutParams as? FrameLayout.LayoutParams
                    ?: return@OnLayoutChangeListener
                if (lp.height != h) {
                    lp.height = h
                    overlay.layoutParams = lp
                }
            }
        }
        state.scrubLayoutSync = listener
        bar.addOnLayoutChangeListener(listener)
        if (bar.height > 0) {
            val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.height != bar.height) {
                lp.height = bar.height
                overlay.layoutParams = lp
            }
        }
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

        val toCenter = offsetX + target.width / 2f
        val toHalfWidth = target.width * INDICATOR_WIDTH_RATIO / 2f
        val previousIndex = state.selectedIndex
        val firstRun = previousIndex < 0 || !indicator.active

        state.selectedIndex = newIndex

        val barH = (if (bar.height > 0) bar.height else target.height).toFloat()
        indicator.top = inset
        indicator.bottom = (barH - inset).coerceAtLeast(inset + 1f)

        if (firstRun) {
            state.currentCenterX = toCenter
            state.currentHalfWidth = toHalfWidth
            state.targetCenterX = toCenter
            state.targetHalfWidth = toHalfWidth
            state.velocityCenter = 0f
            state.velocityWidth = 0f
            indicator.centerX = toCenter
            indicator.halfWidth = toHalfWidth
            indicator.active = true
            indicator.invalidateSelf()
            bar.invalidate()
        } else {
            state.targetCenterX = toCenter
            state.targetHalfWidth = toHalfWidth
            startPillPhysics(bar, state)
        }
    }

    private fun teardownBarState(bar: ViewGroup) {
        val state = barStates.remove(bar) ?: return
        state.isChoreographerActive = false
        state.animator?.cancel()
        state.preDraw?.let {
            if (bar.viewTreeObserver.isAlive) bar.viewTreeObserver.removeOnPreDrawListener(it)
        }
        state.visibilityListener?.let {
            if (bar.rootView?.viewTreeObserver?.isAlive == true) {
                bar.rootView.viewTreeObserver.removeOnPreDrawListener(it)
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

    private fun positionFabsAboveBar(rootView: ViewGroup, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFabsAboveBar(rootView, container) }, 100L)
            return
        }

        val offset = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        for (name in FAB_RESOURCE_NAMES) {
            val id = Utils.getID(name, "id")
            if (id <= 0) continue
            rootView.findViewById<View>(id)?.let { fab ->
                fab.translationY = offset
                fab.bringToFront()
            }
        }
    }

    private fun positionFabAboveCurrentBar(fab: View, bottomNavId: Int) {
        val bottomNav = fab.rootView.findViewById<View>(bottomNavId) ?: return
        val container = bottomNav.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) return
        positionFab(fab, container)
    }

    private fun positionFab(fab: View, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFab(fab, container) }, 100L)
            return
        }

        fab.translationY = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        fab.bringToFront()
    }

    private fun resolveBarColor(bar: ViewGroup): Int {
        val currentBg = bar.background
        if (currentBg is ColorDrawable) {
            return currentBg.color
        }
        return try {
            val typedValue = TypedValue()
            bar.context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            typedValue.data
        } catch (_: Throwable) {
            0xFF121212.toInt()
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 299 + green * 587 + blue * 114) / 1000 > 180
    }

    override fun getPluginName(): String {
        return "Floating Bottom Bar"
    }
}
