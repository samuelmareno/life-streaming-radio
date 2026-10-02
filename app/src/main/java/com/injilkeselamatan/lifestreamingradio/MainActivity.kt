package com.injilkeselamatan.lifestreamingradio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.injilkeselamatan.lifestreamingradio.databinding.ActivityMainBinding
import com.injilkeselamatan.lifestreamingradio.extensions.Constants
import com.injilkeselamatan.lifestreamingradio.extensions.parseIcyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var mediaControllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseUI(isPlaying)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateNowPlaying(mediaMetadata)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        askNotificationPermission()
        setupUpdateManager()
        setupUI()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withContext(Dispatchers.IO) {
                    while (true) {
                        if (::mediaControllerFuture.isInitialized) {
                            if (!mediaControllerFuture.get().isConnected) {
                                withContext(Dispatchers.Main) {
                                    binding.exoPlay.visibility = View.VISIBLE
                                    binding.exoPause.visibility = View.GONE
                                }
                                break
                            }
                        }
                        delay(1000)

                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        checkInProgressUpdate()
    }

    override fun onDestroy() {
        mediaController?.removeListener(listener)
        super.onDestroy()
    }

    private fun connectToMediaSession() {
        val sessionToken = SessionToken(this, ComponentName(this, NewRadioService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            try {
                mediaController = mediaControllerFuture.get()
                mediaController?.addListener(listener)
                updatePlayPauseUI(mediaController?.isPlaying == true)
                mediaController?.mediaMetadata?.let { updateNowPlaying(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun ensureMediaControllerConnectedAndPlay() {
        if (mediaController?.isConnected == true) {
            mediaController?.playWhenReady = true
            return
        }

        val intent = Intent(this, NewRadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }

        connectToMediaSession()
    }

    private fun setupUI() {
        binding.tvTitle.isSelected = true
        Glide.with(this).load(R.drawable.lifers2).into(binding.imageView)

        binding.exoPlay.setOnClickListener {
            ensureMediaControllerConnectedAndPlay()
        }

        binding.exoPause.setOnClickListener {
            mediaController?.pause()
        }

        binding.playPauseButton.setOnClickListener {
            if (mediaController?.isPlaying == true) {
                mediaController?.pause()
            } else {
                ensureMediaControllerConnectedAndPlay()
            }
        }

        // Initial UI state
        updatePlayPauseUI(false)
    }

    private fun updatePlayPauseUI(isPlaying: Boolean) {
        binding.exoPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
        binding.exoPause.visibility = if (isPlaying) View.VISIBLE else View.GONE
    }

    private fun updateNowPlaying(metadata: MediaMetadata) {
        // Notifikasi dibangun dari player.getMediaMetadata() yang sudah dirapikan
        // NewRadioService, tapi MediaController menerima objek metadata MENTAH
        // lewat callback ForwardingPlayer. Supaya kedua tampilan tidak pernah
        // berbeda lagi, keduanya memakai fungsi penguraian yang sama.
        //
        // Kalau judulnya sudah terpecah (tidak mengandung " - "), parseIcyTrack
        // mengembalikan null dan field bawaan dipakai apa adanya — jadi ini tetap
        // benar seandainya nanti Media3 mengirim metadata yang sudah rapi.
        val track = parseIcyTrack(metadata.title?.toString())

        binding.tvTitle.text = track?.title
            ?: metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: Constants.TITLE
        binding.tvArtist.text = track?.artist
            ?: metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: Constants.SUBTITLE
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