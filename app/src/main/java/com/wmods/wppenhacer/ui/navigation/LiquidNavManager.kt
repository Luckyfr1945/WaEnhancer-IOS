package com.wmods.wppenhacer.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.adapter.MainPagerAdapter

class LiquidNavManager(
    private val composeView: ComposeView,
    lifecycleOwner: LifecycleOwner,
    private val onTabSelected: (Int, Int) -> Unit
) {
    private val selectedIndexState = mutableIntStateOf(2)
    private val itemsState = mutableStateOf<List<WaBottomNavItem>>(emptyList())

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner)
        )
        composeView.setContent {
            WaLiquidNavigationBar(
                selectedIndex = selectedIndexState.intValue,
                items = itemsState.value,
                onTabSelected = { index ->
                    selectedIndexState.intValue = index
                    val list = itemsState.value
                    if (index in list.indices) {
                        onTabSelected(index, list[index].id)
                    }
                }
            )
        }
    }

    fun setMode(mode: MainPagerAdapter.Mode, isRecordingEnabled: Boolean) {
        val list = when (mode) {
            MainPagerAdapter.Mode.ROOT_ONLY -> listOf(
                WaBottomNavItem(R.id.navigation_chat, R.drawable.ic_general, "Stiker"),
                WaBottomNavItem(R.id.navigation_home, R.drawable.ic_home_black_24dp, "Home")
            )
            MainPagerAdapter.Mode.HOME_ONLY -> listOf(
                WaBottomNavItem(R.id.navigation_home, R.drawable.ic_home_black_24dp, "Home")
            )
            else -> {
                val base = mutableListOf(
                    WaBottomNavItem(R.id.navigation_chat, R.drawable.ic_general, "Setelan"),
                    WaBottomNavItem(R.id.navigation_privacy, R.drawable.ic_privacy, "Privasi"),
                    WaBottomNavItem(R.id.navigation_home, R.drawable.ic_home_black_24dp, "Home"),
                    WaBottomNavItem(R.id.navigation_media, R.drawable.ic_media, "Media"),
                    WaBottomNavItem(R.id.navigation_colors, R.drawable.ic_dashboard_black_24dp, "Kustomisasi")
                )
                if (isRecordingEnabled) {
                    base.add(WaBottomNavItem(R.id.navigation_recordings, R.drawable.ic_recording, "Perekam"))
                }
                base
            }
        }
        itemsState.value = list
    }

    fun setSelectedIndex(index: Int) {
        selectedIndexState.intValue = index
    }

    fun getSelectedIndex(): Int = selectedIndexState.intValue

    fun setVisibility(visibility: Int) {
        composeView.visibility = visibility
    }
}
