package com.wmods.wppenhacer.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.wmods.wppenhacer.ui.fragments.CustomizationFragment;
import com.wmods.wppenhacer.ui.fragments.GeneralFragment;
import com.wmods.wppenhacer.ui.fragments.HomeFragment;
import com.wmods.wppenhacer.ui.fragments.MediaFragment;
import com.wmods.wppenhacer.ui.fragments.PrivacyFragment;
import com.wmods.wppenhacer.ui.fragments.RecordingsFragment;

public class MainPagerAdapter extends FragmentStateAdapter {

    public enum Mode {
        FULL,       // [0: General, 1: Privacy, 2: Home, 3: Media, 4: Customization, (5: Recordings)]
        ROOT_ONLY,  // [0: General, 1: Home]
        HOME_ONLY   // [0: Home]
    }

    private Mode mode = Mode.FULL;
    private final boolean isRecordingEnabled;

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
        var prefs = PreferenceManager.getDefaultSharedPreferences(fragmentActivity);
        isRecordingEnabled = prefs.getBoolean("call_recording_enable", false);
    }

    public void setMode(Mode newMode) {
        if (this.mode != newMode) {
            this.mode = newMode;
            notifyDataSetChanged();
        }
    }

    public Mode getMode() {
        return mode;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (mode == Mode.ROOT_ONLY) {
            return switch (position) {
                case 0 -> new GeneralFragment();
                default -> new HomeFragment();
            };
        } else if (mode == Mode.HOME_ONLY) {
            return new HomeFragment();
        }

        return switch (position) {
            case 0 -> new GeneralFragment();
            case 1 -> new PrivacyFragment();
            case 3 -> new MediaFragment();
            case 4 -> new CustomizationFragment();
            case 5 -> new RecordingsFragment();
            default -> new HomeFragment();
        };
    }

    @Override
    public int getItemCount() {
        if (mode == Mode.ROOT_ONLY) return 2;
        if (mode == Mode.HOME_ONLY) return 1;
        return isRecordingEnabled ? 6 : 5;
    }

    @Override
    public long getItemId(int position) {
        if (mode == Mode.ROOT_ONLY) {
            return position == 0 ? 100L : 101L;
        } else if (mode == Mode.HOME_ONLY) {
            return 200L;
        }
        return position;
    }

    @Override
    public boolean containsItem(long itemId) {
        if (mode == Mode.ROOT_ONLY) {
            return itemId == 100L || itemId == 101L;
        } else if (mode == Mode.HOME_ONLY) {
            return itemId == 200L;
        }
        int max = isRecordingEnabled ? 6 : 5;
        return itemId >= 0 && itemId < max;
    }
}