package com.wmods.wppenhacer.ui.fragments

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.MainActivity
import com.wmods.wppenhacer.databinding.FragmentStickerSyncBinding
import com.wmods.wppenhacer.databinding.ItemStickerBackupCardBinding
import com.wmods.wppenhacer.ui.fragments.base.BaseFragment
import com.wmods.wppenhacer.utils.StickerSyncManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class StickerSyncFragment : BaseFragment() {

    private var _binding: FragmentStickerSyncBinding? = null
    private val viewBinding get() = _binding!!

    private var selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP
    private var lastBackupZip: File? = null
    private var isRootGranted = false

    private val logBuilder = SpannableStringBuilder()
    private lateinit var backupAdapter: StickerBackupAdapter
    private val backupList = mutableListOf<StickerSyncManager.BackupItem>()

    // File picker for restoring ZIP backup
    private val restoreFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                handleSelectedRestoreUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewBinding.containerHistoryTab.visibility == View.VISIBLE) {
                    switchTab(0)
                    return
                }
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                } else {
                    requireActivity().finish()
                }
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStickerSyncBinding.inflate(inflater, container, false)
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initEnvironment()
        setupListeners()
        setupTabs()
        setupHistoryRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        setDisplayHomeAsUpEnabled(true)
        (activity as? MainActivity)?.setBottomNavVisibility(View.GONE)
        loadBackupHistory()
    }

    override fun onStop() {
        super.onStop()
        (activity as? MainActivity)?.setBottomNavVisibility(View.VISIBLE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.setBottomNavVisibility(View.VISIBLE)
        _binding = null
    }

    private fun initEnvironment() {
        val context = requireContext()

        // Initial state while checking root
        viewBinding.btnBackup.isEnabled = false
        viewBinding.btnRestore.isEnabled = false
        viewBinding.btnBackup.alpha = 0.5f
        viewBinding.btnRestore.alpha = 0.5f

        // 1. Check Root with Strict UID=0 verification
        StickerSyncManager.isRootAvailable { hasRoot ->
            isRootGranted = hasRoot
            activity?.runOnUiThread {
                if (hasRoot) {
                    appendLog("🔒 Status Root: Superuser Aktif & Terverifikasi (UID=0)", StickerSyncManager.LogLevel.SUCCESS)
                    if (selectedPackage.isNotEmpty()) {
                        viewBinding.btnBackup.isEnabled = true
                        viewBinding.btnRestore.isEnabled = true
                        viewBinding.btnBackup.alpha = 1.0f
                        viewBinding.btnRestore.alpha = 1.0f
                    }
                } else {
                    appendLog("❌ Status Root: Akses Root DITOLAK / Belum diizinkan di Magisk/KernelSU/APatch.", StickerSyncManager.LogLevel.ERROR)
                    viewBinding.btnBackup.isEnabled = false
                    viewBinding.btnRestore.isEnabled = false
                    viewBinding.btnBackup.alpha = 0.4f
                    viewBinding.btnRestore.alpha = 0.4f
                }
            }
        }

        // 2. Detect WhatsApp Versions & Configure Clickability
        val stdInstalled = StickerSyncManager.isPackageInstalled(context, StickerSyncManager.PACKAGE_WHATSAPP)
        val stdVer = StickerSyncManager.getInstalledWhatsAppVersion(context, StickerSyncManager.PACKAGE_WHATSAPP)
        if (stdInstalled) {
            viewBinding.tvStandardVersion.text = "Versi terpasang: $stdVer"
            viewBinding.layoutPkgStandard.isEnabled = true
            viewBinding.layoutPkgStandard.isClickable = true
            viewBinding.rbStandard.isEnabled = true
            viewBinding.layoutPkgStandard.alpha = 1.0f
        } else {
            viewBinding.tvStandardVersion.text = "Tidak terpasang di perangkat"
            viewBinding.layoutPkgStandard.isEnabled = false
            viewBinding.layoutPkgStandard.isClickable = false
            viewBinding.rbStandard.isEnabled = false
            viewBinding.layoutPkgStandard.alpha = 0.4f
        }

        val busInstalled = StickerSyncManager.isPackageInstalled(context, StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS)
        val busVer = StickerSyncManager.getInstalledWhatsAppVersion(context, StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS)
        if (busInstalled) {
            viewBinding.tvBusinessVersion.text = "Versi terpasang: $busVer"
            viewBinding.layoutPkgBusiness.isEnabled = true
            viewBinding.layoutPkgBusiness.isClickable = true
            viewBinding.rbBusiness.isEnabled = true
            viewBinding.layoutPkgBusiness.alpha = 1.0f
        } else {
            viewBinding.tvBusinessVersion.text = "Tidak terpasang di perangkat"
            viewBinding.layoutPkgBusiness.isEnabled = false
            viewBinding.layoutPkgBusiness.isClickable = false
            viewBinding.rbBusiness.isEnabled = false
            viewBinding.layoutPkgBusiness.alpha = 0.4f
        }

        if (stdInstalled) {
            viewBinding.rbStandard.isChecked = true
            viewBinding.rbBusiness.isChecked = false
            selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP
        } else if (busInstalled) {
            viewBinding.rbBusiness.isChecked = true
            viewBinding.rbStandard.isChecked = false
            selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS
        } else {
            viewBinding.rbStandard.isChecked = false
            viewBinding.rbBusiness.isChecked = false
            selectedPackage = ""
            viewBinding.btnBackup.isEnabled = false
            viewBinding.btnRestore.isEnabled = false
            viewBinding.btnBackup.alpha = 0.4f
            viewBinding.btnRestore.alpha = 0.4f
            appendLog("⚠️ WhatsApp / WhatsApp Business tidak terpasang di perangkat ini.", StickerSyncManager.LogLevel.WARNING)
        }
    }

    private fun setupTabs() {
        viewBinding.tabNavSync.setOnClickListener {
            switchTab(0)
        }

        viewBinding.tabNavHistory.setOnClickListener {
            switchTab(1)
        }

        viewBinding.btnEmptyCreateBackup.setOnClickListener {
            switchTab(0)
        }
    }

    private fun switchTab(tabIndex: Int) {
        val context = requireContext()
        val greenColor = ContextCompat.getColor(context, R.color.whatsapp_green)
        val secondaryColor = ContextCompat.getColor(context, R.color.text_secondary)

        if (tabIndex == 0) {
            viewBinding.containerSyncTab.visibility = View.VISIBLE
            viewBinding.containerHistoryTab.visibility = View.GONE

            viewBinding.tabNavSync.setBackgroundResource(R.drawable.bg_sticker_tab_active)
            viewBinding.ivNavSync.setColorFilter(greenColor)
            viewBinding.tvNavSync.setTextColor(greenColor)
            viewBinding.tvNavSync.typeface = android.graphics.Typeface.DEFAULT_BOLD

            viewBinding.tabNavHistory.setBackgroundResource(android.R.color.transparent)
            viewBinding.ivNavHistory.setColorFilter(secondaryColor)
            viewBinding.tvNavHistory.setTextColor(secondaryColor)
            viewBinding.tvNavHistory.typeface = android.graphics.Typeface.DEFAULT
        } else {
            viewBinding.containerSyncTab.visibility = View.GONE
            viewBinding.containerHistoryTab.visibility = View.VISIBLE

            viewBinding.tabNavHistory.setBackgroundResource(R.drawable.bg_sticker_tab_active)
            viewBinding.ivNavHistory.setColorFilter(greenColor)
            viewBinding.tvNavHistory.setTextColor(greenColor)
            viewBinding.tvNavHistory.typeface = android.graphics.Typeface.DEFAULT_BOLD

            viewBinding.tabNavSync.setBackgroundResource(android.R.color.transparent)
            viewBinding.ivNavSync.setColorFilter(secondaryColor)
            viewBinding.tvNavSync.setTextColor(secondaryColor)
            viewBinding.tvNavSync.typeface = android.graphics.Typeface.DEFAULT

            loadBackupHistory()
        }
    }

    private fun setupHistoryRecyclerView() {
        backupAdapter = StickerBackupAdapter(
            items = backupList,
            onRestore = { item ->
                startRestoreFromBackupFile(item.file)
            },
            onShare = { item ->
                if (item.isZip) {
                    val shareIntent = StickerSyncManager.createShareIntent(requireContext(), item.file)
                    startActivity(Intent.createChooser(shareIntent, "Bagikan Cadangan Stiker"))
                } else {
                    Toast.makeText(requireContext(), "Hanya file ZIP yang dapat dibagikan langsung", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { item ->
                confirmDeleteBackup(item)
            }
        )

        viewBinding.rvBackupHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = backupAdapter
        }
    }

    private fun loadBackupHistory() {
        Thread {
            val backups = StickerSyncManager.getAvailableBackups()
            activity?.runOnUiThread {
                backupList.clear()
                backupList.addAll(backups)
                backupAdapter.notifyDataSetChanged()

                if (backups.isEmpty()) {
                    viewBinding.layoutEmptyHistory.visibility = View.VISIBLE
                    viewBinding.rvBackupHistory.visibility = View.GONE
                } else {
                    viewBinding.layoutEmptyHistory.visibility = View.GONE
                    viewBinding.rvBackupHistory.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun confirmDeleteBackup(item: StickerSyncManager.BackupItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus Cadangan?")
            .setMessage("Apakah Anda yakin ingin menghapus cadangan '${item.name}'? Tindakan ini tidak dapat dibatalkan.")
            .setPositiveButton("Hapus") { _, _ ->
                if (item.file.isDirectory) {
                    item.file.deleteRecursively()
                } else {
                    item.file.delete()
                }
                loadBackupHistory()
                Toast.makeText(requireContext(), "File cadangan dihapus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setupListeners() {
        // Target package selector
        viewBinding.layoutPkgStandard.setOnClickListener {
            if (selectedPackage != StickerSyncManager.PACKAGE_WHATSAPP &&
                StickerSyncManager.isPackageInstalled(requireContext(), StickerSyncManager.PACKAGE_WHATSAPP)) {
                viewBinding.rbStandard.isChecked = true
                viewBinding.rbBusiness.isChecked = false
                selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP
                appendLog("🎯 Target dipilih: WhatsApp (${StickerSyncManager.PACKAGE_WHATSAPP})")
            }
        }

        viewBinding.layoutPkgBusiness.setOnClickListener {
            if (selectedPackage != StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS &&
                StickerSyncManager.isPackageInstalled(requireContext(), StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS)) {
                viewBinding.rbBusiness.isChecked = true
                viewBinding.rbStandard.isChecked = false
                selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS
                appendLog("🎯 Target dipilih: WhatsApp Business (${StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS})")
            }
        }

        viewBinding.rbStandard.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewBinding.rbBusiness.isChecked = false
                selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP
            }
        }

        viewBinding.rbBusiness.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewBinding.rbStandard.isChecked = false
                selectedPackage = StickerSyncManager.PACKAGE_WHATSAPP_BUSINESS
            }
        }

        // Backup Action
        viewBinding.btnBackup.setOnClickListener {
            startBackup()
        }

        // Restore Action
        viewBinding.btnRestore.setOnClickListener {
            startRestorePicker()
        }

        // Share Action
        viewBinding.btnShare.setOnClickListener {
            val zip = lastBackupZip
            if (zip != null && zip.exists()) {
                val shareIntent = StickerSyncManager.createShareIntent(requireContext(), zip)
                startActivity(Intent.createChooser(shareIntent, "Bagikan Cadangan Stiker"))
            } else {
                Toast.makeText(requireContext(), "File cadangan tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
        }

        // Open WhatsApp Action
        viewBinding.btnOpenApp.setOnClickListener {
            val pm = requireContext().packageManager
            val intent = pm.getLaunchIntentForPackage(selectedPackage)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Gagal membuka aplikasi target", Toast.LENGTH_SHORT).show()
            }
        }

        // Copy Log Action
        viewBinding.btnCopyLog.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("StickerSync Log", logBuilder.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Log disalin ke papan klip", Toast.LENGTH_SHORT).show()
        }

        // Clear Log Action
        viewBinding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            viewBinding.tvConsoleLog.text = "[READY] Log dibersihkan.\n"
        }
    }

    private fun startBackup() {
        if (!isRootGranted) {
            showErrorDialog("Akses Root Diperlukan", "Fitur ini memerlukan akses root (KernelSU / Magisk / APatch) untuk menyalin database privat stiker WhatsApp.")
            return
        }

        val context = requireContext()
        setUiBusy(true, "Memulai pencadangan database stiker...")

        StickerSyncManager.backupStickers(context, selectedPackage, object : StickerSyncManager.ProgressCallback {
            override fun onLog(message: String, level: StickerSyncManager.LogLevel) {
                activity?.runOnUiThread {
                    appendLog(message, level)
                }
            }

            override fun onProgress(step: Int, totalSteps: Int, description: String) {
                activity?.runOnUiThread {
                    viewBinding.tvProgressText.text = "Langkah $step/$totalSteps: $description"
                    val progress = (step.toFloat() / totalSteps.toFloat() * 100).toInt()
                    viewBinding.progressIndicator.progress = progress
                }
            }

            override fun onCompleted(success: Boolean, resultData: Any?) {
                activity?.runOnUiThread {
                    setUiBusy(false)
                    if (success && resultData is StickerSyncManager.BackupInfo) {
                        lastBackupZip = resultData.zipFile
                        viewBinding.layoutSecondaryActions.visibility = View.VISIBLE
                        viewBinding.btnShare.visibility = if (resultData.zipFile != null) View.VISIBLE else View.GONE
                        loadBackupHistory()

                        MaterialAlertDialogBuilder(context)
                            .setTitle("✅ Cadangan Berhasil")
                            .setMessage("Database stiker favorit berhasil diekspor!\n\n📁 Lokasi:\n${resultData.sourceDir.absolutePath}\n\n🎁 File ZIP:\n${resultData.zipFile?.name ?: "-"}")
                            .setPositiveButton("Lihat Hasil") { _, _ ->
                                switchTab(1)
                            }
                            .setNeutralButton("Bagikan") { _, _ ->
                                resultData.zipFile?.let { zip ->
                                    startActivity(Intent.createChooser(StickerSyncManager.createShareIntent(context, zip), "Bagikan Cadangan Stiker"))
                                }
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                    } else {
                        Toast.makeText(context, "Pencadangan gagal. Periksa log terminal.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun startRestorePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        restoreFilePicker.launch(intent)
    }

    private fun handleSelectedRestoreUri(uri: Uri) {
        val context = requireContext()
        val fileName = getFileNameFromUri(uri) ?: "cadangan_stiker.zip"

        setUiBusy(true, "Membaca file cadangan...")

        Thread {
            try {
                val tempZip = File(context.cacheDir, "imported_${System.currentTimeMillis()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
                activity?.runOnUiThread {
                    startRestoreFromBackupFile(tempZip)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    setUiBusy(false)
                    showErrorDialog("Gagal Mengimpor File", "Tidak dapat membaca file ZIP: ${e.localizedMessage}")
                }
            }
        }.start()
    }

    private fun startRestoreFromBackupFile(file: File) {
        if (!isRootGranted) {
            showErrorDialog("Akses Root Diperlukan", "Fitur pemulihan memerlukan akses root untuk menyalin database ke folder privat WhatsApp.")
            return
        }

        val context = requireContext()
        setUiBusy(true, "Memeriksa kecocokan versi...")

        Thread {
            val backupInfo = StickerSyncManager.inspectBackup(file)
            activity?.runOnUiThread {
                setUiBusy(false)
                promptRestoreModeAndExecute(file, backupInfo)
            }
        }.start()
    }

    private fun promptRestoreModeAndExecute(file: File, backupInfo: StickerSyncManager.BackupInfo?) {
        val context = requireContext()
        val currentVer = StickerSyncManager.getInstalledWhatsAppVersion(context, selectedPackage) ?: "unknown"
        val backupVer = backupInfo?.versionName ?: "unknown"
        val isMismatch = backupInfo != null && currentVer != backupVer && backupVer != "unknown"

        val message = StringBuilder().apply {
            append("Pilih metode pemulihan database stiker:\n\n")
            if (backupInfo != null) {
                append("📦 Asal Cadangan: ${backupInfo.packageName} (v${backupInfo.versionName})\n")
                append("🎯 Target Pasang: $selectedPackage (v$currentVer)\n\n")
            }
            if (isMismatch) {
                append("⚠️ PERINGATAN: Versi WhatsApp cadangan (v$backupVer) berbeda dengan versi yang terpasang (v$currentVer). Disarankan menggunakan mode GABUNGKAN (Merge).\n\n")
            }
            append("• Gabungkan (Merge): Menambahkan stiker cadangan ke stiker favorit yang sudah ada tanpa menghapus stiker lama.\n")
            append("• Timpa Total (Replace): Mengganti seluruh stiker dengan data dari cadangan.")
        }.toString()

        MaterialAlertDialogBuilder(context)
            .setTitle("Pulihkan Database Stiker")
            .setMessage(message)
            .setPositiveButton("Gabungkan (Merge)") { _, _ ->
                executeRestore(file, mergeMode = true)
            }
            .setNeutralButton("Timpa Total (Replace)") { _, _ ->
                executeRestore(file, mergeMode = false)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun executeRestore(file: File, mergeMode: Boolean) {
        val context = requireContext()
        if (viewBinding.containerHistoryTab.visibility == View.VISIBLE) {
            switchTab(0)
        }
        setUiBusy(true, "Memulihkan stiker...")

        StickerSyncManager.restoreStickers(context, selectedPackage, file, mergeMode, object : StickerSyncManager.ProgressCallback {
            override fun onLog(message: String, level: StickerSyncManager.LogLevel) {
                activity?.runOnUiThread {
                    appendLog(message, level)
                }
            }

            override fun onProgress(step: Int, totalSteps: Int, description: String) {
                activity?.runOnUiThread {
                    viewBinding.tvProgressText.text = "Langkah $step/$totalSteps: $description"
                    val progress = (step.toFloat() / totalSteps.toFloat() * 100).toInt()
                    viewBinding.progressIndicator.progress = progress
                }
            }

            override fun onCompleted(success: Boolean, resultData: Any?) {
                activity?.runOnUiThread {
                    setUiBusy(false)
                    if (success) {
                        viewBinding.layoutSecondaryActions.visibility = View.VISIBLE
                        viewBinding.btnOpenApp.visibility = View.VISIBLE

                        MaterialAlertDialogBuilder(context)
                            .setTitle("🎉 Pemulihan Berhasil!")
                            .setMessage("Database stiker favorit berhasil dipasang ke $selectedPackage.\n\nSilakan buka WhatsApp dan periksa tab Stiker Favorit (⭐) Anda!")
                            .setPositiveButton("Buka WhatsApp") { _, _ ->
                                val intent = context.packageManager.getLaunchIntentForPackage(selectedPackage)
                                if (intent != null) startActivity(intent)
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                    } else {
                        Toast.makeText(context, "Pemulihan gagal. Periksa log konsol.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun setUiBusy(busy: Boolean, description: String = "") {
        viewBinding.btnBackup.isEnabled = !busy
        viewBinding.btnRestore.isEnabled = !busy
        viewBinding.layoutProgress.visibility = if (busy) View.VISIBLE else View.GONE
        viewBinding.tvProgressText.text = description
        if (busy) {
            viewBinding.progressIndicator.isIndeterminate = false
            viewBinding.progressIndicator.progress = 0
        }
    }

    private fun appendLog(text: String, level: StickerSyncManager.LogLevel = StickerSyncManager.LogLevel.INFO) {
        val color = when (level) {
            StickerSyncManager.LogLevel.SUCCESS -> 0xFF3DDC84.toInt() // Mint Green
            StickerSyncManager.LogLevel.WARNING -> 0xFFFFD166.toInt() // Warm Yellow
            StickerSyncManager.LogLevel.ERROR -> 0xFFFF6B6B.toInt()   // Bright Red
            StickerSyncManager.LogLevel.INFO -> 0xFFE6EDF3.toInt()    // Terminal White
        }

        val start = logBuilder.length
        logBuilder.append(text).append("\n")
        logBuilder.setSpan(ForegroundColorSpan(color), start, logBuilder.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)

        viewBinding.tvConsoleLog.text = logBuilder
        viewBinding.logScrollView.post {
            viewBinding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }

        when (level) {
            StickerSyncManager.LogLevel.ERROR -> android.util.Log.e("StickerSync", text)
            StickerSyncManager.LogLevel.WARNING -> android.util.Log.w("StickerSync", text)
            else -> android.util.Log.d("StickerSync", text)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // =========================================================
    // RECYCLERVIEW ADAPTER FOR BACKUP HISTORY LIST
    // =========================================================
    private class StickerBackupAdapter(
        private val items: List<StickerSyncManager.BackupItem>,
        private val onRestore: (StickerSyncManager.BackupItem) -> Unit,
        private val onShare: (StickerSyncManager.BackupItem) -> Unit,
        private val onDelete: (StickerSyncManager.BackupItem) -> Unit
    ) : RecyclerView.Adapter<StickerBackupAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemStickerBackupCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStickerBackupCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.binding) {
                tvBackupTitle.text = item.name
                tvBackupDate.text = item.dateFormatted
                tvBackupSize.text = item.sizeFormatted
                tvBackupPkg.text = item.packageName
                tvBackupVersion.text = "v${item.versionName}"

                btnItemShare.visibility = if (item.isZip) View.VISIBLE else View.GONE

                btnItemRestore.setOnClickListener { onRestore(item) }
                btnItemShare.setOnClickListener { onShare(item) }
                btnItemDelete.setOnClickListener { onDelete(item) }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
