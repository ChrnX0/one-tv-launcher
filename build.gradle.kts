// Arquivo raiz: declara as versões dos plugins. O app em si é configurado em app/build.gradle.kts.
// AGP 8.11.1 e Kotlin 2.2.20 (alinhadas em 04/11/2026 com o Shizuku embutido).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

// Configuração comum dos módulos do Shizuku (na origem isto ficava no build.gradle raiz do repositório deles).
// Sem NDK desde 15/11/2026: a parte nativa (librish.so, o terminal "rish") ficou de fora; o servidor não precisa dela.
subprojects {
    if (path == ":app" || path == ":celular") return@subprojects
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            defaultConfig { minSdk = 24 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}
