import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from a git-ignored keystore.properties (see keystore.properties.example).
// Absent (dev machines / CI without secrets) -> release builds unsigned, which still validates R8.
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream()) }

android {
    namespace = "com.sieve.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sieve.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // keep all built ABIs (incl. x86_64) so the emulator can run debug + connected tests
        }
        release {
            // R8 minification is DISABLED intentionally. Sieve is GPLv3 open source, so
            // obfuscation gives no IP protection — it only risks breaking the reflection-
            // based init inside youtubedl-android (its Python bootstrap loads classes by
            // name). R8 full-mode did exactly that in v1.0.0: the app crashed on launch
            // with `ExceptionInInitializerError: class ... is not a concrete class` from
            // YoutubeDL.initPython. Keeping minify off makes the release build behave like
            // the (fully tested) debug build. Do NOT re-enable without validated keep rules
            // for youtubedl-android's transitive reflective deps AND an on-device release test.
            isMinifyEnabled = false
            isShrinkResources = false
            ndk { abiFilters.add("arm64-v8a") } // release ships arm64 only (smallest APK)
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // ffmpeg .so must extract to nativeLibraryDir so it can be exec'd.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":transcode"))
    implementation(project(":queue"))
    implementation(project(":storage"))
    implementation(project(":data"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
