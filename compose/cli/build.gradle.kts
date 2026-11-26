plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js {
        outputModuleName = "ledger-cli"
        nodejs()
        binaries.executable()
        useEsModules()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":client-ledger"))
            implementation(project(":business-domain"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.register<Sync>("stageNpm") {
    val production = tasks.named("compileProductionExecutableKotlinJs")
    from("npm")
    from(production) { into("lib") }
    into(layout.buildDirectory.dir("npm"))
}
