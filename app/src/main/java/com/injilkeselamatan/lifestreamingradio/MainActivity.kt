package com.injilkeselamatan.lifestreamingradio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.injilkeselamatan.lifestreamingradio.databinding.ActivityMainBinding
import com.injilkeselamatan.lifestreamingradio.extensions.Constants
import com.injilkeselamatan.lifestreamingradio.extensions.StationStatus
import com.injilkeselamatan.lifestreamingradio.extensions.fetchStationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATUS_POLL_INTERVAL_MS = 15_000L
        private const val SLEEP_TIMER_REFRESH_MS = 15_000L
        private val SLEEP_TIMER_OPTIONS_MINUTES = listOf(15, 30, 45, 60)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var appUpdateManager: AppUpdateManager
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    /** Status terakhir dari API stasiun; null = belum diketahui, bukan berarti offline. */
    private var stationStatus: StationStatus? = null

    /** Auto-play sekali saat app dibuka user, bukan saat activity dibuat ulang (rotasi). */
    private var autoPlayPending = false

    /** Kapan sleep timer berakhir, dalam elapsedRealtime; 0 = mati. */
    private var sleepTimerEnd = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                updatePlayPauseUI()
            }
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                updateNowPlaying(player.mediaMetadata)
            }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            mediaController = null
            controllerFuture = null
            updatePlayPauseUI()
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            applySessionExtras(extras)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Latar app selalu hitam, jadi ikon status bar & navigasi selalu terang.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        autoPlayPending = savedInstanceState == null
        askNotificationPermission()
        setupUpdateManager()
        setupUI()
        setupCastButton()
        observeStationStatus()
        observeSleepTimer()
    }

    override fun onResume() {
        super.onResume()
        checkInProgressUpdate()
    }

    override fun onDestroy() {
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    /** Edge-to-edge wajib sejak targetSdk 35: jauhkan konten dari status bar, navbar, & notch. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Cukup bind lewat MediaController; Media3 yang menyalakan foreground service saat playback
     * dimulai. Jangan startForegroundService() manual: kalau playback batal dimulai (mis.
     * stasiun offline), sistem melempar ForegroundServiceDidNotStartInTimeException.
     */
    private fun connectToMediaSession() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(this, ComponentName(this, NewRadioService::class.java))
        val future = MediaController.Builder(this, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = try {
                future.get()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal terhubung ke NewRadioService", e)
                controllerFuture = null
                return@addListener
            }
            mediaController = controller
            controller.addListener(playerListener)
            updatePlayPauseUI()
            updateNowPlaying(controller.mediaMetadata)
            applySessionExtras(controller.sessionExtras)
            if (autoPlayPending) {
                autoPlayPending = false
                // App dibuka user: radio langsung diputar, seperti sebelumnya dan seperti iOS.
                if (!controller.playWhenReady) startPlayback()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startPlayback() {
        val controller = mediaController
        if (controller == null) {
            // Terputus dari service: sambung ulang, lalu putar setelah terhubung.
            autoPlayPending = true
            connectToMediaSession()
            return
        }
        if (stationStatus?.isOnline == false) {
            showStationOffline()
            return
        }
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        controller.play()
    }

    private fun setupUI() {
        binding.nowPlaying.tvTitle.isSelected = true
        Glide.with(this).load(R.drawable.lifers2).into(binding.imageView)

        binding.nowPlaying.exoPlay.setOnClickListener { startPlayback() }
        binding.nowPlaying.exoPause.setOnClickListener { mediaController?.pause() }
        binding.topControls.sleepTimerButton.setOnClickListener { showSleepTimerDialog() }

        updatePlayPauseUI()
        updateNowPlaying(MediaMetadata.EMPTY)
    }

    private fun updatePlayPauseUI() {
        val controller = mediaController
        // playWhenReady, bukan isPlaying: selama buffering/reconnect tombol tetap "pause" supaya
        // user bisa membatalkan.
        val wantsToPlay = controller != null &&
            controller.playWhenReady &&
            controller.playbackState != Player.STATE_IDLE &&
            controller.playbackState != Player.STATE_ENDED
        binding.nowPlaying.exoPlay.isVisible = !wantsToPlay
        binding.nowPlaying.exoPause.isVisible = wantsToPlay
    }

    private fun updateNowPlaying(metadata: MediaMetadata) {
        // RadioSessionPlayer sudah memecah "Artis - Judul" (dan mengisi judul saat casting).
        // Jangan dipecah ulang di sini: judul yang mengandung " - " akan terpotong dua kali.
        binding.nowPlaying.tvTitle.text =
            metadata.title?.takeIf { it.isNotBlank() } ?: Constants.TITLE
        binding.nowPlaying.tvArtist.text =
            metadata.artist?.takeIf { it.isNotBlank() } ?: Constants.SUBTITLE
    }

    private fun setupCastButton() {
        val castButton = binding.topControls.castButton
        val setup = try {
            MediaRouteButtonFactory.setUpMediaRouteButton(this, castButton)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tombol Cast tidak tersedia", e)
            castButton.isVisible = false
            return
        }
        // Sampai Android 16 tombol Cast membuka bottom sheet sendiri (CastSheets.kt).
        castButton.dialogFactory = CastSheetDialogFactory()
        Futures.addCallback(setup, object : FutureCallback<Void?> {
            override fun onSuccess(result: Void?) = Unit

            override fun onFailure(t: Throwable) {
                // Mis. perangkat tanpa Google Play services: radio tetap jalan, tanpa Cast.
                Log.w(TAG, "Tombol Cast tidak tersedia", t)
                castButton.isVisible = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeStationStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    fetchStationStatus()?.let(::applyStationStatus)
                    delay(STATUS_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun applyStationStatus(status: StationStatus) {
        stationStatus = status
        binding.nowPlaying.tvStatus.apply {
            setText(if (status.isOnline) R.string.status_on_air else R.string.status_offline)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (status.isOnline) R.color.status_on_air else R.color.status_offline
                )
            )
            isVisible = true
        }
        // Stasiun mati tapi player masih mencoba menyambung: hentikan dan beri tahu, daripada
        // spinner berputar sampai batas retry 10 menit habis.
        val controller = mediaController ?: return
        if (!status.isOnline && controller.playWhenReady && !controller.isPlaying) {
            controller.pause()
            showStationOffline()
        }
    }

    private fun showStationOffline() {
        Toast.makeText(this, R.string.msg_station_offline, Toast.LENGTH_LONG).show()
    }

    private fun showSleepTimerDialog() {
        val options = buildList {
            // Pilihan 1 menit hanya di build debug, untuk verifikasi cepat.
            if (BuildConfig.DEBUG) add(1)
            addAll(SLEEP_TIMER_OPTIONS_MINUTES)
        }
        val labels = options.map { getString(R.string.sleep_timer_minutes, it) } +
            getString(R.string.sleep_timer_off)
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(labels.toTypedArray()) { _, which ->
                // Item terakhir, "Matikan timer", di luar daftar pilihan menit: 0 = matikan.
                setSleepTimer(options.getOrElse(which) { 0 })
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        val controller = mediaController ?: return
        val action = if (minutes > 0) {
            NewRadioService.COMMAND_SET_SLEEP_TIMER
        } else {
            NewRadioService.COMMAND_CANCEL_SLEEP_TIMER
        }
        controller.sendCustomCommand(
            SessionCommand(action, Bundle.EMPTY),
            Bundle().apply { putInt(NewRadioService.ARG_SLEEP_TIMER_MINUTES, minutes) },
        )
    }

    private fun applySessionExtras(extras: Bundle) {
        sleepTimerEnd = extras.getLong(NewRadioService.EXTRA_SLEEP_TIMER_END, 0L)
        renderSleepTimer()
    }

    private fun renderSleepTimer() {
        val label = binding.topControls.tvSleepTimer
        val remainingMs = sleepTimerEnd - SystemClock.elapsedRealtime()
        if (sleepTimerEnd == 0L || remainingMs <= 0) {
            label.isVisible = false
            return
        }
        label.text =
            getString(R.string.sleep_timer_remaining, ceil(remainingMs / 60_000.0).toInt())
        label.isVisible = true
    }

    private fun observeSleepTimer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderSleepTimer()
                    delay(SLEEP_TIMER_REFRESH_MS)
                }
            }
        }
    }

    private fun setupUpdateManager() {
        appUpdateManager = AppUpdateManagerFactory.create(applicationContext)
        appUpdateManager.appUpdateInfo.addOnSuccessListener {
            if (it.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                it.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                startImmediateUpdate(it)
            }
        }
    }

    private fun checkInProgressUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener {
            if (it.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                startImmediateUpdate(it)
            }
        }
    }

    private val updateFlowLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* Pengguna membatalkan atau update gagal; alur dicoba lagi di onResume(). */ }

    private fun startImmediateUpdate(info: AppUpdateInfo) {
        appUpdateManager.startUpdateFlowForResult(
            info,
            updateFlowLauncher,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    createNotificationChannel()
                    connectToMediaSession()
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    showPermissionDeniedDialog()
                }

                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            createNotificationChannel()
            connectToMediaSession()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            createNotificationChannel()
            connectToMediaSession()
        } else {
            showPermissionDeniedDialog()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle("Izin Diperlukan")
            .setMessage("Aplikasi ini memerlukan izin notifikasi untuk kontrol media. Izinkan di pengaturan.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .show()
    }
}