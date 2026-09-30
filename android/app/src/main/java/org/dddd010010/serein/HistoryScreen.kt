@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun dayLabel(day:LocalDate)=when(day){LocalDate.now()->tr(R.string.ui_today);LocalDate.now().minusDays(1)->tr(R.string.ui_yesterday);else->day.format(DateTimeFormatter.ofPattern(tr(R.string.ui_d_mmm_yyyy)))}
private fun historyStatus(entry:HistoryEntry):String {
    val listened=timeLabel((entry.seconds*1000).toLong())
    return when(entry.outcome){"completed"->tr(R.string.ui_finished, listened);"skipped"->tr(R.string.ui_skipped_heard, listened);"error"->tr(R.string.ui_interrupted_heard, listened);else->tr(R.string.ui_heard, listened)}
}

@Composable fun HistoryScreen(model:HistoryModel,close:()->Unit,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,message:(String)->Unit){
    val revision=Library.revision
    val entries=remember(revision,model.query,model.loaded){SessionOrder.merge(historyEntries()+model.loaded,{it.event},{it.seq},{it.at},{it.started})
        .filter{model.query.isBlank() || "${it.song.title} ${it.song.artist} ${it.song.album}".contains(model.query,true)}}
    val groups=entries.groupBy{Instant.ofEpochMilli((it.started*1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()}
    val scroll=rememberLazyListState()
    LaunchedEffect(Unit){model.refresh()}
    LaunchedEffect(model.query){scroll.scrollToItem(0)}
    LaunchedEffect(scroll,entries.size,model.more,model.busy,model.loadingMore,model.error){
        snapshotFlow{scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0}.collect{last->
            if(last>=scroll.layoutInfo.totalItemsCount-5)model.next()
        }
    }
    Column(Modifier.fillMaxHeight(.96f)){
        SheetTitle(tr(R.string.ui_listening_history),close)
        Text(tr(R.string.ui_revisit_every_listen),fontSize=13.sp,color=Muted,modifier=Modifier.padding(start=24.dp,bottom=20.dp,top=6.dp))
        SearchBox(model.query,model::search,tr(R.string.ui_search_songs_or_artists_you_ve_heard))
        if(model.error.isNotBlank())Text(RemoteText.message(model.error),color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=24.dp,vertical=10.dp))
        PullToRefreshBox(model.busy,model::refresh,Modifier.weight(1f)){
            LazyColumn(state=scroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
                if(entries.isEmpty() && !model.busy)item{EmptyState(if(model.query.isBlank())tr(R.string.ui_no_listening_history_yet) else tr(R.string.ui_no_matching_listens),if(model.query.isBlank())tr(R.string.ui_songs_you_actually_listen_to_appear_here_including_offline_plays) else tr(R.string.ui_try_another_song_title_or_artist))}
                groups.forEach{(day,rows)->
                    item(key="day-$day"){Text(dayLabel(day),fontSize=17.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(start=24.dp,top=24.dp,bottom=12.dp))}
                    items(rows,key={it.event}){entry->
                        val usable=Library.downloaded(entry.song) || (entry.available && Library.online)
                        Row(Modifier.fillMaxWidth().clickable{
                            if(usable)play(listOf(entry.song),0) else message(if(!entry.available)tr(R.string.ui_this_song_s_audio_is_no_longer_in_the_library) else tr(R.string.ui_connect_or_download_this_song_to_play_it))
                        }.padding(start=24.dp,end=8.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){
                            Artwork(entry.song,Modifier.size(50.dp),4)
                            Column(Modifier.weight(1f).padding(start=13.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Text(entry.song.title,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),color=if(usable)TextBright else Muted)
                                    Text(Instant.ofEpochMilli((entry.started*1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),fontSize=11.sp,color=Muted,modifier=Modifier.padding(start=10.dp))
                                }
                                Text(entry.song.artist,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
                                Text(historyStatus(entry)+(if(!entry.available && !Library.downloaded(entry.song))tr(R.string.ui_audio_unavailable) else ""),fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=5.dp))
                            }
                            IconButton(onClick={menu(entry.song)}){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_options_for, entry.song.title),tint=Muted)}
                        }
                    }
                }
                if(model.loadingMore)item{Box(Modifier.fillMaxWidth().padding(20.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)}}
                if(entries.isNotEmpty() && !model.more && model.error.isBlank())item{Text(tr(R.string.ui_you_ve_reached_your_first_listen),fontSize=11.sp,color=Muted,modifier=Modifier.padding(24.dp))}
            }
        }
    }
}
