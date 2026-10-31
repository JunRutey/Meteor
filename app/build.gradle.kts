plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// R3 (archive): every module must be removable. Dropping :core-index swaps in a
// no-op SearchService and removes the dependency, so the rest still compiles.
val excludedModules: Set<String> =
    (providers.gradleProperty("meteor.excludeModules").orNull ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

val indexEnabled: Boolean = "core-index" !in excludedModules

android {
    namespace = "com.meteor.memory"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.meteor.memory"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 7
        versionName = "0.6.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (indexEnabled) "src/indexEnabled/kotlin" else "src/indexDisabled/kotlin")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when CI provides a keystore (see .github/workflows/release.yml).
            // Secret names are shared across all of my apps — see the private
            // repository JunRutey/android-signing-keys.
            System.getenv("SIGNING_KEYSTORE_PATH")?.let { path ->
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(path)
                    storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // --- Meteor modules (dependency direction is enforced by tools/check-module-graph.sh)
    implementation(project(":feature-settings"))
    implementation(project(":feature-calendar"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-people"))
    implementation(project(":feature-gallery"))
    implementation(project(":feature-ai"))
    implementation(project(":core-backup"))
    implementation(project(":core-storage"))
    implementation(project(":core-crypto"))
    implementation(project(":core-time"))
    implementation(project(":core-log"))
    if (indexEnabled) {
        implementation(project(":core-index"))
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
