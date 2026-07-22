import java.util.Properties
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

/**
 * Gradle ArtifactTransform that re-zips ftpserver-core JARs, omitting
 * FtpResponseEncoder.class.  This ensures D8/R8 never sees that class
 * from the upstream library — only our local thread-safe patch survives
 * into the final DEX, eliminating the "defined multiple times" error.
 */
abstract class StripFtpEncoder : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val output = outputs.file(input.name)
        if (input.name.startsWith("ftpserver-core")) {
            ZipInputStream(input.inputStream().buffered()).use { zin ->
                ZipOutputStream(output.outputStream().buffered()).use { zout ->
                    var entry: ZipEntry? = zin.nextEntry
                    while (entry != null) {
                        if (entry.name != "org/apache/ftpserver/listener/nio/FtpResponseEncoder.class") {
                            zout.putNextEntry(ZipEntry(entry.name))
                            zin.copyTo(zout)
                            zout.closeEntry()
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
        } else {
            input.copyTo(output)
        }
    }
}

/** Custom attribute used to request the stripped variant of all JARs. */
val strippedJar: Attribute<Boolean> = Attribute.of("za.kilowatch.strippedJar", Boolean::class.javaObjectType)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("app.cash.licensee")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val googleDriveTvClientId = localProperties.getProperty("GOOGLE_DRIVE_TV_CLIENT_ID")
    ?: System.getenv("GOOGLE_DRIVE_TV_CLIENT_ID")
    ?: "YOUR_GOOGLE_DRIVE_TV_CLIENT_ID"

val googleDriveMobileDebugClientId = localProperties.getProperty("GOOGLE_DRIVE_MOBILE_DEBUG_CLIENT_ID")
    ?: System.getenv("GOOGLE_DRIVE_MOBILE_DEBUG_CLIENT_ID")
    ?: "YOUR_MOBILE_DEBUG_CLIENT_ID"

val googleDriveMobileReleaseClientId = localProperties.getProperty("GOOGLE_DRIVE_MOBILE_RELEASE_CLIENT_ID")
    ?: System.getenv("GOOGLE_DRIVE_MOBILE_RELEASE_CLIENT_ID")
    ?: "YOUR_MOBILE_RELEASE_CLIENT_ID"

/** Helper to derive the redirect scheme from a Google Drive Client ID. */
fun getGoogleDriveMobileScheme(clientId: String): String {
    // ID: 123-abc.apps.googleusercontent.com -> Scheme: com.googleusercontent.apps.123-abc
    return "com.googleusercontent.apps." + clientId.split(".")[0]
}

val googleDriveTvClientSecret = localProperties.getProperty("GOOGLE_DRIVE_TV_CLIENT_SECRET")
    ?: localProperties.getProperty("GOOGLE_DRIVE_CLIENT_SECRET")
    ?: System.getenv("GOOGLE_DRIVE_TV_CLIENT_SECRET")
    ?: "YOUR_SECRET_HERE"

val dropboxAppKey = localProperties.getProperty("DROPBOX_APP_KEY")
    ?: System.getenv("DROPBOX_APP_KEY")
    ?: "YOUR_KEY_HERE"

val dropboxAppSecret = localProperties.getProperty("DROPBOX_APP_SECRET")
    ?: System.getenv("DROPBOX_APP_SECRET")
    ?: "YOUR_SECRET_HERE"

// ── Single source of truth — bump these on every release ────────────────────
val appVersionCode = 206          // Mobile versionCode; TV = this + 1
val appVersionName = "1.7.3"      // Shown in Play Store listing
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "za.kilowatch.ultimatefilemanager"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "za.kilowatch.ultimatefilemanager"
        minSdk = 26
        targetSdk {
            version = release(36)
        }
        // versionCode / versionName are set per device flavor below

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Explicitly define supported languages to exclude invalid language resources 
        // (like 'tv') introduced by local folders or third-party libraries.
        resourceConfigurations += setOf("en", "ar", "de", "es", "fr", "hi", "id", "in", "ja", "ko", "pt", "ru", "tr", "uk")
        
        buildConfigField("Boolean", "IS_FOSS", "false")
    }

    // ── Two-dimension flavor model ────────────────────────────────────────────────
    //  Dimension 1 — "device": mobile (phones/tablets) vs tv (Android TV / Fire TV)
    //  Dimension 2 — "store" : google (Google Play) vs amazon (Amazon Appstore)
    //
    //  This gives 4 build variants:
    //    mobileGoogle  →  Google Play, phone/tablet
    //    mobileAmazon  →  Amazon Appstore, phone/tablet (Fire tablets)
    //    tvGoogle      →  Google Play, Android TV
    //    tvAmazon      →  Amazon Appstore, Fire TV
    // ─────────────────────────────────────────────────────────────────────────────
    flavorDimensions += listOf("device", "store")
    productFlavors {

        // ── Device dimension ─────────────────────────────────────────────────────
        create("mobile") {
            dimension = "device"
            // Mobile version: leanback not required (allows phones/tablets)
            versionCode = appVersionCode
            versionName = appVersionName
        }
        create("tv") {
            dimension = "device"
            // TV version: always one ahead of mobile so both can coexist on the same account
            versionCode = appVersionCode + 1
            versionName = appVersionName
        }

        // ── Store dimension ───────────────────────────────────────────────────────
        create("google") {
            dimension = "store"
            versionNameSuffix = "-GOOGLE"
            // Google Play Billing active; Amazon IAP never used in this variant.
            buildConfigField("Boolean", "AMAZON_IAP_ENABLED", "false")
            buildConfigField("Boolean", "AMAZON_RATING_ENABLED", "false")
            // OneDrive (MSAL) is available on Google Play builds.
            buildConfigField("Boolean", "ONEDRIVE_ENABLED", "true")
            // Box — available on Google Play builds.
            buildConfigField("Boolean", "BOX_ENABLED", "true")
        }
        create("amazon") {
            dimension = "store"
            versionNameSuffix = "-AMAZON"
            // ─── FLIP TO true ONCE YOU ARE READY TO RELEASE ON THE AMAZON APPSTORE ───
            buildConfigField("Boolean", "AMAZON_IAP_ENABLED", "true")
            buildConfigField("Boolean", "AMAZON_RATING_ENABLED", "true")
            // OneDrive is disabled on Amazon builds: MSAL pulls in Google Play Services
            // (com.google.android.gms:*) which Amazon's policy scanner rejects.
            buildConfigField("Boolean", "ONEDRIVE_ENABLED", "false")
            // Box — available on Amazon builds (no Google Play Services dependency).
            buildConfigField("Boolean", "BOX_ENABLED", "true")
        }

        create("foss") {
            dimension = "store"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-FOSS"
            // All proprietary SDKs disabled — pure open-source build.
            buildConfigField("Boolean", "AMAZON_IAP_ENABLED", "false")
            buildConfigField("Boolean", "AMAZON_RATING_ENABLED", "false")
            buildConfigField("Boolean", "ONEDRIVE_ENABLED", "false")
            buildConfigField("Boolean", "GOOGLE_DRIVE_ENABLED", "false")
            buildConfigField("Boolean", "DROPBOX_ENABLED", "false")
            buildConfigField("Boolean", "BOX_ENABLED", "false")
            buildConfigField("Boolean", "IS_FOSS", "true")
        }

    }


    buildTypes {
        debug {
            // Debug build: use the debug Android OAuth client
            buildConfigField(
                "String", "GOOGLE_DRIVE_MOBILE_CLIENT_ID",
                "\"$googleDriveMobileDebugClientId\""
            )
            manifestPlaceholders["googleDriveMobileScheme"] = getGoogleDriveMobileScheme(googleDriveMobileDebugClientId)
            // TV client — same for debug and release (no SHA-1 required)
            buildConfigField(
                "String", "GOOGLE_DRIVE_TV_CLIENT_ID",
                "\"$googleDriveTvClientId\""
            )
            buildConfigField(
                "String", "GOOGLE_DRIVE_TV_CLIENT_SECRET",
                "\"$googleDriveTvClientSecret\""
            )
            buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
            buildConfigField("String", "DROPBOX_APP_SECRET", "\"$dropboxAppSecret\"")
            manifestPlaceholders["dropboxAuthScheme"] = "db-$dropboxAppKey"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "full"
            }
            // Release build: use the production Android OAuth client
            buildConfigField(
                "String", "GOOGLE_DRIVE_MOBILE_CLIENT_ID",
                "\"$googleDriveMobileReleaseClientId\""
            )
            manifestPlaceholders["googleDriveMobileScheme"] = getGoogleDriveMobileScheme(googleDriveMobileReleaseClientId)
            // TV client — same for debug and release (no SHA-1 required)
            buildConfigField(
                "String", "GOOGLE_DRIVE_TV_CLIENT_ID",
                "\"$googleDriveTvClientId\""
            )
            buildConfigField(
                "String", "GOOGLE_DRIVE_TV_CLIENT_SECRET",
                "\"$googleDriveTvClientSecret\""
            )
            buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
            buildConfigField("String", "DROPBOX_APP_SECRET", "\"$dropboxAppSecret\"")
            manifestPlaceholders["dropboxAuthScheme"] = "db-$dropboxAppKey"
        }
    }
    // ── NDK / libnfs native build ────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                // Ktor / Netty conflict resolution
                "META-INF/io.netty.versions.properties",
                "META-INF/INDEX.LIST"
            )
        }
        jniLibs {
            // libspake2.so (bundled by libadb-android) has no debug symbols to strip.
            // Opt it out of stripping to suppress the "Unable to strip" warning.
            keepDebugSymbols += setOf("**/libspake2.so")
        }
    }

    configurations.all {
        // Removed resolutionStrategy override
    }

    sourceSets {
        getByName("google") {
            java.srcDirs("src/google/java", "src/nonfoss/java")
        }
        getByName("amazon") {
            java.srcDirs("src/amazon/java", "src/nonfoss/java")
        }
    }
}

