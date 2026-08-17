package com.wmods.wppenhacer.xposed.features.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.utils.AudioOpusConverter
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileOutputStream

class VoiceStatusPicker(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    companion object {
        private const val TAG_AUDIO_BTN = 0x7E99A01
        private const val TAG_SCROLL_WRAPPER = 0x7E99A02
        private const val REQUEST_CODE_PICK_AUDIO = 0x7E99
    }

    override fun getPluginName(): String = "Voice Status Picker"

    override fun doHook() {
        if (!prefs.getBoolean("send_audio_as_voice_status", true)) return

        WppCore.addListenerActivity { activity, type ->
            if (type == WppCore.ActivityChangeState.ChangeType.RESUMED || type == WppCore.ActivityChangeState.ChangeType.CREATED) {
                val actName = activity.javaClass.simpleName
                if (actName.contains("Camera", true) || actName.contains("Composer", true) ||
                    actName.contains("Status", true) || actName.contains("Media", true) ||
                    actName.contains("Home", true)
                ) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryInjectAudioButton(activity)
                    }, 100)
                }
            }
        }

        // Hook onActivityResult on Activity to intercept picked audio file
        XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val requestCode = param.args[0] as? Int ?: return
                val resultCode = param.args[1] as? Int ?: return
                val data = param.args[2] as? Intent ?: return

                if (requestCode == REQUEST_CODE_PICK_AUDIO) {
                    if (resultCode == Activity.RESULT_OK) {
                        val audioUri = data.data ?: return
                        val activity = param.thisObject as? Activity ?: return
                        processAndSendAudioToStatus(activity, audioUri)
                    }
                    param.result = null
                }
            }
        })
    }

    private fun tryInjectAudioButton(activity: Activity) {
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        if (decorView.findViewById<View>(TAG_AUDIO_BTN) != null) return

        val container = findStatusActionContainer(decorView) ?: return
        if (container.findViewById<View>(TAG_AUDIO_BTN) != null) return

        // 1. Create Audio button with fixed width matching other buttons (64dp)
        val audioBtn = createAudioActionButton(activity) {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                activity.startActivityForResult(Intent.createChooser(intent, "Pilih File Audio"), REQUEST_CODE_PICK_AUDIO)
            } catch (e: Throwable) {
                Utils.showToast("Gagal membuka pemutar audio: ${e.message}", Toast.LENGTH_SHORT)
            }
        }
        audioBtn.id = TAG_AUDIO_BTN

        // 2. Insert Audio button right after Musik button or at position 2
        val musicIdx = (0 until container.childCount).indexOfFirst { idx ->
            findTextInView(container.getChildAt(idx)).contains("Musik", true) ||
            findTextInView(container.getChildAt(idx)).contains("Music", true)
        }
        val insertPos = if (musicIdx >= 0) musicIdx + 1 else 2.coerceAtMost(container.childCount)
        container.addView(audioBtn, insertPos)

        // 3. Fix UI Collision: Wrap container in HorizontalScrollView if not already scrollable
        makeContainerScrollable(container)
    }

    private fun makeContainerScrollable(container: LinearLayout) {
        val parent = container.parent as? ViewGroup ?: return
        if (parent is HorizontalScrollView || parent.id == TAG_SCROLL_WRAPPER) return

        try {
            val parentParams = container.layoutParams
            val indexInParent = parent.indexOfChild(container)
            parent.removeView(container)

            val scrollView = HorizontalScrollView(container.context).apply {
                id = TAG_SCROLL_WRAPPER
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = parentParams
            }
            container.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            scrollView.addView(container)
            parent.addView(scrollView, indexInParent)
        } catch (_: Throwable) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val lp = child.layoutParams as? LinearLayout.LayoutParams
                if (lp != null) {
                    lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                    lp.weight = 0f
                    lp.setMargins(6, 0, 6, 0)
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun findStatusActionContainer(root: ViewGroup): LinearLayout? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child.orientation == LinearLayout.HORIZONTAL && child.childCount >= 2) {
                val hasMatch = (0 until child.childCount).any { idx ->
                    val text = findTextInView(child.getChildAt(idx))
                    text.contains("Suara", true) || text.contains("Voice", true) ||
                    text.contains("Musik", true) || text.contains("Music", true) ||
                    text.contains("Tata letak", true) || text.contains("Layout", true) ||
                    text.contains("Teks", true) || text.contains("Text", true)
                }
                if (hasMatch) return child
            } else if (child is ViewGroup) {
                val found = findStatusActionContainer(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findTextInView(v: View?): String {
        if (v is TextView) return v.text?.toString() ?: ""
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val res = findTextInView(v.getChildAt(i))
                if (res.isNotEmpty()) return res
            }
        }
        return ""
    }

    private fun createAudioActionButton(context: Context, onClick: () -> Unit): LinearLayout {
        val density = context.resources.displayMetrics.density
        val dpToPx = { dp: Float -> (dp * density).toInt() }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(64f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            }
            setOnClickListener { onClick() }
        }

        val circleView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44f), dpToPx(44f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }

        val iconText = TextView(context).apply {
            text = "🎵"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        circleView.addView(iconText)

        val labelText = TextView(context).apply {
            text = "Audio"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(3f), 0, 0)
        }

        rootLayout.addView(circleView)
        rootLayout.addView(labelText)
        return rootLayout
    }

    private fun processAndSendAudioToStatus(activity: Activity, audioUri: Uri) {
        Utils.showToast("Memproses audio ke status...", Toast.LENGTH_SHORT)

        // Read stream on main thread immediately
        var tempFile: File? = null
        try {
            val file = File(activity.cacheDir, "status_audio_${System.currentTimeMillis()}.mp3")
            activity.contentResolver.openInputStream(audioUri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (file.exists() && file.length() > 0) {
                tempFile = file
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        val fileToProcess = tempFile

        Thread {
            try {
                var finalFile: File? = null
                if (fileToProcess != null && fileToProcess.exists()) {
                    val converted = try { AudioOpusConverter.convert(fileToProcess.absolutePath) } catch (_: Throwable) { null }
                    finalFile = if (converted != null && converted.exists() && converted.length() > 0) converted else fileToProcess
                }

                Handler(Looper.getMainLooper()).post {
                    val streamUri = if (finalFile != null && finalFile.exists()) Uri.fromFile(finalFile) else audioUri

                    // Target ContactPicker / Status Sender in WhatsApp
                    var launched = false
                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            setClassName(activity.packageName, "com.whatsapp.contact.picker.ContactPicker")
                            type = "audio/*"
                            putExtra(Intent.EXTRA_STREAM, streamUri)
                            putExtra("jids", arrayListOf("status@broadcast"))
                            putExtra("jid", "status@broadcast")
                            putExtra("recipient_jids", arrayListOf("status@broadcast"))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                        launched = true
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }

                    if (!launched) {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                setPackage(activity.packageName)
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, streamUri)
                                putExtra("jids", arrayListOf("status@broadcast"))
                                putExtra("jid", "status@broadcast")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            activity.startActivity(shareIntent)
                        } catch (err: Throwable) {
                            Utils.showToast("Gagal membuka status: ${err.message}", Toast.LENGTH_LONG)
                        }
                    }
                }

            } catch (e: Throwable) {
                XposedBridge.log(e)
                Handler(Looper.getMainLooper()).post {
                    Utils.showToast("Error: ${e.message}", Toast.LENGTH_LONG)
                }
            }
        }.start()
    }
}
