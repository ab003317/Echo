@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

@Composable fun ListenScreen(songs:List<Song>,current:Song?,refreshing:Boolean,refresh:()->Unit,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,discover:()->Unit,
    mixes:List<MixEdition>,allMixes:()->Unit,openMix:(MixEdition)->Unit,toggleMix:(MixEdition)->Unit,history:()->Unit){
    val recs=Library.recIds.mapNotNull{id->songs.find{it.id==id}}.ifEmpty{songs.take(30)}
    val hero=recs.firstOrNull()
    val curated=recs.filter{it.id!=hero?.id && it.artist+it.album!=hero?.let{h->h.artist+h.album}}.distinctBy{it.artist+it.album}.take(18)
    val recent=songs.filter{Library.last(it)>0}.sortedByDescending{Library.last(it)}.take(8)
    PullToRefreshBox(refreshing,refresh,Modifier.fillMaxSize()){
        LazyColumn(state=rememberLazyListState(),contentPadding=PaddingValues(bottom=20.dp)){
            if(hero!=null)item{
                val sleeve=(LocalConfiguration.current.screenWidthDp*.70f).coerceIn(200f,320f).dp
                val tone by androidx.compose.animation.animateColorAsState(ArtworkImages.tone(hero),animationSpec=androidx.compose.animation.core.tween(650),label="listening stage")
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Night,lerp(Night,tone,.24f),Night))).padding(top=12.dp,bottom=4.dp)){
                    Box(Modifier.align(Alignment.CenterHorizontally).size(sleeve).clip(RoundedCornerShape(8.dp)).clickable{play(recs,0)}){
                        Artwork(hero,Modifier.fillMaxSize(),8,tone=true)
                    }
                    Row(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=22.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).padding(end=16.dp)){
                            Text(hero.title,fontFamily=EchoTitleFont,fontSize=30.sp,fontWeight=EchoPageWeight,letterSpacing=0.sp,maxLines=2,lineHeight=38.sp,overflow=TextOverflow.Ellipsis)
                            Text(hero.artist,fontSize=14.sp,color=TextBright.copy(alpha=.72f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
                        }
                        FilledIconButton(onClick={play(recs,0)},modifier=Modifier.size(54.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=TextBright,contentColor=Night)){
                            Icon(Icons.Rounded.PlayArrow,tr(R.string.ui_start_listening),Modifier.size(30.dp))
                        }
                    }
                }
            }
            item{Heading(tr(R.string.ui_daily_mixes),tr(R.string.ui_all),allMixes)}
            if(mixes.any{it.active})item{MixShelf(mixes,openMix,toggleMix)}
            else item{Text(tr(R.string.ui_new_combinations_appear_every_day),fontSize=13.sp,color=Muted,modifier=Modifier.clickable(onClick=allMixes).padding(horizontal=24.dp,vertical=12.dp))}
            if(recs.isNotEmpty()){
                item{Heading(tr(R.string.ui_picked_for_you),tr(R.string.ui_play_all)){play(recs,0)}}
                item{LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    items(curated,key={it.id}){song->Column(Modifier.width(148.dp).clickable{play(recs,recs.indexOf(song))}){
                        Artwork(song,Modifier.size(148.dp),4)
                        Text(song.title,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp))
                        Text(song.artist,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
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
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
            PageTitle(tr(R.string.ui_library),Modifier.weight(1f));Text(tr(R.string.ui_tracks_89, songs.size),fontSize=12.sp,color=Muted,modifier=Modifier.padding(end=12.dp))
            HeaderActions{IconButton(onClick={create=true}){Icon(Icons.Rounded.PlaylistAdd,tr(R.string.ui_new_playlist),Modifier.size(22.dp))}}
        }
        SearchBox(query,{query=it},tr(R.string.ui_search_songs_artists_and_albums))
        LazyRow(contentPadding=PaddingValues(horizontal=22.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
            items(listOf("ui_songs" to tr(R.string.ui_songs),"ui_favorites" to tr(R.string.ui_favorites),"ui_playlists" to tr(R.string.ui_playlists),"ui_artist" to tr(R.string.ui_artist),"ui_albums" to tr(R.string.ui_albums),"ui_downloaded" to tr(R.string.ui_downloaded))){(id,label)->LineTab(label,filter==id){filter=id;group=""}}
        }
        if(group.isNotEmpty())Row(Modifier.padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={group=""}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,tr(R.string.ui_back_to_list))}
            Text(group.substringAfter('\u001f').ifBlank{tr(R.string.ui_unknown_album)},fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            if(filter=="ui_playlists"){IconButton(onClick={rename=true}){Icon(Icons.Rounded.Edit,tr(R.string.ui_rename_playlist),Modifier.size(20.dp))};IconButton(onClick={deleting=true}){Icon(Icons.Rounded.DeleteOutline,tr(R.string.ui_delete_playlist),Modifier.size(20.dp))}}
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
                    Row(Modifier.fillMaxWidth().clickable{group=name}.padding(horizontal=22.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
                        Artwork(members.firstOrNull(),Modifier.size(62.dp),if(filter=="ui_artist")31 else 10)
                        Column(Modifier.weight(1f).padding(start=14.dp)){Text(name.substringAfter('\u001f').ifBlank{tr(R.string.ui_unknown_album)},fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis);Text(tr(R.string.ui_tracks_89, members.size)+(if(filter=="ui_albums")" · "+name.substringBefore('\u001f') else ""),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)
                    }
                }
            }else{
                item{Row(Modifier.fillMaxWidth().padding(start=22.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(1f)){TextButton(onClick={sortMenu=true},enabled=filter!="ui_playlists"){Text(if(filter=="ui_playlists")tr(R.string.ui_tracks_added_order, list.size) else tr(R.string.ui_tracks, AppLocale.named(sort), list.size),fontSize=12.sp);if(filter!="ui_playlists")Icon(Icons.Rounded.KeyboardArrowDown,null,Modifier.size(15.dp))}
                        DropdownMenu(sortMenu,{sortMenu=false}){listOf("ui_recently_added" to tr(R.string.ui_recently_added),"ui_song_title" to tr(R.string.ui_song_title),"ui_most_played" to tr(R.string.ui_most_played)).forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={sort=id;sortMenu=false})}}
                    }
                    TextButton(onClick={play(list,0)},enabled=list.isNotEmpty()){Icon(Icons.Rounded.PlayArrow,null,Modifier.size(18.dp));Text(tr(R.string.ui_play_all),fontSize=12.sp)}
                }}
                if(list.isEmpty())item{EmptyState(tr(R.string.ui_no_songs_yet),if(filter=="ui_favorites")tr(R.string.ui_open_a_song_s_menu_to_add_it_to_favorites) else tr(R.string.ui_try_another_search_or_add_some_music_first))}
                items(list,key={it.id}){s->SongRow(s,{play(list,list.indexOf(s))},{menu(s,if(filter=="ui_playlists")group else "")})}
            }
        }
    }
    if(create)TextDialog(tr(R.string.ui_new_playlist),tr(R.string.ui_playlist_name),{create=false}){name->if(name in names)error=tr(R.string.ui_a_playlist_with_this_name_already_exists) else {Library.playlist(name);create=false;filter="ui_playlists";group=name;query=""}}
    if(rename)TextDialog(tr(R.string.ui_rename_playlist),tr(R.string.ui_playlist_name),{rename=false},group){name->runCatching{Library.renamePlaylist(group,name)}.onSuccess{group=name;rename=false}.onFailure{error=it.message.orEmpty()}}
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text(tr(R.string.ui_delete, group))},text={Text(tr(R.string.ui_only_the_playlist_is_deleted_songs_stay_in_your_library))},confirmButton={TextButton(onClick={Library.deletePlaylist(group);group="";deleting=false}){Text(tr(R.string.ui_delete_156))}},dismissButton={TextButton(onClick={deleting=false}){Text(tr(R.string.ui_cancel))}})
    if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text(tr(R.string.ui_ok))}})
}
private fun albumKey(s:Song)=s.artist+"\u001f"+s.album

