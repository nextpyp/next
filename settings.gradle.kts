pluginManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }
    resolutionStrategy {
        eachPlugin {
            when {
                requested.id.id == "kvision" -> useModule("io.kvision:kvision-gradle-plugin:${requested.version}")
            }
        }
    }
}
rootProject.name = "micromon"

include("dokka-python-api")
