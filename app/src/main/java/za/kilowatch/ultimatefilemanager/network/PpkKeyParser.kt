package za.kilowatch.ultimatefilemanager.network

import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Parser for PuTTY Private Key (.ppk) files versions 2 and 3.
 * Converts to Java KeyPair objects compatible with Apache MINA SSHD / Bouncy Castle.
 *
 * Supported key types: ssh-rsa, ssh-ed25519, ecdsa-sha2-nistp256/384/521
 * Supported encryption (v2): none, aes256-cbc
 * Supported encryption (v3): aes256-cbc with Argon2id/Argon2d/Argon2i key derivation
 */
object PpkKeyParser {

    private const val TAG = "PpkKeyParser"

    /**
     * Load a KeyPair from a .ppk file, optionally decrypting with [passphrase].
     * Throws [IllegalArgumentException] or [UnsupportedOperationException] on format issues.
     */
    fun loadKeyPair(file: File, passphrase: String? = null): KeyPair {
        val lines = file.readLines()
        if (lines.isEmpty()) throw IllegalArgumentException("PPK file is empty")

        val header = lines[0]
        val version = when {
            header.startsWith("PuTTY-User-Key-File-3:") -> 3
            header.startsWith("PuTTY-User-Key-File-2:") -> 2
            else -> throw IllegalArgumentException(
                "Unsupported file format. Expected PuTTY PPK, got: ${header.take(40)}"
            )
        }
        val keyType = header.substringAfter(": ").trim()
        Log.d(TAG, "Parsing PPK v$version, type=$keyType")

        // ── Parse named fields and block lines ─────────────────────────────────
        val fields = mutableMapOf<String, String>()
        val pubLines = mutableListOf<String>()
        val privLines = mutableListOf<String>()
        var section = ""
        var pubCount = 0
        var privCount = 0

        for (line in lines.drop(1)) {
            when {
                line.startsWith("Encryption:") -> fields["Encryption"] = field(line)
                line.startsWith("Comment:") -> fields["Comment"] = field(line)
                line.startsWith("Public-Lines:") -> { pubCount = field(line).toInt(); section = "pub" }
                line.startsWith("Private-Lines:") -> { privCount = field(line).toInt(); section = "priv" }
                line.startsWith("Private-MAC:") -> fields["Private-MAC"] = field(line)
                line.startsWith("Private-Hash:") -> fields["Private-Hash"] = field(line)
                // PPK v3 Argon2 fields
                line.startsWith("Key-Derivation:") -> fields["Key-Derivation"] = field(line)
                line.startsWith("Argon2-Memory:") -> fields["Argon2-Memory"] = field(line)
                line.startsWith("Argon2-Passes:") -> fields["Argon2-Passes"] = field(line)
                line.startsWith("Argon2-Parallelism:") -> fields["Argon2-Parallelism"] = field(line)
                line.startsWith("Argon2-Salt:") -> fields["Argon2-Salt"] = field(line)
                section == "pub" && pubLines.size < pubCount -> pubLines.add(line)
                section == "priv" && privLines.size < privCount -> privLines.add(line)
            }
        }

        val encryption = fields["Encryption"] ?: "none"
        val pubBytes = Base64.decode(pubLines.joinToString(""), Base64.NO_WRAP)
        var privBytes = Base64.decode(privLines.joinToString(""), Base64.NO_WRAP)

        // ── Decrypt private key if encrypted ───────────────────────────────────
        if (encryption != "none") {
            if (passphrase == null) throw IllegalArgumentException("Key is encrypted but no passphrase was provided")
            privBytes = when (version) {
                2 -> decryptV2(privBytes, passphrase, encryption)
                3 -> decryptV3(privBytes, passphrase, encryption, fields)
                else -> throw IllegalArgumentException("Unknown PPK version: $version")
            }
        }

        // ── Build KeyPair ───────────────────────────────────────────────────────
        return when (keyType) {
            "ssh-rsa" -> parseRsa(pubBytes, privBytes)
            "ssh-ed25519" -> parseEd25519(pubBytes, privBytes)
            "ecdsa-sha2-nistp256" -> parseEcdsa(pubBytes, privBytes, "secp256r1")
            "ecdsa-sha2-nistp384" -> parseEcdsa(pubBytes, privBytes, "secp384r1")
            "ecdsa-sha2-nistp521" -> parseEcdsa(pubBytes, privBytes, "secp521r1")
            else -> throw UnsupportedOperationException("Unsupported PPK key type: $keyType")
        }
    }

    // ── Decryption ─────────────────────────────────────────────────────────────

    /** PPK v2: two SHA-1 passes to derive a 256-bit AES key, zero IV. */
    private fun decryptV2(data: ByteArray, passphrase: String, encryption: String): ByteArray {
        if (encryption != "aes256-cbc") throw UnsupportedOperationException("Unsupported PPK v2 encryption: $encryption")
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(byteArrayOf(0, 0, 0, 0)); sha.update(pw); val h0 = sha.digest()
        sha.update(byteArrayOf(0, 0, 0, 1)); sha.update(pw); val h1 = sha.digest()
        val key = (h0 + h1).copyOf(32)
        return aesCbcDecrypt(data, key, ByteArray(16))
    }

