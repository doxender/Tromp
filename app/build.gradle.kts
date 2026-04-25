plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.comtekglobal.tromp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.comtekglobal.tromp"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. The keystore (app/release.keystore) and its passwords
    // are committed to the repo on purpose — this is a personal side-loaded
    // app, not a Play Store build, so there's nothing to protect. Anyone who
    // rebuilds from source gets a byte-identical signed APK, which lets
    // updates install over existing installs without wiping data. Do NOT
    // reuse this keystore for anything you'd ship to Google Play.
    //
    // Keystore rotated 2026-04-24 from the original TrekTracker-era keystore
    // (CN=TrekTracker, alias=trektracker, password=trekRelease2026) to the
    // current Tromp identity (CN=Tromp, alias=tromp, password=tromp2026).
    // The rotation was done while the install base was effectively just one
    // device, so the upgrade-path break was trivial. The pre-rotation
    // keystore is archived at app/release.keystore.trektracker.bak — never
    // reuse it; any APK signed with it cannot upgrade an APK signed with
    // the current keystore (Android enforces signing-key continuity).
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "tromp2026"
            keyAlias = "tromp"
            keyPassword = "tromp2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

// Export Room schemas so migrations have a reviewable baseline (CLAUDE.md §3).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle / ViewModel / StateFlow
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Room (persistence)
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Location / coroutines
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Maps (OSM)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Charts (elevation profile + distance-per-week bar chart)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Offline tile download (cancellable, resumable)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Instrumentation tests (kept minimal — no UI tests in v1)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
