cmake_minimum_required(VERSION 3.22.1)

project("kofu_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Forzar máxima optimización (-O3) para los kernels SIMD/NEON/DotProd de ggml y llama.cpp incluso en Debug,
# evitando ejecución lenta (-O0) al perfilar inferencia on-device por ADB.
string(REPLACE "-O0" "" CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG}")
string(REPLACE "-O0" "" CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG}")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")
add_compile_options($<$<OR:$<COMPILE_LANGUAGE:C>,$<COMPILE_LANGUAGE:CXX>>:-O3>)
add_compile_options(
    $<$<OR:$<COMPILE_LANGUAGE:C>,$<COMPILE_LANGUAGE:CXX>>:-include>
    $<$<OR:$<COMPILE_LANGUAGE:C>,$<COMPILE_LANGUAGE:CXX>>:${CMAKE_CURRENT_SOURCE_DIR}/include/kofu_api21_compat.h>
)

# Opciones para compilar llama.cpp como librería estática embebida en Android Edge
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build shared libs" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama: build common" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "llama: build app" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "llama: warnings" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "ggml: warnings" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "ggml: runtime weight repack for Q4_0/Q4_K" FORCE)

# Optimización por generación tecnológica (7nm ARMv8.0-A / 5nm ARMv8.2-A+DotProd+FP16 / <5nm Frontier ARMv8.6-A+i8mm)
set(KOFU_SILICON_TIER "5NM" CACHE STRING "Silicon generation tier (7NM, 5NM, FRONTIER)")
set(KOFU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "Target ARMv8 ISA march string")

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(GGML_CPU_ARM_ARCH "${KOFU_ARM_ARCH}" CACHE STRING "ARMv8 target for ggml" FORCE)
endif()

# Switches condicionales para aceleradores externos (solo se activan si el entorno provee SDK/headers)
option(KOFU_ENABLE_KLEIDIAI "Enable Arm KleidiAI kernels when pre-cached" OFF)
option(KOFU_ENABLE_VULKAN "Enable Vulkan Adreno backend when SPIRV-Headers CMake config is available" OFF)
option(KOFU_ENABLE_OPENCL "Enable OpenCL Adreno backend when OpenCL headers/ICD are available" OFF)
option(KOFU_ENABLE_HEXAGON "Enable Qualcomm Hexagon NPU/HTP backend when HEXAGON_SDK_ROOT is configured" OFF)

if(KOFU_ENABLE_KLEIDIAI)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "ggml: kleidiai" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "ggml: kleidiai" FORCE)
endif()

if(KOFU_ENABLE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "ggml: vulkan" FORCE)
endif()

if(KOFU_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "ggml: opencl" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "ggml: opencl adreno kernels" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "ggml: embed opencl kernels" FORCE)
endif()

if(KOFU_ENABLE_HEXAGON AND DEFINED ENV{HEXAGON_SDK_ROOT})
    set(GGML_HEXAGON ON CACHE BOOL "ggml: hexagon" FORCE)
endif()

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
    src/moe_ufs_pager.cpp
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

target_compile_definitions(
    kofu_engine
    PRIVATE
    KOFU_SILICON_TIER_STR="${KOFU_SILICON_TIER}"
    KOFU_ARM_ARCH_STR="${KOFU_ARM_ARCH}"
)

target_compile_options(
    kofu_engine
    PRIVATE
    -O3
    -DNDEBUG
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -Wformat
    -Wformat-security
)

target_link_options(
    kofu_engine
    PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
)

target_link_libraries(
    kofu_engine
    PRIVATE
    llama
    ggml
    ${log-lib}
    ${android-lib}
)
