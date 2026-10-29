import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.kotlin.dsl.configure

/**
 * Maven publishing for the published Arus modules.
 *
 * The Kotlin Multiplatform plugin registers one publication per target plus
 * the shared `kotlinMultiplatform` metadata publication. That metadata
 * publication is what lets a plain Android consumer — no Kotlin Multiplatform
 * plugin, no Compose Multiplatform plugin — resolve the `-android` variant
 * through Gradle module metadata.
 *
 * `com.vanniktech.maven.publish` is used rather than bare `maven-publish`
 * because Maven Central needs things the bare plugin does not produce: a
 * sources jar, a javadoc jar, and a PGP signature on every artifact. It also
 * configures all of the per-target publications at once.
 *
 * Nothing publishes as a side effect of building. Uploading is an explicit
 * task, and it needs credentials that live outside this repository.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

/**
 * Coordinates and metadata, overridable from `gradle.properties` or `-P` so a
 * fork can publish under its own name without editing this file.
 */
val mavenGroup: String = providers.gradleProperty("arus.group").getOrElse("io.github.renaldi2108")
val mavenVersion: String = providers.gradleProperty("arus.version").getOrElse("0.1.0-SNAPSHOT")
val projectUrl: String = providers.gradleProperty("arus.url").getOrElse("https://github.com/renaldi2108/arus")
val scmConnection: String =
    providers.gradleProperty("arus.scm.connection")
        .getOrElse("scm:git:git://github.com/renaldi2108/arus.git")
val scmDeveloperConnection: String =
    providers.gradleProperty("arus.scm.developerConnection")
        .getOrElse("scm:git:ssh://git@github.com/renaldi2108/arus.git")
val developerId: String = providers.gradleProperty("arus.developer.id").getOrElse("renaldi2108")
val developerName: String = providers.gradleProperty("arus.developer.name").getOrElse("Renaldi")
val developerEmail: String = providers.gradleProperty("arus.developer.email").getOrElse("maintainers@arus.invalid")
val developerOrg: String = providers.gradleProperty("arus.developer.organization").getOrElse("renaldi2108")
val developerOrgUrl: String = providers.gradleProperty("arus.developer.organizationUrl").getOrElse(projectUrl)

group = mavenGroup
version = mavenVersion

/** What each published module is, for the POM `description`. */
val moduleDescription: String =
    when (project.name) {
        "arus-testing" -> "Test doubles and helpers for Arus: a fake navigator, controller test scopes, and Compose helpers."
        else -> "A navigation and state layer for Kotlin, built on Jetpack Navigation 3 and ViewModel."
    }

extensions.configure<MavenPublishBaseExtension> {
    // Uploads to the Central Portal, which is the only endpoint accepting new
    // coordinates — the legacy OSSRH host is closed to new projects. Staged,
    // not released: the final release stays a manual step.
    publishToMavenCentral()

    // Reads the `signingInMemoryKey*` properties. Verified behaviour: a
    // `-SNAPSHOT` version signs nothing and needs no key, while a release
    // version without one fails the signing task outright ("No configured
    // signatory"). So a local build stays keyless and a release cannot slip
    // out unsigned.
    signAllPublications()

    coordinates(mavenGroup, project.name, mavenVersion)

    pom {
        name.set(project.name)
        description.set(moduleDescription)
        inceptionYear.set("2026")
        url.set(projectUrl)

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            // `email`, `organization` and `organizationUrl` are not optional:
            // the POM validator rejects the publication without them. The
            // defaults use an `.invalid` address on purpose, so a real
            // publish has to supply a real one rather than shipping a
            // plausible-looking placeholder.
            developer {
                id.set(developerId)
                name.set(developerName)
                url.set(projectUrl)
                email.set(developerEmail)
                organization.set(developerOrg)
                organizationUrl.set(developerOrgUrl)
            }
        }

        scm {
            url.set(projectUrl)
            connection.set(scmConnection)
            developerConnection.set(scmDeveloperConnection)
        }
    }
}

/**
 * A release publish must have passed the API gate.
 *
 * `apiCheck` is what keeps the published surface from drifting; publishing
 * around it would make the dump decorative. Snapshots are exempt so one can be
 * cut while an API change is still under review.
 *
 * Signing is deliberately *not* guarded here — the signing plugin already
 * fails a keyless release, and earlier in the task graph than anything this
 * script could add.
 */
if (!mavenVersion.endsWith("SNAPSHOT")) {
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        dependsOn(tasks.named("apiCheck"))
    }
}

/*
 * Publishing does not work with the configuration cache — the plugin does not
 * support it, so an upload must be run as:
 *
 *   ./gradlew publishToMavenCentral --no-configuration-cache
 */
