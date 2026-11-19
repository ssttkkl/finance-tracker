import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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

val commitPattern = Regex("[a-f0-9]{40}")
val renderWebRelease = providers.environmentVariable("RENDER_GIT_COMMIT")
    .filter { it.matches(commitPattern) }
val gitWebRelease = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("sh", "-c", "git rev-parse --verify HEAD 2>/dev/null")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().takeIf(commitPattern::matches) ?: "unknown" }
val webRelease = renderWebRelease.orElse(gitWebRelease)

val webSentryDsn = providers.environmentVariable("FT_WEB_SENTRY_DSN").orElse("").map { value ->
    require(value.isEmpty() || value.matches(Regex(
        "(?:https://[A-Za-z0-9]+@[A-Za-z0-9.-]+(?::[0-9]+)?|http://[A-Za-z0-9]+@(?:localhost|127[.]0[.]0[.]1):[0-9]+)/(?:[A-Za-z0-9_-]+/)*[0-9]+"
    ))) { "Invalid FT_WEB_SENTRY_DSN" }
    value
}
val sentryBrowserVersion = "11.14.0"
val sentryBrowserChecksum = "5fdc26d11755569815db45a28eeb2a31849922e71f91192fde7d063c41240ed4"
val sentryBrowserResources = layout.buildDirectory.dir("generated/sentryBrowser")
val sentryBrowserLicense = layout.projectDirectory.file("third-party/sentry-browser-LICENSE.txt")
val prepareWebSentry = tasks.register("prepareWebSentry") {
    val enabled = webSentryDsn.get().isNotEmpty()
    val version = sentryBrowserVersion
    val checksum = sentryBrowserChecksum
    val outputDirectory = sentryBrowserResources.get().asFile
    val licenseSource = sentryBrowserLicense.asFile
    inputs.property("enabled", enabled)
    inputs.property("version", version)
    inputs.property("sha256", checksum)
    inputs.file(licenseSource)
    outputs.dir(outputDirectory)
    doLast {
        val output = outputDirectory.resolve("sentry-browser.min.js")
        val license = outputDirectory.resolve("sentry-browser-LICENSE.txt")
        if (!enabled) {
            output.delete()
            license.delete()
        } else {
            val connection = URI("https://browser.sentry-cdn.com/$version/bundle.min.js").toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val bytes = connection.getInputStream().use { it.readNBytes(500_000) }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == checksum) { "Sentry Browser SDK checksum mismatch" }
            output.parentFile.mkdirs()
            output.writeBytes(bytes)
            licenseSource.copyTo(license, overwrite = true)
        }
    }
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    from(prepareWebSentry)
    val release = webRelease.get()
    val dsn = webSentryDsn.get()
    inputs.property("webSentryDsn", dsn)
    inputs.property("webRelease", release)
    filesMatching("startup.js") {
        filter { line: String -> line.replace("__FT_WEB_RELEASE__", release).replace("__FT_WEB_SENTRY_DSN__", dsn) }
    }
}
