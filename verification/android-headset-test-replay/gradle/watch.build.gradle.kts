import java.time.Duration
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
        versionCode = 19
        versionName = "0.1.18-dev"
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

// ── SCRATCH HARNESS ONLY (appended in a throwaway staging copy; never part of the app or its source zip): host tests
// that run the actual Watch classes under Robolectric. Runtime jars are fetched into the work slot (user.home). ──
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("user.home", rootProject.layout.projectDirectory.dir("../robo-home").asFile.absolutePath)
                it.maxHeapSize = "2g"
                it.testLogging {
                    events("passed", "failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// b43 NEW test-only capacity profile, appended override; no assertion/filter changes.
android { testOptions { unitTests { all { it.maxHeapSize = "1g" } } } }

// TEST-BUNDLE ONLY: bound a hung test.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(30)) }
