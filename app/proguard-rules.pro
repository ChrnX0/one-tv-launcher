# Regras do R8 para o build release (04/11/2026). Sem elas o release quebra em silencio:

# (Até 12/11/2026 havia aqui as regras do servidor do Shizuku embutido, que saiu.)
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
