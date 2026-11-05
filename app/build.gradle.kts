plugins { id("com.android.application") }

val benchmarkProfile = providers.gradleProperty("benchmarkProfile").orElse("1").get().toInt()
require(benchmarkProfile in 1..9)

android {
    namespace = "com.cemig.gpudiagnostic"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "com.cemig.gpudiagnostic"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Wire IDs 1–5 remain reserved for the historical multi-configuration APKs.
        buildConfigField("int", "BENCHMARK_PROFILE", (100 + benchmarkProfile).toString())
        manifestPlaceholders["benchmarkLabel"] = "CEMIG Benchmark · Config $benchmarkProfile"
        buildConfigField("String", "LITERT_VERSION", "\"0.18.1\"")
        buildConfigField("String", "LLAMA_REVISION", "\"5851a8e505f4d5fbb183f2847698eb6155d176b7\"")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared"); targets += "lfm_diagnostic" } }
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("androidx.core:core:1.16.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
