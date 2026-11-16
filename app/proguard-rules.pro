# Regras do R8 para o build release (04/11/2026). Sem elas o release quebra em silencio:

# Servidor do Shizuku embutido (voltou em 15/11/2026): o processo shizuku_server é iniciado por
# `app_process ... rikka.shizuku.server.ShizukuService` (nome da classe em texto, fora do app) e fala por AIDL/binder
# com o launcher e com os apps clientes.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.rish.** { *; }
-keep class rikka.parcelablelist.** { *; }
-keep class dev.rikka.** { *; }
-keep class rikka.hidden.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**
-dontwarn dev.rikka.**
# As APIs escondidas do Android (stubs so de compilacao, "compileOnly hidden-stub") nao existem no classpath do R8.
-dontwarn android.**
-dontwarn com.android.internal.**

# Shell proprio: cliente ADB (libadb-android), SPAKE2 (JNI) e certificados (sun-security-android).
-keep class io.github.muntashirakon.** { *; }
# org.conscrypt agora sao as PONTES do proprio app (src/main/java/org/conscrypt, 04/11/2026), achadas pela libadb por
# Class.forName: o R8 nao pode renomear nem remover.
-keep class org.conscrypt.** { *; }
-keep class android.sun.security.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn org.conscrypt.**
-dontwarn android.sun.security.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# smbj (pasta compartilhada SMB, 07/11/2026): mensagens e enums achados por reflexão; Kerberos (javax.security.auth.kerberos,
# org.ietf.jgss) não existe no Android e não é usado (entramos por NTLM). slf4j sem implementação: só avisa uma vez no log.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**

# SFTP (JSch, 14/11/2026): os algoritmos são carregados pelo NOME da classe (Class.forName) a partir da configuração;
# sem isto o R8 apagaria as classes "não usadas" e a conexão falharia só no APK de release.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
# FTP (Commons Net): os leitores de listagem (formato UNIX, Windows, MLSD...) também são escolhidos pelo nome.
-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**

# Player nativo (15/11/2026, FfNativo + nativo/cola): o C++ chama as funções pelo NOME (Java_br_paulo_tvlauncher_FfNativo_*)
# e lê os bytes pela interface LeitorNativo (métodos ler/tamanho achados por nome). Renomear quebraria só no release.
-keep class br.paulo.tvlauncher.FfNativo { native <methods>; }
-keep interface br.paulo.tvlauncher.LeitorNativo { *; }
-keepclassmembers class * implements br.paulo.tvlauncher.LeitorNativo { int ler(long, byte[], int); long tamanho(); }
