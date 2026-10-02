package com.injilkeselamatan.lifestreamingradio.extensions

import com.injilkeselamatan.lifestreamingradio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Status stasiun dari API now-playing AzuraCast. */
data class StationStatus(
    /** Backend stasiun menyala, jadi stream tersedia. */
    val isOnline: Boolean,
    /** Ada penyiar yang sedang siaran langsung (fitur streamer AzuraCast). */
    val isLive: Boolean,
    /** Baris "Artis - Judul" yang sedang diputar, kalau ada. */
    val songText: String?,
)

/** Fungsi murni supaya format API bisa diuji tanpa jaringan. */
fun parseStationStatus(json: String): StationStatus {
    val root = JSONObject(json)
    val song = root.optJSONObject("now_playing")?.optJSONObject("song")
    val songText = song
        ?.takeUnless { it.isNull("text") }
        ?.optString("text")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return StationStatus(
        isOnline = root.optBoolean("is_online", false),
        isLive = root.optJSONObject("live")?.optBoolean("is_live", false) ?: false,
        songText = songText,
    )
}

/**
 * @return status terbaru, atau null kalau API tidak terjangkau. null BUKAN berarti stasiun
 *   offline; hanya [StationStatus.isOnline] yang menyatakan itu.
 */
suspend fun fetchStationStatus(url: String = BuildConfig.NOWPLAYING_URL): StationStatus? =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    parseStationStatus(connection.inputStream.bufferedReader().use { it.readText() })
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

private const val TIMEOUT_MS = 5_000
