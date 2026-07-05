/**
 * JNI bridge between Android/Kotlin and libnfs.
 *
 * Every JNI method receives the nfs context as a Java long (pointer).
 * The context is created by nfsInit() and must be destroyed with nfsDestroy().
 *
 * Package: za.kilowatch.ultimatefilemanager.network
 * Class:   LibNfsBridge
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <android/log.h>

#include "libnfs.h"
#include "libnfs-raw.h"
#include "libnfs-raw-nfs.h"
#include "libnfs-raw-mount.h"
// Note: libnfs-private.h intentionally NOT included — it redefines sockaddr_storage
// which clashes with NDK 28 headers. All needed declarations are in the public
// headers above (rpc_set_auth, rpc_set_timeout, libnfs_authunix_create, etc).



#define TAG "NFS_JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* ── Helpers ──────────────────────────────────────────────────────────────── */

static struct nfs_context *ctx_from_handle(jlong handle) {
    return (struct nfs_context *)(intptr_t)handle;
}

/* Safely access the rpc_context pointer from an nfs_context.
 * nfs_context->rpc is the FIRST field (offset 0), so we can extract it
 * via memcpy without including libnfs-private.h (which conflicts with NDK 28). */
static struct rpc_context *get_rpc_from_nfs(struct nfs_context *nfs) {
    struct rpc_context *rpc = NULL;
    if (nfs) {
        memcpy(&rpc, nfs, sizeof(rpc));
    }
    return rpc;
}

static void throw_io(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

/* ── Lifecycle ────────────────────────────────────────────────────────────── */

/*
 * long nfsInit()
 * Returns an opaque handle to an nfs_context, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsInit(
        JNIEnv *env, jclass clazz) {
    struct nfs_context *nfs = nfs_init_context();
    if (!nfs) {
        LOGE("nfs_init_context() returned NULL");
        return 0;
    }
    LOGI("nfs_init_context() OK  handle=%p", nfs);
    return (jlong)(intptr_t)nfs;
}

/*
 * void nfsDestroy(long handle)
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    if (nfs) {
        nfs_destroy_context(nfs);
        LOGI("nfs_destroy_context(%p)", nfs);
    }
}

/* ── Configuration ────────────────────────────────────────────────────────── */

/*
 * void nfsSetUid(long handle, int uid)
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetUid(
        JNIEnv *env, jclass clazz, jlong handle, jint uid) {
    nfs_set_uid(ctx_from_handle(handle), uid);
}

/*
 * void nfsSetGid(long handle, int gid)
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetGid(
        JNIEnv *env, jclass clazz, jlong handle, jint gid) {
    nfs_set_gid(ctx_from_handle(handle), gid);
}

/*
 * void nfsSetVersion(long handle, int version)
 * version: 3 for NFSv3, 4 for NFSv4 (default is v3)
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetVersion(
        JNIEnv *env, jclass clazz, jlong handle, jint version) {
    nfs_set_version(ctx_from_handle(handle), version);
}

/*
 * void nfsSetV4MinorVersion(long handle, int minorVersion)
 * Set NFSv4 minor version: 0 = v4.0, 1 = v4.1, 2 = v4.2
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetV4MinorVersion(
        JNIEnv *env, jclass clazz, jlong handle, jint minorVersion) {
    nfs_set_nfsv4_minor_version(ctx_from_handle(handle), minorVersion);
    LOGI("nfsSetV4MinorVersion: set to %d", minorVersion);
}

/*
 * void nfsSetAuthFlavor(long handle, int authFlavor, int uid, int gid)
 * Force the RPC authentication flavor to AUTH_SYS with explicit UNIX credentials.
 *   authFlavor: 1 = AUTH_SYS (AUTH_UNIX), 0 = AUTH_NONE
 *   uid/gid: passed explicitly to avoid needing libnfs-private.h struct access
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetAuthFlavor(
        JNIEnv *env, jclass clazz, jlong handle,
        jint authFlavor, jint uid, jint gid) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct rpc_context *rpc = get_rpc_from_nfs(nfs);
    if (!rpc) return;

    if (authFlavor == 1) {
        struct AUTH *auth = libnfs_authunix_create(
            "android-nfs", (uint32_t)uid, (uint32_t)gid, 0, NULL);
        if (auth) {
            rpc_set_auth(rpc, auth);
            LOGI("nfsSetAuthFlavor: set AUTH_SYS (uid=%d, gid=%d)", uid, gid);
        } else {
            LOGE("nfsSetAuthFlavor: libnfs_authunix_create returned NULL");
        }
    }
    // flavor 0 = AUTH_NONE (no-op, default stays as AUTH_UNIX)
}

/*
 * void nfsSetTimeout(long handle, int timeoutMs)
 * Override the default RPC reply timeout.
 * Default in libnfs is 60,000ms. We set 5,000ms for fast error surfacing.
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetTimeout(
        JNIEnv *env, jclass clazz, jlong handle, jint timeoutMs) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct rpc_context *rpc = get_rpc_from_nfs(nfs);
    if (rpc) {
        rpc_set_timeout(rpc, timeoutMs);
        LOGI("nfsSetTimeout: set to %dms", timeoutMs);
    }
}

/*
 * String nfsGetLastRpcError(long handle)
 * Returns the last RPC-level error string.
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsGetLastRpcError(
        JNIEnv *env, jclass clazz, jlong handle) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    if (!nfs) return (*env)->NewStringUTF(env, "null handle");
    return (*env)->NewStringUTF(env, nfs_get_error(nfs));
}

/*
 * void nfsSetDebug(long handle, int level)
 * Enable verbose RPC logging to logcat.
 *   0 = off, 1+ = verbose
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsSetDebug(
        JNIEnv *env, jclass clazz, jlong handle, jint level) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct rpc_context *rpc = get_rpc_from_nfs(nfs);
    if (rpc) {
        rpc_set_debug(rpc, level);
        LOGI("nfsSetDebug: set level=%d", level);
    }
}

/* ── Mount / Unmount ──────────────────────────────────────────────────────── */

/*
 * String nfsMount(long handle, String server, String exportPath)
 * Returns null on success, or an error string on failure.
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsMount(
        JNIEnv *env, jclass clazz, jlong handle,
        jstring jServer, jstring jExport) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *server = (*env)->GetStringUTFChars(env, jServer, NULL);
    const char *export_path = (*env)->GetStringUTFChars(env, jExport, NULL);

    LOGI("nfs_mount(%s, %s)", server, export_path);
    int ret = nfs_mount(nfs, server, export_path);

    (*env)->ReleaseStringUTFChars(env, jServer, server);
    (*env)->ReleaseStringUTFChars(env, jExport, export_path);

    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        const char *safe_err = (err && *err) ? err : "nfs_mount failed (no detail from libnfs)";
        LOGE("nfs_mount failed: %s (ret=%d)", safe_err, ret);
        return (*env)->NewStringUTF(env, safe_err);
    }
    return NULL; /* success */
}

