@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class DiscoveryTrack(val id:String,val title:String,val artist:String,val cover:String,val duration:Double,val song:Song?=null){
    companion object {fun from(j:JSONObject)=DiscoveryTrack(j.getString("id"),j.optString("title"),j.optString("artist"),j.optString("cover"),j.optDouble("duration",0.0),j.optJSONObject("song")?.let{Song.from(it)})}
    fun json()=JSONObject().put("id",id).put("title",title).put("artist",artist).put("cover",cover).put("duration",duration).put("song",song?.json())
}
class DiscoveryModel:ViewModel(){
    var query by mutableStateOf("")
    var resultQuery by mutableStateOf(""); private set
    var mode by mutableStateOf("taste"); private set
    var rows by mutableStateOf<List<DiscoveryTrack>>(emptyList()); private set
    var jobs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var hasMore by mutableStateOf(true); private set
    var error by mutableStateOf(""); private set
    var libraryChanged by mutableIntStateOf(0); private set
    var playWhenReady by mutableStateOf("")
    var generation by mutableIntStateOf(0); private set
    var refreshVersion by mutableIntStateOf(0); private set
    private var requestJob:Job?=null
    private var polling:Job?=null
    private var seed=UUID.randomUUID().toString()
    private var nextPage=0
    var nextLoadAt by mutableLongStateOf(0L); private set
    private var pendingReset=true
    private var continuity=emptyList<DiscoveryTrack>()
    private val seen=linkedSetOf<String>()
    private val adding=mutableStateListOf<String>()
    init {
        val cache=Library.obj("discoveryState")
        resultQuery=cache.optString("query");query=resultQuery;mode=cache.optString("mode","taste")
        rows=if(cache.optInt("schema")==2)cache.optJSONArray("tracks")?.objects()?.map{DiscoveryTrack.from(it)} ?: emptyList() else emptyList()
        // A restored feed is useful immediately; a fresh session starts on first load-more.
        seen.addAll(rows.map{it.id})
    }
    fun startJobs(){if(polling?.isActive==true)return;polling=viewModelScope.launch{while(isActive){pollJobs();delay(if(jobs.any{it.optString("status") in listOf("queued","downloading")})3000 else 15000)}}}
    fun stopJobs(){polling?.cancel();polling=null}
    fun pruneExcluded(){
        val excluded=Library.contentExcluded
        continuity=continuity.filter{it.song?.id !in excluded}
        val next=rows.filter{it.song?.id !in excluded}
        if(next.size!=rows.size){
            rows=next
            Library.save("discoveryState",JSONObject().put("schema",2).put("query",resultQuery).put("mode",mode).put("tracks",JSONArray(rows.take(72).map{it.json()})),notify=false)
        }
    }
    private suspend fun pollJobs(){try{
        val next=Library.apiAsync("imports").getJSONArray("jobs").objects()
        if(next.any{j->j.optString("status")=="complete" && jobs.none{it.optString("id")==j.optString("id") && it.optString("status")=="complete"}})libraryChanged++
        jobs=next
    }catch(e:CancellationException){throw e}catch(_:Exception){}}
    fun ensureLoaded(){if(rows.isEmpty() && !loading)refresh()}
    fun changeMode(value:String){if(mode==value && query.isBlank())return;mode=value;query="";refresh("",preserve=false)}
    fun search(){refresh(query.trim(),preserve=false)}
    fun refresh(search:String=resultQuery,preserve:Boolean=true){
        requestJob?.cancel();generation++;seed=UUID.randomUUID().toString();nextPage=0;nextLoadAt=0;pendingReset=true;hasMore=true;error=""
        continuity=if(preserve && search==resultQuery)rows.take(36) else emptyList()
        // Search requests should return exact matches, not omit songs seen in unrelated searches.
        if(search!=resultQuery)seen.clear()
        // A ready library is finite. Refresh starts a new order; paging excludes every displayed ID.
        if(search.isBlank()){seen.clear();seen.addAll(continuity.map{it.id})}
        load(search,reset=true)
    }
    fun loadMore(){if(!loading && hasMore && System.currentTimeMillis()>=nextLoadAt)load(resultQuery,reset=false)}
    fun retry(){if(!loading)load(if(pendingReset)query.trim() else resultQuery,reset=pendingReset)}
    private fun load(search:String,reset:Boolean){
        val version=generation
        loading=true;refreshing=reset;error=""
        requestJob=viewModelScope.launch {
            try {
                suspend fun fetch():JSONObject {
                    val exclude=if(search.isBlank() || nextPage==0)seen.toList().takeLast(10000) else emptyList()
                    return Library.apiAsync("discover","POST",JSONObject().put("q",search).put("seed",seed).put("page",nextPage).put("mode",mode).put("exclude",JSONArray(exclude)))
                }
                val data=try{fetch()}catch(e:ApiError){
                    if(e.statusCode!=409)throw e
                    seed=UUID.randomUUID().toString();nextPage=0
                    fetch()
                }
                if(version!=generation)return@launch
                Library.applyContentExclusions(data)
                pruneExcluded()
                val incoming=data.getJSONArray("tracks").objects().map{DiscoveryTrack.from(it)}
                Library.acceptReadyTracks(JSONArray(data.getJSONArray("tracks").objects().mapNotNull{it.optJSONObject("song")}))
                rows=(if(reset)FeedContinuity.refresh(continuity,incoming,{it.id}) else rows+incoming).distinctBy{it.id}
                if(reset)refreshVersion++
                resultQuery=search;seen.addAll(incoming.map{it.id});pendingReset=false
                hasMore=data.optBoolean("hasMore");nextPage=data.optInt("nextPage",nextPage+1)
                nextLoadAt=if(data.optLong("retryAfter")>0)System.currentTimeMillis()+data.optLong("retryAfter")*1000 else 0
                Library.save("discoveryState",JSONObject().put("schema",2).put("query",resultQuery).put("mode",mode).put("tracks",JSONArray(rows.take(72).map{it.json()})),notify=false)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(version==generation){error=if(e is ApiError)e.message.orEmpty() else tr(R.string.ui_connection_lost_your_loaded_songs_are_still_here);if(e is ApiError && e.statusCode==409){seed=UUID.randomUUID().toString();nextPage=0;pendingReset=false}}}
            finally{if(version==generation){loading=false;refreshing=false}}
        }
    }
    fun state(id:String):String=if(id in adding)"queued" else jobs.find{it.optString("video")==id}?.optString("status").orEmpty()
    fun add(url:String,title:String="",message:(String)->Unit){
        if(url in adding)return
        adding.add(url)
        viewModelScope.launch{try{
            Library.apiAsync("imports","POST",JSONObject().put("url",url).put("title",title));pollJobs();message(tr(R.string.ui_added_ready_to_play_once_processing_finishes))
        }catch(e:CancellationException){throw e}catch(e:Exception){message(e.message ?: tr(R.string.ui_could_not_add_this_song_try_again))}finally{adding.remove(url)}}
    }
    fun play(id:String){val job=jobs.find{it.optString("video")==id};if(job!=null){playWhenReady=job.optString("track");libraryChanged++}}
}

