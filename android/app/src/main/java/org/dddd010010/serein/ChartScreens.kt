@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.dddd010010.serein

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

class ChartModel:ViewModel(){
    var section by mutableStateOf("charts");private set
    var provider by mutableStateOf("youtube");private set
    var region by mutableStateOf("global");private set
    var days by mutableIntStateOf(7);private set
    var data by mutableStateOf(JSONObject());private set
    var loading by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    private var request:Job?=null
    private var generation=0
    private fun cacheKey()="catalog-$section-$provider-$region-$days"
    fun select(section:String=this.section,provider:String=this.provider,region:String=this.region,days:Int=this.days){
        if(this.section==section && this.provider==provider && this.region==region && this.days==days && data.has("items"))return
        this.section=section;this.provider=provider;this.region=if(provider=="apple" && region=="global")"hk" else region;this.days=days
        data=Library.obj(cacheKey());load()
    }
    fun stop(){request?.cancel();loading=false}
    fun load(refresh:Boolean=false,more:Boolean=false){
        if(more && (loading || data.optBoolean("loading") || !data.optBoolean("hasMore")))return
        request?.cancel();val version=++generation;val previous=data
        loading=true;error=""
        val key=cacheKey()
        val path=when{section=="releases"->"releases?offset=${if(more)data.optInt("nextOffset") else 0}&refresh=$refresh"
            provider=="echo"->"charts/echo?days=$days"
            else->"charts/$provider/$region?refresh=$refresh"}
        request=viewModelScope.launch{
            try{
                var url=path
                do{
                    val response=Library.apiAsync(url)
                    if(version!=generation)return@launch
                    if(more && response.optString("revision")!=previous.optString("revision")){load();return@launch}
                    if(more){val rows=previous.optJSONArray("items")?.objects().orEmpty()+response.optJSONArray("items")?.objects().orEmpty();response.put("items",org.json.JSONArray(rows.distinctBy{it.optString("id")}))}
                    data=response
                    val ready=response.optJSONArray("items")?.objects().orEmpty().mapNotNull{it.optJSONObject("song")}
                    Library.acceptReadyTracks(org.json.JSONArray(ready))
                    Library.save(key,response,notify=false)
                    loading=false
                    if(!response.optBoolean("loading"))break
                    delay(2000)
                    url=path.replace("refresh=true","refresh=false")
                }while(isActive)
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(version==generation)error=tr(R.string.catalog_connection_error)}
            finally{if(version==generation)loading=false}
        }
    }
}

private fun regionLabel(id:String)=when(id){"global"->tr(R.string.chart_global);"hk"->tr(R.string.chart_hk);"tw"->tr(R.string.chart_tw);else->tr(R.string.chart_jp)}

