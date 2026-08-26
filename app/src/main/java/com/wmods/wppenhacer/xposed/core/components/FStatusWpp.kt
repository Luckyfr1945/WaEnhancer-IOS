package com.wmods.wppenhacer.xposed.core.components

import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class FStatusWpp(val fstatus: Any?) {

    companion object {

        private lateinit var classFMediaStatus: Class<*>
        private lateinit var methodGetStatusByKey: Method

        lateinit var TYPE: Class<*>
        private lateinit var fieldFStatusKey: Field

        private var mStatusStore: Any? = null

        @JvmStatic
        fun initialize(classLoader: ClassLoader) {
            FStatusKey.initialize(classLoader)
            TYPE = Unobfuscator.loadFStatusClass(classLoader)
            val fStatusKeyClass = Unobfuscator.loadFStatusKeyClass(classLoader)
            fieldFStatusKey = ReflectionUtils.getFieldByType(TYPE, fStatusKeyClass)!!
            methodGetStatusByKey = Unobfuscator.loadGetStatusByKey(classLoader)
            XposedBridge.hookAllConstructors(
                methodGetStatusByKey.declaringClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        mStatusStore = param.thisObject
                    }
                })
            classFMediaStatus = Unobfuscator.loadFMediaStatusClass(classLoader)
        }

        @JvmStatic
        fun getFStatusFromFKeyStatus(fStatusKey: FStatusKey): FStatusWpp? {
            try {
                if (mStatusStore == null) {
                    mStatusStore = methodGetStatusByKey.declaringClass.declaredConstructors.first()
                        .newInstance()
                }
                return FStatusWpp(methodGetStatusByKey.invoke(mStatusStore, fStatusKey.thisObject))
            } catch (e: Exception) {
                XposedBridge.log(e)
            }
            return null
        }

    }


    init {
        if (fstatus == null) throw RuntimeException("Object FStatus is null")
        if (!TYPE.isInstance(fstatus))
            throw RuntimeException("Object is not a FStatus Instance")
    }

    val isMediaFile by lazy {
        classFMediaStatus.isInstance(fstatus)
    }


    val fStatusKey by lazy {
        FStatusKey(fieldFStatusKey.get(fstatus))
    }



    val fMessage: FMessageWpp? by lazy {
        try {
            FMessageWpp(WppCore.getFMessageFromFStatus(fstatus))
        } catch (e: Exception) {
            XposedBridge.log(e)
            null
        }
    }

    fun getMediaFile(): File? {
        if (!isMediaFile) return null
        try {
            // 1. Coba field A00 (jika ada)
            val directItem = try {
                val f = classFMediaStatus.declaredFields.firstOrNull { it.name == "A00" } ?: classFMediaStatus.fields.firstOrNull { it.name == "A00" }
                f?.apply { isAccessible = true }?.get(fstatus)
            } catch (_: Throwable) {
                null
            }
            if (directItem != null) {
                val fileMethod = directItem.javaClass.declaredMethods.firstOrNull { it.returnType == File::class.java }
                if (fileMethod != null) {
                    fileMethod.isAccessible = true
                    val f = fileMethod.invoke(directItem) as? File
                    if (f != null && f.exists()) return f
                }
            }

            // 2. Scan semua declared fields di classFMediaStatus
            for (field in classFMediaStatus.declaredFields) {
                if (field.type.isPrimitive) continue
                field.isAccessible = true
                val obj = field.get(fstatus) ?: continue
                if (obj is File && obj.exists()) return obj

                val m = obj.javaClass.declaredMethods.firstOrNull { it.parameterCount == 0 && it.returnType == File::class.java }
                if (m != null) {
                    m.isAccessible = true
                    val f = m.invoke(obj) as? File
                    if (f != null && f.exists()) return f
                }
            }

            // 3. Cek fMessage mediaFile fallback
            val msgFile = fMessage?.mediaFile
            if (msgFile != null && msgFile.exists()) return msgFile
        } catch (e: Throwable) {
            XposedBridge.log("FStatusWpp.getMediaFile error: ${e.message}")
        }
        return null
    }

    override fun toString(): String {
        return "FStatusWpp(fstatus=$fstatus, isMedia=$isMediaFile, fStatusKey=$fStatusKey)"
    }

    class FStatusKey {

        companion object {
            /**
             * The class type of the key object.
             */
            lateinit var TYPE: Class<*>

            @JvmStatic
            fun initialize(classLoader: ClassLoader) {
                TYPE = Unobfuscator.loadFStatusKeyClass(classLoader)
            }

        }

        @JvmField
        var senderJid: FMessageWpp.UserJid = FMessageWpp.UserJid()

        /**
         * The underlying key object from WhatsApp's code.
         */
        @JvmField
        var thisObject: Any? = null

        /**
         * The unique identifier for the message.
         */
        @JvmField
        var messageID: String = ""

        /**
         * A boolean indicating if the message was sent by the current user.
         */
        @JvmField
        var isFromMe: Boolean = false

        /**
         * The JID of whatsapp
         */
        @JvmField
        var remoteJid: FMessageWpp.UserJid = FMessageWpp.UserJid()


        @JvmField
        var fStatus: FStatusWpp? = null


        val key: FMessageWpp.Key by lazy {
            try {
                ReflectionUtils.findFieldUsingFilter(TYPE) {
                    FMessageWpp.Key.TYPE.isAssignableFrom(it.type)
                }.let {
                    FMessageWpp.Key(it.get(thisObject))
                }
            } catch (e: Exception) {
                XposedBridge.log(e)
                FMessageWpp.Key(null)
            }
        }

        constructor(key: Any?) {
            if (key == null) return
            this.thisObject = key
            try {
                this.senderJid = FMessageWpp.UserJid(XposedHelpers.getObjectField(key, "A01"))
            } catch (_: Throwable) {
                val jidObj = ReflectionUtils.findFieldUsingFilterIfExists(key.javaClass) { f ->
                    !f.type.isPrimitive && f.name != "A00"
                }?.get(key)
                this.senderJid = FMessageWpp.UserJid(jidObj)
            }

            try {
                this.messageID = (XposedHelpers.getObjectField(key, "A02") as? String) ?: ""
            } catch (_: Throwable) {
                this.messageID = (ReflectionUtils.findFieldUsingFilterIfExists(key.javaClass) { f ->
                    f.type == String::class.java
                }?.get(key) as? String) ?: ""
            }

            try {
                this.isFromMe = XposedHelpers.getBooleanField(key, "A03")
            } catch (_: Throwable) {
                this.isFromMe = ReflectionUtils.findFieldUsingFilterIfExists(key.javaClass) { f ->
                    f.type == java.lang.Boolean.TYPE || f.type == java.lang.Boolean::class.java
                }?.getBoolean(key) ?: false
            }

            try {
                this.remoteJid = FMessageWpp.UserJid(XposedHelpers.getObjectField(key, "A00"))
            } catch (_: Throwable) {
                this.remoteJid = this.senderJid
            }
        }

        override fun toString(): String {
            return "FStatusKey{" +
                    "thisObject=" + thisObject +
                    ", messageID='" + messageID + '\'' +
                    ", isFromMe=" + isFromMe +
                    ", remoteJid=" + remoteJid +
                    ", senderJid=" + senderJid +
                    '}'
        }
    }

}