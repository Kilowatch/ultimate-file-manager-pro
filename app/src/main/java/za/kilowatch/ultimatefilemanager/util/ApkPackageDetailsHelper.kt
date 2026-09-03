package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Utility for parsing and extracting application details from .apk, .xapk, .apks, and .apkm files.
 * Provides version comparison with installed apps, SDK level formatting,
 * signature certificate extraction, and binary manifest decoding.
 */
object ApkPackageDetailsHelper {

    enum class InstallState {
        NOT_INSTALLED,
        SAME_VERSION,
        UPDATE_AVAILABLE,
        NEWER_INSTALLED
    }

    data class CertificateInfo(
        val subject: String,
        val issuer: String,
        val validFrom: String,
        val validTo: String,
        val algorithm: String,
        val serialNumber: String,
        val md5: String,
        val sha1: String,
        val sha256: String
    )

    data class ApkPackageDetails(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val minSdkDisplay: String,
        val targetSdkDisplay: String,
        val installedVersionName: String?,
        val installedVersionCode: Long?,
        val installState: InstallState,
        val icon: Drawable?,
        val isSplit: Boolean,
        val splitNames: List<String>,
        val architectures: List<String>,
        val certificateInfo: CertificateInfo?
    )

    fun isApkOrBundle(nameOrPath: String): Boolean {
        val ext = nameOrPath.substringAfterLast('.', "").lowercase()
        return ext in setOf("apk", "xapk", "apks", "apkm")
    }

    /**
     * Maps an Android API level (e.g. 21, 33, 34) to user-friendly version string.
     * Matches the format: "13.0 (SDK 33 T)" and "5.0 (SDK 21 L)".
     */
    fun formatSdkVersion(sdk: Int): String {
        if (sdk <= 0) return "Unknown"
        val (version, letter) = when (sdk) {
            1 -> "1.0" to ""
            2 -> "1.1" to ""
            3 -> "1.5" to "Cupcake"
            4 -> "1.6" to "Donut"
            5 -> "2.0" to "Eclair"
            6 -> "2.0.1" to "Eclair"
            7 -> "2.1" to "Eclair"
            8 -> "2.2" to "Froyo"
            9 -> "2.3" to "Gingerbread"
            10 -> "2.3.3" to "Gingerbread"
            11 -> "3.0" to "Honeycomb"
            12 -> "3.1" to "Honeycomb"
            13 -> "3.2" to "Honeycomb"
            14 -> "4.0" to "ICS"
            15 -> "4.0.3" to "ICS"
            16 -> "4.1" to "JB"
            17 -> "4.2" to "JB"
            18 -> "4.3" to "JB"
            19 -> "4.4" to "K"
            20 -> "4.4W" to "W"
            21 -> "5.0" to "L"
            22 -> "5.1" to "L"
            23 -> "6.0" to "M"
            24 -> "7.0" to "N"
            25 -> "7.1" to "N"
            26 -> "8.0" to "O"
            27 -> "8.1" to "O"
            28 -> "9.0" to "P"
            29 -> "10.0" to "Q"
            30 -> "11.0" to "R"
            31 -> "12.0" to "S"
            32 -> "12.1" to "S_V2"
            33 -> "13.0" to "T"
            34 -> "14.0" to "U"
            35 -> "15.0" to "V"
            36 -> "16.0" to "W"
            else -> "${sdk - 20}.0" to ""
        }
        return if (letter.isNotEmpty()) {
            "$version (SDK $sdk $letter)"
        } else {
            "$version (SDK $sdk)"
        }
    }

    /**
     * Parses the package details from a local APK, XAPK, APKS, or APKM file.
     */
    fun parse(context: Context, file: File): ApkPackageDetails? {
        if (!file.exists() || !file.canRead()) return null
        val ext = file.extension.lowercase()

        return if (ext == "apk") {
            parseSingleApk(context, file)
        } else if (ext in setOf("xapk", "apks", "apkm")) {
            parseBundle(context, file)
        } else {
            null
        }
    }

