package com.injilkeselamatan.lifestreamingradio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.injilkeselamatan.lifestreamingradio.extensions.Constants
import com.injilkeselamatan.lifestreamingradio.extensions.Constants.CHANNEL_ID
import com.injilkeselamatan.lifestreamingradio.extensions.RadioEventListener
import com.injilkeselamatan.lifestreamingradio.extensions.parseIcyTrack

@OptIn(UnstableApi::class)
class NewRadioService : MediaSessionService() {

    companion object {
        private const val CUSTOM_COMMAND_STOP = "com.church.injilkeselamatan.radiostream.STOP"
    }


    private lateinit var exoPlayer: ExoPlayer

    private lateinit var mediaSession: MediaSession
    private lateinit var forwardingPlayer: ForwardingPlayer
    private lateinit var radioEventListener: RadioEventListener
    private lateinit var notificationProvider: DefaultMediaNotificationProvider

    private lateinit var hlsSource: MediaSource


    override fun onCreate() {
        super.onCreate()

        // 1. Konfigurasi Audio Player
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)

        // 2. Konfigurasi Notifikasi
        notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.channel_name)
            .setNotificationId(Constants.NOTIFICATION_ID)
            .build()
        // Coba gunakan icon bawaan dulu untuk memastikan tidak ada masalah dengan resource icon
        notificationProvider.setSmallIcon(R.drawable.ic_radio)

        // 3. Inisialisasi Player dan Session


        // 4. Tambahkan Listener untuk Logging & Error Handling
        radioEventListener = RadioEventListener(this, true, {
            setupMediaAndPlay()
        }, {

        })
        exoPlayer.addListener(radioEventListener)

        setListener(Listener())

        // 5. Siapkan Media dan Mulai Bermain
        setupMediaAndPlay()
        setMediaNotificationProvider(notificationProvider)
        // Metadata ICY datang sebagai satu baris "Artis - Judul" di field title.
        // Pemecahannya dilakukan di sini saja, lalu MediaSession (dan karenanya
        // notifikasi maupun MediaController di MainActivity) membaca hasil yang
        // sudah rapi dari player ini. Dengan begitu tidak ada dua tempat yang
        // menafsirkan metadata secara berbeda.
        forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getMediaMetadata(): MediaMetadata =
                splitTrackMetadata(super.getMediaMetadata())
        }
        val stopCustomCommand = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_STOP, Bundle.EMPTY))
            .setDisplayName("Stop Radio")
            .setCustomIconResId(R.drawable.ic_close_24)
            .setEnabled(true)
            .build()

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setId("InjilKeselamatan_RadioSession")
            .setSessionActivity(sessionActivity)
            .setCustomLayout(listOf(stopCustomCommand))
            .setCallback(object : MediaSession.Callback {

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // JANGAN membangun izin di atas super.onConnect(): sejak Media3
                    // 1.11.0 implementasi default hanya memberi akses baca, sehingga
                    // hasilnya kosong. Controller notifikasi jadi tidak punya command,
                    // notifikasi media tidak terbentuk, dan foreground service tidak
                    // pernah menyala. Berikan set command default secara eksplisit.
                    val sessionCommands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(stopCustomCommand.sessionCommand!!)
                            .build()

                    return MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    return if (customCommand.customAction == CUSTOM_COMMAND_STOP) {
                        exoPlayer.stop()
                        mediaSession.release()
                        pauseAllPlayersAndStopSelf()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        super.onCustomCommand(session, controller, customCommand, args)
                    }
                }

            })
            .build()

    }

    // Metode ini wajib di-override untuk menghubungkan service dengan session
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Memecah metadata ICY "Artis - Judul" menjadi field yang benar.
     * Kalau formatnya tidak sesuai dugaan, metadata dikembalikan apa adanya
     * supaya judul mentah tetap tampil ketimbang hilang.
     */
    private fun splitTrackMetadata(source: MediaMetadata): MediaMetadata {
        val track = parseIcyTrack(source.title?.toString()) ?: return source
        return source.buildUpon()
            .setTitle(track.title)
            .setArtist(track.artist)
            .build()
    }

    private fun setupMediaAndPlay() {
        // Buat metadata untuk ditampilkan di notifikasi dan UI lainnya
        val artworkUri = "android.resource://${packageName}/${R.drawable.notif}".toUri()
        val metadata = MediaMetadata.Builder()
            .setArtist(Constants.SUBTITLE)
            .setArtworkUri(artworkUri)
            .build()

        // Buat MediaItem sebagai Live Stream untuk menyembunyikan seek bar
        val mediaItem = MediaItem.Builder()
            .setUri(BuildConfig.RADIO_URL)
            .setMediaId(Constants.MEDIA_ID)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().build()
            )
            .setMediaMetadata(metadata)
            .build()
        hlsSource = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(hlsSource)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // Dipanggil saat service akan dihancurkan
    override fun onDestroy() {
        // Urutan pelepasan resource penting
        exoPlayer.removeListener(radioEventListener)
        if (::forwardingPlayer.isInitialized) forwardingPlayer.release()
        if (::exoPlayer.isInitialized) exoPlayer.release()
        if (::mediaSession.isInitialized) mediaSession.release()

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.release()
        mediaSession.release()
        NotificationManagerCompat.from(this.applicationContext).cancel(
            Constants.NOTIFICATION_ERROR_ID
        )
        pauseAllPlayersAndStopSelf()
    }

    private inner class Listener : MediaSessionService.Listener {
        override fun onForegroundServiceStartNotAllowedException() {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // Notification permission is required but not granted
                return
            }
            val notificationManagerCompat = NotificationManagerCompat.from(this@NewRadioService)
            ensureNotificationChannel(notificationManagerCompat)
            val builder =
                NotificationCompat.Builder(this@NewRadioService, CHANNEL_ID)
                    .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
                    .setContentTitle(Constants.TITLE)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(Constants.TITLE)
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            notificationManagerCompat.notify(Constants.NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureNotificationChannel(notificationManagerCompat: NotificationManagerCompat) {
        if (
            Build.VERSION.SDK_INT < 26 ||
            notificationManagerCompat.getNotificationChannel(CHANNEL_ID) != null
        ) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.description = getString(R.string.channel_description)
        notificationManagerCompat.createNotificationChannel(channel)
    }
}

