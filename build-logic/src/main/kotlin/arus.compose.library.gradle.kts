import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Layers Compose Multiplatform onto a module that already applies `arus.kmp.library`.
 *
 * Only the Compose *runtime* is added here. Modules that render UI (`arus-nav`,
 * `arus-effects`) add `foundation`/`material3` themselves, keeping `arus-core`
 * free of the UI toolkit beyond what `@Composable` itself requires.
 */
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val composeVersion = libs.findVersion("composeMultiplatform").get().requiredVersion

// `navigation3-ui:1.2.0` declares Compose Multiplatform 1.10.x. Because nothing
// else requests a higher version of `compose.ui` / `compose.foundation`, Gradle
// would resolve those to 1.10.x while the Compose plugin itself targets
// `composeVersion`, which the plugin reports as a version-mismatch warning.
// The user-facing Compose Multiplatform artifacts ship as one coordinated set,
// so aligning them on a single version removes the skew.
//
// Two families are deliberately excluded, both because they publish on their
// own release line and forcing `composeVersion` onto them fails to resolve:
//
//  - `org.jetbrains.compose.*-internal` — repackaged AndroidX internals,
//    stopping at 1.11.1.
//  - `org.jetbrains.compose.material3` — stops at 1.9.0; there is no 1.12.x.
//    It is pinned by the catalog instead, and its transitive `foundation` and
//    `ui` are still aligned by the rule below, which is the skew that matters.
val unalignedComposeGroups =
    listOf(
        "org.jetbrains.compose.material3",
    )

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val group = requested.group
        val aligned =
            group.startsWith("org.jetbrains.compose") &&
                !group.endsWith("-internal") &&
                group !in unalignedComposeGroups
        if (aligned) {
            useVersion(composeVersion)
            because("Align Compose Multiplatform artifacts on $composeVersion")
        }
    }
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        dependencies {
            // `api`, not `implementation`: public signatures such as
            // `collectState(): State<S>` expose Compose runtime types.
            api(libs.findLibrary("compose-runtime").get())
        }
    }
}
