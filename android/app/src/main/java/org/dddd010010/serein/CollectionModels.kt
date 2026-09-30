package org.dddd010010.serein

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class HistoryEntry(val event:String,val song:Song,val seconds:Double,val started:Double,val at:Double,
    val outcome:String,val seq:Int,val available:Boolean,val raw:JSONObject) {
    companion object { fun from(j:JSONObject)=HistoryEntry(j.getString("event"),Song.from(j.getJSONObject("song")),
        j.optDouble("seconds",0.0),j.optDouble("started",j.optDouble("at",0.0)),j.optDouble("at",0.0),
        j.optString("outcome","legacy"),j.optInt("seq"),j.optBoolean("available",true),j) }
}

data class MixEdition(val id:String,val originalTitle:String,val kind:String,val originalDescription:String,val songs:List<Song>,
    val unavailable:Set<String>,val created:Double,val expires:Double,val saved:Boolean,val active:Boolean,val raw:JSONObject) {
    val title get()=if(kind in listOf("style","random") || (kind=="series" && originalTitle=="学园偶像大师"))RemoteText.message(originalTitle) else originalTitle
    val description get()=RemoteText.message(originalDescription)
    val label get()=when(kind){"artist"->tr(R.string.ui_artist);"style"->tr(R.string.ui_style);"series"->tr(R.string.ui_series_album);else->tr(R.string.ui_random)}
    companion object { fun from(j:JSONObject):MixEdition {
        val tracks=j.getJSONArray("tracks").objects()
        return MixEdition(j.getString("id"),j.getString("title"),j.getString("kind"),j.optString("description"),
            tracks.map{Song.from(it)},tracks.filter{!it.optBoolean("available",true)}.map{it.getString("id")}.toSet(),
            j.optDouble("created",0.0),j.optDouble("expires",0.0),j.optBoolean("saved"),j.optBoolean("active"),j)
    } }
}

fun historyEntries():List<HistoryEntry> {
    val rows=(Library.array("historyRemote").objects()+Library.array("historyLocal").objects()).mapNotNull{runCatching{HistoryEntry.from(it)}.getOrNull()}
    return SessionOrder.merge(rows,{it.event},{it.seq},{it.at},{it.started})
}
fun mixEditions()=Library.obj("mixFeed").optJSONArray("mixes")?.objects()?.mapNotNull{runCatching{MixEdition.from(it)}.getOrNull()}.orEmpty()

class HistoryModel:ViewModel() {
    var query by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var more by mutableStateOf(true); private set
    var error by mutableStateOf(""); private set
    var loaded by mutableStateOf(emptyList<HistoryEntry>()); private set
    private var cursor=""
    private var request:Job?=null
    fun search(text:String){query=text;loaded=emptyList();request?.cancel();request=viewModelScope.launch{delay(280);load(false)}}
    fun refresh(){request?.cancel();request=viewModelScope.launch{load(false)}}
    fun next(){if(busy || loadingMore || !more || error.isNotBlank())return;request=viewModelScope.launch{load(true)}}
    private suspend fun load(append:Boolean) {
        if(append)loadingMore=true else {busy=true;loadingMore=false;cursor="";more=true}
        error=""
        try {
            if(!append)withContext(Dispatchers.IO){Library.flush()}
            val data=Library.apiAsync("history?limit=50&q="+URLEncoder.encode(query,"UTF-8")+"&cursor="+URLEncoder.encode(cursor,"UTF-8"))
            Library.acceptHistory(data.getJSONArray("entries"))
            val page=data.getJSONArray("entries").objects().map{HistoryEntry.from(it)}
            loaded=SessionOrder.merge((if(append)loaded else emptyList())+page,{it.event},{it.seq},{it.at},{it.started})
            cursor=data.optString("nextCursor").takeUnless{it=="null"}.orEmpty();more=data.optBoolean("hasMore")
        } catch(e:CancellationException){throw e}
        catch(e:Exception){error=if(Library.online)tr(R.string.ui_history_could_not_sync_pull_down_to_retry) else tr(R.string.ui_offline_history_is_saved_and_will_sync_when_connected)}
        finally{busy=false;loadingMore=false}
    }
}

class MixModel:ViewModel() {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    private var polling:Job?=null
    fun start(){if(polling!=null)return;polling=viewModelScope.launch{while(isActive){refreshNow();delay(60000)}}}
    fun stop(){polling?.cancel();polling=null}
    fun refresh(){viewModelScope.launch{refreshNow()}}
    private suspend fun refreshNow(){
        if(busy)return
        busy=true;error=""
        try{withContext(Dispatchers.IO){Library.refreshMixes()}}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=if(Library.online)tr(R.string.ui_playlists_could_not_sync_pull_down_to_retry) else tr(R.string.ui_browsing_cached_playlists)}
        finally{busy=false}
    }
    fun toggle(mix:MixEdition){Library.toggleMix(mix);refresh()}
    fun configure(hours:Int){viewModelScope.launch{
        if(busy)return@launch
        busy=true;error=""
        try{Library.acceptMixFeed(Library.apiAsync("mixes/config","PUT",JSONObject().put("intervalHours",hours)))}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=tr(R.string.ui_rotation_schedule_unchanged_reconnect_and_try_again)}
        finally{busy=false}
    }}
}
