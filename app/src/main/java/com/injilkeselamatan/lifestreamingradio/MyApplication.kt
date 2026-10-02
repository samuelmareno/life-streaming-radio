package com.injilkeselamatan.lifestreamingradio

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.common.util.UnstableApi

class MyApplication : Application() {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Wajib sebelum CastPlayer atau tombol Cast dipakai. Jalur meta-data manifest yang lama
        // menyalakan media session milik Cast SDK, yang bentrok dengan Media3.
        Cast.getSingletonInstance(this).initialize(
            CastParams.Builder()
                // Receiver kustom (docs/cast-receiver) dengan logo dan judul lagu live, atau
                // Default Media Receiver Google sebelum App ID-nya terdaftar. Sama dengan versi
                // iOS, jadi HP Android dan iPhone bisa mengendalikan sesi yang sama.
                .setReceiverApplicationId(BuildConfig.CAST_RECEIVER_APP_ID)
                .setRemoteToLocalEnabled(true)
                // Android 17: memilih TV lewat Output Switcher sistem tidak butuh izin
                // ACCESS_LOCAL_NETWORK, yang wajib kalau app memindai jaringan sendiri.
                .setShowSystemOutputSwitcherOnCastButtonClick(true)
                .build()
        )
    }
}
