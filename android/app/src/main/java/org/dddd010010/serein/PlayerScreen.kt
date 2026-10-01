@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class PlaybackUi(val repeat:Int,val shuffle:Boolean,val playing:Boolean,val buffering:Boolean,val ended:Boolean,val hasNext:Boolean,val count:Int,val error:Boolean)
@Composable fun playbackUi(c:MediaController?,events:Int)=remember(c,events){PlaybackUi(c?.repeatMode ?: 0,c?.shuffleModeEnabled==true,c?.playWhenReady==true,c?.playbackState==Player.STATE_BUFFERING,c?.playbackState==Player.STATE_ENDED,c?.hasNextMediaItem()==true,c?.mediaItemCount ?: 0,c?.playerError!=null)}

@Composable fun PlayerProgress(c:MediaController?,compact:Boolean=false){
    var position by remember(c){mutableLongStateOf(0L)}
    var duration by remember(c){mutableLongStateOf(1L)}
    var seeking by remember{mutableStateOf<Float?>(null)}
    LaunchedEffect(c){while(true){position=c?.currentPosition?.coerceAtLeast(0) ?: 0;val known=c?.duration ?: 0;duration=if(known>0)known else ((Library.songs.find{it.id==c?.currentMediaItem?.mediaId}?.duration ?: 0.0)*1000).toLong().coerceAtLeast(1);delay(500)}}
    val fraction=(position.toFloat()/duration).coerceIn(0f,1f)
    if(compact)LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(2.dp),color=TextBright.copy(alpha=.9f),trackColor=Color.White.copy(alpha=.12f),gapSize=0.dp,drawStopIndicator={})
    else Column{
        val colors=SliderDefaults.colors(thumbColor=TextBright,activeTrackColor=TextBright.copy(alpha=.92f),inactiveTrackColor=Color.White.copy(alpha=.22f))
        Slider(value=seeking ?: fraction,onValueChange={seeking=it},onValueChangeFinished={seeking?.let{c?.seekTo((it*duration).toLong())};seeking=null},enabled=duration>1,colors=colors,
            // The thumb only appears while seeking; the full touch height keeps the bar easy to grab.
            thumb={Box(Modifier.width(14.dp).height(40.dp),contentAlignment=Alignment.Center){if(seeking!=null)Box(Modifier.size(14.dp).background(TextBright,CircleShape))}},
            track={SliderDefaults.Track(it,modifier=Modifier.height(6.dp),colors=colors,thumbTrackGapSize=0.dp,drawStopIndicator=null)})
        val shown=seeking?.let{(it*duration).toLong()} ?: position
        Row(Modifier.fillMaxWidth()){
            Text(timeLabel(shown),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.55f),style=TabularNumbers,modifier=Modifier.weight(1f))
            Text("−"+timeLabel(duration-shown),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.55f),style=TabularNumbers)
        }
    }
}

private data class UpNext(val song:Song?,val item:MediaItem)
private fun upNext(c:MediaController?):UpNext?{
    val index=c?.nextMediaItemIndex ?: C.INDEX_UNSET
    if(c==null || index==C.INDEX_UNSET || index>=c.mediaItemCount)return null
    val item=c.getMediaItemAt(index)
    return UpNext(Library.songs.find{it.id==item.mediaId},item)
}

@Composable private fun ModeButton(icon:ImageVector,on:Boolean,description:String,click:()->Unit){
    IconButton(onClick=click,modifier=Modifier.size(52.dp).semantics{contentDescription=description}){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            Icon(icon,null,Modifier.size(24.dp),tint=if(on)TextBright else TextBright.copy(alpha=.55f))
            Box(Modifier.padding(top=3.dp).size(4.dp).background(if(on)TextBright else Color.Transparent,CircleShape))
        }
    }
}

