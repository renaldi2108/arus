import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Base convention for every published Arus library module.
 *
 * Establishes the multiplatform target set (Android + iOS), `explicitApi()`, and the
 * shared Kotlin compiler configuration. Compose is layered on separately by
 * `arus.compose.library` so a module can opt out of the UI toolkit.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * `arus` -> `io.github.renaldi2108.arus`; `arus-testing` -> `io.github.renaldi2108.arus.testing`.
 * Keeps the Android namespace stable and unique per module without each module
 * restating it.
 */
val moduleNamespace: String =
    if (project.name == "arus") {
        "io.github.renaldi2108.arus"
    } else {
        "io.github.renaldi2108.arus." + project.name.removePrefix("arus-").replace("-", ".")
    }

extensions.configure<KotlinMultiplatformExtension> {
    // Every public declaration must carry an explicit visibility and return type,
    // so an inferred type can never widen the published API by accident.
    explicitApi()

    jvmToolchain(libs.findVersion("jvmTarget").get().requiredVersion.toInt())

    extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = moduleNamespace
        compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()

        // Runs `commonTest` on the JVM. iOS devices are not required in CI, and
        // Xcode is not required for the logic tests that make up most of the suite.
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }

    // Apple Silicon only: the JetBrains AndroidX mirrors (lifecycle 2.11.0,
    // navigation3 1.2.0) no longer publish `iosX64`/`macosX64`, so an Intel
    // simulator target cannot resolve. See ADR-011.
    iosArm64()
    iosSimulatorArm64()

    // Linking an Apple *test* binary shells out to `xcrun`/`xcodebuild`, so it
    // needs a full Xcode install — the Command Line Tools alone are not enough,
    // and the failure is an opaque "Failed to build cache" error. Compiling the
    // klibs works fine either way, so on a machine without Xcode we keep
    // compilation and skip only the test links, leaving `./gradlew build`
    // usable. CI runs on macOS with Xcode and does link them. See BACKLOG-4.
    if (!HasFullXcode.value(providers)) {
        tasks.matching { it.name.startsWith("link") && it.name.contains("Ios") }
            .configureEach { enabled = false }
        logger.lifecycle(
            "[arus] Full Xcode not found - skipping Apple test linking in :${project.name}. " +
                "Kotlin/Native compilation still runs.",
        )
    }

    sourceSets.configureEach {
        languageSettings {
            progressiveMode = true
        }
    }

    sourceSets.named("commonTest") {
        dependencies {
            implementation(kotlin("test"))
        }
    }
}

/**
 * Fails the build the day the klib ABI dump stops covering everything.
 *
 * `binary-compatibility-validator` does not recognise the Android target the
 * AGP KMP plugin creates, so `apiCheck` validates only the klib dumps — which
 * cover `commonMain` and the Apple source sets. Today that is every
 * declaration in the library: there are no `androidMain` sources and no
 * `expect`/`actual` pairs, so nothing is unguarded.
 *
 * The risk is that someone adds an Android-only declaration later and the ABI
 * gate silently stops being complete. This task makes that loud instead.
 * See BACKLOG-2 and ADR-013.
 */
val checkAbiCoverage =
    tasks.register("checkAbiCoverage") {
        group = "verification"
        description = "Fails if Android-only sources exist that the klib ABI dump cannot cover."

        val androidMain = layout.projectDirectory.dir("src/androidMain")
        val jvmMain = layout.projectDirectory.dir("src/jvmMain")
        val candidates = listOf(androidMain, jvmMain)
        val sources =
            providers.provider {
                candidates
                    .filter { it.asFile.exists() }
                    .flatMap { dir ->
                        dir.asFile.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                    }.map { it.relativeTo(layout.projectDirectory.asFile).path }
            }
        inputs.property("sources", sources)

        doLast {
            val found = sources.get()
            check(found.isEmpty()) {
                buildString {
                    appendLine("Android/JVM-only sources are not covered by the ABI gate (BACKLOG-2).")
                    appendLine()
                    appendLine("`apiCheck` validates klib dumps only, which cover commonMain and the")
                    appendLine("Apple targets. These files add declarations nothing checks:")
                    found.forEach { appendLine("  - $it") }
                    appendLine()
                    append("Either move them to commonMain, or gate the Android ABI before shipping.")
                }
            }
        }
    }

tasks.named("check") { dependsOn(checkAbiCoverage) }

/**
 * Fails when the published artifact outgrows its budget.
 *
 * Artifact size has no other gate: it drifted from 107 KB to 163 KB across
 * several milestones with every build green, because nothing was looking.
 * This task looks.
 *
 * Run it after `assemble`; it reads the `.aar` that task produces.
 */
val maxArtifactBytes = 150_000L

tasks.register("checkArtifactSize") {
    group = "verification"
    description = "Fails when the assembled .aar exceeds its size budget."

    val aar = layout.buildDirectory.file("outputs/aar/${project.name}.aar")
    val limit = maxArtifactBytes
    val name = project.name
    inputs.file(aar).optional(true)

    doLast {
        val file = aar.get().asFile
        if (!file.exists()) {
            logger.lifecycle("[arus] $name: no .aar to measure; run `assemble` first.")
            return@doLast
        }
        val size = file.length()
        check(size <= limit) {
            "$name.aar is $size bytes, over the $limit budget by ${size - limit}. " +
                "Either trim it or raise the budget deliberately in arus.kmp.library.gradle.kts."
        }
        logger.lifecycle("[arus] $name.aar $size bytes, ${limit - size} under budget.")
    }
}
