// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The Swift fixtures and the demo samples are read in place, never copied.
val repoRoot = rootDir.parentFile
val fixturesDir = repoRoot.resolve("Tests/AkouKitTests/Fixtures")
val samplesDir = repoRoot.resolve("App/Sources/Demo/Samples")

tasks.test {
    inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(samplesDir).withPropertyName("samples").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("akou.fixtures", fixturesDir.absolutePath)
    systemProperty("akou.samples", samplesDir.absolutePath)
    // ffprobe must be found on PATH: the parity test fails, never skips, without it.
    environment("PATH", System.getenv("PATH") ?: "")
}

// The code targets 17, but the tests run on 21. java.text.BreakIterator counts extended grapheme
// clusters (emoji ZWJ sequences, flags, skin tones) only from JDK 20 on, as Android's ICU-backed one
// does; on 17 it splits them, and TranscriptDigest's tests would test a JVM the app never runs on.
tasks.test {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// CI fails the job on "Executed 0 tests", so the count is printed once per run.
tasks.test {
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("Executed ${result.testCount} tests (${result.failedTestCount} failed, ${result.skippedTestCount} skipped)")
            }
        }
    })
}
