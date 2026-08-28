package com.wmods.wppenhacer.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.base.BaseActivity;
import com.wmods.wppenhacer.databinding.ActivityAboutBinding;

public class AboutActivity extends BaseActivity {

    private static final String[][] CONTRIBUTORS = {
            {"Dev4Mod", "https://github.com/Dev4Mod"},
            {"lukzy1945", "https://github.com/Luckyfr1945"},
            {"mbin", "https://github.com/mbinnn"},
            {"frknkrc44", "https://github.com/frknkrc44"},
            {"mubashardev", "https://github.com/mubashardev"},
            {"masbentoooredoo", "https://github.com/masbentoooredoo"},
            {"zhongerxll", "https://github.com/zhongerxll"},
            {"BryanGIG", "https://github.com/BryanGIG"},
            {"rizqi-developer", "https://github.com/rizqi-developer"},
            {"pedroborraz", "https://github.com/pedroborraz"},
            {"ahmedtohamy1", "https://github.com/ahmedtohamy1"},
            {"mohdafix", "https://github.com/mohdafix"},
            {"maulana-kurniawan", "https://github.com/maulana-kurniawan"},
            {"erzachn", "https://github.com/erzachn"},
            {"cvnertnc", "https://github.com/cvnertnc"},
            {"rkorossy", "https://github.com/rkorossy"},
            {"StupidRepo", "https://github.com/StupidRepo"},
            {"Blank517", "https://github.com/Blank517"},
            {"astola-studio", "https://github.com/astola-studio"},
            {"Strange-IPmart", "https://github.com/Strange-IPmart"}
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAboutBinding binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnTelegram.setOnClickListener(v -> openUrl("https://t.me/waenhancer"));
        binding.btnGithub.setOnClickListener(view -> openUrl("https://github.com/Dev4Mod/WaEnhancer"));

        int topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        int buttonHeight = (int) (46 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < CONTRIBUTORS.length; i++) {
            String[] contributor = CONTRIBUTORS[i];
            MaterialButton button;
            
            // Top 3 primary authors/fork maintainers get solid green pill buttons
            if (i < 3) {
                button = new MaterialButton(this);
                button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#62D87A")));
                button.setTextColor(Color.parseColor("#051A0B"));
                button.setIconTint(ColorStateList.valueOf(Color.parseColor("#051A0B")));
            } else {
                button = new MaterialButton(new ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton));
                button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card_border)));
                button.setStrokeWidth((int) (1.2f * getResources().getDisplayMetrics().density));
                button.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                button.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));
            }

            button.setCornerRadius((int) (999 * getResources().getDisplayMetrics().density));
            button.setText(contributor[0]);
            button.setTextSize(13f);
            button.setAllCaps(false);
            button.setIconResource(R.drawable.ic_github);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconPadding((int) (8 * getResources().getDisplayMetrics().density));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    buttonHeight
            );
            if (i > 0) {
                params.topMargin = topMargin;
            }
            button.setLayoutParams(params);
            button.setOnClickListener(v -> openUrl(contributor[1]));
            binding.contributorsContainer.addView(button);
        }
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }
}
