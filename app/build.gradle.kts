import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

repositories {
    // Declared here too: a global init script on some machines adds a mirror that lacks mavenCentral artifacts.
    google()
    mavenCentral()
}

android {
    namespace = "ir.arashzich.hush"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.arashzich.hush"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.3.1"
        resourceConfigurations += listOf("en", "fa")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // F-Droid rejects the Google-signed dependency blob AGP adds by default.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // GitHub releases also get one APK per CPU type (./release.sh passes -PabiSplits); F-Droid builds the universal one.
    if (project.hasProperty("abiSplits")) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                isUniversalApk = true
            }
        }
    }

    lint { abortOnError = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
