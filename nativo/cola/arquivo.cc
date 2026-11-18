// Leitura de arquivo pelo libavformat (15/11/2026). Serve a quatro coisas do player:
//   1. formatos antigos que o Media3 não abre (AVI, WMV/ASF, RMVB, ...): pacotes para o FfExtrator;
//   3. miniatura ao avançar/voltar: decodifica o quadro-chave mais perto do tempo pedido e reduz para RGBA;
//   5. capítulos do MKV/MP4 (com títulos, para o "pular abertura");
//   e as fontes anexadas ao MKV, que a legenda ASS precisa.
// Os bytes vêm do Kotlin (LeitorNativo: lê de qualquer fonte do app, rede, pasta, nuvem), não de um caminho local.
#include "comum.h"
#include <cstring>
#include <string>
#include <cstdlib>
#include <pthread.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

namespace {
JavaVM* jvm = nullptr;
jmethodID mLer = nullptr, mTamanho = nullptr;
constexpr int PEDACO = 64 * 1024;

struct Arquivo {
  jobject leitor = nullptr;       // referência global ao LeitorNativo
  jbyteArray pedaco = nullptr;    // buffer Java reaproveitado nas leituras
  int64_t posicao = 0;
  int64_t tamanho = -1;
  AVIOContext* io = nullptr;
  AVFormatContext* fmt = nullptr;
  AVPacket* pacote = nullptr;
  bool pacoteCheio = false;
  // Miniatura: decodificador do vídeo aberto na primeira vez e reaproveitado.
  AVCodecContext* minDec = nullptr;
  int minFaixa = -1;
  SwsContext* sws = nullptr;
};

JNIEnv* ambiente() {
  JNIEnv* env = nullptr;
  if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) jvm->AttachCurrentThread(
#ifdef ONETV_HOST
      reinterpret_cast<void**>(&env),
#else
      &env,
#endif
      nullptr);
  return env;
}

int ler(void* opaco, uint8_t* destino, int n) {
  auto* a = static_cast<Arquivo*>(opaco);
  JNIEnv* env = ambiente();
  if (n > PEDACO) n = PEDACO;
  jint lidos = env->CallIntMethod(a->leitor, mLer, static_cast<jlong>(a->posicao), a->pedaco, n);
  if (env->ExceptionCheck()) { env->ExceptionClear(); return AVERROR(EIO); }
  if (lidos <= 0) return AVERROR_EOF;
  if (lidos > n) lidos = n;   // um leitor que devolvesse mais estouraria `destino`
  env->GetByteArrayRegion(a->pedaco, 0, lidos, reinterpret_cast<jbyte*>(destino));
  a->posicao += lidos;
  return lidos;
}

int64_t buscar(void* opaco, int64_t pos, int deOnde) {
  auto* a = static_cast<Arquivo*>(opaco);
  switch (deOnde & ~AVSEEK_FORCE) {
    case AVSEEK_SIZE: return a->tamanho;
    case SEEK_SET: a->posicao = pos; break;
    case SEEK_CUR: a->posicao += pos; break;
    case SEEK_END: if (a->tamanho < 0) return -1; a->posicao = a->tamanho + pos; break;
    default: return -1;
  }
  return a->posicao;
}

void fechar(JNIEnv* env, Arquivo* a) {
  av_packet_free(&a->pacote);
  avcodec_free_context(&a->minDec);
  sws_freeContext(a->sws);
  avformat_close_input(&a->fmt);
  if (a->io) { av_freep(&a->io->buffer); avio_context_free(&a->io); }
  if (a->leitor) env->DeleteGlobalRef(a->leitor);
  if (a->pedaco) env->DeleteGlobalRef(a->pedaco);
  delete a;
}

AVStream* faixa(Arquivo* a, int i) { return (i >= 0 && i < static_cast<int>(a->fmt->nb_streams)) ? a->fmt->streams[i] : nullptr; }
}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) { jvm = vm; return JNI_VERSION_1_6; }

