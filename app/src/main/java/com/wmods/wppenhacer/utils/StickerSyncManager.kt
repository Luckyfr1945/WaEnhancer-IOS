package com.wmods.wppenhacer.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object StickerSyncManager {

    init {
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
        )
    }

    const val PACKAGE_WHATSAPP = "com.whatsapp"
    const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private const val STICKERS_DB = "stickers.db"
    private const val STICKERS_DB_WAL = "stickers.db-wal"
    private const val STICKERS_DB_SHM = "stickers.db-shm"
    private const val INFO_JSON = "info.json"

    data class BackupInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val timestamp: Long,
        val deviceModel: String,
        val androidVersion: String,
        val sourceDir: File,
        val zipFile: File? = null
    )

    data class BackupItem(
        val file: File,
        val isZip: Boolean,
        val name: String,
        val sizeFormatted: String,
        val dateFormatted: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val timestamp: Long
    )

    enum class LogLevel {
        INFO, SUCCESS, WARNING, ERROR
    }

    interface ProgressCallback {
        fun onLog(message: String, level: LogLevel = LogLevel.INFO)
        fun onProgress(step: Int, totalSteps: Int, description: String)
        fun onCompleted(success: Boolean, resultData: Any? = null)
    }

    fun getAvailableBackups(): List<BackupItem> {
        val rootDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WaEnhancer/Stickers"
        )
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()

        val results = mutableListOf<BackupItem>()
        val files = rootDir.listFiles() ?: return emptyList()

        for (f in files) {
            try {
                if (f.isFile && f.name.endsWith(".zip", ignoreCase = true)) {
                    var pkg = PACKAGE_WHATSAPP
                    var vName = "unknown"
                    var vCode = 0L
                    var time = f.lastModified()

                    try {
                        ZipInputStream(FileInputStream(f)).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name == INFO_JSON || entry.name.endsWith("/$INFO_JSON")) {
                                    val text = zis.bufferedReader().readText()
                                    val json = JSONObject(text)
                                    pkg = json.optString("package", pkg)
                                    vName = json.optString("versionName", vName)
                                    vCode = json.optLong("versionCode", vCode)
                                    time = json.optLong("timestamp", time)
                                    break
                                }
                                entry = zis.nextEntry
                            }
                        }
                    } catch (_: Exception) {}

                    val sizeKb = f.length() / 1024
                    val sizeStr = if (sizeKb > 1024) "${String.format(Locale.getDefault(), "%.1f", sizeKb / 1024f)} MB" else "$sizeKb KB"
                    val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(time))

                    results.add(
                        BackupItem(
                            file = f,
                            isZip = true,
                            name = f.name,
                            sizeFormatted = sizeStr,
                            dateFormatted = dateStr,
                            packageName = pkg,
                            versionName = vName,
                            versionCode = vCode,
                            timestamp = time
                        )
                    )
                } else if (f.isDirectory && (f.name.startsWith("backup_") || f.name.startsWith("test_"))) {
                    // Clean up intermediate raw folders
                    f.deleteRecursively()
                }
            } catch (_: Exception) {}
        }

        return results.sortedByDescending { it.timestamp }
    }

    fun isRootAvailable(callback: (Boolean) -> Unit) {
        Thread {
            try {
                val shell = Shell.getShell()
                if (!shell.isRoot) {
                    callback(false)
                    return@Thread
                }
                // Actively execute `id` to verify real root UID=0 execution
                val result = Shell.cmd("id").exec()
                val isUid0 = result.isSuccess && result.out.any { it.contains("uid=0") }
                callback(isUid0)
            } catch (_: Exception) {
                callback(false)
            }
        }.start()
    }

    fun getInstalledWhatsAppVersion(context: Context, packageName: String): String? {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName
        } catch (_: Exception) {
            null
        }
    }

    fun getInstalledWhatsAppVersionCode(context: Context, packageName: String): Long {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Backup stickers.db, stickers.db-wal, stickers.db-shm to shared storage.
     */
    fun backupStickers(context: Context, packageName: String, callback: ProgressCallback) {
        Shell.getShell { shell ->
            val idCheck = Shell.cmd("id").exec()
            val hasRealRoot = shell.isRoot && idCheck.isSuccess && idCheck.out.any { it.contains("uid=0") }
            if (!hasRealRoot) {
                callback.onLog("❌ Akses Root ditolak / Superuser tidak aktif (UID != 0).", LogLevel.ERROR)
                callback.onLog("💡 Buka aplikasi Root Manager (KernelSU / Magisk / APatch) dan berikan izin Superuser untuk WaEnhancer.", LogLevel.WARNING)
                callback.onCompleted(false, "Root access denied: UID is not 0")
                return@getShell
            }

            try {
                val totalSteps = 6
                callback.onProgress(1, totalSteps, "Menghentikan proses WhatsApp...")
                callback.onLog("📦 Menyiapkan backup untuk target: $packageName")

                // Step 1: Force stop target package to flush WAL checkpoint
                callback.onLog("⚡ Menjalankan: am force-stop $packageName")
                val stopResult = Shell.cmd("am force-stop $packageName").exec()
                if (!stopResult.isSuccess) {
                    callback.onLog("⚠️ Peringatan: am force-stop mengembalikan kode ${stopResult.code}", LogLevel.WARNING)
                }

                // Step 2: Prepare export directory in cacheDir (for staging) and public Downloads (for final ZIP)
                callback.onProgress(2, totalSteps, "Menyiapkan folder penyimpanan...")
                val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val stagingDir = File(context.cacheDir, "sticker_backup_stage_$timestampStr").apply { mkdirs() }
                val stagingPath = stagingDir.absolutePath
                val appUid = context.applicationInfo.uid

                val exportFolder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "WaEnhancer/Stickers"
                ).apply { mkdirs() }

                callback.onLog("📁 Lokasi penyimpanan: ${exportFolder.absolutePath}")

                // Step 3: Check database existence in /data/data
                callback.onProgress(3, totalSteps, "Memeriksa database stiker...")
                val srcDbDir = "/data/data/$packageName/databases"
                val checkDb = Shell.cmd("[ -f '$srcDbDir/$STICKERS_DB' ] && echo 'FOUND' || echo 'NOT_FOUND'").exec()
                val found = checkDb.out.any { it.contains("FOUND") }

                if (!found) {
                    callback.onLog("❌ File $srcDbDir/$STICKERS_DB tidak ditemukan di data aplikasi!", LogLevel.ERROR)
                    callback.onLog("💡 Kemungkinan belum ada stiker yang difavoritkan atau path database berbeda.", LogLevel.WARNING)
                    stagingDir.deleteRecursively()
                    callback.onCompleted(false, "stickers.db not found")
                    return@getShell
                }
                callback.onLog("✅ Database stickers.db ditemukan di $srcDbDir", LogLevel.SUCCESS)

                // Step 4: Copy database and sticker media files with root via /data/local/tmp bridge into app cache staging directory
                callback.onProgress(4, totalSteps, "Menyalin file database & gambar stiker (.webp)...")
                val srcFilesDir = "/data/data/$packageName/files"
                val bridgeTmp = "/data/local/tmp/wa_backup_${System.currentTimeMillis()}"
                val copyResult = Shell.cmd(
                    "mkdir -p '$bridgeTmp'",
                    "cat '$srcDbDir/$STICKERS_DB' > '$bridgeTmp/$STICKERS_DB'",
                    "[ -f '$srcDbDir/$STICKERS_DB_WAL' ] && cat '$srcDbDir/$STICKERS_DB_WAL' > '$bridgeTmp/$STICKERS_DB_WAL' || true",
                    "[ -f '$srcDbDir/$STICKERS_DB_SHM' ] && cat '$srcDbDir/$STICKERS_DB_SHM' > '$bridgeTmp/$STICKERS_DB_SHM' || true",
                    "[ -d '$srcFilesDir/Stickers' ] && cp -rf '$srcFilesDir/Stickers' '$bridgeTmp/' || true",
                    "[ -f '$srcFilesDir/content_stickers' ] && cat '$srcFilesDir/content_stickers' > '$bridgeTmp/content_stickers' || true",
                    "mkdir -p '$stagingPath'",
                    "cp -rf '$bridgeTmp'/* '$stagingPath/'",
                    "chown -R $appUid:$appUid '$stagingPath'",
                    "chmod -R 777 '$stagingPath'",
                    "restorecon -RF '$stagingPath' || true",
                    "rm -rf '$bridgeTmp'"
                ).exec()

                callback.onLog("🔎 Shell copy: exit=${copyResult.code} | stdout=${copyResult.out.joinToString(" / ").ifEmpty { "-" }} | stderr=${copyResult.err.joinToString(" / ").ifEmpty { "-" }}", LogLevel.INFO)

                val copiedDb = File(stagingDir, STICKERS_DB)
                if (!copiedDb.exists() || copiedDb.length() == 0L) {
                    callback.onLog("❌ Gagal membaca database stiker dari $srcDbDir (File kosong atau tidak dapat diakses)", LogLevel.ERROR)
                    stagingDir.deleteRecursively()
                    callback.onCompleted(false, "Copy failed: database empty or unreadable")
                    return@getShell
                }

                val stagedMediaDir = File(stagingDir, "Stickers")
                val mediaCount = stagedMediaDir.listFiles()?.size ?: 0

                // Analyze stickers inside copied database
                var starredCount = 0
                var totalStickers = 0
                try {
                    SQLiteDatabase.openDatabase(copiedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        try {
                            db.rawQuery("SELECT COUNT(*) FROM starred_stickers", null).use { c ->
                                if (c.moveToFirst()) starredCount = c.getInt(0)
                            }
                        } catch (_: Exception) {}
                        try {
                            db.rawQuery("SELECT COUNT(*) FROM stickers", null).use { c ->
                                if (c.moveToFirst()) totalStickers = c.getInt(0)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    callback.onLog("⚠️ Peringatan saat membaca isi tabel: ${e.message}", LogLevel.WARNING)
                }

                callback.onLog("✅ Database stickers.db (${copiedDb.length() / 1024} KB) & $mediaCount file gambar stiker berhasil diekspor. ($starredCount stiker berbintang, total $totalStickers stiker tersimpan)", LogLevel.SUCCESS)

                // Step 5: Write metadata info.json
                callback.onProgress(5, totalSteps, "Membuat metadata info.json...")
                val vName = getInstalledWhatsAppVersion(context, packageName) ?: "unknown"
                val vCode = getInstalledWhatsAppVersionCode(context, packageName)
                val infoJson = JSONObject().apply {
                    put("package", packageName)
                    put("versionName", vName)
                    put("versionCode", vCode)
                    put("timestamp", System.currentTimeMillis())
                    put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("androidVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    put("starredStickersCount", starredCount)
                    put("totalStickersCount", totalStickers)
                    put("mediaFilesCount", mediaCount)
                }

                val infoFile = File(stagingDir, INFO_JSON)
                infoFile.writeText(infoJson.toString(4))
                callback.onLog("📄 Metadata info.json berhasil dibuat (WA v$vName).")

                // Step 6: Create shareable ZIP archive
                callback.onProgress(6, totalSteps, "Mengemas ZIP cadangan...")
                val zipFile = File(
                    exportFolder,
                    "WA_Stickers_${packageName.substringAfterLast('.')}_${vName}_$timestampStr.zip"
                )
                createZipArchive(stagingDir, zipFile)

                // Verify ZIP integrity and entries
                val zipEntries = mutableListOf<String>()
                try {
                    ZipInputStream(FileInputStream(zipFile)).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            zipEntries.add(entry.name)
                            entry = zis.nextEntry
                        }
                    }
                } catch (_: Exception) {}

                if (!zipEntries.contains(STICKERS_DB) || zipFile.length() < 1024L) {
                    callback.onLog("❌ Gagal mengemas database ke dalam ZIP! (Ukuran: ${zipFile.length()} bytes, Isi: ${zipEntries.joinToString()})", LogLevel.ERROR)
                    zipFile.delete()
                    stagingDir.deleteRecursively()
                    callback.onCompleted(false, "ZIP package verification failed")
                    return@getShell
                }

                callback.onLog("🎁 File ZIP cadangan siap: ${zipFile.name} (${zipFile.length() / 1024} KB, ${zipEntries.size} items)", LogLevel.SUCCESS)

                // Clean up raw staging directory in cache
                stagingDir.deleteRecursively()

                val backupInfo = BackupInfo(
                    packageName = packageName,
                    versionName = vName,
                    versionCode = vCode,
                    timestamp = System.currentTimeMillis(),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = "Android ${Build.VERSION.RELEASE}",
                    sourceDir = exportFolder,
                    zipFile = zipFile
                )

                callback.onLog("🎉 PROSES BACKUP SELESAI DENGAN SUKSES!", LogLevel.SUCCESS)
                callback.onCompleted(true, backupInfo)

            } catch (e: Throwable) {
                callback.onLog("❌ Terjadi exception saat backup: ${e.message}", LogLevel.ERROR)
                callback.onCompleted(false, e.message)
            }
        }
    }

    /**
     * Inspect a backup folder or ZIP file before restoring.
     */
    fun inspectBackup(source: File): BackupInfo? {
        return try {
            if (source.isFile && source.name.endsWith(".zip", ignoreCase = true)) {
                // Read info.json from ZIP
                ZipInputStream(FileInputStream(source)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(INFO_JSON)) {
                            val jsonText = zis.reader().readText()
                            val json = JSONObject(jsonText)
                            return BackupInfo(
                                packageName = json.optString("package", PACKAGE_WHATSAPP),
                                versionName = json.optString("versionName", "unknown"),
                                versionCode = json.optLong("versionCode", 0L),
                                timestamp = json.optLong("timestamp", 0L),
                                deviceModel = json.optString("deviceModel", "Unknown"),
                                androidVersion = json.optString("androidVersion", "Unknown"),
                                sourceDir = source.parentFile ?: source,
                                zipFile = source
                            )
                        }
                        entry = zis.nextEntry
                    }
                }
            } else if (source.isDirectory) {
                val infoFile = File(source, INFO_JSON)
                if (infoFile.exists()) {
                    val json = JSONObject(infoFile.readText())
                    return BackupInfo(
                        packageName = json.optString("package", PACKAGE_WHATSAPP),
                        versionName = json.optString("versionName", "unknown"),
                        versionCode = json.optLong("versionCode", 0L),
                        timestamp = json.optLong("timestamp", 0L),
                        deviceModel = json.optString("deviceModel", "Unknown"),
                        androidVersion = json.optString("androidVersion", "Unknown"),
                        sourceDir = source,
                        zipFile = null
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restore stickers database to target WhatsApp package with correct UID, permissions, and SELinux.
     * @param mergeMode If true, merges backup stickers with existing WhatsApp stickers instead of overwriting.
     */
    fun restoreStickers(
        context: Context,
        packageName: String,
        backupSource: File,
        mergeMode: Boolean = true,
        callback: ProgressCallback
    ) {
        Shell.getShell { shell ->
            val idCheck = Shell.cmd("id").exec()
            val hasRealRoot = shell.isRoot && idCheck.isSuccess && idCheck.out.any { it.contains("uid=0") }
            if (!hasRealRoot) {
                callback.onLog("❌ Akses Root ditolak / Superuser tidak aktif (UID != 0).", LogLevel.ERROR)
                callback.onLog("💡 Buka aplikasi Root Manager (KernelSU / Magisk / APatch) dan berikan izin Superuser untuk WaEnhancer.", LogLevel.WARNING)
                callback.onCompleted(false, "Root access denied: UID is not 0")
                return@getShell
            }

            try {
                val totalSteps = if (mergeMode) 9 else 8
                callback.onProgress(1, totalSteps, "Mempersiapkan data pemulihan...")
                callback.onLog("🔄 Memulai pemulihan stiker untuk: $packageName (Mode: ${if (mergeMode) "Gabungkan / Merge" else "Ganti Total / Replace"})")

                // Extract ZIP if backupSource is a ZIP file
                val workingDir = if (backupSource.isFile && backupSource.name.endsWith(".zip", ignoreCase = true)) {
                    val tempExtracted = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}")
                    tempExtracted.mkdirs()
                    callback.onLog("📦 Mengekstrak file ZIP cadangan...")
                    extractZip(backupSource, tempExtracted)
                    tempExtracted
                } else {
                    backupSource
                }

                // Verify stickers.db in working dir
                val dbFile = File(workingDir, STICKERS_DB)
                if (!dbFile.exists()) {
                    callback.onLog("❌ File $STICKERS_DB tidak ditemukan di dalam folder cadangan!", LogLevel.ERROR)
                    callback.onCompleted(false, "stickers.db missing")
                    return@getShell
                }
                callback.onLog("✅ File $STICKERS_DB cadangan valid (${dbFile.length() / 1024} KB).")

                // Step 1: Force stop target package to flush WAL
                callback.onProgress(2, totalSteps, "Menghentikan proses $packageName...")
                callback.onLog("⚡ Menjalankan: am force-stop $packageName")
                Shell.cmd("am force-stop $packageName").exec()

                // Step 2: Check target databases directory
                callback.onProgress(3, totalSteps, "Memeriksa folder database tujuan...")
                val dstDir = "/data/data/$packageName/databases"
                val checkDst = Shell.cmd("[ -d '$dstDir' ] && echo 'EXISTS' || echo 'NOT_FOUND'").exec()
                if (!checkDst.out.any { it.contains("EXISTS") }) {
                    callback.onLog("📁 Folder $dstDir belum ada, membuat folder...", LogLevel.INFO)
                    Shell.cmd("mkdir -p '$dstDir'").exec()
                }

                // Step 3: Check if local stickers.db exists on device
                val checkExisting = Shell.cmd("[ -f '$dstDir/$STICKERS_DB' ] && echo 'EXISTS' || echo 'NOT_FOUND'").exec()
                val localDbExists = checkExisting.out.any { it.contains("EXISTS") }

                var finalDbToDeploy = dbFile

                if (mergeMode && localDbExists) {
                    callback.onProgress(4, totalSteps, "Menggabungkan stiker lokal dengan cadangan...")
                    callback.onLog("🧩 Terdeteksi database stiker lokal di WhatsApp. Melakukan proses Merge...")

                    val localTempDir = File(context.cacheDir, "temp_local_${System.currentTimeMillis()}")
                    localTempDir.mkdirs()
                    val localTempDb = File(localTempDir, STICKERS_DB)

                    val appUid = context.applicationInfo.uid
                    val tmpPrefix = "/data/local/tmp/wa_merge_${System.currentTimeMillis()}"
                    // Copy local db to app cache via tmp bridge
                    Shell.cmd(
                        "mkdir -p '${localTempDir.absolutePath}'",
                        "cat '$dstDir/$STICKERS_DB' > '$tmpPrefix.db'",
                        "cat '$tmpPrefix.db' > '${localTempDb.absolutePath}'",
                        "rm -f '$tmpPrefix'*",
                        "chown -R $appUid:$appUid '${localTempDir.absolutePath}'",
                        "chmod -R 777 '${localTempDir.absolutePath}'",
                        "restorecon -RF '${localTempDir.absolutePath}' || true"
                    ).exec()

                    if (localTempDb.exists() && localTempDb.length() > 0) {
                        val mergeSuccess = mergeSqliteDatabases(localTempDb, dbFile) { msg, level ->
                            callback.onLog(msg, level)
                        }
                        if (mergeSuccess) {
                            finalDbToDeploy = localTempDb
                            callback.onLog("🎉 Penggabungan database SQLite selesai tanpa konflik!", LogLevel.SUCCESS)
                        } else {
                            callback.onLog("⚠️ Gagal merge otomatis, beralih menggunakan file cadangan langsung.", LogLevel.WARNING)
                        }
                    }
                }

                // Step 4: Resolve UID:GID ownership of WhatsApp app data directory
                callback.onProgress(5, totalSteps, "Mendeteksi UID & GID WhatsApp...")
                val statCmd = Shell.cmd(
                    "stat -c '%u:%g' '$dstDir' 2>/dev/null || stat -c '%u:%g' '/data/data/$packageName' 2>/dev/null"
                ).exec()

                val uidGid = statCmd.out.firstOrNull { it.matches(Regex("\\d+:\\d+")) }
                if (uidGid == null) {
                    callback.onLog("❌ Gagal membaca UID:GID kepemilikan $packageName!", LogLevel.ERROR)
                    callback.onCompleted(false, "UID/GID resolution failed")
                    return@getShell
                }
                callback.onLog("👤 Terdeteksi UID:GID pemilik aplikasi: $uidGid", LogLevel.SUCCESS)

                // Step 5: Copy database files cleanly into /data/data/<package>/databases/ via tmp bridge
                callback.onProgress(6, totalSteps, "Menyalin database ke data aplikasi...")
                val srcPath = finalDbToDeploy.absolutePath
                val restoreTmp = "/data/local/tmp/wa_restore_${System.currentTimeMillis()}.db"
                Shell.cmd(
                    "cat '$srcPath' > '$restoreTmp'",
                    "rm -f '$dstDir/$STICKERS_DB' '$dstDir/$STICKERS_DB_WAL' '$dstDir/$STICKERS_DB_SHM'",
                    "cat '$restoreTmp' > '$dstDir/$STICKERS_DB'",
                    "rm -f '$restoreTmp'"
                ).exec()

                val checkCopied = Shell.cmd("[ -f '$dstDir/$STICKERS_DB' ] && echo 'FOUND' || echo 'NOT_FOUND'").exec()
                if (!checkCopied.out.any { it.contains("FOUND") }) {
                    callback.onLog("❌ Gagal menyalin database ke $dstDir", LogLevel.ERROR)
                    callback.onCompleted(false, "Copy to data dir failed")
                    return@getShell
                }
                callback.onLog("✅ Database stiker berhasil disalin ke $dstDir", LogLevel.SUCCESS)

                // Step 6: Apply chown and chmod 660
                callback.onProgress(7, totalSteps, "Menetapkan hak akses (chown & chmod 660)...")
                val permCmd = Shell.cmd(
                    "chown $uidGid '$dstDir/$STICKERS_DB'*",
                    "chmod 660 '$dstDir/$STICKERS_DB'*"
                ).exec()

                if (!permCmd.isSuccess) {
                    callback.onLog("⚠️ Gagal mengatur permission: ${permCmd.err.joinToString("\n")}", LogLevel.WARNING)
                } else {
                    callback.onLog("✅ Izin akses berhasil diatur (chown $uidGid & chmod 660).", LogLevel.SUCCESS)
                }

                // Step 7: Deploy sticker media images (.webp / .was) if present in backup
                val stagingStickersDir = File(workingDir, "Stickers")
                val dstFilesDir = "/data/data/$packageName/files"

                if (stagingStickersDir.exists() && stagingStickersDir.isDirectory) {
                    callback.onProgress(8, totalSteps, "Menyalin gambar stiker (.webp)...")
                    val mediaFiles = stagingStickersDir.listFiles()?.filter { it.isFile } ?: emptyList()
                    val countMedia = mediaFiles.size
                    callback.onLog("🖼️ Terdeteksi $countMedia file gambar stiker di dalam cadangan. Menyalin ke $dstFilesDir/Stickers/...")

                    val restoreMediaTmp = "/data/local/tmp/wa_restore_stickers_${System.currentTimeMillis()}"
                    Shell.cmd(
                        "mkdir -p '$restoreMediaTmp'",
                        "cp -rf '${stagingStickersDir.absolutePath}'/* '$restoreMediaTmp/' || true",
                        "mkdir -p '$dstFilesDir/Stickers'",
                        "cp -rf '$restoreMediaTmp'/* '$dstFilesDir/Stickers/'",
                        "chown -R $uidGid '$dstFilesDir/Stickers'",
                        "chmod 700 '$dstFilesDir/Stickers'",
                        "chmod 600 '$dstFilesDir/Stickers'/* || true",
                        "restorecon -RF '$dstFilesDir/Stickers' || chcon -R u:object_r:app_data_file:s0 '$dstFilesDir/Stickers' || true",
                        "rm -rf '$restoreMediaTmp'"
                    ).exec()
                    callback.onLog("✅ $countMedia gambar stiker berhasil disalin & diberi hak akses.", LogLevel.SUCCESS)
                }

                val srcContentStickers = File(workingDir, "content_stickers")
                if (srcContentStickers.exists()) {
                    val restoreContentTmp = "/data/local/tmp/wa_restore_content_${System.currentTimeMillis()}"
                    Shell.cmd(
                        "cat '${srcContentStickers.absolutePath}' > '$restoreContentTmp'",
                        "cat '$restoreContentTmp' > '$dstFilesDir/content_stickers'",
                        "chown $uidGid '$dstFilesDir/content_stickers'",
                        "chmod 600 '$dstFilesDir/content_stickers'",
                        "restorecon -F '$dstFilesDir/content_stickers' || true",
                        "rm -f '$restoreContentTmp'"
                    ).exec()
                }

                // Step 8: Fix SELinux Context
                callback.onProgress(totalSteps, totalSteps, "Memperbaiki konteks SELinux...")
                val selinuxCmd = Shell.cmd(
                    "restorecon -RF '$dstDir' || chcon -R u:object_r:app_data_file:s0 '$dstDir' || true",
                    "restorecon -RF '$dstFilesDir/Stickers' || chcon -R u:object_r:app_data_file:s0 '$dstFilesDir/Stickers' || true"
                ).exec()
                callback.onLog("🛡️ Konteks SELinux disesuaikan (restorecon / chcon app_data_file).", LogLevel.SUCCESS)

                // Clean temporary extraction if any
                if (workingDir != backupSource && workingDir.exists()) {
                    workingDir.deleteRecursively()
                }

                callback.onLog("🎉 PEMULIHAN & SINKRONISASI STIKER BERHASIL!", LogLevel.SUCCESS)
                callback.onLog("💡 Silakan buka WhatsApp dan periksa tab stiker bintang/favorit Anda.")
                callback.onCompleted(true, null)

            } catch (e: Throwable) {
                callback.onLog("❌ Terjadi exception saat restore: ${e.message}", LogLevel.ERROR)
                callback.onCompleted(false, e.message)
            }
        }
    }

    private fun mergeSqliteDatabases(
        localDbFile: File,
        backupDbFile: File,
        onLog: (String, LogLevel) -> Unit
    ): Boolean {
        var localDb: SQLiteDatabase? = null
        return try {
            localDb = SQLiteDatabase.openDatabase(
                localDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )

            val attachSql = "ATTACH DATABASE '${backupDbFile.absolutePath}' AS backup_db"
            localDb.execSQL(attachSql)

            // Get initial count for starred_stickers if exists
            var initialStarred = 0
            try {
                val c = localDb.rawQuery("SELECT COUNT(*) FROM main.starred_stickers", null)
                c.use { if (it.moveToFirst()) initialStarred = it.getInt(0) }
            } catch (_: Exception) {}

            // Find all tables in backup_db
            val cursor = localDb.rawQuery(
                "SELECT name FROM backup_db.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
                null
            )
            val tableNames = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    tableNames.add(it.getString(0))
                }
            }

            // Filter tables: strictly focus on favorite/starred stickers, ignoring recent stickers
            val targetTables = tableNames.filter {
                it == "starred_stickers" || it == "stickers" || (!it.contains("recent", ignoreCase = true) && !it.contains("session", ignoreCase = true))
            }

            for (tableName in targetTables) {
                val checkCursor = localDb.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(tableName)
                )
                val existsInLocal = checkCursor.use { it.moveToFirst() }

                if (existsInLocal) {
                    val localCols = getTableColumns(localDb, "main", tableName)
                    val backupCols = getTableColumns(localDb, "backup_db", tableName)
                    val commonCols = localCols.intersect(backupCols)

                    if (commonCols.isNotEmpty()) {
                        val colListStr = commonCols.joinToString(", ") { "`$it`" }
                        val insertQuery = "INSERT OR IGNORE INTO main.`$tableName` ($colListStr) SELECT $colListStr FROM backup_db.`$tableName`"
                        localDb.execSQL(insertQuery)
                        onLog("🧩 Tabel $tableName digabungkan (${commonCols.size} kolom dicocokkan).", LogLevel.INFO)
                    }
                } else {
                    val createSqlCursor = localDb.rawQuery(
                        "SELECT sql FROM backup_db.sqlite_master WHERE type='table' AND name=?",
                        arrayOf(tableName)
                    )
                    val createSql = createSqlCursor.use { if (it.moveToFirst()) it.getString(0) else null }
                    if (createSql != null) {
                        localDb.execSQL(createSql)
                        localDb.execSQL("INSERT OR IGNORE INTO main.`$tableName` SELECT * FROM backup_db.`$tableName`")
                        onLog("📋 Menyalin tabel baru: $tableName", LogLevel.INFO)
                    }
                }
            }

            // Get final count for starred_stickers
            var finalStarred = initialStarred
            try {
                val c = localDb.rawQuery("SELECT COUNT(*) FROM main.starred_stickers", null)
                c.use { if (it.moveToFirst()) finalStarred = it.getInt(0) }
            } catch (_: Exception) {}

            val addedCount = finalStarred - initialStarred
            onLog("⭐ Stiker Favorit: $initialStarred stiker lama + $addedCount stiker baru (Total: $finalStarred stiker)", LogLevel.SUCCESS)

            localDb.execSQL("DETACH DATABASE backup_db")
            true
        } catch (e: Exception) {
            onLog("❌ Gagal menggabungkan SQLite: ${e.message}", LogLevel.ERROR)
            false
        } finally {
            localDb?.close()
        }
    }

    private fun getTableColumns(db: SQLiteDatabase, schema: String, tableName: String): Set<String> {
        val cols = mutableSetOf<String>()
        try {
            val cursor = db.rawQuery("PRAGMA $schema.table_info(`$tableName`)", null)
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (name != null) cols.add(name)
                }
            }
        } catch (_: Exception) {}
        return cols
    }

    /**
     * Create Intent to share the backup ZIP file via any installed app.
     */
    fun createShareIntent(context: Context, zipFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WA Sticker Favorite Backup - ${zipFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createZipArchive(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zipDirectory(sourceDir, sourceDir, zos)
        }
    }

    private fun zipDirectory(rootDir: File, currentDir: File, zos: ZipOutputStream) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val relPath = file.relativeTo(rootDir).path.replace('\\', '/') + "/"
                val entry = ZipEntry(relPath)
                zos.putNextEntry(entry)
                zos.closeEntry()
                zipDirectory(rootDir, file, zos)
            } else if (file.isFile) {
                val relPath = file.relativeTo(rootDir).path.replace('\\', '/')
                FileInputStream(file).use { fis ->
                    val entry = ZipEntry(relPath)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
