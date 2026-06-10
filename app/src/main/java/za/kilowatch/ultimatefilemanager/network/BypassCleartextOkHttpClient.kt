package za.kilowatch.ultimatefilemanager.network

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetAddress

/**
 * Helper to build an OkHttpClient that bypasses Android's cleartext HTTP policy
 * restrictions for local/private IP addresses using the localhost DNS override trick.
 */
object BypassCleartextOkHttpClient {

    private val targetIpThreadLocal = ThreadLocal<String>()

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "localhost") {
                val overrideIp = targetIpThreadLocal.get()
                if (overrideIp != null) {
                    try {
                        return listOf(InetAddress.getByName(overrideIp))
                    } catch (_: Exception) {
                        // fallback
                    }
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!req.url.isHttps) {
            targetIpThreadLocal.set(req.url.host)
            try {
                val originalHost = req.url.host + if (req.url.port != 80 && req.url.port != 443) ":${req.url.port}" else ""
                val newUrl = req.url.newBuilder().host("localhost").build()
                val newReq = req.newBuilder()
                    .url(newUrl)
                    .header("Host", req.header("host") ?: req.header("Host") ?: originalHost)
                    .build()
                return@Interceptor chain.proceed(newReq)
            } finally {
                targetIpThreadLocal.remove()
            }
        }
        chain.proceed(req)
    }

    fun applyBypass(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder
            .dns(dns)
            .addInterceptor(interceptor)
    }
}
