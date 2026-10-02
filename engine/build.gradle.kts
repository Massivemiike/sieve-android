plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sieve.engine"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // The instrumentation APK of a library module targets minSdk unless told otherwise; Play Protect blocks an APK built for API 26 on the test phone.
        targetSdk = 37
    }

    // youtubedl-android opens its Python bundle (lib/<abi>/libpython.zip.so) and ffmpeg zip by FILE PATH under nativeLibraryDir, so the
    // native libs must be extracted to disk. With AGP 9 and minSdk >= 23 the default is extractNativeLibs=false (libs stored uncompressed and
    // mmap'd out of the APK, no file on disk), and this module's instrumentation APK (:engine:connectedDebugAndroidTest, the library's own
    // test package, which carries the library's jniLibs) then fails with ENOENT on libpython.zip.so. Same switch as :app, :storage and
    // :transcode (AGP writes extractNativeLibs="true" into the merged manifest itself).
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // Native engine (only repo/ uses it; pure sub-packages stay JVM-only)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    // ffmpeg companion: youtubedl-android wires ITS ffmpeg into yt-dlp's process so post-processing
    // (video+audio merge, MP3 extract, format convert) works. yt-dlp can't spawn our own ffmpeg from
    // its embedded Python (the nested subprocess deadlocks). Sieve's own ffmpeg (renamed
    // libsieveffmpeg.so) is still used by :transcode for HW/loudnorm work.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
