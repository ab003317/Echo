@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.min

data class PlaybackUi(val repeat:Int,val shuffle:Boolean,val playing:Boolean,val buffering:Boolean,val ended:Boolean,val hasNext:Boolean,val count:Int,val error:Boolean)
@Composable fun playbackUi(c:MediaController?,events:Int)=remember(c,events){PlaybackUi(c?.repeatMode ?: 0,c?.shuffleModeEnabled==true,c?.playWhenReady==true,c?.playbackState==Player.STATE_BUFFERING,c?.playbackState==Player.STATE_ENDED,c?.hasNextMediaItem()==true,c?.mediaItemCount ?: 0,c?.playerError!=null)}

@Composable fun PlayerProgress(c:MediaController?,compact:Boolean=false){
    var position by remember(c){mutableLongStateOf(0L)}
    var duration by remember(c){mutableLongStateOf(1L)}
    var seeking by remember{mutableStateOf<Float?>(null)}
    LaunchedEffect(c){while(true){position=c?.currentPosition?.coerceAtLeast(0) ?: 0;val known=c?.duration ?: 0;duration=if(known>0)known else ((Library.songs.find{it.id==c?.currentMediaItem?.mediaId}?.duration ?: 0.0)*1000).toLong().coerceAtLeast(1);delay(500)}}
    val fraction=(position.toFloat()/duration).coerceIn(0f,1f)
    if(compact)LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(2.dp),color=Gold,trackColor=Muted.copy(alpha=.15f),gapSize=0.dp,drawStopIndicator={})
    else Column{
        Slider(value=seeking ?: fraction,onValueChange={seeking=it},onValueChangeFinished={seeking?.let{c?.seekTo((it*duration).toLong())};seeking=null},enabled=duration>1,
            // Fill the slider's touch height so its minimum layout size cannot top-align the dot.
            thumb={Box(Modifier.width(12.dp).height(48.dp),contentAlignment=Alignment.Center){Box(Modifier.size(12.dp).background(Gold,CircleShape))}},
            track={SliderDefaults.Track(it,modifier=Modifier.height(3.dp),thumbTrackGapSize=0.dp,drawStopIndicator=null)})
        Row(Modifier.fillMaxWidth()){Text(timeLabel(seeking?.let{(it*duration).toLong()} ?: position),fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f));Text(timeLabel(duration),fontSize=11.sp,color=Muted)}
    }
}

