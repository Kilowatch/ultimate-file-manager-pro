package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.settings.NetworkTransferPreferenceManager

@RunWith(RobolectricTestRunner::class)
class NetworkShareThreadsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ufm_network_transfer_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testDefaultParallelThreadsIsZero() {
        val share = NetworkShare(
            name = "Test FTP",
            type = ShareType.FTP,
            host = "192.168.1.100"
        )
        assertEquals(0, share.parallelThreads)
        // With default 0, effectiveThreads falls back to global default (4)
        assertEquals(4, share.effectiveThreads(context))
    }

    @Test
    fun testGlobalThreadsFallbackWhenZero() {
        NetworkTransferPreferenceManager.setThreadCount(context, 8)
        val share = NetworkShare(
            name = "Test FTP",
            type = ShareType.FTP,
            host = "192.168.1.100",
            parallelThreads = 0
        )
        assertEquals(8, share.effectiveThreads(context))
    }

    @Test
    fun testCustomShareOverridePrecedence() {
        NetworkTransferPreferenceManager.setThreadCount(context, 8)
        val share = NetworkShare(
            name = "Test FTP",
            type = ShareType.FTP,
            host = "192.168.1.100",
            parallelThreads = 2
        )
        // Overridden to 2, regardless of global setting
        assertEquals(2, share.effectiveThreads(context))
    }
}
