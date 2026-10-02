package com.injilkeselamatan.lifestreamingradio

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.injilkeselamatan.lifestreamingradio.extensions.Constants
import com.injilkeselamatan.lifestreamingradio.extensions.parseIcyTrack

/**
 * Player yang dilihat MediaSession (notifikasi, MainActivity, Bluetooth, tombol media, Cast UI).
 *
 * Merapikan state player di satu tempat:
 * - metadata ICY "Artis - Judul" dipecah ke field title/artist;
 * - saat casting, judul lagu diambil dari [setRemoteSongText], karena receiver tidak
 *   meneruskan ICY;
 * - play setelah pause selalu kembali ke siaran live.
 *
 * Karena turunan [ForwardingSimpleBasePlayer], event ke controller dihitung dari state yang
 * sudah dirapikan. Controller mana pun (termasuk MainActivity) menerima metadata yang SUDAH
 * terpecah dan tidak boleh memecahnya lagi.
 */
@UnstableApi
class RadioSessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    private var remoteSongText: String? = null

    /** Teks "Artis - Judul" dari API stasiun selama casting; null saat memutar lokal. */
    fun setRemoteSongText(text: String?) {
        if (text == remoteSongText) return
        remoteSongText = text
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        if (state.timeline.isEmpty) return state
        val isRemote = state.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        val songText = if (isRemote) remoteSongText else state.currentMetadata.title?.toString()
        val cleaned = cleanMetadata(state.currentMetadata, songText)
        if (cleaned == state.currentMetadata) return state
        return state.buildUpon()
            .setPlaylist(state.timeline, state.currentTracks, cleaned)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val player = getPlayer()
        val resumingLocalAfterPause = playWhenReady &&
            !player.playWhenReady &&
            player.playbackState == Player.STATE_READY &&
            player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL
        if (resumingLocalAfterPause) {
            // Radio harus live: buang buffer yang tertinggal sejak pause. Pada stream
            // progresif live, seek ke posisi default berarti menyambung ulang dari awal stream,
            // alias siaran saat ini.
            player.seekToDefaultPosition()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    private fun cleanMetadata(current: MediaMetadata, songText: String?): MediaMetadata {
        val track = parseIcyTrack(songText)
        return when {
            track != null ->
                current.buildUpon().setTitle(track.title).setArtist(track.artist).build()
            // Teks dari API stasiun tanpa pemisah " - ": tampilkan apa adanya.
            !songText.isNullOrBlank() && songText != current.title?.toString() ->
                current.buildUpon().setTitle(songText).setArtist(Constants.SUBTITLE).build()
            // Belum ada judul dari stream: tampilkan nama stasiun, bukan kosong.
            current.title.isNullOrBlank() ->
                current.buildUpon().setTitle(Constants.TITLE).build()
            else -> current
        }
    }
}
