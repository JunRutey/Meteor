plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.meteor.memory.feature.people"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core-storage"))
    // Editing a person's notes uses the same Markdown surface as a diary entry
    // (ADR-020), so the caret/markup rules live in exactly one place.
    implementation(project(":feature-editor"))
    // The media section renders thumbnails through the shared media module
    // (ADR-052 §5 E), so a video/audio cover is drawn by exactly one composable.
    implementation(project(":feature-media"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
