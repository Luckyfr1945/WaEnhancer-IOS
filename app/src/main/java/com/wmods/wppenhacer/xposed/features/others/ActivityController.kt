package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wmods.wppenhacer.model.ContactPickerResult
import com.wmods.wppenhacer.preference.ContactPickerPreference
import com.wmods.wppenhacer.utils.WhatsAppContactPickerLauncher
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore.ActivityChangeState
import com.wmods.wppenhacer.xposed.core.WppCore.addListenerActivity
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.findFirstClassUsingName
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator.loadLockedAuthCheckMethod
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.atomic.AtomicBoolean

class ActivityController(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {
    private val disableAuth = AtomicBoolean(false)

    override fun doHook() {
        val clazz = try {
            findFirstClassUsingName(classLoader, StringMatchType.EndsWith, ".SettingsNotifications")
        } catch (e: Throwable) {
            logDebug("ActivityController: SettingsNotifications class not found: ${e.message}")
            null
        } ?: return

        val authCheckMethod = try {
            loadLockedAuthCheckMethod(classLoader)
        } catch (e: Throwable) {
            logDebug("ActivityController: loadLockedAuthCheckMethod failed: ${e.message}")
            null
        }

        if (authCheckMethod != null) {
            XposedBridge.hookMethod(authCheckMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (disableAuth.get()) param.setResult(false)
                }
            })
        }

        addListenerActivity { activity, type ->
            if (clazz.isAssignableFrom(activity.javaClass) && type == ActivityChangeState.ChangeType.ENDED) {
                disableAuth.set(false)
            }
        }

        XposedHelpers.findAndHookMethod(
            clazz,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val intent = activity.intent
                    if (intent != null && intent.getBooleanExtra("contact_mode", false)) {
                        try {
                            disableAuth.set(true)
                            contactController(intent, activity)
                        } catch (e: Throwable) {
                            disableAuth.set(false)
                            logDebug("ActivityController: contact picker launch failed: ${e.message}")
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            clazz,
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = param.args[0] as Int
                    if (id != ContactPickerPreference.REQUEST_CONTACT_PICKER) return

                    disableAuth.set(false)
                    val activity = param.thisObject as? Activity ?: return
                    val intent = param.args[2] as? Intent
                    if (intent != null) {
                        processResultContact(intent, activity)
                    }
                    activity.finish()
                }
            })
    }

    override fun getPluginName(): String {
        return "Activity Controller"
    }

    companion object {
        private fun processResultContact(intent: Intent, activity: Activity) {
            val key = XposedHelpers.getAdditionalInstanceField(activity, "contact_picker_key") as? String
            if (!intent.hasExtra("key") && key != null) {
                intent.putExtra("key", key)
            }
            XposedHelpers.removeAdditionalInstanceField(activity, "contact_picker_key")
            if (!intent.hasExtra("contacts")) {
                intent.putStringArrayListExtra("contacts", ArrayList<String?>())
            }
            if (!intent.hasExtra("picker_contacts")) {
                intent.putExtra("picker_contacts", ArrayList<ContactPickerResult?>())
            }
            activity.setResult(Activity.RESULT_OK, intent)
        }

        @Throws(Exception::class)
        private fun contactController(intent: Intent, activity: Activity) {
            val key = intent.getStringExtra("key") ?: ""
            XposedHelpers.setAdditionalInstanceField(activity, "contact_picker_key", key)
            val contacts = intent.getStringArrayListExtra("contacts")
            val pickerIntent = WhatsAppContactPickerLauncher.createAboutPickerIntent(
                activity,
                activity.packageName,
                key,
                contacts
            )
            activity.startActivityForResult(
                pickerIntent,
                ContactPickerPreference.REQUEST_CONTACT_PICKER
            )
        }
    }
}