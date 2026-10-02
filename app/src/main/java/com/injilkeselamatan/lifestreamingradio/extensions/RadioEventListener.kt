package com.injilkeselamatan.lifestreamingradio.extensions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.injilkeselamatan.lifestreamingradio.R
import com.injilkeselamatan.lifestreamingradio.extensions.Constants.CHANNEL_ERROR_ID
import com.injilkeselamatan.lifestreamingradio.extensions.Constants.CHANNEL_NAME
import com.injilkeselamatan.lifestreamingradio.extensions.Constants.NOTIFICATION_ERROR_ID

/**
 * Notifikasi "menyambung ke server" dan reconnect saat server menutup stream.
 *
 * Status foreground service sengaja tidak diatur di sini: Media3 MediaSessionService yang
 * mengelolanya, dan campur tangan manual bisa bertabrakan dengan aturan Android 17.
 */
class RadioEventListener(
    private val context: Context,
    private val reconnectToLive: () -> Unit,
) : Player.Listener {

    companion object {
        private const val TAG = "RadioEventListener"
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> showBufferingNotification()
            Player.STATE_READY -> cancelBufferingNotification()
            // Server menutup stream. Sambung ulang selagi FGS masih aktif.
            Player.STATE_ENDED -> reconnectToLive()
            else -> Log.d(TAG, "onPlaybackStateChanged: $state")
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) cancelBufferingNotification()
    }

    override fun onPlayerError(error: PlaybackException) {
        // Error final (batas retry habis): jangan tinggalkan notifikasi "menyambung".
        Log.w(TAG, "Playback berhenti: ${error.errorCodeName}", error)
        cancelBufferingNotification()
    }

    private fun cancelBufferingNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ERROR_ID)
    }

    private fun showBufferingNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR_ID)
            .setContentTitle("Connecting to server...")
            .setContentText("Buffering")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_radio)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .setColorized(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ERROR_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Buffering State" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ERROR_ID, notification)
    }
}
