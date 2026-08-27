package com.wmods.wppenhacer.xposed.features.privacy

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadChatCacheClass
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadLoadedContactsMethod
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadLockedChatsMethod
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadNotificationMethod
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class LockedChatsEnhancer(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {
    @Volatile
    private var chatCache: Any? = null

    override fun doHook() {
        if (!prefs.getBoolean("lockedchats_enhancer", false)) return

        try {
            val jidNotifications = loadNotificationMethod(classLoader)
            val lockedChatsMethod = loadLockedChatsMethod(classLoader)

            if (jidNotifications != null) {
                XposedBridge.hookMethod(jidNotifications, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Ensure notifications for locked chats are stripped/hidden
                        val result = param.result
                        if (result is MutableList<*>) {
                            val currentCache = chatCache
                            if (currentCache != null && lockedChatsMethod != null) {
                                try {
                                    val lockedChats = lockedChatsMethod.invoke(currentCache) as? Collection<*>
                                    if (lockedChats != null && lockedChats.isNotEmpty()) {
                                        val lockedSet = lockedChats.mapNotNull {
                                            try { UserJid(it).phoneNumber ?: UserJid(it).userRawString } catch (_: Throwable) { null }
                                        }.toSet()

                                        if (lockedSet.isNotEmpty()) {
                                            result.removeIf { item ->
                                                if (item == null) return@removeIf false
                                                try {
                                                    val jidObj = UserJid.extractFrom(item)
                                                    val phone = jidObj?.phoneNumber ?: jidObj?.userRawString
                                                    phone != null && phone in lockedSet
                                                } catch (_: Throwable) {
                                                    false
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    logDebug("LockedChatsEnhancer: filter notifications failed: ${e.message}")
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            logDebug("LockedChatsEnhancer: jidNotifications hook failed: ${e.message}")
        }

        try {
            val chatCacheClass = loadChatCacheClass(classLoader) ?: return
            val lockedChatsFields = ReflectionUtils.findAllFieldsUsingFilter(chatCacheClass) { f ->
                HashSet::class.java.isAssignableFrom(f.type)
            }

            XposedBridge.hookAllConstructors(chatCacheClass, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    chatCache = param.thisObject
                }
            })

            val loadedContacts = loadLoadedContactsMethod(classLoader)
            if (loadedContacts != null) {
                XposedBridge.hookMethod(loadedContacts, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val currentCache = chatCache ?: return
                        val arg0 = param.args.getOrNull(0) ?: return

                        @Suppress("UNCHECKED_CAST")
                        val candidateList = try {
                            val field = ReflectionUtils.getFieldByType(arg0.javaClass, List::class.java)
                            field?.get(arg0) as? MutableList<Any?>
                        } catch (_: Throwable) {
                            null
                        }

                        @Suppress("UNCHECKED_CAST")
                        val list = if (candidateList != null && candidateList.any { it != null && WaContactWpp.TYPE.isInstance(it) }) {
                            candidateList
                        } else {
                            try {
                                XposedHelpers.getObjectField(arg0, "A01") as? MutableList<Any?>
                            } catch (_: Throwable) {
                                null
                            }
                        } ?: return

                        val lockedChats = try {
                            lockedChatsFields.firstNotNullOfOrNull { field ->
                                val set = field.get(currentCache) as? HashSet<*>
                                if (set != null && set.isNotEmpty() && set.any { 
                                    it != null && try { UserJid(it).phoneNumber != null } catch (_: Throwable) { false }
                                }) {
                                    set
                                } else null
                            }
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        val lockedNumbers = lockedChats.asSequence()
                            .filterNotNull()
                            .mapNotNull { userjid ->
                                try {
                                    val uj = UserJid(userjid)
                                    uj.phoneNumber ?: uj.phoneRawString ?: uj.userRawString
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                            .toSet()

                        if (lockedNumbers.isEmpty()) return

                        list.removeIf { item ->
                            if (!WaContactWpp.TYPE.isInstance(item)) return@removeIf false
                            try {
                                val waContact = WaContactWpp(item)
                                val jid = waContact.userJid
                                val phone = jid.phoneNumber ?: jid.phoneRawString ?: jid.userRawString
                                phone != null && phone in lockedNumbers
                            } catch (_: Throwable) {
                                false
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            logDebug("LockedChatsEnhancer: chatCache / loadedContacts hook failed: ${e.message}")
        }
    }

    override fun getPluginName(): String {
        return "Locked Chats Enhancer"
    }
}
