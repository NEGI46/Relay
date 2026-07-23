pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Relay"
include(":app")
include(":relay-protocol")
include(":pc-gateway")
include(":shared")
include(":composeApp")
include(":broker")
include(":fuzz-jvm")
