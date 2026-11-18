// Legenda ASS/SSA completa pelo libass (15/11/2026): estilos, posição, rotação, karaokê, efeitos e as fontes que vêm
// dentro do MKV. O Kotlin entrega o cabeçalho da faixa e cada fala; aqui o libass desenha a tela inteira de legenda num
// buffer RGBA (pré-multiplicado, o formato do Bitmap ARGB_8888 do Android), que vira um Cue de imagem.
#include "comum.h"
#include <cstdarg>
#include <cstring>
#include <string>
extern "C" {
#include <ass/ass.h>
}

namespace {
struct Ass {
  ASS_Library* lib = nullptr;
  ASS_Renderer* ren = nullptr;
  std::string configFontes;   // fonts.conf do fontconfig (escrito pelo Kotlin na pasta do app)
  std::string fontePadrao;    // arquivo de fonte para quando o nome pedido não existe
  bool fontesProntas = false; // ass_set_fonts é caro: só roda antes do primeiro desenho e quando chega fonte nova
  int largura = 0, altura = 0;
};

void logLibass(int nivel, const char* fmt, va_list args, void*) {
  if (nivel > 2) return;   // só erros (o libass fala muito)
#ifdef ONETV_HOST
  vfprintf(stderr, fmt, args); fputc('\n', stderr);
#else
  __android_log_vprint(ANDROID_LOG_WARN, "OneTvLibass", fmt, args);
#endif
}

void prepararFontes(Ass* a) {
  if (a->fontesProntas) return;
  ass_set_fonts(a->ren, a->fontePadrao.empty() ? nullptr : a->fontePadrao.c_str(), "sans-serif",
                ASS_FONTPROVIDER_AUTODETECT, a->configFontes.empty() ? nullptr : a->configFontes.c_str(), 1);
  a->fontesProntas = true;
}

std::string texto(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  if (!c) { env->ExceptionClear(); return {}; }
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

// Mistura uma imagem do libass (máscara de 8 bits + uma cor) no buffer RGBA pré-multiplicado.
void misturar(const ASS_Image* i, uint8_t* destino, int largura, int altura) {
  uint32_t cor = i->color;
  uint32_t r = (cor >> 24) & 0xFF, g = (cor >> 16) & 0xFF, b = (cor >> 8) & 0xFF;
  uint32_t opac = 255 - (cor & 0xFF);   // no ASS o último byte é transparência
  for (int y = 0; y < i->h; y++) {
    int dy = i->dst_y + y;
    if (dy < 0 || dy >= altura) continue;
    const uint8_t* m = i->bitmap + static_cast<size_t>(y) * i->stride;
    uint8_t* linha = destino + static_cast<size_t>(dy) * largura * 4;
    for (int x = 0; x < i->w; x++) {
      int dx = i->dst_x + x;
      if (dx < 0 || dx >= largura || m[x] == 0) continue;
      uint32_t al = (m[x] * opac + 127) / 255;   // alfa deste pixel
      uint8_t* px = linha + dx * 4;
      uint32_t inv = 255 - al;
      px[0] = static_cast<uint8_t>((r * al + px[0] * inv + 127) / 255);
      px[1] = static_cast<uint8_t>((g * al + px[1] * inv + 127) / 255);
      px[2] = static_cast<uint8_t>((b * al + px[2] * inv + 127) / 255);
      px[3] = static_cast<uint8_t>(al + (px[3] * inv + 127) / 255);
    }
  }
}
}  // namespace

NATIVO(jlong, assAbrir)(JNIEnv* env, jclass, jstring configFontes, jstring fontePadrao) {
  auto* a = new Ass();
  a->lib = ass_library_init();
  if (!a->lib) { delete a; return 0; }
  ass_set_message_cb(a->lib, logLibass, nullptr);
  ass_set_extract_fonts(a->lib, 1);
  a->ren = ass_renderer_init(a->lib);
  if (!a->ren) { ass_library_done(a->lib); delete a; return 0; }
  a->configFontes = texto(env, configFontes);
  a->fontePadrao = texto(env, fontePadrao);
  ass_set_hinting(a->ren, ASS_HINTING_NONE);   // o mesmo resultado em qualquer tamanho de tela
  return para(a);
}

// Fonte que veio dentro do MKV (anexo). Precisa chegar antes do primeiro desenho para valer de cara.
NATIVO(void, assFonte)(JNIEnv* env, jclass, jlong p, jstring nome, jbyteArray dados) {
  Ass* a = de<Ass>(p);
  std::string n = texto(env, nome);
  jsize t = env->GetArrayLength(dados);
  jbyte* b = env->GetByteArrayElements(dados, nullptr);
  if (!b) { env->ExceptionClear(); return; }
  ass_add_font(a->lib, n.c_str(), reinterpret_cast<const char*>(b), t);
  env->ReleaseByteArrayElements(dados, b, JNI_ABORT);
  a->fontesProntas = false;
}

// Faixa de dentro do arquivo: o cabeçalho ([Script Info], [V4+ Styles]) vem no CodecPrivate do MKV.
NATIVO(jlong, assFaixa)(JNIEnv* env, jclass, jlong p, jbyteArray cabecalho) {
  Ass* a = de<Ass>(p);
  ASS_Track* t = ass_new_track(a->lib);
  if (!t) return 0;
  if (cabecalho) {
    jsize n = env->GetArrayLength(cabecalho);
    jbyte* b = env->GetByteArrayElements(cabecalho, nullptr);
    if (b) {
      ass_process_codec_private(t, reinterpret_cast<char*>(b), n);
      env->ReleaseByteArrayElements(cabecalho, b, JNI_ABORT);
    } else env->ExceptionClear();
  }
  return para(t);
}

// Arquivo .ass inteiro (legenda de fora: ao lado do vídeo ou baixada).
NATIVO(jlong, assFaixaArquivo)(JNIEnv* env, jclass, jlong p, jbyteArray arquivo) {
  Ass* a = de<Ass>(p);
  jsize n = env->GetArrayLength(arquivo);
  jbyte* b = env->GetByteArrayElements(arquivo, nullptr);
  if (!b) { env->ExceptionClear(); return 0; }
  ASS_Track* t = ass_read_memory(a->lib, reinterpret_cast<char*>(b), n, nullptr);
  env->ReleaseByteArrayElements(arquivo, b, JNI_ABORT);
  return para(t);
}

// Uma fala no formato do bloco do MKV ("ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text").
// O libass ignora a fala repetida (mesmo ReadOrder), então reenviar depois de voltar no filme não duplica.
NATIVO(void, assFala)(JNIEnv* env, jclass, jlong faixa, jbyteArray dados, jlong inicioMs, jlong duracaoMs) {
  auto* t = de<ASS_Track>(faixa);
  if (!t) return;   // assFaixa devolve 0 quando o libass não cria a faixa
  jsize n = env->GetArrayLength(dados);
  jbyte* b = env->GetByteArrayElements(dados, nullptr);
  if (!b) { env->ExceptionClear(); return; }
  ass_process_chunk(t, reinterpret_cast<char*>(b), n, inicioMs, duracaoMs);
  env->ReleaseByteArrayElements(dados, b, JNI_ABORT);
}

// Tamanho da imagem de legenda (= área do vídeo, limitada pelo Kotlin) e do vídeo original (para a proporção certa).
NATIVO(void, assTamanho)(JNIEnv*, jclass, jlong p, jint largura, jint altura, jint videoL, jint videoA) {
  Ass* a = de<Ass>(p);
  a->largura = largura; a->altura = altura;
  ass_set_frame_size(a->ren, largura, altura);
  if (videoL > 0 && videoA > 0) ass_set_storage_size(a->ren, videoL, videoA);
}

// Ideia 7: letra maior ou menor que a do arquivo (1.0 = como veio).
NATIVO(void, assEscala)(JNIEnv*, jclass, jlong p, jdouble fator) { ass_set_font_scale(de<Ass>(p)->ren, fator); }

// Desenha o momento `ms` em `destino` (RGBA, largura*altura*4). Devolve 0 = igual ao anterior (nada mudou),
// 1 = mudou e tem legenda, 2 = mudou e ficou vazia. `forcar` redesenha mesmo sem mudança (buffer novo).
NATIVO(jint, assDesenhar)(JNIEnv* env, jclass, jlong p, jlong faixa, jlong ms, jobject destino, jboolean forcar) {
  Ass* a = de<Ass>(p);
  auto* t = de<ASS_Track>(faixa);
  if (!t || a->largura <= 0 || a->altura <= 0) return 0;
  prepararFontes(a);
  int mudou = 0;
  ASS_Image* img = ass_render_frame(a->ren, t, ms, &mudou);
  if (!mudou && !forcar) return 0;
  auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(destino));
  size_t bytes = static_cast<size_t>(a->largura) * a->altura * 4;
  if (!d || env->GetDirectBufferCapacity(destino) < static_cast<jlong>(bytes)) return 0;
  memset(d, 0, bytes);
  if (!img) return 2;
  for (const ASS_Image* i = img; i; i = i->next) misturar(i, d, a->largura, a->altura);
  return 1;
}

NATIVO(void, assFecharFaixa)(JNIEnv*, jclass, jlong faixa) { if (faixa) ass_free_track(de<ASS_Track>(faixa)); }

NATIVO(void, assFechar)(JNIEnv*, jclass, jlong p) {
  Ass* a = de<Ass>(p);
  if (!a) return;
  ass_renderer_done(a->ren);
  ass_library_done(a->lib);
  delete a;
}
