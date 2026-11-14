import java.util.Properties

// Qualcomm QNN (Hexagon NPU) build: ./gradlew assembleRelease -Pqnn=true
// The QNN build swaps in onnxruntime-android-qnn, which pulls the Qualcomm qnn-runtime AAR
// (HTP/GPU/System libraries and the v68..v81 stubs and skeletons) and is arm64-v8a only.
val useQnn = (project.findProperty("qnn") as String?)?.toBoolean() ?: false
// qnn-runtime ships every HTP generation. Keep only the one this APK targets:
// 81 = 8 Elite Gen 5 (SM8850, default), 79 = 8 Elite, 75 = 8 Gen 3, 73 = 8 Gen 2,
// 69 = 8/8+ Gen 1, 68 = 888. Override with -PqnnHtpArch=79 on another SoC.
val qnnHtpArch = (project.findProperty("qnnHtpArch") as String?)?.trim() ?: "81"
val ortVersion = "1.24.3"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from android/keystore.properties when present.
// Falls back to the debug key so that `assembleRelease` always yields an installable APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.flowframes.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flowframes.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "QNN_BUILD", useQnn.toString())

        // Ship 64-bit ARM by default (every Android phone since ~2016).
        // Every ABI:   ./gradlew assembleRelease -PallAbis=true
        // One ABI:     ./gradlew assembleDebug -Pabis=x86_64
        val allAbis = (project.findProperty("allAbis") as String?)?.toBoolean() ?: false
        val explicitAbis = (project.findProperty("abis") as String?)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val requestedAbis = explicitAbis
            ?: if (allAbis) listOf("arm64-v8a", "armeabi-v7a", "x86_64") else listOf("arm64-v8a")
        if (useQnn && requestedAbis != listOf("arm64-v8a")) {
            project.logger.warn("QNN build: qnn-runtime only ships arm64-v8a; building arm64-v8a only.")
        }
        ndk {
            abiFilters += if (useQnn) listOf("arm64-v8a") else requestedAbis
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    androidResources {
        // RIFE models are copied to internal storage on first launch; keep them uncompressed.
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
        if (useQnn) {
            // qnn-runtime is ~200 MB uncompressed and covers six HTP generations. Ship the
            // requested one only, and compress the .so files: the APK would otherwise be 250 MB.
            val knownArches = listOf("68", "69", "73", "75", "79", "81")
            if (qnnHtpArch !in knownArches) {
                project.logger.warn(
                    "QNN build: unknown qnnHtpArch '$qnnHtpArch' (known: ${knownArches.joinToString()}); " +
                        "the APK may not contain a matching HTP skeleton."
                )
            }
            val drop = buildList {
                add("libQnnGpu.so")
                add("libQnnDsp.so")
                add("libQnnDspV66Skel.so")
                add("libQnnDspV66Stub.so")
                for (arch in knownArches.filter { it != qnnHtpArch }) {
                    add("libQnnHtpV${arch}Stub.so")
                    add("libQnnHtpV${arch}Skel.so")
                }
            }
            jniLibs {
                useLegacyPackaging = true
                excludes += drop.map { "lib/arm64-v8a/$it" }
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Unit tests must not see the Android AAR of ONNX Runtime (it has no Linux natives).
// A plain JVM build of the same version is added for tests instead.
configurations.matching { it.name.startsWith("test") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android-qnn")
    exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QNN builds use the same Java/Kotlin API but an ONNX Runtime compiled with the QNN EP.
    val ortArtifact = if (useQnn) "onnxruntime-android-qnn" else "onnxruntime-android"
    implementation("com.microsoft.onnxruntime:$ortArtifact:$ortVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
