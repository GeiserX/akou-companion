// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.concurrent.TimeUnit
import kotlin.math.abs

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.geiserx.akou"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.geiserx.akou"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The real-server test and the phone-call test run only by hand (`am instrument -e class ...`
        // with their arguments). Skipped through an assumption, AGP writes them into the results as failures.
        testInstrumentationRunnerArguments["notClass"] =
            "io.github.geiserx.akou.EndToEndTests,io.github.geiserx.akou.recorder.CallInterruptionTests"
        // The pause test hands its file to the host through TestStorage for ffprobe.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":opus"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.services.storage)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestUtil(libs.androidx.test.services)
}

/**
 * After the instrumented tests, ffprobe (an independent demuxer) reads the paused recording that
 * PauseResumeFileTests hands over, and its duration must be the file's own seconds, which that test
 * wrote beside it: one stream with no hole where the pause was. ffprobe counts the 312-sample
 * pre-skip in. A missing file or a missing ffprobe fails the build, never skips.
 */
abstract class FfprobePausedStream : DefaultTask() {
    @get:Internal abstract val outputsDir: DirectoryProperty

    @TaskAction
    fun probe() {
        val files = outputsDir.get().asFile.walkTopDown().filter { it.name == STREAM_NAME }.toList()
        if (files.isEmpty()) throw GradleException("no $STREAM_NAME under ${outputsDir.get().asFile}: the pause test did not hand over its file")
        val ffprobe = (System.getenv("PATH") ?: "").split(File.pathSeparator).plus(listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"))
            .filter { it.isNotEmpty() }.map { File(it, "ffprobe") }.firstOrNull { it.canExecute() }
            ?: throw GradleException("ffprobe not found: the paused stream cannot be checked")
        for (f in files) {
            val expected = File(f.parentFile, "$STREAM_NAME.seconds").readText().trim().toDouble()
            val p = ProcessBuilder(
                ffprobe.path, "-v", "error", "-show_entries", "stream=codec_name,channels:format=duration",
                "-of", "default=nw=1", f.path,
            ).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw GradleException("ffprobe failed on $f: $text")
            val got = text.lineSequence().firstOrNull { it.startsWith("duration=") }?.removePrefix("duration=")?.toDoubleOrNull()
                ?: throw GradleException("ffprobe reads no duration from $f: $text")
            val audio = got - PRE_SKIP_SECONDS
            if (!text.contains("codec_name=opus") || !text.contains("channels=1") || abs(audio - expected) >= 0.02) {
                throw GradleException("ffprobe on $f reads $got s (audio $audio s), the file says $expected s: $text")
            }
            logger.lifecycle("ffprobe: ${f.name} is opus, mono, $got s with the pre-skip, the file's $expected s within 20 ms")
        }
    }

    companion object {
        const val STREAM_NAME = "paused-recording.opus"
        const val PRE_SKIP_SECONDS = 312.0 / 48000.0
    }
}

val additionalTestOutput = layout.buildDirectory.dir("outputs/connected_android_test_additional_output")

val ffprobePausedStream = tasks.register<FfprobePausedStream>("ffprobePausedStream") {
    outputsDir.set(additionalTestOutput)
}

tasks.configureEach {
    if (name == "connectedDebugAndroidTest") {
        // A file left by an earlier run must not pass the check for this one.
        doFirst { delete(additionalTestOutput) }
        finalizedBy(ffprobePausedStream)
    }
}

// CI fails the job on "Executed 0 tests", so the count is printed once per run, as in :core.
tasks.withType<Test>().configureEach {
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
