package com.church.injilkeselamatan.radiostream

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationManagerCompat
import com.church.injilkeselamatan.radiostream.extensions.*
import com.church.injilkeselamatan.radiostream.extensions.Constants.CHANNEL_ID
import com.church.injilkeselamatan.radiostream.extensions.Constants.NOTIFICATION_ID
import com.google.android.exoplayer2.ForwardingPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RadioService : Service() {
    @Inject
    lateinit var exoPlayer: SimpleExoPlayer

    @Inject
    lateinit var mediaItem: MediaItem

    @Inject
    lateinit var mediaSessionConnector: MediaSessionConnector

    @Inject
    lateinit var mediaSessionCompat: MediaSessionCompat

    private lateinit var playerNotificationManager: PlayerNotificationManager
    private lateinit var radioEventListener: RadioEventListener

    private lateinit var forwardingPlayer: ForwardingPlayer

    var isServiceInForeground = false

    private lateinit var hlsMediaSource: MediaSource

    override fun onCreate() {
        super.onCreate()
        hlsMediaSource = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(mediaItem)
        radioEventListener = RadioEventListener(this)
        exoPlayer.addListener(radioEventListener)
        forwardingPlayer = ForwardingPlayer(exoPlayer)
        playRadio()
        initNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        val intentStart = Intent(this.applicationContext, this::class.java)
        applicationContext.startService(intentStart)
        return RadioServiceBinder()
    }


    private fun initNotification() {
        playerNotificationManager = PlayerNotificationManager
            .Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelDescriptionResourceId(R.string.channel_description)
            .setChannelNameResourceId(R.string.channel_name)
            .setMediaDescriptionAdapter(RadioDescriptionAdapter(this))
            .setNotificationListener(RadioNotificationListener(this))
            .setSmallIconResourceId(R.drawable.ic_radio)
            .build()

        playerNotificationManager.apply {
            setSmallIcon(R.drawable.ic_radio)
            setUseStopAction(true)
            setUseChronometer(true)
            setUsePreviousAction(false)
            setUseNextAction(false)
            setUsePreviousActionInCompactView(false)
            setUseNextActionInCompactView(false)

            setMediaSessionToken(mediaSessionCompat.sessionToken)
        }
        mediaSessionConnector.setQueueNavigator(
            TimelineQueueNavigator(
                this.applicationContext,
                mediaSessionCompat
            )
        )
        showNotification()
        mediaSessionConnector.setPlayer(forwardingPlayer)
        mediaSessionCompat.isActive = true
    }

    fun playRadio() {
        exoPlayer.apply {
            setMediaSource(hlsMediaSource)
            prepare()
            playWhenReady = true
        }
    }

    private fun showNotification() {
        playerNotificationManager.setPlayer(forwardingPlayer)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.removeListener(radioEventListener)
        exoPlayer.release()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.release()
        stopForeground(true)
        NotificationManagerCompat.from(this.applicationContext).cancel(
            Constants.NOTIFICATION_ERROR_ID
        )
    }

    inner class RadioServiceBinder : Binder() {
        fun exoPlayer() = exoPlayer
    }
}