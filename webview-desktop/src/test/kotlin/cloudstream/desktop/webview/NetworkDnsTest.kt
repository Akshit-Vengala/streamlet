package cloudstream.desktop.webview

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkDnsTest {
    @Test fun `switching DNS keeps the shared client's cookie jar`() {
        val req = com.lagradost.nicehttp.Requests()
        SharedCookieJar.install(req)
        val jar = req.baseClient.cookieJar
        NetworkDns.apply(DohProvider.Cloudflare, req)
        assertSame(jar, req.baseClient.cookieJar)
        NetworkDns.apply(DohProvider.None, req)
        assertSame(okhttp3.Dns.SYSTEM, req.baseClient.dns)
    }

    /** Live: every provider answers (needs the network; prints instead of failing for one that is down). */
    @Test fun `providers resolve over https`() {
        val ok = DohProvider.entries.filter { it.url != null }.count { p ->
            val r = NetworkDns.test(p, "github.com")
            println("DoH ${p.label}: ${r.getOrNull() ?: r.exceptionOrNull()}")
            r.isSuccess
        }
        assertTrue(ok >= 5, "most DoH providers should resolve github.com ($ok did)")
    }
}
