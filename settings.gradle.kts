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
rootProject.name = "TgWsProxy"
include(":app")
include(":hevTunnel")
project(":hevTunnel").projectDir = file("third_party/hev-socks5-tunnel-android/library")
