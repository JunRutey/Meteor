plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.meteor.memory.feature.question"
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
    // 回答和日记一样是「编辑 + 预览」两个界面，复用同一套 Markdown 编辑面（ADR-068）。
    // feature-editor 是渲染实现而不是窄接口，这一点与 feature-people 的既有做法一致
    // （DEVELOPMENT §3.3 已登记该瑕疵，这里不再扩大：只用到 MarkdownField / MarkdownPreview）。
    implementation(project(":feature-editor"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    // 回答编辑页与提问页需要拦截系统返回键（有未保存草稿时先确认）。
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
