plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ros2android.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.ros2android.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.1-dev6"
        testInstrumentationRunner = "dev.ros2android.android.Ros2AndroidTestRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    sourceSets["main"].res.srcDir("../branding/res")
    sourceSets["main"].java.srcDir("../security/src/main/java")
    sourceSets["main"].java.srcDir("../protocol/src/main/java")
    sourceSets["main"].java.srcDir("../network/src/main/java")
    sourceSets["main"].java.srcDir("../health/src/main/java")
    // Upstream license texts for bundled native libraries (setup/stage-licenses.py).
    sourceSets["main"].assets.srcDir("../../native-deps/ros-assets")
    sourceSets["main"].jniLibs.srcDir("../../native-deps/apk-libs")
    sourceSets["main"].jniLibs.srcDir("../../native-deps/agent-apk-libs")
    sourceSets["main"].assets.srcDir("../../native-deps/agent-assets")
    packaging.jniLibs.useLegacyPackaging = true
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

