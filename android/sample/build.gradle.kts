import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    id("com.android.application")
}

// Publish with a permanent ID chosen before the first store release. The default keeps
// installed development builds upgradeable and is rejected by the store release gate.
val releaseApplicationId = providers.gradleProperty("phytoyApplicationId")
    .orElse(providers.environmentVariable("PHYTOY_APPLICATION_ID"))
    .getOrElse("com.phytoy.sample")
val releaseVersionCode = providers.gradleProperty("phytoyVersionCode")
    .orElse(providers.environmentVariable("PHYTOY_VERSION_CODE"))
    .getOrElse("17").toInt()
val releaseVersionName = providers.gradleProperty("phytoyVersionName")
    .orElse(providers.environmentVariable("PHYTOY_VERSION_NAME"))
    .getOrElse("0.16.0-rc1")
require(releaseApplicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
    "phytoyApplicationId must be a valid permanent Android application ID"
}
require(releaseVersionCode in 1..2100000000) { "phytoyVersionCode must be a positive Play-compatible integer" }
require(releaseVersionName.isNotBlank()) { "phytoyVersionName must not be blank" }
// This is the public licensing key from Play Console, never a private signing key.
val playBillingPublicKey = providers.gradleProperty("phytoyPlayBillingPublicKey")
    .orElse(providers.environmentVariable("PHYTOY_PLAY_BILLING_PUBLIC_KEY"))
    .getOrElse("").replace(Regex("\\s"), "")
require(playBillingPublicKey.matches(Regex("[A-Za-z0-9+/=]*"))) {
    "phytoyPlayBillingPublicKey must be the base64 public licensing key from Play Console"
}
val billingKeyConfigured = playBillingPublicKey.isNotBlank() && runCatching {
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
        Base64.getDecoder().decode(playBillingPublicKey),
    ))
}.isSuccess
require(playBillingPublicKey.isBlank() || billingKeyConfigured) {
    "The Play licensing public key is not a valid base64 X509 RSA public key"
}
require(!providers.gradleProperty("phytoyRequireBillingConfigured").getOrElse("false").toBoolean() ||
    billingKeyConfigured) {
    "A configured billing release was requested, but the Play licensing public key is absent"
}

// Secrets come only from the build environment, never a checked-in properties file.
// Missing credentials intentionally produce unsigned APK/AAB candidates.
val signingVariables = listOf("PHYTOY_KEYSTORE", "PHYTOY_STORE_PASSWORD", "PHYTOY_KEY_ALIAS", "PHYTOY_KEY_PASSWORD")
val signingValues = signingVariables.associateWith { providers.environmentVariable(it).orNull }
val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
require(signingValues.values.all { it.isNullOrBlank() } || hasSigning) {
    "Release signing requires all four PHYTOY_KEYSTORE / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD variables"
}
require(!providers.gradleProperty("phytoyRequireSignedRelease").getOrElse("false").toBoolean() || hasSigning) {
    "A signed release was requested, but release signing credentials are absent"
}

android {
    namespace = "com.phytoy.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = releaseApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", "\"$playBillingPublicKey\"")
    }

    buildFeatures { buildConfig = true }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(signingValues["PHYTOY_KEYSTORE"]))
                require(storeFile!!.isFile) { "PHYTOY_KEYSTORE does not point to a readable keystore file" }
                storePassword = signingValues["PHYTOY_STORE_PASSWORD"]
                keyAlias = signingValues["PHYTOY_KEY_ALIAS"]
                keyPassword = signingValues["PHYTOY_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            optimization { enable = true }
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            ndk { debugSymbolLevel = "FULL" }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // AGP >= 8.5.1 aligns uncompressed native libraries to 16 KB boundaries.
        jniLibs.useLegacyPackaging = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.android.billingclient:billing:9.1.0")
    testImplementation("junit:junit:4.13.2")
}
