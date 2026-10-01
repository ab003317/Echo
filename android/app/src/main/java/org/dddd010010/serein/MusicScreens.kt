@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

/** Recommended songs for the listening page; the first one is the hero and also tints the page atmosphere. */
fun listenPicks(songs:List<Song>)=Library.recIds.mapNotNull{id->songs.find{it.id==id}}.ifEmpty{songs.take(30)}

@Composable fun ListenScreen(songs:List<Song>,current:Song?,refreshing:Boolean,refresh:()->Unit,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,discover:()->Unit,
    mixes:List<MixEdition>,allMixes:()->Unit,openMix:(MixEdition)->Unit,toggleMix:(MixEdition)->Unit,history:()->Unit){
    val recs=listenPicks(songs)
    val hero=recs.firstOrNull()
    val curated=recs.filter{it.id!=hero?.id && it.artist+it.album!=hero?.let{h->h.artist+h.album}}.distinctBy{it.artist+it.album}.take(18)
    val recent=songs.filter{Library.last(it)>0}.sortedByDescending{Library.last(it)}.take(8)
    PullToRefreshBox(refreshing,refresh,Modifier.fillMaxSize()){
        LazyColumn(state=rememberLazyListState(),contentPadding=PaddingValues(bottom=20.dp)){
            if(hero!=null)item{
                val width=LocalConfiguration.current.screenWidthDp-40
                val height=(width*1.1f).coerceIn(260f,460f).dp
                val tone by androidx.compose.animation.animateColorAsState(ArtworkImages.tone(hero),animationSpec=androidx.compose.animation.core.tween(650),label="listening stage")
                val shape=RoundedCornerShape(22.dp)
                Box(Modifier.padding(start=20.dp,end=20.dp,top=14.dp,bottom=6.dp).fillMaxWidth().height(height).shadow(30.dp,shape,ambientColor=tone,spotColor=tone).clip(shape).clickable{play(recs,0)}){
                    Artwork(hero,Modifier.fillMaxSize(),0,tone=true)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent,.45f to Color.Transparent,1f to Color.Black.copy(alpha=.8f))))
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start=20.dp,end=18.dp,bottom=20.dp),verticalAlignment=Alignment.Bottom){
                        Column(Modifier.weight(1f).padding(end=12.dp)){
                            Text(hero.title,fontFamily=EchoTitleFont,fontSize=30.sp,fontWeight=EchoPageWeight,maxLines=2,lineHeight=36.sp,overflow=TextOverflow.Ellipsis)
                            Text(hero.artist,fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.78f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
                        }
                        GlassButton({play(recs,0)},size=56.dp,fill=TextBright){Icon(Icons.Rounded.PlayArrow,tr(R.string.ui_start_listening),Modifier.size(32.dp),tint=Night)}
                    }
                }
            }
            item{Heading(tr(R.string.ui_daily_mixes),tr(R.string.ui_all),allMixes)}
            if(mixes.any{it.active})item{MixShelf(mixes,openMix,toggleMix)}
            else item{Text(tr(R.string.ui_new_combinations_appear_every_day),fontSize=13.sp,color=Secondary,modifier=Modifier.clickable(onClick=allMixes).padding(horizontal=20.dp,vertical=12.dp))}
            if(recs.isNotEmpty()){
                item{Heading(tr(R.string.ui_picked_for_you),tr(R.string.ui_play_all)){play(recs,0)}}
                item{LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    items(curated,key={it.id}){song->Column(Modifier.width(148.dp).clip(RoundedCornerShape(14.dp)).clickable{play(recs,recs.indexOf(song))}){
                        Artwork(song,Modifier.size(148.dp),14)
                        Text(song.title,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=9.dp))
                        Text(song.artist,fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=1.dp))
                    }}
                }}
            }
            if(recent.isNotEmpty()){item{Heading(tr(R.string.ui_listen_again),tr(R.string.ui_listening_history),history)};items(recent,key={"recent-${it.id}"}){s->SongRow(s,{play(recent,recent.indexOf(s))},{menu(s)})}}
            item{Heading(tr(R.string.ui_recently_added))}
            if(songs.isEmpty())item{EmptyState(if(refreshing)tr(R.string.ui_getting_your_library_ready) else tr(R.string.ui_start_with_a_song_you_love),if(refreshing)tr(R.string.ui_loading_songs_and_artwork) else tr(R.string.ui_search_in_discover_or_add_audio_to_your_server_s_music_folder),if(refreshing)null else tr(R.string.ui_explore_music),discover)}
            items(songs.take(20),key={"new-${it.id}"}){s->SongRow(s,{play(songs,songs.indexOf(s))},{menu(s)})}
        }
    }
}