/*
 * String nfsMountUrl(long handle, String url)
 * Mount using a full NFS URL (e.g. nfs://server/path?nfsport=2049&version=3)
 * Returns null on success, or an error string on failure.
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsMountUrl(
        JNIEnv *env, jclass clazz, jlong handle, jstring jUrl) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *url = (*env)->GetStringUTFChars(env, jUrl, NULL);

    /* nfs_parse_url_dir: parses the URL AND applies all query parameters
     * (version=, nfsport=, mountport=, uid=, gid=, timeo=, etc.) directly
     * to the nfs_context as a side-effect, while returning the FULL path
     * as the mount target. nfs_parse_url_full must NOT be used here — it
     * treats the last path segment as a "file" component and strips it,
     * turning e.g. /srv/nfs/testshare into /srv/nfs at the mount layer. */
    struct nfs_url *nfs_url = nfs_parse_url_dir(nfs, url);
    (*env)->ReleaseStringUTFChars(env, jUrl, url);

    if (!nfs_url) {
        const char *err = nfs_get_error(nfs);
        const char *safe_err = (err && *err) ? err : "nfs_parse_url_dir failed (bad URL or rejected parameter)";
        LOGE("nfs_parse_url_dir failed: %s", safe_err);
        return (*env)->NewStringUTF(env, safe_err);
    }

    LOGI("nfs_mount via URL: server=%s path=%s (nfs version=%d.%d)",
         nfs_url->server, nfs_url->path, nfs_get_version(nfs),
         nfs_get_nfsv4_minor_version(nfs));
    int ret = nfs_mount(nfs, nfs_url->server, nfs_url->path);
    nfs_destroy_url(nfs_url);

    if (ret != 0) {
        const char *nfs_err = nfs_get_error(nfs);
        /* nfs_mount() ends with nfs_set_error(nfs, rpc_get_error(rpc)).
         * If the rpc error was empty, nfs_err is also empty. Log both for
         * diagnosis. */
        if (nfs_err && *nfs_err) {
            LOGE("nfs_mount (URL) failed: %s (ret=%d)", nfs_err, ret);
            return (*env)->NewStringUTF(env, nfs_err);
        }
        /* libnfs didn't populate its error string — capture errno directly */
        char fallback[256];
        snprintf(fallback, sizeof(fallback),
                 "nfs_mount failed: ret=%d errno=%d (%s)", ret, errno, strerror(errno));
        LOGE("%s", fallback);
        return (*env)->NewStringUTF(env, fallback);
    }
    return NULL;
}