// ── Artifact transform registration ──────────────────────────────────────────
// Tell Gradle that plain JARs start with strippedJar=false, and register the
// StripFtpEncoder transform to produce strippedJar=true variants on demand.
dependencies {
    attributesSchema { attribute(strippedJar) }
    artifactTypes.named("jar") { attributes.attribute(strippedJar, false) }
    registerTransform(StripFtpEncoder::class) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
            .attribute(strippedJar, false)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
            .attribute(strippedJar, true)
    }
}

// Request the stripped variant on every configuration that feeds the compiler
// or the D8/R8 dexer (i.e. all *CompileClasspath and *RuntimeClasspath confs).
configurations.matching {
    it.name.endsWith("CompileClasspath", ignoreCase = true) ||
    it.name.endsWith("RuntimeClasspath", ignoreCase = true)
}.configureEach {
    attributes.attribute(strippedJar, true)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    // Ktor embedded HTTP server for FileServer (replaces NanoHTTPD in FileServer.kt)
    // SEC: upgraded 3.4.2 → 3.5.0 to pick up patched Netty ≥ 4.1.135.Final (CVE-2026-50010)
    implementation("io.ktor:ktor-server-core:3.5.0")
    implementation("io.ktor:ktor-server-netty:3.5.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-server-status-pages:3.5.0")
    // NanoHTTPD retained for PairingServer (HTTPS/TLS TV pairing — uses makeSecure())
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Google Play Billing (Tip Jar — Google variants)
    "googleImplementation"("com.android.billingclient:billing-ktx:8.3.0")
    // Amazon Appstore SDK 3.0.8 (Tip Jar — Amazon variants)
    "amazonImplementation"("com.amazon.device:amazon-appstore-sdk:3.0.8")
    implementation("com.hierynomus:smbj:0.14.0")
    // jcifs-ng 2.1.10 is the last release from the original AgNO3 maintainer.
    // The actively maintained fork (kimmerin/jcifs-ng) has no published Maven artifacts yet.
    // Re-evaluate if the fork publishes releases: https://github.com/kimmerin/jcifs-ng
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("commons-net:commons-net:3.11.1")
    implementation("org.apache.sshd:sshd-core:2.17.1")
    implementation("org.apache.sshd:sshd-sftp:2.17.1")
    implementation("org.apache.sshd:sshd-scp:2.17.1")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")

    // Apache POI 3.17 — legacy MS Office support (DOC/XLS/PPT, compatible with minSdk 24)
    // Modern OOXML formats (DOCX/XLSX/PPTX) are parsed via direct ZIP+XML (no POI OOXML needed)
    implementation("org.apache.poi:poi:3.17")
    implementation("org.apache.poi:poi-scratchpad:3.17")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")  // latest stable (alpha: 1.4.0-alpha06)
    implementation("androidx.documentfile:documentfile:1.0.1")
    "googleImplementation"(libs.firebase.analytics)
    
    // Archive Support
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    "googleImplementation"(libs.play.review)
    implementation(libs.libadb) {
        exclude(group = "org.bouncycastle")
    }

    // Animated image support (APNG, GIF, animated WebP, SVG)
    implementation(libs.coil.core)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.apng.core)
    // BouncyCastle for certificate generation and SSHD cryptography
    // SEC: upgraded 1.83 → 1.84 to fix CVE-2026-5588 (HIGH — CompositeVerifier signature bypass in bcpkix)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcutil-jdk18on:1.84")
    
    // EdDSA for Ed25519 SSH keys
    implementation("net.i2p.crypto:eddsa:0.3.0")
    
    // SLF4J for logging (using a more modern Android binding)
    implementation("uk.uuid.slf4j:slf4j-android:2.0.17-0")

    
    // Room Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // MSAL and REST libs for OneDrive
    // MSAL is used exclusively for OneDrive authentication.
    // Amazon build variants must NOT include MSAL because it transitively pulls in
    // com.google.android.gms:play-services-* (via credentials-play-services-auth),
    // which Amazon's policy scanner flags as prohibited ad-network/GMS libraries.
    // OneDrive is hidden from the Amazon UI entirely — see src/amazon/ source-set overrides.
    "googleImplementation"(libs.msal.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.androidx.browser) // Chrome Custom Tabs for Google Drive mobile OAuth
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.lottie)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    // libnfs: native NFS v2/v3/v4 client via JNI (built from source in src/main/cpp/)



    // RClone library for cloud storage support (Drime, Filen, MEGA)
    implementation(files("libs/rclone.aar"))

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

