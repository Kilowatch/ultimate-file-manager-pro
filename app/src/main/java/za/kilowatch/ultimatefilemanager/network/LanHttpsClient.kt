package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.util.Log
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * Provides an [HttpsURLConnection] pre-configured for UFM's local-network pairing traffic.
 *
 * Security model:
 *  - The server uses an AndroidKeyStore-generated self-signed cert (unique per device).
 *  - Certificate *authentication* is performed via SHA-256 fingerprint pinning.
 *    The fingerprint is captured during the PIN-authenticated pairing handshake and
 *    stored in [PairedDevice.certFingerprint].
 *  - TLS provides full *encryption*, protecting the PIN and device data from
 *    passive eavesdroppers on the LAN.
 *
 * When [expectedFingerprint] is provided, the TrustManager validates that the server's
 * certificate matches the stored fingerprint. When null (initial pairing only), a
 * trust-on-first-use (TOFU) approach is used — the connection succeeds but the caller
 * must capture the fingerprint from the peer certificates for future pinning.
 */
object LanHttpsClient {

    private const val TAG = "LanHttpsClient"

    /**
     * Opens an [HttpsURLConnection] to [urlStr] (must start with `https://`).
     *
     * @param expectedFingerprint SHA-256 hex fingerprint of the expected server certificate.
     *   Pass `null` during initial pairing (TOFU mode). On all subsequent connections,
     *   pass the stored [PairedDevice.certFingerprint].
     */
    fun openConnection(
        context: Context,
        urlStr: String,
        expectedFingerprint: String? = null
    ): HttpsURLConnection {
        val sslContext = buildSslContext(expectedFingerprint)
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sslContext.socketFactory

        // HostnameVerifier: verify that the connected host matches the URL host (LAN IP).
        // For LAN pairing we connect to IP addresses, so hostname == IP string.
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
            try {
                val peerCerts = session.peerCertificates
                if (peerCerts.isNullOrEmpty()) return@HostnameVerifier false

                // Verify the connection target matches what we requested
                val urlHost = url.host
                hostname.equals(urlHost, ignoreCase = true)
            } catch (e: Exception) {
                Log.e(TAG, "HostnameVerifier error", e)
                false
            }
        }
        return conn
    }

    /**
     * Computes the SHA-256 fingerprint (hex string) of the given certificate.
     */
    fun getCertFingerprint(certificate: java.security.cert.Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(certificate.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun buildSslContext(expectedFingerprint: String?): SSLContext {
        val trustManager = if (expectedFingerprint != null) {
            PinningTrustManager(expectedFingerprint)
        } else {
            TofuTrustManager()
        }
        return SSLContext.getInstance("TLS").also { ctx ->
            ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }

    /**
     * TrustManager that validates the server's certificate SHA-256 fingerprint
     * against the expected (pinned) fingerprint stored from pairing.
     */
    private class PinningTrustManager(
        private val expectedFingerprint: String
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client auth not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) {
                throw CertificateException("Empty certificate chain")
            }

            val serverCert = chain[0]
            val actualFingerprint = getCertFingerprint(serverCert)

            if (!actualFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                throw CertificateException(
                    "Certificate fingerprint mismatch. " +
                    "Expected: $expectedFingerprint, " +
                    "Got: $actualFingerprint"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun getCertFingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Trust-on-first-use (TOFU) TrustManager: used ONLY during the initial
     * PIN-authenticated pairing handshake. Accepts the server certificate so the
     * TLS connection can be established, allowing the caller to capture the
     * fingerprint from [HttpsURLConnection.getServerCertificates] after connect.
     *
     * This is safe because the pairing PIN authenticates the peer — a MITM cannot
     * provide the correct PIN.
     */
    private class TofuTrustManager : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client auth not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) {
                throw CertificateException("Empty certificate chain")
            }
            // Accept the certificate — caller will capture the fingerprint for future pinning.
            // This is only used during PIN-authenticated pairing.
            Log.d(TAG, "TOFU: accepting certificate with fingerprint ${getCertFingerprint(chain[0])}")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun getCertFingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
