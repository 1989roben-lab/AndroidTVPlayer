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
        maven("http://4thline.org/m2") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "OpenClawTvReceiver"

include(
    ":app",
    ":core-player",
    ":core-protocol",
    ":core-ui",
    ":feature-discovery",
    ":feature-player",
)
