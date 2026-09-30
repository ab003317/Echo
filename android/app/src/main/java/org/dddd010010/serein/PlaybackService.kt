package org.dddd010010.serein

import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var telemetry:PlaybackTelemetry?=null
    private var flushJob:Job?=null
    private val languageChanged:()->Unit={updateNotificationLanguage()}
    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config);AppLocale.changed()
    }
    private fun updateNotificationLanguage() {
        val channel=android.app.NotificationChannel("default_channel_id",tr(R.string.playback_channel),android.app.NotificationManager.IMPORTANCE_LOW)
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(AppLocale.wrap(this))
            .setChannelId("default_channel_id").setChannelName(R.string.playback_channel).build())
        session?.let{onUpdateNotification(it,it.player.playWhenReady)}
    }
    private fun sample(player:Player) {
        val id=player.currentMediaItem?.mediaId.orEmpty()
        if(id.isBlank())return
        if(telemetry?.track!=id || (telemetry?.finished==true && player.isPlaying)) {
            val song=Library.knownSong(id) ?: return
            telemetry=PlaybackTelemetry(id,song.duration,System.currentTimeMillis()/1000.0,android.os.SystemClock.elapsedRealtime())
        }
        telemetry?.sample(android.os.SystemClock.elapsedRealtime(),player.isPlaying,player.currentPosition)
    }
    private fun checkpoint(outcome:String="progress",force:Boolean=false) {
        val value=telemetry?.checkpoint(System.currentTimeMillis()/1000.0,outcome,force) ?: return
        Library.recordPlayback(value)
        if(flushJob?.isActive!=true)flushJob=scope.launch(Dispatchers.IO){runCatching{Library.flush()}}
    }
    override fun onCreate() {
        super.onCreate()
        updateNotificationLanguage()
        AppLocale.listeners.add(languageChanged)
        val network = DefaultHttpDataSource.Factory().setUserAgent("Echo/0.6").setConnectTimeoutMs(12000).setReadTimeoutMs(20000)
        val sources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, network)) { spec ->
            if(spec.uri.scheme != "serein") spec else {
                val song = Library.knownSong(spec.uri.lastPathSegment)
                    ?: throw java.io.FileNotFoundException(tr(R.string.ui_song_not_found_in_library))
                if(Library.available(song)) spec.withUri(Uri.fromFile(Library.file(song)))
                else spec.withUri(Uri.parse(Library.audio(song)))
            }
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.repeatMode = Library.setting("repeat")
        player.shuffleModeEnabled = Library.setting("shuffle") == 1
        session = MediaSession.Builder(this, player)
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(NotificationArtwork(this,scope))).build()
        val queue = Library.array("queue")
        val restored = (0 until queue.length()).mapNotNull { i -> Library.knownSong(queue.optString(i)) }
        if(restored.isNotEmpty()) {
            player.setMediaItems(restored.map { item(it) }, Library.setting("queueIndex").coerceIn(restored.indices), Library.setting("position").toLong())
        }
        player.addListener(object: Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if(reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || mediaItem?.mediaId != telemetry?.track) {
                    checkpoint(if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)"completed" else if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || (reason==Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem!=null))"skipped" else "stopped",true)
                    telemetry=null
                }
                sample(player)
                persist(player)
            }
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                telemetry?.sample(android.os.SystemClock.elapsedRealtime(),player.isPlaying,oldPosition.positionMs)
                if(oldPosition.mediaItem?.mediaId==newPosition.mediaItem?.mediaId && reason!=Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    telemetry?.sample(android.os.SystemClock.elapsedRealtime(),player.isPlaying,newPosition.positionMs)
            }
            override fun onIsPlayingChanged(isPlaying:Boolean) { sample(player);if(!isPlaying)checkpoint(force=true) }
            override fun onPlaybackStateChanged(playbackState:Int) {
                if(playbackState==Player.STATE_ENDED){sample(player);checkpoint("completed",true)}
            }
            override fun onPlayerError(error:PlaybackException) { sample(player);checkpoint("error",true) }
            override fun onRepeatModeChanged(repeatMode: Int) { Library.setting("repeat", repeatMode, true) }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { Library.setting("shuffle", if(shuffleModeEnabled) 1 else 0, true) }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { persist(player) }
        })
        scope.launch {
            var tick = 0
            while(isActive) {
                delay(1000)
                sample(player)
                checkpoint()
                val sleep = Library.obj("sleep").optLong("deadline")
                if(sleep>0 && System.currentTimeMillis()>=sleep) { player.pause(); Library.save("sleep",JSONObject()) }
                if(++tick % 5 == 0) persist(player)
            }
        }
    }
    private fun persist(player: Player) {
        val queue = JSONArray()
        for(i in 0 until player.mediaItemCount) queue.put(player.getMediaItemAt(i).mediaId)
        Library.save("queue", queue, notify=false)
        Library.setting("queueIndex", maxOf(0,player.currentMediaItemIndex), true)
        Library.setting("position", player.currentPosition.toInt().coerceAtLeast(0), true)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if(controllerInfo.isTrusted || controllerInfo.packageName == packageName) session else null
    override fun onDestroy() {
        AppLocale.listeners.remove(languageChanged)
        session?.let { sample(it.player);checkpoint("stopped",true);persist(it.player); it.player.release(); it.release() }
        scope.cancel(); super.onDestroy()
    }
    companion object {
        fun item(s: Song): MediaItem {
            val cover = File(Library.mediaDir, s.id + ".cover")
            val metadata = MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            if(s.hasCover)metadata.setArtworkUri(if(s.id in Library.coverFiles) Uri.fromFile(cover) else Uri.parse(Library.art(s)))
            return MediaItem.Builder().setMediaId(s.id).setUri("serein://track/${s.id}").setMediaMetadata(metadata.build()).build()
        }
    }
}
