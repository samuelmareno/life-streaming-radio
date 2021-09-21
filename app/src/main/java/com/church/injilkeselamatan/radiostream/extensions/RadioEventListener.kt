package com.church.injilkeselamatan.radiostream.extensions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.church.injilkeselamatan.radiostream.R
import com.church.injilkeselamatan.radiostream.RadioService
import com.church.injilkeselamatan.radiostream.extensions.Constants.CHANNEL_ERROR_ID
import com.church.injilkeselamatan.radiostream.extensions.Constants.CHANNEL_NAME
import com.church.injilkeselamatan.radiostream.extensions.Constants.NOTIFICATION_ERROR_ID
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player

class RadioEventListener(
    private val radioService: RadioService
) : Player.Listener {
    private val context = radioService.applicationContext

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        radioService.apply {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && isServiceInForeground) {
                stopForeground(false)
                isServiceInForeground = false
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE) {
                //radioService.playRadio()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Stopped from the server", Toast.LENGTH_SHORT).show()
                }
            }
            if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                radioService.playRadio()
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                showBufferingNotification()
            }
            Player.STATE_READY -> {
                NotificationManagerCompat.from(context).cancel(
                    NOTIFICATION_ERROR_ID
                )

            }
            Player.STATE_ENDED -> {
                radioService.playRadio()
            }
            else -> Unit
        }
    }

    private fun showBufferingNotification() {
        val notificationBuilder = NotificationCompat.Builder(
            context,
            CHANNEL_ERROR_ID
        )
        notificationBuilder.apply {
            setContentTitle("Connecting to server...")
            setContentText("Buffering")
            priority = NotificationManagerCompat.IMPORTANCE_LOW
            setSmallIcon(R.drawable.ic_radio)
            color = ContextCompat.getColor(context, R.color.colorPrimary)
            setColorized(true)
            setAutoCancel(false)
            setOngoing(true)
        }

        val notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val description = "Buffering State"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ERROR_ID, CHANNEL_NAME, importance)
            channel.description = description

            val notificationManager: NotificationManager =
                radioService.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ERROR_ID,
            notification
        )
    }
}