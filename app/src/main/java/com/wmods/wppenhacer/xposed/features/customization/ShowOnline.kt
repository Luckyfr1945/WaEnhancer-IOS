package com.wmods.wppenhacer.xposed.features.customization

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.core.text.TextUtilsCompat
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.core.devkit.UnobfuscatorCache
import com.wmods.wppenhacer.xposed.features.listeners.ContactItemListener
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

class ShowOnline(loader: ClassLoader, preferences:SharedPreferences) : Feature(loader, preferences) {

    private var mStatusUser: Any? = null
    private var mInstancePresence: Any? = null
    private var sendPresenceMethod: Method? = null
    private var tcTokenMethod: Method? = null
    private var getStatusUser: Method? = null
    private var fieldTokenDBInstance: Field? = null
    private var tokenClass: Class<*>? = null

    override fun doHook() {
        val showOnlineText = prefs.getBoolean("showonlinetext", false)
        val showOnlineIcon = prefs.getBoolean("dotonline", false)
        if (!showOnlineText && !showOnlineIcon) return

        val classViewHolder = Unobfuscator.loadViewHolder(classLoader)
        XposedBridge.hookAllConstructors(classViewHolder, object : XC_MethodHook() {
            @SuppressLint("ResourceType")
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.args[1] as View
                val context = param.args[0] as Context
                var content = view.findViewById<LinearLayout>(Utils.getID("conversations_row_content", "id"))
                if (content == null) {
                    content = view.findViewById(Utils.getID("row_content", "id"))
                }
                if (showOnlineText) {
                    val linearLayout = LinearLayout(context)
                    linearLayout.gravity = Gravity.END or Gravity.TOP
                    content.addView(linearLayout)

                    val lastSeenText = TextView(context)
                    lastSeenText.id = 0x7FFF0002
                    lastSeenText.textSize = 12f
                    lastSeenText.text = ""
                    lastSeenText.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    lastSeenText.gravity = Gravity.CENTER_VERTICAL
                    lastSeenText.visibility = View.VISIBLE
                    linearLayout.addView(lastSeenText)
                }
                if (showOnlineIcon) {
                    val contactPhoto = view.findViewById<View>(Utils.getID("contact_photo", "id"))
                    if (contactPhoto != null) {
                        val contactView = contactPhoto.parent as? ViewGroup
                        if (contactView != null && contactView.id != 0x7FFF0003) {
                            val isLeftToRight = TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_LTR
                            val index = contactView.indexOfChild(contactPhoto)
                            contactView.removeView(contactPhoto)

                            val relativeLayout = RelativeLayout(context)
                            relativeLayout.id = 0x7FFF0003
                            relativeLayout.layoutParams = contactPhoto.layoutParams

                            val params = RelativeLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            params.addRule(RelativeLayout.CENTER_IN_PARENT)
                            contactPhoto.layoutParams = params
                            relativeLayout.addView(contactPhoto)
                            contactView.addView(relativeLayout, index)

                            val imageView = ImageView(context)
                            imageView.id = 0x7FFF0001
                            val params2 = RelativeLayout.LayoutParams(
                                Utils.dipToPixels(14), Utils.dipToPixels(14)
                            )
                            params2.addRule(RelativeLayout.ALIGN_TOP, contactPhoto.id)
                            params2.addRule(
                                if (isLeftToRight) RelativeLayout.ALIGN_RIGHT else RelativeLayout.ALIGN_LEFT,
                                contactPhoto.id
                            )
                            params2.topMargin = Utils.dipToPixels(5)
                            imageView.layoutParams = params2
                            imageView.setImageResource(R.drawable.online)
                            imageView.adjustViewBounds = true
                            imageView.scaleType = ImageView.ScaleType.FIT_XY
                            imageView.visibility = View.INVISIBLE
                            relativeLayout.addView(imageView)
                        }
                    }
                }
            }
        })

        getStatusUser = Unobfuscator.loadStatusUserMethod(classLoader)
        sendPresenceMethod = Unobfuscator.loadSendPresenceMethod(classLoader)
        tcTokenMethod = Unobfuscator.loadTcTokenMethod(classLoader)