@Composable private fun PageHeader(title:String,count:String?=null,actions:@Composable RowScope.()->Unit){
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
        Row(Modifier.weight(1f),verticalAlignment=Alignment.Bottom){
            PageTitle(title)
            if(count!=null)Text(count,fontSize=14.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.5f),modifier=Modifier.padding(start=10.dp,bottom=7.dp))
        }
        HeaderActions(actions)
    }
}

@Composable fun LibraryScreen(songs:List<Song>,play:(List<Song>,Int)->Unit,menu:(Song,String)->Unit,active:Boolean=true,
    savedMixes:List<MixEdition>,openMix:(MixEdition)->Unit,toggleMix:(MixEdition)->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    var filter by rememberSaveable{mutableStateOf("ui_songs")}
    var group by rememberSaveable{mutableStateOf("")}
    var sort by rememberSaveable{mutableStateOf("ui_recently_added")}
    var create by remember{mutableStateOf(false)}
    var rename by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var sortMenu by remember{mutableStateOf(false)}
    var searchFocused by remember{mutableStateOf(false)}
    val ime=WindowInsets.ime
    val density=androidx.compose.ui.platform.LocalDensity.current
    val header=TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState(),canScroll={!searchFocused || ime.getBottom(density)==0})
    var error by remember{mutableStateOf("")}
    val names=Library.playlists.keys().asSequence().toList()
    val playlist=Library.playlists.optJSONArray(group)
    val ids=if(playlist!=null)(0 until playlist.length()).map{playlist.getString(it)} else emptyList()
    val candidates=if(filter=="ui_playlists" && group.isNotBlank())ids.mapNotNull{id->songs.find{it.id==id}} else songs
    val filtered=candidates.filter{s->(query.isBlank() || "${s.title} ${s.artist} ${s.album}".contains(query,true)) && when(filter){
        "ui_favorites"->s.id in Library.favorites;"ui_downloaded"->Library.downloaded(s);"ui_artist"->group.isBlank() || s.artist==group;"ui_albums"->group.isBlank() || albumKey(s)==group;else->true
    }}
    val list=if(filter=="ui_playlists")filtered else when(sort){"ui_song_title"->filtered.sortedBy{it.title.lowercase()};"ui_most_played"->filtered.sortedByDescending{Library.plays(it)};else->filtered}
    val grouped=group.isBlank() && filter in listOf("ui_artist","ui_albums","ui_playlists")
    val state=rememberLazyListState()
    val criteria=listOf(filter,group,query,sort).joinToString("\u001e")
    var previousCriteria by rememberSaveable{mutableStateOf(criteria)}
    LaunchedEffect(criteria){if(previousCriteria!=criteria){state.scrollToItem(0);previousCriteria=criteria}}
    BackHandler(active && (group.isNotBlank() || query.isNotBlank())){if(group.isNotBlank())group="" else query=""}
    Column(Modifier.fillMaxSize().nestedScroll(header.nestedScrollConnection).background(Brush.verticalGradient(listOf(Color(0xFF17181C),Night),endY=900f))) {
        CollapsingHeader(header){
        PageHeader(tr(R.string.ui_library),tr(R.string.ui_tracks_89, songs.size)){GlassIcon(Icons.Rounded.PlaylistAdd,tr(R.string.ui_new_playlist),{create=true})}
        SearchBox(query,{query=it},tr(R.string.ui_search_songs_artists_and_albums),modifier=Modifier.onFocusChanged{searchFocused=it.isFocused})
        }
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(listOf("ui_songs" to tr(R.string.ui_songs),"ui_favorites" to tr(R.string.ui_favorites),"ui_playlists" to tr(R.string.ui_playlists),"ui_artist" to tr(R.string.ui_artist),"ui_albums" to tr(R.string.ui_albums),"ui_downloaded" to tr(R.string.ui_downloaded))){(id,label)->LineTab(label,filter==id){filter=id;group=""}}
        }
        if(group.isNotEmpty())Row(Modifier.padding(start=12.dp,end=10.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically){
            GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack,tr(R.string.ui_back_to_list),{group=""})
            Text(group.substringAfter('\u001f').ifBlank{tr(R.string.ui_unknown_album)},fontSize=17.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).padding(horizontal=8.dp))
            if(filter=="ui_playlists"){GlassIcon(Icons.Rounded.Edit,tr(R.string.ui_rename_playlist),{rename=true});Spacer(Modifier.width(6.dp));GlassIcon(Icons.Rounded.DeleteOutline,tr(R.string.ui_delete_playlist),{deleting=true})}
        }
        LazyColumn(state=state,modifier=Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)){
            if(grouped){
                val saved=savedMixes.filter{query.isBlank() || it.title.contains(query,true)}
                if(filter=="ui_playlists" && saved.isNotEmpty()){
                    item{Heading(tr(R.string.ui_saved_mixes))}
                    items(saved,key={"mix-${it.id}"}){mix->MixListRow(mix,openMix,toggleMix)}
                    if(names.isNotEmpty())item{Heading(tr(R.string.ui_your_playlists))}
                }
                val groups=when(filter){"ui_artist"->filtered.map{it.artist}.distinct();"ui_albums"->filtered.map{albumKey(it)}.distinct();else->names.filter{query.isBlank() || it.contains(query,true)}}
                if(groups.isEmpty() && (filter!="ui_playlists" || saved.isEmpty()))item{EmptyState(tr(R.string.ui_no_here_yet, AppLocale.named(filter)),if(filter=="ui_playlists")tr(R.string.ui_bring_songs_you_love_together_and_build_your_collection) else tr(R.string.ui_try_a_different_search),if(filter=="ui_playlists")tr(R.string.ui_create_playlist) else null){create=true}}
                items(groups,key={it}){name->
                    val members=when(filter){"ui_artist"->songs.filter{it.artist==name};"ui_albums"->songs.filter{albumKey(it)==name};else->{val a=Library.playlists.optJSONArray(name);(0 until(a?.length() ?: 0)).mapNotNull{i->songs.find{it.id==a!!.getString(i)}}}}
                    Row(Modifier.fillMaxWidth().clickable{group=name}.padding(start=20.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Artwork(members.firstOrNull(),Modifier.size(58.dp),if(filter=="ui_artist")29 else 12)
                        Column(Modifier.weight(1f).padding(start=14.dp)){Text(name.substringAfter('\u001f').ifBlank{tr(R.string.ui_unknown_album)},fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(tr(R.string.ui_tracks_89, members.size)+(if(filter=="ui_albums")" · "+name.substringBefore('\u001f') else ""),fontSize=13.sp,color=Secondary,modifier=Modifier.padding(top=2.dp))}
                        Chevron()
                    }
                }
            }else{
                item{BoxWithConstraints(Modifier.fillMaxWidth()){
                val compactActions=maxWidth<(360f*density.fontScale).dp
                Row(Modifier.fillMaxWidth().padding(start=10.dp,end=16.dp,top=2.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(1f)){TextButton(onClick={sortMenu=true},enabled=filter!="ui_playlists"){
                        Text(if(filter=="ui_playlists")tr(R.string.ui_tracks_89,list.size) else "${AppLocale.named(sort)} · ${list.size}",fontSize=14.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.8f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
                        if(filter!="ui_playlists")Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(18.dp),tint=TextBright.copy(alpha=.8f))}
                        DropdownMenu(sortMenu,{sortMenu=false},containerColor=Panel){listOf("ui_recently_added" to tr(R.string.ui_recently_added),"ui_song_title" to tr(R.string.ui_song_title),"ui_most_played" to tr(R.string.ui_most_played)).forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={sort=id;sortMenu=false})}}
                    }
                    GlassIcon(Icons.Rounded.Shuffle,tr(R.string.ui_shuffle_all),{play(list.shuffled(),0)},enabled=list.isNotEmpty())
                    Spacer(Modifier.width(4.dp))
                    if(compactActions)GlassButton({play(list,0)},size=46.dp,enabled=list.isNotEmpty(),fill=if(list.isNotEmpty())TextBright else Glass){Icon(Icons.Rounded.PlayArrow,tr(R.string.ui_play_all),Modifier.size(24.dp),tint=if(list.isNotEmpty())Night else Secondary)}
                    else PrimaryPill(tr(R.string.ui_play_all),{play(list,0)},enabled=list.isNotEmpty())
                }}}
                if(list.isEmpty())item{EmptyState(tr(R.string.ui_no_songs_yet),if(filter=="ui_favorites")tr(R.string.ui_open_a_song_s_menu_to_add_it_to_favorites) else tr(R.string.ui_try_another_search_or_add_some_music_first))}
                items(list,key={it.id}){s->SongRow(s,{play(list,list.indexOf(s))},{menu(s,if(filter=="ui_playlists")group else "")})}
            }
        }
    }
    if(create)TextDialog(tr(R.string.ui_new_playlist),tr(R.string.ui_playlist_name),{create=false}){name->if(name in names)error=tr(R.string.ui_a_playlist_with_this_name_already_exists) else {Library.playlist(name);create=false;filter="ui_playlists";group=name;query=""}}
    if(rename)TextDialog(tr(R.string.ui_rename_playlist),tr(R.string.ui_playlist_name),{rename=false},group){name->runCatching{Library.renamePlaylist(group,name)}.onSuccess{group=name;rename=false}.onFailure{error=it.message.orEmpty()}}
    if(deleting)AlertDialog(onDismissRequest={deleting=false},containerColor=Panel,title={Text(tr(R.string.ui_delete, group))},text={Text(tr(R.string.ui_only_the_playlist_is_deleted_songs_stay_in_your_library))},confirmButton={TextButton(onClick={Library.deletePlaylist(group);group="";deleting=false}){Text(tr(R.string.ui_delete_156),color=Danger)}},dismissButton={TextButton(onClick={deleting=false}){Text(tr(R.string.ui_cancel))}})
    if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},containerColor=Panel,text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text(tr(R.string.ui_ok))}})
}
private fun albumKey(s:Song)=s.artist+"\u001f"+s.album

