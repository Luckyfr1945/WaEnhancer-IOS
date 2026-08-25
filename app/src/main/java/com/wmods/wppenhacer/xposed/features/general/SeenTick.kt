package com.wmods.wppenhacer.xposed.features.general

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Pair
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.MenuStatusListener
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.WaeCoroutineExceptionHandler
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeenTick(
    loader: ClassLoader,
    preferences: SharedPreferences
) : Feature(loader, preferences) {

    private val messageMap = ConcurrentHashMap<String, WeakReference<ImageView>>()
    private val sendBlueTickMutex = Mutex()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + WaeCoroutineExceptionHandler)

    companion object {
        private var mWaJobManager: Any? = null
        private var mSendReadClass: Class<*>? = null
        private var waJobManagerMethod: Method? = null

        private var cachedSeenDrawable: Drawable? = null
        private var cachedUnseenDrawable: Drawable? = null

        private var sendJobConstructor: Constructor<*>? = null
        private var sendJobParamTypes: Array<Class<*>>? = null
        private var sendJobJidIndexes: List<Pair<Int, Class<*>>>? = null
        private var sendJobMessageIdIndex: Int = -1

        private var sendPlayedClass: Class<*>? = null
        private var sendPlayedConstructor: Constructor<*>? = null
        private var participantInfoConstructor: Constructor<*>? = null

        fun setSeenButton(buttonImage: ImageView, isSeen: Boolean) {
            val originalDrawable = DesignUtils.getDrawableByName("ic_notif_mark_read")
            if (originalDrawable == null) {
                buttonImage.setImageResource(Utils.getID("ic_notif_mark_read", "drawable"))
                if (isSeen) buttonImage.setColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP)
                else buttonImage.clearColorFilter()
                return
            }

            val clonedDrawable: Drawable = if (originalDrawable is BitmapDrawable) {
                val bitmap = originalDrawable.bitmap
                val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                val clonedBitmap = try {
                    bitmap.copy(config, true)
                } catch (_: Exception) {
                    val fallbackBitmap =
                        createBitmap(bitmap.width, bitmap.height)
                    try {
                        val canvas = Canvas(fallbackBitmap)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                    } catch (_: Exception) {
                    }
                    fallbackBitmap
                }
                clonedBitmap.toDrawable(buttonImage.resources)
            } else {
                originalDrawable.constantState?.newDrawable()?.mutate() ?: originalDrawable.mutate()
            }

            if (isSeen) {
                @Suppress("DEPRECATION")
                clonedDrawable.setColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP)
            } else {
                clonedDrawable.clearColorFilter()
            }

            buttonImage.setImageDrawable(clonedDrawable)
            buttonImage.postInvalidate()
        }
    }

    private fun registerMessageView(messageId: String?, view: ImageView?) {
        if (messageId == null || view == null) return
        if (messageMap.size > 100) {
            messageMap.entries.removeIf { it.value.get() == null }
        }
        messageMap[messageId] = WeakReference(view)
    }

    private fun getRegisteredView(messageId: String?): ImageView? {
        return messageMap[messageId]?.get()
    }

    override fun doHook() {
        waJobManagerMethod = Unobfuscator.loadBlueOnReplayWaJobManagerMethod(classLoader)
        mSendReadClass = Unobfuscator.findFirstClassUsingName(
            classLoader,
            StringMatchType.EndsWith,
            "SendReadReceiptJob"
        )

        try {
            mSendReadClass?.let { cls ->
                val candidates = cls.declaredConstructors.filter { c ->
                    c.parameterTypes.any { it == Array<String>::class.java }
                }
                sendJobConstructor = candidates.maxByOrNull { it.parameterCount }
                    ?: cls.declaredConstructors.maxByOrNull { it.parameterCount }
                    ?: cls.declaredConstructors.firstOrNull()

                sendJobConstructor?.let { constr ->
                    constr.isAccessible = true
                    val paramTypes = constr.parameterTypes
                    sendJobParamTypes = paramTypes

                    val jidClass = FMessageWpp.UserJid.TYPE_JID
                    val jidIndices = mutableListOf<Pair<Int, Class<*>>>()
                    var msgIdIdx = -1

                    for (i in paramTypes.indices) {
                        val p = paramTypes[i]
                        if (p == Array<String>::class.java) {
                            msgIdIdx = i
                        } else if ((jidClass != null && jidClass.isAssignableFrom(p)) || p.name.contains(".jid.") || p.name.endsWith("Jid")) {
                            jidIndices.add(Pair(i, p))
                        }
                    }

                    sendJobJidIndexes = jidIndices
                    sendJobMessageIdIndex = msgIdIdx
                }
            }

            sendPlayedClass = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.Contains,
                "SendPlayedReceiptJob"
            )
            sendPlayedClass?.let { cls ->
                sendPlayedConstructor = cls.declaredConstructors.firstOrNull()
                val classParticipantInfo = sendPlayedConstructor?.parameterTypes?.firstOrNull()
                participantInfoConstructor =
                    classParticipantInfo?.declaredConstructors?.firstOrNull()
            }
        } catch (e: Exception) {
            logDebug("Error caching reflection: ${e.message}")
        }

        XposedBridge.hookAllConstructors(
            waJobManagerMethod?.declaringClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    mWaJobManager = param.thisObject
                }
            })

        hookOnSendMessages()

        val ticktype = prefs.getString("seentick", "0")?.toIntOrNull() ?: 0
        if (ticktype == 0) return

        hookConversationScreen(ticktype)
        hookViewOnceScreen(ticktype)
        hookStatusScreen(ticktype)
    }

    private fun hookStatusScreen(ticktype: Int) {
        val viewButtonMethod = Unobfuscator.loadBlueOnReplayViewButtonMethod(classLoader)
        var viewStatusField: Field? = null
        val ifaceKeyStatusItemClass =
            Unobfuscator.loadUnknownStatusPlaybackMethod(classLoader).parameterTypes.first()
        val replyContainerMethod = Unobfuscator.loadStatusPlaybackReplyContainer(classLoader)

        if (ticktype == 1) {
            XposedBridge.hookMethod(viewButtonMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean("hidestatusview", false)) return

                    if (viewStatusField == null) {
                        viewStatusField =
                            ReflectionUtils.findFieldUsingFilter(param.thisObject.javaClass) { f ->
                                f.type == ifaceKeyStatusItemClass
                            }
                    }
                    val ifaceStatusItem = viewStatusField?.get(param.thisObject)
                    val fstatus = StatusItemWpp.from(ifaceStatusItem)

                    if (fstatus == null) {
                        logDebug("FMessage is null")
                        return
                    }

                    if (fstatus.isFromMe) return

                    val fieldViewContainer =
                        ReflectionUtils.findFieldUsingFilter(param.thisObject.javaClass) {
                            replyContainerMethod.declaringClass.isAssignableFrom(it.type)
                        } ?: return
                    val replyContainer =
                        replyContainerMethod.invoke(fieldViewContainer.get(param.thisObject)) ?: return
                    val replyView = XposedHelpers.callMethod(replyContainer, "A01") as View
                    val contentView =
                        replyView.findViewById<LinearLayout>(
                            Utils.getID(
                                "reply_bar_tappable",
                                "id"
                            )
                        ) ?: return

                    val replyBarBackground =
                        replyView.findViewById<View>(Utils.getID("reply_bar_background", "id")) ?: return

                    val buttonImage = ImageView(replyView.context)

                    val iconSize = Utils.dipToPixels(32f)

                    buttonImage.setImageResource(Utils.getID("ic_notif_mark_read", "drawable"))

                    val containerButton = FrameLayout(replyView.context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(DesignUtils.getBackgroundColorFromMap("#ff20272b"))
                        }
                    }

                    containerButton.addView(
                        buttonImage,
                        FrameLayout.LayoutParams(iconSize, iconSize).apply {
                            gravity = Gravity.CENTER
                        }
                    )

                    replyBarBackground.post {
                        val containerSize = replyBarBackground.height

                        containerButton.layoutParams = FrameLayout.LayoutParams(
                            containerSize,
                            containerSize,
                        ).apply {
                            setMargins(0, 0, Utils.dipToPixels(5f), 0)
                        }

                        val position = contentView.indexOfChild(replyBarBackground)
                        contentView.addView(containerButton, position + 1)
                    }

                    registerMessageView(fstatus.messageID, buttonImage)

                    buttonImage.setOnClickListener {
                        scope.launch {
                            Utils.showToast(
                                replyView.context.getString(R.string.sending_read_blue_tick),
                                Toast.LENGTH_SHORT
                            )
                            sendBlueTickStatus(
                                listOf(fstatus)
                            )
                            withContext(Dispatchers.Main) {
                                setSeenButton(buttonImage, true)
                            }
                        }
                    }

                    scope.launch(Dispatchers.IO) {
                        val item = MessageHistoryStore.getInstance().getHideSeenMessage(
                            "status@broadcast",
                            fstatus.messageID,
                            MessageHistoryStore.ReceiptType.READ
                        )
                        withContext(Dispatchers.Main) {
                            setSeenButton(buttonImage, item?.viewed ?: false)
                        }
                    }

                }
            })
        } else {
            MenuStatusListener.menuStatuses.add(object :
                MenuStatusListener.OnMenuItemStatusListener() {

                override fun addMenu(
                    menu: Menu,
                    statusData: MenuStatusListener.StatusData,
                ): MenuItem? {
                    if (menu.findItem(R.string.send_blue_tick) != null) return null
                    if (statusData.currentItem.isFromMe) return null
                    return menu.add(0, R.string.send_blue_tick, 0, R.string.send_blue_tick)
                }

                override fun onClick(
                    item: MenuItem,
                    statusData: MenuStatusListener.StatusData
                ) {
                    sendBlueTickStatus(listOf(statusData.currentItem))
                    Utils.showToast(
                        Utils.getString(R.string.sending_read_blue_tick),
                        Toast.LENGTH_SHORT
                    )
                }
            })
        }
    }

    private fun hookConversationScreen(ticktype: Int) {
        val onCreateMenuConversationMethod = Unobfuscator.loadOnCreatedMenuConversation(classLoader)

        XposedBridge.hookMethod(onCreateMenuConversationMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val menu = param.args[0] as Menu
                val menuItem = menu.add(0, 0, 0, R.string.send_blue_tick)
                if (ticktype == 1) menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menuItem.setIcon(Utils.getID("ic_notif_mark_read", "drawable"))
                menuItem.setOnMenuItemClickListener {
                    val currentUserJid = WppCore.getCurrentUserJid()
                    currentUserJid?.let { jid -> sendBlueTick(jid) }
                    Utils.showToast(
                        Utils.getString(R.string.sending_read_blue_tick),
                        Toast.LENGTH_SHORT
                    )
                    true
                }
            }
        })
    }

    private fun hookViewOnceScreen(ticktype: Int) {
        val menuMethod = Unobfuscator.loadViewOnceDownloadMenuMethod(classLoader)

        XposedBridge.hookMethod(menuMethod, object : XC_MethodHook() {
            @SuppressLint("DiscouragedApi")
            override fun afterHookedMethod(param: MethodHookParam) {
                val fmessageObj = ReflectionUtils.getArg(param.args, FMessageWpp.TYPE, 0) ?: return
                val fMessage = FMessageWpp(fmessageObj)
                if (!fMessage.isViewOnce) return

                val menu = ReflectionUtils.getArg(param.args, Menu::class.java, 0) ?: return

                val item = menu.add(0, 0, 0, R.string.send_blue_tick)
                    .setIcon(Utils.getID("ic_notif_mark_read", "drawable"))
                if (ticktype == 1) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

                item.setOnMenuItemClickListener {
                    val userJid = fMessage.key.remoteJid
                    val messageID = fMessage.key.messageID
                    val primaryJid = userJid.phoneRawString ?: userJid.userRawString ?: return@setOnMenuItemClickListener true
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        primaryJid,
                        messageID,
                        MessageHistoryStore.ReceiptType.PLAYED,
                        true
                    )
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        primaryJid,
                        messageID,
                        MessageHistoryStore.ReceiptType.READ,
                        true
                    )
                    sendBlueTickMedia(fMessage)
                    Utils.showToast(
                        Utils.getString(R.string.sending_read_blue_tick),
                        Toast.LENGTH_SHORT
                    )
                    true
                }
            }
        })

        XposedHelpers.findAndHookMethod(
            WppCore.viewOnceViewerActivityClass,
            "onCreateOptionsMenu",
            Menu::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val menu = param.args[0] as Menu
                    val item = menu.add(0, 0, 0, R.string.send_blue_tick)
                        .setIcon(Utils.getID("ic_notif_mark_read", "drawable"))
                    if (ticktype == 1) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

                    item.setOnMenuItemClickListener {
                        scope.launch(Dispatchers.IO) {
                            val keyClass = FMessageWpp.Key.TYPE
                            val fieldType =
                                ReflectionUtils.getFieldByType(param.thisObject.javaClass, keyClass) ?: return@launch
                            val keyMessage =
                                ReflectionUtils.getObjectField(fieldType, param.thisObject)
                            val fMessage = FMessageWpp.Key(keyMessage).fMessage ?: return@launch
                            val rawJid = fMessage.key.remoteJid.phoneRawString ?: fMessage.key.remoteJid.userRawString ?: return@launch
                            val messageID = fMessage.key.messageID

                            MessageHistoryStore.getInstance().updateViewedMessage(
                                rawJid,
                                messageID,
                                MessageHistoryStore.ReceiptType.PLAYED,
                                true
                            )
                            MessageHistoryStore.getInstance().updateViewedMessage(
                                rawJid,
                                messageID,
                                MessageHistoryStore.ReceiptType.READ,
                                true
                            )
                            sendBlueTickMedia(fMessage)
                            Utils.showToast(
                                Utils.getString(R.string.sending_read_blue_tick),
                                Toast.LENGTH_SHORT
                            )
                        }
                        true
                    }
                }
            }
        )
    }

    private fun hookOnSendMessages() {
        val messageJobMethod = runCatching { Unobfuscator.loadBlueOnReplayMessageJobMethod(classLoader) }.getOrNull() ?: return
        val messageSendClass = runCatching {
            Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.Contains,
                "SendE2EMessageJob"
            )
        }.getOrNull()

        XposedBridge.hookMethod(messageJobMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Utils.isBlueOnReplyEnabled(prefs)) return

                scope.launch(Dispatchers.IO) {
                    runCatching {
                        // Jangan cast langsung — bisa ClassCastException silent kalau obfuscated class berubah
                        val obj = param.thisObject ?: return@launch
                        val userJid = runCatching { FMessageWpp.UserJid.extractFrom(obj) }.getOrNull()
                            ?: WppCore.getCurrentUserJid() ?: return@launch

                        logDebug("[SeenTick] hookOnSendMessages triggered, userJid=$userJid")

                        if (userJid.isStatus) {
                            val listStatus = MenuStatusListener.statusData.getCurrentItemList()
                            listStatus.forEach { fstatus ->
                                val view = getRegisteredView(fstatus.messageID)
                                view?.post {
                                    setSeenButton(view, true)
                                }
                            }
                            sendBlueTickStatus(listStatus)
                        } else {
                            sendBlueTick(userJid)
                        }
                    }.onFailure { e ->
                        logDebug("[SeenTick] hookOnSendMessages error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        })
    }

    private fun getJobManager(): Any? {
        if (mWaJobManager != null) return mWaJobManager
        val cls = waJobManagerMethod?.declaringClass ?: return null
        for (f in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) && cls.isAssignableFrom(f.type)) {
                try {
                    f.isAccessible = true
                    val inst = f.get(null)
                    if (inst != null) {
                        mWaJobManager = inst
                        return inst
                    }
                } catch (_: Throwable) {}
            }
        }
        return mWaJobManager
    }

    fun sendBlueTick(userJid: FMessageWpp.UserJid) {
        scope.launch {
            val phoneNumber = userJid.phoneNumber
            val userRaw = userJid.userRawString ?: ""
            if (phoneNumber == Utils.getMyNumber() || userRaw.contains("lid_me") || userRaw.contains("status_me")) return@launch

            val primaryJid = userJid.phoneRawString ?: userRaw
            if (primaryJid.isBlank()) return@launch

            // Mutex per-jid: tunggu sampai trigger sebelumnya selesai update DB
            // sehingga trigger berikutnya tidak dapat pesan yang sama dari DB
            sendBlueTickMutex.withLock {
                sendBlueTickInternal(userJid, primaryJid, userRaw)
            }
        }
    }

    private suspend fun sendBlueTickInternal(userJid: FMessageWpp.UserJid, primaryJid: String, userRaw: String) {

            // Kumpulkan semua varian jid yang mungkin dipakai saat insert di HideSeen
            // HideSeen insert dengan phoneRawString dan juga userRawString (dobel)
            // userRawString dari extractFrom(job) bisa berisi full lid seperti "101542237626618@lid"
            // tapi userJid.userRawString di sini hanya "6618:0@lid" (terpotong)
            // Ambil full raw string dari object JID langsung via getRawString
            val fullUserRaw: String? = runCatching {
                userJid.userJid?.let {
                    XposedHelpers.callMethod(it, "getRawString") as? String
                }
            }.getOrNull()

            val allJids = linkedSetOf<String>()
            if (primaryJid.isNotBlank()) allJids.add(primaryJid)
            if (userRaw.isNotBlank() && userRaw != primaryJid) allJids.add(userRaw)
            if (!fullUserRaw.isNullOrBlank() && fullUserRaw != primaryJid && fullUserRaw != userRaw) allJids.add(fullUserRaw)

            val messages = ArrayList<FMessageWpp>()
            var hiddenMessages: List<MessageHistoryStore.MessageSeenItem>? = null

            for (jid in allJids) {
                val result = MessageHistoryStore.getInstance()
                    .getHideSeenMessages(jid, MessageHistoryStore.ReceiptType.READ, false)
                if (!result.isNullOrEmpty()) {
                    hiddenMessages = result
                    break
                }
            }

            hiddenMessages?.forEach { message ->
                message.fMessage?.let { messages.add(it) }
            }

            // Also check active conversation items if database has no records
            if (messages.isEmpty() && (hiddenMessages == null || hiddenMessages.isEmpty())) {
                val jidPhone = userJid.phoneNumber ?: ""
                val activeItems = com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener.listItems.values
                    .map { it.message }
                    .filter { m ->
                        if (m.key.isFromMe || m.key.remoteJid.isNull) return@filter false
                        val mJid = m.key.remoteJid
                        val mPhone = mJid.phoneRawString ?: mJid.userRawString ?: ""
                        mPhone.isNotBlank() && (
                            mPhone == primaryJid ||
                            mPhone == userRaw ||
                            (jidPhone.isNotBlank() && mPhone.contains(jidPhone)) ||
                            (mPhone.isNotBlank() && primaryJid.contains(mPhone.substringBefore("@")))
                        )
                    }
                    .distinctBy { it.key.messageID }

                // Insert ke DB sebelum dikirim supaya tidak dikirim ulang di trigger berikutnya
                activeItems.forEach { m ->
                    MessageHistoryStore.getInstance().insertHideSeenMessage(
                        primaryJid,
                        m.key.messageID,
                        MessageHistoryStore.ReceiptType.READ,
                        false
                    )
                }
                messages.addAll(activeItems)
            }

            if (messages.isEmpty()) {
                val ids = hiddenMessages?.map { it.message } ?: emptyList()
                if (ids.isNotEmpty()) {
                    ids.forEach { msgId ->
                        for (jid in allJids) {
                            MessageHistoryStore.getInstance().updateViewedMessage(
                                jid, msgId, MessageHistoryStore.ReceiptType.READ, true
                            )
                        }
                    }
                    sendBlueTickMsgDirect(userJid, ids.toTypedArray())
                }
                return
            }

            messages.forEach { m ->
                if (m.mediaType == 2) {
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        primaryJid,
                        m.key.messageID,
                        MessageHistoryStore.ReceiptType.PLAYED,
                        true
                    )
                    sendBlueTickMedia(m)
                }
            }

            messages.forEach { msg ->
                val msgId = msg.key.messageID
                // Update semua varian jid yang mungkin diinsert oleh HideSeen
                for (jid in allJids) {
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        jid, msgId, MessageHistoryStore.ReceiptType.READ, true
                    )
                }
            }

            if (messages.isNotEmpty()) {
                val allIds = messages.map { it.key.messageID }.toTypedArray()
                sendBlueTickMsgDirect(userJid, allIds)
            }
    }

    private fun buildSendReadReceiptJobArgs(
        userJid: FMessageWpp.UserJid,
        userJidMsg: FMessageWpp.UserJid?,
        isGroup: Boolean,
        messageIds: Array<String>,
        paramTypes: Array<Class<*>>
    ): Array<Any?> {
        val args = ReflectionUtils.initArray(paramTypes)

        val phoneRaw = (userJid.phoneRawString ?: userJid.phoneNumber?.let { "$it@s.whatsapp.net" } ?: "").replaceFirst(":[\\d]+@".toRegex(), "@")
        val chatJidObj = (if (phoneRaw.isNotBlank()) WppCore.createUserJid(phoneRaw) else null)
            ?: (if (!userJid.phoneNumber.isNullOrBlank()) WppCore.createUserJid("${userJid.phoneNumber}@s.whatsapp.net") else null)
            ?: userJid.phoneJid

        val participantJidObj = if (isGroup) {
            val partRaw = userJidMsg?.phoneRawString ?: userJidMsg?.userRawString ?: ""
            (if (partRaw.isNotBlank()) WppCore.createUserJid(partRaw) else null)
                ?: userJidMsg?.phoneJid
                ?: userJidMsg?.userJid
        } else null

        // Untuk chat personal (1-on-1), fromJid WA asli = null (participant = null)
        // Untuk group chat, fromJid = pengirim pesan (participant)
        val fromJidObj = if (isGroup) (participantJidObj ?: chatJidObj) else null

        // WA asli menggunakan microseconds untuk messageServerStoreTimeMicros
        // Parameter long di constructor:
        // long[0] -> originalMessageTimestamp = -1L
        // long[1] -> loggableStanzaId = 0L
        // long[2] -> messageServerStoreTimeMicros = nowMicros
        val nowMicros = System.currentTimeMillis() * 1000L

        var longParamCount = 0
        for (i in paramTypes.indices) {
            val p = paramTypes[i]
            when {
                i == 0 -> args[0] = chatJidObj
                i == 1 -> args[1] = fromJidObj
                i == 2 -> args[2] = null  // selalu null
                i == 3 && p.name.contains("DeviceJid") -> args[3] = null
                p == Array<String>::class.java -> args[i] = messageIds
                p == Long::class.java || p == Long::class.javaObjectType -> {
                    args[i] = when (longParamCount) {
                        0 -> -1L         // originalMessageTimestamp
                        1 -> 0L          // loggableStanzaId
                        else -> nowMicros // messageServerStoreTimeMicros
                    }
                    longParamCount++
                }
                p == Boolean::class.java || p == Boolean::class.javaObjectType -> args[i] = false
                p == String::class.java -> args[i] = null
                p.name.contains(".jid.") || p.name.endsWith("Jid") -> {
                    if (args[i] == null && chatJidObj != null && p.isInstance(chatJidObj)) {
                        args[i] = chatJidObj
                    }
                }
            }
        }
        return args
    }

    private fun sendBlueTickMsgDirect(userJid: FMessageWpp.UserJid, messageIds: Array<String>) {
        if (messageIds.isEmpty()) return
        val constr = sendJobConstructor ?: return
        val paramTypes = sendJobParamTypes ?: return

        try {
            val args = buildSendReadReceiptJobArgs(userJid, null, userJid.isGroup, messageIds, paramTypes)
            logDebug("[SeenTick] invoking constr direct with args: ${args.mapIndexed { idx, v -> "[$idx] ${paramTypes[idx].simpleName}=$v" }}")
            val sendJob = constr.newInstance(*args)
            XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true)
            val jobMgr = getJobManager()
            waJobManagerMethod?.invoke(jobMgr, sendJob)
            logDebug("[SeenTick] SendReadReceiptJob (direct) dispatched successfully for ${messageIds.joinToString()}")
        } catch (ex: Throwable) {
            val target = if (ex is java.lang.reflect.InvocationTargetException) ex.targetException ?: ex else ex
            logDebug("[SeenTick] sendBlueTickMsgDirect ERROR: ${target.javaClass.name}: ${target.message}")
            target.stackTrace.take(8).forEach { logDebug("   at $it") }
        }
    }

    private fun sendBlueTickMsg(userJid: FMessageWpp.UserJid, messages: ArrayList<FMessageWpp>) {
        if (messages.isEmpty()) return
        val constr = sendJobConstructor ?: return
        val paramTypes = sendJobParamTypes ?: return

        val groupedMap = HashMap<FMessageWpp.UserJid, MutableList<FMessageWpp>>(4)
        val isGroup = userJid.isGroup

        for (message in messages) {
            val userJidMsg = (if (isGroup) message.userJid else message.key.remoteJid)
            groupedMap.computeIfAbsent(userJidMsg) { ArrayList(if (isGroup) 4 else messages.size) }
                .add(message)
        }

        for ((userJidMsg, groupMessages) in groupedMap) {
            try {
                val groupSize = groupMessages.size
                val messageIds = Array(groupSize) { i -> groupMessages[i].key.messageID }

                val args = buildSendReadReceiptJobArgs(userJid, userJidMsg, isGroup, messageIds, paramTypes)
                logDebug("[SeenTick] invoking constr with args: ${args.mapIndexed { idx, v -> "[$idx] ${paramTypes[idx].simpleName}=$v" }}")
                val sendJob = constr.newInstance(*args)
                XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true)
                val jobMgr = getJobManager()
                waJobManagerMethod?.invoke(jobMgr, sendJob)
                logDebug("[SeenTick] SendReadReceiptJob dispatched successfully for ${messageIds.joinToString()}")
            } catch (ex: Throwable) {
                val target = if (ex is java.lang.reflect.InvocationTargetException) ex.targetException ?: ex else ex
                logDebug("[SeenTick] sendBlueTickMsg ERROR: ${target.javaClass.name}: ${target.message}")
                target.stackTrace.take(8).forEach { logDebug("   at $it") }
            }
        }
    }

    private fun sendBlueTickStatus(fstatus: List<StatusItemWpp>) {
        if (fstatus.isEmpty()) return
        val currentJidTarget = fstatus.first().senderJid ?: return

        scope.launch {
            try {
                val size = fstatus.size
                val constr = sendJobConstructor ?: return@launch
                val jidIndexes = sendJobJidIndexes ?: return@launch
                val paramTypes = sendJobParamTypes ?: return@launch

                if (jidIndexes.size < 2 || sendJobMessageIdIndex == -1) return@launch

                val arrS = Array(size) { "" }
                val messageHistory = MessageHistoryStore.getInstance()

                for (i in 0 until size) {
                    val msgId = fstatus[i].messageID
                    arrS[i] = msgId
                    messageHistory.updateViewedMessage(
                        "status@broadcast",
                        msgId,
                        MessageHistoryStore.ReceiptType.READ,
                        true
                    )
                }

                val userJidSender = WppCore.createUserJid("status@broadcast")

                val args = ReflectionUtils.initArray(paramTypes)
                args[jidIndexes[0].first] = userJidSender
                args[jidIndexes[1].first] = currentJidTarget.userJid
                args[sendJobMessageIdIndex] = arrS

                val sendJob2 = constr.newInstance(*args)
                XposedHelpers.setAdditionalInstanceField(sendJob2, "blue_on_reply", true)
                waJobManagerMethod?.invoke(mWaJobManager, sendJob2)
            } catch (e: Exception) {
                logDebug(e)
            }
        }
    }

    private fun sendBlueTickMedia(fMessage: FMessageWpp) {
        scope.launch {
            try {
                val userJid = fMessage.key.remoteJid
                val participant = if (userJid.isGroup) fMessage.userJid.userJid else null

                val sPlayedClass = sendPlayedClass ?: return@launch
                val pInfoConstructor = participantInfoConstructor ?: return@launch

                val rowsId = arrayOf(fMessage.rowId)
                val messageId = fMessage.key.messageID

                val participantInfo = pInfoConstructor.newInstance(
                    userJid.userJid,
                    participant,
                    rowsId,
                    arrayOf(messageId)
                )
                val sendJob = XposedHelpers.newInstance(sPlayedClass, participantInfo, false)

                waJobManagerMethod?.invoke(mWaJobManager, sendJob)
            } catch (e: Throwable) {
                logDebug(e)
            }
        }
    }

    override fun getPluginName(): String {
        return "Seen Tick"
    }
}
