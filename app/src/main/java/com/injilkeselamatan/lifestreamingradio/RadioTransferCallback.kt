package com.injilkeselamatan.lifestreamingradio

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi

/**
 * Memindahkan playback antara HP dan TV.
 *
 * Callback bawaan menyalin MediaItem apa adanya, padahal item lokal sengaja tanpa judul (lihat
 * [RadioMediaItem.create]). Di sini receiver Cast mendapat item berjudul nama stasiun, dan
 * posisi selalu default karena siaran live tidak punya posisi untuk dilanjutkan.
 * `CastPlayer` sendiri yang memanggil prepare() pada pemutar tujuan.
 */
@UnstableApi
class RadioTransferCallback(private val context: Context) : CastPlayer.TransferCallback {

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        PlayerTransferState.builderFromPlayer(sourcePlayer)
            .setMediaItems(
                listOf(RadioMediaItem.create(context, forCast = targetPlayer is RemoteCastPlayer))
            )
            .setCurrentMediaItemIndex(0)
            .setCurrentPosition(C.TIME_UNSET)
            .build()
            .setToPlayer(targetPlayer)
    }
}
