package com.wmods.wppenhacer.ui.fragments.base;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.MainActivity;
import com.wmods.wppenhacer.databinding.BaseFragmentBinding;

import eightbitlab.com.blurview.BlurView;

public class BaseFragment extends Fragment {

    public BaseFragmentBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BaseFragmentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupBackdropBlur();
    }

    @Override
    public void onResume() {
        super.onResume();
        setupBackdropBlur();
    }

    public void setupBackdropBlur() {
        if (getView() == null || getActivity() == null) return;
        BlurView blurBackdrop = getView().findViewById(R.id.fragment_blur_backdrop);
        if (blurBackdrop == null) return;

        if (MainActivity.customWallpaperBitmap != null) {
            float blurRadius = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(requireContext())
                    .getInt("app_blur_radius", 20);
            blurRadius = Math.max(1f, Math.min(blurRadius, 25f));

            ViewGroup decorView = (ViewGroup) requireActivity().getWindow().getDecorView();
            blurBackdrop.setVisibility(View.VISIBLE);
            blurBackdrop.setupWith(decorView)
                    .setFrameClearDrawable(decorView.getBackground())
                    .setBlurRadius(blurRadius)
                    .setOverlayColor(Color.argb(35, 10, 14, 20))
                    .setBlurAutoUpdate(true);
        } else {
            blurBackdrop.setVisibility(View.GONE);
        }
    }

    public void setDisplayHomeAsUpEnabled(boolean enabled) {
        if (getActivity() == null) return;
        var actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(enabled);
        }
    }
}