    private fun parseSingleApk(context: Context, file: File): ApkPackageDetails? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
        }

        val pi = try {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (_: Exception) { null } ?: return null

        val appInfo = pi.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath

        val packageName = pi.packageName ?: ""
        val appName = try {
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { file.nameWithoutExtension }

        val versionName = pi.versionName ?: "1.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }

        val targetSdk = appInfo.targetSdkVersion
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appInfo.minSdkVersion
        } else 1

        val icon = safeGetIcon(context, appInfo, null, packageName)

        val (installedVerName, installedVerCode, installState) = checkInstallState(pm, packageName, versionCode)

        // Architectures from ZIP
        val architectures = extractArchitectures(file)

        // Certificate Info
        val certInfo = extractCertificate(pi, file)

        return ApkPackageDetails(
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            minSdkDisplay = formatSdkVersion(minSdk),
            targetSdkDisplay = formatSdkVersion(targetSdk),
            installedVersionName = installedVerName,
            installedVersionCode = installedVerCode,
            installState = installState,
            icon = icon,
            isSplit = false,
            splitNames = emptyList(),
            architectures = architectures,
            certificateInfo = certInfo
        )
    }

    private fun parseBundle(context: Context, bundleFile: File): ApkPackageDetails? {
        val pm = context.packageManager
        var tempBaseApk: File? = null

        try {
            var manifestJson: JSONObject? = null
            var rootIconBitmap: android.graphics.Bitmap? = null
            val apkEntries = mutableListOf<String>()
            val architectures = mutableSetOf<String>()

            ZipFile(bundleFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    val entryLower = name.lowercase()

                    if (name.equals("manifest.json", ignoreCase = true)) {
                        try {
                            val content = zip.getInputStream(entry).use { it.reader().readText() }
                            manifestJson = JSONObject(content)
                        } catch (_: Exception) {}
                    } else if (entryLower == "icon.png" || entryLower.endsWith("/icon.png") ||
                        entryLower == "icon.webp" || entryLower.endsWith("/icon.webp")) {
                        try {
                            rootIconBitmap = BitmapFactory.decodeStream(zip.getInputStream(entry))
                        } catch (_: Exception) {}
                    } else if (name.endsWith(".apk", ignoreCase = true)) {
                        apkEntries.add(name.substringAfterLast('/'))
                        if (name.contains("split_config.", ignoreCase = true)) {
                            val abiMatch = Regex("""split_config\.(arm64.v8a|armeabi.v7a|x86_64|x86)\.apk""", RegexOption.IGNORE_CASE).find(name)
                            abiMatch?.groupValues?.getOrNull(1)?.let { architectures.add(it) }
                        }
                    } else if (name.startsWith("lib/", ignoreCase = true)) {
                        val parts = name.split('/')
                        if (parts.size >= 2 && parts[1].isNotEmpty()) {
                            architectures.add(parts[1])
                        }
                    }
                }

                if (rootIconBitmap == null && manifestJson != null) {
                    val iconName = manifestJson.optString("icon")
                    if (iconName.isNotEmpty()) {
                        val iconEntry = zip.getEntry(iconName)
                            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(iconName, ignoreCase = true) }
                        if (iconEntry != null) {
                            try {
                                rootIconBitmap = BitmapFactory.decodeStream(zip.getInputStream(iconEntry))
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Extract base.apk to temp file to parse with PackageManager
                val baseEntry = zip.getEntry("base.apk")
                    ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

                if (baseEntry != null) {
                    tempBaseApk = File(context.cacheDir, "props_bundle_base_${System.currentTimeMillis()}.apk")
                    zip.getInputStream(baseEntry).use { input ->
                        tempBaseApk!!.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            // Parse base APK with PackageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
            }

            val baseApk = tempBaseApk
            val pi = baseApk?.let {
                try { pm.getPackageArchiveInfo(it.absolutePath, flags) } catch (_: Exception) { null }
            }

            val appInfo = pi?.applicationInfo
            if (appInfo != null && baseApk != null) {
                appInfo.sourceDir = baseApk.absolutePath
                appInfo.publicSourceDir = baseApk.absolutePath
            }

            val packageName = pi?.packageName
                ?: manifestJson?.optString("package_name")
                ?: bundleFile.nameWithoutExtension

            val appName = (appInfo?.let { pm.getApplicationLabel(it).toString() })
                ?: manifestJson?.optString("name")
                ?: bundleFile.nameWithoutExtension

            val versionName = pi?.versionName
                ?: manifestJson?.optString("version_name")
                ?: "1.0"

            val versionCode = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi?.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi?.versionCode?.toLong()
            }) ?: manifestJson?.optLong("version_code") ?: 1L

            val targetSdk = appInfo?.targetSdkVersion
                ?: manifestJson?.optInt("target_sdk_version") ?: 21

            val minSdk = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appInfo?.minSdkVersion
            } else 1) ?: manifestJson?.optInt("min_sdk_version") ?: 1

            val iconDrawable = safeGetIcon(context, appInfo, rootIconBitmap, packageName)

            val (installedVerName, installedVerCode, installState) = checkInstallState(pm, packageName, versionCode)

            val certInfo = if (pi != null && baseApk != null) {
                extractCertificate(pi, baseApk)
            } else null

            return ApkPackageDetails(
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                minSdkDisplay = formatSdkVersion(minSdk),
                targetSdkDisplay = formatSdkVersion(targetSdk),
                installedVersionName = installedVerName,
                installedVersionCode = installedVerCode,
                installState = installState,
                icon = iconDrawable,
                isSplit = apkEntries.size > 1,
                splitNames = apkEntries,
                architectures = architectures.toList(),
                certificateInfo = certInfo
            )

        } catch (_: Exception) {
            return null
        } finally {
            tempBaseApk?.delete()
        }
    }

    private fun safeGetIcon(
        context: Context,
        appInfo: android.content.pm.ApplicationInfo?,
        rootIconBitmap: android.graphics.Bitmap?,
        packageName: String?
    ): Drawable? {
        if (rootIconBitmap != null) {
            return BitmapDrawable(context.resources, rootIconBitmap)
        }
        val pm = context.packageManager
        if (appInfo != null) {
            try {
                val rawDrawable = appInfo.loadIcon(pm)
                if (rawDrawable != null) {
                    return toSafeDrawable(context, rawDrawable)
                }
            } catch (_: Exception) {}
        }
        if (!packageName.isNullOrEmpty()) {
            try {
                val installedDrawable = pm.getApplicationIcon(packageName)
                return toSafeDrawable(context, installedDrawable)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun toSafeDrawable(context: Context, drawable: Drawable): Drawable {
        return try {
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 144
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 144
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            BitmapDrawable(context.resources, bitmap)
        } catch (_: Exception) {
            drawable
        }
    }

    private fun checkInstallState(
        pm: PackageManager,
        packageName: String,
        fileVersionCode: Long
    ): Triple<String?, Long?, InstallState> {
        val installedPkg = try {
            pm.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }

        if (installedPkg == null) {
            return Triple(null, null, InstallState.NOT_INSTALLED)
        }

        val installedVerName = installedPkg.versionName
        val installedVerCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedPkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installedPkg.versionCode.toLong()
        }

        val state = when {
            fileVersionCode > installedVerCode -> InstallState.UPDATE_AVAILABLE
            fileVersionCode < installedVerCode -> InstallState.NEWER_INSTALLED
            else -> InstallState.SAME_VERSION
        }

        return Triple(installedVerName, installedVerCode, state)
    }

    private fun extractArchitectures(apkFile: File): List<String> {
        val archs = mutableSetOf<String>()
        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/") && name.count { it == '/' } >= 2) {
                        val arch = name.split('/')[1]
                        if (arch.isNotEmpty()) archs.add(arch)
                    }
                }
            }
        } catch (_: Exception) {}
        return archs.toList().sorted()
    }

    private fun extractCertificate(pi: PackageInfo, apkFile: File): CertificateInfo? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.apkContentsSigners
                ?: pi.signingInfo?.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pi.signatures
        }

        var cert: X509Certificate? = null
        if (!signatures.isNullOrEmpty()) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                cert = cf.generateCertificate(ByteArrayInputStream(signatures[0].toByteArray())) as? X509Certificate
            } catch (_: Exception) {}
        }

        // Fallback: read certificate from META-INF in ZIP
        if (cert == null) {
            try {
                ZipFile(apkFile).use { zip ->
                    val rsaEntry = zip.entries().asSequence().firstOrNull {
                        it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA", true) || it.name.endsWith(".DSA", true) || it.name.endsWith(".EC", true))
                    }
                    if (rsaEntry != null) {
                        val cf = CertificateFactory.getInstance("X.509")
                        zip.getInputStream(rsaEntry).use { input ->
                            cert = cf.generateCertificates(input).filterIsInstance<X509Certificate>().firstOrNull()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val validCert = cert ?: return null

        val md5 = computeDigest(validCert, "MD5")
        val sha1 = computeDigest(validCert, "SHA-1")
        val sha256 = computeDigest(validCert, "SHA-256")

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val validFrom = try { sdf.format(validCert.notBefore) } catch (_: Exception) { validCert.notBefore.toString() }
        val validTo = try { sdf.format(validCert.notAfter) } catch (_: Exception) { validCert.notAfter.toString() }

        return CertificateInfo(
            subject = validCert.subjectX500Principal?.name ?: validCert.subjectDN?.name ?: "",
            issuer = validCert.issuerX500Principal?.name ?: validCert.issuerDN?.name ?: "",
            validFrom = validFrom,
            validTo = validTo,
            algorithm = validCert.sigAlgName ?: "Unknown",
            serialNumber = validCert.serialNumber?.toString(16)?.uppercase() ?: "",
            md5 = md5,
            sha1 = sha1,
            sha256 = sha256
        )
    }

    private fun computeDigest(cert: X509Certificate, algorithm: String): String {
        return try {
            val md = MessageDigest.getInstance(algorithm)
            val digest = md.digest(cert.encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) { "" }
    }

    /**
     * Decodes the AndroidManifest.xml from an APK or XAPK into a formatted XML string.
     */
    fun decodeManifest(file: File): String? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()

        return try {
            if (ext == "apk") {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                    zip.getInputStream(entry).use { AxmlDecoder.decode(it) }
                }
            } else {
                // XAPK / APKS - find base.apk or AndroidManifest.xml
                ZipFile(file).use { zip ->
                    val baseEntry = zip.getEntry("base.apk")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

                    if (baseEntry != null) {
                        val baseBytes = zip.getInputStream(baseEntry).use { it.readBytes() }
                        java.util.zip.ZipInputStream(ByteArrayInputStream(baseBytes)).use { zis ->
                            var subEntry = zis.nextEntry
                            while (subEntry != null) {
                                if (subEntry.name.equals("AndroidManifest.xml", ignoreCase = true)) {
                                    val manifestBytes = zis.readBytes()
                                    return AxmlDecoder.decode(manifestBytes)
                                }
                                subEntry = zis.nextEntry
                            }
                        }
                    }
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