/* ── Directory Listing ────────────────────────────────────────────────────── */

/*
 * String[] nfsListDir(long handle, String path)
 * Returns an array of "name\ttype\tsize\tmtime" strings.
 *   type: 'f' for file, 'd' for directory, 'l' for symlink
 */
JNIEXPORT jobjectArray JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsListDir(
        JNIEnv *env, jclass clazz, jlong handle, jstring jPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);

    struct nfsdir *dir = NULL;
    int ret = nfs_opendir(nfs, path, &dir);
    (*env)->ReleaseStringUTFChars(env, jPath, path);

    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        LOGE("nfs_opendir failed: %s", err);
        throw_io(env, err);
        return NULL;
    }

    /* First pass: count entries */
    int count = 0;
    struct nfsdirent *entry;
    while ((entry = nfs_readdir(nfs, dir)) != NULL) {
        if (strcmp(entry->name, ".") != 0 && strcmp(entry->name, "..") != 0)
            count++;
    }
    nfs_seekdir(nfs, dir, 0);

    /* Second pass: build the array */
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, strCls, NULL);
    int i = 0;
    char buf[1024];
    while ((entry = nfs_readdir(nfs, dir)) != NULL) {
        if (strcmp(entry->name, ".") == 0 || strcmp(entry->name, "..") == 0)
            continue;
        char type = 'f';
        if (entry->type == NF3DIR) type = 'd';
        else if (entry->type == NF3LNK) type = 'l';

        snprintf(buf, sizeof(buf), "%s\t%c\t%llu\t%llu",
                 entry->name, type,
                 (unsigned long long)entry->size,
                 (unsigned long long)entry->mtime.tv_sec);
        (*env)->SetObjectArrayElement(env, result, i++,
                                      (*env)->NewStringUTF(env, buf));
    }
    nfs_closedir(nfs, dir);
    return result;
}

/* ── File Stats ───────────────────────────────────────────────────────────── */

/*
 * long nfsFileSize(long handle, String path)
 * Returns -1 on error.
 */
JNIEXPORT jlong JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsFileSize(
        JNIEnv *env, jclass clazz, jlong handle, jstring jPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);

    struct nfs_stat_64 st;
    int ret = nfs_stat64(nfs, path, &st);
    (*env)->ReleaseStringUTFChars(env, jPath, path);

    if (ret != 0) {
        LOGE("nfs_stat64 failed: %s", nfs_get_error(nfs));
        return -1;
    }
    return (jlong)st.nfs_size;
}

/* ── File I/O ─────────────────────────────────────────────────────────────── */

/*
 * long nfsOpen(long handle, String path, int flags)
 * flags: 0 = O_RDONLY, 1 = O_WRONLY|O_CREAT, 2 = O_RDWR
 * Returns nfsfh handle as long, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsOpen(
        JNIEnv *env, jclass clazz, jlong handle,
        jstring jPath, jint flags) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);

    struct nfsfh *fh = NULL;
    int mode = O_RDONLY;
    if (flags == 1) mode = O_WRONLY | O_CREAT;
    else if (flags == 2) mode = O_RDWR;

    int ret = nfs_open(nfs, path, mode, &fh);
    (*env)->ReleaseStringUTFChars(env, jPath, path);

    if (ret != 0) {
        LOGE("nfs_open failed: %s", nfs_get_error(nfs));
        throw_io(env, nfs_get_error(nfs));
        return 0;
    }
    return (jlong)(intptr_t)fh;
}

/*
 * int nfsRead(long handle, long fhHandle, byte[] buffer, int offset, int length)
 * Returns bytes read, or -1 on EOF/error.
 */
