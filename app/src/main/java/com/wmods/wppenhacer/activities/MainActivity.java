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
    private String pendingScrollToPreference = null;
    private int pendingScrollToFragment = -1;
    private String pendingParentKey = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        pagerAdapter = new MainPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);

        binding.viewPager.setPageTransformer(new DepthPageTransformer());

        updateNavMenuVisibility();

        binding.navView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @SuppressLint("NonConstantResourceId")
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int itemId = item.getItemId();
                MainPagerAdapter.Mode mode = pagerAdapter != null ? pagerAdapter.getMode() : MainPagerAdapter.Mode.FULL;

                if (mode == MainPagerAdapter.Mode.ROOT_ONLY) {
                    if (itemId == R.id.navigation_chat) {
                        binding.viewPager.setCurrentItem(0, true);
                        return true;
                    } else if (itemId == R.id.navigation_home) {
                        binding.viewPager.setCurrentItem(1, true);
                        return true;
                    }
                    return false;
                } else if (mode == MainPagerAdapter.Mode.HOME_ONLY) {
                    if (itemId == R.id.navigation_home) {
                        binding.viewPager.setCurrentItem(0, true);
                        return true;
                    }
                    return false;
                }

                if (itemId == R.id.navigation_chat) {
                    binding.viewPager.setCurrentItem(0, true);
                    return true;
                } else if (itemId == R.id.navigation_privacy) {
                    binding.viewPager.setCurrentItem(1, true);
                    return true;
                } else if (itemId == R.id.navigation_home) {
                    binding.viewPager.setCurrentItem(2, true);
                    return true;
                } else if (itemId == R.id.navigation_media) {
                    binding.viewPager.setCurrentItem(3, true);
                    return true;
                } else if (itemId == R.id.navigation_colors) {
                    binding.viewPager.setCurrentItem(4, true);
                    return true;
                } else if (itemId == R.id.navigation_recordings) {
                    binding.viewPager.setCurrentItem(5, true);
                    return true;
                }
                return false;
            }
        });

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                MainPagerAdapter.Mode mode = pagerAdapter != null ? pagerAdapter.getMode() : MainPagerAdapter.Mode.FULL;

                int menuId;
                String subtitle;

                if (mode == MainPagerAdapter.Mode.ROOT_ONLY) {
                    if (position == 0) {
                        menuId = R.id.navigation_chat;
                        subtitle = "Sinkronisasi Stiker";
                    } else {
                        menuId = R.id.navigation_home;
                        subtitle = "Module Control Center";
                    }
                } else if (mode == MainPagerAdapter.Mode.HOME_ONLY) {
                    menuId = R.id.navigation_home;
                    subtitle = "Module Control Center";
                } else {
                    menuId = switch (position) {
                        case 0 -> R.id.navigation_chat;
                        case 1 -> R.id.navigation_privacy;
                        case 2 -> R.id.navigation_home;
                        case 3 -> R.id.navigation_media;
                        case 4 -> R.id.navigation_colors;
                        case 5 -> R.id.navigation_recordings;
                        default -> R.id.navigation_home;
                    };
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

                MenuItem item = binding.navView.getMenu().findItem(menuId);
                if (item != null) {
                    item.setChecked(true);
                }
                binding.toolbarSubtitle.setText(subtitle);

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

        eightbitlab.com.blurview.BlurView blurView = findViewById(R.id.blur_view);
        if (blurView != null) {
            android.view.ViewGroup decorView = (android.view.ViewGroup) getWindow().getDecorView();
            android.graphics.drawable.Drawable windowBackground = decorView.getBackground();
            blurView.setupWith(decorView)
                    .setFrameClearDrawable(windowBackground)
                    .setBlurRadius(10f)
                    .setOverlayColor(android.graphics.Color.TRANSPARENT)
                    .setBlurAutoUpdate(true);
        }
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
        if (item.getItemId() == R.id.menu_search) {
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
        if (binding == null || binding.navView == null || pagerAdapter == null) return;

        boolean isLsposed = isXposedEnabled();
        var menu = binding.navView.getMenu();

        if (isLsposed) {
            // Case 1: LSPosed is active -> show all navigation tabs
            pagerAdapter.setMode(MainPagerAdapter.Mode.FULL);
            var prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            boolean isRecording = prefs.getBoolean("call_recording_enable", false);

            menu.findItem(R.id.navigation_chat).setVisible(true);
            menu.findItem(R.id.navigation_privacy).setVisible(true);
            menu.findItem(R.id.navigation_home).setVisible(true);
            menu.findItem(R.id.navigation_media).setVisible(true);
            menu.findItem(R.id.navigation_colors).setVisible(true);
            menu.findItem(R.id.navigation_recordings).setVisible(isRecording);
            binding.viewPager.setUserInputEnabled(true);
            applyNavViewWidth(isRecording ? 6 : 5);
        } else {
            // Case 2 & 3: LSPosed not active -> Check Root status asynchronously
            com.wmods.wppenhacer.utils.StickerSyncManager.INSTANCE.isRootAvailable(hasRoot -> {
                runOnUiThread(() -> {
                    if (binding == null || binding.navView == null || pagerAdapter == null) return;
                    var currentMenu = binding.navView.getMenu();

                    if (Boolean.TRUE.equals(hasRoot)) {
                        // Case 2: Root active, LSPosed inactive -> ONLY 2 pages: [0: Gear (Sticker), 1: Home]
                        pagerAdapter.setMode(MainPagerAdapter.Mode.ROOT_ONLY);
                        currentMenu.findItem(R.id.navigation_chat).setVisible(true);
                        currentMenu.findItem(R.id.navigation_privacy).setVisible(false);
                        currentMenu.findItem(R.id.navigation_home).setVisible(true);
                        currentMenu.findItem(R.id.navigation_media).setVisible(false);
                        currentMenu.findItem(R.id.navigation_colors).setVisible(false);
                        currentMenu.findItem(R.id.navigation_recordings).setVisible(false);

                        // Allow swiping directly between Gear (0) and Home (1) with ZERO intermediate layers!
                        binding.viewPager.setUserInputEnabled(true);
                        applyNavViewWidth(2);

                        int cur = binding.viewPager.getCurrentItem();
                        if (cur != 0 && cur != 1) {
                            binding.viewPager.setCurrentItem(1, false);
                        }
                    } else {
                        // Case 3: No LSPosed, No Root -> ONLY 1 page: [0: Home]
                        pagerAdapter.setMode(MainPagerAdapter.Mode.HOME_ONLY);
                        currentMenu.findItem(R.id.navigation_chat).setVisible(false);
                        currentMenu.findItem(R.id.navigation_privacy).setVisible(false);
                        currentMenu.findItem(R.id.navigation_home).setVisible(true);
                        currentMenu.findItem(R.id.navigation_media).setVisible(false);
                        currentMenu.findItem(R.id.navigation_colors).setVisible(false);
                        currentMenu.findItem(R.id.navigation_recordings).setVisible(false);

                        binding.viewPager.setCurrentItem(0, false);
                        binding.viewPager.setUserInputEnabled(false);
                        applyNavViewWidth(1);
                    }
                });
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    private void applyNavViewWidth(int visibleItemCount) {
        if (binding == null || binding.navView == null) return;

        float density = getResources().getDisplayMetrics().density;
        var lp = (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) binding.navView.getLayoutParams();

        if (visibleItemCount == 1) {
            lp.width = (int) (84 * density);
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        } else if (visibleItemCount == 2) {
            lp.width = (int) (164 * density);
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        } else {
            lp.width = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT;
            lp.leftMargin = (int) (20 * density);
            lp.rightMargin = (int) (20 * density);
        }

        binding.navView.setLayoutParams(lp);
        binding.navView.requestLayout();

        android.view.View blurView = findViewById(R.id.blur_view);
        if (blurView != null) {
            blurView.requestLayout();
        }
    }

    public static boolean isXposedEnabled() {
        return false;
    }

    public void setBottomNavVisibility(int visibility) {
        if (binding != null && binding.navView != null) {
            binding.navView.setVisibility(visibility);
        }
        android.view.View blurView = findViewById(R.id.blur_view);
        if (blurView != null) {
            blurView.setVisibility(visibility);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return super.onSupportNavigateUp();
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
                page.setTranslationZ(-1f);
                float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
                page.setScaleX(scaleFactor);
                page.setScaleY(scaleFactor);
            } else {
                page.setAlpha(0f);
            }
        }
    }
}