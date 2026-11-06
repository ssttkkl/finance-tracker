import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildKonfig)
}

val composeDemoModeEnabled = providers.environmentVariable("FT_COMPOSE_DEMO_MODE")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val composeApiOrigin = providers.environmentVariable("FT_API_ORIGIN").getOrElse("")

buildkonfig {
    packageName = "com.finance.tracker.shared"
    objectName = "ComposeBuildConfig"

    defaultConfigs {
        buildConfigField(BOOLEAN, "COMPOSE_DEMO_MODE", composeDemoModeEnabled.toString())
        buildConfigField(STRING, "FT_API_ORIGIN", composeApiOrigin)
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    android {
       namespace = "com.finance.tracker.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            api(project(":business-contracts"))
            implementation(project(":business-domain"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.paging.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.kotlinx.io.core)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        val webMain by getting {
            dependencies { implementation(libs.sqldelight.web.worker.driver) }
        }
        val androidHostTest by getting {
            dependencies { implementation(libs.sqldelight.sqlite.driver) }
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(libs.composeUiTestJunit4)
                implementation(libs.composeUiTestManifest)
                implementation(libs.androidx.testExt.junit)
            }
        }
        iosMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        wasmJsMain.dependencies {
            implementation(npm("@js-temporal/polyfill", libs.versions.temporalPolyfill.get()))
        }
        jsMain.dependencies {
            implementation(npm("@js-temporal/polyfill", libs.versions.temporalPolyfill.get()))
            implementation(libs.wrappers.browser)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

tasks.matching { it.name.endsWith("BrowserTest") }.configureEach {
    inputs.files(fileTree(project(":webApp").layout.projectDirectory.dir("src/webMain/resources")) {
        include("ledger-storage/**", "ledger-key-vault.mjs")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
}

// Package dependency notices on all application targets, including JS/Wasm bundles.
val prepareCommonComposeResources by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    from(project(":business-domain").layout.projectDirectory.dir("src/commonMain/resources/META-INF")) {
        include("workbook-third-party-notices.txt")
        into("files/third-party")
    }
    into(layout.buildDirectory.dir("generated/commonComposeResources"))
}

compose.resources {
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = prepareCommonComposeResources.map { layout.projectDirectory.dir(it.destinationDir.absolutePath) },
    )
    packageOfResClass = "com.finance.tracker.resources"
    customDirectory(
        sourceSetName = "wasmJsMain",
        directoryProvider = provider { layout.projectDirectory.dir("src/wasmJsMain/composeResources") },
    )
}

// Actual Ktor response fixtures belong to the explicit database run.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val snapshotFixture = rootProject.file("backend/src/test/resources/ledger-snapshot-parity.json")
    inputs.file(snapshotFixture).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    systemProperty("ft.ledgerSnapshotFixture", snapshotFixture.absolutePath)
    outputs.upToDateWhen { System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR") == null }
    outputs.cacheIf { System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR") == null }
}