    /** PPK v3: Argon2-based key derivation. */
    private fun decryptV3(data: ByteArray, passphrase: String, encryption: String, fields: Map<String, String>): ByteArray {
        if (encryption != "aes256-cbc") throw UnsupportedOperationException("Unsupported PPK v3 encryption: $encryption")
        val kd = fields["Key-Derivation"] ?: throw IllegalArgumentException("Missing Key-Derivation in PPK v3")
        val mem = fields["Argon2-Memory"]?.toInt() ?: throw IllegalArgumentException("Missing Argon2-Memory")
        val passes = fields["Argon2-Passes"]?.toInt() ?: throw IllegalArgumentException("Missing Argon2-Passes")
        val parallelism = fields["Argon2-Parallelism"]?.toInt() ?: throw IllegalArgumentException("Missing Argon2-Parallelism")
        val saltHex = fields["Argon2-Salt"] ?: throw IllegalArgumentException("Missing Argon2-Salt")
        val salt = hexToBytes(saltHex)

        val type = when (kd.lowercase()) {
            "argon2d" -> Argon2Parameters.ARGON2_d
            "argon2i" -> Argon2Parameters.ARGON2_i
            "argon2id" -> Argon2Parameters.ARGON2_id
            else -> throw UnsupportedOperationException("Unknown Argon2 variant: $kd")
        }

        val params = Argon2Parameters.Builder(type)
            .withMemoryAsKB(mem)
            .withIterations(passes)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()

        // Derive key (32 bytes) + IV (16 bytes) + MAC key (32 bytes) = 80 bytes total
        val derived = ByteArray(80)
        Argon2BytesGenerator().apply { init(params) }
            .generateBytes(passphrase.toByteArray(Charsets.UTF_8), derived)

        val key = derived.copyOf(32)
        val iv = derived.copyOfRange(32, 48)
        return aesCbcDecrypt(data, key, iv)
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ── Key parsers ────────────────────────────────────────────────────────────

    private fun parseRsa(pubBytes: ByteArray, privBytes: ByteArray): KeyPair {
        val pub = ByteBuffer.wrap(pubBytes)
        readString(pub) // "ssh-rsa"
        val e = readMpint(pub)
        val n = readMpint(pub)

        val priv = ByteBuffer.wrap(privBytes)
        val d = readMpint(priv)
        val p = readMpint(priv)
        val q = readMpint(priv)
        val iqmp = readMpint(priv) // modular inverse of q mod p, used to derive CRT exponents

        val kf = KeyFactory.getInstance("RSA")
        val dp = d.mod(p.subtract(BigInteger.ONE))
        val dq = d.mod(q.subtract(BigInteger.ONE))
        return KeyPair(
            kf.generatePublic(RSAPublicKeySpec(n, e)),
            kf.generatePrivate(RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, iqmp))
        )
    }

    private fun parseEd25519(pubBytes: ByteArray, privBytes: ByteArray): KeyPair {
        val pub = ByteBuffer.wrap(pubBytes)
        readString(pub) // "ssh-ed25519"
        val pubPoint = readBytes(pub) // 32-byte EC point

        val priv = ByteBuffer.wrap(privBytes)
        val seed = readBytes(priv) // 32-byte seed

        // Build using Bouncy Castle's Ed25519 parameters → PKCS#8 / X.509 encoding
        val bcPriv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val bcPub = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubPoint, 0)

        val privKeyInfo = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(bcPriv)
        val pubKeyInfo = org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(bcPub)

        val kf = KeyFactory.getInstance("Ed25519", "BC")
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(pubKeyInfo.encoded)),
            kf.generatePrivate(PKCS8EncodedKeySpec(privKeyInfo.encoded))
        )
    }

    private fun parseEcdsa(pubBytes: ByteArray, privBytes: ByteArray, bcCurveName: String): KeyPair {
        val pub = ByteBuffer.wrap(pubBytes)
        readString(pub) // algo, e.g. "ecdsa-sha2-nistp256"
        readString(pub) // curve id, e.g. "nistp256"
        val pubPoint = readBytes(pub) // uncompressed point: 0x04 || x || y

        val priv = ByteBuffer.wrap(privBytes)
        val privScalar = readMpint(priv)

        val curveSpec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(bcCurveName)
        val point = curveSpec.curve.decodePoint(pubPoint)
        val x = point.affineXCoord.toBigInteger()
        val y = point.affineYCoord.toBigInteger()

        val kf = KeyFactory.getInstance("EC", "BC")
        val jcaSpec = org.bouncycastle.jce.spec.ECNamedCurveSpec(
            bcCurveName, curveSpec.curve, curveSpec.g, curveSpec.n, curveSpec.h
        )
        return KeyPair(
            kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), jcaSpec)),
            kf.generatePrivate(ECPrivateKeySpec(privScalar, jcaSpec))
        )
    }

    // ── SSH wire-format helpers ────────────────────────────────────────────────

    private fun readString(buf: ByteBuffer): String = String(readBytes(buf), Charsets.UTF_8)

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        return ByteArray(len).also { buf.get(it) }
    }

    private fun readMpint(buf: ByteBuffer): BigInteger {
        val len = buf.int
        val bytes = ByteArray(len).also { buf.get(it) }
        return if (len == 0) BigInteger.ZERO else BigInteger(1, bytes)
    }

    private fun field(line: String) = line.substringAfter(": ").trim()

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        return ByteArray(len / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
