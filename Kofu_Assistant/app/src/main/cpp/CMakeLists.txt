cmake_minimum_required(VERSION 3.22.1)

project("kofu_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Opciones para compilar llama.cpp como librería estática embebida en Android ARM64
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build shared libs" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama: build common" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "llama: build app" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "llama: warnings" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "ggml: warnings" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

add_library(
    kofu_engine
    SHARED
    src/model_loader.cpp
    src/prompt_formatter.cpp
    src/kofu_engine.cpp
    src/kofu_jni.cpp
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

target_link_libraries(
    kofu_engine
    PRIVATE
    llama
    ggml
    ${log-lib}
    ${android-lib}
)
