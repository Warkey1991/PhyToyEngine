import java.security.KeyFactory
import java.net.URI
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    id("com.android.application")
}

// Separate permanent IDs keep store-specific purchases and updates in their own channel.
val playApplicationId = providers.gradleProperty("phytoyApplicationId")
    .orElse(providers.environmentVariable("PHYTOY_APPLICATION_ID"))
    .getOrElse("com.ycolor.team.phytoy.camera.android.gpapp")
val galaxyApplicationId = providers.gradleProperty("phytoyGalaxyApplicationId")
    .orElse(providers.environmentVariable("PHYTOY_GALAXY_APPLICATION_ID"))
    .getOrElse("com.ycolor.team.phytoy.camera.android.galaxyapp")
val releaseVersionCode = providers.gradleProperty("phytoyVersionCode")
    .orElse(providers.environmentVariable("PHYTOY_VERSION_CODE"))
    .getOrElse("18").toInt()
val releaseVersionName = providers.gradleProperty("phytoyVersionName")
    .orElse(providers.environmentVariable("PHYTOY_VERSION_NAME"))
    .getOrElse("0.17.0")
require(listOf(playApplicationId, galaxyApplicationId).all {
    it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
}) {
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
val galaxyBillingConfigured = providers.gradleProperty("phytoyGalaxyBillingConfigured")
    .orElse(providers.environmentVariable("PHYTOY_GALAXY_BILLING_CONFIGURED"))
    .getOrElse("false").toBoolean()
val requireBilling = providers.gradleProperty("phytoyRequireBillingConfigured").getOrElse("false").toBoolean()
fun publicValue(property: String, environment: String) = providers.gradleProperty(property)
    .orElse(providers.environmentVariable(environment)).getOrElse("").trim()
val publisherName = publicValue("phytoyPublisherName", "PHYTOY_PUBLISHER_NAME")
val supportEmail = publicValue("phytoySupportEmail", "PHYTOY_SUPPORT_EMAIL")
val privacyPolicyUrl = publicValue("phytoyPrivacyPolicyUrl", "PHYTOY_PRIVACY_POLICY_URL")
val publisherConfigured = publisherName.isNotBlank() &&
    supportEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) && runCatching {
        val uri = URI(privacyPolicyUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
require(!providers.gradleProperty("phytoyRequirePublisherConfigured").getOrElse("false").toBoolean() ||
    publisherConfigured) { "A publisher name, valid support email and public HTTPS privacy policy URL are required" }
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

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
        applicationId = playApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "PUBLISHER_NAME", quoted(publisherName))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(supportEmail))
        buildConfigField("String", "PRIVACY_POLICY_URL", quoted(privacyPolicyUrl))
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            applicationId = playApplicationId
            buildConfigField("String", "STORE_CHANNEL", "\"play\"")
            buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", quoted(playBillingPublicKey))
        }
        create("galaxy") {
            dimension = "store"
            applicationId = galaxyApplicationId
            buildConfigField("String", "STORE_CHANNEL", "\"galaxy\"")
            // Enable only after the four permanent Item products are configured in Seller Portal.
            buildConfigField("boolean", "GALAXY_BILLING_CONFIGURED", galaxyBillingConfigured.toString())
        }
    }

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
    "playImplementation"("com.android.billingclient:billing:9.1.0")
    "galaxyImplementation"("com.samsung.developer:iap:6.5.2")
    testImplementation("junit:junit:4.13.2")
}

// Require only the configuration for the channel actually being built.
tasks.configureEach {
    if (name.matches(Regex("pre(Play|Galaxy)(Release|Benchmark)Build"))) doFirst {
        if (requireBilling) require(if (name.contains("Play")) billingKeyConfigured else galaxyBillingConfigured) {
            "Billing configuration for this store channel is absent"
        }
    }
}
