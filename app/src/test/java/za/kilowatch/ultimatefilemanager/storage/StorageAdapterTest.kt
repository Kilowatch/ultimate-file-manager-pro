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
        assert(headerIdx >= 0)
        assertEquals(StorageAdapter.VIEW_TYPE_CATEGORY_HEADER, adapter.getItemViewType(headerIdx))
        assert(items[headerIdx].categoryGroupItems.isNotEmpty())
    }

    @Test
    fun adapterReordersTilesWithinAndAcrossCategories() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val adapter = StorageAdapter(
            isTv = false,
            onStorageClick = {}
        )
        adapter.viewMode = MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED

        val item1 = StorageItem(
            id = "tile_internal",
            label = "Internal",
            iconRes = R.drawable.ic_storage_internal,
            totalBytes = 100,
            usedBytes = 50,
            mountPath = "/data"
        )
        val item2 = StorageItem(
            id = "tile_sdcard",
            label = "SD Card",
            iconRes = R.drawable.ic_storage_usb,
            totalBytes = 200,
            usedBytes = 100,
            mountPath = "/sdcard"
        )
        val item3 = StorageItem(
            id = "tile_usb",
            label = "USB Drive",
            iconRes = R.drawable.ic_storage_usb,
            totalBytes = 300,
            usedBytes = 150,
            mountPath = "/usb"
        )

        // Submit list of 3 storage items (all default to CATEGORY_STORAGE)
        adapter.submitList(listOf(item1, item2, item3), context)

        val initialHeaders = adapter.getItems().filter { it.isCategoryHeader }
        val storageHeader = initialHeaders.find { it.categoryId == MainMenuViewModeManager.CATEGORY_STORAGE }
        assert(storageHeader != null)
        assertEquals(listOf("tile_internal", "tile_sdcard", "tile_usb"), storageHeader!!.categoryGroupItems.map { it.id })

        // 1. Move tile_usb to index 0 (above tile_internal) within CATEGORY_STORAGE
        adapter.reorderTile(context, "tile_usb", MainMenuViewModeManager.CATEGORY_STORAGE, 0)
        val updatedHeader = adapter.getItems().find { it.categoryId == MainMenuViewModeManager.CATEGORY_STORAGE }
        assertEquals(listOf("tile_usb", "tile_internal", "tile_sdcard"), updatedHeader!!.categoryGroupItems.map { it.id })

        // 2. Move tile_sdcard to CATEGORY_CONNECT at index 0
        adapter.reorderTile(context, "tile_sdcard", MainMenuViewModeManager.CATEGORY_CONNECT, 0)
        val storageAfterMove = adapter.getItems().find { it.categoryId == MainMenuViewModeManager.CATEGORY_STORAGE }
        val connectAfterMove = adapter.getItems().find { it.categoryId == MainMenuViewModeManager.CATEGORY_CONNECT }
        assertEquals(listOf("tile_usb", "tile_internal"), storageAfterMove!!.categoryGroupItems.map { it.id })
        assertEquals(listOf("tile_sdcard"), connectAfterMove!!.categoryGroupItems.map { it.id })
        assertEquals(MainMenuViewModeManager.CATEGORY_CONNECT, MainMenuViewModeManager.loadTileCategory(context, "tile_sdcard"))
    }
}