@Composable private fun StorageCard(completed:List<Song>,waiting:List<Song>,message:(String)->Unit){
    val manual=completed.filter{it.id in Library.pinned}
    val manualBytes=manual.sumOf{it.size}
    val autoBytes=(Library.offlineBytes-manualBytes).coerceAtLeast(0)
    val budget=Library.budget.coerceAtLeast(1)
    val used=size(Library.offlineBytes)
    val shape=RoundedCornerShape(22.dp)
    Column(Modifier.padding(horizontal=16.dp,vertical=8.dp).fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(Color.White.copy(alpha=.13f),Color.White.copy(alpha=.05f)))).border(1.dp,Hairline,shape).padding(18.dp)){
        Row(verticalAlignment=Alignment.Bottom){
            Text(used.substringBefore(' '),fontSize=42.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1).sp,lineHeight=44.sp)
            Text(" "+used.substringAfter(' ')+" / ${size(Library.budget)}",fontSize=15.sp,fontWeight=FontWeight.Bold,color=Secondary,modifier=Modifier.padding(bottom=6.dp))
        }
        Row(Modifier.padding(top=16.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.White.copy(alpha=.12f))){
            val manualShare=(manualBytes.toFloat()/budget).coerceIn(0f,1f)
            val autoShare=(autoBytes.toFloat()/budget).coerceIn(0f,1f-manualShare)
            if(manualShare>0f)Box(Modifier.weight(manualShare).fillMaxHeight().background(TextBright))
            if(autoShare>0f)Box(Modifier.weight(autoShare).fillMaxHeight().background(TextBright.copy(alpha=.5f)))
            val free=1f-manualShare-autoShare
            if(free>0f)Spacer(Modifier.weight(free))
        }
        Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
            listOf(Triple(TextBright,tr(R.string.ui_kept_manually),manual.size),Triple(TextBright.copy(alpha=.5f),tr(R.string.ui_saved_automatically),completed.size-manual.size)).forEach{(color,label,count)->
                Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).background(color,CircleShape));Text(label+" "+tr(R.string.ui_tracks_89,count),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.65f),modifier=Modifier.padding(start=6.dp))}
            }
        }
        Box(Modifier.padding(vertical=14.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha=.1f)))
        Row(verticalAlignment=Alignment.CenterVertically){
            Icon(if(Library.downloadPaused)Icons.Rounded.Pause else Icons.Rounded.CloudDone,null,Modifier.size(18.dp),tint=TextBright.copy(alpha=.7f))
            Text(when{Library.downloadPaused->tr(R.string.ui_downloads_paused);!Library.wifiAvailable && waiting.isNotEmpty()->tr(R.string.ui_waiting_for_wi_fi_downloaded_music_is_ready_to_play);else->Library.status},
                fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.8f),modifier=Modifier.weight(1f).padding(horizontal=10.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
            if(Library.downloadPaused)SecondaryPill(tr(R.string.ui_resume),{Library.resumeDownloads()},icon=Icons.Rounded.PlayArrow,selected=true,height=38.dp)
            else{
                TextButton(onClick={Library.pauseDownloads()}){Text(tr(R.string.ui_pause),fontSize=13.sp,color=Secondary)}
                SecondaryPill(tr(R.string.ui_sync_now),{OfflineWorker.kick(Library.context);message(if(Library.wifiAvailable)tr(R.string.ui_syncing_offline_music) else tr(R.string.ui_scheduled_downloads_start_on_wi_fi))},icon=Icons.Rounded.Sync,height=38.dp)
            }
        }
    }
}