@Composable fun DiscoveryScreen(model:DiscoveryModel,message:(String)->Unit,play:(List<Song>,Int)->Unit){
    val listState=rememberLazyGridState()
    var link by rememberSaveable{mutableStateOf(false)}
    var tasks by rememberSaveable{mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    fun refresh(){keyboard?.hide();model.refresh()}
    LaunchedEffect(Unit){model.ensureLoaded()}
    LaunchedEffect(Library.revision){model.pruneExcluded();model.ensureLoaded()}
    var appliedRefresh by rememberSaveable{mutableIntStateOf(model.refreshVersion)}
    LaunchedEffect(model.refreshVersion){if(appliedRefresh!=model.refreshVersion){listState.scrollToItem(0);appliedRefresh=model.refreshVersion}}
    LaunchedEffect(listState,model){snapshotFlow{
        val last=listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        Triple(last>=listState.layoutInfo.totalItemsCount-7,model.rows.size,model.loading)
    }.distinctUntilChanged().collect{(near,_,busy)->if(near && !busy && model.rows.isNotEmpty() && model.error.isBlank())model.loadMore()}}
    LaunchedEffect(model.nextLoadAt){
        if(model.nextLoadAt>System.currentTimeMillis()){
            delay((model.nextLoadAt-System.currentTimeMillis()).coerceAtLeast(1))
            if((listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)>=listState.layoutInfo.totalItemsCount-7 && model.error.isBlank())model.loadMore()
        }
    }
    Column {
        Row(Modifier.padding(start=24.dp,end=8.dp,top=8.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically){
            Text(tr(R.string.ui_discover),fontSize=28.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1).sp,modifier=Modifier.weight(1f))
            IconButton(onClick={tasks=true}){BadgedBox(badge={val active=model.jobs.count{it.optString("status") in listOf("queued","downloading")};if(active>0)Badge{Text(active.toString())}}){Icon(Icons.Rounded.PlaylistAddCheck,tr(R.string.ui_imports))}}
            IconButton(onClick={link=true}){Icon(Icons.Rounded.AddLink,tr(R.string.ui_paste_a_link))}
        }
        SearchBox(model.query,{model.query=it},tr(R.string.ui_song_artist_or_youtube_link),submit={
            if(model.query.trim().startsWith("https://"))model.add(model.query.trim(),message=message) else {model.search();scope.launch{listState.scrollToItem(0)}}
        },clear={model.query="";model.search()})
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=6.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
            LazyRow(Modifier.weight(1f)){
                items(listOf("taste" to tr(R.string.ui_for_you),"new" to tr(R.string.ui_explore),"calm" to tr(R.string.ui_unwind),"energy" to tr(R.string.ui_energize))){(id,label)->LineTab(label,model.mode==id && model.resultQuery.isBlank()){model.changeMode(id);scope.launch{listState.scrollToItem(0)}}}
            }
        }
        PullToRefreshBox(isRefreshing=model.refreshing,onRefresh={refresh()},modifier=Modifier.weight(1f)){
            LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),state=listState,contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(22.dp),modifier=Modifier.fillMaxSize()){
                if(model.resultQuery.isNotBlank())item(span={GridItemSpan(maxLineSpan)}){Text(tr(R.string.ui_results_for, model.resultQuery),fontSize=13.sp,color=Muted,modifier=Modifier.padding(vertical=4.dp))}
                items(model.rows,key={"discovery-${it.id}"}){song->DiscoveryRow(song,if(song.song!=null)"complete" else model.state(song.id),{
                    if(song.song!=null){val queue=model.rows.mapNotNull{it.song}.distinctBy{it.id};play(queue,queue.indexOfFirst{it.id==song.song.id}.coerceAtLeast(0))}
                    else model.play(song.id)
                },{model.add(song.id,song.title,message)})}
                if(model.error.isNotBlank())item(span={GridItemSpan(maxLineSpan)}){Column(Modifier.fillMaxWidth().padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(RemoteText.message(model.error),color=Muted,fontSize=13.sp);TextButton(onClick={model.retry()}){Text(tr(R.string.ui_try_again))}}}
                if(model.loading && !model.refreshing)item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text(tr(R.string.ui_finding_more_music),fontSize=12.sp,color=Muted,modifier=Modifier.padding(start=12.dp))}}
                if(!model.loading && model.error.isBlank()){
                    if(model.rows.isEmpty())item(span={GridItemSpan(maxLineSpan)}){EmptyState(tr(R.string.ui_no_songs_found),tr(R.string.ui_try_another_song_or_artist))}
                    else if(!model.hasMore)item(span={GridItemSpan(maxLineSpan)}){Text(if(model.resultQuery.isBlank())tr(R.string.ui_no_more_new_songs_for_now_pull_down_to_refresh) else tr(R.string.ui_all_results_for_this_search),fontSize=12.sp,color=Muted,modifier=Modifier.fillMaxWidth().padding(vertical=24.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                    else if(model.nextLoadAt>0 && model.resultQuery.isBlank())item(span={GridItemSpan(maxLineSpan)}){Text(tr(R.string.ui_more_songs_will_appear_here_as_they_become_ready),fontSize=12.sp,color=Muted,modifier=Modifier.fillMaxWidth().padding(vertical=24.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                }
            }
        }
    }
    if(link)TextDialog(tr(R.string.ui_add_music),tr(R.string.ui_youtube_link_to_a_song_or_music_collection),{link=false}){model.add(it,message=message);link=false}
    if(tasks)FullSheet({tasks=false}){dismiss->Column{SheetTitle(tr(R.string.ui_imports),dismiss);LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=24.dp)){
        if(model.jobs.isEmpty())item{EmptyState(tr(R.string.ui_no_imports_yet),tr(R.string.ui_tap_in_discover_or_paste_a_song_link))}
        items(model.jobs,key={it.getString("id")}){job->
            var details by remember{mutableStateOf(false)}
            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=13.dp)){
                Text(job.optString("title"),fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                val state=job.optString("status")
                Row(verticalAlignment=Alignment.CenterVertically){Text(when(state){"complete"->tr(R.string.ui_added_to_library);"failed"->tr(R.string.ui_could_not_retrieve_this_song);"downloading"->tr(R.string.ui_adding);else->tr(R.string.ui_queued)},fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f))
                    if(state=="complete")TextButton(onClick={model.play(job.getString("video"));dismiss()}){Text(tr(R.string.ui_play))}
                    if(state=="failed"){TextButton(onClick={details=!details}){Text(tr(R.string.ui_details))};TextButton(onClick={model.add(job.getString("video"),job.optString("title"),message)}){Text(tr(R.string.ui_retry))}}
                }
                if(state=="downloading")LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                if(details)Text(RemoteText.message(job.optString("error")),fontSize=12.sp,color=Muted)
            }
        }
    }}}
}

