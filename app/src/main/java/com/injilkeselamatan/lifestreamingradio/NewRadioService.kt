package com.injilkeselamatan.lifestreamingradio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import com.injilkeselamatan.lifestreamingradio.extensions.fetchStationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class NewRadioService : MediaSessionService() {

    companion object {
        private const val TAG = "NewRadioService"
        private const val CUSTOM_COMMAND_STOP = "com.church.injilkeselamatan.radiostream.STOP"
        const val COMMAND_SET_SLEEP_TIMER = "com.injilkeselamatan.lifestreamingradio.SET_SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER =
            "com.injilkeselamatan.lifestreamingradio.CANCEL_SLEEP_TIMER"
        const val ARG_SLEEP_TIMER_MINUTES = "minutes"

        /** Kapan sleep timer berakhir, dalam [SystemClock.elapsedRealtime]. Tidak ada = mati. */
        const val EXTRA_SLEEP_TIMER_END = "sleep_timer_end_elapsed_realtime"

        private const val REMOTE_POLL_INTERVAL_MS = 15_000L
    }

    /** CastPlayer, atau ExoPlayer saja kalau Cast tidak tersedia di perangkat ini. */
    private lateinit var playbackPlayer: Player
    private lateinit var sessionPlayer: RadioSessionPlayer
    private lateinit var mediaSession: MediaSession

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var remoteSongPollJob: Job? = null

    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private val sleepTimerRunnable = Runnable {
        sessionPlayer.pause()
        clearSleepTimer()
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            // Headset dicabut: pause, jangan tiba-tiba bersuara dari speaker.
            .setHandleAudioBecomingNoisy(true)
            // Jaga Wi-Fi tetap hidup saat layar mati; tanpa ini stream bisa putus.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(StreamRetryPolicy())
            )
            .build()

        playbackPlayer = createPlaybackPlayer(exoPlayer)
        sessionPlayer = RadioSessionPlayer(playbackPlayer)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.channel_name)
            .setNotificationId(Constants.NOTIFICATION_ID)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_radio)
        setMediaNotificationProvider(notificationProvider)
        setListener(Listener())

        playbackPlayer.addListener(RadioEventListener(this, ::reconnectToLive))
        playbackPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateRemoteSongPolling()
            }
        })

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

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("InjilKeselamatan_RadioSession")
            .setSessionActivity(sessionActivity)
            .setCustomLayout(listOf(stopCustomCommand))
            .setCallback(SessionCallback(stopCustomCommand))
            .build()
        // Daftarkan sesi sekarang juga, jangan tunggu controller terhubung. Kalau sistem
        // menghidupkan ulang service setelah prosesnya dimatikan, belum ada controller, padahal
        // tombol media (headset/Bluetooth) tetap sampai ke sesi ini. Tanpa addSession, Media3
        // tidak menyalakan foreground service saat playback dimulai, dan Android 17 membisukan
        // audionya ("AudioHardening background playback muted").
        addSession(mediaSession)

        // Android 17 (background audio hardening): playback hanya boleh dimulai oleh aksi user,
        // jadi di sini item cukup dipasang tanpa prepare()/play(). Kalau sesi Cast lama masih
        // memutar sesuatu di TV, jangan diganti.
        if (playbackPlayer.currentTimeline.isEmpty) {
            playbackPlayer.setMediaItem(RadioMediaItem.create(this, forCast = isCasting()))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    /**
     * CastPlayer memindahkan playback antara HP dan TV secara otomatis. Di perangkat tanpa
     * Google Play services (misalnya Huawei), Cast tidak tersedia, tapi radio tetap harus bisa
     * diputar secara lokal.
     */
    private fun createPlaybackPlayer(localPlayer: ExoPlayer): Player =
        try {
            CastPlayer.Builder(this)
                .setLocalPlayer(localPlayer)
                .setTransferCallback(RadioTransferCallback(this))
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cast tidak tersedia, hanya memutar di perangkat ini", e)
            localPlayer
        }

    private fun isCasting(): Boolean =
        playbackPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    /** Server menutup stream (STATE_ENDED): sambung ulang selagi FGS masih aktif. */
    private fun reconnectToLive() {
        if (!playbackPlayer.playWhenReady) return
        playbackPlayer.seekToDefaultPosition()
        playbackPlayer.prepare()
    }

    /**
     * Receiver Cast tidak meneruskan metadata ICY, jadi selama casting judul lagu diambil dari
     * API stasiun. Polling hanya berjalan selama casting.
     */
    private fun updateRemoteSongPolling() {
        val casting = isCasting()
        if (casting && remoteSongPollJob == null) {
            remoteSongPollJob = serviceScope.launch {
                while (isActive) {
                    fetchStationStatus()?.let { sessionPlayer.setRemoteSongText(it.songText) }
                    delay(REMOTE_POLL_INTERVAL_MS)
                }
            }
        } else if (!casting && remoteSongPollJob != null) {
            remoteSongPollJob?.cancel()
            remoteSongPollJob = null
            sessionPlayer.setRemoteSongText(null)
        }
    }

    private fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) return clearSleepTimer()
        val durationMs = minutes * 60_000L
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.postDelayed(sleepTimerRunnable, durationMs)
        mediaSession.setSessionExtras(
            Bundle().apply {
                putLong(EXTRA_SLEEP_TIMER_END, SystemClock.elapsedRealtime() + durationMs)
            }
        )
    }

    private fun clearSleepTimer() {
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        mediaSession.setSessionExtras(Bundle.EMPTY)
    }

    private inner class SessionCallback(
        private val stopButton: CommandButton,
    ) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // JANGAN membangun izin di atas super.onConnect(): sejak Media3 1.11.0 implementasi
            // default hanya memberi akses baca, sehingga hasilnya kosong. Controller notifikasi
            // jadi tidak punya command, notifikasi media tidak terbentuk, dan foreground service
            // tidak pernah menyala. Berikan set command default secara eksplisit.
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(stopButton.sessionCommand!!)
                    .apply {
                        // Sleep timer hanya untuk UI app ini sendiri.
                        if (controller.packageName == packageName) {
                            add(SessionCommand(COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                            add(SessionCommand(COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                        }
                    }
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
            when (customCommand.customAction) {
                CUSTOM_COMMAND_STOP -> {
                    clearSleepTimer()
                    sessionPlayer.stop()
                    // Playlist kosong menghapus notifikasi. Play berikutnya memulihkan item
                    // lewat onPlaybackResumption.
                    sessionPlayer.clearMediaItems()
                    pauseAllPlayersAndStopSelf()
                }
                COMMAND_SET_SLEEP_TIMER -> startSleepTimer(args.getInt(ARG_SLEEP_TIMER_MINUTES))
                COMMAND_CANCEL_SLEEP_TIMER -> clearSleepTimer()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Play tanpa item: tombol play Bluetooth setelah proses mati, kartu media sistem, atau
         * play setelah "Stop Radio". Menggantikan auto-play lama di onCreate().
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(RadioMediaItem.create(this@NewRadioService, forCast = isCasting())),
                    /* startIndex= */ 0,
                    /* startPositionMs= */ C.TIME_UNSET
                )
            )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        NotificationManagerCompat.from(applicationContext).cancel(Constants.NOTIFICATION_ERROR_ID)
        clearSleepTimer()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        serviceScope.cancel()
        mediaSession.release()
        // Melepas CastPlayer beserta ExoPlayer di dalamnya.
        sessionPlayer.release()
        super.onDestroy()
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