@Composable fun DownloadsScreen(songs:List<Song>,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,settings:()->Unit,message:(String)->Unit){
    val completed=songs.filter{Library.downloaded(it)}
    val waiting=songs.filter{!Library.downloaded(it) && (it.id in Library.pinned || it.id==Library.activeDownload || it.id in Library.partialFiles)}
    var filter by rememberSaveable{mutableStateOf("ui_all")}
    val visible=completed.filter{when(filter){"ui_kept_manually"->it.id in Library.pinned;"ui_saved_automatically"->it.id !in Library.pinned;else->true}}
    LazyColumn(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF17181C),Night),endY=900f)),state=rememberLazyListState(),contentPadding=PaddingValues(bottom=24.dp)){
        item{PageHeader(tr(R.string.ui_offline_music)){GlassIcon(Icons.Rounded.Tune,tr(R.string.ui_download_settings),settings)}}
        item{StorageCard(completed,waiting,message)}
        item{Row(Modifier.fillMaxWidth().padding(end=14.dp,top=6.dp),verticalAlignment=Alignment.CenterVertically){
            LazyRow(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf("ui_all" to tr(R.string.ui_all),"ui_kept_manually" to tr(R.string.ui_kept_manually),"ui_saved_automatically" to tr(R.string.ui_saved_automatically))){(id,label)->LineTab(label,filter==id){filter=id}}}
            GlassIcon(Icons.Rounded.PlayArrow,tr(R.string.ui_play_all),{play(visible,0)},enabled=visible.isNotEmpty())
        }}
        if(waiting.isNotEmpty() && filter!="ui_saved_automatically"){
            item{Heading(tr(R.string.ui_queued_and_downloading))}
            items(waiting,key={"waiting-${it.id}"}){song->
                val active=Library.activeDownload==song.id
                Column{SongRow(song,{menu(song)},{menu(song)},when{active->"${size(Library.downloadBytes)} / ${size(song.size)}";Library.downloadPaused->tr(R.string.ui_paused);!Library.wifiAvailable->tr(R.string.ui_waiting_for_wi_fi_172);else->tr(R.string.ui_waiting_to_download)},trailing={IconButton(onClick={Library.remove(song)}){Icon(Icons.Rounded.Close,tr(R.string.ui_cancel_download_of, song.title),tint=Secondary)}})
                    if(active)LinearProgressIndicator(progress={(Library.downloadBytes.toFloat()/song.size.coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.padding(start=82.dp,end=24.dp).fillMaxWidth().height(3.dp).clip(CircleShape),color=TextBright,trackColor=Glass,gapSize=0.dp,drawStopIndicator={})
                }
            }
        }
        if(visible.isNotEmpty())item{Heading(tr(R.string.ui_downloaded))}
        items(visible,key={it.id}){s->SongRow(s,{play(visible,visible.indexOf(s))},{menu(s)},(if(s.id in Library.pinned)tr(R.string.ui_kept_manually) else tr(R.string.ui_saved_automatically))+" · "+size(s.size))}
        if(visible.isEmpty() && waiting.isEmpty())item{EmptyState(tr(R.string.ui_take_your_music_with_you),tr(R.string.ui_keep_songs_from_their_menu_or_choose_what_to_save_automatically),tr(R.string.ui_set_up_downloads),settings)}
    }
}

