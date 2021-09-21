package com.church.injilkeselamatan.radiostream.di

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.MediaSessionCompat
import com.church.injilkeselamatan.radiostream.BuildConfig
import com.church.injilkeselamatan.radiostream.extensions.RadioControlDispatcher
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {

    @ServiceScoped
    @Provides
    fun provideMediaItem(): MediaItem =
        MediaItem
            .fromUri(Uri.parse(BuildConfig.RADIO_URL))

    @ServiceScoped
    @Provides
    fun provideAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()

    @ServiceScoped
    @Provides
    fun provideExoplayer(
        @ApplicationContext context: Context,
        audioAttributes: AudioAttributes
    ): SimpleExoPlayer =
        SimpleExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .build()

    @ServiceScoped
    @Provides
    fun provideMediaSessionCompat(@ApplicationContext context: Context): MediaSessionCompat {
        return MediaSessionCompat(context, "TAG")
    }

    @ServiceScoped
    @Provides
    fun provideMediaSessionConnector(mediaSessionCompat: MediaSessionCompat): MediaSessionConnector =
        MediaSessionConnector(mediaSessionCompat)
}