@Composable fun DownloadsScreen(songs:List<Song>,play:(List<Song>,Int)->Unit,menu:(Song)->Unit,settings:()->Unit,message:(String)->Unit){
    val completed=songs.filter{Library.downloaded(it)}
    val waiting=songs.filter{!Library.downloaded(it) && (it.id in Library.pinned || it.id==Library.activeDownload || it.id in Library.partialFiles)}
    var filter by rememberSaveable{mutableStateOf("ui_all")}
    LazyColumn(state=rememberLazyListState(),contentPadding=PaddingValues(bottom=24.dp)){
        item{Row(Modifier.fillMaxWidth().padding(start=24.dp,end=14.dp,top=12.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){PageTitle(tr(R.string.ui_offline_music),Modifier.weight(1f));TextButton(onClick=settings){Text(tr(R.string.ui_download_settings),fontSize=12.sp,color=Muted)}}}
        item{Column(Modifier.padding(horizontal=24.dp).fillMaxWidth().padding(vertical=12.dp)){
            Row(verticalAlignment=Alignment.Bottom){Text(size(Library.offlineBytes),fontFamily=EchoTitleFont,fontSize=36.sp,fontWeight=FontWeight.Normal,letterSpacing=0.sp);Text(" / ${size(Library.budget)}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(bottom=6.dp))}
            LinearProgressIndicator(progress={(Library.offlineBytes.toFloat()/Library.budget).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=14.dp).height(3.dp),color=Gold,trackColor=Raised)
            Text(when{Library.downloadPaused->tr(R.string.ui_downloads_paused);!Library.wifiAvailable && waiting.isNotEmpty()->tr(R.string.ui_waiting_for_wi_fi_downloaded_music_is_ready_to_play);else->Library.status},fontSize=12.sp,color=Muted)
            Row(Modifier.padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={if(Library.downloadPaused)Library.resumeDownloads() else {OfflineWorker.kick(Library.context);message(if(Library.wifiAvailable)tr(R.string.ui_syncing_offline_music) else tr(R.string.ui_scheduled_downloads_start_on_wi_fi))}}){Icon(if(Library.downloadPaused)Icons.Rounded.PlayArrow else Icons.Rounded.Sync,null,Modifier.size(17.dp));Text(if(Library.downloadPaused)tr(R.string.ui_resume) else tr(R.string.ui_sync_now),fontSize=12.sp)}
                if(!Library.downloadPaused)TextButton(onClick={Library.pauseDownloads()}){Text(tr(R.string.ui_pause),fontSize=12.sp)}
                Spacer(Modifier.weight(1f));Text(tr(R.string.ui_tracks_89, completed.size),fontSize=12.sp,color=Muted)
            }
        }}
        item{LazyRow(contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp)){items(listOf("ui_all" to tr(R.string.ui_all),"ui_kept_manually" to tr(R.string.ui_kept_manually),"ui_saved_automatically" to tr(R.string.ui_saved_automatically))){(id,label)->LineTab(label,filter==id){filter=id}}}}
        if(waiting.isNotEmpty() && filter!="ui_saved_automatically"){
            item{Heading(tr(R.string.ui_queued_and_downloading))}
            items(waiting,key={"waiting-${it.id}"}){song->
                val active=Library.activeDownload==song.id
                Column{SongRow(song,{menu(song)},{menu(song)},when{active->"${size(Library.downloadBytes)} / ${size(song.size)}";Library.downloadPaused->tr(R.string.ui_paused);!Library.wifiAvailable->tr(R.string.ui_waiting_for_wi_fi_172);else->tr(R.string.ui_waiting_to_download)},trailing={IconButton(onClick={Library.remove(song)}){Icon(Icons.Rounded.Close,tr(R.string.ui_cancel_download_of, song.title))}})
                    if(active)LinearProgressIndicator(progress={(Library.downloadBytes.toFloat()/song.size.coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.padding(start=84.dp,end=24.dp).fillMaxWidth().height(2.dp),trackColor=Panel)
                }
            }
        }
        val visible=completed.filter{when(filter){"ui_kept_manually"->it.id in Library.pinned;"ui_saved_automatically"->it.id !in Library.pinned;else->true}}
        if(visible.isNotEmpty())item{Heading(tr(R.string.ui_downloaded),tr(R.string.ui_play_all)){play(visible,0)}}
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
    Column{SheetTitle(tr(R.string.ui_settings),close);LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=30.dp)){
        item{Heading(tr(R.string.language_label))}
        item{Row(Modifier.fillMaxWidth().padding(start=22.dp,end=22.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            listOf("zh-Hant" to "繁體中文","en" to "English").forEach{(tag,label)->
                FilterChip(selected=AppLocale.language==tag,onClick={
                    var current=context
                    while(current is android.content.ContextWrapper && current !is android.app.Activity)current=current.baseContext
                    (current as? android.app.Activity)?.let{AppLocale.choose(it,tag)}
                },label={Text(label)},leadingIcon={if(AppLocale.language==tag)Icon(Icons.Rounded.Check,null,Modifier.size(17.dp))})
            }
        }}
        item{Heading(tr(R.string.ui_offline_music))}
        item{Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr(R.string.ui_auto_save_on_wi_fi),fontSize=15.sp);Text(tr(R.string.ui_existing_downloads_stay_when_turned_off),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=4.dp))};Switch(Library.auto,{Library.auto=it;OfflineWorker.kick(Library.context)})}}
        items(listOf("recent" to tr(R.string.ui_recently_played),"frequent" to tr(R.string.ui_most_played),"favorite" to tr(R.string.ui_favorites),"recommended" to tr(R.string.ui_recommended))){(id,label)->
            Row(Modifier.fillMaxWidth().clickable{Library.sources=if(id in Library.sources)Library.sources-id else Library.sources+id;OfflineWorker.kick(Library.context)}.padding(horizontal=22.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f),fontSize=14.sp);Checkbox(id in Library.sources,{Library.sources=if(it)Library.sources+id else Library.sources-id;OfflineWorker.kick(Library.context)})}
        }
        item{HorizontalDivider(Modifier.padding(horizontal=22.dp,vertical=16.dp),color=Raised)}
        item{Row(Modifier.fillMaxWidth().clickable{editCapacity=true}.padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr(R.string.ui_storage_limit),fontSize=15.sp);Text(tr(R.string.ui_includes_manual_and_automatic_downloads),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=4.dp))};Text(size(Library.budget),color=Gold,fontSize=14.sp);Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=Muted)}}
        item{LazyRow(contentPadding=PaddingValues(horizontal=22.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(1,2,5,10,20)){gb->FilterChip(Library.budget==gb.toLong()*1073741824,onClick={Library.budget=gb.toLong()*1073741824;OfflineWorker.kick(Library.context)},label={Text("$gb GB",fontSize=12.sp)})}}}
        item{Text(tr(R.string.ui_if_manually_kept_songs_exceed_the_limit_new_downloads_pause_until),fontSize=12.sp,color=Muted,lineHeight=19.sp,modifier=Modifier.padding(horizontal=22.dp,vertical=14.dp))}
        item{Action(tr(R.string.ui_excluded_from_auto_save),Icons.Rounded.Block,tr(R.string.ui_songs_excluded, Library.excluded.size)){exclusions=true}}
        item{Heading(tr(R.string.ui_music_discovery))}
        item{Action(tr(R.string.ui_new_music_and_listening_preferences),Icons.Rounded.AutoAwesome,tr(R.string.ui_new_music_ready_daily_songs_you_hear_stay)){pool=true}}
        item{Heading(tr(R.string.ui_library_connection))}
        item{Action(tr(R.string.ui_server_address),Icons.Rounded.Dns,Library.base){editServer=true}}
        item{Action(if(busy)tr(R.string.ui_scanning) else tr(R.string.ui_rescan_library),Icons.Rounded.Refresh){if(!busy)scope.launch{busy=true;try{withContext(Dispatchers.IO){Library.api("scan","POST");Library.refresh()};status=tr(R.string.ui_library_updated)}catch(e:Exception){status=e.message.orEmpty()}finally{busy=false}}}}
        if(status.isNotBlank())item{Text(status,fontSize=12.sp,color=Muted,modifier=Modifier.padding(horizontal=22.dp,vertical=12.dp))}
        item{Text("Echo $version",fontSize=12.sp,color=Muted,modifier=Modifier.padding(22.dp))}
    }}
    if(editCapacity){var value by remember{mutableStateOf("%.1f".format(java.util.Locale.US,Library.budget/1073741824.0))};val gb=value.toDoubleOrNull();AlertDialog(onDismissRequest={editCapacity=false},title={Text(tr(R.string.ui_storage_limit))},text={OutlinedTextField(value,{value=it},label={Text("GB (0.1–512)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)},confirmButton={TextButton(onClick={Library.budget=(gb!!*1073741824).toLong();OfflineWorker.kick(Library.context);editCapacity=false},enabled=gb!=null && gb in .1..512.0){Text(tr(R.string.ui_save))}},dismissButton={TextButton(onClick={editCapacity=false}){Text(tr(R.string.ui_cancel))}})}
    if(editServer){var url by remember{mutableStateOf(Library.base)};var connecting by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};AlertDialog(onDismissRequest={if(!connecting)editServer=false},title={Text(tr(R.string.ui_library_address))},text={Column{OutlinedTextField(url,{url=it},label={Text(tr(R.string.ui_server_address))},singleLine=true);Text(tr(R.string.setup_switch_hint),fontSize=12.sp,color=Muted);if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={scope.launch{connecting=true;try{beforeServerChange();Library.changeServer(url);Library.resumeDownloads();editServer=false}catch(e:Exception){error=e.message.orEmpty()}finally{connecting=false}}},enabled=!connecting){Text(if(connecting)tr(R.string.ui_connecting) else tr(R.string.ui_connect))}},dismissButton={TextButton(onClick={editServer=false},enabled=!connecting){Text(tr(R.string.ui_cancel))}})}
    if(exclusions)AlertDialog(onDismissRequest={exclusions=false},title={Text(tr(R.string.ui_restore_automatic_saving))},text={Text(tr(R.string.ui_allow_these_excluded_songs_to_be_downloaded_automatically_again, Library.excluded.size))},confirmButton={TextButton(onClick={Library.resetExclusions();exclusions=false}){Text(tr(R.string.ui_restore))}},dismissButton={TextButton(onClick={exclusions=false}){Text(tr(R.string.ui_cancel))}})
    if(pool)FullSheet({pool=false}){dismiss->PoolScreen(dismiss)}
}
