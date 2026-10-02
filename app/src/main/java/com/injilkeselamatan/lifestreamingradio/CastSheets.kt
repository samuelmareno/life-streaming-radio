package com.injilkeselamatan.lifestreamingradio

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.injilkeselamatan.lifestreamingradio.databinding.ItemCastDeviceBinding
import com.injilkeselamatan.lifestreamingradio.databinding.SheetCastChooserBinding
import com.injilkeselamatan.lifestreamingradio.databinding.SheetCastControllerBinding

/**
 * Dialog tombol Cast versi bottom sheet, menggantikan dialog bawaan mediarouter: daftar
 * perangkat saat belum casting, dan volume + tombol berhenti saat sedang casting.
 *
 * Hanya dipakai sampai Android 16. Mulai Android 17 `MediaRouteButton` membuka Output Switcher
 * sistem (diatur `CastParams` di [MyApplication]) dan tidak memanggil factory ini, sehingga
 * aplikasi tidak perlu izin ACCESS_LOCAL_NETWORK.
 */
class CastSheetDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment =
        CastChooserSheetFragment()

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment =
        CastControllerSheetFragment()
}

/**
 * Daftar TV/speaker di Wi-Fi yang sama. Mengetuk satu perangkat memulai casting; sheet tertutup
 * begitu perangkat terpilih, sama seperti dialog bawaan.
 */
class CastChooserSheetFragment : MediaRouteChooserDialogFragment() {

    private var binding: SheetCastChooserBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var searchStartedAt = 0L
    private var connectingRouteId: String? = null

