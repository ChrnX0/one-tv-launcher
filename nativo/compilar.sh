#!/usr/bin/env bash
# Compila as bibliotecas nativas do player (15/11/2026): FFmpeg (LGPL-2.1), dav1d (AV1, BSD), libass (ISC) com
# FreeType (licença FTL), FriBidi (LGPL-2.1), HarfBuzz (MIT), Fontconfig/Expat (MIT) e libunibreak (zlib), e por
# fim a nossa cola JNI (nativo/cola). O resultado vai para nativo/libs/<abi>/ e entra no APK como está: o build do app
# NÃO precisa do NDK. Rode de novo só quando mudar uma versão abaixo ou o código da cola.
#
# Uso:  nativo/compilar.sh                 tudo, para armeabi-v7a e arm64-v8a
#       nativo/compilar.sh cola            só a cola (as bibliotecas de terceiros já compiladas em nativo/obra/)
#       ABIS="host" nativo/compilar.sh     versão para o Linux do computador (testes da cola em nativo/teste/)
# Precisa: Android NDK (ANDROID_NDK_HOME ou $ANDROID_HOME/ndk/<NDK_VERSAO>), curl, tar, make, cmake, meson, ninja,
#          nasm, pkg-config, gperf, python3. Linux ou WSL.
#
# Licenças (por que estas escolhas): o FFmpeg sai em LGPL-2.1 (sem --enable-gpl, sem --enable-version3, sem nonfree),
# e cada biblioteca LGPL (FFmpeg e FriBidi) é uma .so separada, ligada dinamicamente, para quem quiser trocar por outra
# compilação. As outras são permissivas e vão estáticas dentro do libass.so. Os textos das licenças são copiados para
# nativo/licencas/ a cada compilação.
set -euo pipefail

# ---------------------------------------------------------------- versões (com o SHA-256 de cada pacote)
FFMPEG=9.0.2;      FFMPEG_SHA="8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"
DAV1D=1.5.4;       DAV1D_SHA="a1d5b63d2d38ec9bd03acf643caa51fa22edd1e89c5a109c4807717216bbec07"
LIBASS=0.17.5;     LIBASS_SHA="2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc"
FREETYPE=2.14.1;   FREETYPE_SHA="32427e8c471ac095853212a37aef816c60b42052d4d9e48230bab3bdf2936ccc"
FRIBIDI=1.0.16;    FRIBIDI_SHA="1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c"
HARFBUZZ=14.4.0;   HARFBUZZ_SHA="2357ed966c6ced7bfa720b0640c0231065af01158fbea215093ffa15aed44371"
FONTCONFIG=2.16.0; FONTCONFIG_SHA="6a33dc555cc9ba8b10caf7695878ef134eeb36d0af366041f639b1da9b6ed220"
EXPAT=2.8.4;       EXPAT_SHA="656ae1cc8da3b4ea513bb4e254f33e6243938084c0ec6239da873376b09985a7"
UNIBREAK=7.0;      UNIBREAK_SHA="8c9a6e121736cd0d5c890ae3ae96f3f4010a19aa040f1dbded833a62a87717d3"
NDK_VERSAO=${NDK_VERSAO:-28.2.13676358}
API=29   # = minSdk do app

RAIZ=$(cd "$(dirname "$0")" && pwd)
OBRA=$RAIZ/obra            # fontes e compilação (fora do git)
FONTES=$OBRA/fontes
SAIDA=$RAIZ/libs           # o que vai para o APK (no git)
ABIS=${ABIS:-"armeabi-v7a arm64-v8a"}
JOBS=$(nproc 2>/dev/null || echo 4)
ETAPA=${1:-tudo}   # tudo | terceiros | cola

# Decodificadores. Áudio: tudo o que a TV não decodifica (Dolby, DTS, TrueHD, MP2) e o que os formatos antigos usam
# (WMA, RealAudio, ADPCM). Vídeo: por software, só entra quando o chip da TV não aceita o formato.
DEC_AUDIO="aac aac_latm ac3 eac3 dca truehd mlp mp1 mp2 mp3 mp3float vorbis opus flac alac wmav1 wmav2 wmapro
  wmalossless wmavoice cook ra_144 ra_288 sipr atrac3 atrac3p amrnb amrwb gsm gsm_ms
  adpcm_ima_wav adpcm_ms adpcm_g726 adpcm_swf pcm_alaw pcm_mulaw pcm_s16le pcm_s16be pcm_s24le pcm_s24be pcm_s32le
  pcm_u8 pcm_f32le pcm_dvd pcm_bluray"
DEC_VIDEO="h264 hevc mpeg1video mpeg2video mpeg4 msmpeg4v1 msmpeg4v2 msmpeg4v3 wmv1 wmv2 wmv3 vc1 rv10 rv20 rv30 rv40
  flv h263 h263p vp6 vp6f vp6a vp8 vp9 libdav1d mjpeg theora indeo3 indeo5 svq3 cinepak"