@Composable fun SettingsScreen(beforeServerChange:()->Unit={},close:()->Unit){
    val revision=Library.revision
    val context=androidx.compose.ui.platform.LocalContext.current
    val version=remember(context){context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()}
    var editCapacity by remember{mutableStateOf(false)}
    var editServer by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var exclusions by remember{mutableStateOf(false)}
    var pool by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    Column{SheetTitle(tr(R.string.ui_settings),close);LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=34.dp)){
        item{SectionLabel(tr(R.string.language_label))}
        item{Segmented(listOf("zh-Hant" to "繁體中文","en" to "English"),AppLocale.language,{tag->
            var current=context
            while(current is android.content.ContextWrapper && current !is android.app.Activity)current=current.baseContext
            (current as? android.app.Activity)?.let{AppLocale.choose(it,tag)}
        },Modifier.padding(horizontal=16.dp))}
        item{SectionLabel(tr(R.string.ui_offline_music))}
        item{GlassCard(Modifier.padding(horizontal=16.dp)){
            CardRow(tr(R.string.ui_auto_save_on_wi_fi),Icons.Rounded.Wifi,tr(R.string.ui_existing_downloads_stay_when_turned_off),tile=true){EchoSwitch(Library.auto,{Library.auto=it;OfflineWorker.kick(Library.context)})}
            CardDivider(60.dp)
            Column(Modifier.padding(vertical=4.dp)){
                listOf("recent" to tr(R.string.ui_recently_played),"frequent" to tr(R.string.ui_most_played),"favorite" to tr(R.string.ui_favorites),"recommended" to tr(R.string.ui_recommended)).forEach{(id,label)->
                    val on=id in Library.sources
                    Row(Modifier.fillMaxWidth().clickable{Library.sources=if(on)Library.sources-id else Library.sources+id;OfflineWorker.kick(Library.context)}.heightIn(min=46.dp).padding(start=60.dp,end=18.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(label,modifier=Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=if(on)TextBright else TextBright.copy(alpha=.55f))
                        if(on)Icon(Icons.Rounded.Check,null,Modifier.size(20.dp),tint=TextBright)
                    }
                }
            }
            CardDivider(60.dp)
            CardRow(tr(R.string.ui_storage_limit),Icons.Rounded.SdStorage,tr(R.string.ui_includes_manual_and_automatic_downloads),tile=true,click={editCapacity=true}){Text(size(Library.budget),fontSize=15.sp,fontWeight=FontWeight.ExtraBold);Chevron()}
            Segmented(listOf(1,2,5,10,20).map{it to "$it GB"},listOf(1,2,5,10,20).firstOrNull{Library.budget==it.toLong()*1073741824},{gb->Library.budget=gb.toLong()*1073741824;OfflineWorker.kick(Library.context)},Modifier.padding(horizontal=16.dp))
            Text(tr(R.string.ui_if_manually_kept_songs_exceed_the_limit_new_downloads_pause_until),fontSize=12.sp,color=TextBright.copy(alpha=.45f),lineHeight=18.sp,modifier=Modifier.padding(start=16.dp,end=16.dp,top=10.dp,bottom=12.dp))
            CardDivider(60.dp)
            CardRow(tr(R.string.ui_excluded_from_auto_save),Icons.Rounded.Block,tr(R.string.ui_songs_excluded, Library.excluded.size),tile=true,click={exclusions=true}){Chevron()}
        }}
        item{SectionLabel(tr(R.string.ui_music_discovery))}
        item{GlassCard(Modifier.padding(horizontal=16.dp)){CardRow(tr(R.string.ui_new_music_and_listening_preferences),Icons.Rounded.AutoAwesome,tr(R.string.ui_new_music_ready_daily_songs_you_hear_stay),tile=true,click={pool=true}){Chevron()}}}
        item{SectionLabel(tr(R.string.ui_library_connection))}
        item{GlassCard(Modifier.padding(horizontal=16.dp)){
            CardRow(tr(R.string.ui_server_address),Icons.Rounded.Dns,Library.base,tile=true,click={editServer=true}){Chevron()}
            CardDivider(60.dp)
            CardRow(if(busy)tr(R.string.ui_scanning) else tr(R.string.ui_rescan_library),Icons.Rounded.Refresh,status.ifBlank{null},tile=true,click={if(!busy)scope.launch{busy=true;try{withContext(Dispatchers.IO){Library.api("scan","POST");Library.refresh()};status=tr(R.string.ui_library_updated)}catch(e:Exception){status=e.message.orEmpty()}finally{busy=false}}})
        }}
        item{Column(Modifier.fillMaxWidth().padding(top=40.dp),horizontalAlignment=Alignment.CenterHorizontally){
            BrandMark(Modifier.size(30.dp),TextBright.copy(alpha=.45f))
            Text("Echo $version",fontSize=12.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.4f),modifier=Modifier.padding(top=10.dp))
        }}
    }}
    if(editCapacity){var value by remember{mutableStateOf("%.1f".format(java.util.Locale.US,Library.budget/1073741824.0))};val gb=value.toDoubleOrNull();AlertDialog(onDismissRequest={editCapacity=false},containerColor=Panel,title={Text(tr(R.string.ui_storage_limit))},text={OutlinedTextField(value,{value=it},label={Text("GB (0.1–512)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)},confirmButton={TextButton(onClick={Library.budget=(gb!!*1073741824).toLong();OfflineWorker.kick(Library.context);editCapacity=false},enabled=gb!=null && gb in .1..512.0){Text(tr(R.string.ui_save))}},dismissButton={TextButton(onClick={editCapacity=false}){Text(tr(R.string.ui_cancel))}})}
    if(editServer){var url by remember{mutableStateOf(Library.base)};var connecting by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};AlertDialog(onDismissRequest={if(!connecting)editServer=false},containerColor=Panel,title={Text(tr(R.string.ui_library_address))},text={Column{OutlinedTextField(url,{url=it},label={Text(tr(R.string.ui_server_address))},singleLine=true);Text(tr(R.string.setup_switch_hint),fontSize=12.sp,color=Muted);if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={scope.launch{connecting=true;try{beforeServerChange();Library.changeServer(url);Library.resumeDownloads();editServer=false}catch(e:Exception){error=e.message.orEmpty()}finally{connecting=false}}},enabled=!connecting){Text(if(connecting)tr(R.string.ui_connecting) else tr(R.string.ui_connect))}},dismissButton={TextButton(onClick={editServer=false},enabled=!connecting){Text(tr(R.string.ui_cancel))}})}
    if(exclusions)AlertDialog(onDismissRequest={exclusions=false},containerColor=Panel,title={Text(tr(R.string.ui_restore_automatic_saving))},text={Text(tr(R.string.ui_allow_these_excluded_songs_to_be_downloaded_automatically_again, Library.excluded.size))},confirmButton={TextButton(onClick={Library.resetExclusions();exclusions=false}){Text(tr(R.string.ui_restore))}},dismissButton={TextButton(onClick={exclusions=false}){Text(tr(R.string.ui_cancel))}})
    if(pool)FullSheet({pool=false}){dismiss->PoolScreen(dismiss)}
}
