rootProject.name = "FinanceTracker"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "A2uiMavenCentral"
                    url = uri("https://repo.maven.apache.org/maven2")
                }
            }
            filter { includeGroup("dev.ynagai.a2ui") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared")
include(":webApp")
include(":business-contracts")
include(":business-domain")
include(":backend")
include(":cashImportWorker")
include(":cli")
include(":client-operations")