    private val router: MediaRouter
        get() = MediaRouter.getInstance(requireContext())

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: RouteInfo, reason: Int) =
            dismissAllowingStateLoss()
    }

    // Base class membuat MediaRouteChooserDialog; di sini diganti bottom sheet sendiri.
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = createSheetDialog(this)
        val sheet = SheetCastChooserBinding.inflate(LayoutInflater.from(dialog.context))
        binding = sheet
        dialog.setContentView(sheet.root)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        searchStartedAt = SystemClock.uptimeMillis()
        router.addCallback(routeSelector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refreshRoutes()
        // Pesan pencarian berubah seiring waktu walau daftar perangkat tidak berubah.
        handler.postDelayed({ refreshRoutes() }, WIFI_HINT_DELAY_MS)
        handler.postDelayed({ refreshRoutes() }, NO_DEVICES_DELAY_MS)
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        router.removeCallback(callback)
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun refreshRoutes() {
        val sheet = binding ?: return
        // Seperti MediaRouteChooserDialog.onFilterRoute (isDefaultOrBluetooth tidak publik; speaker HP
        // dan Bluetooth adalah route sistem), urut nama seperti dialog bawaan.
        val routes = router.routes
            .filter { !it.isSystemRoute && it.isEnabled && it.matchesSelector(routeSelector) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        sheet.deviceList.removeAllViews()
        routes.forEach { sheet.deviceList.addView(deviceRow(sheet, it)) }

        val elapsed = SystemClock.uptimeMillis() - searchStartedAt
        sheet.searchProgress.isInvisible = elapsed >= NO_DEVICES_DELAY_MS
        sheet.searchMessage.isVisible = routes.isEmpty()
        sheet.searchMessage.setText(
            when {
                elapsed < WIFI_HINT_DELAY_MS -> R.string.cast_sheet_searching
                elapsed < NO_DEVICES_DELAY_MS -> R.string.cast_sheet_searching_hint
                else -> R.string.cast_sheet_no_devices
            }
        )
    }

    private fun deviceRow(sheet: SheetCastChooserBinding, route: RouteInfo): View {
        val row = ItemCastDeviceBinding.inflate(
            LayoutInflater.from(sheet.root.context), sheet.deviceList, false
        )
        row.icon.setImageResource(iconFor(route))
        row.name.text = route.name
        // Untuk Cast: model perangkat, atau aplikasi yang sedang dibuka di TV.
        row.description.text = route.description
        row.description.isVisible = !route.description.isNullOrBlank()
        row.progress.isVisible = route.id == connectingRouteId ||
            route.connectionState == RouteInfo.CONNECTION_STATE_CONNECTING
        row.root.setOnClickListener {
            connectingRouteId = route.id
            route.select()
            refreshRoutes()
        }
        return row.root
    }

    private companion object {
        // Sama dengan jeda petunjuk Wi-Fi dan "tidak ada perangkat" di dialog bawaan mediarouter.
        const val WIFI_HINT_DELAY_MS = 5_000L
        const val NO_DEVICES_DELAY_MS = 15_000L
    }
}

/** Perangkat yang sedang memutar radio: volume perangkat dan tombol berhenti casting. */
class CastControllerSheetFragment : MediaRouteControllerDialogFragment() {

    private var binding: SheetCastControllerBinding? = null
    private var draggingVolume = false

    private val router: MediaRouter
        get() = MediaRouter.getInstance(requireContext())

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteChanged(router: MediaRouter, route: RouteInfo) = bindSelectedRoute()
        override fun onRouteVolumeChanged(router: MediaRouter, route: RouteInfo) =
            bindSelectedRoute()

        override fun onRouteUnselected(router: MediaRouter, route: RouteInfo, reason: Int) =
            dismissAllowingStateLoss()
    }

    // Base class membuat MediaRouteControllerDialog; di sini diganti bottom sheet sendiri.
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = createSheetDialog(this)
        val sheet = SheetCastControllerBinding.inflate(LayoutInflater.from(dialog.context))
        binding = sheet
        sheet.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) router.selectedRoute.requestSetVolume(value.toInt())
        }
        sheet.volumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingVolume = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingVolume = false
            }
        })
        sheet.stopButton.setOnClickListener {
            // Sama dengan tombol "Hentikan transmisi" bawaan: receiver di TV ditutup.
            router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            dismiss()
        }
        dialog.setContentView(sheet.root)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Selector kosong + UNFILTERED_EVENTS: cukup untuk mengikuti perangkat yang sedang dipakai.
        router.addCallback(
            MediaRouteSelector.EMPTY, callback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        bindSelectedRoute()
    }

    override fun onStop() {
        router.removeCallback(callback)
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindSelectedRoute() {
        val sheet = binding ?: return
        val route = router.selectedRoute
        if (route.isSystemRoute) {
            // Casting sudah berhenti (kembali ke speaker HP/Bluetooth), mis. dari TV atau HP lain.
            dismissAllowingStateLoss()
            return
        }
        sheet.icon.setImageResource(iconFor(route))
        sheet.name.text = route.name
        sheet.status.setText(
            if (route.connectionState == RouteInfo.CONNECTION_STATE_CONNECTING) {
                R.string.cast_sheet_connecting
            } else {
                R.string.cast_sheet_playing_here
            }
        )
        val adjustable = route.volumeHandling == RouteInfo.PLAYBACK_VOLUME_VARIABLE &&
            route.volumeMax > 0
        sheet.volumeRow.isVisible = adjustable
        // Jangan menimpa posisi slider yang sedang digeser.
        if (adjustable && !draggingVolume) {
            sheet.volumeSlider.valueTo = route.volumeMax.toFloat()
            sheet.volumeSlider.value = route.volume.coerceIn(0, route.volumeMax).toFloat()
        }
    }
}

private fun createSheetDialog(fragment: DialogFragment): BottomSheetDialog =
    BottomSheetDialog(fragment.requireContext(), R.style.ThemeOverlay_LifeRadio_CastSheet).apply {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        dismissWithAnimation = true
    }

@DrawableRes
private fun iconFor(route: RouteInfo): Int = when (route.deviceType) {
    RouteInfo.DEVICE_TYPE_TV -> R.drawable.ic_tv
    RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER, RouteInfo.DEVICE_TYPE_GROUP -> R.drawable.ic_speaker
    else -> R.drawable.ic_cast
}
