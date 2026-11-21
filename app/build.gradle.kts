// "java.util.Properties" escrito por extenso não funciona aqui: no script do Gradle, "java" é a extensão do plugin Java.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 04/11/2026: o build não lê mais o local.properties (além do sdk.dir, que o Gradle lê sozinho). As credenciais da
// VPN própria (vpn.* ali dentro) iam em texto puro para o APK via BuildConfig; com a troca para o app AdGuard VPN
// elas saíram do build. O local.properties do dono continua como está.

// 05/11/2026: dados PESSOAIS (cidade, coordenadas, apps preferidos, eclipses da cidade) saíram do Config.kt, porque o
// repositório é público. Eles vêm do arquivo pessoal.properties, na raiz do repo, que NÃO vai para o git (.gitignore).
// Sem o arquivo (quem baixa do GitHub, ou o build do GitHub Actions), valem padrões neutros. Modelo comentado em
// pessoal.properties.exemplo. Cada valor vira um campo da classe BuildConfig, que o Config.kt lê.
val pessoal = Properties().apply {
    val f = rootProject.file("pessoal.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }   // UTF-8: acentos (ã, ç) saem certos
}
// Texto Java entre aspas, com \ e " escapados (senão um valor com aspas quebra a compilação do BuildConfig).
fun textoJava(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// Build da Play (-Pplay, 17/11/2026): sai do PC do dono, que tem o pessoal.properties; cidade, coordenadas, apps fixos,
// METAR e eclipses dele NÃO podem ir para o app de todos os clientes. Na Play só as chaves de serviço (chaveApp) vêm de lá.
val dadosPessoais = if (project.hasProperty("play")) Properties() else pessoal
fun pessoalTexto(chave: String, padrao: String = "") = textoJava(dadosPessoais.getProperty(chave, padrao).trim())
// Chaves dos APLICATIVOS de filmes e séries (ServicosFilmes.kt, 14/11/2026): uma por projeto, nunca no git. No PC vêm do
// pessoal.properties; no GitHub Actions, das variáveis de ambiente que o release.yml preenche com os secrets de mesmo nome.
// Sem a chave, o serviço some da tela do launcher.
fun chaveApp(nome: String) = textoJava((System.getenv(nome) ?: pessoal.getProperty(nome.lowercase(), "")).trim())
fun pessoalNumero(chave: String, padrao: Double) = (dadosPessoais.getProperty(chave)?.trim()?.toDoubleOrNull() ?: padrao).toString()

android {
    namespace = "br.paulo.tvlauncher"
    // Ideia 96: o Robolectric precisa dos recursos (textos, cores) para desenhar as telas nos testes.
    testOptions { unitTests.isIncludeAndroidResources = true }
    // 36 (antes 34): veio com o Shizuku embutido em 04/11/2026 (androidx.core 1.16 pede compileSdk >= 35); ficou.
    // Não muda o comportamento na TV: quem manda nisso é o targetSdk, que continua 34.
    compileSdk = 36

    defaultConfig {
        // 19/11/2026 (decisão do Paulo: nenhum pacote público com o nome dele): o app é tv.sofaly.launcher, no site e na
        // Play. O namespace do código continua br.paulo.tvlauncher (classes não mudam). `-Plegacy` monta a PONTE com o
        // pacote antigo: a versão que as TVs antigas instalam e que passa o Device Owner e os ajustes para o app novo
        // (PackageMigration.kt). Só existe enquanto houver TV no pacote antigo.
        applicationId = if (project.hasProperty("legacy")) "br.paulo.tvlauncher" else "tv.sofaly.launcher"
        minSdk = 29   // Android 10, o mínimo do Google TV (15/11/2026: abaixo disso ~27 chamadas de API 28-29 derrubavam o launcher)
        targetSdk = 34
        // 04/11/2026: a atualização automática (GitHub Releases) precisa de um versionCode que sempre cresce, senão o
        // Android recusa a instalação ("downgrade"). Usamos os minutos desde 01/01/2026 na hora do build, no PC e no
        // GitHub: todo build novo é maior que qualquer um mais antigo, de qualquer branch. Antes era sempre 1.
        // Cabe num Int por uns 4 mil anos.
        // `-PversionCode=N`: o Release monta a ponte e o app novo com o MESMO número (dois builds podem cair em minutos
        // diferentes, e a ponte reconhece o app novo do mesmo Release pela tag).
        versionCode = (project.findProperty("versionCode") as String?)?.toInt()
            ?: ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = "1.0"


        // Dados pessoais (ver pessoal.properties no topo). Padrões neutros entre aspas ou como número.
        buildConfigField("String", "CIDADE", pessoalTexto("cidade"))
        buildConfigField("double", "LATITUDE", pessoalNumero("latitude", 0.0))
        buildConfigField("double", "LONGITUDE", pessoalNumero("longitude", 0.0))
        buildConfigField("boolean", "USAR_FAHRENHEIT", (dadosPessoais.getProperty("fahrenheit")?.trim() == "true").toString())
        buildConfigField("String", "ESTACAO_METAR", pessoalTexto("estacao_metar"))
        buildConfigField("String", "APPS_FIXOS", pessoalTexto("apps_fixos"))
        buildConfigField("String", "APPS_ORDEM", pessoalTexto("apps_ordem",
            "com.google.android.youtube.tv,com.google.android.youtube.tvmusic,com.netflix.ninja,com.android.vending"))
        buildConfigField("String", "BOTAO_NETFLIX", pessoalTexto("botao_netflix", "com.netflix.ninja"))
        buildConfigField("String", "ECLIPSES", pessoalTexto("eclipses"))
        // Cuidar de longe (15/11/2026, decisão do Paulo): a Play proíbe app que avisa terceiros sobre um adulto, mesmo
        // com consentimento (regra de stalkerware). Build da Play = `-Pplay`: só fica o pedido de ajuda (77); o aviso à
        // família do lembrete (78) e da TV que não ligou (79) só existe no APK do site.
        buildConfigField("boolean", "CUIDAR_FAMILIA", (!project.hasProperty("play")).toString())
        // Build da Play (`-Pplay`, 16/11/2026): o produto Sofaly. PLAY liga a cobrança da Play e desliga o que a loja
        // não aceita (atualizador do GitHub, instalar APK). A fonte do clima não depende mais do build (19/11/2026): é o
        // plano e a licença que escolhem, em DataSources.kt (MET Norway no Básico).
        buildConfigField("boolean", "PLAY", project.hasProperty("play").toString())
        // Build da Play: sem TMDB (chave pessoal; uso comercial pede contrato) até haver contrato. O Simkl entra (19/11/2026)
        // só no caminho grátis que eles aceitaram: a conta da própria pessoa e o convite "Conecte sua conta"; a nota sem
        // login fica para o Pro com licença (DataSources.kt). Trakt (Standard) e OpenSubtitles (login da pessoa) ficam.
        val semLicencaNaPlay = setOf("TMDB_CHAVE")
        for (k in listOf("TMDB_CHAVE", "TRAKT_ID", "TRAKT_SEGREDO", "SIMKL_ID", "OPENSUBTITLES_CHAVE"))
            buildConfigField("String", k, if (project.hasProperty("play") && k in semLicencaNaPlay) "\"\"" else chaveApp(k))
        // Degrau 2a (17/11/2026): e-mail que recebe o "Relatar um problema" (quem não tem conta no GitHub). Fora do git:
        // Padrão = o e-mail do produto (Paulo, 17/11/2026: contact@sofaly.tv). pessoal.properties "suporte_email" ou a
        // variável SUPORTE_EMAIL do repo trocam; um valor inválido faz o QR voltar ao formulário do GitHub.
        buildConfigField("String", "SUPORTE_EMAIL", textoJava((System.getenv("SUPORTE_EMAIL")?.takeIf { it.isNotBlank() }
            ?: pessoal.getProperty("suporte_email")?.takeIf { it.isNotBlank() } ?: "contact@sofaly.tv").trim()))
    }

    // Um APK por arquitetura (12/11/2026, pedido do dono: versões de 32 e 64 bits no GitHub).
    // - armeabi-v7a (32 bits): o da Sala. `getprop ro.product.cpu.abilist` na TV = armeabi-v7a,armeabi (lido em
    //   04/11/2026): o Chromecast com Google TV tem processador de 64 bits, mas o Android dele é só de 32.
    // - arm64-v8a (64 bits): aparelhos com Android de 64 bits (e a Play exige 64 bits para TV desde 01/08/2026).
    // Separados, não um universal: o túnel (libtsnet.so) pesa ~8 MB comprimido por arquitetura, e a Sala baixaria os
    // 8 MB do 64 bits a cada atualização sem nunca usar. Saem app-armeabi-v7a-release.apk e app-arm64-v8a-release.apk,
    // com o mesmo versionCode. Só essas duas: x86 e as outras não entram (antes o conscrypt trazia 4 ABIs, ~7 MB à toa).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true   // gera a classe BuildConfig (campos do pessoal.properties, ver defaultConfig)
    }

    // Extrai as .so para a pasta lib/arm do app instalado: o túnel (libtsnet.so) é um executável que o TunelTailscale roda
    // de lá (ver o fim deste arquivo). Até 12/11/2026 servia também à librish.so do Shizuku embutido, que saiu.
    packaging {
        jniLibs { useLegacyPackaging = true }
        // 04/11/2026: tabelas da assinatura pós-quântica "Picnic" do bouncycastle (vem de carona numa dependência da
        // libadb). Eram 1,2 MB comprimidos (~2,3 MB reais) no APK e nada do launcher usa Picnic: o pareamento
        // e o TLS do adb usam SPAKE2 (libspake2) e conscrypt. Medido no release: lowmcL1/L3/L5.bin.properties.
        resources {
            excludes += "org/bouncycastle/pqc/crypto/picnic/**"
            // Manifesto OSGi repetido no JSch e no error_prone_annotations (14/11/2026): metadado de servidor Java, não vai no APK.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // Chave de assinatura do release (04/11/2026). Se a variável ASSINATURA_ARQUIVO apontar para um keystore (no GitHub
    // ela vem do segredo ASSINATURA_KEYSTORE_B64), assina com ele. Senão, usa a chave debug desta máquina, como sempre.
    // Desde o reset de 10/11/2026 a Sala roda a chave PÚBLICA dos Releases (a "opção A" de 04/11, Sala na chave debug
    // do PC de build, foi trocada pelo dono) e se atualiza pelos Releases. No PC de build, build sem ASSINATURA_* sai com a chave
    // debug e o install -r na Sala falha por assinatura. O segredo do GitHub nunca é o debug.keystore do PC de build.
    val keystoreDoAmbiente = System.getenv("ASSINATURA_ARQUIVO")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("release") {
            if (keystoreDoAmbiente != null) {
                storeFile = file(keystoreDoAmbiente)
                storePassword = System.getenv("ASSINATURA_SENHA")?.trim()?.takeIf { it.isNotBlank() } ?: "android"           // padrão do keystore debug
                keyAlias = System.getenv("ASSINATURA_ALIAS")?.trim()?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
                keyPassword = System.getenv("ASSINATURA_SENHA_CHAVE")?.trim()?.takeIf { it.isNotBlank() } ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            // Release encolhe o APK e remove código não usado. Assina com a chave debug só para instalar via adb.
            isMinifyEnabled = true
            isShrinkResources = false
            // proguard-rules.pro (04/11/2026): mantém a libadb, o conscrypt e o sun-security, que são achados por nome
            // (reflexão, JNI). Sem elas o release quebrava o shell próprio.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreDoAmbiente != null) "release" else "debug")
        }
    }

    // Build da Play (`./gradlew bundleRelease -Pplay`, 16/11/2026): o produto Sofaly. Package PRÓPRIO, decidido em 16/11/2026
    // e que nunca mais pode mudar depois de publicado; o namespace do código continua br.paulo.tvlauncher e a Sala segue
    // com applicationId br.paulo.tvlauncher (ver CLAUDE.md). Por cima do manifesto principal entra src/play/AndroidManifest.xml
    // (tira as permissões que a loja recusa) e a seção de dados das licenças (src/play/assets). A lista de cidades offline
    // (CidadesOffline.kt) fica em src/main/assets desde 19/11/2026: todo build acha a cidade/CEP sem serviço de fora.
    // Sem flavor de propósito: com flavor todas as tarefas mudariam de nome (assembleRelease, testReleaseUnitTest) e os
    // workflows e scripts da Sala quebrariam.
    if (project.hasProperty("play")) {
        if (project.hasProperty("legacy")) throw GradleException("-Pplay e -Plegacy não combinam: a Play é só tv.sofaly.launcher")
        defaultConfig.applicationId = "tv.sofaly.launcher"
        sourceSets.getByName("main").assets.srcDir("src/play/assets")
        sourceSets.getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")
        sourceSets.getByName("debug").manifest.srcFile("src/play/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.2 trocou o bloco kotlinOptions { } (dentro de android) por este.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Antes eram zero dependências, de propósito (APK menor, menos RAM na TV). Até 03/11/2026 tinha a API do Shizuku
// aqui (app Shizuku desinstalado, dependência removida); hoje só o shell próprio:
dependencies {
    // Shell proprio MARCO 2: cliente ADB embutido (pareamento + TLS + shell). Licenca Apache-2.0 (opcao da dupla).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    // Empréstimo de shell (15/11/2026, "poder máximo"): o servidor do Shizuku EMBUTIDO. As classes do servidor
    // precisam estar no APK do launcher, porque o processo shizuku_server é iniciado com
    // `app_process -Djava.class.path=<este APK>`. "provider" + "api" = o lado gerente: recebe o binder do servidor.
    implementation(project(":server"))
    implementation(project(":provider"))
    implementation(project(":api"))
    // Empréstimo do Device Owner (Dhizuku, 15/11/2026): servidor vendorizado (MIT, dhizuku/NOTICE.md) e a HiddenApiBypass
    // (Apache-2.0) só para ler o mapa de métodos do IDevicePolicyManager no filtro (FiltroDpm.kt).
    implementation(project(":dhizuku-server_api"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // conscrypt-android SAIU (04/11/2026, -1,28 MB de lib nativa): sem ela a libadb usa o TLS 1.3 do próprio Android
    // (SslUtils: SSLContext.getInstance("TLSv1.3"), Android 10+) e, no pareamento, a exportKeyingMaterial da
    // conscrypt da plataforma. Testado na TV: ver o commit.


    // Perfil de compilação (04/11/2026). Medido na TV: o ArtService do Android 14 força o app de HOME para
    // "speed-profile", seja qual for o -m do `cmd package compile`, e sem perfil isso vira "verify" (o log do artd diz
    // "Merge skipped because there are no existing profiles"). Ou seja: o launcher rodava só interpretado/JIT.
    // O src/main/baseline-prof.txt vai dentro do APK, e este pacote grava o perfil na pasta do app quando ele abre
    // (ou pelo broadcast INSTALL_PROFILE, usado pelo build3.ps1). Depois o compile -m speed-profile passa a valer.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Corrotinas (21/11/2026, refatoração C): o trabalho de fundo (Fundo.kt) roda nelas. Já vinham no APK por tabela
    // (media3/mlkit puxam a 1.7.3); declarar a MESMA versão deixa explícito sem crescer o APK.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Cobrança da Play (16/11/2026, CobrancaPlay.kt): só é usada no build -Pplay; no APK do site o cliente nunca liga.
    // A Play exige a versão 8 ou mais nova da biblioteca nos apps novos.
    implementation("com.android.billingclient:billing:8.3.0")
    // QR code (login do Tailscale e endereço do MCP na tela da TV e no painel). Só o núcleo, sem câmera.
    implementation("com.google.zxing:core:3.5.4")

    // Player universal (06/11/2026): Media3 (ExoPlayer), o motor de vídeo do Google. "hls" = listas .m3u8, o formato
    // mais comum de canal ao vivo; .ts direto (Xtream) já vem no exoplayer. Sem o media3-ui: a tela é nossa (Views).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // Sessão de mídia dos players (SessaoMidia.kt, 17/11/2026): o sistema, o Gemini e o MCP passam a ver o que toca.
    implementation("androidx.media3:media3-session:1.11.1")
    // Ideia 90: tradução de legenda NA TV (ML Kit, offline; modelo de ~30 MB baixado no primeiro uso). +~12 MB no APK.
    implementation("com.google.mlkit:translate:17.0.3")
    // Player completo (15/11/2026): FFmpeg (LGPL-2.1) e libass compilados por nós (nativo/compilar.sh), com cola própria
    // (nativo/cola + FfNativo.kt). Substitui a NextLib (GPL). As .so prontas ficam em nativo/libs (ver sourceSets abaixo).
    // Mídias da rede, etapa 2 (07/11/2026): pasta compartilhada SMB 2/3 (Windows, NAS). Apache-2.0. Traz bcprov e slf4j;
    // o R8 corta o que não é usado.
    implementation("com.hierynomus:smbj:0.15.0") {
        // O smbj traz o bcprov-jdk18on; o projeto já tem o bcprov-jdk15to18 (mesmas classes, versão mais nova). Dois = build quebra.
        exclude(group = "org.bouncycastle")
    }
    // "Na rede" com mais protocolos (14/11/2026): FTP/FTPS (Apache Commons Net, Apache-2.0) e SFTP (JSch, fork mantido
    // por mwiede, licença BSD; sem dependências). NFS é código nosso (Nfs.kt), sem biblioteca.
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.github.mwiede:jsch:2.28.8")

    // Testes que rodam no computador (gradlew testReleaseUnitTest), sem TV: a parte do painel no navegador que não
    // usa o Android (PainelHttp, PainelSeguranca). Não entra no APK.
    testImplementation("junit:junit:4.13.2")
    // O org.json do android.jar é só casca nos testes do computador ("Stub!"): a IA (ComandosIa) precisa do de verdade.
    testImplementation("org.json:json:20260814")
    // Leitor de XML em fluxo para os testes do guia XMLTV (PlayerGuia.kt): na TV o Android já traz um (é o mesmo kxml).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Teste de tela por foto (ideia 96, 14/11/2026): o Robolectric desenha as telas de verdade (gráficos nativos) no
    // computador, sem emulador. Só roda com -Ptelas (ver tasks.withType<Test> abaixo): o "Build e Release" não paga isso.
    // Sem o conscrypt do Robolectric: ele é assinado e briga com a ponte nossa em org/conscrypt (mesmo pacote).
    testImplementation("org.robolectric:robolectric:4.16") { exclude(group = "org.conscrypt") }
}

// Ideia 96: os testes de tela (pasta telas/) baixam o Android do Robolectric (~150 MB na primeira vez) e levam minutos.
// Ficam fora do testReleaseUnitTest de sempre; rodam com "./gradlew testReleaseUnitTest -Ptelas" (workflow telas.yml).
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("telas")) exclude("br/paulo/tvlauncher/telas/**")
    else filter { includeTestsMatching("br.paulo.tvlauncher.telas.*") }
    systemProperty("telas.atualizar", project.hasProperty("telasAtualizar").toString())
    systemProperty("telas.auditoria", project.hasProperty("auditoria").toString())
}

// Túnel para a internet (opção E, 05/11/2026): o programa Go em tunel/ (Tailscale embutido, tsnet) compilado para ARM
// 32 e 64 bits (um por APK, ver splits) e posto no APK como "libtsnet.so". O nome .so é o truque: o Android extrai as libs para a pasta nativa do app
// (useLegacyPackaging acima), que é a única de onde um app pode EXECUTAR um binário. TunelTailscale.kt o roda de lá.
// Sem o Go instalado na máquina (ex.: o PC de build hoje), o build segue sem o túnel e avisa; o resto do launcher não muda.
val pastaTunel = layout.buildDirectory.dir("tunel/jniLibs")
// Pasta de libs do Android -> GOARCH do Go. GOARM=7 só vale para o arm de 32 bits (o Go ignora no arm64).
val arquiteturasTunel = mapOf("armeabi-v7a" to "arm", "arm64-v8a" to "arm64")
val compilarTunel = tasks.register("compilarTunel") {
    val fonte = rootProject.file("tunel")
    val saidas = arquiteturasTunel.keys.map { pastaTunel.get().file("$it/libtsnet.so").asFile }
    inputs.dir(fonte)
    outputs.files(saidas)
    doLast {
        for ((abi, goarch) in arquiteturasTunel) {
            val saida = pastaTunel.get().file("$abi/libtsnet.so").asFile
            saida.parentFile.mkdirs()
            val pb = ProcessBuilder("go", "build", "-trimpath", "-ldflags=-s -w", "-o", saida.absolutePath, ".")
                .directory(fonte).redirectErrorStream(true)
            pb.environment().putAll(mapOf("CGO_ENABLED" to "0", "GOOS" to "linux", "GOARCH" to goarch, "GOARM" to "7"))
            val codigo = try {
                val p = pb.start()
                val texto = p.inputStream.bufferedReader().readText()
                p.waitFor().also { if (it != 0) logger.warn("túnel ($abi): go build falhou:\n$texto") }
            } catch (e: java.io.IOException) {
                logger.warn("túnel: Go não está instalado; o APK sai SEM o túnel para a internet (MCP)")
                -1
            }
            if (codigo != 0) { saida.delete(); if (codigo == -1) break }
        }
    }
}
android.sourceSets["main"].jniLibs.srcDir(pastaTunel)
// Player: FFmpeg, libass e a cola nativa, já compilados (nativo/compilar.sh). O build do app não precisa do NDK.
android.sourceSets["main"].jniLibs.srcDir(rootProject.file("nativo/libs"))
tasks.named("preBuild") { dependsOn(compilarTunel) }