@Composable fun ChartScreen(model:ChartModel,section:String,discovery:DiscoveryModel,play:(List<Song>,Int)->Unit,search:(String)->Unit,message:(String)->Unit){
    var follows by rememberSaveable{mutableStateOf(false)}
    val state=rememberLazyListState()
    LaunchedEffect(section){model.select(section=section);if(!model.loading)model.load()}
    DisposableEffect(Unit){onDispose{model.stop()}}
    val data=model.data
    val rows=data.optJSONArray("items")?.objects().orEmpty()
    val following=data.optJSONArray("artists")?.objects().orEmpty()
    LaunchedEffect(section,model.provider,model.region,model.days){state.scrollToItem(0)}
    Column(Modifier.fillMaxSize()){
        if(section=="charts"){
            LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(listOf("youtube" to "YouTube", "apple" to "Apple Music", "echo" to "Echo")){(id,label)->FilterChip(model.provider==id,{model.select(provider=id)},label={Text(label)})}
            }
            LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                if(model.provider=="echo")items(listOf(7,30,0)){days->TextButton(onClick={model.select(days=days)}){Text(when(days){7->tr(R.string.chart_week);30->tr(R.string.chart_month);else->tr(R.string.chart_all_time)},color=if(model.days==days)TextBright else Muted)}}
                else items(if(model.provider=="youtube")listOf("global","hk","tw","jp") else listOf("hk","tw","jp")){region->TextButton(onClick={model.select(region=region)}){Text(regionLabel(region),color=if(model.region==region)TextBright else Muted)}}
            }
        }else Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){
            Text(tr(R.string.release_order),fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f))
            TextButton(onClick={follows=true}){Icon(Icons.Rounded.PersonAdd,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(tr(R.string.follow_artists))}
        }
        PullToRefreshBox(model.loading,{model.load(refresh=true)},Modifier.weight(1f)){
            LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
                item{
                    Column(Modifier.padding(horizontal=24.dp,vertical=12.dp)){
                        Text(when{section=="releases"->tr(R.string.release_source);model.provider=="youtube"->tr(R.string.chart_youtube_title);model.provider=="apple"->tr(R.string.chart_apple_title);else->tr(R.string.chart_echo_title)},fontFamily=EchoTitleFont,fontSize=20.sp,fontWeight=FontWeight.Medium)
                        val period=data.optString("period")
                        Text(when{section=="releases"->tr(R.string.release_follow_count,following.size);model.provider=="echo"->tr(R.string.chart_echo_basis);period.isNotBlank()->tr(R.string.chart_source_date,period.take(10));else->tr(R.string.chart_waiting)},fontSize=12.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(top=6.dp))
                        if(data.optBoolean("loading"))LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp).height(2.dp))
                        if(data.optBoolean("stale") && rows.isNotEmpty() || data.optJSONArray("errors")?.length()?.let{it>0}==true)Text(tr(R.string.catalog_cached),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                        if(model.error.isNotBlank() || data.optString("error").isNotBlank())Text(tr(R.string.catalog_connection_error),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                    }
                }
                items(rows,key={it.getString("id")}){row->
                    val song=row.optJSONObject("song")?.let{Song.from(it)}
                    val video=row.optString("video")
                    val importState=if(video.isNotBlank())discovery.state(video) else ""
                    val action={
                        when{
                            song!=null->{val queue=rows.mapNotNull{it.optJSONObject("song")?.let{json->Song.from(json)}};play(queue,queue.indexOfFirst{it.id==song.id}.coerceAtLeast(0))}
                            importState=="complete"->discovery.play(video)
                            video.isNotBlank()->discovery.add(video,row.optString("title"),message,playAfter=true)
                            else->search(row.optString("artist")+" "+row.optString("title"))
                        }
                    }
                    ChartRow(row,song,section=="charts",importState,action)
                }
                if(rows.isEmpty() && !model.loading && !data.optBoolean("loading"))item{
                    if(section=="releases" && following.isEmpty())EmptyState(tr(R.string.follow_artists),tr(R.string.release_empty),tr(R.string.follow_add)){follows=true}
                    else EmptyState(tr(R.string.catalog_empty),tr(R.string.catalog_refresh_hint),tr(R.string.ui_try_again)){model.load(refresh=true)}
                }
                if(model.loading && rows.isEmpty())item{Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)}}
                if(section=="releases" && data.optBoolean("hasMore"))item{LaunchedEffect(data.optInt("nextOffset"),data.optBoolean("loading")){model.load(more=true)};Box(Modifier.fillMaxWidth().padding(16.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)}}
            }
        }
    }
    if(follows)FullSheet({follows=false;model.load()}){dismiss->ArtistFollowsScreen({dismiss();model.load()},message)}
}

