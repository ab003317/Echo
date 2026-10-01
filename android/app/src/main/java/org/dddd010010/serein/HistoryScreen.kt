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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
        SheetTitle(tr(R.string.ui_listening_history),close,subtitle=tr(R.string.ui_revisit_every_listen))
        Spacer(Modifier.height(10.dp))
        SearchBox(model.query,model::search,tr(R.string.ui_search_songs_or_artists_you_ve_heard))
        if(model.error.isNotBlank())Text(RemoteText.message(model.error),color=Secondary,fontSize=12.sp,modifier=Modifier.padding(horizontal=20.dp,vertical=10.dp))
        PullToRefreshBox(model.busy,model::refresh,Modifier.weight(1f)){
            LazyColumn(state=scroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
                if(entries.isEmpty() && !model.busy)item{EmptyState(if(model.query.isBlank())tr(R.string.ui_no_listening_history_yet) else tr(R.string.ui_no_matching_listens),if(model.query.isBlank())tr(R.string.ui_songs_you_actually_listen_to_appear_here_including_offline_plays) else tr(R.string.ui_try_another_song_title_or_artist))}
                groups.forEach{(day,rows)->
                    item(key="day-$day"){Text(dayLabel(day),fontFamily=EchoTitleFont,fontSize=20.sp,fontWeight=EchoPageWeight,modifier=Modifier.padding(start=20.dp,top=24.dp,bottom=6.dp))}
                    items(rows,key={it.event}){entry->HistoryRow(entry,play,menu,message)}
                }
                if(model.loadingMore)item{Box(Modifier.fillMaxWidth().padding(20.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(22.dp),color=TextBright,strokeWidth=2.dp)}}
                if(entries.isNotEmpty() && !model.more && model.error.isBlank())item{Text(tr(R.string.ui_you_ve_reached_your_first_listen),fontSize=12.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(20.dp))}
            }
        }
    }
}

@Composable private fun HistoryRow(entry:HistoryEntry,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,message:(String)->Unit){
    val usable=Library.downloaded(entry.song) || (entry.available && Library.online)
    val quiet=entry.outcome in listOf("skipped","error")
    val listened=(entry.seconds/entry.song.duration.coerceAtLeast(1.0)).toFloat().coerceIn(0f,1f)
    Row(Modifier.fillMaxWidth().clickable{
        if(usable)play(listOf(entry.song),0) else message(if(!entry.available)tr(R.string.ui_this_song_s_audio_is_no_longer_in_the_library) else tr(R.string.ui_connect_or_download_this_song_to_play_it))
    }.padding(start=20.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        Artwork(entry.song,Modifier.size(50.dp).graphicsLayer{alpha=if(quiet || !usable).55f else 1f},9)
        Column(Modifier.weight(1f).padding(start=14.dp,end=8.dp)){
            Text(entry.song.title,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(usable && !quiet)TextBright else TextBright.copy(alpha=.72f))
            Text(entry.song.artist+(if(!entry.available && !Library.downloaded(entry.song))tr(R.string.ui_audio_unavailable) else ""),fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
            // How much of the song was actually heard, at a glance.
            Row(Modifier.padding(top=5.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.width(110.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha=.14f))){
                    Box(Modifier.fillMaxHeight().fillMaxWidth(listened).background(if(quiet)TextBright.copy(alpha=.5f) else TextBright.copy(alpha=.8f)))
                }
                Text(historyStatus(entry).substringAfterLast("· ").trim(),fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.5f),modifier=Modifier.padding(start=8.dp),maxLines=1)
            }
        }
        Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(Instant.ofEpochMilli((entry.started*1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.5f),style=TabularNumbers)
            when(entry.outcome){
                "completed"->StatusPill(tr(R.string.history_pill_finished),1)
                "skipped"->StatusPill(tr(R.string.history_pill_skipped),0)
                "error"->StatusPill(tr(R.string.history_pill_interrupted),0)
                else->StatusPill(tr(R.string.history_pill_partial),1)
            }
        }
        IconButton(onClick={menu(entry.song)}){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_options_for, entry.song.title),tint=TextBright.copy(alpha=.45f))}
    }
}
