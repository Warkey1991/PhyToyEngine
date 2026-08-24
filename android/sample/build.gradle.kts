plugins {
    id("com.android.application")
}

android {
    namespace = "com.phytoy.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phytoy.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(project(":sdk"))
}
