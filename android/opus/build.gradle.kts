// SPDX-License-Identifier: GPL-3.0-or-later
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.library)
}

// libopus, built from the upstream release tarball. Pinned exactly: an update is a reviewed change.
// swift-opus 0.0.2 (the iOS build) vendors an untagged 2022 commit, not a release, so Android takes
// the newest stable 1.5.x. The SHA-256 is Xiph's own (downloads.xiph.org/releases/opus/SHA256SUMS.txt).
val libopusVersion = "1.5.2"
val libopusUrl = "https://downloads.xiph.org/releases/opus/opus-$libopusVersion.tar.gz"
val libopusSha256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
val libopusTarball = layout.buildDirectory.file("libopus/opus-$libopusVersion.tar.gz")

// The iOS settings shim is compiled as is, so both apps apply the same six settings in the same order.
val repoRoot = rootDir.parentFile
val shimDir = repoRoot.resolve("Sources/COpusShim")

android {
    namespace = "io.github.geiserx.akou.opus"
    compileSdk = 36
    // r28 or newer links 16 KB aligned libraries by default (Play requires them at targetSdk 35+).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The round-trip test hands its file to the host through TestStorage for ffprobe.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        ndk {
            // arm64-v8a for phones and the arm64 emulator, x86_64 for the CI emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DAKOU_OPUS_URL=$libopusUrl",
                    "-DAKOU_OPUS_TARBALL=${libopusTarball.get().asFile.absolutePath}",
                    "-DAKOU_OPUS_SHA256=$libopusSha256",
                    "-DAKOU_SHIM_DIR=${shimDir.absolutePath}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.services.storage)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestUtil(libs.androidx.test.services)
}

/**
 * Downloads the libopus tarball once per build directory and checks its SHA-256, so the four CMake
 * configurations (two ABIs, debug and release) extract a local file instead of each fetching it.
 * CMake checks the same hash again, and falls back to the URL when the file is missing (IDE sync).
 */
abstract class FetchLibopus : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val tarball: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = tarball.get().asFile
        if (out.isFile && sha(out.readBytes()) == sha256.get()) return
        out.parentFile.mkdirs()
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URI(url.get()).toURL().openConnection().apply {
                    connectTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
                    readTimeout = TimeUnit.SECONDS.toMillis(60).toInt()
                }
                val bytes = conn.getInputStream().use { it.readBytes() }
                val got = sha(bytes)
                if (got != sha256.get()) throw GradleException("libopus tarball SHA-256 is $got, expected ${sha256.get()}")
                out.writeBytes(bytes)
                return
            } catch (e: GradleException) {
                throw e
            } catch (e: Exception) {
                last = e
                logger.warn("libopus download attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        throw GradleException("could not download ${url.get()}", last)
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

val fetchLibopus = tasks.register<FetchLibopus>("fetchLibopus") {
    url.set(libopusUrl)
    sha256.set(libopusSha256)
    tarball.set(libopusTarball)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLibopus)
}

/**
 * After the instrumented tests, ffprobe (an independent demuxer and decoder) reads the file the
 * round-trip test wrote on the device. It must be mono Opus and end exactly where one second through
 * ffmpeg's own encoder and muxer ends (the fixture, 1.0065 s: ffprobe counts the pre-skip in), as the
 * JVM parity test in :core checks for the fake encoder. A missing file or a missing ffprobe fails the
 * build, never skips: a check that cannot run must not pass.
 */
abstract class FfprobeEncodedStream : DefaultTask() {
    @get:Internal abstract val outputsDir: DirectoryProperty
    @get:Internal abstract val reference: RegularFileProperty

    @TaskAction
    fun probe() {
        val files = outputsDir.get().asFile.walkTopDown().filter { it.name == STREAM_NAME }.toList()
        if (files.isEmpty()) throw GradleException("no $STREAM_NAME under ${outputsDir.get().asFile}: the round-trip test did not hand over its file")
        val ffprobe = (System.getenv("PATH") ?: "").split(File.pathSeparator).plus(listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"))
            .filter { it.isNotEmpty() }.map { File(it, "ffprobe") }.firstOrNull { it.canExecute() }
            ?: throw GradleException("ffprobe not found: the encoded stream cannot be checked")
        val expected = duration(probe(ffprobe, reference.get().asFile))
            ?: throw GradleException("ffprobe reads no duration from the reference ${reference.get().asFile}")
        for (f in files) {
            val text = probe(ffprobe, f)
            val got = duration(text)
            if (!text.contains("codec_name=opus") || !text.contains("channels=1") || got != expected) {
                throw GradleException("ffprobe on $f does not read one second of mono Opus (reference $expected s): $text")
            }
            logger.lifecycle("ffprobe: ${f.name} is opus, mono, $got s, as ffmpeg's own one second")
        }
    }

    private fun probe(ffprobe: File, f: File): String {
        val p = ProcessBuilder(
            ffprobe.path, "-v", "error", "-show_entries", "stream=codec_name,channels:format=duration",
            "-of", "default=nw=1", f.path,
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw GradleException("ffprobe failed on $f: $text")
        return text
    }

    private fun duration(text: String): Double? =
        text.lineSequence().firstOrNull { it.startsWith("duration=") }?.removePrefix("duration=")?.toDoubleOrNull()

    companion object {
        const val STREAM_NAME = "one-second-440hz.opus"
    }
}

val additionalTestOutput = layout.buildDirectory.dir("outputs/connected_android_test_additional_output")

val ffprobeEncodedStream = tasks.register<FfprobeEncodedStream>("ffprobeEncodedStream") {
    outputsDir.set(additionalTestOutput)
    reference.set(repoRoot.resolve("Tests/AkouKitTests/Fixtures/ffmpeg-1s-16k.opus"))
}

tasks.configureEach {
    if (name == "connectedDebugAndroidTest") {
        // A file left by an earlier run must not pass the check for this one.
        doFirst { delete(additionalTestOutput) }
        finalizedBy(ffprobeEncodedStream)
    }
}
