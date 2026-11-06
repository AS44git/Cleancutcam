plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.cleancut.cameras"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cleancut.cameras"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        ndk {
            // Phones: 64-bit ARM only (keeps the APK small).
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed after the build, with the release key (see the workflow).
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Chaquopy runs pip with a Python of the same version on the build machine.
        System.getenv("CHAQUOPY_BUILD_PYTHON")?.let { buildPython(it) }
        pip {
            // Pure-Python packages only. pycryptodome (a C extension) is replaced by the
            // small javax.crypto-backed Crypto package in src/main/python, so the two camera
            // libraries are installed without their dependency lists.
            install("httpx")
            install("requests")
            install("xmltodict")
            install("paho-mqtt")
            install("--no-deps", "pyezvizapi==1.0.5.0")
            install("--no-deps", "hikcloudstream==0.1.4")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