// Diz que formato é este começo de arquivo e com que certeza (0 a 100). "nome:certeza", ou null.
NATIVO(jstring, arquivoSondar)(JNIEnv* env, jclass, jbyteArray inicio) {
  jsize n = env->GetArrayLength(inicio);
  std::string buf(static_cast<size_t>(n) + AVPROBE_PADDING_SIZE, '\0');
  env->GetByteArrayRegion(inicio, 0, n, reinterpret_cast<jbyte*>(&buf[0]));
  AVProbeData pd{};
  pd.filename = "";
  pd.buf = reinterpret_cast<unsigned char*>(&buf[0]);
  pd.buf_size = n;
  int certeza = 0;
  const AVInputFormat* f = av_probe_input_format3(&pd, 1, &certeza);
  if (!f) return nullptr;
  std::string r = std::string(f->name) + ":" + std::to_string(certeza);
  return env->NewStringUTF(r.c_str());
}

// Abre pelo leitor. `infoCompleta` lê o começo dos pacotes para descobrir tamanho/canais (mais lento; o extrator
// precisa, a sonda de capítulos/fontes/miniatura não).
NATIVO(jlong, arquivoAbrir)(JNIEnv* env, jclass, jobject leitor, jboolean infoCompleta) {
  {
    // Sonda e extrator abrem em threads diferentes: sem a trava, a segunda via mLer pronto e mTamanho ainda nulo.
    // pthread (não std::mutex): std::mutex puxava meia libc++ estática para dentro da .so.
    static pthread_mutex_t trava = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&trava);
    if (!mLer || !mTamanho) {
      // Pela interface (não pela classe do objeto): o id vale para qualquer LeitorNativo.
      jclass c = env->FindClass("br/paulo/tvlauncher/LeitorNativo");
      if (!c) { env->ExceptionClear(); pthread_mutex_unlock(&trava); return 0; }
      jmethodID l = env->GetMethodID(c, "ler", "(J[BI)I");
      jmethodID t = env->GetMethodID(c, "tamanho", "()J");
      env->DeleteLocalRef(c);
      if (!l || !t) { env->ExceptionClear(); pthread_mutex_unlock(&trava); return 0; }
      mTamanho = t;
      mLer = l;
    }
    pthread_mutex_unlock(&trava);
  }
  auto* a = new Arquivo();
  a->leitor = env->NewGlobalRef(leitor);
  jbyteArray pedaco = env->NewByteArray(PEDACO);
  if (!pedaco) { env->ExceptionClear(); env->DeleteGlobalRef(a->leitor); delete a; return 0; }
  a->pedaco = static_cast<jbyteArray>(env->NewGlobalRef(pedaco));
  env->DeleteLocalRef(pedaco);
  a->tamanho = env->CallLongMethod(leitor, mTamanho);
  if (env->ExceptionCheck()) { env->ExceptionClear(); a->tamanho = -1; }
  auto* buf = static_cast<uint8_t*>(av_malloc(PEDACO));
  a->io = avio_alloc_context(buf, PEDACO, 0, a, ler, nullptr, buscar);
  a->fmt = avformat_alloc_context();
  a->pacote = av_packet_alloc();
  if (!a->io || !a->fmt || !a->pacote) { if (!a->io) av_free(buf); fechar(env, a); return 0; }
  a->io->seekable = a->tamanho > 0 ? AVIO_SEEKABLE_NORMAL : 0;
  a->fmt->pb = a->io;
  a->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
  if (avformat_open_input(&a->fmt, nullptr, nullptr, nullptr) < 0) { a->fmt = nullptr; fechar(env, a); return 0; }
  if (infoCompleta && avformat_find_stream_info(a->fmt, nullptr) < 0) LOGW("find_stream_info falhou; seguindo");
  return para(a);
}

NATIVO(void, arquivoFechar)(JNIEnv* env, jclass, jlong p) { if (p) fechar(env, de<Arquivo>(p)); }

NATIVO(jlong, arquivoDuracaoUs)(JNIEnv*, jclass, jlong p) {
  auto* a = de<Arquivo>(p);
  return a->fmt->duration == AV_NOPTS_VALUE ? -1 : a->fmt->duration;   // AV_TIME_BASE = µs
}

NATIVO(jint, arquivoFaixas)(JNIEnv*, jclass, jlong p) { return static_cast<jint>(de<Arquivo>(p)->fmt->nb_streams); }

