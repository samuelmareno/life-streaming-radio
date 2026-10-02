package com.injilkeselamatan.lifestreamingradio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.IOException

/**
 * Retry untuk stream radio live yang menjaga foreground service tetap hidup.
 *
 * Android 17 (background audio hardening) membisukan audio dari background kecuali app
 * menjalankan FGS, dan panduannya meminta FGS mediaPlayback bertahan selama gangguan
 * sementara di bawah 10 menit. Media3 menjaga FGS selama `playWhenReady` dan player masih
 * BUFFERING/READY; selama loader me-retry, player tetap BUFFERING. Dengan kebijakan bawaan,
 * error naik setelah ±15 detik, player jatuh ke IDLE, FGS dilepas, dan reconnect berikutnya
 * dari background tidak bersuara.
 */
@UnstableApi
class StreamRetryPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        retryDelayMsFor(loadErrorInfo.exception, loadErrorInfo.errorCount)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_RETRIES

    companion object {
        /** Jumlah percobaan sebelum menyerah: ±10 menit (lihat [retryDelayMs]). */
        const val MAX_RETRIES = 62
        private const val MAX_DELAY_MS = 10_000L

        /**
         * Semua error stream di-retry, termasuk kode HTTP (mount mati) dan data rusak (halaman
         * error alih-alih MP3), kecuali yang jelas bukan gangguan sementara.
         */
        fun retryDelayMsFor(error: IOException, errorCount: Int): Long {
            if (error is HttpDataSource.CleartextNotPermittedException ||
                error is Loader.UnexpectedLoaderException
            ) {
                // Salah konfigurasi atau bug: retry tidak akan menolong.
                return C.TIME_UNSET
            }
            return retryDelayMs(errorCount)
        }

        /**
         * Jeda sebelum percobaan ke-[errorCount]: 1, 2, 4, 8 detik, lalu tiap 10 detik.
         *
         * @return [C.TIME_UNSET] kalau batas percobaan sudah habis (error menjadi final).
         */
        fun retryDelayMs(errorCount: Int): Long {
            if (errorCount > MAX_RETRIES) return C.TIME_UNSET
            val doublings = (errorCount - 1).coerceIn(0, 4)
            return minOf(1_000L shl doublings, MAX_DELAY_MS)
        }
    }
}
