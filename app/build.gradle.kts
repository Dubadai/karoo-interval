import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key, kept out of git. See README "Publishing a release".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.daviddokakis.karoointerval"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daviddokakis.karoointerval"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Your own release key when keystore.properties exists; otherwise the debug key, so
            // the project still builds anywhere (but such APKs can't update a published one).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":karoo-ext"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    // ANT+ Plugin library (ANT+ Shared Source License, see app/libs/ANT-LICENSE.txt), for ERG on FE-C trainers.
    implementation(files("libs/antpluginlib_3-9-0.aar"))
}
