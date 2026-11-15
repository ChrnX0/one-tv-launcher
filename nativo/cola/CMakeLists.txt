# Cola nativa do player (15/11/2026): liga o Media3 ao FFmpeg e ao libass. Código nosso.
# Compilado por nativo/compilar.sh (não pelo Gradle). PREFIXO = pasta com as bibliotecas de terceiros já instaladas.
cmake_minimum_required(VERSION 3.18)
project(onetvplayer CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT PREFIXO)
  message(FATAL_ERROR "Passe -DPREFIXO=<pasta instalada>")
endif()

add_library(onetvplayer SHARED audio.cc video.cc legenda_ass.cc arquivo.cc)
target_include_directories(onetvplayer PRIVATE ${PREFIXO}/include)
target_compile_options(onetvplayer PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_directories(onetvplayer PRIVATE ${PREFIXO}/lib)
target_link_libraries(onetvplayer PRIVATE avformat avcodec swscale swresample avutil ass)

if(ANDROID)
  target_link_libraries(onetvplayer PRIVATE android jnigraphics log)
  target_link_options(onetvplayer PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
else()
  # Versão do computador (testes em nativo/teste): sem Android, a parte de tela vira stub e o JNI vem do JDK.
  find_package(JNI REQUIRED)
  target_include_directories(onetvplayer PRIVATE ${JNI_INCLUDE_DIRS})
  target_compile_definitions(onetvplayer PRIVATE ONETV_HOST=1)
  set_target_properties(onetvplayer PROPERTIES BUILD_RPATH "${PREFIXO}/lib")
endif()