@Composable fun PlayerScreen(song:Song,c:MediaController?,events:Int,toggle:()->Unit,queue:()->Unit,menu:()->Unit,close:()->Unit){
    val configuration=LocalConfiguration.current
    val landscape=configuration.screenWidthDp>configuration.screenHeightDp
    val ui=playbackUi(c,events)
    val sampledTone=ArtworkImages.tone(song)
    val tone by animateColorAsState(sampledTone,animationSpec=tween(650),label="album atmosphere")
    var timer by remember{mutableStateOf(false)}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    val revision=Library.revision
    val deadline=Library.obj("sleep").optLong("deadline")
    LaunchedEffect(deadline){while(deadline>0){now=System.currentTimeMillis();delay(1000)}}
    val repeat=when(ui.repeat){Player.REPEAT_MODE_ONE->tr(R.string.ui_repeat_one);Player.REPEAT_MODE_ALL->tr(R.string.ui_repeat_all);else->tr(R.string.ui_play_in_order)}
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(Night,tone,.40f),lerp(Night,tone,.16f),Night)))){
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=close){Icon(Icons.Rounded.KeyboardArrowDown,tr(R.string.ui_collapse_player),Modifier.size(26.dp))};Text(tr(R.string.ui_now_playing),fontSize=12.sp,color=TextBright.copy(alpha=.72f),modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center);IconButton(onClick=menu){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_song_options))}}
        val controls:@Composable ()->Unit={Column(Modifier.fillMaxWidth().padding(horizontal=28.dp)){
            Row(Modifier.padding(top=28.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(song.title,fontSize=27.sp,fontWeight=FontWeight.Medium,letterSpacing=(-.6).sp,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=33.sp);Text(song.artist,fontSize=14.sp,color=TextBright.copy(alpha=.62f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp))};IconButton(onClick={Library.toggleFavorite(song)}){Icon(if(song.id in Library.favorites)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,tr(R.string.ui_favorites),tint=if(song.id in Library.favorites)TextBright else Muted)}}
            PlayerProgress(c)
            Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={c?.shuffleModeEnabled=!ui.shuffle}){Icon(Icons.Rounded.Shuffle,if(ui.shuffle)tr(R.string.ui_turn_shuffle_off) else tr(R.string.ui_turn_shuffle_on),tint=if(ui.shuffle)Gold else Muted)}
                IconButton(onClick={if((c?.currentPosition ?: 0)>3000)c?.seekTo(0) else c?.seekToPreviousMediaItem()}){Icon(Icons.Rounded.SkipPrevious,tr(R.string.ui_previous_song),Modifier.size(34.dp))}
                FilledIconButton(onClick=toggle,modifier=Modifier.size(76.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=TextBright,contentColor=Night)){
                    if(ui.playing && ui.buffering)CircularProgressIndicator(Modifier.size(25.dp),color=Night,strokeWidth=2.dp) else Icon(if(ui.playing && !ui.ended)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,tr(R.string.ui_play_or_pause),Modifier.size(38.dp))
                }
                IconButton(onClick={c?.seekToNextMediaItem()},enabled=ui.hasNext){Icon(Icons.Rounded.SkipNext,tr(R.string.ui_next_song),Modifier.size(34.dp))}
                IconButton(onClick={c?.repeatMode=when(ui.repeat){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}}){Icon(if(ui.repeat==Player.REPEAT_MODE_ONE)Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,tr(R.string.ui_repeat_mode, repeat),tint=if(ui.repeat==Player.REPEAT_MODE_OFF)Muted else Gold)}
            }
            Text(if(ui.playing && ui.buffering)tr(R.string.ui_buffering) else if(ui.error)tr(R.string.ui_playback_interrupted_tap_play_to_retry) else (if(ui.shuffle)tr(R.string.ui_shuffle) else "")+repeat,modifier=Modifier.fillMaxWidth().padding(top=12.dp),fontSize=11.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=12.dp),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={timer=true}){Icon(Icons.Rounded.Bedtime,null,Modifier.size(17.dp),tint=Muted);Text(if(deadline>now)tr(R.string.ui_stop_in_min, ((deadline-now+59999)/60000)) else tr(R.string.ui_sleep_timer),fontSize=12.sp,color=Muted)};TextButton(onClick=queue){Icon(Icons.Rounded.QueueMusic,null,Modifier.size(20.dp),tint=Muted);Text(tr(R.string.ui_queue, ui.count),fontSize=12.sp,color=Muted)}}
        }}
        if(landscape)Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){Artwork(song,Modifier.padding(start=28.dp).size(min(configuration.screenHeightDp*.60f,270f).dp).shadow(22.dp,RoundedCornerShape(6.dp)),6,tone=true);Box(Modifier.weight(1f).verticalScroll(rememberScrollState())){controls()}}
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=24.dp,bottom=8.dp)){
            Artwork(song,Modifier.align(Alignment.CenterHorizontally).size(min(min(configuration.screenWidthDp-56f,configuration.screenHeightDp*.37f),380f).dp).shadow(24.dp,RoundedCornerShape(6.dp)),6,tone=true)
            controls()
        }
    }
    }
    if(timer)AlertDialog(onDismissRequest={timer=false},title={Text(tr(R.string.ui_sleep_timer_232))},text={Column{listOf(15,30,45,60,90).forEach{minutes->TextButton(onClick={Library.save("sleep",JSONObject().put("deadline",System.currentTimeMillis()+minutes*60000L));timer=false},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_stop_in_minutes, minutes))}};TextButton(onClick={Library.save("sleep",JSONObject());timer=false},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_turn_timer_off))}}},confirmButton={TextButton(onClick={timer=false}){Text(tr(R.string.ui_cancel))}})
}

