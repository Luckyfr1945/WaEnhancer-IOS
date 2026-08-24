package com.wmods.wppenhacer.xposed.features.general

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.wmods.wppenhacer.xposed.utils.DebugUtils
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

class SeenTick(
    loader: ClassLoader,
    preferences: SharedPreferences
) : Feature(loader, preferences) {

    private val messageMap = ConcurrentHashMap<String, WeakReference<ImageView>>()
    private val viewStatusFieldMap = ConcurrentHashMap<Class<*>, Field?>()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + WaeCoroutineExceptionHandler)

    companion object {
        @Volatile
        var instance: SeenTick? = null

        fun triggerBlueOnReply(userJid: FMessageWpp.UserJid) {
            instance?.let { seenTick ->
                if (!Utils.isBlueOnReplyEnabled(seenTick.prefs)) return
                seenTick.sendBlueTick(userJid)
            }
        }

        @Volatile
        private var mWaJobManager: Any? = null
        @Volatile
        private var mSendReadClass: Class<*>? = null
        @Volatile
        private var waJobManagerMethod: Method? = null

        @Volatile
        private var cachedSeenDrawable: Drawable? = null
        @Volatile
        private var cachedUnseenDrawable: Drawable? = null

        @Volatile
        private var sendJobConstructor: Constructor<*>? = null
        @Volatile
        private var sendJobParamTypes: Array<Class<*>>? = null
        @Volatile
        private var sendJobJidIndices: List<Int> = emptyList()
        @Volatile
        private var sendJobMessageIdIndex: Int = -1

        @Volatile
        private var sendPlayedClass: Class<*>? = null
        @Volatile
        private var sendPlayedConstructor: Constructor<*>? = null
        @Volatile
        private var participantInfoConstructor: Constructor<*>? = null

        fun setSeenButton(buttonImage: ImageView, isSeen: Boolean) {
            if (isSeen && cachedSeenDrawable != null) {
                buttonImage.setImageDrawable(cachedSeenDrawable)
                buttonImage.postInvalidate()
                return
            } else if (!isSeen && cachedUnseenDrawable != null) {
                buttonImage.setImageDrawable(cachedUnseenDrawable)
                buttonImage.postInvalidate()
                return
            }

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
                cachedSeenDrawable = clonedDrawable
            } else {
                clonedDrawable.clearColorFilter()
                cachedUnseenDrawable = clonedDrawable
            }

            buttonImage.setImageDrawable(clonedDrawable)
            buttonImage.postInvalidate()
        }
    }

    private fun registerMessageView(messageId: String?, view: ImageView?) {
        if (messageId == null || view == null) return
        if (messageMap.size > 200) {
            messageMap.entries.removeIf { it.value.get() == null }
        }
        messageMap[messageId] = WeakReference(view)
    }

    private fun getRegisteredView(messageId: String?): ImageView? {
        return messageMap[messageId]?.get()
    }

    override fun doHook() {
        instance = this
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
                val constr = candidates.maxByOrNull { it.parameterCount }
                    ?: cls.declaredConstructors.maxByOrNull { it.parameterCount }
                    ?: cls.declaredConstructors.firstOrNull()

                constr?.let { c ->
                    c.isAccessible = true
                    sendJobConstructor = c
                    val paramTypes = c.parameterTypes
                    sendJobParamTypes = paramTypes

                    val jidClass = FMessageWpp.UserJid.TYPE_JID
                    val jidIndices = mutableListOf<Int>()
                    var msgIdIdx = -1

                    for (i in paramTypes.indices) {
                        val p = paramTypes[i]
                        if (p == Array<String>::class.java) {
                            msgIdIdx = i
                        } else if (jidClass != null && jidClass.isAssignableFrom(p)) {
                            jidIndices.add(i)
                        } else if (p.name.contains("jid", ignoreCase = true)) {
                            jidIndices.add(i)
                        }
                    }

                    sendJobJidIndices = jidIndices
                    sendJobMessageIdIndex = msgIdIdx
                }
            }

            sendPlayedClass = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.Contains,
                "SendPlayedReceiptJob"
            )
            sendPlayedClass?.let { cls ->
                val constr = cls.declaredConstructors.maxByOrNull { it.parameterCount }
                    ?: cls.declaredConstructors.firstOrNull()
                constr?.isAccessible = true
                sendPlayedConstructor = constr

                val classParticipantInfo = constr?.parameterTypes?.firstOrNull()
                classParticipantInfo?.let { pCls ->
                    val pConstr = pCls.declaredConstructors.filter { c ->
                        c.parameterTypes.any { it == Array<String>::class.java }
                    }.maxByOrNull { it.parameterCount }
                        ?: pCls.declaredConstructors.maxByOrNull { it.parameterCount }
                        ?: pCls.declaredConstructors.firstOrNull()

                    pConstr?.isAccessible = true
                    participantInfoConstructor = pConstr
                }
            }
        } catch (e: Throwable) {
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

                    val ifaceStatusItem = viewStatusFieldMap.computeIfAbsent(param.thisObject.javaClass) { cls ->
                        ReflectionUtils.findFieldUsingFilter(cls) { f ->
                            f.type == ifaceKeyStatusItemClass
                        }
                    }?.get(param.thisObject)
                    val fstatus = StatusItemWpp.from(ifaceStatusItem)

                    if (fstatus == null) {
                        log("FMessage is null")
                        return
                    }

                    if (fstatus.isFromMe) return

                    val fieldViewContainer =
                        ReflectionUtils.findFieldUsingFilter(param.thisObject.javaClass) {
                            replyContainerMethod.declaringClass.isAssignableFrom(it.type)
                        }
                    val replyContainer =
                        replyContainerMethod.invoke(fieldViewContainer.get(param.thisObject))
                    val replyView = XposedHelpers.callMethod(replyContainer, "A01") as View
                    val contentView =
                        replyView.findViewById<LinearLayout>(
                            Utils.getID(
                                "reply_bar_tappable",
                                "id"
                            )
                        )

                    val replyBarBackground =
                        replyView.findViewById<View>(Utils.getID("reply_bar_background", "id"))

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

        MenuStatusListener.menuStatuses.add(object : MenuStatusListener.OnMenuItemStatusListener() {
            override fun addMenu(
                menu: Menu,
                statusData: MenuStatusListener.StatusData
            ): MenuItem? {
                if (menu.findItem(R.string.read_all_mark_as_read) != null) return null
                if (statusData.currentItem.isFromMe) return null
                return menu.add(
                    0,
                    R.string.read_all_mark_as_read,
                    0,
                    R.string.read_all_mark_as_read
                )
            }

            override fun onClick(
                item: MenuItem,
                statusData: MenuStatusListener.StatusData
            ) {
                val listStatus = statusData.getCurrentItemList()
                listStatus.forEach { fStatus ->
                    val view = getRegisteredView(fStatus.messageID)
                    view?.post {
                        setSeenButton(view, true)
                    }
                }
                sendBlueTickStatus(listStatus)
                Utils.showToast(
                    Utils.getString(R.string.sending_read_blue_tick),
                    Toast.LENGTH_SHORT
                )
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

                val menu = ReflectionUtils.getArg(param.args, Menu::class.java, 0)
                if (menu == null) {
                    logDebug("Menu is null")
                    return
                }

                val item = menu.add(0, 0, 0, R.string.send_blue_tick)
                    .setIcon(Utils.getID("ic_notif_mark_read", "drawable"))
                if (ticktype == 1) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

                item.setOnMenuItemClickListener {
                    val userJid = fMessage.key.remoteJid
                    val messageID = fMessage.key.messageID
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        userJid.phoneRawString,
                        messageID,
                        MessageHistoryStore.ReceiptType.PLAYED,
                        true
                    )
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        userJid.phoneRawString,
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
                                ReflectionUtils.getFieldByType(param.thisObject.javaClass, keyClass)
                            val keyMessage =
                                ReflectionUtils.getObjectField(fieldType, param.thisObject)
                            val fMessage = FMessageWpp.Key(keyMessage).fMessage ?: return@launch
                            val rawJid = fMessage.key.remoteJid.phoneRawString
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
        // Blue on reply is triggered safely via HideSeenView when the outgoing message is bound.
    }

    fun sendBlueTick(userJid: FMessageWpp.UserJid) {
        scope.launch {
            val phoneNumber = userJid.phoneNumber
            val userRaw = userJid.userRawString ?: ""
            if (phoneNumber == Utils.getMyNumber() || userRaw.contains("lid_me") || userRaw.contains("status_me")) return@launch

            val jidKey = userJid.phoneRawString ?: userRaw
            if (jidKey.isBlank()) return@launch

            val jidPattern = if (!phoneNumber.isNullOrEmpty()) "%$phoneNumber%" else "%$jidKey%"
            
            XposedBridge.log("[WaEnhancer] Querying hidden messages for jidKey=$jidKey, userRaw=$userRaw, pattern=$jidPattern")

            var hiddenMessages = MessageHistoryStore.getInstance()
                .getHideSeenMessages(
                    jidKey,
                    MessageHistoryStore.ReceiptType.READ,
                    false
                )
            if (hiddenMessages.isNullOrEmpty()) {
                hiddenMessages = MessageHistoryStore.getInstance().getHideSeenMessagesByPattern(
                    jidKey,
                    jidPattern,
                    MessageHistoryStore.ReceiptType.READ,
                    false
                )
            }
            if (hiddenMessages.isNullOrEmpty() && userJid.userRawString != null && userJid.userRawString != jidKey) {
                hiddenMessages = MessageHistoryStore.getInstance().getHideSeenMessages(
                    userJid.userRawString,
                    MessageHistoryStore.ReceiptType.READ,
                    false
                )
            }

            XposedBridge.log("[WaEnhancer] Found ${hiddenMessages?.size ?: 0} hidden messages to mark read")
            if (hiddenMessages.isNullOrEmpty()) return@launch

            val messages = ArrayList<FMessageWpp>()
            val messageIds = ArrayList<String>()

            hiddenMessages.forEach { item ->
                messageIds.add(item.message)
                MessageHistoryStore.getInstance().updateViewedMessage(
                    item.jid,
                    item.message,
                    MessageHistoryStore.ReceiptType.READ,
                    true
                )
                if (item.jid != jidKey) {
                    MessageHistoryStore.getInstance().updateViewedMessage(
                        jidKey,
                        item.message,
                        MessageHistoryStore.ReceiptType.READ,
                        true
                    )
                }
                item.fMessage?.let { m ->
                    messages.add(m)
                    if (m.mediaType == 2) {
                        MessageHistoryStore.getInstance().updateViewedMessage(
                            item.jid,
                            m.key.messageID,
                            MessageHistoryStore.ReceiptType.PLAYED,
                            true
                        )
                        sendBlueTickMedia(m)
                    }
                }
            }

            WppCore.getCurrentActivity()?.runOnUiThread {
                com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener.notifyDataSetChanged()
            }

            if (messages.isNotEmpty()) {
                sendBlueTickMsg(userJid, messages)
            } else if (messageIds.isNotEmpty()) {
                sendBlueTickMsgDirect(userJid, messageIds.toTypedArray())
            }
        }
    }

    private fun createSendReadReceiptJob(
        chatJid: Any?,
        fromJid: Any?,
        participantJid: Any?,
        messageIds: Array<String>,
        isGroup: Boolean
    ): Any? {
        val constr = sendJobConstructor ?: return null
        val paramTypes = sendJobParamTypes ?: return null
        if (messageIds.isEmpty() || sendJobMessageIdIndex == -1) return null

        val args = arrayOfNulls<Any>(paramTypes.size)

        // 1. Isi default primitives secara realistis
        for (i in paramTypes.indices) {
            when (paramTypes[i]) {
                java.lang.Long.TYPE, java.lang.Long::class.java -> args[i] = System.currentTimeMillis()
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> args[i] = 0
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> args[i] = isGroup
                else -> args[i] = null
            }
        }

        // 2. Set messageIds ke slot yang tepat
        if (sendJobMessageIdIndex in args.indices) {
            args[sendJobMessageIdIndex] = messageIds
        }

        // 3. Mapping Jid secara presisi
        val jids = sendJobJidIndices
        if (jids.isNotEmpty()) {
            if (jids.size >= 1) args[jids[0]] = chatJid
            if (jids.size >= 2) args[jids[1]] = fromJid ?: chatJid
            if (jids.size >= 3) args[jids[2]] = participantJid
        }

        return try {
            constr.newInstance(*args)
        } catch (e: Throwable) {
            logDebug("Error creating SendReadReceiptJob: ${e.message}")
            null
        }
    }

    private fun sendBlueTickMsgDirect(userJid: FMessageWpp.UserJid, messageIds: Array<String>) {
        if (messageIds.isEmpty()) return
        val targetJid = userJid.phoneJid ?: userJid.userJid ?: return
        try {
            val sendJob = createSendReadReceiptJob(
                chatJid = targetJid,
                fromJid = targetJid,
                participantJid = null,
                messageIds = messageIds,
                isGroup = userJid.isGroup
            ) ?: return

            XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true)
            waJobManagerMethod?.invoke(mWaJobManager, sendJob)
        } catch (ex: Throwable) {
            logDebug(ex)
        }
    }

    private fun sendBlueTickMsg(userJid: FMessageWpp.UserJid, messages: ArrayList<FMessageWpp>) {
        if (messages.isEmpty()) return
        val targetJid = userJid.phoneJid ?: userJid.userJid ?: return

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
                val participantJid = if (isGroup) (userJidMsg.phoneJid ?: userJidMsg.userJid) else null

                val sendJob = createSendReadReceiptJob(
                    chatJid = targetJid,
                    fromJid = if (isGroup) participantJid else targetJid,
                    participantJid = participantJid,
                    messageIds = messageIds,
                    isGroup = isGroup
                ) ?: continue

                XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true)
                waJobManagerMethod?.invoke(mWaJobManager, sendJob)
            } catch (ex: Throwable) {
                logDebug(ex)
            }
        }
    }

    private fun sendBlueTickStatus(
        fstatus: List<StatusItemWpp>
    ) {
        if (fstatus.isEmpty()) return
        val currentJidTarget = fstatus.first().senderJid ?: return

        scope.launch {
            try {
                val size = fstatus.size
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

                val statusBroadcastJid = WppCore.createUserJid("status@broadcast")
                val senderJid = currentJidTarget.phoneJid ?: currentJidTarget.userJid

                val sendJob = createSendReadReceiptJob(
                    chatJid = statusBroadcastJid,
                    fromJid = senderJid,
                    participantJid = senderJid,
                    messageIds = arrS,
                    isGroup = false
                ) ?: return@launch

                XposedHelpers.setAdditionalInstanceField(sendJob, "blue_on_reply", true)
                waJobManagerMethod?.invoke(mWaJobManager, sendJob)
            } catch (e: Throwable) {
                logDebug(e)
            }
        }
    }

    private fun sendBlueTickMedia(fMessage: FMessageWpp) {
        scope.launch {
            try {
                val userJid = fMessage.key.remoteJid
                val participant = if (userJid.isGroup) (fMessage.userJid.phoneJid ?: fMessage.userJid.userJid) else null

                val sPlayedClass = sendPlayedClass ?: return@launch
                val pInfoConstructor = participantInfoConstructor ?: return@launch

                val rowsId = arrayOf(fMessage.rowId)
                val messageId = fMessage.key.messageID
                val targetJid = userJid.phoneJid ?: userJid.userJid

                val participantInfo = pInfoConstructor.newInstance(
                    targetJid,
                    participant,
                    rowsId,
                    arrayOf(messageId)
                )

                val sPlayedConstr = sendPlayedConstructor
                val sendJob = if (sPlayedConstr != null && sPlayedConstr.parameterCount == 2) {
                    sPlayedConstr.newInstance(participantInfo, false)
                } else if (sPlayedConstr != null && sPlayedConstr.parameterCount == 1) {
                    sPlayedConstr.newInstance(participantInfo)
                } else {
                    XposedHelpers.newInstance(sPlayedClass, participantInfo, false)
                }

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
