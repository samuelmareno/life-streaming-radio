package com.church.injilkeselamatan.radiostream

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.bumptech.glide.Glide
import com.church.injilkeselamatan.radiostream.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appUpdateManager: AppUpdateManager

    companion object {
        private const val UPDATE_CODE = 999
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appUpdateManager = AppUpdateManagerFactory.create(applicationContext)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener {
            if (it.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    it.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                appUpdateManager.startUpdateFlowForResult(
                    it,
                    AppUpdateType.IMMEDIATE,
                    this,
                    UPDATE_CODE
                )
            } else {
                //TODO: do something in here if update not available
            }
        }

        Glide.with(applicationContext)
            .load(R.drawable.lifers2)
            .into(binding.imageView)

        binding.imageView.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.lifers2))

        binding.controller.show()

        val intent = Intent(this, RadioService::class.java)
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
                if (binder is RadioService.RadioServiceBinder) {
                    binding.controller.player = binder.exoPlayer()
                }
            }

            override fun onServiceDisconnected(p0: ComponentName?) = Unit

        }, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener {
                if (it.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    appUpdateManager.startUpdateFlowForResult(
                        it,
                        AppUpdateType.IMMEDIATE,
                        this,
                        UPDATE_CODE
                    )
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_CODE && resultCode == Activity.RESULT_OK) {
            // TODO: do something in here if in-app updates success
        } else {
            // TODO: do something in here if in-app updates failure
        }
    }

}