package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.network.HttpException
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CheckSourceHealthTest {

    private val check = CheckSourceHealth(mockk(relaxed = true))

    @Test
    fun `403 and 503 are a Cloudflare check`() {
        assertEquals(SourceHealth.Cloudflare, check.classify(HttpException(403)))
        assertEquals(SourceHealth.Cloudflare, check.classify(HttpException(503)))
    }

    @Test
    fun `a wrapped Cloudflare bypass failure is a Cloudflare check`() {
        val e = IOException("Failed to bypass Cloudflare", IllegalStateException())
        assertEquals(SourceHealth.Cloudflare, check.classify(e))
    }

    @Test
    fun `server errors and network failures are Down`() {
        assertEquals(SourceHealth.Down, check.classify(HttpException(502)))
        assertEquals(SourceHealth.Down, check.classify(UnknownHostException("nope")))
        assertEquals(SourceHealth.Down, check.classify(SocketTimeoutException()))
    }

    @Test
    fun `a 404 or an unexpected page is Blocked`() {
        assertEquals(SourceHealth.Blocked, check.classify(HttpException(404)))
        assertEquals(SourceHealth.Blocked, check.classify(IOException("needs a browser check")))
        assertEquals(SourceHealth.Blocked, check.classify(IllegalStateException("parse")))
    }
}
