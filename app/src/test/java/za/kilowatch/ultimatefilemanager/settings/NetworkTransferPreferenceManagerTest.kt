package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkTransferPreferenceManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ufm_network_transfer_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testDefaultThreadsIsFour() {
        assertEquals(4, NetworkTransferPreferenceManager.DEFAULT_THREADS)
        assertEquals(4, NetworkTransferPreferenceManager.getThreadCount(context))
    }

    @Test
    fun testSetThreadsValidOptions() {
        for (threads in NetworkTransferPreferenceManager.AVAILABLE_THREAD_OPTIONS) {
            NetworkTransferPreferenceManager.setThreadCount(context, threads)
            assertEquals(threads, NetworkTransferPreferenceManager.getThreadCount(context))
        }
    }

    @Test
    fun testSetThreadsClamping() {
        NetworkTransferPreferenceManager.setThreadCount(context, 0)
        assertEquals(1, NetworkTransferPreferenceManager.getThreadCount(context))

        NetworkTransferPreferenceManager.setThreadCount(context, -5)
        assertEquals(1, NetworkTransferPreferenceManager.getThreadCount(context))

        NetworkTransferPreferenceManager.setThreadCount(context, 10)
        assertEquals(8, NetworkTransferPreferenceManager.getThreadCount(context))

        NetworkTransferPreferenceManager.setThreadCount(context, 99)
        assertEquals(8, NetworkTransferPreferenceManager.getThreadCount(context))
    }
}
