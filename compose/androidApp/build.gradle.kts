import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val configuredApiOrigin = providers.gradleProperty("ftApiOrigin")
    .orElse(providers.environmentVariable("FT_API_ORIGIN"))
    .getOrElse("")
val escapedApiOrigin = configuredApiOrigin.replace("\\", "\\\\").replace("\"", "\\\"")
val configuredWebOrigin = providers.gradleProperty("ftWebOrigin")
    .orElse(providers.environmentVariable("FT_WEB_ORIGIN"))
    .getOrElse("")

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.finance.tracker"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.finance.tracker"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "FT_API_ORIGIN", "\"$escapedApiOrigin\"")
        manifestPlaceholders["ftWebOrigin"] = configuredWebOrigin
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
