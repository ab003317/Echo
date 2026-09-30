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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MixArtwork(mix:MixEdition,modifier:Modifier=Modifier){
    val covers=mix.songs.filter{it.hasCover}.distinctBy{it.artist+it.album.ifBlank{it.id}}.take(4)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(5.dp)).background(Panel)){
        if(covers.isEmpty()){
            Icon(Icons.Rounded.Album,null,Modifier.align(Alignment.BottomStart).padding(14.dp).size(28.dp),tint=Muted)
            Text(mix.title.take(2),fontSize=(maxWidth.value*.31f).coerceAtMost(64f).sp,fontWeight=FontWeight.Light,
                color=TextBright.copy(alpha=.75f),maxLines=1,modifier=Modifier.align(Alignment.TopStart).padding(start=14.dp,top=8.dp))
        }
        else if(covers.size<4)Artwork(covers.first(),Modifier.fillMaxSize(),0)
        else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(2.dp)){
            repeat(2){y->Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(2.dp)){
                repeat(2){x->Artwork(covers[y*2+x],Modifier.weight(1f).fillMaxHeight(),0)}
            }}
        }
    }
}

@Composable private fun SaveMix(mix:MixEdition,toggle:(MixEdition)->Unit,modifier:Modifier=Modifier){
    IconButton(onClick={toggle(mix)},modifier=modifier){Icon(if(mix.saved)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if(mix.saved)tr(R.string.ui_unsave_playlist, mix.title) else tr(R.string.ui_like_and_save_playlist, mix.title),Modifier.size(22.dp),tint=TextBright)}
}

@Composable fun MixShelf(mixes:List<MixEdition>,open:(MixEdition)->Unit,toggle:(MixEdition)->Unit){
    LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){
        items(mixes.filter{it.active},key={it.id}){mix->Column(Modifier.width(178.dp).clickable{open(mix)}){
            Box(Modifier.size(178.dp)){
                MixArtwork(mix,Modifier.fillMaxSize())
                SaveMix(mix,toggle,Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Night.copy(alpha=.78f),CircleShape))
            }
            Text(tr(R.string.ui_tracks, mix.label, mix.songs.size),color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=12.dp))
            Text(mix.title,fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,lineHeight=22.sp,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
        }}
    }
}

