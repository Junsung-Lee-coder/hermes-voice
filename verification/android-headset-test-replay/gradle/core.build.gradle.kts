import java.time.Duration
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Android ships org.json; the JVM build and tests need the real artifact.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// TEST-BUNDLE ONLY: bound a hung test.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(30)) }