licensee {
    // Apache 2.0 — used by smbj, commons-net, poi, poi-scratchpad, AndroidX libs
    allow("Apache-2.0")

    // LGPL 2.1 — used by jcifs-ng
    allow("LGPL-2.1")
    allow("LGPL-2.1-only")
    allow("LGPL-2.1-or-later")

    // BSD — used by NanoHTTPD (BSD-3-Clause) and BouncyCastle
    allow("BSD-3-Clause")
    allow("BSD-2-Clause")

    // Apache 2.0 covers Ktor, Netty, and many transitive deps
    // (already allowed above, but noted here for clarity)
    // MIT — common transitive dependency license
    allow("MIT")

    // EPL — Eclipse Public License (some Apache POI transitive deps)
    allow("EPL-1.0")
    allow("EPL-2.0")

    // New additions
    allow("CC0-1.0")
    allowUrl("https://developer.android.com/studio/terms.html")
    allowUrl("https://www.bouncycastle.org/licence.html")
    allowUrl("https://www.gnu.org/licenses/lgpl.txt")
    allowUrl("https://opensource.org/license/mit")
    allowUrl("https://api.github.com/licenses/lgpl-3.0")
    allowUrl("https://developer.android.com/guide/playcore/license")
    
    ignoreDependencies("com.microsoft.identity.client", "msal")
    ignoreDependencies("com.microsoft.identity", "common")
    ignoreDependencies("com.microsoft.identity", "common4j")
    ignoreDependencies("com.microsoft.device.display", "display-mask")
    ignoreDependencies("org.tukaani", "xz")
    ignoreDependencies("com.github.MuntashirAkon", "libadb-android")
    // Amazon Appstore SDK uses a proprietary license; managed outside Gradle licensee.
    ignoreDependencies("com.amazon.device", "amazon-appstore-sdk")
}