// [tipo (0 vídeo, 1 áudio, 2 legenda, 3 anexo, -1 outro), largura, altura, taxa, canais, duração µs,
//  quadros por segundo x1000, bits por segundo, bits por amostra, alinhamento do bloco]
NATIVO(jlongArray, arquivoFaixaInfo)(JNIEnv* env, jclass, jlong p, jint i) {
  AVStream* s = faixa(de<Arquivo>(p), i);
  if (!s) return nullptr;
  const AVCodecParameters* c = s->codecpar;
  jlong tipo = c->codec_type == AVMEDIA_TYPE_VIDEO ? 0 : c->codec_type == AVMEDIA_TYPE_AUDIO ? 1
             : c->codec_type == AVMEDIA_TYPE_SUBTITLE ? 2 : c->codec_type == AVMEDIA_TYPE_ATTACHMENT ? 3 : -1;
  if (tipo == 0 && (s->disposition & AV_DISPOSITION_ATTACHED_PIC)) tipo = -1;   // capa de MP3/MKV não é vídeo
  AVRational fps = s->avg_frame_rate.num ? s->avg_frame_rate : s->r_frame_rate;
  jlong v[10] = {tipo, c->width, c->height, c->sample_rate, c->ch_layout.nb_channels,
                 s->duration == AV_NOPTS_VALUE ? -1 : av_rescale_q(s->duration, s->time_base, AVRational{1, 1000000}),
                 fps.den ? static_cast<jlong>(av_q2d(fps) * 1000) : 0, c->bit_rate, c->bits_per_coded_sample, c->block_align};
  jlongArray r = env->NewLongArray(10);
  if (!r) return nullptr;
  env->SetLongArrayRegion(r, 0, 10, v);
  return r;
}

NATIVO(jstring, arquivoFaixaCodec)(JNIEnv* env, jclass, jlong p, jint i) {
  AVStream* s = faixa(de<Arquivo>(p), i);
  return s ? env->NewStringUTF(avcodec_get_name(s->codecpar->codec_id)) : nullptr;
}

NATIVO(jbyteArray, arquivoFaixaExtra)(JNIEnv* env, jclass, jlong p, jint i) {
  AVStream* s = faixa(de<Arquivo>(p), i);
  if (!s || s->codecpar->extradata_size <= 0) return nullptr;
  jbyteArray r = env->NewByteArray(s->codecpar->extradata_size);
  if (r) env->SetByteArrayRegion(r, 0, s->codecpar->extradata_size, reinterpret_cast<jbyte*>(s->codecpar->extradata));
  return r;
}

// Metadado da faixa: "language", "title", "filename", "mimetype".
NATIVO(jstring, arquivoFaixaMeta)(JNIEnv* env, jclass, jlong p, jint i, jstring chave) {
  AVStream* s = faixa(de<Arquivo>(p), i);
  if (!s) return nullptr;
  const char* k = chave ? env->GetStringUTFChars(chave, nullptr) : nullptr;
  if (!k) return nullptr;
  const AVDictionaryEntry* e = av_dict_get(s->metadata, k, nullptr, 0);
  env->ReleaseStringUTFChars(chave, k);
  return e ? env->NewStringUTF(e->value) : nullptr;
}

// Capítulos: [início ms, fim ms, início ms, fim ms, ...]. Títulos com arquivoCapituloTitulo.
NATIVO(jlongArray, arquivoCapitulos)(JNIEnv* env, jclass, jlong p) {
  auto* a = de<Arquivo>(p);
  int n = static_cast<int>(a->fmt->nb_chapters);
  jlongArray r = env->NewLongArray(n * 2);
  if (!r) return nullptr;
  for (int i = 0; i < n; i++) {
    const AVChapter* c = a->fmt->chapters[i];
    jlong v[2] = {av_rescale_q(c->start, c->time_base, AVRational{1, 1000}), av_rescale_q(c->end, c->time_base, AVRational{1, 1000})};
    env->SetLongArrayRegion(r, i * 2, 2, v);
  }
  return r;
}

NATIVO(jstring, arquivoCapituloTitulo)(JNIEnv* env, jclass, jlong p, jint i) {
  auto* a = de<Arquivo>(p);
  if (i < 0 || i >= static_cast<int>(a->fmt->nb_chapters)) return nullptr;
  const AVDictionaryEntry* e = av_dict_get(a->fmt->chapters[i]->metadata, "title", nullptr, 0);
  return e ? env->NewStringUTF(e->value) : nullptr;
}

