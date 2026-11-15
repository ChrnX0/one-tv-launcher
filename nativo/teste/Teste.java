import br.paulo.tvlauncher.FfNativo;
import br.paulo.tvlauncher.LeitorNativo;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Teste da cola nativa no computador (15/11/2026): mesma cola do APK, compilada para Linux (ABIS=host).
 * Roda com nativo/teste/rodar.sh <pasta de amostras>. Cada verificação imprime OK ou FALHOU; sai com 1 se alguma falhar.
 */
public class Teste {
  static int falhas = 0;
  static void conferir(boolean ok, String o) { System.out.println((ok ? "OK      " : "FALHOU  ") + o); if (!ok) falhas++; }

  /** LeitorNativo de um arquivo local, como o FfExtrator faz com a rede. */
  static class Arquivo implements LeitorNativo {
    final RandomAccessFile f;
    Arquivo(File a) throws Exception { f = new RandomAccessFile(a, "r"); }
    public int ler(long pos, byte[] d, int n) {
      try { f.seek(pos); int r = f.read(d, 0, n); return r <= 0 ? -1 : r; } catch (Exception e) { return -1; }
    }
    public long tamanho() { try { return f.length(); } catch (Exception e) { return -1; } }
  }

  public static void main(String[] a) throws Exception {
    File pasta = new File(a[0]);
    for (String lib : new String[]{"avutil", "swresample", "swscale", "avcodec", "avformat", "fribidi", "ass", "onetvplayer"})
      System.load(new File(a[1], "lib" + lib + ".so").getAbsolutePath());

    conferir(FfNativo.versao().length() > 0, "versão do FFmpeg: " + FfNativo.versao());
    for (String c : new String[]{"ac3", "eac3", "dca", "truehd", "mp2", "wmav2", "cook", "mpeg4", "wmv3", "rv40", "libdav1d", "h264", "hevc"})
      conferir(FfNativo.temDecodificador(c), "tem decodificador " + c);

    avi(new File(pasta, "pegasus-1958-chiptune.avi"));
    capitulos(new File(pasta, "asf_with_chapters.wmv"));
    wma(new File(pasta, "asf_with_chapters.wmv"));
    ac3(new File(pasta, "Canyon-5.1-48khz-448kbit.ac3"));
    ass(new File(a[2]));
    System.out.println(falhas == 0 ? "\nTUDO OK" : "\n" + falhas + " FALHA(S)");
    System.exit(falhas == 0 ? 0 : 1);
  }

