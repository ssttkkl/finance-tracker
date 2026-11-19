import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

abstract class StageCashImportWorker @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiled: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bootstrap: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val entry = bootstrap.file("entry.mjs").get().asFile
        val assets = compiled.get().asFile.listFiles().orEmpty()
            .filter { it.extension in setOf("mjs", "wasm") }
            .plus(entry)
            .sortedBy { it.name }
        check(assets.any { it.extension == "wasm" } && assets.any { it.name == "FinanceTracker-cashImportWorker.mjs" }) {
            "Missing linked Kotlin/Wasm cash import Worker assets"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (asset in assets) {
            digest.update(asset.name.toByteArray())
            digest.update(MessageDigest.getInstance("SHA-256").digest(asset.readBytes()))
        }
        val version = digest.digest().toHexString()
        files.sync {
            into(output)
            from(assets) { into("cash-import-worker/assets/$version") }
            from(bootstrap.file("client.mjs")) {
                into("cash-import-worker")
                filter { line: String -> line.replace("__ASSET_VERSION__", version) }
            }
        }
    }
}

val stageCashImportWorker by tasks.registering(StageCashImportWorker::class) {
    dependsOn(":cashImportWorker:wasmJsProductionExecutableCompileSync")
    compiled.set(project(":cashImportWorker").layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/kotlin"))
    bootstrap.set(layout.projectDirectory.dir("src/wasmJsMain/worker"))
    output.set(layout.buildDirectory.dir("generated/cashImportWorker"))
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain { resources.srcDir(stageCashImportWorker) }
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
        }
    }
}
