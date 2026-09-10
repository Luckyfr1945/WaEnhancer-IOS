package com.wmods.wppenhacer.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.navigation.NavigationBarView;
import com.waseemsabir.betterypermissionhelper.BatteryPermissionHelper;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.base.BaseActivity;
import com.wmods.wppenhacer.adapter.MainPagerAdapter;
import com.wmods.wppenhacer.databinding.ActivityMainBinding;
import com.wmods.wppenhacer.ui.fragments.GeneralFragment;
import com.wmods.wppenhacer.ui.fragments.HomeFragment;
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment;
import com.wmods.wppenhacer.utils.FilePicker;

import java.io.File;

public class MainActivity extends BaseActivity {

    private ActivityMainBinding binding;
    private BatteryPermissionHelper batteryPermissionHelper = BatteryPermissionHelper.Companion.getInstance();
    private MainPagerAdapter pagerAdapter;
    private com.wmods.wppenhacer.ui.navigation.LiquidNavManager liquidNavManager;
    private String pendingScrollToPreference = null;
    private int pendingScrollToFragment = -1;
    private String pendingParentKey = null;
    public static android.graphics.Bitmap customWallpaperBitmap = null;

    private final androidx.activity.result.ActivityResultLauncher<String> wallpaperPickerLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    try {
                        File destFile = new File(getFilesDir(), "custom_app_wallpaper.png");
                        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                                java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                            byte[] buffer = new byte[4096];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                        }
                        applyCustomWallpaper();
                        android.widget.Toast
                                .makeText(this, R.string.wallpaper_applied, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        android.widget.Toast.makeText(this, "Gagal memuat wallpaper: " + e.getMessage(),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyCustomWallpaper();

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        pagerAdapter = new MainPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);
        binding.viewPager.setOffscreenPageLimit(4);

        // binding.viewPager.setPageTransformer(new DepthPageTransformer());

        liquidNavManager = new com.wmods.wppenhacer.ui.navigation.LiquidNavManager(
                binding.liquidNavView,
                this,
                (index, itemId) -> {
                    binding.viewPager.setCurrentItem(index, true);
                    updateToolbarSubtitleForPage(index);
                    return kotlin.Unit.INSTANCE;
                });
        updateNavMenuVisibility();

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (liquidNavManager != null) {
                    liquidNavManager.setSelectedIndex(position);
                    liquidNavManager.showBar(true);
                }
                updateToolbarSubtitleForPage(position);

                // Handle pending scroll after page change
                if (pendingScrollToFragment == position && pendingScrollToPreference != null) {
                    final String scrollKey = pendingScrollToPreference;
                    final String parentKey = pendingParentKey;
                    pendingScrollToPreference = null;
                    pendingScrollToFragment = -1;
                    pendingParentKey = null;

                    // Wait for fragment to be ready
                    binding.viewPager.postDelayed(() -> {
                        scrollToPreferenceInCurrentFragment(scrollKey, parentKey);
                    }, 300);
                }
            }
        });
        binding.viewPager.setCurrentItem(isXposedEnabled() ? 2 : 1, false);
        createMainDir();
        FilePicker.registerFilePicker(this);

        // Handle incoming navigation from search
        handleIncomingIntent(getIntent());
        applyCustomWallpaper();
    }

    private void createMainDir() {
        var nomedia = new File(App.getWaEnhancerFolder(), ".nomedia");
        if (nomedia.exists()) {
            nomedia.delete();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null)
            return;

        int fragmentPosition = intent.getIntExtra("navigate_to_fragment", -1);
        String preferenceKey = intent.getStringExtra("scroll_to_preference");
        String parentKey = intent.getStringExtra("parent_preference");

        if (fragmentPosition >= 0 && preferenceKey != null) {
            // Store the scroll target
            pendingScrollToPreference = preferenceKey;
            pendingScrollToFragment = fragmentPosition;
            pendingParentKey = parentKey;

            // Navigate to the fragment (onPageSelected will handle the scroll)
            binding.viewPager.setCurrentItem(fragmentPosition, false);

            // Clear intent extras
            intent.removeExtra("navigate_to_fragment");
            intent.removeExtra("scroll_to_preference");
            intent.removeExtra("parent_preference");
        } else if (fragmentPosition >= 0) {
            // Just navigate without scrolling
            binding.viewPager.setCurrentItem(fragmentPosition, true);
        }
    }

    private void scrollToPreferenceInCurrentFragment(String preferenceKey, String parentKey) {
        // Get the current fragment from the ViewPager
        int currentItem = binding.viewPager.getCurrentItem();
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + currentItem);

        if (fragment == null)
            return;

        // Handle different fragment types
        if (fragment instanceof GeneralFragment || fragment instanceof HomeFragment) {
            // These fragments have child fragments
            if (parentKey != null && !parentKey.isEmpty()) {
                // Navigate to sub-fragment first, then scroll
                navigateToSubFragmentAndScroll(fragment, parentKey, preferenceKey);
            } else {
                // Direct scroll in current child fragment
                scrollInChildFragment(fragment, preferenceKey);
            }
        } else if (fragment instanceof BasePreferenceFragment) {
            // Direct preference fragments (no nesting)
            ((BasePreferenceFragment) fragment).scrollToPreference(preferenceKey);
        }
    }

    private void navigateToSubFragmentAndScroll(Fragment parentFragment, String parentKey, String childPreferenceKey) {
        // Directly instantiate the sub-fragment
        Fragment subFragment = null;

        switch (parentKey) {
            case "general_home":
                subFragment = new GeneralFragment.HomeGeneralPreference();
                break;
            case "homescreen":
                subFragment = new GeneralFragment.HomeScreenGeneralPreference();
                break;
            case "conversation":
                subFragment = new GeneralFragment.ConversationGeneralPreference();
                break;
            case "sticker_sync":
                subFragment = new com.wmods.wppenhacer.ui.fragments.StickerSyncFragment();
                break;
        }

        if (subFragment != null && parentFragment.getView() != null) {
            final Fragment finalSubFragment = subFragment;
            // Replace the current child fragment
            parentFragment.getChildFragmentManager().beginTransaction()
                    .replace(R.id.frag_container, subFragment)
                    .commitNow();

            // Wait for fragment to be ready, then scroll
            parentFragment.getView().postDelayed(() -> {
                if (finalSubFragment instanceof BasePreferenceFragment) {
                    ((BasePreferenceFragment) finalSubFragment).scrollToPreference(childPreferenceKey);
                }
            }, 400);
        }
    }

    private void scrollInChildFragment(Fragment parentFragment, String preferenceKey) {
        Fragment childFragment = parentFragment.getChildFragmentManager().findFragmentById(R.id.frag_container);
        if (childFragment instanceof BasePreferenceFragment) {
            ((BasePreferenceFragment) childFragment).scrollToPreference(preferenceKey);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            fragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.header_menu, menu);
        var powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            menu.findItem(R.id.batteryoptimization).setVisible(false);
        }
        return true;
    }

    @SuppressLint("BatteryLife")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_wallpaper) {
            showWallpaperOptionsDialog();
            return true;
        } else if (item.getItemId() == R.id.menu_search) {
            var options = ActivityOptionsCompat.makeCustomAnimation(
                    this, R.anim.slide_in_right, R.anim.slide_out_left);
            startActivity(new Intent(this, SearchActivity.class), options.toBundle());
            return true;
        } else if (item.getItemId() == R.id.menu_about) {
            var options = ActivityOptionsCompat.makeCustomAnimation(
                    this, R.anim.slide_in_right, R.anim.slide_out_left);
            startActivity(new Intent(this, AboutActivity.class), options.toBundle());
            return true;
        } else if (item.getItemId() == R.id.batteryoptimization) {
            if (batteryPermissionHelper.isBatterySaverPermissionAvailable(this, true)) {
                batteryPermissionHelper.getPermission(this, true, true);
            } else {
                var intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNavMenuVisibility();
    }

    public void updateNavMenuVisibility() {
        if (binding == null || liquidNavManager == null || pagerAdapter == null)
            return;

        boolean isLsposed = isXposedEnabled();
        var prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean isRecording = prefs.getBoolean("call_recording_enable", false);

        if (isLsposed) {
            pagerAdapter.setMode(MainPagerAdapter.Mode.FULL);
            liquidNavManager.setMode(MainPagerAdapter.Mode.FULL, isRecording);
            binding.viewPager.setUserInputEnabled(true);
        } else {
            com.wmods.wppenhacer.utils.StickerSyncManager.INSTANCE.isRootAvailable(hasRoot -> {
                runOnUiThread(() -> {
                    if (binding == null || liquidNavManager == null || pagerAdapter == null)
                        return;

                    if (Boolean.TRUE.equals(hasRoot)) {
                        pagerAdapter.setMode(MainPagerAdapter.Mode.ROOT_ONLY);
                        liquidNavManager.setMode(MainPagerAdapter.Mode.ROOT_ONLY, false);
                        binding.viewPager.setUserInputEnabled(true);

                        int cur = binding.viewPager.getCurrentItem();
                        if (cur != 0 && cur != 1) {
                            binding.viewPager.setCurrentItem(1, false);
                            liquidNavManager.setSelectedIndex(1);
                        }
                    } else {
                        pagerAdapter.setMode(MainPagerAdapter.Mode.HOME_ONLY);
                        liquidNavManager.setMode(MainPagerAdapter.Mode.HOME_ONLY, false);
                        binding.viewPager.setCurrentItem(0, false);
                        liquidNavManager.setSelectedIndex(0);
                        binding.viewPager.setUserInputEnabled(false);
                    }
                });
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    private void updateToolbarSubtitleForPage(int position) {
        MainPagerAdapter.Mode mode = pagerAdapter != null ? pagerAdapter.getMode() : MainPagerAdapter.Mode.FULL;
        String subtitle;

        if (mode == MainPagerAdapter.Mode.ROOT_ONLY) {
            subtitle = (position == 0) ? "Sinkronisasi Stiker" : "Module Control Center";
        } else if (mode == MainPagerAdapter.Mode.HOME_ONLY) {
            subtitle = "Module Control Center";
        } else {
            subtitle = switch (position) {
                case 0 -> "Setelan & Kustomisasi";
                case 1 -> "Pengaturan Privasi";
                case 2 -> "Module Control Center";
                case 3 -> "Pengaturan Media & Unduhan";
                case 4 -> "Kustomisasi Tampilan";
                case 5 -> "Perekam Panggilan";
                default -> "Module Control Center";
            };
        }
        binding.toolbarSubtitle.setText(subtitle);
    }

    public static boolean isXposedEnabled() {
        return false;
    }

    public void setBottomNavVisibility(int visibility) {
        if (liquidNavManager != null) {
            liquidNavManager.setVisibility(visibility);
        }
    }

    public void hideLiquidBar() {
        if (liquidNavManager != null) {
            liquidNavManager.hideBar(true);
        }
    }

    public void showLiquidBar() {
        if (liquidNavManager != null) {
            liquidNavManager.showBar(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return super.onSupportNavigateUp();
    }

    public void applyCustomWallpaper() {
        if (binding == null)
            return;
        File wallpaperFile = new File(getFilesDir(), "custom_app_wallpaper.png");
        if (wallpaperFile.exists() && wallpaperFile.length() > 0) {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(wallpaperFile.getAbsolutePath());
            if (bitmap != null) {
                customWallpaperBitmap = bitmap;
                binding.mainWallpaper.setImageBitmap(bitmap);
                binding.mainWallpaper.setVisibility(android.view.View.VISIBLE);
                binding.mainWallpaperScrim.setVisibility(android.view.View.VISIBLE);

                float blurRadius = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this)
                        .getInt("app_blur_radius", 20);
                blurRadius = Math.max(1f, Math.min(blurRadius, 25f));

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    binding.mainWallpaper.setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                    blurRadius * 2.5f, blurRadius * 2.5f, android.graphics.Shader.TileMode.CLAMP
                            )
                    );
                }

                binding.container.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                binding.appBarLayout.setBackgroundResource(R.drawable.bg_frosted_glass_topbar);
                binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                return;
            }
        }

        customWallpaperBitmap = null;
        binding.mainWallpaper.setVisibility(android.view.View.GONE);
        binding.mainWallpaperScrim.setVisibility(android.view.View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            binding.mainWallpaper.setRenderEffect(null);
        }
        binding.container.setBackgroundResource(R.color.background_color);
        binding.appBarLayout.setBackgroundResource(R.drawable.bg_frosted_glass_topbar);
        binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    private void showWallpaperOptionsDialog() {
        File wallpaperFile = new File(getFilesDir(), "custom_app_wallpaper.png");
        boolean hasWallpaper = wallpaperFile.exists() && wallpaperFile.length() > 0;

        String[] options = hasWallpaper
                ? new String[] { getString(R.string.wallpaper_choose_gallery), getString(R.string.wallpaper_reset) }
                : new String[] { getString(R.string.wallpaper_choose_gallery) };

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wallpaper_dialog_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        wallpaperPickerLauncher.launch("image/*");
                    } else if (which == 1) {
                        if (wallpaperFile.exists()) {
                            wallpaperFile.delete();
                        }
                        applyCustomWallpaper();
                        android.widget.Toast
                                .makeText(this, R.string.wallpaper_removed, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private static class DepthPageTransformer implements ViewPager2.PageTransformer {
        private static final float MIN_SCALE = 0.85f;

        @Override
        public void transformPage(@NonNull android.view.View page, float position) {
            int pageWidth = page.getWidth();

            if (position < -1) {
                page.setAlpha(0f);
            } else if (position <= 0) {
                page.setAlpha(1f);
                page.setTranslationX(0f);
                page.setTranslationZ(0f);
                page.setScaleX(1f);
                page.setScaleY(1f);
            } else if (position <= 1) {
                page.setAlpha(1 - position);
                page.setTranslationX(pageWidth * -position);
                page.setTranslationZ(0f);
                float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
                page.setScaleX(scaleFactor);
                page.setScaleY(scaleFactor);
            } else {
                page.setAlpha(0f);
            }
        }
    }
}