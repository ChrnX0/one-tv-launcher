// Modelo de plugin de cartão para a tela inicial da Sofaly. Projeto independente: copie esta pasta e abra no Android Studio.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "sofaly-plugin-modelo"
include(":contagem")   // o modelo: contagem regressiva para uma data que a pessoa escolhe
include(":lua")        // exemplo mínimo: fase da Lua, calculada na TV (sem internet)
