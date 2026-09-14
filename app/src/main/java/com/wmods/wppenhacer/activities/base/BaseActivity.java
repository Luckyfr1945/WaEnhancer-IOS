package com.wmods.wppenhacer.activities.base;

import android.os.Bundle;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.wmods.wppenhacer.R;

public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        getTheme().applyStyle(rikka.material.preference.R.style.ThemeOverlay_Rikka_Material3_Preference, true);
        getTheme().applyStyle(R.style.ThemeOverlay, true);
        applyThemeOverlay();
        super.onCreate(savedInstanceState);
        enableHighRefreshRate();
    }

    /**
     * Dynamically detects and requests the highest refresh rate supported by the
     * display (90Hz, 120Hz, 144Hz+).
     * Safely adapts to the hardware: if the device only supports 60Hz, it
     * gracefully leaves default attributes intact.
     */
    private void enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.view.Window window = getWindow();
                if (window == null)
                    return;

                android.view.Display display = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display = getDisplay();
                }
                if (display == null) {
                    android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm != null) {
                        display = wm.getDefaultDisplay();
                    }
                }

                if (display != null) {
                    android.view.Display.Mode[] modes = display.getSupportedModes();
                    if (modes != null && modes.length > 0) {
                        android.view.Display.Mode maxMode = null;
                        float maxRate = 0f;
                        for (android.view.Display.Mode mode : modes) {
                            if (mode.getRefreshRate() > maxRate) {
                                maxRate = mode.getRefreshRate();
                                maxMode = mode;
                            }
                        }

                        if (maxMode != null && maxRate > 60f) {
                            android.view.WindowManager.LayoutParams params = window.getAttributes();
                            params.preferredDisplayModeId = maxMode.getModeId();
                            params.preferredRefreshRate = maxRate;
                            window.setAttributes(params);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyThemeOverlay() {
        var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        var colorMode = prefs.getString("wae_color_mode", "preset");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "monet".equals(colorMode)) {
            return;
        }

        var colorPreset = prefs.getString("wae_color_preset", "green");
        getTheme().applyStyle(resolveColorOverlay(colorPreset), true);
    }

    private int resolveColorOverlay(String colorPreset) {
        if (colorPreset == null) {
            return R.style.ThemeOverlay_MaterialGreen;
        }

        return switch (colorPreset) {
            case "blue" -> R.style.ThemeOverlay_MaterialBlue;
            case "cyan" -> R.style.ThemeOverlay_MaterialCyan;
            case "purple" -> R.style.ThemeOverlay_MaterialPurple;
            case "orange" -> R.style.ThemeOverlay_MaterialOrange;
            case "red" -> R.style.ThemeOverlay_MaterialRed;
            case "pink" -> R.style.ThemeOverlay_MaterialPink;
            default -> R.style.ThemeOverlay_MaterialGreen;
        };
    }

}
