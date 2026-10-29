pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// Type-safe project accessors are off: with the root project named "arus" and
// a module also named ":arus" (ADR-019), Gradle generates a duplicate
// `getArus()` accessor and fails to configure. Two modules do not justify
// renaming the root project to work around it.

rootProject.name = "arus"

// A single published library artifact. The internal structure is preserved as
// packages (`io.github.renaldi2108.arus.core`, `.effects`, `.nav`), not as separate Gradle
// modules, so consumers depend on one coordinate and -- when consuming raw
// .aar files -- repeat one set of transitive dependencies instead of three.
// See ADR-019.
include(":arus")

// Test-support artifact: consumers add this with `testImplementation`, so it
// stays a separate module rather than shipping fakes into production builds.
include(":arus-testing")
