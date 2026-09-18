# CMake for the EGL contract shim (libeglshim.so, arm64-v8a).
#
# Dobby (ARM64 inline hook, MIT) is vendored at ../Dobby at configure time.
# build_egl_shim.sh clones it (default branch, --depth 1) and invokes this with
# the NDK toolchain; no network is needed at cmake time once ../Dobby exists.
#
# We let Dobby build with its defaults (it is a self-contained inline-hook
# engine). Linking the static archive keeps everything in one .so.

cmake_minimum_required(VERSION 3.18.1)
project(egl_contract_shim C CXX)   # C for the shim, CXX for Dobby

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/Dobby/CMakeLists.txt)
  message(FATAL_ERROR
    "Dobby not found at ${CMAKE_CURRENT_SOURCE_DIR}/Dobby. "
    "Run build_egl_shim.sh (it clones the default branch) or vendor it manually.")
endif()

# Defaults are fine; turn off debug logging for a clean release .so.
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
# The APK embeds ONLY libeglshim.so, so Dobby must be linked statically --
# otherwise the .so carries an unresolved DT_NEEDED on libdobby.so that the
# app process cannot satisfy. DOBBY_GENERATE_SHARED is the switch upstream's
# own CMakeLists uses (NOT DOBBY_LIBRARY_TYPE, which it overwrites).
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/Dobby Dobby)

add_library(eglshim SHARED egl_contract_shim.c)
# Old Dobby layouts (<=2022) keep the public header under include/ and expose
# a single "dobby" target (DOBBY_LIBRARY_TYPE defaults to STATIC). New layouts
# would need dobby_static -- see the comment block above for the pin decision.
target_include_directories(eglshim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/Dobby/include)
target_link_libraries(eglshim log dl dobby)
# The shim runs inside an app process and only replaces EGL entry points the
# engine actually calls; keep it minimal and position-independent.
set_target_properties(eglshim PROPERTIES
  LINK_FLAGS "-Wl,--hash-style=both -fvisibility=hidden")
