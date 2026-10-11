// ── SCRATCH HARNESS ONLY: host tests
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
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// b43 NEW test-only capacity profile, appended override; no assertion/filter changes.
android { testOptions { unitTests { all { it.maxHeapSize = "1g" } } } }

// TEST-BUNDLE ONLY: bound a hung test (needs `import java.time.Duration` as the first line of the module script).
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(30)) }
