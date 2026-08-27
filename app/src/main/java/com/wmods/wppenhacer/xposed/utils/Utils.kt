package com.wmods.wppenhacer.xposed.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.core.WppCore.getClientBridge
import com.wmods.wppenhacer.xposed.core.WppCore.getContactName
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp.UserJid
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

object Utils {
    lateinit var xprefs: SharedPreferences
    private val ids = HashMap<String, Int>()
    lateinit var appClassLoader: ClassLoader

    fun init() {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val channel =
            NotificationChannel("wppenhacer", "WAE Enhancer", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
    }


    @JvmStatic
    val application: Application
        get() = FeatureLoader.mApp ?: App.instance!!

    fun getString(id: Int): String {
        return application.getString(id)
    }


    val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(minOf(4, Runtime.getRuntime().availableProcessors()))
    }

    @JvmStatic
    fun isBlueOnReplyEnabled(prefs: SharedPreferences? = null): Boolean {
        if (prefs?.getBoolean("blueonreply", false) == true) return true
        try {
            val ctx = application
            val embeddedPrefs = ctx.getSharedPreferences("wae_embedded_prefs", Context.MODE_PRIVATE)
            return embeddedPrefs.getBoolean("blueonreply", false)
        } catch (_: Throwable) {}
        return false
    }

    @JvmStatic
    fun doRestart(context: Context): Boolean {
        val packageManager = context.packageManager
        val intent =
            packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val componentName = intent.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        mainIntent.setPackage(context.packageName)
        context.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
        return true
    }

