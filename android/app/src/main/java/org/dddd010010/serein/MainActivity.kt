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
import androidx.compose.ui.draw.shadow
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
    Scaffold(containerColor=Night,snackbarHost={SnackbarHost(snackbar)},bottomBar={Column(Modifier.background(Night)) {
        if(current!=null) MiniPlayer(current,controller,playerEvents,{expanded=true},{toggle()})
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top=4.dp,bottom=2.dp)) {
            listOf(Triple(tr(R.string.ui_listen),Icons.Outlined.Headphones,Icons.Rounded.Headphones),Triple(tr(R.string.ui_discover_78),Icons.Outlined.Explore,Icons.Rounded.Explore),
                Triple(tr(R.string.ui_library),Icons.Outlined.LibraryMusic,Icons.Rounded.LibraryMusic),Triple(tr(R.string.ui_downloads),Icons.Outlined.FileDownload,Icons.Rounded.DownloadForOffline)).forEachIndexed{i,(label,idle,active)->
                val tint by animateColorAsState(if(tab==i)TextBright else TextBright.copy(alpha=.5f),label="navigation")
                Column(Modifier.weight(1f).selectable(selected=tab==i,role=Role.Tab,onClick={navigate(i)}).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(if(tab==i)active else idle,label,Modifier.size(25.dp),tint=tint)
                    Text(label,fontSize=11.sp,lineHeight=15.sp,fontWeight=if(tab==i)FontWeight.Bold else FontWeight.SemiBold,color=tint,modifier=Modifier.padding(top=3.dp))
                }
            }
        }
    }}){padding->Column(Modifier.fillMaxSize().padding(padding)) {
        if(connectionError.isNotBlank()) Row(Modifier.fillMaxWidth().background(Glass).clickable{refresh()}.padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Text(connectionError,color=Secondary,fontSize=12.sp,modifier=Modifier.weight(1f));Text(tr(R.string.ui_retry),color=TextBright,fontWeight=FontWeight.Bold,fontSize=12.sp)
        }
        HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth().weight(1f),key={it},
            userScrollEnabled=!expanded && !settings && !queue && !historyOpen && !mixesOpen && selectedMix==null && selected==null){page->
        CompositionLocalProvider(LocalArtworkActive provides (page==pager.currentPage || page==pager.targetPage)){
        Box(Modifier.fillMaxSize()){
        if(page==0)SongBackdrop(listenPicks(songs).firstOrNull(),Modifier.fillMaxWidth().height(640.dp))
        Column(Modifier.fillMaxSize()){
        if(page==0)Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=10.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            BrandMark(Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            EchoWordmark(Modifier.weight(1f))
            if(!Library.online) Icon(Icons.Rounded.CloudOff,tr(R.string.ui_offline),Modifier.padding(end=8.dp).size(18.dp),tint=Secondary)
            HeaderActions{
                GlassIcon(Icons.Rounded.History,tr(R.string.ui_listening_history),{historyOpen=true})
                GlassIcon(Icons.Rounded.Tune,tr(R.string.ui_settings),{settings=true})
            }
        }
        when(page){
            0->ListenScreen(songs,current,refreshing,{refresh(true)},playAction,{s->menu(s,"")},{navigate(1)},editions,{mixesOpen=true},{selectedMix=it},mixes::toggle,{historyOpen=true})
            1->DiscoveryScreen(discovery,::message,playAction)
            2->LibraryScreen(songs,playAction,menu,active=page==pager.settledPage && !pager.isScrollInProgress && !expanded && !settings && !queue && !historyOpen && !mixesOpen && selectedMix==null && selected==null,savedMixes=editions.filter{it.saved},openMix={selectedMix=it},toggleMix=mixes::toggle)
            3->DownloadsScreen(songs,playAction,{s->menu(s,"")},{settings=true},::message)
        }
        }}}}
    }}
    if(settings) FullSheet({settings=false}){dismiss->SettingsScreen(beforeServerChange={controller?.stop();controller?.clearMediaItems();androidx.work.WorkManager.getInstance(context).cancelUniqueWork("offline-sync")}){dismiss();refresh()}}
    if(current!=null) PlayerScene(expanded,{expanded=false}){PlayerScreen(current,controller,playerEvents,{toggle()},{queue=true},{menu(current,"")},{expanded=false})}
    fun closeChild(){queue=false;selected=null}
    if(queue) FullSheet(::closeChild,backdrop={SongBackdrop(current,Modifier.matchParentSize())}){dismiss->QueueScreen(controller,playerEvents,dismiss)}
    if(historyOpen)FullSheet({historyOpen=false},fullHeight=false){dismiss->HistoryScreen(history,dismiss,playAction,{menu(it,"")},::message)}
    if(mixesOpen)FullSheet({mixesOpen=false},fullHeight=false){dismiss->MixBrowser(mixes,dismiss,{selectedMix=it})}
    selectedMix?.let{mix->FullSheet({selectedMix=null},fullHeight=false,backdrop={SongBackdrop(mix.songs.firstOrNull{it.hasCover},Modifier.fillMaxWidth().height(620.dp))}){dismiss->MixDetail(mix,mixes,dismiss,playAction,{menu(it,"")},::message)}}
    selected?.let{s->SongSheet(s,controller,playlistContext,::closeChild,::message)}
}

@Composable fun MiniPlayer(song:Song,c:MediaController?,events:Int,open:()->Unit,toggle:()->Unit){
    val ui=playbackUi(c,events)
    val shape=RoundedCornerShape(16.dp)
    Box(Modifier.padding(start=10.dp,end=10.dp,top=4.dp,bottom=4.dp).shadow(18.dp,shape).clip(shape).background(Color(0xFF26282C)).border(1.dp,Hairline,shape)){
        Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(start=10.dp,end=4.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){
            Artwork(song,Modifier.size(42.dp),8,priority=true)
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(song.title,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=12.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=1.dp))}
            IconButton(onClick=toggle,modifier=Modifier.semantics{contentDescription=tr(R.string.ui_play_or_pause)}){if(ui.playing && ui.buffering)CircularProgressIndicator(Modifier.size(20.dp),color=TextBright,strokeWidth=2.dp) else Icon(if(ui.playing && !ui.ended)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null,Modifier.size(28.dp),tint=TextBright)}
            IconButton(onClick={c?.seekToNextMediaItem()},enabled=ui.hasNext){Icon(Icons.Rounded.SkipNext,tr(R.string.ui_next_song),Modifier.size(28.dp))}
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal=14.dp)){PlayerProgress(c,compact=true)}
    }
}
