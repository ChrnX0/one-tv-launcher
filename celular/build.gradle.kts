// App de celular do One TV (ideia 101, 14/11/2026): casca fina que embute o painel web da TV. Nada de telas
// duplicadas: o painel continua sendo o da TV (app/src/main/assets/painel). O que só o app faz:
//  1. acha as TVs da casa sozinho (mDNS "_tvlauncher._tcp", o mesmo anúncio do PainelVizinhos da TV);
//  2. pareia lendo o QR da TV uma vez e FIXA o certificado https dela (impressão SHA-256 que vem no QR): sem instalar
//     certificado no celular, e nunca aceita outro depois (mais rígido que confiar numa CA; ver Pareamento.kt);
//  3. botões de volume do celular = volume da TV; 4. "Compartilhar" do Android manda para a TV; 5. várias TVs.
// applicationId próprio: NUNCA o do launcher (br.paulo.tvlauncher), que é o Device Owner das TVs.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.paulo.onetv.celular"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.paulo.onetv.celular"
        minSdk = 29          // SslCertificate.getX509Certificate (fixar o certificado da TV) é do Android 10
        targetSdk = 35
        // Mesmo relógio do launcher (app/build.gradle.kts): cresce a cada compilação.
        versionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = "1.0"
    }

    // A mesma chave do launcher no "Build e Release" (variáveis de ambiente do release.yml); fora dele, a debug.
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Leitor de QR (ZXing, Apache-2.0): funciona sem os serviços do Google.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
