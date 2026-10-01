@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MixArtwork(mix:MixEdition,modifier:Modifier=Modifier,radius:Int=14){
    val covers=mix.songs.filter{it.hasCover}.distinctBy{it.artist+it.album.ifBlank{it.id}}.take(4)
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Raised)){
        if(covers.isEmpty()){
            Image(rememberRecordArtwork(mix.title),null,Modifier.fillMaxSize())
        }
        else if(covers.size<4)Artwork(covers.first(),Modifier.fillMaxSize(),0)
        else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(1.dp)){
            repeat(2){y->Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(1.dp)){
                repeat(2){x->Artwork(covers[y*2+x],Modifier.weight(1f).fillMaxHeight(),0)}
            }}
        }
    }
}

@Composable private fun SaveMix(mix:MixEdition,toggle:(MixEdition)->Unit,modifier:Modifier=Modifier,fill:Color=GlassStrong,size:Int=40){
    GlassButton({toggle(mix)},modifier,size=size.dp,fill=fill){Icon(if(mix.saved)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if(mix.saved)tr(R.string.ui_unsave_playlist, mix.title) else tr(R.string.ui_like_and_save_playlist, mix.title),Modifier.size((size/2).dp),tint=TextBright)}
}

@Composable fun MixShelf(mixes:List<MixEdition>,open:(MixEdition)->Unit,toggle:(MixEdition)->Unit){
    LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){
        items(mixes.filter{it.active},key={it.id}){mix->Column(Modifier.width(156.dp).clip(RoundedCornerShape(14.dp)).clickable{open(mix)}){
            Box(Modifier.size(156.dp)){
                MixArtwork(mix,Modifier.fillMaxSize())
                SaveMix(mix,toggle,Modifier.align(Alignment.BottomEnd),fill=Color.Black.copy(alpha=.42f),size=32)
            }
            Text(mix.title,fontFamily=EchoTitleFont,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=9.dp))
            Text(tr(R.string.ui_tracks, mix.label, mix.songs.size),color=Secondary,fontSize=13.sp,maxLines=1,modifier=Modifier.padding(top=1.dp))
        }}
    }
}

@Composable fun MixListRow(mix:MixEdition,open:(MixEdition)->Unit,toggle:(MixEdition)->Unit){
    Row(Modifier.fillMaxWidth().clickable{open(mix)}.padding(start=20.dp,end=10.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        MixArtwork(mix,Modifier.size(84.dp))
        Column(Modifier.weight(1f).padding(start=16.dp,end=6.dp)){
            Text(mix.label,fontSize=12.sp,fontWeight=FontWeight.Bold,color=Secondary)
            Text(mix.title,fontFamily=EchoTitleFont,fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))
            Text(tr(R.string.ui_tracks_89, mix.songs.size)+(if(mix.saved)tr(R.string.ui_saved) else ""),fontSize=13.sp,color=Secondary,modifier=Modifier.padding(top=4.dp))
        }
        SaveMix(mix,toggle)
    }
}

@Composable fun MixBrowser(model:MixModel,close:()->Unit,open:(MixEdition)->Unit){
    val revision=Library.revision
    val all=remember(revision){mixEditions()}
    var savedTab by rememberSaveable{mutableStateOf(false)}
    var category by rememberSaveable{mutableStateOf("")}
    var schedule by remember{mutableStateOf(false)}
    val hours=Library.obj("mixFeed").optInt("intervalHours",24)
    val next=Library.obj("mixFeed").optDouble("nextRefresh",0.0)
    val list=all.filter{(if(savedTab)it.saved else it.active) && (category.isBlank() || it.kind==category)}
    val state=rememberLazyListState()
    LaunchedEffect(Unit){model.refresh()}
    LaunchedEffect(savedTab,category){state.scrollToItem(0)}
    Column(Modifier.fillMaxHeight(.96f)){
        SheetTitle(tr(R.string.ui_daily_mixes),close,subtitle=if(hours==24)tr(R.string.ui_a_different_mix_every_day) else tr(R.string.ui_a_different_mix_every_hours, hours))
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=4.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(next>0)tr(R.string.ui_next_refresh)+Instant.ofEpochMilli((next*1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")) else "",fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.5f),modifier=Modifier.weight(1f))
            Box{
                SecondaryPill(if(hours==24)tr(R.string.ui_daily) else tr(R.string.ui_every_hours, hours),{schedule=true},icon=Icons.Rounded.Schedule,height=34.dp)
                DropdownMenu(schedule,{schedule=false},containerColor=Panel){listOf(2,6,24).forEach{value->DropdownMenuItem(text={Text(if(value==24)tr(R.string.ui_daily) else tr(R.string.ui_every_hours, value))},onClick={schedule=false;model.configure(value)},enabled=!model.busy,leadingIcon={if(hours==value)Icon(Icons.Rounded.Check,null,Modifier.size(18.dp))})}}
            }
        }
        Segmented(listOf(false to tr(R.string.ui_this_round),true to tr(R.string.ui_saved_99)),savedTab,{savedTab=it},Modifier.padding(horizontal=16.dp))
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(listOf("" to tr(R.string.ui_all),"artist" to tr(R.string.ui_artist),"style" to tr(R.string.ui_style),"series" to tr(R.string.ui_series),"random" to tr(R.string.ui_random))){(id,label)->
                LineTab(label,category==id){category=id}
            }
        }
        if(model.error.isNotBlank())Text(RemoteText.message(model.error),fontSize=12.sp,color=Secondary,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))
        PullToRefreshBox(model.busy,model::refresh,Modifier.weight(1f)){
            LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
                if(list.isEmpty() && !model.busy)item{EmptyState(if(savedTab)tr(R.string.ui_keep_a_mix_you_love) else if(category=="style")tr(R.string.ui_style_mixes_are_getting_ready) else tr(R.string.ui_no_mixes_in_this_category_yet),
                    if(savedTab)tr(R.string.ui_tap_a_playlist_s_heart_to_keep_its_tracks_and_order_here) else if(category=="style")tr(R.string.ui_songs_are_grouped_by_how_their_audio_samples_sound) else tr(R.string.ui_explore_another_mix_new_combinations_appear_automatically))}
                items(list,key={it.id}){mix->MixListRow(mix,open,model::toggle)}
                if(list.isNotEmpty())item{Text(if(savedTab)tr(R.string.ui_these_editions_stay_here_for_you) else tr(R.string.ui_keep_your_favorites_the_rest_keep_changing),fontSize=12.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(horizontal=20.dp,vertical=20.dp))}
            }
        }
    }
}

