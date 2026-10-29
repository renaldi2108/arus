plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false

    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.kover)
}

/**
 * Modules whose public API is published and therefore guarded by the API dump
 * and by coverage reporting.
 */
val publishedModules =
    listOf(
        "arus",
        "arus-testing",
    )

apiValidation {
    // `arus-testing` ships fakes for consumers, so its API is public surface too;
    // nothing is ignored today. Naming a project here hides its declarations from
    // the dump, which is how a breaking change slips out unnoticed.
    ignoredProjects += emptyList<String>()

    // These modules have no JVM/Android-only target that BCV can dump in its
    // classic mode, so API tracking is done through klib ABI dumps, which cover
    // every Kotlin target including iOS.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

dependencies {
    publishedModules.forEach { kover(project(":$it")) }
}

/**
 * The line-coverage floor, now 100%.
 *
 * Declared **above** the `kover` block on purpose: top-level properties in a
 * Kotlin build script initialise in declaration order, so a block that reads
 * this before it is assigned sees 0 — and `minBound(0)` passes silently.
 */
val minLineCoverage = 100

/**
 * Every line the JVM suite can reach is reached: **452/452**.
 *
 * Branch coverage is 88.8%, and deliberately not gated. The uncovered
 * branches are null paths that cannot be taken — `backStack.lastOrNull()`
 * when the constructor forbids an empty stack, a key vanishing between a
 * filter and a removal on one thread. Forcing them to 100% would mean
 * deleting defensive code that exists precisely because it should never
 * trigger.
 *
 * **Why the exclusions are not a fudge.** Kover collects from the JVM host
 * tests. Every excluded entry is a `@Composable` or a Compose-only holder,
 * which cannot execute off a device — so it reports 0% no matter how well it
 * is tested. Those are covered instead by the instrumented suite
 * (`:arus:connectedAndroidDeviceTest`), which exercises `ArusHost`,
 * `rememberArusGraph`, `CollectEffects`, `select` and the testing helpers on a
 * real device. Counting them here would mean either lowering the bar to
 * accommodate a measurement artefact, or deleting honest code to raise a
 * number.
 *
 * Each exclusion is listed one by one, never by wildcard, so adding a
 * non-composable file cannot slip under the bar unnoticed.
 */
kover {
    reports {
        filters {
            excludes {
                // Every `@Composable`, by annotation rather than by name.
                //
                // Kover collects from the JVM host tests, where a composable
                // cannot execute, so each one reports 0% however well it is
                // tested. The instrumented suite covers them instead.
                //
                // This used to be a hand-written list of class names, and it
                // went stale without a sound: the Rx -> Ars rename left
                // `RxKt$observe*` matching nothing, and the composable it was
                // meant to exclude quietly started counting against the
                // figure. An annotation cannot drift that way.
                annotatedBy("androidx.compose.runtime.Composable")

                classes(
                    // Classes whose bodies are composable lambdas. The
                    // annotation filter sees the enclosing function but not
                    // the synthetic classes the compiler generates for its
                    // lambdas, so these still need naming.
                    "io.github.renaldi2108.arus.nav.ArusGraphKt",
                    "io.github.renaldi2108.arus.nav.ArusGraphKt$*",
                    "io.github.renaldi2108.arus.nav.ArusTabsKt",
                    "io.github.renaldi2108.arus.nav.ArusTabsKt$*",
                    "io.github.renaldi2108.arus.core.ComposeStateKt$*",
                    "io.github.renaldi2108.arus.effects.CollectEffectsKt$*",
                    "io.github.renaldi2108.arus.nav.BottomSheetScene",
                    "io.github.renaldi2108.arus.nav.BottomSheetScene$*",
                    "io.github.renaldi2108.arus.nav.ArusBottomSheetStrategy",
                    "io.github.renaldi2108.arus.nav.ArusBottomSheetStrategy$*",
                    // Receiver handed to a composable screen body; its members
                    // only run inside one.
                    "io.github.renaldi2108.arus.nav.ArusScreenScope",
                    // ViewModel that exists only to outlive a composition.
                    "io.github.renaldi2108.arus.nav.NavigatorHolder",
                    // Compiler-generated holders for composable lambdas.
                    "io.github.renaldi2108.arus.nav.ComposableSingletons*",
                    // Wraps a ComposeUiTest; there is no such thing on the JVM.
                    "io.github.renaldi2108.arus.testing.ArusHostUnderTest",
                    "io.github.renaldi2108.arus.testing.ArusHostUnderTestKt",
                    "io.github.renaldi2108.arus.testing.ComposeTestingKt",
                )
            }
        }

        verify {
            warningInsteadOfFailure = false
            rule("Every line the JVM suite can reach is covered") {
                minBound(minLineCoverage, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    // Kover must be applied per-module; the root project only aggregates.
    apply(plugin = "org.jetbrains.kotlinx.kover")

    detekt {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
        // The default `src/main/kotlin` layout does not exist in a Kotlin
        // Multiplatform module, so detekt would silently analyze zero files.
        // Point it at the source sets this project actually uses.
        source.setFrom(
            files(
                "src/commonMain/kotlin",
                "src/androidMain/kotlin",
                "src/iosMain/kotlin",
                "src/commonTest/kotlin",
            ),
        )
    }

    ktlint {
        version.set("1.7.1")
    }
}

// The root project has no `check` of its own — `base` adds the lifecycle task
// so the coverage gate runs with everything else, rather than only when
// somebody remembers `koverVerify`. Coverage is aggregated at the root, so
// this is the only place it can hang.
apply(plugin = "base")

tasks.named("check") { dependsOn(tasks.named("koverVerify")) }