// Lê o próximo pacote. Devolve o tamanho (pegar com arquivoPacoteCopiar), -1 no fim, ERRO_OUTRO em falha.
NATIVO(jint, arquivoLerPacote)(JNIEnv*, jclass, jlong p) {
  auto* a = de<Arquivo>(p);
  if (a->pacoteCheio) { av_packet_unref(a->pacote); a->pacoteCheio = false; }
  int r = av_read_frame(a->fmt, a->pacote);
  if (r == AVERROR_EOF) return -1;
  if (r < 0) return a->fmt->pb && a->fmt->pb->eof_reached ? -1 : ERRO_OUTRO;
  a->pacoteCheio = true;
  return a->pacote->size;
}

NATIVO(jint, arquivoPacoteFaixa)(JNIEnv*, jclass, jlong p) { return de<Arquivo>(p)->pacote->stream_index; }
NATIVO(jboolean, arquivoPacoteChave)(JNIEnv*, jclass, jlong p) { return (de<Arquivo>(p)->pacote->flags & AV_PKT_FLAG_KEY) != 0; }
// Tempo de apresentação em µs (cai para o dts quando o arquivo não tem pts, comum em AVI); Long.MIN_VALUE = sem tempo.
NATIVO(jlong, arquivoPacoteTempoUs)(JNIEnv*, jclass, jlong p) {
  auto* a = de<Arquivo>(p);
  int64_t t = a->pacote->pts != AV_NOPTS_VALUE ? a->pacote->pts : a->pacote->dts;
  if (t == AV_NOPTS_VALUE) return INT64_MIN;
  AVStream* s = a->fmt->streams[a->pacote->stream_index];
  int64_t inicio = s->start_time != AV_NOPTS_VALUE ? s->start_time : 0;
  return av_rescale_q(t - inicio, s->time_base, AVRational{1, 1000000});
}
NATIVO(jlong, arquivoPacoteDuracaoUs)(JNIEnv*, jclass, jlong p) {
  auto* a = de<Arquivo>(p);
  return av_rescale_q(a->pacote->duration, a->fmt->streams[a->pacote->stream_index]->time_base, AVRational{1, 1000000});
}

NATIVO(void, arquivoPacoteCopiar)(JNIEnv* env, jclass, jlong p, jbyteArray destino) {
  auto* a = de<Arquivo>(p);
  if (a->pacoteCheio) env->SetByteArrayRegion(destino, 0, a->pacote->size, reinterpret_cast<jbyte*>(a->pacote->data));
}

// Vai para perto de `tempoUs` (o quadro-chave anterior). Devolve false se o arquivo não deixa.
NATIVO(jboolean, arquivoBuscar)(JNIEnv*, jclass, jlong p, jlong tempoUs) {
  auto* a = de<Arquivo>(p);
  if (a->pacoteCheio) { av_packet_unref(a->pacote); a->pacoteCheio = false; }
  // tempoUs é relativo ao começo (arquivoPacoteTempoUs desconta o start_time); a busca na faixa -1 quer o absoluto.
  int64_t inicio = a->fmt->start_time != AV_NOPTS_VALUE ? a->fmt->start_time : 0;
  return av_seek_frame(a->fmt, -1, tempoUs + inicio, AVSEEK_FLAG_BACKWARD) >= 0;
}

