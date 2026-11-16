// Plugin "Sofaly Extras" (16/11/2026, decisão da venda nº 4): APP SEPARADO, grátis, baixado no site por escolha da
// pessoa. O app da Play não aponta para ele. Traz de volta o que a Play não aceita dentro do launcher: hoje, o aviso
// automático "a TV não foi ligada hoje" (ideia 79). Regras: sem acessibilidade e sem leitor de notificações (o Play
// Protect barra APK de fora que declara isso); conversa com o launcher só pelo ExtrasProvider dele, depois do OK do
// dono com PIN (o plugin aparece em Configurações › Tela inicial › Plugins). Ver app/.../ExtrasPlugin.kt.
// applicationId próprio, que nunca muda: tv.sofaly.extras.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.sofaly.extras"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.sofaly.extras"
        minSdk = 29
        targetSdk = 35
        versionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = "1.0"
    }

    // A chave do projeto no "Build e Release" (mesmas variáveis do launcher); fora dele, a debug.
    val keystoreDoAmbiente = System.getenv("ASSINATURA_ARQUIVO")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("release") {
            if (keystoreDoAmbiente != null) {
                storeFile = file(keystoreDoAmbiente)
                storePassword = System.getenv("ASSINATURA_SENHA")?.trim()?.takeIf { it.isNotBlank() } ?: "android"
                keyAlias = System.getenv("ASSINATURA_ALIAS")?.trim()?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
                keyPassword = System.getenv("ASSINATURA_SENHA_CHAVE")?.trim()?.takeIf { it.isNotBlank() } ?: storePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystoreDoAmbiente != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
