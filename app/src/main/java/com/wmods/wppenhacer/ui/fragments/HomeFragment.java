package com.wmods.wppenhacer.ui.fragments;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.MainActivity;
import com.wmods.wppenhacer.adapter.LogLineAdapter;
import com.wmods.wppenhacer.databinding.DialogDiagnosticsLogBinding;
import com.wmods.wppenhacer.databinding.DialogUpdateAvailableBinding;
import com.wmods.wppenhacer.databinding.FragmentHomeBinding;
import io.noties.markwon.Markwon;
import com.wmods.wppenhacer.ui.fragments.base.BaseFragment;
import com.wmods.wppenhacer.utils.FilePicker;
import com.wmods.wppenhacer.utils.RootDiagnostics;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import rikka.core.util.IOUtils;

public class HomeFragment extends BaseFragment {

    private FragmentHomeBinding binding;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        var intentFilter = new IntentFilter(BuildConfig.APPLICATION_ID + ".RECEIVER_WPP");
        ContextCompat.registerReceiver(requireContext(), new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (FeatureLoader.PACKAGE_WPP.equals(intent.getStringExtra("PKG")))
                        receiverBroadcastWpp(context, intent);
                    else
                        receiverBroadcastBusiness(context, intent);
                } catch (Exception ignored) {
                }
            }
        }, intentFilter, ContextCompat.RECEIVER_EXPORTED);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);

        checkStateWpp(requireActivity());
        checkRootStatus();

        binding.rebootBtn.setOnClickListener(view -> {
            animateClick(view);
            App.instance.restartApp(FeatureLoader.PACKAGE_WPP);
            disableWpp(requireActivity());
        });

        binding.scrollDiagBtn.setOnClickListener(view -> {
            animateClick(view);
            binding.nestedScrollView.post(() -> binding.nestedScrollView.smoothScrollTo(0, binding.diagCard.getTop()));
        });

        binding.rebootBtn2.setOnClickListener(view -> {
            animateClick(view);
            App.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS);
            disableBusiness(requireActivity());
        });

        binding.exportBtn.setOnClickListener(view -> {
            animateClick(view);
            saveConfigs(this.getContext());
        });

        binding.importBtn.setOnClickListener(view -> {
            animateClick(view);
            importConfigs(this.getContext());
        });

        binding.resetBtn.setOnClickListener(view -> {
            animateClick(view);
            resetConfigs(this.getContext());
        });

        binding.diagBtn.setOnClickListener(view -> {
            animateClick(view);
            showDiagnosticsDialog();
        });

        binding.updateCard.setOnClickListener(view -> {
            animateClick(view);
            showChangelogDialog();
        });

        var prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        if (prefs.getBoolean("update_check", true)) {
            checkForUpdates();
        } else {
            binding.updateCard.setVisibility(View.GONE);
        }

        return binding.getRoot();
    }

    private void checkRootStatus() {
        if (getContext() == null || binding == null) return;
        var context = requireContext();

        binding.heroRootBadge.setText("● ROOT CHECK...");
        binding.heroRootBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        binding.heroRootBadge.setBackgroundResource(R.drawable.category_badge_background);

        com.wmods.wppenhacer.utils.StickerSyncManager.INSTANCE.isRootAvailable(hasRoot -> {
            var activity = getActivity();
            if (activity == null || binding == null) return kotlin.Unit.INSTANCE;
            activity.runOnUiThread(() -> {
                if (Boolean.TRUE.equals(hasRoot)) {
                    binding.heroRootBadge.setText("● ROOT ACTIVE");
                    binding.heroRootBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_green_text));
                    binding.heroRootBadge.setBackgroundResource(R.drawable.category_badge_background);
                } else {
                    binding.heroRootBadge.setText("● NO ROOT");
                    binding.heroRootBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_red_text));
                    binding.heroRootBadge.setBackgroundResource(R.drawable.category_badge_error_background);
                }
            });
            return kotlin.Unit.INSTANCE;
        });
    }

    private void animateClick(View view) {
        var scaleIn = AnimationUtils.loadAnimation(getContext(), R.anim.scale_in);
        view.startAnimation(scaleIn);
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
        if (getActivity() != null) {
            checkStateWpp(requireActivity());
        }
        checkRootStatus();
    }

    @SuppressLint("StringFormatInvalid")
    private void receiverBroadcastBusiness(Context context, Intent intent) {
        if (App.isOriginalPackage()) binding.status3.setVisibility(View.VISIBLE);
        binding.statusTitle3.setText("WhatsApp Business Background");
        var version = intent.getStringExtra("VERSION");
        if (version != null) {
            binding.statusSummary3.setText("Versi " + version);
            binding.listBusiness.setText(version);
        }
        binding.rebootBtn2.setVisibility(View.VISIBLE);
        binding.statusSummary3.setVisibility(View.VISIBLE);
        binding.statusIcon3.setImageResource(R.drawable.ic_check_circle);
    }

    @SuppressLint("StringFormatInvalid")
    private void receiverBroadcastWpp(Context context, Intent intent) {
        binding.statusTitle2.setText("WhatsApp Background");
        var version = intent.getStringExtra("VERSION");
        if (version != null) {
            binding.statusSummary1.setText("Versi " + version);
            binding.listWpp.setText(version);
        }
        binding.rebootBtn.setVisibility(View.VISIBLE);
        binding.statusSummary1.setVisibility(View.VISIBLE);
        binding.statusIcon2.setImageResource(R.drawable.ic_check_circle);
    }

    private void resetConfigs(Context context) {
        var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.getAll().forEach((key, value) -> prefs.edit().remove(key).apply());
        App.instance.restartApp(FeatureLoader.PACKAGE_WPP);
        App.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS);
        Utils.showToast(context.getString(R.string.configs_reset), Toast.LENGTH_SHORT);
    }

    private static @NonNull JSONObject getJsonObject(SharedPreferences prefs) throws JSONException {
        var entries = prefs.getAll();
        var JSOjsonObject = new JSONObject();
        for (var entry : entries.entrySet()) {
            var type = new JSONObject();
            var keyValue = entry.getValue();
            if (keyValue instanceof HashSet<?> hashSet) {
                keyValue = new JSONArray(new ArrayList<>(hashSet));
            }
            type.put("type", keyValue.getClass().getSimpleName());
            type.put("value", keyValue);
            JSOjsonObject.put(entry.getKey(), type);
        }
        return JSOjsonObject;
    }

    private void saveConfigs(Context context) {
        FilePicker.setOnUriPickedListener((uri) -> {
            try {
                try (var output = context.getContentResolver().openOutputStream(uri)) {
                    var prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    var JSOjsonObject = getJsonObject(prefs);
                    Objects.requireNonNull(output).write(JSOjsonObject.toString(4).getBytes());
                }
                Toast.makeText(context, context.getString(R.string.configs_saved), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        String formattedDate = dateFormat.format(new Date());
        FilePicker.fileSalve.launch("wpp_enhacer_configs_" + formattedDate + ".json");
    }

    private void importConfigs(Context context) {
        FilePicker.setOnUriPickedListener((uri) -> {
            try {
                try (var input = context.getContentResolver().openInputStream(uri)) {
                    var data = IOUtils.toString(input);
                    var prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    var jsonObject = new JSONObject(data);
                    prefs.getAll().forEach((key, value) -> prefs.edit().remove(key).apply());
                    var key = jsonObject.keys();
                    while (key.hasNext()) {
                        var keyName = key.next();
                        var value = jsonObject.get(keyName);
                        var type = value.getClass().getSimpleName();
                        if (value instanceof JSONObject valueJson) {
                            value = valueJson.get("value");
                            type = valueJson.getString("type");
                        }

                        if (type.equals(JSONArray.class.getSimpleName())) {
                            var jsonArray = (JSONArray) value;
                            HashSet<String> hashSet = new HashSet<>();
                            for (var i = 0; i < jsonArray.length(); i++) {
                                hashSet.add(jsonArray.getString(i));
                            }
                            prefs.edit().putStringSet(keyName, hashSet).apply();
                        } else if (type.equals(String.class.getSimpleName())) {
                            prefs.edit().putString(keyName, (String) value).apply();
                        } else if (type.equals(Boolean.class.getSimpleName())) {
                            prefs.edit().putBoolean(keyName, (boolean) value).apply();
                        } else if (type.equals(Integer.class.getSimpleName())) {
                            prefs.edit().putInt(keyName, (int) value).apply();
                        } else if (type.equals(Long.class.getSimpleName())) {
                            prefs.edit().putLong(keyName, (long) value).apply();
                        } else if (type.equals(Double.class.getSimpleName())) {
                            prefs.edit().putFloat(keyName, Float.parseFloat(String.valueOf(value))).apply();
                        } else if (type.equals(Float.class.getSimpleName())) {
                            prefs.edit().putFloat(keyName, Float.parseFloat(String.valueOf(value))).apply();
                        }
                    }
                }
                Toast.makeText(context, context.getString(R.string.configs_imported), Toast.LENGTH_SHORT).show();
                App.instance.restartApp(FeatureLoader.PACKAGE_WPP);
                App.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS);
            } catch (Exception e) {
                Log.e("importConfigs", e.getMessage(), e);
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        FilePicker.fileCapture.launch(new String[]{"application/json"});
    }

    @SuppressLint("StringFormatInvalid")
    private void checkStateWpp(FragmentActivity activity) {
        // Automatically format dynamic version string: e.g. "1.5.7 (EFB7AAB0)" -> "1.5.7 · EFB7AAB0"
        var formattedVersion = BuildConfig.VERSION_NAME.replace(" (", " · ").replace(")", "");
        binding.heroVersionText.setText(formattedVersion);

        var context = activity;
        if (MainActivity.isXposedEnabled()) {
            binding.status.setStrokeColor(ContextCompat.getColor(context, R.color.hero_border_active));
            binding.statusIconBox.setBackgroundResource(R.drawable.bg_badge_category);
            binding.statusIcon.setImageResource(R.drawable.ic_round_check_circle_24);
            binding.statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.whatsapp_green)));
            binding.statusTitle.setText(R.string.module_enabled);
            binding.statusSummary.setText("Hook LSPosed berhasil dimuat");
            binding.heroBgImage.setImageResource(R.drawable.hero_active);
            binding.heroActiveBadge.setText("● ACTIVE");
            binding.heroActiveBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_green_text));
            binding.heroActiveBadge.setBackgroundResource(R.drawable.category_badge_background);
            binding.heroLsposedBadge.setText("● LSPosed Hook");
            binding.heroLsposedBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_green_text));
            binding.heroLsposedBadge.setBackgroundResource(R.drawable.category_badge_background);
            binding.modSubtext.setText("Wa Enhancer " + BuildConfig.VERSION_NAME + " · Aktif");
            binding.modActiveBadge.setText("ACTIVE");
            binding.modActiveBadge.setBackgroundResource(R.drawable.category_badge_background);
            binding.modActiveBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_green_text));
        } else {
            binding.status.setStrokeColor(ContextCompat.getColor(context, R.color.hero_border_inactive));
            binding.statusIconBox.setBackgroundResource(R.drawable.bg_badge_error_category);
            binding.statusIcon.setImageResource(R.drawable.ic_round_error_outline_24);
            binding.statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.badge_red_text)));
            binding.statusTitle.setText(R.string.module_disabled);
            binding.statusSummary.setText("Modul belum diaktifkan di LSPosed");
            binding.heroBgImage.setImageResource(R.drawable.hero_inactive);
            binding.heroActiveBadge.setText("● INACTIVE");
            binding.heroActiveBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_red_text));
            binding.heroActiveBadge.setBackgroundResource(R.drawable.category_badge_error_background);
            binding.heroLsposedBadge.setText("● LSPosed Inactive");
            binding.heroLsposedBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_red_text));
            binding.heroLsposedBadge.setBackgroundResource(R.drawable.category_badge_error_background);
            binding.modSubtext.setText("Wa Enhancer " + BuildConfig.VERSION_NAME + " · Tidak Aktif");
            binding.modActiveBadge.setText("INACTIVE");
            binding.modActiveBadge.setBackgroundResource(R.drawable.category_badge_error_background);
            binding.modActiveBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_red_text));
        }

        if (isInstalled(FeatureLoader.PACKAGE_WPP) && App.isOriginalPackage()) {
            disableWpp(activity);
        } else {
            binding.status2.setVisibility(View.GONE);
        }
        if (App.isOriginalPackage())
            binding.status3.setVisibility(View.GONE);

        checkWpp(activity);

        // Auto-detect Device Hardware & Android OS level
        binding.deviceName.setText(Build.MANUFACTURER);
        binding.sdk.setText(String.valueOf(Build.VERSION.SDK_INT));
        binding.modelName.setText(Build.MODEL != null ? Build.MODEL : Build.DEVICE);

        // Auto-detect installed WhatsApp versions
        try {
            var pInfo = App.instance.getPackageManager().getPackageInfo(FeatureLoader.PACKAGE_WPP, 0);
            binding.listWpp.setText(pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            binding.listWpp.setText("Tidak Terpasang");
        }

        try {
            var pInfoBiz = App.instance.getPackageManager().getPackageInfo(FeatureLoader.PACKAGE_BUSINESS, 0);
            binding.listBusiness.setText(pInfoBiz.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            binding.listBusiness.setText("Tidak Terpasang");
        }
    }

    private boolean isInstalled(String packageWpp) {
        try {
            App.instance.getPackageManager().getPackageInfo(packageWpp, 0);
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void disableBusiness(FragmentActivity activity) {
        binding.statusIcon3.setImageResource(R.drawable.ic_round_error_outline_24);
        binding.statusTitle3.setText("WhatsApp Business (Nonaktif)");
        binding.statusSummary3.setText(R.string.business_is_not_running_or_has_not_been_activated_in_lsposed);
        binding.rebootBtn2.setVisibility(View.GONE);
    }

    private void disableWpp(FragmentActivity activity) {
        binding.statusIcon2.setImageResource(R.drawable.ic_round_error_outline_24);
        binding.statusTitle2.setText("WhatsApp (Nonaktif)");
        binding.statusSummary1.setText(R.string.whatsapp_is_not_running_or_has_not_been_activated_in_lsposed);
        binding.rebootBtn.setVisibility(View.GONE);
    }

    private static void checkWpp(FragmentActivity activity) {
        Intent checkWpp = new Intent(BuildConfig.APPLICATION_ID + ".CHECK_WPP");
        activity.sendBroadcast(checkWpp);
    }

    private boolean isUpdateAvailable = false;
    private String latestVersionName = null;
    private String latestReleaseUrl = null;
    private String latestApkUrl = null;
    private String latestChangelog = null;
    private String latestReleaseDate = null;

    private void checkForUpdates() {
        var context = getContext();
        if (context == null) return;

        binding.updateSummary.setText(getString(R.string.current_version_s, BuildConfig.VERSION_NAME));

        new Thread(() -> {
            try {
                var client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                var request = new Request.Builder()
                        .url("https://api.github.com/repos/Luckyfr1945/WaEnhancer-IOS/releases/latest")
                        .build();

                try (var response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        updateCardState(false, false, null);
                        return;
                    }

                    var body = response.body();
                    var content = body.string();
                    var release = new JSONObject(content);
                    var tagName = release.optString("tag_name", "");

                    if (tagName.isBlank()) {
                        updateCardState(true, true, null);
                        return;
                    }

                    String htmlUrl = release.optString("html_url", "https://github.com/Luckyfr1945/WaEnhancer-IOS/releases/latest");
                    String releaseBody = release.optString("body", "");
                    String publishedAt = release.optString("published_at", "");

                    String apkUrl = null;
                    if (release.has("assets")) {
                        var assets = release.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                var asset = assets.optJSONObject(i);
                                if (asset != null) {
                                    String downloadUrl = asset.optString("browser_download_url", "");
                                    if (downloadUrl.endsWith(".apk")) {
                                        apkUrl = downloadUrl;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    this.latestVersionName = tagName;
                    this.latestReleaseUrl = htmlUrl;
                    this.latestApkUrl = apkUrl != null ? apkUrl : htmlUrl;
                    this.latestChangelog = releaseBody.isBlank() ? null : releaseBody;

                    if (!publishedAt.isBlank()) {
                        try {
                            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                            isoFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            Date date = isoFormat.parse(publishedAt);
                            if (date != null) {
                                this.latestReleaseDate = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (this.latestReleaseDate == null) {
                        this.latestReleaseDate = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date());
                    }

                    var hash = tagName.contains("-") ? tagName.split("-")[1].trim() : tagName;
                    var isNewVersion = !BuildConfig.VERSION_NAME.toLowerCase().contains(hash.toLowerCase());
                    this.isUpdateAvailable = isNewVersion;

                    updateCardState(true, !isNewVersion, tagName);
                }
            } catch (Exception e) {
                updateCardState(false, false, null);
            }
        }).start();
    }

    private void updateCardState(boolean success, boolean isUpToDate, @Nullable String newVersion) {
        var activity = getActivity();
        if (activity == null || !isAdded()) return;

        activity.runOnUiThread(() -> {
            if (binding == null) return;

            if (!success) {
                binding.updateIcon.setImageResource(R.drawable.ic_round_error_outline_24);
                binding.updateTitle.setText(R.string.update_check_failed);
                binding.updateSummary.setText(R.string.update_check_failed_summary);
                binding.updateBadge.setVisibility(View.GONE);
            } else if (isUpToDate) {
                binding.updateIcon.setImageResource(R.drawable.ic_round_check_circle_24);
                binding.updateTitle.setText(R.string.up_to_date);
                binding.updateSummary.setText(getString(R.string.current_version_s, BuildConfig.VERSION_NAME));
                binding.updateBadge.setText("UP TO DATE");
                binding.updateBadge.setVisibility(View.VISIBLE);
            } else {
                binding.updateIcon.setImageResource(R.drawable.ic_round_update_24);
                binding.updateTitle.setText(R.string.update_available);
                binding.updateSummary.setText(getString(R.string.update_available_summary, newVersion));
                binding.updateBadge.setText("UPDATE");
                binding.updateBadge.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showDiagnosticsDialog() {
        var context = requireContext();
        var dialogBinding = DialogDiagnosticsLogBinding.inflate(LayoutInflater.from(context));
        var adapter = new LogLineAdapter();

        dialogBinding.logRecycler.setLayoutManager(new LinearLayoutManager(context));
        dialogBinding.logRecycler.setAdapter(adapter);

        var dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.diag_dialog_title)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.diag_close, null)
                .setCancelable(true)
                .show();

        var handler = new Handler(Looper.getMainLooper());
        var queue = new java.util.ArrayList<RootDiagnostics.LogEntry>();

        RootDiagnostics.INSTANCE.runDiagnostics(context, entry -> {
            if (!isAdded()) return;
            queue.add(entry);
        });

        Runnable poller = new Runnable() {
            private int emptyCycles = 0;

            @Override
            public void run() {
                if (!isAdded() || dialog == null || !dialog.isShowing()) return;

                if (!queue.isEmpty()) {
                    emptyCycles = 0;
                    adapter.add(queue.remove(0));
                    dialogBinding.logRecycler.smoothScrollToPosition(adapter.getItemCount() - 1);
                    handler.postDelayed(this, 120);
                } else if (emptyCycles < 50) {
                    emptyCycles++;
                    handler.postDelayed(this, 120);
                }
            }
        };
        handler.postDelayed(poller, 120);
    }

    private void showChangelogDialog() {
        var context = getContext();
        if (context == null) return;
        var dialogBinding = DialogUpdateAvailableBinding.inflate(LayoutInflater.from(context));

        var markwon = Markwon.create(context);

        if (isUpdateAvailable && latestVersionName != null) {
            dialogBinding.tvUpdateTitle.setText(R.string.update_available);
            dialogBinding.tvUpdateSubtitle.setText("Versi baru siap diunduh");
            dialogBinding.tvVersionBadge.setText(latestVersionName);
            dialogBinding.tvReleaseDate.setText(latestReleaseDate != null ? latestReleaseDate : new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date()));

            String bodyText = (latestChangelog != null && !latestChangelog.isBlank())
                    ? latestChangelog
                    : "Pembaruan versi " + latestVersionName + " telah tersedia. Klik tombol di bawah untuk mengunduh rilis terbaru.";
            dialogBinding.tvChangelog.setText(markwon.toMarkdown(bodyText));

            dialogBinding.btnIgnore.setVisibility(View.VISIBLE);
            dialogBinding.btnIgnore.setText("Nanti");
            dialogBinding.btnUpdate.setText("Unduh Pembaruan");

            var dialog = new MaterialAlertDialogBuilder(context)
                    .setView(dialogBinding.getRoot())
                    .setCancelable(true)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            dialogBinding.btnIgnore.setOnClickListener(v -> dialog.dismiss());
            dialogBinding.btnUpdate.setOnClickListener(v -> {
                dialog.dismiss();
                String targetUrl = latestReleaseUrl != null ? latestReleaseUrl : "https://github.com/Luckyfr1945/WaEnhancer-IOS/releases/latest";
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(context, "Gagal membuka browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

            dialog.show();
        } else {
            dialogBinding.tvUpdateTitle.setText("Catatan Rilis");
            dialogBinding.tvUpdateSubtitle.setText("WaEnhancer iOS Modul");
            dialogBinding.tvVersionBadge.setText("v" + BuildConfig.VERSION_NAME);
            dialogBinding.tvReleaseDate.setText(new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date()));

            String changelogText = (latestChangelog != null && !latestChangelog.isBlank())
                    ? latestChangelog
                    : """
                    ### 🚀 FITUR TERBARU [ADDED]
                    • **Lompat ke Pesan Pertama:** Menu di dalam obrolan chat untuk langsung menuju ke pesan paling awal.
                    • **Hook ConversationScrollApi WhatsApp:** Navigasi cepat dan pemuatan riwayat pesan dinamis dari database.
                    • **Sinkronisasi & Cadangan Stiker:** Cadangkan dan pulihkan stiker WhatsApp/WA Business dengan aman.

                    ### ⚡ PENINGKATAN [IMPROVED]
                    • **Gradasi Halus Hero Card:** Efek transparansi blending menyatu mulus tanpa garis tepi di seluruh ukuran layar.
                    • **Tombol Mic & Kirim Asli:** Mempertahankan warna, bentuk, dan animasi asli WhatsApp.
                    • **Pencarian Fitur & Tab Adaptif:** Menyesuaikan visibilitas fitur berdasarkan status aktif LSPosed dan Root.
                    • **Warna Status Dinamis:** Indikator badge, border, dan icon otomatis hijau saat aktif atau merah saat nonaktif.

                    ### 🐛 PERBAIKAN BUG [FIXED]
                    • **Perbaikan Navigasi Pesan Pertama:** Memperbaiki scroll pesan agar tidak salah memicu quote reply bar.
                    • **Perbaikan Override Privasi Kontak:** Memastikan aturan privasi per-kontak selalu diterapkan secara konsisten.
                    • **Perbaikan Multi-Job Architecture Crash:** Mengatasi kompatibilitas WA Standard dan Business.""";

            dialogBinding.tvChangelog.setText(markwon.toMarkdown(changelogText));

            dialogBinding.btnIgnore.setVisibility(View.GONE);
            dialogBinding.btnUpdate.setText("Tutup");

            var dialog = new MaterialAlertDialogBuilder(context)
                    .setView(dialogBinding.getRoot())
                    .setCancelable(true)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            dialogBinding.btnUpdate.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
