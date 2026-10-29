import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm { compilerOptions.jvmTarget = JvmTarget.JVM_11 }
    android {
        namespace = "com.finance.tracker.business.contracts"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget = JvmTarget.JVM_11
        withHostTest { }
    }
    iosArm64()
    iosSimulatorArm64()
    js { nodejs(); browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs(); browser() }
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.serialization.json) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
