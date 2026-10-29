plugins {
    id("arus.kmp.library")
    id("arus.compose.library")
    id("arus.publishing")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        commonMain.dependencies {
            api(project(":arus"))
            api(libs.kotlinx.coroutines.test)
            api(libs.turbine)
            // `setArusContent` takes a `ComposeUiTest` receiver, so the type is
            // part of this module's public API.
            api(libs.compose.ui.test)
        }
    }
}