        XposedBridge.hookAllConstructors(getStatusUser!!.declaringClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                mStatusUser = param.thisObject
            }
        })

        XposedBridge.hookAllConstructors(sendPresenceMethod!!.declaringClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                mInstancePresence = param.thisObject
            }
        })

        tokenClass = sendPresenceMethod!!.parameterTypes[2]
        fieldTokenDBInstance = ReflectionUtils.getFieldByExtendType(
            sendPresenceMethod!!.declaringClass, tcTokenMethod!!.declaringClass
        )

        ContactItemListener.contactListeners.add(object : ContactItemListener.OnContactItemListener() {
            @SuppressLint("ResourceType")
            override fun onBind(waContact: WaContactWpp?, view: View?) {
                if (waContact == null || view == null) return
                val userJid = waContact.userJid
                if (userJid.isGroup) return

                val csDot: ImageView? = if (showOnlineIcon) view.findViewById(0x7FFF0001) else null
                if (showOnlineIcon && csDot != null) {
                    csDot.visibility = View.INVISIBLE
                }
                val lastSeenText: TextView? = if (showOnlineText) view.findViewById(0x7FFF0002) else null
                if (showOnlineText && lastSeenText != null) {
                    lastSeenText.text = ""
                }

                val currentJid = userJid.phoneRawString ?: userJid.userRawString ?: return
                view.setTag(0x7FFF0003, currentJid)

                Utils.executor.execute {
                    try {
                        val mInstance = mInstancePresence ?: return@execute
                        val mStatus = mStatusUser ?: return@execute
                        val sPresence = sendPresenceMethod ?: return@execute
                        val gStatus = getStatusUser ?: return@execute
                        val tClass = tokenClass ?: return@execute
                        val fTokenDB = fieldTokenDBInstance ?: return@execute

                        val tokenDBInstance = fTokenDB.get(mInstance)
                        val tokenData = tcTokenMethod?.let {
                            ReflectionUtils.callMethod(it, tokenDBInstance, userJid.userJid)
                        }
                        val tokenObj = tClass.constructors[0].newInstance(
                            if (tokenData == null) null else XposedHelpers.getObjectField(tokenData, "A01")
                        )
                        sPresence.invoke(null, userJid.userJid, null, tokenObj, mInstance)
                        val statusRaw = ReflectionUtils.callMethod(gStatus, mStatus, waContact.getObject(), false)
                        val status = statusRaw?.toString()

                        view.post {
                            if (view.getTag(0x7FFF0003) == currentJid) {
                                setStatus(status, csDot, lastSeenText)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        })
    }

    override fun getPluginName(): String {
        return "Conversation"
    }

    companion object {
        private fun isOnlineStatus(status: String?): Boolean {
            if (status.isNullOrBlank()) return false
            val trimmed = status.trim()
            val onlineStr = getOnlineString()
            if (trimmed.equals(onlineStr, ignoreCase = true)) return true
            if (trimmed.equals("online", ignoreCase = true)) return true
            if (trimmed.equals("en línea", ignoreCase = true)) return true
            if (trimmed.equals("en linea", ignoreCase = true)) return true
            return false
        }

        private fun getOnlineString(): String {
            val str = runCatching { UnobfuscatorCache.getInstance().getString("online") }.getOrNull()
            if (!str.isNullOrBlank()) return str
            val id1 = Utils.getID("conversation_contact_online", "string")
            if (id1 > 0) return runCatching { Utils.application.getString(id1) }.getOrDefault("online")
            val id2 = Utils.getID("online", "string")
            if (id2 > 0) return runCatching { Utils.application.getString(id2) }.getOrDefault("online")
            return "online"
        }

        private fun setStatus(status: String?, csDot: ImageView?, lastSeenText: TextView?) {
            val isOnline = isOnlineStatus(status)
            if (csDot != null) {
                csDot.visibility = if (isOnline) View.VISIBLE else View.INVISIBLE
            }

            if (lastSeenText != null) {
                if (!status.isNullOrBlank()) {
                    lastSeenText.text = status
                    if (isOnline) {
                        lastSeenText.setTextColor(Color.GREEN)
                    } else {
                        lastSeenText.setTextColor(0xffcac100.toInt())
                    }
                } else {
                    lastSeenText.text = ""
                    lastSeenText.setTextColor(Color.GRAY)
                }
            }
        }
    }
}