@Composable private fun UpNextCard(next:UpNext?,count:Int,open:()->Unit,modifier:Modifier=Modifier){
    val shape=RoundedCornerShape(18.dp)
    Column(modifier.padding(horizontal=16.dp).fillMaxWidth().clip(shape).background(Glass).border(1.dp,Hairline,shape).clickable(onClick=open).padding(horizontal=14.dp,vertical=12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(tr(R.string.ui_up_next),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Secondary,modifier=Modifier.weight(1f))
            Text(tr(R.string.ui_queue,count).trim(),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Secondary)
            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(16.dp),tint=Secondary)
        }
        if(next==null)Text(tr(R.string.ui_end_of_queue),fontSize=13.sp,color=Secondary,modifier=Modifier.padding(top=8.dp))
        else Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){
            Artwork(next.song,Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                Text(next.song?.title ?: next.item.mediaMetadata.title?.toString().orEmpty(),fontFamily=EchoChineseTitle,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(next.song?.artist ?: next.item.mediaMetadata.artist?.toString().orEmpty(),fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            val seconds=next.song?.duration ?: 0.0
            if(seconds>0)Text(timeLabel((seconds*1000).toLong()),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.5f),style=TabularNumbers)
        }
    }
}

@Composable fun PlayerScreen(song:Song,c:MediaController?,events:Int,toggle:()->Unit,queue:()->Unit,menu:()->Unit,close:()->Unit){
    val configuration=LocalConfiguration.current
    val landscape=configuration.screenWidthDp>configuration.screenHeightDp
    val ui=playbackUi(c,events)
    var timer by remember{mutableStateOf(false)}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    val revision=Library.revision
    val deadline=Library.obj("sleep").optLong("deadline")
    LaunchedEffect(deadline){while(deadline>0){now=System.currentTimeMillis();delay(1000)}}
    val sleeping=deadline>now
    val repeat=when(ui.repeat){Player.REPEAT_MODE_ONE->tr(R.string.ui_repeat_one);Player.REPEAT_MODE_ALL->tr(R.string.ui_repeat_all);else->tr(R.string.ui_play_in_order)}
    val next=remember(c,events,revision){upNext(c)}
    val favorite=song.id in Library.favorites
    Box(Modifier.fillMaxSize().background(Night)){
        SongBackdrop(song,Modifier.fillMaxSize(),fade=false)
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
            val tall=maxHeight>=680.dp
            val cover=if(landscape)minOf(maxHeight*.72f,300.dp) else minOf(maxWidth-64.dp,maxHeight*.42f,420.dp)
            val topBar:@Composable ()->Unit={Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=close){Icon(Icons.Rounded.KeyboardArrowDown,tr(R.string.ui_collapse_player),Modifier.size(30.dp))}
                Spacer(Modifier.width(48.dp))
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(tr(R.string.ui_now_playing),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.72f))
                    if(sleeping)Text(tr(R.string.ui_stop_in_min,((deadline-now+59999)/60000)).trim(),fontSize=11.sp,color=Secondary,modifier=Modifier.padding(top=1.dp))
                }
                IconButton(onClick={timer=true}){Icon(if(sleeping)Icons.Rounded.Bedtime else Icons.Outlined.Bedtime,tr(R.string.ui_sleep_timer).trim(),Modifier.size(22.dp))}
                IconButton(onClick=menu){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_song_options))}
            }}
            val art:@Composable (Modifier)->Unit={m->Artwork(song,m.size(cover).shadow(32.dp,RoundedCornerShape(14.dp)),14,tone=true)}
            val info:@Composable ()->Unit={Column(Modifier.fillMaxWidth().padding(horizontal=28.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f).padding(end=10.dp)){
                        Text(song.title,fontFamily=EchoTitleFont,fontSize=23.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=30.sp)
                        Text(song.artist,fontSize=17.sp,color=TextBright.copy(alpha=.66f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))
                    }
                    GlassButton({Library.toggleFavorite(song)},size=38.dp){Icon(if(favorite)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,tr(R.string.ui_favorites),Modifier.size(19.dp),tint=TextBright)}
                }
                Box(Modifier.padding(top=14.dp)){PlayerProgress(c)}
                Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    ModeButton(Icons.Rounded.Shuffle,ui.shuffle,if(ui.shuffle)tr(R.string.ui_turn_shuffle_off) else tr(R.string.ui_turn_shuffle_on)){c?.shuffleModeEnabled=!ui.shuffle}
                    IconButton(onClick={if((c?.currentPosition ?: 0)>3000)c?.seekTo(0) else c?.seekToPreviousMediaItem()},modifier=Modifier.size(64.dp)){Icon(Icons.Rounded.SkipPrevious,tr(R.string.ui_previous_song),Modifier.size(46.dp))}
                    IconButton(onClick=toggle,modifier=Modifier.size(84.dp)){
                        if(ui.playing && ui.buffering)CircularProgressIndicator(Modifier.size(34.dp),color=TextBright,strokeWidth=3.dp)
                        else Icon(if(ui.playing && !ui.ended)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,tr(R.string.ui_play_or_pause),Modifier.size(66.dp))
                    }
                    IconButton(onClick={c?.seekToNextMediaItem()},enabled=ui.hasNext,modifier=Modifier.size(64.dp)){Icon(Icons.Rounded.SkipNext,tr(R.string.ui_next_song),Modifier.size(46.dp))}
                    ModeButton(if(ui.repeat==Player.REPEAT_MODE_ONE)Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,ui.repeat!=Player.REPEAT_MODE_OFF,tr(R.string.ui_repeat_mode,repeat)){
                        c?.repeatMode=when(ui.repeat){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}
                    }
                }
                if((ui.playing && ui.buffering) || ui.error)Text(if(ui.error)tr(R.string.ui_playback_interrupted_tap_play_to_retry) else tr(R.string.ui_buffering),modifier=Modifier.fillMaxWidth(),fontSize=12.sp,color=Secondary,textAlign=TextAlign.Center)
            }}
            if(landscape)Column(Modifier.fillMaxSize()){
                topBar()
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
                    art(Modifier.padding(start=28.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=12.dp)){info();Spacer(Modifier.height(14.dp));UpNextCard(next,ui.count,queue)}
                }
            }
            // Spread spare height between groups so taller phones never leave one empty band.
            else if(tall)Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally){
                topBar();Spacer(Modifier.weight(.6f));art(Modifier);Spacer(Modifier.weight(1f));info();Spacer(Modifier.weight(1f));UpNextCard(next,ui.count,queue);Spacer(Modifier.height(14.dp))
            }
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally){
                topBar();Spacer(Modifier.height(12.dp));art(Modifier);Spacer(Modifier.height(24.dp));info();Spacer(Modifier.height(18.dp));UpNextCard(next,ui.count,queue);Spacer(Modifier.height(14.dp))
            }
        }
    }
    if(timer)AlertDialog(onDismissRequest={timer=false},containerColor=Panel,title={Text(tr(R.string.ui_sleep_timer_232))},text={Column{listOf(15,30,45,60,90).forEach{minutes->TextButton(onClick={Library.save("sleep",JSONObject().put("deadline",System.currentTimeMillis()+minutes*60000L));timer=false},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_stop_in_minutes, minutes))}};TextButton(onClick={Library.save("sleep",JSONObject());timer=false},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_turn_timer_off))}}},confirmButton={TextButton(onClick={timer=false}){Text(tr(R.string.ui_cancel))}})
}

