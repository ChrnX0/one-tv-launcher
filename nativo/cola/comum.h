// Coisas comuns da cola nativa: log, nome das funções JNI e ponteiro guardado num long do Kotlin.
#pragma once
#include <jni.h>
#include <cstdint>

#ifdef ONETV_HOST
#include <cstdio>
#define LOGE(...) (fprintf(stderr, "E/OneTvNativo: " __VA_ARGS__), fputc('\n', stderr))
#define LOGW(...) (fprintf(stderr, "W/OneTvNativo: " __VA_ARGS__), fputc('\n', stderr))
#else
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OneTvNativo", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OneTvNativo", __VA_ARGS__)
#endif

// Todas as funções nativas ficam no objeto Kotlin br.paulo.tvlauncher.FfNativo (@JvmStatic external).
#define NATIVO(tipo, nome) extern "C" JNIEXPORT tipo JNICALL Java_br_paulo_tvlauncher_FfNativo_##nome

template <typename T> inline T* de(jlong p) { return reinterpret_cast<T*>(static_cast<intptr_t>(p)); }
template <typename T> inline jlong para(T* p) { return static_cast<jlong>(reinterpret_cast<intptr_t>(p)); }

// Códigos devolvidos ao Kotlin (iguais em FfNativo.kt).
constexpr int OK_NADA = 0;          // pacote aceito, ainda sem saída
constexpr int ERRO_DADOS = -1;      // pacote estragado: pular e seguir
constexpr int ERRO_OUTRO = -2;      // falha séria: o player mostra erro
