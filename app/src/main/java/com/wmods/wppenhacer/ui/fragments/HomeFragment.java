package com.wmods.wppenhacer.ui.fragments;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

        binding.status.setOnClickListener(view -> {
            animateClick(view);
            showChangelogDialog();
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

    private void animateClick(View view) {
        var scaleIn = AnimationUtils.loadAnimation(getContext(), R.anim.scale_in);
        view.startAnimation(scaleIn);
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
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

        if (MainActivity.isXposedEnabled()) {
            binding.statusIcon.setImageResource(R.drawable.ic_round_check_circle_24);
            binding.statusTitle.setText(R.string.module_enabled);
            binding.statusSummary.setText("Hook LSPosed berhasil dimuat");
            binding.heroBgImage.setImageResource(R.drawable.hero_active);
            binding.heroActiveBadge.setText("● ACTIVE");
            binding.modSubtext.setText("Wa Enhancer " + BuildConfig.VERSION_NAME + " · Aktif");
            binding.modActiveBadge.setText("ACTIVE");
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_round_error_outline_24);
            binding.statusTitle.setText(R.string.module_disabled);
            binding.statusSummary.setText("Modul belum diaktifkan di LSPosed");
            binding.heroBgImage.setImageResource(R.drawable.hero_inactive);
            binding.heroActiveBadge.setText("● INACTIVE");
            binding.modSubtext.setText("Wa Enhancer " + BuildConfig.VERSION_NAME + " · Tidak Aktif");
            binding.modActiveBadge.setText("INACTIVE");
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
                        .url("https://api.github.com/repos/Dev4Mod/WaEnhancer/releases/latest")
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

                    var hash = tagName.split("-")[1].trim();
                    var isNewVersion = !BuildConfig.VERSION_NAME.toLowerCase().contains(hash.toLowerCase());

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
        var context = requireContext();
        var dialogBinding = DialogUpdateAvailableBinding.inflate(LayoutInflater.from(context));

        dialogBinding.tvUpdateTitle.setText("Catatan Rilis Modul");
        dialogBinding.tvUpdateSubtitle.setText("Versi 1.5.7 · " + BuildConfig.VERSION_NAME);
        dialogBinding.tvVersionBadge.setText(BuildConfig.VERSION_NAME);
        dialogBinding.tvReleaseDate.setText(new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date()));

        String changelogText = """
                ### [ADDED]

                • **iOS Green Plus (+) Header Button:** Added green plus action button to Communities, Calls, and Status tabs matching iOS header style in Chats.
                • **Language-Independent Tab Detection:** Hooked ViewPager page selection with WhatsApp internal numeric Tab IDs (200 Chats, 300 Status, 400 Calls, 600 Communities, 700 Settings), supporting 100% of device languages and locales.
                • **Toolbar Tab Title Badge Stripping:** Automatically cleans unread counter badges (e.g. '(12)') from tab titles for clean header rendering.
                • **Direct SQLite Database Queries:** Added fast background SQLite queries to chatsettings.db and msgstore.db for accurate Pinned, Muted, and Unread counts on iOS Swipe Menu.

                ### [IMPROVED]

                • **Dynamic Header Plus Action:** Improved click handler to dynamically trigger primary creation actions (New Chat, New Call, New Status, New Community) across all main tabs.
                • **Header Tab Visibility:** Improved top bar action button manager to display green plus button on all main content tabs while hiding it on Settings tab.
                • **Unified Toolbar Layout & PreDraw Performance:** Consolidated multiple pre-draw passes into a single unified OnPreDrawListener with translation caching to prevent frame jank.
                • **Toolbar Lifecycle Resilience:** Added View.OnAttachStateChangeListener to safely re-register OnPreDrawListener across fragment detach and re-attach cycles.
                • **iOS Header Action Buttons Layout:** Cleaned up action buttons with transparent borderless ripple and precise right edge alignment.
                • **Tag Collision Prevention:** Re-assigned unique tag keys across views to eliminate tag ID collisions.
                • **Comprehensive Defensive Logging:** Replaced silent try-catch blocks across IosHeader and IosSwipeMenu with zero-overhead logDebug for debugging.
                • **Action Menu Retry & Diagnostics:** Added retry timeout budget logging in IosSwipeMenu and item matching failure diagnostics in silentToolbarAction.
                • **Clean Code Refactoring:** Removed dead code and unused legacy helpers (ProfileInfo, checkProfileCardInRv, findSettingsRecyclerView).
                • **Removed Duplicate Preference:** Removed duplicate 'Gaya Kolom Chat iOS' (ios_text_entry) preference toggle from Customization settings menu.

                ### [FIXED]

                • **Fixed missing green plus button** in Communities, Calls, and Status header tabs.
                • **Fixed Communities tab green plus button** triggering existing community group profile popups instead of opening New Community creation flow.
                • **Fixed awkward spacing and padding** on Restart and action buttons in iOS header.
                • **Fixed missing user profile name title** in toolbar when scrolling down on Settings/Anda tab.
                • **Fixed SQLite Database Path Bug:** Corrected dbParent path to point directly to app data directory, enabling SQLite queries to find chatsettings.db and msgstore.db.
                • **Fixed Navigation Icon and Action Buttons Disappearing:** Re-injected ensureNavigationIcon and container persistence across all tab transitions to prevent three-dot icon and action buttons from disappearing.
                • **Fixed Tab Reset on Empty Title:** Prevented toolbar from blindly resetting active tab to 'Chats' when title is empty by reading persisted active tab tag.
                • **Fixed False-Positive Tab Matching:** Replaced loose substring matching with exact matching and numeric ID verification.""";

        var markwon = Markwon.create(context);
        dialogBinding.tvChangelog.setText(markwon.toMarkdown(changelogText));

        dialogBinding.btnIgnore.setVisibility(View.GONE);
        dialogBinding.btnUpdate.setText("Tutup");

        var dialog = new MaterialAlertDialogBuilder(context)
                .setView(dialogBinding.getRoot())
                .setCancelable(true)
                .show();

        dialogBinding.btnUpdate.setOnClickListener(v -> dialog.dismiss());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