@Composable private fun NumberedSongRow(number:Int,song:Song,subtitle:String,play:()->Unit,menu:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=play).padding(start=24.dp,end=6.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){
        Text(number.toString(),fontSize=14.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.45f),modifier=Modifier.width(26.dp),style=TabularNumbers)
        Column(Modifier.weight(1f).padding(end=10.dp)){
            Text(song.title,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(subtitle,fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        if(song.duration>0)Text(timeLabel((song.duration*1000).toLong()),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.45f),style=TabularNumbers)
        IconButton(onClick=menu){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_options_for, song.title),tint=Secondary)}
    }
}

@Composable fun MixDetail(snapshot:MixEdition,model:MixModel,close:()->Unit,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,message:(String)->Unit){
    val revision=Library.revision
    var retained by remember(snapshot.id){mutableStateOf(snapshot)}
    val live=remember(revision,snapshot.id){mixEditions().find{it.id==snapshot.id}}
    val mix=live ?: retained
    SideEffect{if(live!=null)retained=live}
    val pending=Library.obj("mixSavedOutbox").has(mix.id)
    val playable=mix.songs.filter{Library.downloaded(it) || (Library.online && it.id !in mix.unavailable)}
    Box(Modifier.fillMaxHeight(.96f)){
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
            item{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.End){GlassIcon(Icons.Rounded.Close,tr(R.string.ui_close),close)}
                MixArtwork(mix,Modifier.size(232.dp).shadow(34.dp,RoundedCornerShape(20.dp)),20)
                Text(mix.label,fontSize=12.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.85f),modifier=Modifier.padding(top=18.dp).clip(CircleShape).background(GlassStrong).padding(horizontal=10.dp,vertical=3.dp))
                Text(mix.title,fontFamily=EchoTitleFont,fontSize=30.sp,lineHeight=36.sp,fontWeight=EchoPageWeight,maxLines=3,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.padding(start=24.dp,end=24.dp,top=10.dp))
                Text(tr(R.string.ui_tracks_111, mix.songs.size)+timeLabel((mix.songs.sumOf{it.duration}*1000).toLong()),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Secondary,modifier=Modifier.padding(top=6.dp))
                Row(Modifier.padding(top=18.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                    PrimaryPill(tr(R.string.ui_play_all),{play(playable,0)},enabled=playable.isNotEmpty())
                    SecondaryPill(tr(R.string.ui_shuffle_short),{play(playable.shuffled(),0)},icon=Icons.Rounded.Shuffle,enabled=playable.isNotEmpty())
                    SaveMix(mix,model::toggle,size=46)
                }
                Text(when{pending->tr(R.string.ui_waiting_to_sync, if(mix.saved)tr(R.string.ui_saved_on_this_phone) else tr(R.string.ui_unsaved));mix.saved->tr(R.string.ui_this_edition_is_saved_its_tracks_and_order_stay_the_same);else->tr(R.string.ui_like_this_mix_keep_it)},
                    fontSize=13.sp,color=TextBright.copy(alpha=.55f),lineHeight=19.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(start=24.dp,end=24.dp,top=12.dp))
                if(playable.size<mix.songs.size)Text(if(!Library.online)tr(R.string.ui_offline_playback_includes_downloaded_songs_only) else tr(R.string.ui_some_audio_is_being_restored_available_songs_can_play_now),fontSize=12.sp,color=Secondary,textAlign=TextAlign.Center,modifier=Modifier.padding(horizontal=24.dp,vertical=6.dp))
                if(mix.description.isNotBlank())Text(mix.description,fontSize=13.sp,lineHeight=21.sp,color=Secondary,modifier=Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=16.dp))
                Spacer(Modifier.height(10.dp))
            }}
            itemsIndexed(mix.songs,key={_,song->song.id}){index,song->
                NumberedSongRow(index+1,song,song.artist+(if(song.id in mix.unavailable && !Library.downloaded(song))tr(R.string.ui_audio_unavailable) else ""),{
                    val at=playable.indexOfFirst{it.id==song.id}
                    if(at>=0)play(playable,at) else message(if(!Library.online)tr(R.string.ui_this_song_is_not_downloaded_connect_to_play_it) else tr(R.string.ui_this_song_s_audio_is_temporarily_unavailable))
                },{menu(song)})
            }
        }
    }
}
