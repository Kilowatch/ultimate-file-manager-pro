cmake_minimum_required(VERSION 3.22)
project(nfs_jni C)

# ── Build libnfs as a static library ────────────────────────────────────────
# We use libnfs's own CMakeLists.txt which handles all source files,
# platform checks, and config.h generation.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_UTILS OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING ON CACHE BOOL "" FORCE)

# Android does not have a separate libpthread (it is part of libc), so CMAKE_REQUIRED_LIBRARIES=pthread fails.
# We force HAVE_PTHREAD to ON since pthreads are built into Android libc.
set(HAVE_PTHREAD ON CACHE BOOL "" FORCE)

add_subdirectory(libnfs)


# ── JNI wrapper shared library ──────────────────────────────────────────────
add_library(nfs_jni SHARED nfs_jni.c)
target_include_directories(nfs_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/include
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/include/nfsc
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/mount
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/nfs
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/nfs4
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/nlm
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/nsm
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/portmap
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/rquota
    ${CMAKE_CURRENT_BINARY_DIR}/libnfs   # for generated config.h
)

# Link the libnfs static library (target name is "nfs" from libnfs's CMake)
target_link_libraries(nfs_jni
    nfs       # libnfs static lib
    log       # Android __android_log_print
)
