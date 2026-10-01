@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(if(Build.VERSION.SDK_INT>=33)base else AppLocale.wrap(base))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);AppLocale.changed();enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { SereinTheme { Serein() } }
    }
}

@Composable fun Serein() {
    if(!Library.ready){Box(Modifier.fillMaxSize().background(Night),contentAlignment=Alignment.Center){BrandMark(Modifier.size(72.dp))};return}
    if(Library.base.isBlank()){ServerSetup();return}
    key(Library.profile){ConnectedSerein()}
}

@Composable private fun ConnectedSerein() {
    val revision=Library.revision
    val songs=remember(revision) { Library.songs }
    val pager=rememberPagerState(pageCount={4})
    val tab=pager.currentPage
    var settings by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var mixesOpen by rememberSaveable { mutableStateOf(false) }
    var selectedMix by remember { mutableStateOf<MixEdition?>(null) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var playlistContext by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playerEvents by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf("") }
    val discovery: DiscoveryModel=viewModel(key="discovery-"+Library.profile)
    val history: HistoryModel=viewModel(key="history-"+Library.profile)
    val mixes: MixModel=viewModel(key="mixes-"+Library.profile)
    val editions=remember(revision){mixEditions()}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    var pageChange by remember { mutableStateOf<Job?>(null) }
    fun navigate(page:Int){pageChange?.cancel();pageChange=scope.launch{pager.animateScrollToPage(page)}}
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun message(text:String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    fun refresh(shuffle:Boolean=false) {
        if(refreshing)return
        scope.launch { refreshing=true;connectionError=""
            try { withContext(Dispatchers.IO) { Library.refresh(seed=if(shuffle)UUID.randomUUID().toString() else "") } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){connectionError=if(songs.isEmpty())tr(R.string.ui_library_unavailable_tap_to_retry) else tr(R.string.ui_using_your_saved_library)}
            finally{refreshing=false}
        }
    }
    DisposableEffect(Unit) {
        var active=true
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        future.addListener({if(active) runCatching{controller=future.get()}.onFailure{message(tr(R.string.ui_player_could_not_start_reopen_echo))}},ContextCompat.getMainExecutor(context))
        onDispose {active=false;MediaController.releaseFuture(future)}
    }
    DisposableEffect(controller) {
        val listener=object:Player.Listener {
            override fun onEvents(player:Player,events:Player.Events){playerEvents++}
            override fun onPlayerError(error:PlaybackException){message(if(Library.online)tr(R.string.ui_this_song_cannot_play_right_now_tap_to_retry) else tr(R.string.ui_this_song_is_not_downloaded_connect_to_play_it))}
        }
        controller?.addListener(listener)
        onDispose{controller?.removeListener(listener)}
    }
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(Library.online){if(Library.online && connectionError.isNotEmpty())refresh()}
    LifecycleResumeEffect(Unit) { Library.refreshFiles();discovery.startJobs();mixes.start(); onPauseOrDispose { discovery.stopJobs();mixes.stop() } }
    LaunchedEffect(discovery.libraryChanged){if(discovery.libraryChanged>0)refresh()}
    LaunchedEffect(discovery.playWhenReady,songs.size,controller) {
        val song=songs.find{it.id==discovery.playWhenReady}
        if(song!=null && controller!=null){controller!!.setMediaItem(PlaybackService.item(song));controller!!.prepare();controller!!.play();discovery.playWhenReady=""}
    }
    fun play(list:List<Song>,index:Int=0) {
        val c=controller ?: return message(tr(R.string.ui_player_is_getting_ready))
        val target=list.getOrNull(index) ?: return
        val playable=if(Library.online)list else list.filter{Library.available(it)}
        val at=playable.indexOfFirst{it.id==target.id}
        if(at<0)return message(tr(R.string.ui_this_song_is_not_downloaded_connect_to_play_it))
        c.setMediaItems(playable.map{PlaybackService.item(it)},at,0);c.prepare();c.play()
        if(Build.VERSION.SDK_INT>=33 && !Library.obj("permission").optBoolean("asked")) {
            Library.save("permission",org.json.JSONObject().put("asked",true),notify=false)
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun toggle(){controller?.let{c->
        if(c.playWhenReady && c.playbackState!=Player.STATE_ENDED)c.pause() else {
            if(c.playbackState==Player.STATE_ENDED)c.seekToDefaultPosition(0)
            if(c.playerError!=null || c.playbackState==Player.STATE_IDLE)c.prepare()
            c.play()
        }
    }}
    val current=remember(controller,playerEvents,revision){val id=controller?.currentMediaItem?.mediaId
        songs.find{it.id==id} ?: editions.flatMap{it.songs}.find{it.id==id} ?: historyEntries().find{it.song.id==id}?.song}
    val playAction:(List<Song>,Int)->Unit={list,index->play(list,index)}
    val menu:(Song,String)->Unit={song,from->selected=song;playlistContext=from}
    BackHandler(tab!=0 && !expanded && !settings && !queue && !historyOpen && !mixesOpen && selectedMix==null && selected==null){navigate(0)}
    Scaffold(containerColor=Night,snackbarHost={SnackbarHost(snackbar)},bottomBar={Column {
        if(current!=null) MiniPlayer(current,controller,playerEvents,{expanded=true},{toggle()})
        Row(Modifier.fillMaxWidth().background(Night).navigationBarsPadding().padding(top=8.dp,bottom=4.dp)) {
            listOf(tr(R.string.ui_listen) to Icons.Outlined.Headphones,tr(R.string.ui_discover_78) to Icons.Outlined.Explore,tr(R.string.ui_library) to Icons.Outlined.LibraryMusic,tr(R.string.ui_downloads) to Icons.Outlined.FileDownload).forEachIndexed{i,item->
                val tint by animateColorAsState(if(tab==i)TextBright else Muted,label="navigation")
                Column(Modifier.weight(1f).selectable(selected=tab==i,role=Role.Tab,onClick={navigate(i)}).padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(item.second,item.first,Modifier.size(22.dp),tint=tint)
                    Text(item.first,fontSize=11.sp,lineHeight=15.sp,fontWeight=if(tab==i)FontWeight.SemiBold else FontWeight.Normal,color=tint,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
    }}){padding->Column(Modifier.fillMaxSize().padding(padding)) {
        if(connectionError.isNotBlank()) Row(Modifier.fillMaxWidth().background(Panel).clickable{refresh()}.padding(horizontal=22.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
            Text(connectionError,color=Muted,fontSize=12.sp,modifier=Modifier.weight(1f));Text(tr(R.string.ui_retry),color=Gold,fontSize=12.sp)
        }
        HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth().weight(1f),key={it},
            userScrollEnabled=!expanded && !settings && !queue && !historyOpen && !mixesOpen && selectedMix==null && selected==null){page->
        CompositionLocalProvider(LocalArtworkActive provides (page==pager.currentPage || page==pager.targetPage)){
        Column(Modifier.fillMaxSize()){
        if(page==0)Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=2.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Echo",fontFamily=EchoLatinTitle,fontSize=23.sp,fontWeight=FontWeight.Medium,letterSpacing=(-.7).sp,modifier=Modifier.weight(1f))
            if(!Library.online) Icon(Icons.Rounded.CloudOff,tr(R.string.ui_offline),Modifier.size(18.dp),tint=Muted)
            IconButton(onClick={historyOpen=true}){Icon(Icons.Outlined.History,tr(R.string.ui_listening_history),Modifier.size(21.dp),tint=Muted)}
            IconButton(onClick={settings=true}){Icon(Icons.Outlined.Tune,tr(R.string.ui_settings),Modifier.size(21.dp),tint=Muted)}
        }
        when(page){
            0->ListenScreen(songs,current,refreshing,{refresh(true)},playAction,{s->menu(s,"")},{navigate(1)},editions,{mixesOpen=true},{selectedMix=it},mixes::toggle,{historyOpen=true})
            1->DiscoveryScreen(discovery,::message,playAction)
            2->LibraryScreen(songs,playAction,menu,active=page==pager.settledPage && !pager.isScrollInProgress && !expanded && !settings && !queue && !historyOpen && !mixesOpen && selectedMix==null && selected==null,savedMixes=editions.filter{it.saved},openMix={selectedMix=it},toggleMix=mixes::toggle)
            3->DownloadsScreen(songs,playAction,{s->menu(s,"")},{settings=true},::message)
        }
        }}}
    }}
    if(settings) FullSheet({settings=false}){dismiss->SettingsScreen(beforeServerChange={controller?.stop();controller?.clearMediaItems();androidx.work.WorkManager.getInstance(context).cancelUniqueWork("offline-sync")}){dismiss();refresh()}}
    if(current!=null) PlayerScene(expanded,{expanded=false}){PlayerScreen(current,controller,playerEvents,{toggle()},{queue=true},{menu(current,"")},{expanded=false})}
    fun closeChild(){queue=false;selected=null}
    if(queue) FullSheet(::closeChild){dismiss->QueueScreen(controller,playerEvents,dismiss)}
    if(historyOpen)FullSheet({historyOpen=false}){dismiss->HistoryScreen(history,dismiss,playAction,{menu(it,"")},::message)}
    if(mixesOpen)FullSheet({mixesOpen=false}){dismiss->MixBrowser(mixes,dismiss,{selectedMix=it})}
    selectedMix?.let{mix->FullSheet({selectedMix=null}){dismiss->MixDetail(mix,mixes,dismiss,playAction,{menu(it,"")},::message)}}
    selected?.let{s->SongSheet(s,controller,playlistContext,::closeChild,::message)}
}

@Composable fun MiniPlayer(song:Song,c:MediaController?,events:Int,open:()->Unit,toggle:()->Unit){
    val ui=playbackUi(c,events)
    Column(Modifier.padding(horizontal=12.dp).clip(RoundedCornerShape(10.dp)).background(Panel).border(.5.dp,TextBright.copy(alpha=.08f),RoundedCornerShape(10.dp))){
        Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(start=7.dp,end=2.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
            Artwork(song,Modifier.size(38.dp),4,priority=true)
            Column(Modifier.weight(1f).padding(horizontal=11.dp)){Text(song.title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))}
            IconButton(onClick=toggle,modifier=Modifier.semantics{contentDescription=tr(R.string.ui_play_or_pause)}){if(ui.playing && ui.buffering)CircularProgressIndicator(Modifier.size(20.dp),color=Gold,strokeWidth=2.dp) else Icon(if(ui.playing && !ui.ended)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null,tint=TextBright)}
            IconButton(onClick={c?.seekToNextMediaItem()},enabled=ui.hasNext){Icon(Icons.Rounded.SkipNext,tr(R.string.ui_next_song))}
        }
        PlayerProgress(c,compact=true)
    }
}
