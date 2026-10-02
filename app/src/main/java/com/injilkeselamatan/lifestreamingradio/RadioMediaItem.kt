package com.injilkeselamatan.lifestreamingradio

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.injilkeselamatan.lifestreamingradio.extensions.Constants

/**
 * Satu-satunya tempat MediaItem radio dibuat, supaya pemutar lokal, Cast, dan playback
 * resumption memakai konfigurasi yang sama.
 */
object RadioMediaItem {

    /**
     * Sengaja TIDAK sama dengan [MediaItem.LiveConfiguration.UNSET]: konverter Cast hanya
     * mengirim STREAM_TYPE_LIVE ke receiver kalau konfigurasinya bukan UNSET, sedangkan
     * `LiveConfiguration.Builder().build()` identik dengan UNSET sehingga TV menganggap radio
     * sebagai file yang bisa di-seek. Kecepatan dikunci 1x; stream progresif tidak punya
     * jendela live untuk dikejar, jadi pemutaran lokal tidak berubah.
     */
    private val LIVE_CONFIGURATION = MediaItem.LiveConfiguration.Builder()
        .setMinPlaybackSpeed(1f)
        .setMaxPlaybackSpeed(1f)
        .build()

    /**
     * @param forCast true untuk item yang dikirim ke receiver Cast. Item lokal sengaja tanpa
     *   judul: ExoPlayer memprioritaskan metadata MediaItem di atas metadata ICY dari stream,
     *   sehingga judul statis akan menutupi judul lagu. Receiver Cast tidak membaca ICY, jadi
     *   di sana nama stasiun yang dipakai sebagai judul.
     */
    fun create(context: Context, forCast: Boolean = false): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setArtist(Constants.SUBTITLE)
            .setArtworkUri("android.resource://${context.packageName}/${R.drawable.notif}".toUri())
            // Tanpa media type, receiver Cast memperlakukannya sebagai film.
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { if (forCast) setTitle(Constants.TITLE) }
            .build()

        return MediaItem.Builder()
            .setUri(BuildConfig.RADIO_URL)
            .setMediaId(Constants.MEDIA_ID)
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setLiveConfiguration(LIVE_CONFIGURATION)
            .setMediaMetadata(metadata)
            .build()
    }
}
