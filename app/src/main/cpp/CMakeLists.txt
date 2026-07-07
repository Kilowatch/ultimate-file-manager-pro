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
    ${CMAKE_CURRENT_SOURCE_DIR}/libnfs/lib   # for libnfs-private.h (rpc struct access)
)

# Link the libnfs static library (target name is "nfs" from libnfs's CMake)
target_link_libraries(nfs_jni
    nfs       # libnfs static lib
    log       # Android __android_log_print
)


# ── FFmpeg JNI wrapper shared library ──────────────────────────────────────
if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    # Define the paths based on target ABI
    set(FFMPEG_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

    # Helper function to import FFmpeg shared libraries
    function(import_ffmpeg_lib name)
        add_library(${name} SHARED IMPORTED)
        set_target_properties(${name} PROPERTIES
            IMPORTED_LOCATION "${FFMPEG_LIBS_DIR}/lib${name}.so"
        )
    endfunction()

    import_ffmpeg_lib(avutil)
    import_ffmpeg_lib(avcodec)
    import_ffmpeg_lib(avformat)
    import_ffmpeg_lib(swscale)

    add_library(ffmpeg_jni SHARED ffmpeg_jni.c)

    target_include_directories(ffmpeg_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg_include
    )

    target_link_libraries(ffmpeg_jni
        avformat
        avcodec
        avutil
        swscale
        jnigraphics
        log
    )
endif()

