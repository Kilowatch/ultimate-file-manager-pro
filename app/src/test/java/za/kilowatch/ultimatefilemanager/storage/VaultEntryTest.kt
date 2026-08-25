package za.kilowatch.ultimatefilemanager.storage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VaultEntryTest {

    @Test
    fun vaultEntryCreation() {
        val entry = VaultEntry(
            id = "test-uuid-1234",
            displayName = "Secret Documents",
            originalRoot = "/storage/emulated/0/Documents/Secret",
            files = listOf("doc1.pdf", "subfolder/doc2.pdf")
        )

        assertEquals("test-uuid-1234", entry.id)
        assertEquals("Secret Documents", entry.displayName)
        assertEquals("/storage/emulated/0/Documents/Secret", entry.originalRoot)
        assertEquals(2, entry.files.size)
        assertEquals("doc1.pdf", entry.files[0])
        assertEquals("subfolder/doc2.pdf", entry.files[1])
    }

    @Test
    fun legacyPlainMetadataParsing() {
        val jsonString = """
            {
                "id": "vault-001",
                "displayName": "My Photos",
                "originalRoot": "/storage/emulated/0/DCIM/Camera",
                "files": ["photo1.jpg", "photo2.jpg", "vacation/beach.jpg"]
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val filesJson = json.getJSONArray("files")
        val files = mutableListOf<String>()
        for (i in 0 until filesJson.length()) {
            val item = filesJson.getString(i)
            files.add(if (item.startsWith("enc:")) item.removePrefix("enc:") else item)
        }

        val entry = VaultEntry(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            originalRoot = json.getString("originalRoot"),
            files = files
        )

        assertEquals("vault-001", entry.id)
        assertEquals("My Photos", entry.displayName)
        assertEquals(3, entry.files.size)
        assertEquals("vacation/beach.jpg", entry.files[2])
    }

    @Test
    fun perFieldEncryptedMetadataParsing() {
        val jsonString = """
            {
                "id": "vault-002",
                "displayName": "enc:TXlTZWNyZXRz",
                "originalRoot": "enc:L3N0b3JhZ2UvZW11bGF0ZWQvMC9WYXVsdA==",
                "files": ["enc:ZmlsZTEudHh0", "enc:ZmlsZTIudHh0"]
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        assertTrue(json.getString("displayName").startsWith("enc:"))
        assertTrue(json.getString("originalRoot").startsWith("enc:"))

        val filesArr = json.getJSONArray("files")
        assertEquals(2, filesArr.length())
        assertTrue(filesArr.getString(0).startsWith("enc:"))
    }

    @Test
    fun bulkPayloadMetadataStructure() {
        val json = JSONObject().apply {
            put("id", "vault-003")
            put("displayName", "enc:TXlGaWxlcw==")
            put("originalRoot", "enc:L3BhdGg=")
            put("filesPayload", "enc:W1widGVzdDEudHh0XCIsXCJ0ZXN0Mi50eHRcIl0=")
            put("files", JSONArray(listOf("enc:dGVzdDEudHh0", "enc:dGVzdDIudHh0")))
        }

        assertTrue(json.has("filesPayload"))
        assertTrue(json.has("files"))
        assertEquals("vault-003", json.getString("id"))
    }
}
