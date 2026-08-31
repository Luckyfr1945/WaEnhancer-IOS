package com.wmods.wppenhacer

import android.app.Activity
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import io.noties.markwon.Markwon
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateChecker(private val mActivity: Activity) : Runnable {

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/Luckyfr1945/WaEnhancer-IOS/releases/latest"
        private const val DEFAULT_RELEASE_URL = "https://github.com/Luckyfr1945/WaEnhancer-IOS/releases/latest"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun run() {
        try {
            val request = okhttp3.Request.Builder()
                .url(LATEST_RELEASE_API)
                .build()

            val tagName: String
            val releaseUrl: String
            val changelog: String
            val publishedAt: String

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return

                val content = response.body.string()
                val release = JSONObject(content)
                tagName = release.optString("tag_name", "").trim()

                if (tagName.isBlank()) return

                val htmlUrl = release.optString("html_url", DEFAULT_RELEASE_URL)
                releaseUrl = htmlUrl
                changelog = release.optString("body", "Pembaruan versi baru telah tersedia.").trim()
                publishedAt = release.optString("published_at", "")
            }

            if (tagName.isBlank()) return

            val packageInfo = try {
                mActivity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
            } catch (e: Exception) {
                XposedBridge.log(e)
                return
            }

            val currentVersion = packageInfo.versionName?.lowercase() ?: ""
            val hash = if (tagName.contains("-")) tagName.split("-")[1].trim() else tagName
            val isNewVersion = !currentVersion.contains(hash.lowercase().trim())

            // Check if user has already been notified ONCE for this release version
            val lastNotifiedVersion = WppCore.getPrivString("last_notified_release_version", "")
            if (isNewVersion && lastNotifiedVersion != tagName) {
                // Mark as notified so it NEVER pops up again for this version
                WppCore.setPrivString("last_notified_release_version", tagName)

                mActivity.runOnUiThread {
                    if (!mActivity.isFinishing && !mActivity.isDestroyed) {
                        showUpdateDialog(tagName, changelog, publishedAt, releaseUrl)
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    private fun showUpdateDialog(tagName: String, changelog: String, publishedAt: String, releaseUrl: String) {
        try {
            val markwon = Markwon.create(mActivity)
            val formattedDate = formatPublishedDate(publishedAt)

            val message = buildString {
                append("📦 **Versi Baru:** `").append(tagName).append("`\n")
                if (formattedDate.isNotEmpty()) {
                    append("📅 **Rilis:** ").append(formattedDate).append("\n")
                }
                append("\n### Rincian Pembaruan:\n\n").append(changelog)
            }

            val builder = android.app.AlertDialog.Builder(mActivity)
            builder.setTitle("🎉 Pembaruan WaEnhancer Tersedia!")
            builder.setMessage(markwon.toMarkdown(message))
            builder.setNegativeButton("Nanti") { d, _ ->
                d.dismiss()
            }
            builder.setPositiveButton("Unduh Sekarang") { d, _ ->
                Utils.openLink(mActivity, releaseUrl)
                d.dismiss()
            }
            val dialog = builder.create()
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatPublishedDate(isoDate: String?): String {
        if (isoDate.isNullOrEmpty()) return ""

        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(isoDate)
            if (date != null) {
                val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                displayFormat.format(date)
            } else ""
        } catch (e: Exception) {
            XposedBridge.log(e)
            ""
        }
    }
}
