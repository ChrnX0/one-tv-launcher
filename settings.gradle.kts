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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "one-tv-launcher"
include(":app")

// Até 12/11/2026 havia aqui os módulos do servidor do Shizuku embutido (fork thedjchi, pasta shizuku/) e um app de teste
// (ferramentas/cliente-shizuku). Saíram: o launcher usa só o shell próprio (cliente ADB Wi-Fi). Ver o git se precisar.