@Composable fun DiscoveryRow(song:DiscoveryTrack,state:String,play:()->Unit,add:()->Unit){
    Column(Modifier.fillMaxWidth().then(if(state=="complete")Modifier.clickable(onClick=play) else Modifier)){
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Panel)){
            if(song.song!=null)Artwork(song.song,Modifier.fillMaxSize(),4)
            else {
                val context=androidx.compose.ui.platform.LocalContext.current
                val active=LocalArtworkActive.current
                var started by remember(song.cover){mutableStateOf(false)}
                LaunchedEffect(active){if(active)started=true}
                val request=remember(song.cover){coil.request.ImageRequest.Builder(context).data(song.cover).build()}
                if(started)AsyncImage(request,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            }
            if(song.duration>0)Text(timeLabel((song.duration*1000).toLong()),color=TextBright,fontSize=10.sp,modifier=Modifier.align(Alignment.TopEnd).padding(7.dp).background(Night.copy(alpha=.68f),RoundedCornerShape(4.dp)).padding(horizontal=6.dp,vertical=3.dp))
            IconButton(onClick=if(state=="complete")play else add,enabled=state!in listOf("queued","downloading"),modifier=Modifier.align(Alignment.BottomEnd).padding(4.dp)){
                Box(Modifier.size(36.dp).background(if(state in listOf("queued","downloading"))Panel else TextBright,CircleShape),contentAlignment=Alignment.Center){
                    Icon(when(state){"complete"->Icons.Rounded.PlayArrow;"queued","downloading"->Icons.Rounded.Schedule;"failed"->Icons.Rounded.Refresh;else->Icons.Rounded.Add},if(state=="complete")tr(R.string.ui_play_39, song.title) else tr(R.string.ui_add, song.title),Modifier.size(22.dp),tint=if(state in listOf("queued","downloading"))Muted else Night)
                }
            }
        }
        Text(song.title,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis,lineHeight=20.sp,modifier=Modifier.padding(top=10.dp))
        Text(when(state){"queued"->tr(R.string.ui_waiting_to_add);"downloading"->tr(R.string.ui_adding);"failed"->tr(R.string.ui_could_not_add_tap_to_retry);else->song.artist},fontSize=11.sp,lineHeight=16.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
    }
}
