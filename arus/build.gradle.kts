plugins {
    id("arus.kmp.library")
    id("arus.compose.library")
    id("arus.publishing")
    alias(libs.plugins.kotlinSerialization)
}

// Compose behaviour cannot be asserted on the JVM host without Robolectric, so
// the composable-level regression tests run as instrumented tests on a device.
// See BACKLOG-19.
kotlin {
    android {
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.savedstate)
            api(libs.androidx.navigation3.runtime)
            api(libs.jb.navigation3.ui)
            // For `bottomSheetDialog<R>`. ModalBottomSheet lives in plain
            // material3 — not material3-adaptive, as an earlier note claimed —
            // so this is the whole cost of making sheets first-class. See
            // ADR-031 for the size it adds and why it is `api`.
            api(libs.compose.material3)

            // `api`, not `implementation`: the documented way to use Arus
            // calls into these, so a consumer cannot compile without them on
            // its own compile classpath.
            //   viewModel { } / createSavedStateHandle()  -> viewmodel-compose
            //   currentRoute.collectAsStateWithLifecycle() -> runtime-compose
            //   @Serializable on every route               -> serialization-core
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.kotlinx.serialization.core)

            // Internal: the entry decorators. Nothing a consumer names.
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotlinx.serialization.json)
        }
        // Accessors are only generated for source sets the script can see at
        // compile time, and `androidDeviceTest` is created by the builder above.
        getByName("androidDeviceTest").dependencies {
            implementation(project(":arus-testing"))
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.test.junit4.android)
            implementation(libs.compose.ui.test.manifest)
            implementation(libs.androidx.test.runner)
            // compose ui-test pulls an Espresso too old for API 37, where
            // InputManager.getInstance no longer exists and every test dies
            // inside onIdle. 3.7.0 is the first that works there.
            implementation(libs.androidx.test.espresso.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The Compose Multiplatform resources plugin creates this task for the
// device-test source set but never configures its output directory, so the
// build fails validation before a test can run. This module ships no Compose
// resources, so there is nothing for it to copy.
tasks
    .matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }
    .configureEach { enabled = false }