# Leitores de arquivo (formatos antigos que o Media3 não abre) e os "parsers" que acham o começo de cada quadro.
DEMUXERS="avi asf flv rm mpegps mpegvideo vob matroska mov mpegts ogg wav mp3 aac ac3 eac3 dts truehd h264 hevc"
PARSERS="aac ac3 dca mlp mpegaudio h264 hevc mpeg4video mpegvideo vc1 vp8 vp9 av1 rv34 cook flac opus vorbis"

baixar() {   # baixar <url> <pasta-final> <sha256 ou vazio>
  local url=$1 destino=$2 sha=$3
  [[ -d "$destino" ]] && return 0
  mkdir -p "$FONTES"
  local tmp; tmp=$(mktemp -d "$FONTES/.baixando.XXXX")
  curl -fL --retry 4 --retry-all-errors -o "$tmp/pacote" "$url"
  local real; real=$(sha256sum "$tmp/pacote" | cut -d' ' -f1)
  if [[ -n "$sha" && "$sha" != "$real" ]]; then echo "SHA-256 diferente em $url: $real" >&2; rm -rf "$tmp"; exit 1; fi
  [[ -z "$sha" ]] && echo "AVISO: sem SHA fixado para $(basename "$destino"): $real" >&2
  tar -xf "$tmp/pacote" -C "$tmp"
  mv "$tmp/$(basename "$destino")" "$destino"
  rm -rf "$tmp"
}

ndk() {
  NDK=${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/$NDK_VERSAO}
  [[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || { echo "NDK não achado em $NDK" >&2; exit 1; }
  TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
}

# Variáveis de cada arquitetura: alvo do clang, família do meson, arch/cpu do FFmpeg.
alvo() {
  case $ABI in
    armeabi-v7a) ALVO=armv7a-linux-androideabi; FAMILIA=arm;     FF_ARCH=arm;     FF_CPU=armv7-a
                 FLAGS="-march=armv7-a -mfpu=neon -mfloat-abi=softfp" ;;
    arm64-v8a)   ALVO=aarch64-linux-android;    FAMILIA=aarch64; FF_ARCH=aarch64; FF_CPU=armv8-a; FLAGS="" ;;
    host)        ALVO=""; FAMILIA=x86_64; FF_ARCH=x86_64; FF_CPU=x86-64; FLAGS="" ;;
    *) echo "ABI desconhecida: $ABI" >&2; exit 1 ;;
  esac
  PREFIXO=$OBRA/instalado/$ABI
  if [[ $ABI == host ]]; then
    CC=cc; CXX=c++; AR=ar; RANLIB=ranlib; NM=nm; STRIP=strip
  else
    CC=$TC/$ALVO$API-clang; CXX=$TC/$ALVO$API-clang++; AR=$TC/llvm-ar; RANLIB=$TC/llvm-ranlib; NM=$TC/llvm-nm; STRIP=$TC/llvm-strip
  fi
  # 16 KB de página: exigência do Google Play para apps de 64 bits a partir de 2025; inofensivo no de 32.
  LDPAGINA="-Wl,-z,max-page-size=16384"
}

