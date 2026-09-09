package com.wmods.wppenhacer.xposed.features.media

import android.content.SharedPreferences
import androidx.core.content.edit
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache
import com.wmods.wppenhacer.xposed.features.general.Others
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

class MediaQuality(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        const val EDGE_WIDTH = 1920
        const val BITRATE = 10_000
    }

    override fun doHook() {
        val videoQuality = prefs.getBoolean("videoquality", false)
        val imageQuality = prefs.getBoolean("imagequality", false)
        val maxSize = prefs.getFloat("video_limit_size", 60f).toInt().coerceIn(30, 120)

        // Disable manual calculation ProcessMediaQuality
        Others.propsBoolean[14447] = false

        // Enable Media Quality selection for Stories
        enableMediaQualityForStories()

        val videoRealResolution = prefs.getBoolean("video_real_resolution", false)
        val videoMaxFps = prefs.getBoolean("video_maxfps", false)
        val targetEdge = if (videoRealResolution) 3840 else EDGE_WIDTH
        val targetBitrate = if (videoRealResolution) 40_000 else BITRATE
        val targetFps = if (videoMaxFps) 60 else 30

        if (videoQuality) {
            Others.propsBoolean[5549] = true
            Others.propsBoolean[7589] = true
            Others.propsBoolean[6972] = true
            Others.propsBoolean[15708] = true

            val processVideoQualityClass = Unobfuscator.loadProcessVideoQualityClass(classLoader)
            val fieldsVideoQuality = Unobfuscator.getAllMapFields(processVideoQualityClass)

            fieldsVideoQuality.keys.forEach {
                fieldsVideoQuality[it]?.isAccessible = true
            }

            XposedBridge.hookAllConstructors(processVideoQualityClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject
                    fieldsVideoQuality["videoLimitMb"]?.setInt(instance, maxSize)
                    fieldsVideoQuality["videoMaxEdge"]?.setInt(instance, targetEdge)
                    fieldsVideoQuality["videoMaxBitrate"]?.setInt(instance, targetBitrate * 1000)
                    fieldsVideoQuality["frameRate"]?.setInt(instance, targetFps)
                    fieldsVideoQuality["mainHighBitRate"]?.set(instance, null)
                    fieldsVideoQuality["shouldRetainAspectRatio"]?.setBoolean(instance, true)
                }
            })

            val mediaDataVideoConfiguration =
                Unobfuscator.loadMediaDataVideoConfigurationClass(classLoader)
            val fieldsMediaDataVideoConfiguration =
                Unobfuscator.getAllMapFields(mediaDataVideoConfiguration)

            val videoTranscoderStart = Unobfuscator.loadVideoTranscoderStartMethod(classLoader)
            if (videoTranscoderStart != null) {
                XposedBridge.hookMethod(videoTranscoderStart, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val videoProcessor = param.args.getOrNull(0) ?: return
                            val fieldMediaDataVideoConfiguration = ReflectionUtils.getFieldByType(
                                videoProcessor.javaClass,
                                mediaDataVideoConfiguration
                            ) ?: return
                            val mediaDataVideoConfigObj =
                                fieldMediaDataVideoConfiguration.get(videoProcessor) ?: return
                            val fieldforceSingleTranscoding =
                                fieldsMediaDataVideoConfiguration["forceSingleTranscoding"]
                            fieldforceSingleTranscoding?.setBoolean(mediaDataVideoConfigObj, true)
                        } catch (e: Throwable) {
                            logDebug("MediaQuality: forceSingleTranscoding failed: ${e.message}")
                        }
                    }
                })
            }

            Others.propsBoolean[18888] = true
            listOf(594, 12852).forEach { Others.propsInteger[it] = targetEdge }
            listOf(4686, 3654, 3183, 4685).forEach { Others.propsInteger[it] = targetEdge }
            listOf(3755, 3756, 3757, 3758).forEach { Others.propsInteger[it] = targetBitrate }
            if (videoMaxFps) {
                listOf(4687, 3655).forEach { Others.propsInteger[it] = 60 }
            }
        }

        if (imageQuality) {
            val processImageQualityClass = Unobfuscator.loadProcessImageQualityClass(classLoader)
            val fieldsProcessImageQuality = Unobfuscator.getAllMapFields(processImageQualityClass)

            val maxKb = 100 * 1024
            val targetImageMaxEdge = 4096 // Conservative 4K-class limit for stability

            XposedBridge.hookAllConstructors(processImageQualityClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val processImageQuality = param.thisObject
                    val fieldimageMaxSize = fieldsProcessImageQuality["maxKb"]
                    val fieldimageMaxQuality = fieldsProcessImageQuality["quality"]
                    val fieldimageMaxEdge = fieldsProcessImageQuality["maxEdge"]

                    fieldimageMaxSize?.setInt(processImageQuality, maxKb)
                    fieldimageMaxQuality?.setInt(processImageQuality, 100)
                    fieldimageMaxEdge?.setInt(processImageQuality, targetImageMaxEdge)
                }
            })

            listOf(1577, 6030, 2656, 15752, 15746).forEach { Others.propsInteger[it] = maxKb }
            listOf(1581, 1575, 1578, 6029, 2655, 15749).forEach {
                Others.propsInteger[it] = 100
            }
            Others.propsBoolean[6033] = true
            Others.propsBoolean[9569] = false
            listOf(1576, 2654, 6032, 15748, 3068).forEach { Others.propsInteger[it] = targetImageMaxEdge }
        }
    }

    private fun enableMediaQualityForStories() {
        val prefs = UnobfuscatorCache.getInstance().sPrefsCacheHooks
        var legacyQualitySelection = prefs.getInt("legacy_quality_selection", -1)

        if (legacyQualitySelection != 0) {
            try {
                val hookMediaQualitySelection =
                    Unobfuscator.loadMediaQualitySelectionMethod(classLoader)
                if (hookMediaQualitySelection.returnType == java.lang.Boolean.TYPE || hookMediaQualitySelection.returnType == java.lang.Boolean::class.java) {
                    XposedBridge.hookMethod(
                        hookMediaQualitySelection,
                        XC_MethodReplacement.returnConstant(true)
                    )
                } else {
                    XposedBridge.hookMethod(hookMediaQualitySelection, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            ReflectionUtils.blockMethodExecution(param)
                        }
                    })
                }
                legacyQualitySelection = 1
            } catch (_: Exception) {
                legacyQualitySelection = 0
            }
        }

        if (legacyQualitySelection != 1) {
            val bottomBarConfigClass = Unobfuscator.loadBottomBarConfigClass(classLoader)
            val fieldsBottomBarConfig = Unobfuscator.getAllMapFields(bottomBarConfigClass)
            XposedBridge.hookAllConstructors(bottomBarConfigClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val supportsHdQuality = fieldsBottomBarConfig["supportsHdQuality"]
                    supportsHdQuality?.set(param.thisObject, true)
                }
            })
            legacyQualitySelection = 0
        }
        prefs.edit {
            putInt("legacy_quality_selection", legacyQualitySelection)
        }
    }

    override fun getPluginName(): String {
        return "Media Quality"
    }
}
