package com.wmods.wppenhacer.ui.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import android.widget.Toast;

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
        
        // Handle scroll to preference from search
        if (getActivity() != null && getActivity().getIntent() != null) {
            String scrollToKey = getActivity().getIntent().getStringExtra("scroll_to_preference");
            if (scrollToKey != null) {
                scrollToPreference(scrollToKey);
                // Clear the intent extra
                getActivity().getIntent().removeExtra("scroll_to_preference");
            }
        }

        androidx.preference.SwitchPreferenceCompat iosTheme = findPreference("ios_header");
        if (iosTheme != null) {
            iosTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof Boolean && (Boolean) newValue) {
                    SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                    if (prefs != null) {
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putBoolean("floating_bottom_bar", true);
                        editor.putBoolean("floatingmenu", true);
                        editor.putBoolean("bubble_color", false);
                        editor.putBoolean("disable_defemojis", true);
                        editor.putBoolean("wallpaper", true);
                        editor.putInt("wallpaper_alpha_toolbar", 30);
                        editor.putInt("wallpaper_alpha_navigation", 30);
                        editor.apply();

                        if (getContext() != null) {
                            Toast.makeText(getContext(), "iOS Theme Applied! Restarting settings...", Toast.LENGTH_SHORT).show();
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
