package za.kilowatch.ultimatefilemanager.storage

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.R

@RunWith(RobolectricTestRunner::class)
class StorageAdapterTest {

    @Test
    fun adapterHasNoStableIds() {
        val adapter = StorageAdapter(
            isTv = false,
            onStorageClick = {}
        )
        // Verify setHasStableIds(true) was removed to prevent scrap re-attachment crashes
        assertFalse("StorageAdapter must not have stable IDs", adapter.hasStableIds())
    }

    @Test
    fun adapterGuardsAgainstRedundantNotifications() {
        val adapter = StorageAdapter(
            isTv = false,
            onStorageClick = {}
        )

        var notificationCount = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                notificationCount++
            }
        })

        // Initial mode is LIST
        assertEquals(MainMenuViewModeManager.ViewMode.LIST, adapter.viewMode)

        // Setting the same viewMode should not trigger notifyDataSetChanged
        adapter.viewMode = MainMenuViewModeManager.ViewMode.LIST
        assertEquals(0, notificationCount)

        // Changing viewMode triggers notifyDataSetChanged
        adapter.viewMode = MainMenuViewModeManager.ViewMode.GRID
        assertEquals(1, notificationCount)

        // Setting same grid mode again should not trigger notification
        adapter.viewMode = MainMenuViewModeManager.ViewMode.GRID
        assertEquals(1, notificationCount)

        // Setting same column count should not trigger notification
        adapter.gridColumnCount = 3
        assertEquals(1, notificationCount)

        // Changing column count should trigger notification
        adapter.gridColumnCount = 4
        assertEquals(2, notificationCount)

        // Setting same tile colors should not trigger notification
        adapter.setTileColors(emptyMap())
        assertEquals(2, notificationCount)

        // Changing tile colors should trigger notification
        adapter.setTileColors(mapOf("internal" to TileColorConfig(iconColor = 0xFF112233.toInt())))
        assertEquals(3, notificationCount)

        // Setting same tile colors again should not trigger notification
        adapter.setTileColors(mapOf("internal" to TileColorConfig(iconColor = 0xFF112233.toInt())))
        assertEquals(3, notificationCount)
    }

    @Test
    fun adapterComputesCorrectItemViewTypes() {
        val adapter = StorageAdapter(
            isTv = false,
            onStorageClick = {}
        )

        val regularItem = StorageItem(
            id = "twin_window_tile",
            label = "Twin Window",
            iconRes = R.drawable.ic_twin_window,
            totalBytes = 0,
            usedBytes = 0,
            mountPath = ""
        )

        adapter.submitList(listOf(regularItem))

        // In LIST mode, regular item uses VIEW_TYPE_STORAGE
        adapter.viewMode = MainMenuViewModeManager.ViewMode.LIST
        assertEquals(StorageAdapter.VIEW_TYPE_STORAGE, adapter.getItemViewType(0))

        // In GRID mode, regular item uses VIEW_TYPE_GRID
        adapter.viewMode = MainMenuViewModeManager.ViewMode.GRID
        assertEquals(StorageAdapter.VIEW_TYPE_GRID, adapter.getItemViewType(0))

        // In MODERN_CATEGORIZED mode, categorized items include category headers
        adapter.viewMode = MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED
        // First item in categorized mode is the category header (e.g. Utilities for twin window)
        val items = adapter.getItems()
        val headerIdx = items.indexOfFirst { it.isCategoryHeader }
        val tileIdx = items.indexOfFirst { !it.isCategoryHeader }
        assert(headerIdx >= 0)
        assert(tileIdx >= 0)
        assertEquals(StorageAdapter.VIEW_TYPE_CATEGORY_HEADER, adapter.getItemViewType(headerIdx))
        assertEquals(StorageAdapter.VIEW_TYPE_STORAGE, adapter.getItemViewType(tileIdx))
    }
}
