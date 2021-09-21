package com.church.injilkeselamatan.radiostream.extensions

import com.google.android.exoplayer2.ControlDispatcher
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player

class RadioControlDispatcher : ControlDispatcher {
    override fun dispatchPrepare(player: Player) = false

    override fun dispatchSetPlayWhenReady(player: Player, playWhenReady: Boolean): Boolean {
        player.playWhenReady = playWhenReady
        return true
    }

    override fun dispatchSeekTo(player: Player, windowIndex: Int, positionMs: Long) = false

    override fun dispatchPrevious(player: Player) = false

    override fun dispatchNext(player: Player) = false

    override fun dispatchRewind(player: Player) = false

    override fun dispatchFastForward(player: Player) = false

    override fun dispatchSetRepeatMode(player: Player, repeatMode: Int) = true

    override fun dispatchSetShuffleModeEnabled(player: Player, shuffleModeEnabled: Boolean) =
        false

    override fun dispatchStop(player: Player, reset: Boolean): Boolean {
        player.stop()
        player.clearMediaItems()
        return true
    }

    override fun dispatchSetPlaybackParameters(
        player: Player,
        playbackParameters: PlaybackParameters
    ): Boolean {
        return true
    }

    override fun isRewindEnabled() = false

    override fun isFastForwardEnabled() = false
}