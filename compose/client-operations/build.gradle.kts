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
        namespace = "com.finance.tracker.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
    iosArm64()
    iosSimulatorArm64()
    js { nodejs(); browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            api(project(":business-contracts"))
            api(project(":business-domain"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinxCoroutines.get()}")
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest { resources.srcDir(rootProject.file("backend/src/test/resources/cash-import-slice")) }
    }
}

val compilerVersion = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("kotlin").get().requiredVersion
val abiReader by configurations.creating
dependencies {
    abiReader("org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion")
}

tasks.register<Exec>("dumpPerformanceAbi") {
    val compilation = tasks.named("compileKotlinJs")
    val klib = layout.buildDirectory.dir("classes/kotlin/js/main")
    val dumper = layout.projectDirectory.file("perf/DumpClientOperationsAbi.java")
    val abi = layout.buildDirectory.file("performance/client-operations.abi")
    dependsOn(compilation)
    inputs.dir(klib)
    inputs.file(dumper)
    inputs.files(abiReader)
    outputs.file(abi)
    doFirst {
        commandLine(
            "java", "--class-path", abiReader.asPath, dumper.asFile.absolutePath,
            klib.get().asFile.absolutePath, abi.get().asFile.absolutePath,
        )
    }
}
