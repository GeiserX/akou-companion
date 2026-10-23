# SPDX-License-Identifier: GPL-3.0-or-later
# libopus from its release tarball (version, URL and SHA-256 come from opus/build.gradle.kts), the
# iOS settings shim compiled as is, and the JNI layer, linked into one libakouopus.so.
cmake_minimum_required(VERSION 3.22.1)
project(akouopus C)

include(FetchContent)

if(EXISTS "${AKOU_OPUS_TARBALL}")
    set(akou_opus_source "${AKOU_OPUS_TARBALL}")
else()
    set(akou_opus_source "${AKOU_OPUS_URL}")
endif()

# A float build with the encoder only in use; everything optional upstream stays off.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libopus
    URL "${akou_opus_source}"
    URL_HASH SHA256=${AKOU_OPUS_SHA256}
)
FetchContent_MakeAvailable(libopus)
set_target_properties(opus PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(akouopus SHARED
    akou_opus_jni.c
    "${AKOU_SHIM_DIR}/akou_opus_shim.c"
)
target_include_directories(akouopus PRIVATE "${AKOU_SHIM_DIR}/include")
# The opus target exports its include/ directory, where the shim finds "opus.h".
target_link_libraries(akouopus PRIVATE opus)
# Export only the JNI entry points, not libopus's own symbols.
target_link_options(akouopus PRIVATE "-Wl,--exclude-libs,ALL")
