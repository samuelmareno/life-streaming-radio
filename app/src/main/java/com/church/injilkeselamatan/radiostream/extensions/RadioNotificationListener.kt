//package com.church.injilkeselamatan.radiostream.extensions
//
//import android.app.Notification
//import android.app.Service
//import android.content.Intent
//import android.content.pm.ServiceInfo
//import android.os.Build
//import androidx.annotation.OptIn
//import androidx.core.content.ContextCompat
//import androidx.media3.common.util.UnstableApi
//import androidx.media3.ui.PlayerNotificationManager
//import com.church.injilkeselamatan.radiostream.RadioService
//
//@OptIn(UnstableApi::class)
//class RadioNotificationListener(private val radioService: RadioService) :
//    PlayerNotificationManager.NotificationListener {
//    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
//        super.onNotificationCancelled(notificationId, dismissedByUser)
//        radioService.apply {
//            stopSelf()
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                stopForeground(Service.STOP_FOREGROUND_DETACH)
//            } else {
//                @Suppress("DEPRECATION")
//                stopForeground(false)
//            }
//            isServiceInForeground = false
//        }
//    }
//
//    override fun onNotificationPosted(
//        notificationId: Int,
//        notification: Notification,
//        ongoing: Boolean
//    ) {
//        super.onNotificationPosted(notificationId, notification, ongoing)
//
//        radioService.apply {
//            if (ongoing && !isServiceInForeground) {
//
//                val intent = Intent(this.applicationContext, this::class.java)
//                ContextCompat.startForegroundService(this, intent)
//                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
//                    startForeground(
//                        notificationId,
//                        notification,
//                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
//                    )
//                } else {
//                    startForeground(notificationId, notification)
//                }
//                isServiceInForeground = true
//            }
//        }
//    }
//}