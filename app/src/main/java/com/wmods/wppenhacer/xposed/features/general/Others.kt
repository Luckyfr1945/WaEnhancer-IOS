package com.wmods.wppenhacer.xposed.features.general

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BaseBundle
import android.os.Message
import android.os.PowerManager
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.listeners.OnMultiClickListener
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.db.MessageStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.AnimationUtil
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.util.DexSignUtil
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import kotlin.math.max

class Others(loader: ClassLoader, preferences:SharedPreferences) : Feature(loader, preferences) {

    companion object {

        @JvmField
        val propsBoolean = HashMap<Int, Boolean>()
        @JvmField
        val propsInteger = HashMap<Int, Int>()
        @JvmField
        val statusCaptionOverrides = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private lateinit var properties: Properties

    override fun doHook() {
        // Load saved status caption overrides
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("status_capt_override_") && v is String) {
                val sId = k.removePrefix("status_capt_override_")
                statusCaptionOverrides[sId] = v
            }
        }

        properties = Utils.getProperties(prefs, "custom_css", "custom_filters")
        val menuWIcons = prefs.getBoolean("menuwicon", false)
        val newSettings = getNewSettingsVariant()
        logDebug("Others: newSettings value is $newSettings (configui_mode=${prefs.getString("configui_mode", null)})")
        val filterChats = prefs.getString("chatfilter", "2")
        val filterSeen = prefs.getBoolean("filterseen", false)
        var statusStyle = prefs.getString("status_style", "0")?.toInt() ?: 0
        val disableMetaAI = prefs.getBoolean("metaai", false)
        val disableSensorProximity = prefs.getBoolean("disable_sensor_proximity", false)
        val proximityAudios = prefs.getBoolean("proximity_audios", false)
        val showOnline = prefs.getBoolean("showonline", false)
        val floatingMenu = prefs.getBoolean("floatingmenu", false) || prefs.getBoolean("ios_header", false)
        val filterItems = prefs.getString("filter_items", null)
        val autonextStatus = prefs.getBoolean("autonext_status", false)
        val audioType = prefs.getString("audio_type", "0")?.toInt() ?: 0
        val audioTranscription = prefs.getBoolean("audio_transcription", false)
        val oldStatus = prefs.getBoolean("oldstatus", false)
        val igstatus = prefs.getBoolean("igstatus", false)
        val animationEmojis = prefs.getBoolean("animation_emojis", false)
        val disableProfileStatus = prefs.getBoolean("disable_profile_status", false)
        val disableExpiration = prefs.getBoolean("disable_expiration", false)
        val disableAd = prefs.getBoolean("disable_ads", false)

        propsInteger[3877] = if (oldStatus) (if (igstatus) 2 else 0) else 2

        propsBoolean[18250] = false
        propsBoolean[11528] = false

        propsBoolean[4497] = menuWIcons
        propsBoolean[4023] = false
        propsBoolean[16250] = false

        val fileSizeSpoofer = prefs.getBoolean("file_size_spoofer", true)
        if (fileSizeSpoofer) {
            propsInteger[1702] = 2048
            propsInteger[1703] = 2048
            propsInteger[500] = 2048
            propsInteger[2627] = 2048
        }

        if (newSettings == 2) {
            XposedBridge.hookAllMethods(WppCore.homeActivityClass, "onCreateOptionsMenu", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val menu = param.args[0] as Menu
                    val menuItem = menu.findItem(Utils.getID("me_tab_menu_item", "id"))
                    menuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            })
        }
        propsInteger[18564] = newSettings // ME_TAB_V2_VARIANTS_CODE

        // WA 2.26+ Native Settings Tab injection:
        // When configui_mode is 6 (You Tab), hook WDSBottomBar, TabList, TabName, TabIcon, and GetTabMethod
        // to inject WhatsApp's native SettingsFragment into ViewPager.
        if (newSettings == 6) {
            try {
                val wdsBottomBarClass = classLoader.loadClass(
                    "com.whatsapp.ui.wds.components.bottombar.WDSBottomBar"
                )
                XposedBridge.hookAllConstructors(wdsBottomBarClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "setSettingsTabVariantEnabled", true)
                            XposedHelpers.callMethod(param.thisObject, "setSettingsTabVariant", true)
                            logDebug("WDSBottomBar: Settings tab variant enabled")
                        } catch (e: Throwable) {
                            logDebug("WDSBottomBar: Failed to enable settings tab: ${e.message}")
                        }
                    }
                })
            } catch (e: Throwable) {
                logDebug("WDSBottomBar class not found: ${e.message}")
            }

            hookNativeSettingsTab()
        }

        propsBoolean[2889] = floatingMenu

        // new text composer
        propsBoolean[15708] = true

        // change page id
        propsBoolean[2358] = false

        // disable contact filter
        propsBoolean[7769] = false

        // disable new Media Picker
        propsBoolean[9286] = false

        // Instant Video
        propsBoolean[3354] = true
        propsBoolean[5418] = true
        propsBoolean[9051] = true

        // disable new toolbar
        propsBoolean[11824] = false
        propsBoolean[6481] = false

        // Enable music in Stories
        propsBoolean[13591] = true
        propsBoolean[10024] = true

        // show all status
        propsBoolean[6798] = true

        // auto play emojis settings
        propsBoolean[3575] = animationEmojis
        propsBoolean[9757] = animationEmojis

        // emojis maps
        propsBoolean[10639] = animationEmojis
        propsBoolean[12495] = animationEmojis
        propsBoolean[11066] = animationEmojis

        propsBoolean[7589] = true  // Media select quality
        propsBoolean[6972] = false // Media select quality
        propsBoolean[5625] = true  // Enable option to autodelete channels media

        propsBoolean[8643] = true  // Enable TextStatusComposerActivityV2
