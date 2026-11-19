package za.kilowatch.ultimatefilemanager.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.network.TvShareClient

@RunWith(RobolectricTestRunner::class)
class RemoteDownloadManagerTest {

    @Test
    fun testRemoteStatusParsing() {
        val jsonString = """
            {
                "success": true,
                "settings": {
                    "downloadDir": "/storage/emulated/0/Download",
                    "freeBytes": 15000000000,
                    "totalBytes": 32000000000,
                    "networkPolicy": "WIFI_ONLY",
                    "legalAccepted": true
                },
                "transfers": [
                    {
                        "id": "torrent-hash-123",
                        "title": "sample-linux.iso",
                        "type": "TORRENT",
                        "state": "DOWNLOADING",
                        "totalBytes": 4200000000,
                        "downloadedBytes": 2100000000,
                        "progressPercent": 50,
                        "speedBytesPerSec": 3500000,
                        "etaSeconds": 600,
                        "connectedPeers": 15,
                        "totalPeers": 50,
                        "connectedSeeds": 8,
                        "targetFolder": "/storage/emulated/0/Download",
                        "errorMessage": null
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(jsonString)
        assertTrue(root.getBoolean("success"))

        val sObj = root.getJSONObject("settings")
        val settings = TvShareClient.RemoteDownloadSettings(
            downloadDir = sObj.optString("downloadDir", ""),
            freeBytes = sObj.optLong("freeBytes", 0L),
            totalBytes = sObj.optLong("totalBytes", 0L),
            networkPolicy = sObj.optString("networkPolicy", "WIFI_ONLY"),
            legalAccepted = sObj.optBoolean("legalAccepted", true)
        )

        assertEquals("/storage/emulated/0/Download", settings.downloadDir)
        assertEquals(15000000000L, settings.freeBytes)
        assertEquals("WIFI_ONLY", settings.networkPolicy)
        assertTrue(settings.legalAccepted)

        val tArray = root.getJSONArray("transfers")
        assertEquals(1, tArray.length())

        val tObj = tArray.getJSONObject(0)
        val item = TvShareClient.RemoteTransferItem(
            id = tObj.optString("id", ""),
            title = tObj.optString("title", ""),
            type = tObj.optString("type", "DIRECT_HTTP"),
            state = tObj.optString("state", "DOWNLOADING"),
            totalBytes = tObj.optLong("totalBytes", 0L),
            downloadedBytes = tObj.optLong("downloadedBytes", 0L),
            progressPercent = tObj.optInt("progressPercent", 0),
            speedBytesPerSec = tObj.optLong("speedBytesPerSec", 0L),
            etaSeconds = tObj.optLong("etaSeconds", 0L),
            connectedPeers = tObj.optInt("connectedPeers", 0),
            totalPeers = tObj.optInt("totalPeers", 0),
            connectedSeeds = tObj.optInt("connectedSeeds", 0),
            targetFolder = tObj.optString("targetFolder", ""),
            errorMessage = if (tObj.isNull("errorMessage")) null else tObj.optString("errorMessage")
        )

        assertEquals("torrent-hash-123", item.id)
        assertEquals("sample-linux.iso", item.title)
        assertEquals("TORRENT", item.type)
        assertEquals(50, item.progressPercent)
        assertEquals(3500000L, item.speedBytesPerSec)
        assertEquals(15, item.connectedPeers)
        assertEquals(8, item.connectedSeeds)
    }

    @Test
    fun testActionPayloadSerialization() {
        val deletePayload = JSONObject().apply {
            put("id", "transfer-1")
            put("action", "delete")
            put("deleteFiles", true)
        }

        assertEquals("transfer-1", deletePayload.getString("id"))
        assertEquals("delete", deletePayload.getString("action"))
        assertTrue(deletePayload.getBoolean("deleteFiles"))

        val pausePayload = JSONObject().apply {
            put("id", "transfer-1")
            put("action", "pause")
            put("deleteFiles", false)
        }

        assertEquals("pause", pausePayload.getString("action"))
    }
}
