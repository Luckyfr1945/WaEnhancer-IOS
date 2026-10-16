package com.wmods.wppenhacer.ui.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import android.widget.Toast;

import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;

public class CustomizationFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.fragment_customization, rootKey);
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable android.os.Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // One-time self-healing cleanup: if iOS Theme is currently OFF, ensure legacy residual
        // flags (floating bar, IG stories, wallpaper) are also neutralized so WhatsApp displays 100% stock Android UI
        SharedPreferences defaultPrefs = getPreferenceManager().getSharedPreferences();
        if (defaultPrefs != null && !defaultPrefs.getBoolean("ios_theme_cleaned_up_v1", false)) {
            if (!defaultPrefs.getBoolean("ios_header", false)) {
                defaultPrefs.edit()
                        .putBoolean("floating_bottom_bar", false)
                        .putBoolean("igstatus", false)
                        .putBoolean("floatingmenu", false)
                        .putBoolean("wallpaper", false)
                        .putBoolean("ios_swipe_menu", false)
                        .putBoolean("ios_text_entry", false)
                        .putBoolean("disable_defemojis", false)
                        .putBoolean("ios_theme_cleaned_up_v1", true)
                        .apply();
            } else {
                defaultPrefs.edit().putBoolean("ios_theme_cleaned_up_v1", true).apply();
            }
        }

        // Handle scroll to preference from search
        if (getActivity() != null && getActivity().getIntent() != null) {
            String scrollToKey = getActivity().getIntent().getStringExtra("scroll_to_preference");
            if (scrollToKey != null) {
                scrollToPreference(scrollToKey);
                // Clear the intent extra
                getActivity().getIntent().removeExtra("scroll_to_preference");
            }
        }

        // Blur radius live preview
        androidx.preference.SeekBarPreference blurPref = findPreference("app_blur_radius");
        if (blurPref != null) {
            blurPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (getActivity() != null) {
                    getActivity().recreate();
                }
                return true;
            });
        }

        androidx.preference.SwitchPreferenceCompat iosTheme = findPreference("ios_header");
        if (iosTheme != null) {
            iosTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof Boolean) {
                    boolean enabled = (Boolean) newValue;
                    SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                    if (prefs != null) {
                        SharedPreferences.Editor editor = prefs.edit();
                        if (enabled) {
                            editor.putBoolean("floating_bottom_bar", true);
                            editor.putBoolean("floatingmenu", true);
                            editor.putBoolean("bubble_color", false);
                            editor.putBoolean("disable_defemojis", true);
                            editor.putBoolean("wallpaper", true);
                            editor.putBoolean("igstatus", true);
                            editor.putBoolean("ios_swipe_menu", true);
                            editor.putBoolean("ios_text_entry", true);
                            editor.putInt("wallpaper_alpha_toolbar", 30);
                            editor.putInt("wallpaper_alpha_navigation", 30);
                        } else {
                            editor.putBoolean("floating_bottom_bar", false);
                            editor.putBoolean("floatingmenu", false);
                            editor.putBoolean("wallpaper", false);
                            editor.putBoolean("igstatus", false);
                            editor.putBoolean("disable_defemojis", false);
                            editor.putBoolean("ios_swipe_menu", false);
                            editor.putBoolean("ios_text_entry", false);
                        }
                        editor.apply();

                        // Force-restart WhatsApp to immediately apply or disable native Xposed hooks cleanly
                        if (App.instance != null) {
                            App.instance.restartApp(FeatureLoader.PACKAGE_WPP);
                            App.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS);
                        }
                        try {
                            if (Boolean.TRUE.equals(com.topjohnwu.superuser.Shell.isAppGrantedRoot())) {
                                com.topjohnwu.superuser.Shell.cmd(
                                        "am force-stop " + FeatureLoader.PACKAGE_WPP,
                                        "am force-stop " + FeatureLoader.PACKAGE_BUSINESS
                                ).exec();
                            }
                        } catch (Throwable ignored) {}

                        if (getContext() != null) {
                            String msg = enabled
                                    ? "Tema iOS Diterapkan! Memulai ulang WhatsApp..."
                                    : "Tema iOS Dimatikan! Tampilan WhatsApp standar dipulihkan...";
                            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                        }
                        if (getActivity() != null) {
                            getActivity().recreate();
                        }
                    }
                }
                return true;
            });
        }
    }

}
