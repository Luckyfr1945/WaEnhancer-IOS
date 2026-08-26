package com.wmods.wppenhacer.xposed.features.media

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.MenuStatusListener
import com.wmods.wppenhacer.xposed.utils.MimeTypeUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File

class StatusDownload(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun doHook() {
        if (!prefs.getBoolean("downloadstatus", false)) return

        val downloadStatus = object : MenuStatusListener.OnMenuItemStatusListener() {
            override fun addMenu(menu: Menu, statusData: MenuStatusListener.StatusData): MenuItem? {
                if (menu.findItem(R.string.download) != null) return null
                val item = statusData.currentItem
                if (item.isFromMe) return null
                if (!item.isMediaFile) return null
                // Tampilkan menu "Unduh" langsung tanpa harus menunggu video selesai diputar
                return menu.add(0, R.string.download, 0, R.string.download)
            }

            override fun onClick(item: MenuItem, statusData: MenuStatusListener.StatusData) {
                downloadFile(statusData.currentItem)
            }
        }
        MenuStatusListener.menuStatuses.add(downloadStatus)

        val sharedMenu = object : MenuStatusListener.OnMenuItemStatusListener() {
            override fun addMenu(menu: Menu, statusData: MenuStatusListener.StatusData): MenuItem? {
                val item = statusData.currentItem
                if (item.isFromMe) return null
                if (menu.findItem(R.string.share_as_status) != null) return null
                return menu.add(0, R.string.share_as_status, 0, R.string.share_as_status)
            }

            override fun onClick(item: MenuItem, statusData: MenuStatusListener.StatusData) {
                sharedStatus(statusData.currentItem)
            }
        }
        MenuStatusListener.menuStatuses.add(sharedMenu)
    }

    private fun sharedStatus(statusItem: StatusItemWpp) {
        Utils.executor.execute {
            try {
                val fMessage = statusItem.fMessage
                if (!statusItem.isMediaFile) {
                    val intent = Intent()
                    var clazz: Class<*>
                    try {
                        clazz = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "TextStatusComposerActivity")
                    } catch (ignored: Exception) {
                        clazz = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "ConsolidatedStatusComposerActivity")
                        intent.putExtra("status_composer_mode", 2)
                    }
                    intent.setClassName(Utils.application.packageName, clazz.name)
                    intent.putExtra("android.intent.extra.TEXT", fMessage?.messageStr)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Handler(Looper.getMainLooper()).post {
                        (WppCore.getCurrentActivity() ?: Utils.application).startActivity(intent)
                    }
                    return@execute
                }

                // 1. Cek ketersediaan file media
                var file = statusItem.getMediaFile() ?: statusItem.fMessage?.mediaFile
                val isReady = file != null && file.exists() && file.length() > 0L

                if (!isReady) {
                    Utils.showToast("⏳ Menyiapkan media status...", Toast.LENGTH_SHORT)
                }

                // 2. Polling di background jika file sedang di-buffer / diunduh
                val startTime = System.currentTimeMillis()
                var lastSize = -1L
                var stableCount = 0

                while (System.currentTimeMillis() - startTime < 35_000) {
                    file = statusItem.getMediaFile() ?: statusItem.fMessage?.mediaFile
                    if (file == null || !file.exists() || file.length() == 0L) {
                        file = findRecentStatusFileInStatusesFolder(statusItem)
                    }

                    if (file != null && file.exists() && file.length() > 0L) {
                        val currentSize = file.length()
                        if (currentSize == lastSize) {
                            stableCount++
                            if (stableCount >= 3) {
                                break
                            }
                        } else {
                            lastSize = currentSize
                            stableCount = 0
                        }
                    }
                    try {
                        Thread.sleep(400)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                if (file == null || !file.exists() || file.length() == 0L) {
                    Utils.showToast(Utils.getString(R.string.download_not_available), Toast.LENGTH_SHORT)
                    return@execute
                }

                val intent = Intent()
                val clazz = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "MediaComposerActivity")
                intent.setClassName(Utils.application.packageName, clazz.name)
                intent.putExtra("jids", arrayListOf("status@broadcast"))
                intent.putExtra("android.intent.extra.STREAM", arrayListOf(Uri.fromFile(file)))
                intent.putExtra("android.intent.extra.TEXT", fMessage?.messageStr)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

                Handler(Looper.getMainLooper()).post {
                    try {
                        (WppCore.getCurrentActivity() ?: Utils.application).startActivity(intent)
                    } catch (e: Throwable) {
                        Utils.showToast(e.message, Toast.LENGTH_SHORT)
                    }
                }

            } catch (e: Throwable) {
                Utils.showToast(e.message, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun downloadFile(statusItem: StatusItemWpp) {
        Utils.executor.execute {
            val notifId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            try {
                logDebug("StatusDownload: Async downloadFile started for $statusItem")
                val userJid = statusItem.senderJid ?: statusItem.fMessage?.userJid ?: FMessageWpp.UserJid("status@broadcast")
                val contactName = runCatching { WppCore.getContactName(userJid) }.getOrNull() ?: "Status"

                // 1. Cek apakah file sudah langsung tersedia
                var file = statusItem.getMediaFile() ?: statusItem.fMessage?.mediaFile
                val isReady = file != null && file.exists() && file.length() > 0L

                if (!isReady) {
                    Utils.showToast("⏳ Mengunduh status di latar belakang...", Toast.LENGTH_SHORT)
                    Utils.showProgressNotification(
                        notifId,
                        "⏳ Mengunduh Status $contactName",
                        "Sedang mengunduh media status di latar belakang..."
                    )
                }

                // 2. Polling di background hingga file selesai diunduh oleh WhatsApp (maksimal 35 detik)
                val startTime = System.currentTimeMillis()
                var lastSize = -1L
                var stableCount = 0

                while (System.currentTimeMillis() - startTime < 35_000) {
                    file = statusItem.getMediaFile() ?: statusItem.fMessage?.mediaFile
                    if (file == null || !file.exists() || file.length() == 0L) {
                        file = findRecentStatusFileInStatusesFolder(statusItem)
                    }

                    if (file != null && file.exists() && file.length() > 0L) {
                        val currentSize = file.length()
                        if (currentSize == lastSize) {
                            stableCount++
                            if (stableCount >= 4) {
                                // Ukuran file stabil selama 2 detik penuh (selesai ditulis)
                                break
                            }
                        } else {
                            lastSize = currentSize
                            stableCount = 0
                        }
                    }
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                if (file == null || !file.exists() || file.length() == 0L) {
                    logDebug("StatusDownload: Timeout waiting for media file")
                    Utils.updateNotificationError(
                        notifId,
                        "❌ Gagal Mengunduh Status $contactName",
                        "Waktu habis atau media status belum tersedia."
                    )
                    Utils.showToast(Utils.getString(R.string.download_not_available), Toast.LENGTH_LONG)
                    return@execute
                }

                val fileType = if (file.name.contains(".")) file.name.substringAfterLast(".") else "mp4"
                val destination = getStatusDestination(file)
                val name = Utils.generateName(userJid, fileType)
                logDebug("StatusDownload: copying completed file from ${file.absolutePath} (${file.length()} bytes) to $destination$name")
                val error = Utils.copyFile(file, destination, name)

                if (TextUtils.isEmpty(error)) {
                    Utils.updateNotificationSuccess(
                        notifId,
                        "✅ Status $contactName Tersimpan",
                        "Tersimpan di: $destination$name"
                    )
                    Utils.showToast(Utils.getString(R.string.saved_to) + destination, Toast.LENGTH_SHORT)
                } else {
                    logDebug("StatusDownload: copyFile error=$error")
                    Utils.updateNotificationError(
                        notifId,
                        "❌ Gagal Menyimpan Status",
                        "Error: $error"
                    )
                    Utils.showToast("${Utils.getString(R.string.error_when_saving_try_again)}: $error", Toast.LENGTH_SHORT)
                }
            } catch (e: Throwable) {
                logDebug("StatusDownload: downloadFile error=${e.message}")
                Utils.updateNotificationError(
                    notifId,
                    "❌ Gagal Mengunduh Status",
                    e.message ?: "Terjadi kesalahan sistem"
                )
                Utils.showToast(e.message, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun findRecentStatusFileInStatusesFolder(statusItem: StatusItemWpp): File? {
        val extStorage = Environment.getExternalStorageDirectory().absolutePath
        val paths = arrayOf(
            "$extStorage/Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "$extStorage/WhatsApp/Media/.Statuses"
        )
        val now = System.currentTimeMillis()
        val expectedName = statusItem.fMessage?.mediaFile?.name

        for (path in paths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: continue
                // 1. Coba cocokkan dengan nama file jika ada
                if (!expectedName.isNullOrEmpty()) {
                    val matched = files.firstOrNull { it.name.equals(expectedName, ignoreCase = true) && it.length() > 0L }
                    if (matched != null) return matched
                }
                // 2. Fallback: file terbaru dalam window 30 detik terakhir
                val recentFile = files
                    .filter { it.isFile && it.length() > 0L && (now - it.lastModified()) < 30_000 }
                    .maxByOrNull { it.lastModified() }
                if (recentFile != null) return recentFile
            }
        }
        return null
    }

    override fun getPluginName(): String {
        return "Download Status"
    }

    @Throws(Exception::class)
    private fun getStatusDestination(f: File): String {
        val fileName = f.name.lowercase()
        val mimeType = MimeTypeUtils.getMimeTypeFromExtension(fileName)

        val folderPath = when {
            mimeType.contains("video") -> "Status Videos"
            mimeType.contains("image") -> "Status Images"
            mimeType.contains("audio") -> "Status Sounds"
            else -> "Status Media"
        }

        return Utils.getDestination(folderPath)
    }
}