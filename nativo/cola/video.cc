// Vídeo por software pelo FFmpeg (15/11/2026). Só entra quando o chip da TV não aceita o formato (MPEG-4 ASP de
// AVI antigo, WMV, RealVideo, AV1 em aparelho sem AV1, H.264 10 bits...). Caminho de cada quadro:
//   pacote -> avcodec -> fila de quadros -> cópia em I420 8 bits para o buffer do Media3 -> janela YV12 da SurfaceView.
// A fila existe porque o decodificador pode entregar dois quadros de uma vez (ou nenhum) por causa dos quadros B.
#include "comum.h"
#include <deque>
#include <cstring>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}
#ifndef ONETV_HOST
#include <android/native_window.h>
#include <android/native_window_jni.h>
#endif

namespace {
struct Video {
  AVCodecContext* ctx = nullptr;
  AVPacket* pacote = nullptr;
  std::deque<AVFrame*> prontos;
  SwsContext* sws = nullptr;
};

void esvaziar(Video* v) {
  for (AVFrame* q : v->prontos) av_frame_free(&q);
  v->prontos.clear();
}

// Puxa do decodificador tudo o que já saiu. Devolve false em erro sério.
bool colher(Video* v) {
  for (;;) {
    AVFrame* q = av_frame_alloc();
    int r = avcodec_receive_frame(v->ctx, q);
    if (r < 0) {
      av_frame_free(&q);
      return r == AVERROR(EAGAIN) || r == AVERROR_EOF || r == AVERROR_INVALIDDATA;
    }
    v->prontos.push_back(q);
  }
}
}  // namespace

NATIVO(jlong, videoAbrir)(JNIEnv* env, jclass, jstring codec, jbyteArray extra, jint largura, jint altura, jint threads) {
  const char* nome = env->GetStringUTFChars(codec, nullptr);
  const AVCodec* c = avcodec_find_decoder_by_name(nome);
  if (!c) { LOGE("sem decodificador de vídeo '%s'", nome); env->ReleaseStringUTFChars(codec, nome); return 0; }
  env->ReleaseStringUTFChars(codec, nome);
  auto* v = new Video();
  v->ctx = avcodec_alloc_context3(c);
  v->pacote = av_packet_alloc();
  if (!v->ctx || !v->pacote) { avcodec_free_context(&v->ctx); av_packet_free(&v->pacote); delete v; return 0; }
  if (extra) {
    jsize n = env->GetArrayLength(extra);
    v->ctx->extradata = static_cast<uint8_t*>(av_mallocz(n + AV_INPUT_BUFFER_PADDING_SIZE));
    v->ctx->extradata_size = n;
    env->GetByteArrayRegion(extra, 0, n, reinterpret_cast<jbyte*>(v->ctx->extradata));
  }
  v->ctx->width = largura;    // formatos antigos (RealVideo, WMV) não trazem o tamanho no pacote
  v->ctx->height = altura;
  // Frame + slice: usa todos os núcleos (a Sala tem 4). Atrasa a saída alguns quadros, o que a fila absorve.
  v->ctx->thread_count = threads;
  v->ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  v->ctx->pkt_timebase = AVRational{1, 1000000};   // o tempo do pacote vem em microssegundos do Media3
  if (avcodec_open2(v->ctx, c, nullptr) < 0) {
    LOGE("avcodec_open2 vídeo falhou");
    avcodec_free_context(&v->ctx); av_packet_free(&v->pacote); delete v;
    return 0;
  }
  return para(v);
}

// Manda um pacote. Devolve quantos quadros estão prontos na fila (0 = nenhum ainda), ou ERRO_*.
NATIVO(jint, videoDecodificar)(JNIEnv* env, jclass, jlong p, jobject entrada, jint tamanho, jlong tempoUs) {
  Video* v = de<Video>(p);
  auto* dados = static_cast<uint8_t*>(env->GetDirectBufferAddress(entrada));
  if (!dados) return ERRO_OUTRO;
  v->pacote->data = dados;
  v->pacote->size = tamanho;
  // O mesmo tempo como pts e dts: em MKV/MP4 ele é o de exibição (o dts sai fora de ordem e o FFmpeg usa o pts); em
  // AVI antigo ele é o de decodificação (o pts sai fora de ordem e o FFmpeg passa a usar o dts). best_effort_timestamp
  // escolhe sozinho.
  v->pacote->pts = tempoUs;
  v->pacote->dts = tempoUs;
  int r = avcodec_send_packet(v->ctx, v->pacote);
  if (r == AVERROR(EAGAIN)) {   // saída cheia: colhe e tenta de novo
    if (!colher(v)) return ERRO_OUTRO;
    r = avcodec_send_packet(v->ctx, v->pacote);
  }
  if (r < 0 && r != AVERROR_INVALIDDATA && r != AVERROR(EAGAIN)) { LOGE("send_packet vídeo: %d", r); return ERRO_OUTRO; }
  if (!colher(v)) return ERRO_OUTRO;
  return static_cast<jint>(v->prontos.size());
}

// Dados do quadro da frente da fila: [largura, altura, 0/1 cheio de cores BT.709] e o tempo em µs.
NATIVO(jint, videoLargura)(JNIEnv*, jclass, jlong p) { auto* v = de<Video>(p); return v->prontos.empty() ? 0 : v->prontos.front()->width; }
NATIVO(jint, videoAltura)(JNIEnv*, jclass, jlong p) { auto* v = de<Video>(p); return v->prontos.empty() ? 0 : v->prontos.front()->height; }
NATIVO(jlong, videoTempo)(JNIEnv*, jclass, jlong p) {
  auto* v = de<Video>(p);
  if (v->prontos.empty()) return 0;
  AVFrame* q = v->prontos.front();
  return q->best_effort_timestamp != AV_NOPTS_VALUE ? q->best_effort_timestamp : q->pts;
}
// 1 = BT.601, 2 = BT.709, 3 = BT.2020 (os mesmos números de VideoDecoderOutputBuffer.COLORSPACE_*).
NATIVO(jint, videoCores)(JNIEnv*, jclass, jlong p) {
  auto* v = de<Video>(p);
  if (v->prontos.empty()) return 0;
  switch (v->prontos.front()->colorspace) {
    case AVCOL_SPC_BT709: return 2;
    case AVCOL_SPC_BT2020_NCL: case AVCOL_SPC_BT2020_CL: return 3;
    default: return 1;
  }
}

