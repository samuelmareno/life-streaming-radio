package com.church.injilkeselamatan.radiostream.extensions

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import com.church.injilkeselamatan.radiostream.RadioService
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class RadioNotificationListener(private val radioService: RadioService) :
    PlayerNotificationManager.NotificationListener {
    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
        super.onNotificationCancelled(notificationId, dismissedByUser)
        radioService.apply {
            stopSelf()
            stopForeground(true)
            isServiceInForeground = false
        }
    }

    override fun onNotificationPosted(
        notificationId: Int,
        notification: Notification,
        ongoing: Boolean
    ) {
        super.onNotificationPosted(notificationId, notification, ongoing)

        radioService.apply {
            if (ongoing && !isServiceInForeground) {

                val intent = Intent(this.applicationContext, this::class.java)
                ContextCompat.startForegroundService(this, intent)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(
                        notificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(notificationId, notification)
                }
                isServiceInForeground = true
            }
        }
    }
}