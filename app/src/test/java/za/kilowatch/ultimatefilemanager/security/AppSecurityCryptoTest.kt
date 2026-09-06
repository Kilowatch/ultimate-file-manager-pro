package za.kilowatch.ultimatefilemanager.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSecurityCryptoTest {

    @Test
    fun testSecurityModeMapping() {
        assertEquals("none", SecurityMode.NONE.key)
        assertEquals("pin", SecurityMode.PIN.key)
        assertEquals("password", SecurityMode.PASSWORD.key)
        assertEquals("biometric", SecurityMode.BIOMETRIC.key)

        assertEquals(SecurityMode.NONE, SecurityMode.fromKey("none"))
        assertEquals(SecurityMode.PIN, SecurityMode.fromKey("pin"))
        assertEquals(SecurityMode.PASSWORD, SecurityMode.fromKey("password"))
        assertEquals(SecurityMode.BIOMETRIC, SecurityMode.fromKey("biometric"))
        assertEquals(SecurityMode.NONE, SecurityMode.fromKey("unknown"))
        assertEquals(SecurityMode.NONE, SecurityMode.fromKey(null))
    }

    @Test
    fun testLockTimeoutConstants() {
        assertEquals(-1L, AppSecurityManager.TIMEOUT_FRESH_OPEN_ONLY)
        assertEquals(0L, AppSecurityManager.TIMEOUT_IMMEDIATELY)
        assertEquals(60_000L, AppSecurityManager.TIMEOUT_ONE_MINUTE)
        assertEquals(300_000L, AppSecurityManager.TIMEOUT_FIVE_MINUTES)
    }

    @Test
    fun testRecoveryKeyGeneration() {
        val manager = za.kilowatch.ultimatefilemanager.security.AppSecurityManager.getInstance(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        val key = manager.generateRecoveryKey()
        assertNotNull(key)
        assertEquals(16, key.length)
        assertTrue(key.all { it.isLetterOrDigit() })
    }

    @Test
    fun testPbkdf2HashAndVerifyPin() = runBlocking {
        val manager = za.kilowatch.ultimatefilemanager.security.AppSecurityManager.getInstance(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        val pin = "1234"
        val hash = manager.hashCredential(pin)
        assertNotNull(hash)
        assertTrue(hash.contains(":"))

        assertTrue(manager.verifyCredential(pin, hash))
        assertFalse(manager.verifyCredential("4321", hash))
        assertFalse(manager.verifyCredential("12345", hash))
        assertFalse(manager.verifyCredential("", hash))
    }

    @Test
    fun testPbkdf2HashAndVerifyPassword() = runBlocking {
        val manager = za.kilowatch.ultimatefilemanager.security.AppSecurityManager.getInstance(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        val password = "MySecureP@ssw0rd!#$%"
        val hash = manager.hashCredential(password)
        assertNotNull(hash)

        assertTrue(manager.verifyCredential(password, hash))
        assertFalse(manager.verifyCredential("WrongP@ssw0rd", hash))
        assertFalse(manager.verifyCredential("mysecurep@ssw0rd!#$%", hash)) // case sensitive
    }
}