@Composable fun QueueScreen(c:MediaController?,events:Int,close:()->Unit){
    val songs=Library.songs.associateBy{it.id}
    val ui=playbackUi(c,events)
    val live=remember(c,events){
        val copies=mutableMapOf<Int,Int>()
        (0 until (c?.mediaItemCount ?: 0)).map{index->val item=c!!.getMediaItemAt(index);val identity=System.identityHashCode(item);val occurrence=copies.getOrDefault(identity,0);copies[identity]=occurrence+1;QueueItem("$identity:$occurrence",item)}
    }
    var rows by remember(c){mutableStateOf(live)}
    var dragged by remember{mutableStateOf<String?>(null)}
    var dragTop by remember{mutableFloatStateOf(0f)}
    var dragHeight by remember{mutableIntStateOf(0)}
    val state=rememberLazyListState(initialFirstVisibleItemIndex=(c?.currentMediaItemIndex ?: 0).coerceAtLeast(0))
    LaunchedEffect(live){rows=live;dragged=null}
    fun reorder(){
        val key=dragged ?: return
        val from=rows.indexOfFirst{it.key==key}
        val midpoint=dragTop+dragHeight/2f
        val target=state.layoutInfo.visibleItemsInfo.firstOrNull{it.key!=key && midpoint>=it.offset && midpoint<it.offset+it.size} ?: return
        val to=rows.indexOfFirst{it.key==target.key}
        if(from>=0 && to>=0)rows=rows.toMutableList().apply{add(to,removeAt(from))}
    }
    fun finish(commit:Boolean){
        val key=dragged
        if(commit && key!=null){val from=live.indexOfFirst{it.key==key};val to=rows.indexOfFirst{it.key==key};if(from>=0 && to>=0 && from!=to)c?.moveMediaItem(from,to)}
        else rows=live
        dragged=null
    }
    LaunchedEffect(dragged){while(dragged!=null){
        val info=state.layoutInfo
        val edge=dragHeight.coerceAtLeast(48)
        val distance=when{dragTop<info.viewportStartOffset+edge->-12f;dragTop+dragHeight>info.viewportEndOffset-edge->12f;else->0f}
        if(distance!=0f){state.scrollBy(distance);reorder()}
        delay(16)
    }}
    val current=live.getOrNull(c?.currentMediaItemIndex ?: -1)?.key
    Box{
        Column{
            SheetTitle(tr(R.string.ui_queue_235, rows.size).substringBefore(" ·"),close,subtitle=tr(R.string.ui_tracks_89,rows.size))
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=16.dp,top=8.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text(tr(R.string.queue_drag_hint),color=TextBright.copy(alpha=.5f),fontSize=12.sp,modifier=Modifier.weight(1f),maxLines=2)
                SecondaryPill(tr(R.string.ui_shuffle_short),{c?.shuffleModeEnabled=!ui.shuffle},icon=Icons.Rounded.Shuffle,selected=ui.shuffle,height=34.dp)
                SecondaryPill(when(ui.repeat){Player.REPEAT_MODE_ONE->tr(R.string.ui_repeat_one);Player.REPEAT_MODE_ALL->tr(R.string.ui_repeat_all);else->tr(R.string.ui_play_in_order)},
                    {c?.repeatMode=when(ui.repeat){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF}},
                    icon=if(ui.repeat==Player.REPEAT_MODE_ONE)Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,selected=ui.repeat!=Player.REPEAT_MODE_OFF,height=34.dp)
            }
            LazyColumn(state=state,modifier=Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(top=4.dp,bottom=28.dp)){
                if(rows.isEmpty())item{EmptyState(tr(R.string.ui_your_queue_is_empty),tr(R.string.ui_choose_a_song_to_start_listening))}
                itemsIndexed(rows,key={_,entry->entry.key}){index,entry->
                    val item=entry.item
                    val song=songs[item.mediaId]
                    var options by remember(entry.key){mutableStateOf(false)}
                    val isDragged=dragged==entry.key
                    val isCurrent=entry.key==current
                    val offset=state.layoutInfo.visibleItemsInfo.firstOrNull{it.key==entry.key}?.offset ?: 0
                    val shape=RoundedCornerShape(16.dp)
                    Row(Modifier.fillMaxWidth().zIndex(if(isDragged)1f else 0f)
                        .graphicsLayer{translationY=if(isDragged)dragTop-offset else 0f;if(isDragged){scaleX=1.02f;scaleY=1.02f}}
                        .then(if(isDragged)Modifier else Modifier.animateItem())
                        .padding(horizontal=8.dp)
                        .then(if(isDragged)Modifier.shadow(18.dp,shape) else Modifier)
                        .clip(shape).background(if(isDragged)Color(0xFF34363B) else if(isCurrent)GlassStrong else Color.Transparent)
                        .padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Row(Modifier.weight(1f).clickable(enabled=dragged==null){c?.seekTo(index,0);c?.prepare();c?.play()},verticalAlignment=Alignment.CenterVertically){
                            Artwork(song,Modifier.size(if(isCurrent)54.dp else 46.dp))
                            Column(Modifier.weight(1f).padding(start=14.dp,end=4.dp)){
                                if(isCurrent)Text(tr(R.string.ui_now_playing),fontSize=12.sp,fontWeight=FontWeight.Bold,color=Secondary)
                                Text(song?.title ?: item.mediaMetadata.title.toString(),fontSize=if(isCurrent)16.sp else 15.sp,fontWeight=if(isCurrent)FontWeight.ExtraBold else FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(song?.artist ?: item.mediaMetadata.artist?.toString().orEmpty(),fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            if(isCurrent)Icon(Icons.Rounded.GraphicEq,null,Modifier.size(20.dp),tint=TextBright)
                        }
                        Box{
                            IconButton(onClick={options=true},enabled=dragged==null){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_song_options),Modifier.size(20.dp),tint=TextBright.copy(alpha=.45f))}
                            DropdownMenu(expanded=options,onDismissRequest={options=false},containerColor=Panel){
                                DropdownMenuItem(text={Text(tr(R.string.queue_move_up))},enabled=index>0,onClick={options=false;c?.moveMediaItem(index,index-1)})
                                DropdownMenuItem(text={Text(tr(R.string.queue_move_down))},enabled=index<rows.lastIndex,onClick={options=false;c?.moveMediaItem(index,index+1)})
                                DropdownMenuItem(text={Text(tr(R.string.queue_remove))},onClick={options=false;c?.removeMediaItem(index)})
                            }
                        }
                        val dragLabel=tr(R.string.queue_reorder,song?.title ?: item.mediaMetadata.title.toString())
                        Box(Modifier.size(44.dp).semantics{contentDescription=dragLabel}.pointerInput(entry.key,live){
                            detectDragGesturesAfterLongPress(onDragStart={
                                val visible=state.layoutInfo.visibleItemsInfo.firstOrNull{it.key==entry.key}
                                if(visible!=null){dragTop=visible.offset.toFloat();dragHeight=visible.size;dragged=entry.key}
                            },onDragEnd={finish(true)},onDragCancel={finish(false)},onDrag={change,amount->change.consume();dragTop+=amount.y;reorder()})
                        },contentAlignment=Alignment.Center){Icon(Icons.Rounded.DragHandle,null,Modifier.size(22.dp),tint=if(isDragged)TextBright else TextBright.copy(alpha=.45f))}
                    }
                }
            }
        }
    }
}
private data class QueueItem(val key:String,val item:androidx.media3.common.MediaItem)

