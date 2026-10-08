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
    namespace = "com.rumi.hermesvoice.phone"
    compileSdk = 35

    defaultConfig {
        // Wear Data Layer delivery is package-scoped: the Watch APK uses the same applicationId.
        // Distinct from the Recorder MVP's id, so both can be installed side by side.
        applicationId = "com.rumi.hermesvoice"
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "0.1.18-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    if (uiFixture) debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ── SCRATCH HARNESS ONLY (appended in a throwaway staging copy; never part of the app or its source zip): host tests that run the
// actual Phone classes under Robolectric. Runtime jars are fetched into the work slot (user.home). ──
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("user.home", rootProject.layout.projectDirectory.dir("../robo-home").asFile.absolutePath)
                it.maxHeapSize = "1g"
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
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// TEST-BUNDLE ONLY: Compose UI test (Robolectric) for the production chat renderer.
dependencies {
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// TEST-BUNDLE ONLY: bound a hung test.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(30)) }
