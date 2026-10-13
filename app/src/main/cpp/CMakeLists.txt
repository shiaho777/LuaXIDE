cmake_minimum_required(VERSION 3.22.1)
project(luax C)

set(CMAKE_C_STANDARD 11)

# The engine lives outside the module, shared with the CLI build.
# app/src/main/cpp -> ../../../../engine
set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine")

add_library(luax SHARED
    luax_jni.c
    "${ENGINE_DIR}/lx.c"
)

target_include_directories(luax PRIVATE "${ENGINE_DIR}")

target_compile_options(luax PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wno-unused
    -Wno-unused-result
)

# Link-time size trimming + math lib + android log.
target_link_options(luax PRIVATE -Wl,--gc-sections)
find_library(log-lib log)
target_link_libraries(luax ${log-lib} m android)