@Composable fun QueueScreen(c:MediaController?,events:Int,close:()->Unit){
    val songs=Library.songs.associateBy{it.id}
    val queue=remember(c,events){(0 until (c?.mediaItemCount ?: 0)).map{c!!.getMediaItemAt(it)}}
    val current=c?.currentMediaItemIndex ?: -1
    val state=rememberLazyListState(initialFirstVisibleItemIndex=current.coerceAtLeast(0))
    Column{SheetTitle(tr(R.string.ui_queue_235, queue.size),close)
        LazyColumn(state=state,modifier=Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=20.dp)){
            if(queue.isEmpty())item{EmptyState(tr(R.string.ui_your_queue_is_empty),tr(R.string.ui_choose_a_song_to_start_listening))}
            itemsIndexed(queue,key={i,item->"${item.mediaId}-$i"}){index,item->
                val song=songs[item.mediaId]
                Row(Modifier.fillMaxWidth().background(if(index==current)Panel else Night).padding(start=16.dp,end=4.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                    Row(Modifier.weight(1f).clickable{c?.seekTo(index,0);c?.prepare();c?.play()}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Artwork(song,Modifier.size(42.dp),6);Column(Modifier.padding(start=12.dp)){Text(song?.title ?: item.mediaMetadata.title.toString(),fontSize=14.sp,color=if(index==current)Gold else TextBright,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(index==current)tr(R.string.ui_now_playing) else song?.artist.orEmpty(),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}}
                    IconButton(onClick={c?.moveMediaItem(index,index-1)},enabled=index>0){Icon(Icons.Rounded.KeyboardArrowUp,tr(R.string.ui_move_item_up, index+1),Modifier.size(19.dp))}
                    IconButton(onClick={c?.moveMediaItem(index,index+1)},enabled=index<queue.lastIndex){Icon(Icons.Rounded.KeyboardArrowDown,tr(R.string.ui_move_item_down, index+1),Modifier.size(19.dp))}
                    IconButton(onClick={c?.removeMediaItem(index)}){Icon(Icons.Rounded.Close,tr(R.string.ui_remove_item, index+1),Modifier.size(17.dp),tint=Muted)}
                }
            }
        }
    }
}

@Composable fun SongSheet(song:Song,c:MediaController?,playlistContext:String,close:()->Unit,message:(String)->Unit){
    var route by remember{mutableStateOf("menu")}
    var name by remember{mutableStateOf("")}
    var reporting by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val revision=Library.revision
    BackHandler(route!="menu"){route="menu"}
    FullSheet(close){dismiss->
        fun done(text:String){message(text);dismiss()}
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom=18.dp)){
        SheetTitle(if(route=="menu")tr(R.string.ui_song_options) else tr(R.string.ui_add_to_playlist),dismiss)
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically){Artwork(song,Modifier.size(56.dp),8);Column(Modifier.padding(start=15.dp)){Text(song.title,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}}
        HorizontalDivider(Modifier.padding(horizontal=24.dp),color=Raised)
        if(route=="menu"){
            Action(if(song.id in Library.favorites)tr(R.string.ui_remove_from_favorites) else tr(R.string.ui_add_to_favorites),Icons.Rounded.FavoriteBorder){Library.toggleFavorite(song);done(if(song.id in Library.favorites)tr(R.string.ui_added_to_favorites) else tr(R.string.ui_removed_from_favorites))}
            Action(tr(R.string.ui_play_next),Icons.Rounded.PlaylistPlay){if(c!=null){c.addMediaItem((c.currentMediaItemIndex+1).coerceIn(0,c.mediaItemCount),PlaybackService.item(song));if(c.mediaItemCount==1)c.prepare();done(tr(R.string.ui_added_up_next))}else message(tr(R.string.ui_player_is_getting_ready))}
            Action(tr(R.string.ui_add_to_queue),Icons.Rounded.QueueMusic){if(c!=null){c.addMediaItem(PlaybackService.item(song));if(c.mediaItemCount==1)c.prepare();done(tr(R.string.ui_added_to_queue))}else message(tr(R.string.ui_player_is_getting_ready))}
            Action(tr(R.string.ui_add_to_playlist),Icons.Rounded.PlaylistAdd){route="playlists"}
            if(song.pool.isNotBlank())Action(if(reporting)tr(R.string.ui_excluding) else tr(R.string.ui_not_complete_music),Icons.Rounded.Flag,tr(R.string.ui_teaser_preview_or_non_music_content)){if(!reporting)scope.launch{
                reporting=true
                try{
                    val result=Library.apiAsync("pool/tracks/${song.id}/report-content","POST")
                    Library.applyContentExclusions(result)
                    if(c!=null)for(i in c.mediaItemCount-1 downTo 0)if(c.getMediaItemAt(i).mediaId==song.id)c.removeMediaItem(i)
                    Library.hideReportedTrack(song.id)
                    OfflineWorker.kick(Library.context)
                    done(tr(R.string.ui_excluded_other_music_will_be_added_automatically))
                }catch(e:CancellationException){throw e}catch(e:Exception){message(tr(R.string.ui_could_not_finish_reconnect_and_try_again))}finally{reporting=false}
            }}
            if(playlistContext.isNotBlank())Action(tr(R.string.ui_remove_from_this_playlist),Icons.Rounded.PlaylistRemove,playlistContext){Library.removeFromPlaylist(playlistContext,song);done(tr(R.string.ui_removed_from_playlist))}
            if(song.id !in Library.pinned)Action(tr(R.string.ui_keep_on_this_phone),Icons.Rounded.DownloadForOffline,if(Library.wifiAvailable)tr(R.string.ui_download_on_wi_fi_and_keep_during_automatic_cleanup) else tr(R.string.ui_download_when_connected_to_wi_fi)){Library.keep(song);done(if(Library.wifiAvailable)tr(R.string.ui_added_to_downloads) else tr(R.string.ui_queued_waiting_for_wi_fi))}
            else Action(tr(R.string.ui_stop_keeping_manually),Icons.Rounded.PushPin,tr(R.string.ui_still_eligible_for_automatic_saving)){Library.unpin(song);done(tr(R.string.ui_no_longer_kept_manually))}
            if(Library.downloaded(song) || song.id in Library.pinned)Action(tr(R.string.ui_delete_from_this_phone),Icons.Rounded.DeleteOutline,tr(R.string.ui_keep_the_server_copy_and_exclude_from_automatic_downloads)){Library.remove(song);done(tr(R.string.ui_removed_from_this_phone))}
        }else{
            Library.playlists.keys().asSequence().toList().forEach{playlist->Action(playlist,Icons.Rounded.PlaylistPlay){Library.playlist(playlist,song);done(tr(R.string.ui_added_to, playlist))}}
            OutlinedTextField(name,{name=it},label={Text(tr(R.string.ui_new_playlist_name))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=10.dp))
            Button(onClick={Library.playlist(name.trim(),song);done(tr(R.string.ui_added_to, name.trim()))},enabled=name.isNotBlank(),modifier=Modifier.padding(horizontal=24.dp)){Text(tr(R.string.ui_create_and_add))}
            TextButton(onClick={route="menu"},modifier=Modifier.padding(horizontal=14.dp)){Text(tr(R.string.ui_back_to_song_options))}
        }
    }}
}