@Composable private fun ChartRow(row:JSONObject,song:Song?,ranked:Boolean,state:String,action:()->Unit){
    val busy=state in listOf("queued","downloading")
    Row(Modifier.fillMaxWidth().clickable(enabled=!busy,onClick=action).padding(start=24.dp,end=12.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        if(ranked)Text(row.optInt("rank").toString(),fontFamily=EchoLatinTitle,fontSize=19.sp,color=Muted,modifier=Modifier.width(34.dp))
        if(song!=null)Artwork(song,Modifier.size(48.dp),5)
        else AsyncImage(row.optString("cover"),null,Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)).background(Panel),contentScale=ContentScale.Crop)
        Column(Modifier.weight(1f).padding(start=13.dp,end=4.dp)){
            Text(row.optString("title"),fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=20.sp)
            Text(row.optString("artist"),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
            val detail=when{busy->tr(R.string.ui_adding);row.has("playCount")->tr(R.string.ui_plays,row.optInt("playCount"));!ranked->row.optString("releaseDate");else->""}
            if(detail.isNotBlank())Text(detail,fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
        }
        IconButton(onClick=action,enabled=!busy){
            if(busy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            else Icon(when{song!=null || state=="complete"->Icons.Rounded.PlayArrow;row.optString("video").isNotBlank()->Icons.Rounded.Download;else->Icons.Rounded.Search},
                when{song!=null || state=="complete"->tr(R.string.ui_play);row.optString("video").isNotBlank()->tr(R.string.catalog_prepare_play);else->tr(R.string.catalog_find_audio)},Modifier.size(21.dp),tint=Muted)
        }
    }
}

@Composable private fun ArtistFollowsScreen(close:()->Unit,message:(String)->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    var country by rememberSaveable{mutableStateOf("hk")}
    var followed by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var results by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var changing by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    var job by remember{mutableStateOf<Job?>(null)}
    fun search(){job?.cancel();if(query.isBlank()){results=emptyList();return};val term=query.trim();val market=country
        job=scope.launch{loading=true;error="";try{results=Library.apiAsync("artists/search?q=${URLEncoder.encode(term,"UTF-8")}&country=$market").getJSONArray("artists").objects()}catch(e:CancellationException){throw e}catch(_:Exception){error=tr(R.string.catalog_connection_error)}finally{loading=false}}}
    LaunchedEffect(Unit){try{followed=Library.apiAsync("artists").getJSONArray("artists").objects()}catch(e:CancellationException){throw e}catch(_:Exception){error=tr(R.string.catalog_connection_error)}}
    Column{SheetTitle(tr(R.string.follow_artists),close)
        SearchBox(query,{query=it},tr(R.string.follow_search),submit={search()},clear={query="";results=emptyList();job?.cancel();loading=false})
        LazyRow(contentPadding=PaddingValues(horizontal=24.dp)){items(listOf("hk","tw","jp")){id->TextButton(onClick={country=id;results=emptyList();search()}){Text(regionLabel(id),color=if(country==id)TextBright else Muted)}}}
        Text(tr(R.string.follow_identity_hint),fontSize=12.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp))
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=24.dp).height(2.dp))
        if(error.isNotBlank())Text(error,fontSize=12.sp,color=Muted,modifier=Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=24.dp)){
            val shown=if(query.isBlank())followed else results
            items(shown,key={it.getString("id")+it.getString("country")}){artist->
                val id=artist.getString("id");val market=artist.getString("country");val key=id+market
                val active=followed.any{it.optString("id")==id && it.optString("country")==market}
                Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text(artist.optString("name"),fontSize=16.sp,fontWeight=FontWeight.Medium);Text("Apple Music · ${regionLabel(market)}",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=5.dp))}
                    TextButton(enabled=changing.isBlank(),onClick={scope.launch{changing=key;try{
                        followed=Library.apiAsync("artists/$id","PUT",JSONObject().put("country",market).put("followed",!active)).getJSONArray("artists").objects()
                    }catch(e:CancellationException){throw e}catch(_:Exception){message(tr(R.string.catalog_connection_error))}finally{changing=""}}}){
                        if(changing==key)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text(if(active)tr(R.string.follow_remove) else tr(R.string.follow_add))
                    }
                }
            }
            if(shown.isEmpty() && !loading)item{Text(if(query.isBlank())tr(R.string.release_empty) else tr(R.string.follow_not_found),fontSize=13.sp,color=Muted,modifier=Modifier.padding(24.dp))}
        }
    }
}
