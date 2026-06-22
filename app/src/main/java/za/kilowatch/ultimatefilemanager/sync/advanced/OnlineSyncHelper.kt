package za.kilowatch.ultimatefilemanager.sync.advanced

import android.util.Log
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import java.io.InputStream
import java.io.OutputStream

/**
 * Reflection-based helper to call flavor-specific online storage share clients
 * (GoogleDriveShareClient, DropboxShareClient, OnedriveShareClient) from the main source set.
 *
 * These classes are in `nonfoss`/`foss`/`google`/`amazon` source sets and can't be
 * imported directly from `main`. They ARE available at runtime on the correct flavor.
 *
 * Handles both regular and suspend functions via reflection with Continuation.
 */
object OnlineSyncHelper {

    private const val TAG = "OnlineSyncHelper"

    /** Try to list files using an online storage client via reflection (suspend function). */
    suspend fun tryListFiles(share: NetworkShare, remotePath: String): List<NetworkFile>? {
        val (clazz, instance) = resolveClient(share) ?: return null
        return try {
            val method = clazz.getMethod("listFiles", NetworkShare::class.java, String::class.java, Continuation::class.java)
            callSuspend<List<NetworkFile>>(instance, method, share, remotePath)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "listFiles not found on ${clazz.simpleName}, trying non-suspend", e)
            try {
                val method = clazz.getMethod("listFiles", NetworkShare::class.java, String::class.java)
                @Suppress("UNCHECKED_CAST")
                withContext(Dispatchers.IO) {
                    method.invoke(instance, share, remotePath) as? List<NetworkFile>
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Non-suspend listFiles also failed", e2)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reflection listFiles failed for ${share.type}", e)
            null
        }
    }

    /** Try to mkdir using an online storage client via reflection. */
    suspend fun tryMkdir(share: NetworkShare, remotePath: String) {
        val (clazz, instance) = resolveClient(share) ?: return
        try {
            val method = clazz.getMethod("mkdir", NetworkShare::class.java, String::class.java, Continuation::class.java)
            callSuspend<Unit>(instance, method, share, remotePath)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "mkdir not found on ${clazz.simpleName}, skipping", e)
        } catch (e: Exception) {
            Log.w(TAG, "Reflection mkdir failed for ${share.type}", e)
        }
    }

    /** Try to delete a file using an online storage client via reflection. */
    suspend fun tryDeleteFile(share: NetworkShare, remotePath: String) {
        val (clazz, instance) = resolveClient(share) ?: return
        try {
            val method = clazz.getMethod("deleteFile", NetworkShare::class.java, String::class.java, Continuation::class.java)
            callSuspend<Unit>(instance, method, share, remotePath)
            Log.d(TAG, "Reflection deleteFile succeeded for ${share.type}")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "deleteFile not found on ${clazz.simpleName}", e)
        } catch (e: Exception) {
            Log.w(TAG, "Reflection deleteFile failed for ${share.type}", e)
        }
    }

    /** Try to open an input stream using an online storage client via reflection. */
    suspend fun tryOpenInputStream(share: NetworkShare, remotePath: String): InputStream? {
        val (clazz, instance) = resolveClient(share) ?: return null
        return try {
            val method = clazz.getMethod("openInputStream", NetworkShare::class.java, String::class.java, Continuation::class.java)
            val result = callSuspend<Any>(instance, method, share, remotePath)
            if (result is Pair<*, *>) result.first as? InputStream
            else result as? InputStream
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "openInputStream not found on ${clazz.simpleName}", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Reflection openInputStream failed for ${share.type}", e)
            null
        }
    }

    /** Try to open an output stream using an online storage client via reflection. */
    suspend fun tryOpenOutputStream(share: NetworkShare, remotePath: String): OutputStream? {
        val (clazz, instance) = resolveClient(share) ?: return null
        return try {
            val method = clazz.getMethod("openOutputStream", NetworkShare::class.java, String::class.java, Continuation::class.java)
            callSuspend<OutputStream>(instance, method, share, remotePath)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "openOutputStream not found on ${clazz.simpleName}", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Reflection openOutputStream failed for ${share.type}", e)
            null
        }
    }

    /**
     * Call a Kotlin suspend function via reflection.
     * Uses suspendCoroutine to create the continuation.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> callSuspend(instance: Any, method: java.lang.reflect.Method, vararg args: Any?): T =
        kotlin.coroutines.suspendCoroutine { cont ->
            try {
                val result = method.invoke(instance, *args, cont)
                if (result !== COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }

    /** Map share type to the client class and singleton instance. */
    private fun resolveClient(share: NetworkShare): Pair<Class<*>, Any>? {
        val className = when (share.type) {
            ShareType.GOOGLE_DRIVE -> "za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient"
            ShareType.DROPBOX -> "za.kilowatch.ultimatefilemanager.network.DropboxShareClient"
            ShareType.ONEDRIVE -> "za.kilowatch.ultimatefilemanager.network.OnedriveShareClient"
            else -> return null
        }
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getField("INSTANCE").get(null)
            Pair(clazz, instance)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve $className (flavor doesn't include it)")
            null
        }
    }
}
