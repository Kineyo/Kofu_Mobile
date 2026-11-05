cmake_minimum_required(VERSION 3.22.1)

project("kofu_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
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
    ${log-lib}
    ${android-lib}
)
