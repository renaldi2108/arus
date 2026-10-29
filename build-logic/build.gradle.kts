plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.kotlin.gradle)
    implementation(libs.plugin.android.gradle)
    implementation(libs.plugin.compose.gradle)
    implementation(libs.plugin.kotlin.compose.gradle)
    implementation(libs.plugin.maven.publish)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}