@Composable fun MixListRow(mix:MixEdition,open:(MixEdition)->Unit,toggle:(MixEdition)->Unit){
    Row(Modifier.fillMaxWidth().clickable{open(mix)}.padding(start=24.dp,end=8.dp,top=13.dp,bottom=13.dp),verticalAlignment=Alignment.CenterVertically){
        MixArtwork(mix,Modifier.size(94.dp))
        Column(Modifier.weight(1f).padding(start=16.dp)){
            Text(mix.label,fontSize=11.sp,color=Muted)
            Text(mix.title,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
            Text(tr(R.string.ui_tracks_89, mix.songs.size)+(if(mix.saved)tr(R.string.ui_saved) else ""),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
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
        SheetTitle(tr(R.string.ui_daily_mixes),close)
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=8.dp,top=5.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(if(hours==24)tr(R.string.ui_a_different_mix_every_day) else tr(R.string.ui_a_different_mix_every_hours, hours),fontSize=13.sp,color=Muted)
                if(next>0)Text(tr(R.string.ui_next_refresh)+Instant.ofEpochMilli((next*1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")),fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=5.dp))
            }
            Box{
                IconButton(onClick={schedule=true}){Icon(Icons.Rounded.Schedule,tr(R.string.ui_playlist_rotation),Modifier.size(21.dp),tint=Muted)}
                DropdownMenu(schedule,{schedule=false}){listOf(2,6,24).forEach{value->DropdownMenuItem(text={Text(if(value==24)tr(R.string.ui_daily) else tr(R.string.ui_every_hours, value))},onClick={schedule=false;model.configure(value)},enabled=!model.busy,leadingIcon={if(hours==value)Icon(Icons.Rounded.Check,null,Modifier.size(18.dp))})}}
            }
        }
        Row(Modifier.padding(horizontal=24.dp)){LineTab(tr(R.string.ui_this_round),!savedTab){savedTab=false};LineTab(tr(R.string.ui_saved_99),savedTab){savedTab=true}}
        LazyRow(contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(listOf("" to tr(R.string.ui_all),"artist" to tr(R.string.ui_artist),"style" to tr(R.string.ui_style),"series" to tr(R.string.ui_series),"random" to tr(R.string.ui_random))){(id,label)->
                FilterChip(selected=category==id,onClick={category=id},label={Text(label,fontSize=12.sp)})
            }
        }
        if(model.error.isNotBlank())Text(RemoteText.message(model.error),fontSize=12.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=4.dp))
        PullToRefreshBox(model.busy,model::refresh,Modifier.weight(1f)){
            LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
                if(list.isEmpty() && !model.busy)item{EmptyState(if(savedTab)tr(R.string.ui_keep_a_mix_you_love) else if(category=="style")tr(R.string.ui_style_mixes_are_getting_ready) else tr(R.string.ui_no_mixes_in_this_category_yet),
                    if(savedTab)tr(R.string.ui_tap_a_playlist_s_heart_to_keep_its_tracks_and_order_here) else if(category=="style")tr(R.string.ui_songs_are_grouped_by_how_their_audio_samples_sound) else tr(R.string.ui_explore_another_mix_new_combinations_appear_automatically))}
                items(list,key={it.id}){mix->MixListRow(mix,open,model::toggle)}
                if(list.isNotEmpty())item{Text(if(savedTab)tr(R.string.ui_these_editions_stay_here_for_you) else tr(R.string.ui_keep_your_favorites_the_rest_keep_changing),fontSize=12.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=20.dp))}
            }
        }
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
    Column(Modifier.fillMaxHeight(.96f)){
        SheetTitle(tr(R.string.ui_playlists),close)
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=26.dp)){
            item{Column(Modifier.padding(horizontal=24.dp,vertical=15.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    MixArtwork(mix,Modifier.size(124.dp))
                    Column(Modifier.weight(1f).padding(start=20.dp)){
                        Text(mix.label,fontSize=12.sp,color=Muted)
                        Text(mix.title,fontSize=25.sp,lineHeight=31.sp,fontWeight=FontWeight.Medium,maxLines=4,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=9.dp))
                        Text(tr(R.string.ui_tracks_111, mix.songs.size)+timeLabel((mix.songs.sumOf{it.duration}*1000).toLong()),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
                    }
                }
                Text(mix.description,fontSize=13.sp,lineHeight=21.sp,color=Muted,modifier=Modifier.padding(top=23.dp))
                Text(when{pending->tr(R.string.ui_waiting_to_sync, if(mix.saved)tr(R.string.ui_saved_on_this_phone) else tr(R.string.ui_unsaved));mix.saved->tr(R.string.ui_this_edition_is_saved_its_tracks_and_order_stay_the_same);else->tr(R.string.ui_like_this_mix_keep_it)},fontSize=12.sp,color=Muted,lineHeight=19.sp,modifier=Modifier.padding(top=6.dp))
                Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically){
                    Button(onClick={play(playable,0)},enabled=playable.isNotEmpty(),contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp)){
                        Icon(Icons.Rounded.PlayArrow,null,Modifier.size(21.dp));Spacer(Modifier.width(6.dp));Text(tr(R.string.ui_play_all))
                    }
                    IconButton(onClick={play(playable.shuffled(),0)},enabled=playable.isNotEmpty(),modifier=Modifier.padding(start=8.dp)){Icon(Icons.Rounded.Shuffle,tr(R.string.ui_shuffle_playlist),Modifier.size(22.dp))}
                    Spacer(Modifier.weight(1f));SaveMix(mix,model::toggle)
                }
                if(playable.size<mix.songs.size)Text(if(!Library.online)tr(R.string.ui_offline_playback_includes_downloaded_songs_only) else tr(R.string.ui_some_audio_is_being_restored_available_songs_can_play_now),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=9.dp))
            }}
            items(mix.songs,key={it.id}){song->
                SongRow(song,{
                    val at=playable.indexOfFirst{it.id==song.id}
                    if(at>=0)play(playable,at) else message(if(!Library.online)tr(R.string.ui_this_song_is_not_downloaded_connect_to_play_it) else tr(R.string.ui_this_song_s_audio_is_temporarily_unavailable))
                },{menu(song)},song.artist+(if(song.id in mix.unavailable && !Library.downloaded(song))tr(R.string.ui_audio_unavailable) else ""))
            }
        }
    }
}