    /**
     * Retrieves the resource ID by name and type.
     * Uses caching to improve performance for repeated lookups.
     * 
     * @param name The resource name to look up
     * @param type The resource type (e.g., "id", "drawable", "layout", "string")
     * @return The resource ID or -1 if not found or an error occurred
     */
    @JvmStatic
    @SuppressLint("DiscouragedApi")
    fun getID(name: String?, type: String?): Int {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type)) {
            return -1
        }

        val key = "${type}_${name}"

        synchronized(ids) {
            val cachedId = ids[key]
            if (cachedId != null) return cachedId
        }

        try {
            val app: Application = application
            val context = app.applicationContext
            val id = context.resources.getIdentifier(name, type, app.packageName)

            synchronized(ids) {
                ids[key] = id
            }

            return id
        } catch (e: Exception) {
            XposedBridge.log("Error getting resource ID: type=$type, name=$name, error: ${e.message}")
            return -1
        }
    }

    @JvmStatic
    fun dipToPixels(dipValue: Int): Int {
        val metrics = application.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue.toFloat(), metrics)
            .toInt()
    }


    @JvmStatic
    fun dipToPixels(dipValue: Float): Int {
        val metrics = application.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue, metrics).toInt()
    }

    @JvmStatic
    fun getDateTimeFromMillis(timestamp: Long): String {
        return SimpleDateFormat(
            "dd/MM/yyyy hh:mm:ss a",
            Locale.getDefault()
        ).format(Date(timestamp))
    }

    @SuppressLint("SdCardPath")
    fun getDestination(name: String): String {
        val folder = xprefs.getString("download_local", "/sdcard/Download")
        val waFolder = File(folder, "WhatsApp")
        val filePath = File(waFolder, name)
        try {
            getClientBridge()!!.createDir(filePath.absolutePath)
        } catch (_: Exception) {
        }
        return filePath.absolutePath + "/"
    }

    fun copyFile(srcFile: File?, destFolder: String, name: String): String? {
        if (srcFile == null || !srcFile.exists()) return "File not found or is null"
        try {
            return copyFile(FileInputStream(srcFile), destFolder, name)
        } catch (e: Exception) {
            XposedBridge.log(e)
            return e.message
        }
    }


    fun copyFile(inputStream: InputStream, destFolder: String, name: String): String? {
        val destDir = File(destFolder)
        if (!destDir.exists()) {
            runCatching { destDir.mkdirs() }
        }
        val destFile = File(destFolder, name)

        // 1. Coba via Bridge AIDL terlebih dahulu
        try {
            val bridge = runCatching { getClientBridge() }.getOrNull()
            if (bridge != null) {
                val pfd = bridge.openFile(destFile.absolutePath, true)
                if (pfd != null) {
                    inputStream.use { `in` ->
                        pfd.use { parcelFileDescriptor ->
                            FileOutputStream(parcelFileDescriptor.fileDescriptor).use { out ->
                                `in`.copyTo(out)
                            }
                        }
                    }
                    scanFile(destFile)
                    return ""
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Utils.copyFile bridge error, trying direct write: ${e.message}")
        }

        // 2. Fallback direct copy
        try {
            inputStream.use { `in` ->
                FileOutputStream(destFile).use { out ->
                    `in`.copyTo(out)
                }
            }
            scanFile(destFile)
            return ""
        } catch (e: Throwable) {
            XposedBridge.log("Utils.copyFile direct error: ${e.message}")
            return e.message
        }
    }

    @JvmStatic
    @JvmOverloads
    fun showToast(message: String?, length: Int = 0) {
        if (message == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(application, message, length).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    application,
                    message,
                    length
                ).show()
            }
        }
    }

    fun setToClipboard(string: String?) {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", string)
        clipboard.setPrimaryClip(clip)
    }

    fun generateName(userJid: UserJid, fileFormat: String?): String {
        val contactName = getContactName(userJid)
        val number = userJid.phoneRawString ?: userJid.phoneNumber ?: userJid.userRawString ?: "unknown"
        return toValidFileName(contactName) + "_" + number + "_" + SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.getDefault()
        ).format(
            Date()
        ) + "." + (fileFormat ?: "dat")
    }


    fun toValidFileName(input: String): String {
        return input
            .replace("[:\\\\/*\"?|<>']".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun scanFile(file: File) {
        MediaScannerConnection.scanFile(
            application,
            arrayOf<String>(file.absolutePath),
            arrayOf<String?>(MimeTypeUtils.getMimeTypeFromExtension(file.absolutePath))
        ) { _: String?, _: Uri? -> }
    }

    fun getProperties(prefs: SharedPreferences, key: String?, checkKey: String?): Properties {
        val properties = Properties()
        if (checkKey != null && !prefs.getBoolean(checkKey, false)) return properties
        val text = prefs.getString(key, "") ?: return properties
        val pattern = Pattern.compile("^/\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL)
        val matcher = pattern.matcher(text)

        if (matcher.find()) {
            val propertiesText = matcher.group(1) ?: return properties
            val lines = propertiesText.split("\\s*\\n\\s*".toRegex())

            for (line in lines) {
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val skey = line.substring(0, separator).trim()
                val value = line.substring(separator + 1)
                    .trim()
                    .replace("^\"|\"$".toRegex(), "") // Remove quotes, if any
                properties[skey] = value
            }
        }

        return properties
    }

    fun tryParseInt(wallpaperAlpha: String?, i: Int): Int {
        return try {
            wallpaperAlpha?.trim { it <= ' ' }?.toInt() ?: i
        } catch (_: Exception) {
            i
        }
    }

    fun getMyNumber(): String {
        val app = FeatureLoader.mApp ?: application
        return app.getSharedPreferences(
            "${app.packageName}_preferences_light",
            Context.MODE_PRIVATE
        ).getString("ph", "") ?: ""
    }


    @JvmStatic
    fun <T> binderLocalScope(block: BinderLocalScopeBlock<T?>): T? {
        val identity = Binder.clearCallingIdentity()
        try {
            return block.execute()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @JvmStatic
    fun getAuthorFromCss(code: String?): String? {
        if (code == null) return null
        val match = Pattern.compile("author\\s*=\\s*(.*?)\n").matcher(code)
        if (!match.find()) return null
        return match.group(1)
    }

    @SuppressLint("MissingPermission")
    fun showNotification(title: String?, content: String?) {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, "wppenhacer")
            .setSmallIcon(android.R.mipmap.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
        notificationManager.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), notification.build())
    }

    @SuppressLint("MissingPermission")
    fun showProgressNotification(notifId: Int, title: String, content: String) {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, "wppenhacer")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notificationManager.notify(notifId, notification.build())
    }

    @SuppressLint("MissingPermission")
    fun updateNotificationSuccess(notifId: Int, title: String, content: String) {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, "wppenhacer")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notificationManager.notify(notifId, notification.build())
    }

    @SuppressLint("MissingPermission")
    fun updateNotificationError(notifId: Int, title: String, content: String) {
        val context: Application = application
        val notificationManager = NotificationManagerCompat.from(context)
        val notification = NotificationCompat.Builder(context, "wppenhacer")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notificationManager.notify(notifId, notification.build())
    }

    @JvmStatic
    fun openLink(mActivity: Activity, url: String?) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        mActivity.startActivity(browserIntent)
    }


    fun interface BinderLocalScopeBlock<T> {
        fun execute(): T?
    }
}