@Composable private fun RowScope.QuickTile(icon:ImageVector,label:String,click:()->Unit){
    Column(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp)).background(Glass).clickable(onClick=click).padding(horizontal=6.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Icon(icon,null,Modifier.size(23.dp),tint=TextBright)
        Text(label,fontSize=12.sp,fontWeight=FontWeight.Bold,maxLines=2,textAlign=TextAlign.Center,lineHeight=15.sp,modifier=Modifier.padding(top=7.dp))
    }
}

@Composable fun SongSheet(song:Song,c:MediaController?,playlistContext:String,close:()->Unit,message:(String)->Unit){
    var route by remember{mutableStateOf("menu")}
    var name by remember{mutableStateOf("")}
    var reporting by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val revision=Library.revision
    BackHandler(route!="menu"){route="menu"}
    FullSheet(close,containerColor=Color(0xFF1E1F23),fullHeight=false){dismiss->
        fun done(text:String){message(text);dismiss()}
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom=20.dp)){
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=10.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
            Artwork(song,Modifier.size(58.dp),10)
            Column(Modifier.weight(1f).padding(start=14.dp)){Text(song.title,fontSize=17.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=13.sp,color=Secondary,modifier=Modifier.padding(top=2.dp),maxLines=1,overflow=TextOverflow.Ellipsis)}
            if(route!="menu")GlassIcon(Icons.Rounded.Close,tr(R.string.ui_close),dismiss)
        }
        if(route=="menu"){
            val favorite=song.id in Library.favorites
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                QuickTile(if(favorite)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,if(favorite)tr(R.string.ui_remove_from_favorites) else tr(R.string.ui_add_to_favorites)){Library.toggleFavorite(song);done(if(song.id in Library.favorites)tr(R.string.ui_added_to_favorites) else tr(R.string.ui_removed_from_favorites))}
                QuickTile(Icons.Rounded.PlaylistPlay,tr(R.string.ui_play_next)){if(c!=null){c.addMediaItem((c.currentMediaItemIndex+1).coerceIn(0,c.mediaItemCount),PlaybackService.item(song));if(c.mediaItemCount==1)c.prepare();done(tr(R.string.ui_added_up_next))}else message(tr(R.string.ui_player_is_getting_ready))}
                QuickTile(Icons.Rounded.QueueMusic,tr(R.string.ui_add_to_queue)){if(c!=null){c.addMediaItem(PlaybackService.item(song));if(c.mediaItemCount==1)c.prepare();done(tr(R.string.ui_added_to_queue))}else message(tr(R.string.ui_player_is_getting_ready))}
            }
            GlassCard(Modifier.padding(horizontal=16.dp,vertical=12.dp)){
                CardRow(tr(R.string.ui_add_to_playlist),Icons.Rounded.PlaylistAdd,click={route="playlists"}){Chevron()}
                if(song.pool.isNotBlank()){CardDivider(51.dp);CardRow(if(reporting)tr(R.string.ui_excluding) else tr(R.string.ui_not_complete_music),Icons.Rounded.Flag,tr(R.string.ui_teaser_preview_or_non_music_content),click={if(!reporting)scope.launch{
                    reporting=true
                    try{
                        val result=Library.apiAsync("pool/tracks/${song.id}/report-content","POST")
                        Library.applyContentExclusions(result)
                        if(c!=null)for(i in c.mediaItemCount-1 downTo 0)if(c.getMediaItemAt(i).mediaId==song.id)c.removeMediaItem(i)
                        Library.hideReportedTrack(song.id)
                        OfflineWorker.kick(Library.context)
                        done(tr(R.string.ui_excluded_other_music_will_be_added_automatically))
                    }catch(e:CancellationException){throw e}catch(e:Exception){message(tr(R.string.ui_could_not_finish_reconnect_and_try_again))}finally{reporting=false}
                }})}
                if(playlistContext.isNotBlank()){CardDivider(51.dp);CardRow(tr(R.string.ui_remove_from_this_playlist),Icons.Rounded.PlaylistRemove,playlistContext,click={Library.removeFromPlaylist(playlistContext,song);done(tr(R.string.ui_removed_from_playlist))})}
                CardDivider(51.dp)
                if(song.id !in Library.pinned)CardRow(tr(R.string.ui_keep_on_this_phone),Icons.Rounded.DownloadForOffline,if(Library.wifiAvailable)tr(R.string.ui_download_on_wi_fi_and_keep_during_automatic_cleanup) else tr(R.string.ui_download_when_connected_to_wi_fi),click={Library.keep(song);done(if(Library.wifiAvailable)tr(R.string.ui_added_to_downloads) else tr(R.string.ui_queued_waiting_for_wi_fi))})
                else CardRow(tr(R.string.ui_stop_keeping_manually),Icons.Rounded.PushPin,tr(R.string.ui_still_eligible_for_automatic_saving),click={Library.unpin(song);done(tr(R.string.ui_no_longer_kept_manually))})
                if(Library.downloaded(song) || song.id in Library.pinned){CardDivider(51.dp);CardRow(tr(R.string.ui_delete_from_this_phone),Icons.Rounded.DeleteOutline,tr(R.string.ui_keep_the_server_copy_and_exclude_from_automatic_downloads),color=Danger,click={Library.remove(song);done(tr(R.string.ui_removed_from_this_phone))})}
            }
        }else{
            Text(tr(R.string.ui_add_to_playlist),fontFamily=EchoTitleFont,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=20.dp,bottom=10.dp))
            val names=Library.playlists.keys().asSequence().toList()
            if(names.isNotEmpty())GlassCard(Modifier.padding(horizontal=16.dp)){
                names.forEachIndexed{i,playlist->if(i>0)CardDivider(51.dp);CardRow(playlist,Icons.Rounded.PlaylistPlay,click={Library.playlist(playlist,song);done(tr(R.string.ui_added_to, playlist))})}
            }
            OutlinedTextField(name,{name=it},label={Text(tr(R.string.ui_new_playlist_name))},singleLine=true,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp))
            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                PrimaryPill(tr(R.string.ui_create_and_add),{Library.playlist(name.trim(),song);done(tr(R.string.ui_added_to, name.trim()))},icon=Icons.Rounded.Add,enabled=name.isNotBlank())
                TextButton(onClick={route="menu"},modifier=Modifier.padding(start=8.dp)){Text(tr(R.string.ui_back_to_song_options),color=Secondary)}
            }
        }
    }}
}
