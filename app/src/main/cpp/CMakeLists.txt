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

# ---- JavaScript engine: QuickJS facade (engine-js/), same host contract ----
# app/src/main/cpp -> ../../../../engine-js
set(ENGINE_JS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine-js")
set(QJS_DIR "${ENGINE_JS_DIR}/quickjs")

add_library(luaxjs SHARED
    qjs_jni.c
    "${ENGINE_JS_DIR}/qjs_x.c"
    "${QJS_DIR}/quickjs.c"
    "${QJS_DIR}/libregexp.c"
    "${QJS_DIR}/libunicode.c"
    "${QJS_DIR}/cutils.c"
    "${QJS_DIR}/xsum.c"
)

target_include_directories(luaxjs PRIVATE "${ENGINE_JS_DIR}" "${QJS_DIR}")

target_compile_definitions(luaxjs PRIVATE _GNU_SOURCE)

target_compile_options(luaxjs PRIVATE
    -O2
    -ffunction-sections
    -fdata-sections
    -Wno-unused
    -Wno-unused-result
)

target_link_options(luaxjs PRIVATE -Wl,--gc-sections)
target_link_libraries(luaxjs ${log-lib} m android)
