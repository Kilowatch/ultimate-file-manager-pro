package za.kilowatch.ultimatefilemanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPreferencesManagerTest {

    @Test
    fun defaultNetworkPolicyIsWifiOnly() {
        assertEquals(
            DownloadPreferencesManager.NetworkPolicy.WIFI_ONLY,
            DownloadPreferencesManager.NetworkPolicy.valueOf("WIFI_ONLY")
        )
    }

    @Test
    fun networkPolicyEnumValues() {
        val policies = DownloadPreferencesManager.NetworkPolicy.values()
        assertEquals(2, policies.size)
        assertTrue(policies.contains(DownloadPreferencesManager.NetworkPolicy.WIFI_ONLY))
        assertTrue(policies.contains(DownloadPreferencesManager.NetworkPolicy.ALL_NETWORKS))
    }
}
