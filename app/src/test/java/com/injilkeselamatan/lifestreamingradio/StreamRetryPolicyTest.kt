package com.injilkeselamatan.lifestreamingradio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(UnstableApi::class)
class StreamRetryPolicyTest {

    @Test
    fun `jeda naik bertahap lalu tertahan di 10 detik`() {
        val delays = (1..7).map { StreamRetryPolicy.retryDelayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L), delays)
    }

    @Test
    fun `menyerah tepat setelah batas percobaan`() {
        assertEquals(10_000L, StreamRetryPolicy.retryDelayMs(StreamRetryPolicy.MAX_RETRIES))
        assertEquals(C.TIME_UNSET, StreamRetryPolicy.retryDelayMs(StreamRetryPolicy.MAX_RETRIES + 1))
    }

    @Test
    fun `seluruh retry bertahan hampir 10 menit, tidak lebih`() {
        // Panduan Android 17: FGS mediaPlayback bertahan selama gangguan < 10 menit.
        val totalMs = (1..StreamRetryPolicy.MAX_RETRIES).sumOf { StreamRetryPolicy.retryDelayMs(it) }
        assertTrue("total $totalMs ms", totalMs in 9 * 60_000L..10 * 60_000L)
    }

    @Test
    fun `error jaringan di-retry`() {
        assertEquals(1_000L, StreamRetryPolicy.retryDelayMsFor(UnknownHostException("a2.siar.us"), 1))
        assertEquals(2_000L, StreamRetryPolicy.retryDelayMsFor(SocketTimeoutException(), 2))
        assertEquals(4_000L, StreamRetryPolicy.retryDelayMsFor(IOException("reset"), 3))
    }

    @Test
    fun `bug loader tidak di-retry`() {
        val error = Loader.UnexpectedLoaderException(IllegalStateException("bug"))
        assertEquals(C.TIME_UNSET, StreamRetryPolicy.retryDelayMsFor(error, 1))
    }

    @Test
    fun `error tidak naik ke player sebelum batas retry habis`() {
        assertEquals(
            StreamRetryPolicy.MAX_RETRIES,
            StreamRetryPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE)
        )
    }
}