// Copy licensee's artifacts.json into raw resources after the task runs.
// The task is named licenseeAndroidMobile<Variant> and licenseeAndroidTV<Variant> for product flavors.
afterEvaluate {
    val rawDir = file("src/main/res/raw")
    // Two-dimension flavors → 8 variants (device × store × buildType)
    listOf(
        "mobileGoogleDebug", "mobileGoogleRelease",
        "mobileAmazonDebug", "mobileAmazonRelease",
        "mobileFossDebug",   "mobileFossRelease",
        "tvGoogleDebug",    "tvGoogleRelease",
        "tvAmazonDebug",    "tvAmazonRelease",
        "tvFossDebug",      "tvFossRelease"
    ).forEach { variant ->
        val variantCapitalized = variant.replaceFirstChar { it.uppercase() }
        val taskName = "licenseeAndroid$variantCapitalized"
        tasks.findByName(taskName)?.doLast {
            rawDir.mkdirs()
            val report = file("build/reports/licensee/android$variantCapitalized/artifacts.json")
            if (report.exists()) {
                report.copyTo(file("$rawDir/licenses.json"), overwrite = true)
            }
        }
    }
}

// Disable google-services tasks for Amazon and FOSS variants to prevent processing/merging GMS configurations
tasks.configureEach {
    if (name.startsWith("process") &&
        (name.contains("Amazon", ignoreCase = true) || name.contains("Foss", ignoreCase = true)) &&
        name.endsWith("GoogleServices")) {
        enabled = false
    }
}