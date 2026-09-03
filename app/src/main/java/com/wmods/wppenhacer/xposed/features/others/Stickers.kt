package com.wmods.wppenhacer.xposed.features.others

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.setTouchClickAndLongClickListener
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class Stickers(classLoader: ClassLoader, preferences:SharedPreferences) :
    Feature(classLoader, preferences) {

    private val stickerContainerId by lazy { Utils.getID("stickerContainer", "id") }
    private val stickerId by lazy { Utils.getID("sticker", "id") }

    override fun doHook() {

        if (!prefs.getBoolean("alertsticker", false)) return
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val targetId = stickerContainerId
                    if (targetId == 0 || view.id != targetId) return
                    if (view.tag == "wae_hooked") return
                    view.tag = "wae_hooked"
                    view.setTouchClickAndLongClickListener(
                        onClick = {
                            showAlertDialog(view)
                        },
                        onLongClick = {
                            view.performLongClick()
                        }
                    )
                }
            })
        if (prefs.getBoolean("remove_sticker_white_outline", false)) {
            val stickerColoredOutline = Unobfuscator.loadStickerColoredOutline(classLoader)
            XposedBridge.hookMethod(stickerColoredOutline, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val source = param.args.getOrNull(0) as? Bitmap ?: return
                    val safeConfig = source.config ?: Bitmap.Config.ARGB_8888
                    param.result = source.copy(safeConfig, true)
                }
            })
        }
    }


    private fun showAlertDialog(view: View) {
        val context = view.context
        val sId = stickerId
        val stickerView = if (sId != 0) view.findViewById<ImageView?>(sId) else null
        if (stickerView == null) return

        val dialog = AlertDialogWpp(context)
        dialog.setTitle(context.getString(R.string.send_sticker))
        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.VERTICAL
        linearLayout.gravity = Gravity.CENTER_HORIZONTAL
        val padding = Utils.dipToPixels(16)
        linearLayout.setPadding(padding, padding, padding, padding)
        val image = ImageView(context)
        val size = Utils.dipToPixels(72)
        val params = LinearLayout.LayoutParams(size, size)
        params.bottomMargin = padding
        image.layoutParams = params
        image.setImageDrawable(stickerView.drawable)
        linearLayout.addView(image)

        val text = TextView(context)
        text.text = context.getString(R.string.do_you_want_to_send_sticker)
        text.textAlignment = View.TEXT_ALIGNMENT_CENTER
        linearLayout.addView(text)


        dialog.setView(linearLayout)
        dialog.setPositiveButton(
            context.getString(R.string.send)
        ) { _, _ -> view.performClick() }
        dialog.setNegativeButton(
            context.getString(R.string.cancel),
            null
        )
        dialog.show()
    }


    override fun getPluginName(): String {
        return "Stickers"
    }
}