//        propsBoolean[3403] = true  // Enable Sticker Suggestion
        propsBoolean[8607] = true  // Enable Dialer keyboard
        propsBoolean[9578] = true  // Enable Privacy Checkup
        propsInteger[8135] = 2  // Call Filters

        // Enable Translate Message
        propsBoolean[9141] = true
        propsBoolean[8925] = true

        propsBoolean[10380] = false // fix crash bug in Settings/Archived

        propsBoolean[0x34b9] = true // Enable Select People in call
        propsBoolean[0x351c] = true // Enable new colors style in Text Composer

        // Enable show count until viewed
        propsBoolean[0x2289] = true
        propsBoolean[0x373f] = true

        // add yours in stories
        propsBoolean[0x2ce2] = true
        propsBoolean[0x2ce3] = true

        propsBoolean[0x345a] = true // new edit profile name

        // new stories selection
        propsBoolean[0x32ca] = true
        propsBoolean[0x32cb] = true

        if (disableMetaAI) {
            propsInteger[15535] = 0
            propsBoolean[8025] = false
            propsBoolean[6251] = false
            propsBoolean[8026] = false
            propsBoolean[14886] = false
        }

        if (audioTranscription) {
            propsBoolean[8632] = true
            propsBoolean[2890] = true
            propsBoolean[9215] = false
            propsBoolean[9216] = true
            propsBoolean[6808] = true
            propsBoolean[10286] = true
            propsBoolean[11596] = true
            propsBoolean[13949] = true
        }

        // Whatsapp Status Style
        val retStatusStyle = Unobfuscator.loadStatusStyleMethod(classLoader)
        XposedBridge.hookMethod(retStatusStyle, XC_MethodReplacement.returnConstant(statusStyle))
        statusStyle = if (oldStatus) 0 else statusStyle
        propsInteger[9973] = 1
        propsBoolean[6285] = true
        propsInteger[8522] = statusStyle
        propsInteger[8521] = statusStyle

        // Status in Group
        propsBoolean[13956] = true
        propsBoolean[13957] = true

        // Remove limit to edit status caption
        val removeLimitEditStatus = prefs.getBoolean("remove_limit_edit_status", true)
        if (removeLimitEditStatus) {
            runCatching {
                // Hook PopupWindow untuk meng-inject item Edit Caption di menu status
                hookStatusPlaybackPopupMenu()

                // Temukan companion / factory method asli StatusCaptionEditActivity
                runCatching { Unobfuscator.inspectStatusCaptionEditActivity(classLoader) }

                // Hook StatusCaptionEditActivity onCreate untuk inspect & inject status
                val editActClass = runCatching { classLoader.loadClass("com.whatsapp.status.playback.caption.StatusCaptionEditActivity") }.getOrNull()
                if (editActClass != null) {
                    for (m in editActClass.declaredMethods) {
                        logDebug("Others: StatusCaptionEditActivity method: ${m.name}(${m.parameterTypes.map { it.name }.joinToString()}) -> ${m.returnType.name}")
                    }

                    XposedHelpers.findAndHookMethod(editActClass, "onCreate", android.os.Bundle::class.java, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val intent = activity.intent
                            logDebug("Others: StatusCaptionEditActivity.onCreate extras: ${intent?.extras?.keySet()?.map { "$it=${intent.extras?.get(it)}" }}")

                            activity.window.decorView.post {
                                try {
                                    val fMessageWpp = StatusReplayTracker.currentActiveFMessageWpp
                                    val pkg = activity.packageName

                                    val captionEditId = activity.resources.getIdentifier("caption_edit_text", "id", pkg)
                                    val captionEditText = if (captionEditId != 0) activity.findViewById<android.widget.EditText>(captionEditId) else null

                                    val confirmBtnId = activity.resources.getIdentifier("confirm_button", "id", pkg)
                                    val confirmBtn = if (confirmBtnId != 0) activity.findViewById<android.widget.ImageView>(confirmBtnId) else null

                                    confirmBtn?.alpha = 1.0f
                                    confirmBtn?.isEnabled = true
                                    confirmBtn?.isClickable = true

                                    val mediaFile = fMessageWpp?.mediaFile
                                    logDebug("Others: Active status mediaFile = $mediaFile (exists=${mediaFile?.exists()})")

                                    if (mediaFile != null && mediaFile.exists()) {
                                        val bmp = android.graphics.BitmapFactory.decodeFile(mediaFile.absolutePath)
                                        if (bmp != null) {
                                            fun setMediaPreview(v: View): Boolean {
                                                val closeBtnId = activity.resources.getIdentifier("close_button", "id", pkg)
                                                if (v is android.widget.ImageView && v != confirmBtn && v.id != closeBtnId) {
                                                    v.setImageBitmap(bmp)
                                                    v.visibility = View.VISIBLE
                                                    logDebug("Others: Set media bitmap on ImageView: ${v.javaClass.simpleName}")
                                                    return true
                                                }
                                                if (v is ViewGroup) {
                                                    for (i in 0 until v.childCount) {
                                                        if (setMediaPreview(v.getChildAt(i))) return true
                                                    }
                                                }
                                                return false
                                            }
                                            setMediaPreview(activity.window.decorView)
                                        }
                                    }

                                    confirmBtn?.setOnClickListener {
                                        val newText = captionEditText?.text?.toString() ?: ""
                                        logDebug("Others: Confirm button clicked! Saving new status caption: '$newText'")
                                        if (fMessageWpp != null) {
                                            val rawFMessage = fMessageWpp.getObject()
                                            val rowId = intent?.getLongExtra("extra_message_row_id", -1L)?.takeIf { it > 0L }
                                                ?: extractRowId(rawFMessage).takeIf { it > 0L }
                                                ?: MessageStore.getInstance().getIdfromKey(fMessageWpp.key.messageID)
                                            
                                            logDebug("Others: rowId resolved to $rowId before SQL update")

                                            // 1. Update in-memory FMessage object
                                            try {
                                                var currentCls: Class<*>? = rawFMessage.javaClass
                                                while (currentCls != null && currentCls != Any::class.java) {
                                                    for (m in currentCls.declaredMethods) {
                                                        if (m.parameterCount == 1 && m.parameterTypes[0] == String::class.java && m.returnType == Void.TYPE) {
                                                            m.isAccessible = true
                                                            m.invoke(rawFMessage, newText)
                                                            logDebug("Others: Invoked in-memory text setter ${m.name}(String)")
                                                        }
                                                    }
                                                    for (f in currentCls.declaredFields) {
                                                        if (f.type == String::class.java) {
                                                            f.isAccessible = true
                                                            val curVal = f.get(rawFMessage) as? String
                                                            if (curVal != null && curVal.isNotEmpty() && !curVal.contains("@")) {
                                                                f.set(rawFMessage, newText)
                                                                logDebug("Others: Updated String field ${f.name} in FMessage to '$newText'")
                                                            }
                                                        }
                                                    }
                                                    currentCls = currentCls.superclass
                                                }
                                            } catch (e: Throwable) {
                                                logDebug("Others: Failed to update in-memory FMessage: ${e.message}")
                                            }

                                            // 2. Update statusCaptionOverrides in-memory map & SharedPreferences
                                            val statusId = fMessageWpp.key.messageID
                                            if (!statusId.isNullOrEmpty()) {
                                                statusCaptionOverrides[statusId] = newText
                                                prefs.edit().putString("status_capt_override_$statusId", newText).apply()
                                                logDebug("Others: Stored status caption override for statusId=$statusId: '$newText'")
                                            }

                                            // 3. Update SQLite msgstore.db
                                            if (rowId > 0L) {
                                                val esc = newText.replace("'", "''")
                                                MessageStore.getInstance().executeSQL("UPDATE message SET text_data = '$esc' WHERE _id = $rowId")
                                                MessageStore.getInstance().executeSQL("UPDATE message_media SET caption = '$esc' WHERE message_row_id = $rowId")
                                                MessageStore.getInstance().executeSQL("UPDATE message_ftsv2_content SET c0content = '$esc' WHERE docid = $rowId")
                                                logDebug("Others: Updated SQLite message text_data, message_media caption, and fts for rowId=$rowId")
                                            } else {
                                                logDebug("Others: Warning - rowId is $rowId <= 0, skipping direct SQLite update")
                                            }

                                            // 4. Trigger native A03
                                            runCatching {
                                                val a03 = editActClass.declaredMethods.firstOrNull { it.name == "A03" }
                                                logDebug("Others: A03 found=${a03 != null}")
                                                if (a03 != null) {
                                                    a03.isAccessible = true
                                                    if (Modifier.isStatic(a03.modifiers)) {
                                                        a03.invoke(null, activity)
                                                    } else {
                                                        a03.invoke(activity)
                                                    }
                                                    logDebug("Others: A03 successfully invoked with activity")
                                                }
                                            }.onFailure {
                                                logDebug("Others: A03 invoke failed: ${it.message}")
                                            }

                                            Utils.showToast("Caption status berhasil diperbarui", Toast.LENGTH_SHORT)
                                        }
                                        activity.finish()
                                    }
                                } catch (e: Throwable) {
                                    logDebug("Others: Post decorView setup error: ${e.message}")
                                }
                            }
                        }
                    })
                }
            }.onFailure {
                logDebug("Others: Status caption edit hook error: ${it.message}")
            }
        }

        // new popup menu in chat
        propsBoolean[21541] = floatingMenu

        // hookProps()
        hookSearchbar(filterChats)

        if (disableSensorProximity) {
            disableSensorProximity()
        }

        if (proximityAudios) {
            val classes = Unobfuscator.loadProximitySensorListenerClasses(classLoader)
            for (cls in classes) {
                XposedBridge.hookAllMethods(cls, "onSensorChanged", ReflectionUtils.DO_NOTHING)
            }
        }

        if (filterItems != null && prefs.getBoolean("custom_filters", true)) {
            filterItems(filterItems)
        }

        if (autonextStatus) {
            autoNextStatus()
        }

        val sendAudioAsVoiceStatus = prefs.getBoolean("send_audio_as_voice_status", true)
        val effectiveAudioType = if (audioType == 0 && sendAudioAsVoiceStatus) 2 else audioType

        if (effectiveAudioType > 0) {
            try {
                sendAudioType(effectiveAudioType)
            } catch (e: Exception) {
                logDebug(e)
            }
        }

        try {
            customPlayBackSpeed()
        } catch (e: Exception) {
            logDebug("customPlayBackSpeed error: ${e.message}")
        }

        showOnline(showOnline)

        animationList()

        stampCopiedMessage()

        doubleTapReaction()

        alwaysOnline()

        callInfo()

        if (disableProfileStatus) {
            disablePhotoProfileStatus()
        }

        if (disableExpiration) {
            FeatureLoader.disableExpirationVersion(classLoader)
        }

        if (disableAd) {
            disableAds()
        }

        if (!filterSeen) {
            disableHomeFilters()
        }

        if (floatingMenu) {
            hookBlurContextMenu()
        }
    }


    @SuppressLint("NewApi")
    private fun hookBlurContextMenu() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", null, "addView",
                View::class.java, android.view.ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val layoutParams = param.args[1] as? android.view.WindowManager.LayoutParams ?: return
                            
                            // Jika window ini meminta layar diredupkan (dimming),
                            // kita ubah menjadi efek blur gaya iOS
                            if (layoutParams.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                                layoutParams.flags = layoutParams.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                                layoutParams.blurBehindRadius = 45 // Radius blur
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            logDebug("Gagal hook WindowManagerImpl: ${e.message}")
        }
    }

    private fun getNewSettingsVariant(): Int {
        val type = prefs.getString("configui_mode", "-1")?.toInt() ?: -1
        return if (type != -1){
            type
        }else {
            if (prefs.getBoolean("novaconfig", false)) 2 else 0
        }
    }

    private fun disableHomeFilters() {
        propsBoolean[15345] = true
        propsBoolean[13546] = false
        propsBoolean[13408] = true

        val filterView = try {
            Unobfuscator.loadChatFilterView(classLoader)
        } catch (_: Throwable) {
            null
        }

        if (filterView != null) {
            XposedBridge.hookAllConstructors(filterView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    view.visibility = View.GONE
                    view.layoutParams?.let {
                        it.height = 0
                        view.layoutParams = it
                    }
                }
            })
        }
    }

    private fun disableAds() {
        propsBoolean[22904] = true
        propsBoolean[14306] = false
        try {
            val loadAd = Unobfuscator.loadAdVerifyMethod(classLoader)
            XposedBridge.hookMethod(loadAd, object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val enumParam = param.args[0] as Enum<*>
                    if (enumParam.name == "WAMO") {
                        val retClass = (param.method as Method).returnType as Class<out Enum<*>>
                        val pauseEnum = java.lang.Enum.valueOf(retClass, "PAUSED")
                        param.result = pauseEnum
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug(e)
        }
    }

    private fun disablePhotoProfileStatus() {
        val statusDataClass = Unobfuscator.loadStatusDataClass(classLoader)
        val statusProfileMethod = Unobfuscator.loadStatusProfileMethod(classLoader)
        val photoProfileClass = Unobfuscator.findFirstClassUsingName(
            classLoader,
            StringMatchType.EndsWith,
            ".WDSProfilePhoto"
        )
        val isCalledFromProfileStatus = ThreadLocal<Boolean>()

        XposedBridge.hookMethod(statusProfileMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                isCalledFromProfileStatus.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                isCalledFromProfileStatus.set(false)
            }
        })

        val methods = ReflectionUtils.findAllMethodsUsingFilter(statusDataClass){
            it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
        }

        methods.forEach {
            XposedBridge.hookMethod(it, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isCalledFromProfileStatus.get() ?: false)
                        param.result = false
                }
            })
        }

        XposedBridge.hookAllMethods(photoProfileClass, "setStatusIndicatorEnabled", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] as Boolean) {
                    param.result = null
                }
            }
        })
    }

    private fun disableSensorProximity() {
        XposedBridge.hookAllMethods(PowerManager::class.java, "newWakeLock", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) {
                    param.result = null
                }
            }
        })
    }

    private fun callInfo() {
        if (!prefs.getBoolean("call_info", false)) return

        val clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "VoiceServiceEventCallback")
        val clsWamCall = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "WamCall")

        XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (clsWamCall.isInstance(param.args[0])) {

                    val callinfo = XposedHelpers.callMethod(param.thisObject, "getCallInfo") ?: return
                    val userJid = FMessageWpp.UserJid(XposedHelpers.callMethod(callinfo, "getPeerJid"))
                    if (userJid.isNull) return
                    CompletableFuture.runAsync {
                        try {
                            showCallInformation(param.args[0], userJid)
                        } catch (e: Exception) {
                            logDebug(e)
                        }
                    }
                }
            }
        })
    }

    private fun showCallInformation(wamCall: Any, userJid: FMessageWpp.UserJid) {
        if (userJid.isGroup) return
        val sb = StringBuilder()
        val contact = WppCore.getContactName(userJid)
        val number = userJid.phoneNumber
        if (!TextUtils.isEmpty(contact))
            sb.append(String.format(Utils.application.getString(R.string.contact_s), contact)).append("\n")
        sb.append(String.format(Utils.application.getString(R.string.phone_number_s), number)).append("\n")
        
        val ip = XposedHelpers.getObjectField(wamCall, "callPeerIpStr") as String?
        if (ip != null) {
            val client = OkHttpClient.Builder().build()
            val url = "http://ip-api.com/json/$ip"
            val request = Request.Builder().url(url).build()
            val content = client.newCall(request).execute().body.string()
            val json = JSONObject(content)
            val country = json.getString("country")
            val city = json.getString("city")
            sb.append(String.format(Utils.application.getString(R.string.country_s), country)).append("\n")
              .append(String.format(Utils.application.getString(R.string.city_s), city)).append("\n")
              .append(String.format(Utils.application.getString(R.string.ip_s), ip)).append("\n")
        }
        val platform = XposedHelpers.getObjectField(wamCall, "callPeerPlatform") as String?
        if (platform != null)
            sb.append(String.format(Utils.application.getString(R.string.platform_s), platform)).append("\n")
        val wppVersion = XposedHelpers.getObjectField(wamCall, "callPeerAppVersion") as String?
        if (wppVersion != null)
            sb.append(String.format(Utils.application.getString(R.string.wpp_version_s), wppVersion)).append("\n")
        
        Utils.showNotification(Utils.application.getString(R.string.call_information), sb.toString())
    }

    private fun alwaysOnline() {
        if (!prefs.getBoolean("always_online", false)) return
        val stateChange = Unobfuscator.loadStateChangeMethod(classLoader)
        XposedBridge.hookMethod(stateChange, ReflectionUtils.DO_NOTHING)
    }

    private fun doubleTapReaction() {
        if (!prefs.getBoolean("doubletap2like", false)) return

        val emoji = prefs.getString("doubletap2like_emoji", "👍") ?: "👍"

        val conversationRowClass = Unobfuscator.loadConversationRowClass(classLoader)

        XposedBridge.hookAllConstructors(conversationRowClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val viewGroup = param.thisObject as ViewGroup
                viewGroup.setOnTouchListener(null)
            }
        })

        ConversationItemListener.conversationListeners.add(object :
            ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(fMessage: FMessageWpp, view: ViewGroup, position: Int, convertView: View?) {
                val messageId = fMessage.key.messageID
                val onMultiClickListener = object : OnMultiClickListener(2, 500) {
                    override fun onMultiClick(v: View) {
                        if (!ConversationItemListener.isViewBoundToMessage(view, messageId)) return
                        val reactionView = v.findViewById<ViewGroup>(Utils.getID("reactions_bubble_layout", "id"))
                        if (reactionView != null && reactionView.isVisible) {
                            for (i in 0 until reactionView.childCount) {
                                val child = reactionView.getChildAt(i)
                                if (child is TextView) {
                                    if (child.text.toString().contains(emoji)) {
                                        WppCore.sendReaction("", fMessage.getObject())
                                        return
                                    }
                                }
                            }
                        }
                        WppCore.sendReaction(emoji, fMessage.getObject())
                    }
                }
                view.setOnClickListener(onMultiClickListener)
            }
        })
    }

    private fun stampCopiedMessage() {
        if (!prefs.getBoolean("stamp_copied_message", false)) return

        val copiedMessage = Unobfuscator.loadCopiedMessageMethod(classLoader)

        XposedBridge.hookMethod(copiedMessage, object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                val collection = param.args.last() as java.util.Collection<*>
                param.args[param.args.lastIndex] = object : ArrayList<Any>(collection as Collection<Any>) {
                    override val size: Int
                        get() = 1
                }
            }
        })
    }

    private fun animationList() {
        val animation = prefs.getString("animation_list", "default") ?: "default"

        val onChangeStatus = Unobfuscator.loadOnChangeStatus(classLoader)
        logDebug(Unobfuscator.getMethodDescriptor(onChangeStatus))
        val field1 = Unobfuscator.loadViewHolderField1(classLoader)
        logDebug(Unobfuscator.getFieldDescriptor(field1))
        val absViewHolderClass = Unobfuscator.loadAbsViewHolder(classLoader)

        XposedBridge.hookMethod(onChangeStatus, object : XC_MethodHook() {
            @SuppressLint("ResourceType")
            override fun afterHookedMethod(param: MethodHookParam) {
                val viewHolder = field1.get(param.thisObject)
                val viewField = ReflectionUtils.findFieldUsingFilter(absViewHolderClass) { field -> field.type == View::class.java }
                val view = viewField.get(viewHolder) as View
                
                if (animation != "default") {
                    view.startAnimation(AnimationUtil.getAnimation(animation))
                } else if (properties.containsKey("home_list_animation")) {
                    val anim = AnimationUtil.getAnimation(properties.getProperty("home_list_animation"))
                    if (anim != null) {
                        view.startAnimation(anim)
                    }
                }
            }
        })
    }

    private fun customPlayBackSpeed() {
        val voicenoteSpeed = prefs.getFloat("voicenote_speed", 2.0f)
        val playBackSpeed = Unobfuscator.loadPlaybackSpeed(classLoader)
        
        XposedBridge.hookMethod(playBackSpeed, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                super.beforeHookedMethod(param)
                if (param.args[1] as Float == 2.0f) {
                    param.args[1] = voicenoteSpeed
                }
            }
        })
        
        val voicenoteClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "VoiceNoteProfileAvatarView")
        val method = ReflectionUtils.findAllMethodsUsingFilter(voicenoteClass) { method1 -> 
            method1.parameterCount == 4 && method1.parameterTypes[0] == Int::class.javaPrimitiveType && method1.returnType == Void.TYPE
        }
        
        XposedBridge.hookMethod(method[method.size - 1], object : XC_MethodHook() {
            @SuppressLint("SetTextI18n")
            override fun afterHookedMethod(param: MethodHookParam) {
                super.afterHookedMethod(param)
                if (param.args[0] as Int == 3) {
                    val view = param.thisObject as View
                    val playback = view.findViewById<TextView>(Utils.getID("fast_playback_overlay", "id"))
                    if (playback != null) {
                        playback.text = voicenoteSpeed.toString().replace(".", ",") + "×"
                    }
                }
            }
        })
    }

    private fun sendAudioType(selectedAudioType: Int) {
        val sendAudioTypeMethod = Unobfuscator.loadSendAudioTypeMethod(classLoader)
        
        XposedBridge.hookMethod(sendAudioTypeMethod, object : XC_MethodHook() {
            private var newFile: File? = null

            override fun beforeHookedMethod(param: MethodHookParam) {
                newFile = null
                val results = ReflectionUtils.findInstancesOfType(param.args, Integer::class.java)
                if (results.size < 2) {
                    return
                }

                val mediaType = results[0]
                val sourceType = results[1]

                if (mediaType.second as Int == 2 || mediaType.second as Int == 9) {
                    if (selectedAudioType > 0) {
                        val audioTypeValue = sourceType.second as Int
                        val targetAudioType = selectedAudioType - 1
                        param.args[sourceType.first as Int] = targetAudioType

                        if (audioTypeValue != targetAudioType && targetAudioType == 1) {
                            Utils.showToast(Utils.getString(R.string.converting_audio), Toast.LENGTH_LONG)
                            val fileMedia = param.args[2]
                            val fieldFile = ReflectionUtils.getFieldByExtendType(fileMedia.javaClass, File::class.java)
                            val file = fieldFile!!.get(fileMedia) as File
                            newFile = AudioOpusConverter.convert(file.absolutePath)
                            if (newFile != null) {
                                file.delete()
                                fieldFile!!.set(fileMedia, newFile)
                            }
                        }
                    }
                }
            }
        })

        val originFMessageField = Unobfuscator.loadOriginFMessageField(classLoader)
        val forwardAudioTypeMethod = Unobfuscator.loadForwardAudioTypeMethod(classLoader)

        XposedBridge.hookMethod(forwardAudioTypeMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fMessage = param.result
                originFMessageField.isAccessible = true
                originFMessageField.setInt(fMessage, selectedAudioType - 1)
            }
        })
    }

    private fun autoNextStatus() {
        val statusPlaybackContactFragmentClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "StatusPlaybackContactFragment")
        val runNextStatusMethod = Unobfuscator.loadNextStatusRunMethod(classLoader)
        
        XposedBridge.hookMethod(runNextStatusMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val obj = XposedHelpers.getObjectField(param.thisObject, "A01")
                if (statusPlaybackContactFragmentClass.isInstance(obj)) {
                    param.result = null
                }
            }
        })
        
        val onPlayBackFinished = Unobfuscator.loadOnPlaybackFinished(classLoader)
        XposedBridge.hookMethod(onPlayBackFinished, ReflectionUtils.DO_NOTHING)
    }




    private fun filterItems(filterItems: String) {
        val idsFilter = filterItems.split("\n").map {
            Utils.getID(it.trim(), "id")
        }.filter { it > 0 }.toHashSet()

        if (idsFilter.isEmpty()) return

        XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val id = view.id
                if (id > 0 && idsFilter.contains(id)) {
                    view.visibility = View.GONE
                }
            }
        })
    }

    private fun showOnline(showOnline: Boolean) {
        val checkOnlineMethod = Unobfuscator.loadCheckOnlineMethod(classLoader)
        XposedBridge.hookMethod(checkOnlineMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val message = param.args[0] as Message
                if (message.arg1 != 5) return
                val baseBundle = message.obj as BaseBundle
                val jid = baseBundle.getString("jid")
                if (TextUtils.isEmpty(jid)) return
                val userjid = FMessageWpp.UserJid(jid)
                if (userjid.isGroup) return
                val waContact = WaContactWpp.getWaContactFromJid(userjid)
                val name = waContact?.displayName ?: "Unknown"
                if (showOnline)
                    Utils.showToast(String.format(Utils.application.getString(R.string.toast_online), name), Toast.LENGTH_SHORT)
                Tasker.sendTaskerEvent(name, WppCore.stripJID(jid), "contact_online")
            }
        })
    }

    private fun hookProps() {
        val methodPropsBoolean = Unobfuscator.loadPropsBooleanMethod(classLoader)
        logDebug(Unobfuscator.getMethodDescriptor(methodPropsBoolean))
        val dataUsageActivityClass = WppCore.dataUsageActivityClass

        XposedBridge.hookMethod(methodPropsBoolean, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val method = param.method as? Method
                val returnType = method?.returnType
                val list = ReflectionUtils.findInstancesOfType(param.args, Integer::class.java)
                val i = list.firstOrNull()?.second?.toInt() ?: return

                val propValue = propsBoolean[i]
                if (propValue != null) {
                    // Fix Bug in Settings Data Usage
                    if (i == 4023) {
                        if (ReflectionUtils.isCalledFromClass(dataUsageActivityClass)) return
                    }
                    if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java) {
                        param.result = propValue
                    }
                }
            }
        })

        val methodPropsInteger = Unobfuscator.loadPropsIntegerMethod(classLoader)

        XposedBridge.hookMethod(methodPropsInteger, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val method = param.method as? Method
                val returnType = method?.returnType
                val list = ReflectionUtils.findInstancesOfType(param.args, Integer::class.java)
                val i = list.firstOrNull()?.second?.toInt() ?: return
                val propValue = propsInteger[i] ?: return
                if (returnType == java.lang.Integer.TYPE || returnType == java.lang.Integer::class.java) {
                    param.result = propValue
                } else if (returnType == java.lang.Long.TYPE || returnType == java.lang.Long::class.java) {
                    param.result = propValue.toLong()
                }
            }
        })
    }

    private fun hookSearchbar(filterChats: String?) {
        if (filterChats.isNullOrEmpty())return
        val searchbar = Unobfuscator.loadViewAddSearchBarMethod(classLoader)
        val searchBarID = Utils.getID("my_search_bar", "id")

        XposedBridge.hookMethod(searchbar, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                var view: View? = null
                if (param.args[0] is View) {
                    view = param.args[0] as View
                } else {
                    val auxFace = (param.method as Method).parameterTypes[0]
                    val method = ReflectionUtils.findMethodUsingFilter(auxFace) { m -> m.returnType == View::class.java }
                    if (method != null) {
                        val currentActivity = WppCore.getCurrentActivity()
                        view = method.invoke(param.args[0], currentActivity) as View?
                    }
                }

                if (view != null && (view.id == searchBarID || view.findViewById<View>(searchBarID) != null) && filterChats != "2") {
                    param.result = null
                }
            }
        })

        try {
            if (filterChats != "2") {
                val loadMySearchBar = Unobfuscator.loadMySearchBarMethod(classLoader)
                if (loadMySearchBar != null) {
                    XposedBridge.hookMethod(loadMySearchBar, ReflectionUtils.DO_NOTHING)
                }
            }
        } catch (_: Exception) {
        }

        val addSeachBar = Unobfuscator.loadAddOptionSearchBarMethod(classLoader)
        val curPageField = Unobfuscator.loadGetCurrentPageInHomeField(classLoader)

        XposedBridge.hookMethod(addSeachBar, object : XC_MethodHook() {
            private var homeActivity: Any? = null
            private var originPageId: Int = 0

            override fun beforeHookedMethod(param: MethodHookParam) {
                if (filterChats != "1") return
                homeActivity = param.thisObject
                if (Modifier.isStatic(param.method.modifiers)) {
                    homeActivity = param.args[0]
                }
                originPageId = 0
                if (curPageField.type == Int::class.javaPrimitiveType) {
                    originPageId = curPageField.getInt(homeActivity)
                    curPageField.setInt(homeActivity, 1)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (originPageId != 0) {
                    curPageField.setInt(homeActivity, originPageId)
                }
            }
        })
        
        XposedHelpers.findAndHookMethod(WppCore.homeActivityClass, "onPrepareOptionsMenu", Menu::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val menu = param.args[0] as Menu
                val item = menu.findItem(Utils.getID("menuitem_search", "id"))
                item?.isVisible = filterChats == "1"
            }
        })
    }

    private fun hookStatusPlaybackPopupMenu() {
        val popupWindowShowHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean("remove_limit_edit_status", true)) return
                val currentActivity = WppCore.getCurrentActivity() ?: return
                val actName = currentActivity.javaClass.name
                val isMyStatusesActivity = actName.contains("MyStatusesActivity")
                if (!actName.contains("StatusPlaybackActivity") && !isMyStatusesActivity) return

                // Cek cepat in-memory: Hanya status milik sendiri yang diproses
                val activeFMessage = StatusReplayTracker.currentActiveFMessageWpp
                val isFromMe = isMyStatusesActivity || (activeFMessage?.key?.isFromMe == true)

                // Jika status orang lain, langsung return instant tanpa lag
                if (!isFromMe) return

                val statusId = StatusReplayTracker.currentActiveStatusKey
                if (statusId.isEmpty()) return

                val popup = param.thisObject as? android.widget.PopupWindow ?: return
                val contentView = popup.contentView as? ViewGroup ?: return

                injectEditCaptionMenuItem(currentActivity, popup, contentView, statusId)
            }
        }

        XposedBridge.hookAllMethods(android.widget.PopupWindow::class.java, "showAsDropDown", popupWindowShowHook)
        XposedBridge.hookAllMethods(android.widget.PopupWindow::class.java, "showAtLocation", popupWindowShowHook)
    }

    private fun injectEditCaptionMenuItem(
        activity: Activity,
        popup: android.widget.PopupWindow,
        container: ViewGroup,
        statusId: String
    ) {
        try {
            val tagKey = R.id.status_replay_tag
            if (container.getTag(tagKey) != null) return
            container.setTag(tagKey, true)

            val targetGroup = findMenuContainerViewGroup(container) ?: container
            val sampleTextView = findFirstTextView(targetGroup)

            val editItem = TextView(activity).apply {
                text = "✏️  Edit Caption"
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (sampleTextView != null) {
                    setTextColor(sampleTextView.textColors)
                    textSize = sampleTextView.textSize / activity.resources.displayMetrics.scaledDensity
                    typeface = sampleTextView.typeface
                    setPadding(
                        sampleTextView.paddingLeft,
                        sampleTextView.paddingTop,
                        sampleTextView.paddingRight,
                        sampleTextView.paddingBottom
                    )
                } else {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    val padH = (16 * activity.resources.displayMetrics.density).toInt()
                    val padV = (12 * activity.resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                }
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)

                setOnClickListener {
                    popup.dismiss()
                    launchStatusCaptionEdit(activity, statusId)
                }
            }

            if (targetGroup is android.widget.ListView) {
                targetGroup.addHeaderView(editItem)
            } else if (targetGroup !is android.widget.AdapterView<*>) {
                targetGroup.addView(editItem, 0)
            } else {
                val parent = targetGroup.parent as? ViewGroup
                if (parent != null && parent !is android.widget.AdapterView<*>) {
                    parent.addView(editItem, 0)
                }
            }
            logDebug("Others: Successfully injected '✏️ Edit Caption' item into status popup menu")
        } catch (e: Throwable) {
            logDebug("Others: injectEditCaptionMenuItem error: ${e.message}")
        }
    }

    private fun extractRowId(rawFMessage: Any?): Long {
        if (rawFMessage == null) return -1L
        try {
            var currentCls: Class<*>? = rawFMessage.javaClass
            while (currentCls != null && currentCls != Any::class.java) {
                for (field in currentCls.declaredFields) {
                    if (field.type == Long::class.javaPrimitiveType || field.type == java.lang.Long::class.java) {
                        field.isAccessible = true
                        val value = field.getLong(rawFMessage)
                        logDebug("Others: FMessage long field ${field.name} = $value")
                        // rowId di SQLite adalah integer positif (1..10_000_000)
                        // timestamp adalah epoch millis (> 1_000_000_000_000)
                        if (value in 1..999_999_999_999L) {
                            return value
                        }
                    }
                }
                currentCls = currentCls.superclass
            }
        } catch (e: Throwable) {
            logDebug("Others: extractRowId error: ${e.message}")
        }
        return -1L
    }

    private fun launchStatusCaptionEdit(activity: Activity, statusId: String) {
        try {
            val fMessageWpp = StatusReplayTracker.currentActiveFMessageWpp
            val rawFMessage = fMessageWpp?.getObject()
            var rowId = extractRowId(rawFMessage)
            var caption = fMessageWpp?.messageStr ?: ""

            if (rowId <= 0L) {
                rowId = MessageStore.getInstance().getIdfromKey(statusId)
            }
            if (caption.isEmpty()) {
                caption = MessageStore.getInstance().getCurrentMessageByKey(statusId)
            }

            logDebug("Others: launchStatusCaptionEdit: statusId=$statusId, rowId=$rowId, caption=$caption")

            val intent = Intent().apply {
                setClassName(activity.packageName, "com.whatsapp.status.playback.caption.StatusCaptionEditActivity")
                putExtra("extra_message_key_id", statusId)
                putExtra("extra_msg_key_id", statusId)
                if (rowId > 0L) {
                    putExtra("extra_message_row_id", rowId)
                    putExtra("message_row_id", rowId)
                }
                putExtra("extra_current_caption", caption)
                putExtra("extra_jid", "status@broadcast")
                putExtra("extra_msg_key_jid", "status@broadcast")
                putExtra("extra_is_from_me", true)
                putExtra("extra_msg_key_from_me", true)
            }

            activity.startActivity(intent)
            logDebug("Others: Successfully launched StatusCaptionEditActivity for statusId=$statusId")
        } catch (e: Throwable) {
            logDebug("Others: Failed to launch StatusCaptionEditActivity: ${e.message}")
            Utils.showToast("Gagal membuka editor status: ${e.message}", Toast.LENGTH_SHORT)
        }
    }

    private fun findMenuContainerViewGroup(view: View): ViewGroup? {
        if (view is android.widget.ListView || (view is ViewGroup && view.childCount > 1)) {
            return view as ViewGroup
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val found = findMenuContainerViewGroup(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFirstTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstTextView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun hookNativeSettingsTab() {
        val SETTINGS_TAB = 700
        var activeTabs = ArrayList<Int>()

        try {
            val bottomNavigationViewCls = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                ".BottomNavigationView"
            )
            XposedHelpers.findAndHookMethod(
                bottomNavigationViewCls,
                "getMaxItemCount",
                XC_MethodReplacement.returnConstant(99)
            )
        } catch (e: Throwable) {
            logDebug("Others: Error hooking getMaxItemCount for Settings Tab: ${e.message}")
        }

        // 1. Hook Tab List (ViewPager Adapter tab IDs)
        try {
            val onCreateTabList = Unobfuscator.loadTabListMethod(classLoader)
            XposedBridge.hookMethod(onCreateTabList, object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resultTabs = param.result as? ArrayList<Int> ?: return
                    activeTabs = resultTabs
                    if (!resultTabs.contains(SETTINGS_TAB)) {
                        resultTabs.add(SETTINGS_TAB)
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("Others: Error hooking TabList for Settings Tab: ${e.message}")
        }

        // 2. Hook Tab Name
        try {
            val tabNameMethod = Unobfuscator.loadTabNameMethod(classLoader)
            XposedBridge.hookMethod(tabNameMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tab = param.args[0] as? Int ?: return
                    if (tab == SETTINGS_TAB) {
                        param.result = "Pengaturan"
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("Others: Error hooking TabName for Settings Tab: ${e.message}")
        }

        // 3. Hook Tab Icon
        try {
            val iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader)
            val menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader)
            XposedBridge.hookMethod(iconTabMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val hooked = XposedBridge.hookMethod(menuAddAndroidX, object : XC_MethodHook() {
                        override fun afterHookedMethod(innerParam: MethodHookParam) {
                            if (innerParam.args.size > 2 && (innerParam.args[1] as? Int) == SETTINGS_TAB) {
                                val menuItem = innerParam.result as? MenuItem ?: return
                                var iconId = Utils.getID("ic_settings", "drawable")
                                if (iconId == 0) {
                                    iconId = Utils.getID("ic_settings_filled", "drawable")
                                }
                                if (iconId != 0) {
                                    menuItem.setIcon(iconId)
                                }
                            }
                        }
                    })
                    param.setObjectExtra("hooked_settings", hooked)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val hooked = param.getObjectExtra("hooked_settings") as? XC_MethodHook.Unhook
                    hooked?.unhook()
                }
            })
        } catch (e: Throwable) {
            logDebug("Others: Error hooking TabIcon for Settings Tab: ${e.message}")
        }

        // 4. Hook Tab Instance (GetTabMethod returns SettingsFragment)
        try {
            val settingsFragClass = XposedHelpers.findClass(
                "com.whatsapp.settings.ui.SettingsFragment",
                classLoader
            )
            val getTabMethod = Unobfuscator.loadGetTabMethod(classLoader)
            XposedBridge.hookMethod(getTabMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val index = param.args[0] as? Int ?: return
                    val tabId = if (index in activeTabs.indices) activeTabs[index] else index
                    if (tabId == SETTINGS_TAB) {
                        val fragment = settingsFragClass.declaredConstructors.first {
                            it.parameterCount == 0
                        }.newInstance()
                        param.result = fragment
                        logDebug("Others: Successfully instantiated SettingsFragment for tab $tabId")
                    }
                }
            })
        } catch (e: Throwable) {
            logDebug("Others: Error hooking TabInstance for Settings Tab: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Others"
    }
}
