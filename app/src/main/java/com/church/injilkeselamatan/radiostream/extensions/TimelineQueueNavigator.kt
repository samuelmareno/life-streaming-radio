package com.church.injilkeselamatan.radiostream.extensions


import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import com.church.injilkeselamatan.radiostream.R
import com.google.android.exoplayer2.ControlDispatcher
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator

class TimelineQueueNavigator(
    private val context: Context,
    mediaSession: MediaSessionCompat
) : TimelineQueueNavigator(mediaSession) {

    override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.notif)
        val bundle = Bundle()
        bundle.putString(MediaMetadataCompat.METADATA_KEY_TITLE, Constants.TITLE)
        bundle.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, Constants.SUBTITLE)
        bundle.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, Constants.SUBTITLE)
        bundle.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, Constants.TITLE)
        bundle.putParcelable(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        bundle.putParcelable(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
        return MediaDescriptionCompat.Builder()
            .setMediaId(Constants.MEDIA_ID)
            .setTitle(Constants.TITLE)
            .setDescription(Constants.SUBTITLE)
            .setExtras(bundle)
            .build()
    }

}