JNIEXPORT jint JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsRead(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle,
        jbyteArray jBuf, jint offset, jint length) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;

    char *tmp = malloc(length);
    if (!tmp) {
        throw_io(env, "malloc failed");
        return -1;
    }
    int ret = nfs_read(nfs, fh, tmp, length);
    if (ret > 0) {
        (*env)->SetByteArrayRegion(env, jBuf, offset, ret, (jbyte *)tmp);
    }
    free(tmp);
    if (ret < 0) {
        LOGE("nfs_read failed: %s", nfs_get_error(nfs));
    }
    return ret;
}

/*
 * int nfsPread(long handle, long fhHandle, long fileOffset, byte[] buffer, int bufOffset, int length)
 */
JNIEXPORT jint JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsPread(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle,
        jlong fileOffset, jbyteArray jBuf, jint bufOffset, jint length) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;

    char *tmp = malloc(length);
    if (!tmp) {
        throw_io(env, "malloc failed");
        return -1;
    }
    int ret = nfs_pread(nfs, fh, tmp, length, fileOffset);
    if (ret > 0) {
        (*env)->SetByteArrayRegion(env, jBuf, bufOffset, ret, (jbyte *)tmp);
    }
    free(tmp);
    if (ret < 0) {
        LOGE("nfs_pread failed: %s", nfs_get_error(nfs));
    }
    return ret;
}

/*
 * int nfsWrite(long handle, long fhHandle, byte[] buffer, int offset, int length)
 */
JNIEXPORT jint JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsWrite(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle,
        jbyteArray jBuf, jint offset, jint length) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;

    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    int ret = nfs_write(nfs, fh, (const void *)(buf + offset), length);
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, JNI_ABORT);

    if (ret < 0) {
        LOGE("nfs_write failed: %s", nfs_get_error(nfs));
    }
    return ret;
}

/*
 * int nfsPwrite(long handle, long fhHandle, long fileOffset, byte[] buffer, int bufOffset, int length)
 */
JNIEXPORT jint JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsPwrite(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle,
        jlong fileOffset, jbyteArray jBuf, jint bufOffset, jint length) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;

    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    int ret = nfs_pwrite(nfs, fh, (const void *)(buf + bufOffset), length, fileOffset);
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, JNI_ABORT);

    if (ret < 0) {
        LOGE("nfs_pwrite failed: %s", nfs_get_error(nfs));
    }
    return ret;
}

/*
 * void nfsLseek(long handle, long fhHandle, long offset, int whence)
 * whence: 0 = SEEK_SET, 1 = SEEK_CUR, 2 = SEEK_END
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsLseek(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle,
        jlong offset, jint whence) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;
    uint64_t current_offset;
    nfs_lseek(nfs, fh, offset, whence, &current_offset);
}

/*
 * void nfsClose(long handle, long fhHandle)
 */
JNIEXPORT void JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsClose(
        JNIEnv *env, jclass clazz, jlong handle, jlong fhHandle) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    struct nfsfh *fh = (struct nfsfh *)(intptr_t)fhHandle;
    if (fh) nfs_close(nfs, fh);
}

/* ── Directory Operations ─────────────────────────────────────────────────── */

/*
 * String nfsMkdir(long handle, String path)
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsMkdir(
        JNIEnv *env, jclass clazz, jlong handle, jstring jPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int ret = nfs_mkdir(nfs, path);
    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        LOGE("nfs_mkdir failed: %s (ret=%d) path=%s", err, ret, path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return (*env)->NewStringUTF(env, err);
    }
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return NULL;
}

/*
 * String nfsRmdir(long handle, String path)
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsRmdir(
        JNIEnv *env, jclass clazz, jlong handle, jstring jPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int ret = nfs_rmdir(nfs, path);
    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        LOGE("nfs_rmdir failed: %s (ret=%d) path=%s", err, ret, path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return (*env)->NewStringUTF(env, err);
    }
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return NULL;
}

/*
 * String nfsUnlink(long handle, String path)
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsUnlink(
        JNIEnv *env, jclass clazz, jlong handle, jstring jPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    int ret = nfs_unlink(nfs, path);
    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        LOGE("nfs_unlink failed: %s (ret=%d) path=%s", err, ret, path);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return (*env)->NewStringUTF(env, err);
    }
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return NULL;
}

/*
 * String nfsRename(long handle, String oldPath, String newPath)
 */
