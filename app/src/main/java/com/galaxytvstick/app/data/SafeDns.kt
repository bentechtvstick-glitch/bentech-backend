package com.galaxytvstick.app.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS prive (DNS-over-HTTPS) pou app la: kèk routeur (egz: Xfinity "Advanced Security") reponn ak yon
 * paj "Potential Threat Detected" olye adrès vre sèvè IPTV a. Lè app la mande adrès la bay Cloudflare
 * oswa Google an HTTPS, routeur a pa ka chanje repons lan. Si DNS prive a pa reponn, nou sèvi ak DNS nòmal la.
 */
object SafeDns : Dns {
    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val resolvers: List<Dns> by lazy {
        listOf(
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://1.1.1.1/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                .includeIPv6(false)
                .build(),
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
                .includeIPv6(false)
                .build()
        )
    }

    private class Entry(val addrs: List<InetAddress>, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 10 * 60 * 1000L

    private val ipLiteral = Regex("^[0-9.]+$|:")

    override fun lookup(hostname: String): List<InetAddress> {
        if (ipLiteral.containsMatchIn(hostname)) return Dns.SYSTEM.lookup(hostname)
        cache[hostname]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.addrs }
        for (r in resolvers) {
            val found = runCatching { r.lookup(hostname) }.getOrNull()
            if (!found.isNullOrEmpty()) {
                cache[hostname] = Entry(found, System.currentTimeMillis())
                return found
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}
