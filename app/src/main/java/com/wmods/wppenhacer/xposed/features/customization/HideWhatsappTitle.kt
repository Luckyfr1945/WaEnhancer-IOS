package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.wmods.wppenhacer.xposed.core.Feature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class HideWhatsappTitle(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun getPluginName() = "Hide WhatsApp Title"

    override fun doHook() {
        if (!prefs.getBoolean("hide_whatsapp_title", false)) return

        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name.contains("HomeActivity", ignoreCase = true)) {
                        hideTitleInView(activity.window.decorView)
                    }
                }
            })
        } catch (e: Exception) {}
    }

    private fun hideTitleInView(view: View) {
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            if (text.equals("WhatsApp", ignoreCase = true)) {
                view.visibility = View.GONE
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hideTitleInView(view.getChildAt(i))
            }
        }
    }
}