// Miniatura (ideia 3): quadro-chave em `tempoUs` reduzido para largura x altura em RGBA (destino direto, l*a*4 bytes).
// Usa só quadros-chave: rápido o bastante para seguir o ▶▶ segurado, sem decodificar o filme inteiro.
NATIVO(jboolean, arquivoMiniatura)(JNIEnv* env, jclass, jlong p, jlong tempoUs, jobject destino, jint largura, jint altura) {
  auto* a = de<Arquivo>(p);
  auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(destino));
  if (!d || env->GetDirectBufferCapacity(destino) < static_cast<jlong>(largura) * altura * 4) return JNI_FALSE;
  if (!a->minDec) {
    const AVCodec* codec = nullptr;
    a->minFaixa = av_find_best_stream(a->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (a->minFaixa < 0 || !codec) return JNI_FALSE;
    a->minDec = avcodec_alloc_context3(codec);
    if (!a->minDec) return JNI_FALSE;
    if (avcodec_parameters_to_context(a->minDec, a->fmt->streams[a->minFaixa]->codecpar) < 0) {
      avcodec_free_context(&a->minDec); return JNI_FALSE;
    }
    a->minDec->thread_count = 2;
    a->minDec->thread_type = FF_THREAD_SLICE;   // sem atraso de quadros: um pacote, um quadro
    if (avcodec_open2(a->minDec, codec, nullptr) < 0) { avcodec_free_context(&a->minDec); return JNI_FALSE; }
    for (unsigned i = 0; i < a->fmt->nb_streams; i++)
      if (static_cast<int>(i) != a->minFaixa) a->fmt->streams[i]->discard = AVDISCARD_ALL;
  }
  AVStream* s = a->fmt->streams[a->minFaixa];
  int64_t alvo = av_rescale_q(tempoUs, AVRational{1, 1000000}, s->time_base) + (s->start_time != AV_NOPTS_VALUE ? s->start_time : 0);
  if (av_seek_frame(a->fmt, a->minFaixa, alvo, AVSEEK_FLAG_BACKWARD) < 0) { LOGW("miniatura: busca falhou"); return JNI_FALSE; }
  avcodec_flush_buffers(a->minDec);
  AVFrame* q = av_frame_alloc();
  if (!q) return JNI_FALSE;
  if (a->pacoteCheio) { av_packet_unref(a->pacote); a->pacoteCheio = false; }
  bool ok = false;
  // Acha o primeiro quadro-chave (no máximo 60 pacotes de vídeo, para não ficar preso num arquivo ruim), manda só ele e
  // "esvazia" o decodificador: sem isso, quadros seguintes (pulados) nunca empurrariam o chave para fora.
  for (int lidos = 0; lidos < 60; ) {
    if (av_read_frame(a->fmt, a->pacote) < 0) break;
    bool chave = a->pacote->stream_index == a->minFaixa && (a->pacote->flags & AV_PKT_FLAG_KEY);
    if (a->pacote->stream_index == a->minFaixa) lidos++;
    if (!chave) { av_packet_unref(a->pacote); continue; }
    avcodec_send_packet(a->minDec, a->pacote);
    av_packet_unref(a->pacote);
    avcodec_send_packet(a->minDec, nullptr);
    if (avcodec_receive_frame(a->minDec, q) == 0) {
      a->sws = sws_getCachedContext(a->sws, q->width, q->height, static_cast<AVPixelFormat>(q->format),
                                    largura, altura, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
      uint8_t* planos[1] = {d};
      int passos[1] = {largura * 4};
      ok = a->sws && sws_scale(a->sws, q->data, q->linesize, 0, q->height, planos, passos) > 0;
    }
    break;
  }
  avcodec_flush_buffers(a->minDec);   // volta a aceitar pacotes depois do "esvaziar"
  av_frame_free(&q);
  return ok ? JNI_TRUE : JNI_FALSE;
}

// Ponto de busca pelo índice do arquivo (AVI, WMV e RMVB têm um): [tempo µs, posição em bytes] do quadro-chave em ou
// antes de `tempoUs`, ou null sem índice. O FfExtrator usa para o Media3 reabrir a leitura já no lugar certo.
NATIVO(jlongArray, arquivoPontoBusca)(JNIEnv* env, jclass, jlong p, jlong tempoUs) {
  auto* a = de<Arquivo>(p);
  int i = av_find_best_stream(a->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (i < 0) i = 0;
  if (i >= static_cast<int>(a->fmt->nb_streams)) return nullptr;
  AVStream* s = a->fmt->streams[i];
  int64_t inicio = s->start_time != AV_NOPTS_VALUE ? s->start_time : 0;
  int64_t ts = av_rescale_q(tempoUs, AVRational{1, 1000000}, s->time_base) + inicio;
  const AVIndexEntry* e = avformat_index_get_entry_from_timestamp(s, ts, AVSEEK_FLAG_BACKWARD);
  if (!e) return nullptr;
  jlong v[2] = {av_rescale_q(e->timestamp - inicio, s->time_base, AVRational{1, 1000000}), e->pos};
  jlongArray r = env->NewLongArray(2);
  if (!r) return nullptr;
  env->SetLongArrayRegion(r, 0, 2, v);
  return r;
}