JNIEXPORT jstring JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsRename(
        JNIEnv *env, jclass clazz, jlong handle,
        jstring jOldPath, jstring jNewPath) {
    struct nfs_context *nfs = ctx_from_handle(handle);
    const char *old_p = (*env)->GetStringUTFChars(env, jOldPath, NULL);
    const char *new_p = (*env)->GetStringUTFChars(env, jNewPath, NULL);
    int ret = nfs_rename(nfs, old_p, new_p);
    if (ret != 0) {
        const char *err = nfs_get_error(nfs);
        LOGE("nfs_rename failed: %s (ret=%d) old=%s new=%s", err, ret, old_p, new_p);
        (*env)->ReleaseStringUTFChars(env, jOldPath, old_p);
        (*env)->ReleaseStringUTFChars(env, jNewPath, new_p);
        return (*env)->NewStringUTF(env, err);
    }
    (*env)->ReleaseStringUTFChars(env, jOldPath, old_p);
    (*env)->ReleaseStringUTFChars(env, jNewPath, new_p);
    return NULL;
}

/* ── Export Listing ───────────────────────────────────────────────────────── */

/*
 * String[] nfsListExports(String server)
 * Returns a list of available exports on the server.
 */
JNIEXPORT jobjectArray JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsListExports(
        JNIEnv *env, jclass clazz, jstring jServer) {
    const char *server = (*env)->GetStringUTFChars(env, jServer, NULL);

    LOGI("nfsListExports: querying exports from %s ...", server);
    struct exportnode *exports = mount_getexports(server);
    (*env)->ReleaseStringUTFChars(env, jServer, server);

    if (!exports) {
        LOGW("nfsListExports: mount_getexports(%s) returned NULL", server);
        return NULL;
    }

    /* Count */
    int count = 0;
    struct exportnode *e = exports;
    while (e) { count++; e = e->ex_next; }
    LOGI("nfsListExports(%s): %d export(s) found", server, count);

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, strCls, NULL);
    e = exports;
    int i = 0;
    while (e) {
        LOGI("nfsListExports(%s): export[%d] = \"%s\"", server, i, e->ex_dir);
        (*env)->SetObjectArrayElement(env, result, i++,
                                      (*env)->NewStringUTF(env, e->ex_dir));
        e = e->ex_next;
    }
    mount_free_export_list(exports);
    LOGI("nfsListExports(%s): returning %d exports OK", server, count);
    return result;
}

/*
 * String[] nfsFindLocalServers()
 * Broadcast-discover NFS servers on the local network.
 * Returns an array of IP address strings, or NULL if none found / error.
 */
JNIEXPORT jobjectArray JNICALL
Java_za_kilowatch_ultimatefilemanager_network_LibNfsBridge_nfsFindLocalServers(
        JNIEnv *env, jclass clazz) {
    LOGI("nfsFindLocalServers: calling nfs_find_local_servers() ...");
    struct nfs_server_list *srvrs = nfs_find_local_servers();
    if (!srvrs) {
        LOGW("nfsFindLocalServers: nfs_find_local_servers returned NULL (no servers or timeout)");
        return NULL;
    }
    LOGI("nfsFindLocalServers: nfs_find_local_servers returned non-NULL, counting...");

    /* Count */
    int count = 0;
    struct nfs_server_list *s = srvrs;
    while (s) { count++; s = s->next; }
    LOGI("nfsFindLocalServers: %d server(s) found via broadcast", count);

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, strCls, NULL);
    s = srvrs;
    int i = 0;
    while (s) {
        LOGI("nfsFindLocalServers: server[%d] = %s", i, s->addr);
        (*env)->SetObjectArrayElement(env, result, i++,
                                      (*env)->NewStringUTF(env, s->addr));
        s = s->next;
    }
    free_nfs_srvr_list(srvrs);
    LOGI("nfsFindLocalServers: returning %d servers OK", count);
    return result;
}
