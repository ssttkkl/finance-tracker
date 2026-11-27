plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val copyCliHost = tasks.register<Sync>("copyCliHost") {
    from(rootProject.file("cli/src/jsMain/kotlin/com/finance/tracker/cli")) {
        include("NodeHost.kt")
    }
    into(layout.buildDirectory.dir("cli-host"))
}

kotlin {
    js {
        outputModuleName = "operations-benchmarks"
        nodejs()
        binaries.executable()
        useEsModules()
    }
    sourceSets {
        jsMain {
            kotlin.srcDir(copyCliHost)
            dependencies {
                implementation(project(":client-operations"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

tasks.register<Sync>("stageBenchmark") {
    from(tasks.named("compileProductionExecutableKotlinJs"))
    from(rootProject.file("cli/npm/lib")) {
        include("host.mjs")
        rename("host.mjs", "production-host.mjs")
    }
    from("regular-cli-host.mjs") { rename("regular-cli-host.mjs", "host.mjs") }
    from("regular-host.mjs")
    from("regular-fixture.mjs")
    from("cli-fixture.mjs")
    into(layout.buildDirectory.dir("benchmark"))
}