  static String sondar(File f) throws Exception {
    byte[] b = new byte[8192];
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.readFully(b, 0, (int) Math.min(b.length, r.length())); }
    return FfNativo.arquivoSondar(b);
  }

  static void avi(File f) throws Exception {
    System.out.println("\n== AVI (formatos antigos, ideia 1) " + f.getName());
    String s = sondar(f);
    conferir(s != null && s.startsWith("avi:"), "sonda reconhece AVI: " + s);
    long ctx = FfNativo.arquivoAbrir(new Arquivo(f), true);
    conferir(ctx != 0, "abre");
    int n = FfNativo.arquivoFaixas(ctx);
    int fv = -1, fa = -1;
    long[] infoV = null;
    for (int i = 0; i < n; i++) {
      long[] info = FfNativo.arquivoFaixaInfo(ctx, i);
      System.out.println("        faixa " + i + ": tipo=" + info[0] + " codec=" + FfNativo.arquivoFaixaCodec(ctx, i) + " " + info[1] + "x" + info[2] + " " + info[3] + " Hz " + info[4] + " canais");
      if (info[0] == 0 && fv < 0) { fv = i; infoV = info; }
      if (info[0] == 1 && fa < 0) fa = i;
    }
    conferir(fv >= 0 && fa >= 0, "tem vídeo e áudio");
    long duracao = FfNativo.arquivoDuracaoUs(ctx);
    conferir(duracao > 10_000_000, "duração " + duracao / 1_000_000 + " s");
    long[] ponto = FfNativo.arquivoPontoBusca(ctx, duracao / 2);
    conferir(ponto != null && ponto[1] > 0 && ponto[0] <= duracao / 2, "índice dá ponto de busca no meio: " + (ponto == null ? "null" : ponto[0] + " µs @ " + ponto[1]));

    long dv = FfNativo.videoAbrir(FfNativo.arquivoFaixaCodec(ctx, fv), FfNativo.arquivoFaixaExtra(ctx, fv), (int) infoV[1], (int) infoV[2], 4);
    String codecA = FfNativo.arquivoFaixaCodec(ctx, fa);
    long[] infoA = FfNativo.arquivoFaixaInfo(ctx, fa);
    long da = FfNativo.audioAbrir(codecA, FfNativo.arquivoFaixaExtra(ctx, fa), (int) infoA[3], (int) infoA[4], false, (int) infoA[9], (int) infoA[7], (int) infoA[8]);
    conferir(dv != 0 && da != 0, "abre decodificadores de vídeo e áudio");
    int quadros = 0, pacotesV = 0, bytesPcm = 0;
    long ultimo = Long.MIN_VALUE; boolean ordem = true;
    ByteBuffer ent = ByteBuffer.allocateDirect(4 << 20);
    int w = (int) infoV[1], h = (int) infoV[2];
    int py = (w + 15) & ~15, puv = ((w + 1) / 2 + 15) & ~15;
    ByteBuffer yuv = ByteBuffer.allocateDirect(py * h + 2 * puv * ((h + 1) / 2));
    byte[] pac = new byte[4 << 20];
    for (int k = 0; k < 600; k++) {
      int t = FfNativo.arquivoLerPacote(ctx);
      if (t < 0) break;
      int faixa = FfNativo.arquivoPacoteFaixa(ctx);
      FfNativo.arquivoPacoteCopiar(ctx, pac);
      ent.clear(); ent.put(pac, 0, t); ent.flip();
      if (faixa == fv) {
        pacotesV++;
        int r = FfNativo.videoDecodificar(dv, ent, t, FfNativo.arquivoPacoteTempoUs(ctx));
        while (r > 0) {
          long tq = FfNativo.videoTempo(dv);
          if (tq < ultimo) ordem = false;
          ultimo = tq;
          if (!FfNativo.videoCopiar(dv, yuv, py, puv)) break;
          quadros++;
          r--;
        }
      } else if (faixa == fa) {
        int r = FfNativo.audioDecodificar(da, ent, t);
        if (r > 0) bytesPcm += r;
      }
    }
    conferir(quadros > pacotesV / 2, "vídeo por software: " + quadros + " quadros de " + pacotesV + " pacotes");
    conferir(ordem, "quadros saem em ordem de tempo");
    conferir(bytesPcm > 0 && FfNativo.audioCanais(da) > 0, "áudio " + codecA + ": " + bytesPcm + " bytes PCM, " + FfNativo.audioCanais(da) + " canais, " + FfNativo.audioTaxa(da) + " Hz");
    FfNativo.videoFechar(dv); FfNativo.audioFechar(da);
    conferir(FfNativo.arquivoBuscar(ctx, duracao / 2), "busca no meio");
    int lidos = FfNativo.arquivoLerPacote(ctx);
    long tb = FfNativo.arquivoPacoteTempoUs(ctx);
    conferir(lidos > 0 && Math.abs(tb - duracao / 2) < 15_000_000, "depois da busca o pacote está perto do meio: " + tb / 1000 + " ms");
    FfNativo.arquivoFechar(ctx);

    // Miniatura (ideia 3): sessão separada, como no app.
    long m = FfNativo.arquivoAbrir(new Arquivo(f), false);
    ByteBuffer rgba = ByteBuffer.allocateDirect(160 * 90 * 4);
    long t0 = System.nanoTime();
    boolean ok = FfNativo.arquivoMiniatura(m, duracao / 3, rgba, 160, 90);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    int naoPreto = 0;
    for (int i = 0; i < 160 * 90 * 4; i += 4) if ((rgba.get(i) & 0xFF) + (rgba.get(i + 1) & 0xFF) + (rgba.get(i + 2) & 0xFF) > 30) naoPreto++;
    conferir(ok && naoPreto > 1000, "miniatura 160x90 em " + ms + " ms, " + naoPreto + " pixels com imagem");
    FfNativo.arquivoFechar(m);
  }

  static void capitulos(File f) throws Exception {
    System.out.println("\n== Capítulos (ideia 5) " + f.getName());
    long ctx = FfNativo.arquivoAbrir(new Arquivo(f), false);
    conferir(ctx != 0, "abre WMV: " + sondar(f));
    long[] c = FfNativo.arquivoCapitulos(ctx);
    conferir(c.length >= 4, (c.length / 2) + " capítulos");
    for (int i = 0; i < c.length / 2; i++) System.out.println("        " + c[2 * i] + "-" + c[2 * i + 1] + " ms: " + FfNativo.arquivoCapituloTitulo(ctx, i));
    FfNativo.arquivoFechar(ctx);
  }

  // Visto na Sala (15/11): o WMA não abria sem o tamanho do bloco e a taxa do contêiner.
  static void wma(File f) throws Exception {
    System.out.println("\n== WMA (bloco do contêiner) " + f.getName());
    long ctx = FfNativo.arquivoAbrir(new Arquivo(f), true);
    int fa = -1;
    for (int i = 0; i < FfNativo.arquivoFaixas(ctx); i++) if (FfNativo.arquivoFaixaInfo(ctx, i)[0] == 1) { fa = i; break; }
    long[] info = FfNativo.arquivoFaixaInfo(ctx, fa);
    String codec = FfNativo.arquivoFaixaCodec(ctx, fa);
    long d = FfNativo.audioAbrir(codec, FfNativo.arquivoFaixaExtra(ctx, fa), (int) info[3], (int) info[4], false, (int) info[9], (int) info[7], (int) info[8]);
    conferir(d != 0, "abre " + codec + " (bloco " + info[9] + ", " + info[7] + " b/s)");
    ByteBuffer ent = ByteBuffer.allocateDirect(1 << 16);
    byte[] pac = new byte[1 << 16];
    int total = 0;
    for (int k = 0; k < 200 && d != 0; k++) {
      int t = FfNativo.arquivoLerPacote(ctx);
      if (t < 0) break;
      if (FfNativo.arquivoPacoteFaixa(ctx) != fa) continue;
      FfNativo.arquivoPacoteCopiar(ctx, pac);
      ent.clear(); ent.put(pac, 0, t); ent.flip();
      int r = FfNativo.audioDecodificar(d, ent, t);
      if (r > 0) { ByteBuffer s = ByteBuffer.allocateDirect(r); FfNativo.audioCopiar(d, s); total += r; }
    }
    conferir(total > 0, "sai som: " + total + " bytes");
    if (d != 0) FfNativo.audioFechar(d);
    FfNativo.arquivoFechar(ctx);
  }

  static void ac3(File f) throws Exception {
    System.out.println("\n== Dolby AC-3 5.1 " + f.getName());
    long ctx = FfNativo.arquivoAbrir(new Arquivo(f), true);
    conferir(ctx != 0 && "ac3".equals(FfNativo.arquivoFaixaCodec(ctx, 0)), "abre e acha ac3");
    long d = FfNativo.audioAbrir("ac3", null, 0, 0, false, 0, 0, 0);
    ByteBuffer ent = ByteBuffer.allocateDirect(1 << 16);
    byte[] pac = new byte[1 << 16];
    int total = 0;
    for (int k = 0; k < 50; k++) {
      int t = FfNativo.arquivoLerPacote(ctx);
      if (t < 0) break;
      FfNativo.arquivoPacoteCopiar(ctx, pac);
      ent.clear(); ent.put(pac, 0, t); ent.flip();
      int r = FfNativo.audioDecodificar(d, ent, t);
      if (r > 0) {
        ByteBuffer s = ByteBuffer.allocateDirect(r);
        FfNativo.audioCopiar(d, s);
        total += r;
      }
    }
    conferir(FfNativo.audioCanais(d) == 6 && FfNativo.audioTaxa(d) == 48000, "5.1 a 48 kHz: " + FfNativo.audioCanais(d) + " canais " + FfNativo.audioTaxa(d) + " Hz");
    conferir(total == 50 * 1536 * 6 * 2, "PCM 16 bits: " + total + " bytes (esperado " + 50 * 1536 * 12 + ")");
    FfNativo.audioFechar(d);
    FfNativo.arquivoFechar(ctx);
  }

  static int pixelsComTinta(ByteBuffer b) {
    int n = 0;
    for (int i = 3; i < b.capacity(); i += 4) if (b.get(i) != 0) n++;
    return n;
  }

  static void ass(File fontsConf) throws Exception {
    System.out.println("\n== Legenda ASS (libass)");
    long lib = FfNativo.assAbrir(fontsConf.getAbsolutePath(), "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    conferir(lib != 0, "abre libass");
    String arq = "[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\n\n"
      + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
      + "Style: Default,DejaVu Sans,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,30,1\n\n"
      + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
      + "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\\fad(300,300)}Olá, legenda em português\n"
      + "Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,{\\pos(200,100)\\frz20\\c&H0000FF&}Em cima, girada\n";
    long faixa = FfNativo.assFaixaArquivo(lib, arq.getBytes(StandardCharsets.UTF_8));
    conferir(faixa != 0, "lê .ass inteiro");
    FfNativo.assTamanho(lib, 1280, 720, 1920, 1080);
    ByteBuffer img = ByteBuffer.allocateDirect(1280 * 720 * 4);
    int r0 = FfNativo.assDesenhar(lib, faixa, 500, img, true);
    conferir(r0 == 2, "antes da 1ª fala: vazio (" + r0 + ")");
    int r1 = FfNativo.assDesenhar(lib, faixa, 1500, img, false);
    int p1 = pixelsComTinta(img);
    conferir(r1 == 1 && p1 > 2000, "1,5 s: uma fala desenhada (" + p1 + " pixels)");
    int r2 = FfNativo.assDesenhar(lib, faixa, 2500, img, false);
    int p2 = pixelsComTinta(img);
    conferir(r2 == 1 && p2 > p1, "2,5 s: duas falas (" + p2 + " pixels)");
    // A fala de cima está no canto de cima (\pos): tem tinta nas primeiras 200 linhas.
    int topo = 0;
    for (int y = 0; y < 200; y++) for (int x = 0; x < 1280; x++) if (img.get((y * 1280 + x) * 4 + 3) != 0) topo++;
    conferir(topo > 500, "\\pos põe a fala em cima (" + topo + " pixels no topo)");
    int r3 = FfNativo.assDesenhar(lib, faixa, 2600, img, false);
    conferir(r3 == 0 || r3 == 1, "sem mudança não precisa redesenhar (" + r3 + ")");
    FfNativo.assEscala(lib, 1.5);
    FfNativo.assDesenhar(lib, faixa, 2500, img, true);
    int p3 = pixelsComTinta(img);
    conferir(p3 > p2 * 1.4, "escala 1,5 (ideia 7) aumenta a letra (" + p3 + " pixels)");
    FfNativo.assEscala(lib, 1.0);
    // Caminho do MKV: cabeçalho + falas no formato do bloco.
    String cab = arq.substring(0, arq.indexOf("[Events]")) + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
    long f2 = FfNativo.assFaixa(lib, cab.getBytes(StandardCharsets.UTF_8));
    FfNativo.assFala(f2, "1,0,Default,,0,0,0,,Fala do MKV".getBytes(StandardCharsets.UTF_8), 10_000, 2_000);
    FfNativo.assFala(f2, "1,0,Default,,0,0,0,,Fala do MKV".getBytes(StandardCharsets.UTF_8), 10_000, 2_000);   // repetida (voltou no filme)
    int r4 = FfNativo.assDesenhar(lib, f2, 11_000, img, true);
    int p4 = pixelsComTinta(img);
    conferir(r4 == 1 && p4 > 1000, "fala do MKV aparece no tempo (" + p4 + " pixels)");
    conferir(FfNativo.assDesenhar(lib, f2, 12_500, img, false) == 2, "fala do MKV some depois da duração");
    FfNativo.assFecharFaixa(faixa); FfNativo.assFecharFaixa(f2); FfNativo.assFechar(lib);
  }
}
