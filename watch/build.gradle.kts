plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// UI fixture builds only (`-Phv.uiFixture=true`): a separately installed copy with its own application
// id, whose instrumentation tests render the production composables in a plain test activity. The app
// itself is never built with it.
val uiFixture = providers.gradleProperty("hv.uiFixture").orNull == "true"

android {
    namespace = "com.rumi.hermesvoice.watch"
    compileSdk = 35

    defaultConfig {
        // Must equal the Phone's applicationId (and signing key) for Wear Data Layer delivery.
        applicationId = "com.rumi.hermesvoice"
        minSdk = 30
        targetSdk = 35
        versionCode = 16
        versionName = "0.1.15-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (uiFixture) applicationIdSuffix = ".uifixture"
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
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // Only the pure link contract, settings and silence endpoint are used on the Watch; the Watch
    // never calls Hermes (no dashboard client, no credentials).
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    constraints {
        // Play Services resolves Fragment 1.1.0; ActivityResult permission launchers require 1.3.0.
        implementation("androidx.fragment:fragment:1.3.0")
    }
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    if (uiFixture) debugImplementation("androidx.compose.ui:ui-test-manifest")
}
