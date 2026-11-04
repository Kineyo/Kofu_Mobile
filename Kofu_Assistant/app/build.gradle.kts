plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kofu.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kofu.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    androidResources {
        // Evita que AAPT2 comprima pesos de modelos para permitir mmap directo en memoria (zero-copy)
        noCompress += listOf("litertlm", "tflite", "task", "gguf", "bin")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose BOM & UI
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    // Motores Nativos de Inferencia On-Device (ARM64)
    // Google LiteRT / MediaPipe Tasks GenAI (NPU/GPU Delegate):
    // implementation("com.google.mediapipe:tasks-genai:0.10.14")
    // Llama.cpp Native JNI Fallback (GGUF mmap):
    // implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0")

    // Descarga de modelos desde HuggingFace
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Carga de Imágenes Asíncrona en Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Testing & Debug Tooling
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
