package com.church.injilkeselamatan.radiostream.extensions

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.church.injilkeselamatan.radiostream.MainActivity
import com.church.injilkeselamatan.radiostream.R
import com.church.injilkeselamatan.radiostream.RadioService
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import javax.inject.Inject


class RadioDescriptionAdapter(
    private val radioService: RadioService
) : PlayerNotificationManager.MediaDescriptionAdapter {

    override fun getCurrentContentTitle(player: Player) = Constants.TITLE

    override fun createCurrentContentIntent(player: Player): PendingIntent {
        val intent = Intent(radioService, MainActivity::class.java)
        intent.putExtra("foo_bar_extra_key", "foo_bar_extra_value")
        intent.action = System.currentTimeMillis().toString()
        return PendingIntent.getActivity(radioService, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun getCurrentContentText(player: Player) = Constants.SUBTITLE

    override fun getCurrentLargeIcon(
        player: Player,
        callback: PlayerNotificationManager.BitmapCallback
    ): Bitmap? {
        return BitmapFactory.decodeResource(
            radioService.applicationContext.resources,
            R.drawable.notif
        )
    }
}