pc() { PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$PREFIXO/lib/pkgconfig" "$@"; }

meson_() {   # meson_ <nome> <fonte> [opções]
  local nome=$1 fonte=$2; shift 2
  local dir=$OBRA/compilando/$nome/$ABI cruz=$OBRA/compilando/$nome/$ABI.cruz
  rm -rf "$dir"; mkdir -p "$(dirname "$dir")"
  local extra=()
  if [[ $ABI != host ]]; then
    cat > "$cruz" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = '$(command -v pkg-config)'
[built-in options]
c_args = [$(printf "'%s'," $FLAGS)'-fPIC']
cpp_args = [$(printf "'%s'," $FLAGS)'-fPIC']
[properties]
needs_exe_wrapper = true
[host_machine]
system = 'android'
cpu_family = '$FAMILIA'
cpu = '$FAMILIA'
endian = 'little'
EOF
    extra=(--cross-file="$cruz")
  fi
  pc meson setup "$dir" "$fonte" "${extra[@]}" --prefix="$PREFIXO" --libdir=lib --buildtype=release \
    --default-library=static --wrap-mode=nodownload -Db_staticpic=true -Dauto_features=disabled "$@"
  ninja -C "$dir" -j"$JOBS" install
}

cmake_() {   # cmake_ <nome> <fonte> [opções]
  local nome=$1 fonte=$2; shift 2
  local dir=$OBRA/compilando/$nome/$ABI
  rm -rf "$dir"
  local extra=()
  [[ $ABI != host ]] && extra=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$API")
  cmake -S "$fonte" -B "$dir" "${extra[@]}" -DCMAKE_INSTALL_PREFIX="$PREFIXO" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@"
  cmake --build "$dir" -j "$JOBS"
  cmake --install "$dir"
}

autotools_() (   # autotools_ <nome> <fonte> [opções]
  nome=$1 fonte=$2; shift 2
  dir=$OBRA/compilando/$nome/$ABI
  rm -rf "$dir"; mkdir -p "$dir"; cd "$dir"
  host=(); [[ $ABI != host ]] && host=(--host="$ALVO")
  CC="$CC" AR="$AR" RANLIB="$RANLIB" NM="$NM" STRIP="$STRIP" CFLAGS="-O2 -fPIC $FLAGS" LDFLAGS="$LDPAGINA" \
    PKG_CONFIG="$(command -v pkg-config) --static" PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$PREFIXO/lib/pkgconfig" \
    "$fonte/configure" "${host[@]}" --prefix="$PREFIXO" "$@"
  make -j"$JOBS"; make install
)

ffmpeg_() (
  cd "$FONTES/ffmpeg-$FFMPEG"
  opcoes=()
  for d in $DEC_AUDIO $DEC_VIDEO; do opcoes+=(--enable-decoder="$d"); done
  for d in $DEMUXERS; do opcoes+=(--enable-demuxer="$d"); done
  for p in $PARSERS; do opcoes+=(--enable-parser="$p"); done
  cruz=()
  if [[ $ABI != host ]]; then
    cruz=(--enable-cross-compile --target-os=android --arch="$FF_ARCH" --cpu="$FF_CPU" --cc="$CC" --cxx="$CXX"
          --nm="$NM" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP")
    [[ $ABI == armeabi-v7a ]] && cruz+=(--enable-neon)
  fi
  make distclean >/dev/null 2>&1 || true
  PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$PREFIXO/lib/pkgconfig" ./configure "${cruz[@]}" \
    --prefix="$PREFIXO" --pkg-config="$(command -v pkg-config)" --pkg-config-flags=--static \
    --extra-cflags="-O3 -fPIC $FLAGS -I$PREFIXO/include" --extra-ldflags="-L$PREFIXO/lib $LDPAGINA" \
    --enable-shared --disable-static --disable-programs --disable-doc --disable-debug --disable-symver \
    --disable-everything --disable-avdevice --disable-avfilter --disable-network --disable-autodetect \
    --enable-avcodec --enable-avformat --enable-swresample --enable-swscale --enable-libdav1d \
    --enable-protocol=file "${opcoes[@]}"
  # Garantia de licença: o configure tem de dizer LGPL 2.1; se aparecer GPL ou "version 3", para tudo.
  grep -q '^License: LGPL version 2.1 or later' ffbuild/config.log 2>/dev/null || ./configure --help >/dev/null
  licenca=$(grep -m1 '^#define FFMPEG_LICENSE' config.h | cut -d'"' -f2)
  [[ "$licenca" == "LGPL version 2.1 or later" ]] || { echo "FFmpeg saiu com licença '$licenca'" >&2; exit 1; }
  make -j"$JOBS"; make install
)

terceiros() {
  baixar "https://ffmpeg.org/releases/ffmpeg-$FFMPEG.tar.xz" "$FONTES/ffmpeg-$FFMPEG" "$FFMPEG_SHA"
  baixar "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D/dav1d-$DAV1D.tar.gz" "$FONTES/dav1d-$DAV1D" "$DAV1D_SHA"
  baixar "https://github.com/libass/libass/releases/download/$LIBASS/libass-$LIBASS.tar.xz" "$FONTES/libass-$LIBASS" "$LIBASS_SHA"
  baixar "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE.tar.xz" "$FONTES/freetype-$FREETYPE" "$FREETYPE_SHA"
  baixar "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI/fribidi-$FRIBIDI.tar.xz" "$FONTES/fribidi-$FRIBIDI" "$FRIBIDI_SHA"
  baixar "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ/harfbuzz-$HARFBUZZ.tar.xz" "$FONTES/harfbuzz-$HARFBUZZ" "$HARFBUZZ_SHA"
  baixar "https://www.freedesktop.org/software/fontconfig/release/fontconfig-$FONTCONFIG.tar.xz" "$FONTES/fontconfig-$FONTCONFIG" "$FONTCONFIG_SHA"
  baixar "https://github.com/libexpat/libexpat/releases/download/R_${EXPAT//./_}/expat-$EXPAT.tar.xz" "$FONTES/expat-$EXPAT" "$EXPAT_SHA"
  baixar "https://github.com/adah1972/libunibreak/releases/download/libunibreak_${UNIBREAK//./_}/libunibreak-$UNIBREAK.tar.gz" "$FONTES/libunibreak-$UNIBREAK" "$UNIBREAK_SHA"

  for ABI in $ABIS; do
    alvo
    meson_ dav1d "$FONTES/dav1d-$DAV1D" -Denable_tools=false -Denable_tests=false
    meson_ freetype "$FONTES/freetype-$FREETYPE" -Dharfbuzz=disabled -Dzlib=disabled -Dpng=disabled -Dbrotli=disabled -Dbzip2=disabled
    # FriBidi é LGPL: sai como .so própria (as outras vão estáticas dentro do libass.so).
    meson_ fribidi "$FONTES/fribidi-$FRIBIDI" -Ddocs=false -Dbin=false -Dtests=false --default-library=shared
    meson_ harfbuzz "$FONTES/harfbuzz-$HARFBUZZ" -Dtests=disabled -Dutilities=disabled -Dsubset=disabled -Dfreetype=enabled
    cmake_ expat "$FONTES/expat-$EXPAT" -DEXPAT_SHARED_LIBS=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF \
      -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_DOCS=OFF
    # Fontes do sistema Android; o cache vai para a pasta do app (a cola passa FONTCONFIG_FILE na abertura).
    meson_ fontconfig "$FONTES/fontconfig-$FONTCONFIG" -Dxml-backend=expat -Dcache-build=disabled -Dtools=disabled \
      -Dtests=disabled -Ddoc=disabled -Dnls=disabled -Ddefault-fonts-dirs=/system/fonts -Dadditional-fonts-dirs=/product/fonts
    autotools_ unibreak "$FONTES/libunibreak-$UNIBREAK" --disable-shared --enable-static
    autotools_ libass "$FONTES/libass-$LIBASS" --enable-shared --disable-static --enable-fontconfig \
      --enable-libunibreak --disable-test --disable-profile --disable-require-system-font-provider
    ffmpeg_
  done

  # Textos de licença de tudo o que vai no APK.
  local L=$RAIZ/licencas; rm -rf "$L"; mkdir -p "$L"
  cp "$FONTES/ffmpeg-$FFMPEG/COPYING.LGPLv2.1" "$L/ffmpeg-LGPL-2.1.txt"
  cp "$FONTES/dav1d-$DAV1D/COPYING" "$L/dav1d.txt"
  cp "$FONTES/libass-$LIBASS/COPYING" "$L/libass.txt"
  cp "$FONTES/freetype-$FREETYPE/docs/FTL.TXT" "$L/freetype-FTL.txt"
  cp "$FONTES/fribidi-$FRIBIDI/COPYING" "$L/fribidi-LGPL-2.1.txt"
  cp "$FONTES/harfbuzz-$HARFBUZZ/COPYING" "$L/harfbuzz.txt"
  cp "$FONTES/fontconfig-$FONTCONFIG/COPYING" "$L/fontconfig.txt"
  cp "$FONTES/expat-$EXPAT/COPYING" "$L/expat.txt"
  cp "$FONTES/libunibreak-$UNIBREAK/LICENCE" "$L/libunibreak.txt"
  printf 'FFmpeg %s, dav1d %s, libass %s, FreeType %s, FriBidi %s, HarfBuzz %s, Fontconfig %s, Expat %s, libunibreak %s\n' \
    "$FFMPEG" "$DAV1D" "$LIBASS" "$FREETYPE" "$FRIBIDI" "$HARFBUZZ" "$FONTCONFIG" "$EXPAT" "$UNIBREAK" > "$L/VERSOES.txt"
}

cola() {
  for ABI in $ABIS; do
    alvo
    cmake_ cola "$RAIZ/cola" -DPREFIXO="$PREFIXO"
    local destino=$SAIDA/$ABI; [[ $ABI == host ]] && destino=$OBRA/host-libs
    rm -rf "$destino"; mkdir -p "$destino"
    # Só as .so de verdade (sem os links libavcodec.so.62 -> ...): o Android carrega pelo nome exato do SONAME.
    for so in avutil swresample swscale avcodec avformat; do
      local real; real=$(readlink -f "$PREFIXO/lib/lib$so.so")
      cp "$real" "$destino/lib$so.so"
    done
    cp "$(readlink -f "$PREFIXO/lib/libfribidi.so")" "$destino/libfribidi.so"
    cp "$(readlink -f "$PREFIXO/lib/libass.so")" "$destino/libass.so"
    cp "$OBRA/compilando/cola/$ABI/libonetvplayer.so" "$destino/"
    [[ $ABI != host ]] && "$STRIP" --strip-unneeded "$destino"/*.so
  done
}

[[ "${ABIS}" == "host" ]] || ndk
[[ $ETAPA == cola ]] || terceiros
[[ $ETAPA == terceiros ]] || cola
echo "Pronto: $SAIDA"