// Copia o quadro da frente para `destino` em I420 8 bits (Y, depois U, depois V; linhas com os passos dados) e o tira
// da fila. Converte com o swscale quando o quadro é 10 bits, 4:2:2, 4:4:4 ou RGB.
NATIVO(jboolean, videoCopiar)(JNIEnv* env, jclass, jlong p, jobject destino, jint passoY, jint passoUV) {
  Video* v = de<Video>(p);
  if (v->prontos.empty()) return JNI_FALSE;
  AVFrame* q = v->prontos.front();
  v->prontos.pop_front();
  auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(destino));
  int w = q->width, h = q->height, hc = (h + 1) / 2;
  bool ok = d != nullptr && static_cast<jlong>(passoY) * h + 2L * passoUV * hc <= env->GetDirectBufferCapacity(destino);
  if (ok) {
    uint8_t* planos[3] = {d, d + static_cast<size_t>(passoY) * h, d + static_cast<size_t>(passoY) * h + static_cast<size_t>(passoUV) * hc};
    int passos[3] = {passoY, passoUV, passoUV};
    if (q->format == AV_PIX_FMT_YUV420P || q->format == AV_PIX_FMT_YUVJ420P) {
      av_image_copy_plane(planos[0], passoY, q->data[0], q->linesize[0], w, h);
      av_image_copy_plane(planos[1], passoUV, q->data[1], q->linesize[1], (w + 1) / 2, hc);
      av_image_copy_plane(planos[2], passoUV, q->data[2], q->linesize[2], (w + 1) / 2, hc);
    } else {
      v->sws = sws_getCachedContext(v->sws, w, h, static_cast<AVPixelFormat>(q->format), w, h, AV_PIX_FMT_YUV420P,
                                    SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
      ok = v->sws && sws_scale(v->sws, q->data, q->linesize, 0, h, planos, passos) > 0;
    }
  }
  av_frame_free(&q);
  return ok ? JNI_TRUE : JNI_FALSE;
}

// Descarta um quadro sem copiar (o Media3 decidiu não mostrar: atrasado ou antes do ponto de busca).
NATIVO(void, videoPular)(JNIEnv*, jclass, jlong p) {
  Video* v = de<Video>(p);
  if (v->prontos.empty()) return;
  av_frame_free(&v->prontos.front());
  v->prontos.pop_front();
}

NATIVO(void, videoLimpar)(JNIEnv*, jclass, jlong p) {
  Video* v = de<Video>(p);
  avcodec_flush_buffers(v->ctx);
  esvaziar(v);
}

NATIVO(void, videoFechar)(JNIEnv*, jclass, jlong p) {
  Video* v = de<Video>(p);
  if (!v) return;
  esvaziar(v);
  avcodec_free_context(&v->ctx);
  av_packet_free(&v->pacote);
  sws_freeContext(v->sws);
  delete v;
}

// Desenha um quadro I420 numa Surface (a SurfaceView do player) no formato YV12, que toda TV Android aceita.
// YV12: Y com passo múltiplo de 16; depois V e U, cada um com passo = alinhar(passoY / 2, 16).
NATIVO(jboolean, videoDesenhar)(JNIEnv* env, jclass, jobject surface, jobject dados, jint w, jint h, jint passoY, jint passoUV) {
#ifdef ONETV_HOST
  return JNI_FALSE;
#else
  auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(dados));
  if (!src) return JNI_FALSE;
  ANativeWindow* janela = ANativeWindow_fromSurface(env, surface);
  if (!janela) return JNI_FALSE;
  constexpr int32_t YV12 = 0x32315659;
  bool ok = false;
  if (ANativeWindow_setBuffersGeometry(janela, w, h, YV12) == 0) {
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(janela, &b, nullptr) == 0) {
      if (b.width >= w && b.height >= h) {
        auto* dst = static_cast<uint8_t*>(b.bits);
        int hc = (h + 1) / 2, wc = (w + 1) / 2;
        int passoC = ((b.stride / 2) + 15) & ~15;
        const uint8_t* sy = src;
        const uint8_t* su = src + static_cast<size_t>(passoY) * h;
        const uint8_t* sv = su + static_cast<size_t>(passoUV) * hc;
        for (int y = 0; y < h; y++) memcpy(dst + static_cast<size_t>(y) * b.stride, sy + static_cast<size_t>(y) * passoY, w);
        uint8_t* dv = dst + static_cast<size_t>(b.stride) * b.height;
        uint8_t* du = dv + static_cast<size_t>(passoC) * ((b.height + 1) / 2);
        for (int y = 0; y < hc; y++) {
          memcpy(dv + static_cast<size_t>(y) * passoC, sv + static_cast<size_t>(y) * passoUV, wc);
          memcpy(du + static_cast<size_t>(y) * passoC, su + static_cast<size_t>(y) * passoUV, wc);
        }
        ok = true;
      }
      ANativeWindow_unlockAndPost(janela);
    }
  }
  ANativeWindow_release(janela);
  return ok ? JNI_TRUE : JNI_FALSE;
#endif
}
