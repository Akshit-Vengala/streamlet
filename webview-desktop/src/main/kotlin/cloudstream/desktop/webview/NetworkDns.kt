package cloudstream.desktop.webview

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * CloudStream's "DNS over HTTPS" setting (app/network/DohProviders.kt + RequestsHelper.buildDefaultClient): names are
 * looked up through one of these resolvers over HTTPS instead of the system / ISP DNS, which gets past DNS-based
 * blocking of sites. Applies to the shared NiceHttp client (`app`) that extensions, their extractors and HLS
 * downloads use. Same providers, addresses and order as CloudStream (OpenDNS is left out there too: it doesn't work).
 */
enum class DohProvider(val label: String, val url: String?, val bootstrap: List<String>) {
    None("Off (system DNS)", null, emptyList()),
    Google("Google", "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
    Cloudflare("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    AdGuard("AdGuard", "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
    DnsWatch("DNS.Watch", "https://resolver2.dns.watch/dns-query", listOf("84.200.69.80", "84.200.70.40")),
    Quad9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    DnsSb("DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
    CanadianShield("Canadian Shield", "https://private.canadianshield.cira.ca/dns-query", listOf("149.112.121.10", "149.112.122.10"));

    /** The resolver, built on [client] (its own lookups go to the bootstrap addresses, never through DNS). */
    fun dns(client: OkHttpClient): Dns = if (url == null) Dns.SYSTEM else DnsOverHttps.Builder()
        .client(client)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
        .build()
}

object NetworkDns {
    @Volatile var current: DohProvider = DohProvider.None
        private set

    /** Switches the shared client (`app.baseClient`) to [p]; cookies, timeouts and interceptors are kept. */
    fun apply(p: DohProvider, requests: com.lagradost.nicehttp.Requests = com.lagradost.cloudstream3.app) {
        val base = requests.baseClient
        // The DoH client itself must resolve with the system DNS (bootstrap hosts cover its own server).
        val plain = base.newBuilder().dns(Dns.SYSTEM).build()
        requests.baseClient = plain.newBuilder().dns(p.dns(plain)).build()
        current = p
    }

    /** Looks [host] up with [p] (Settings → "Test"); the addresses, or the error. */
    fun test(p: DohProvider, host: String): Result<List<String>> = runCatching {
        p.dns(OkHttpClient()).lookup(host).map { it.hostAddress }
    }
}
