import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningFile = providers.environmentVariable("LIRIUM_SIGNING_PROPERTIES")
    .map { file(it) }.orElse(rootProject.file("release-signing.properties"))
val releaseSigning = Properties().apply {
    if (releaseSigningFile.get().isFile) {
        releaseSigningFile.get().inputStream().use { load(it) }
    }
}

android {
    namespace = "com.localmodels.app"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.localmodels.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 11
        versionName = "1.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_UI=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_OPENMP=ON",
                    "-DGGML_CPU_KLEIDIAI=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8-a",
                    "-DMTMD_VIDEO=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("publicRelease") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigning.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("publicRelease")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.11.0")
    implementation("androidx.compose.ui:ui-graphics:1.11.0")
    implementation("androidx.compose.foundation:foundation:1.11.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
