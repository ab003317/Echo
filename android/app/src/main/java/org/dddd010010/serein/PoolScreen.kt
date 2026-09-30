package org.dddd010010.serein

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun PoolScreen(close:()->Unit) {
    var data by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf("")}
    var saving by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    suspend fun refresh(){try{data=Library.apiAsync("pool");error=""}catch(e:CancellationException){throw e}catch(_:Exception){error=tr(R.string.ui_connection_unavailable_updates_resume_when_connected)}}
    fun change(key:String,value:Any){if(!saving)scope.launch{saving=true;try{data=Library.apiAsync("pool","PUT",JSONObject().put(key,value));error=""}catch(e:CancellationException){throw e}catch(_:Exception){error=tr(R.string.ui_settings_could_not_be_saved_try_again_later)}finally{saving=false}}}
    LaunchedEffect(Unit){while(isActive){if(!saving)refresh();delay(15000)}}
    Column {
        SheetTitle(tr(R.string.ui_new_music_pool),close)
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=28.dp)) {
            item{Text(tr(R.string.ui_your_next_song_is_ready),fontSize=24.sp,fontWeight=FontWeight.Medium,lineHeight=32.sp,modifier=Modifier.padding(horizontal=24.dp,vertical=18.dp))}
            item{Text(tr(R.string.ui_new_music_is_stored_in_your_library_before_you_tap_play_unheard_s),fontSize=13.sp,lineHeight=22.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp))}
            if(error.isNotBlank())item{Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(24.dp))}
            val snapshot=data
            if(snapshot==null && error.isBlank())item{LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))}
            if(snapshot!=null) {
                val config=snapshot.getJSONObject("config")
                val counts=snapshot.getJSONObject("counts")
                item{Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=26.dp),horizontalArrangement=Arrangement.SpaceBetween){listOf(tr(R.string.ui_to_discover) to "explore",tr(R.string.ui_kept) to "kept",tr(R.string.ui_permanent) to "resident").forEach{(label,key)->Column{Text(counts.optInt(key).toString(),fontSize=32.sp,fontWeight=FontWeight.Normal);Text(label,fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}}}}
                item{Text(RemoteText.message(snapshot.optString("status"))+(if(snapshot.optInt("pending")>0)tr(R.string.ui_songs_preparing, snapshot.optInt("pending")) else ""),fontSize=12.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=4.dp))}
                item{PoolToggle(tr(R.string.ui_prepare_new_music_automatically),tr(R.string.ui_refresh_unheard_songs_daily_at_4_am),config.optBoolean("enabled"),!saving){change("enabled",it)}}
                item{PoolChoices(tr(R.string.ui_songs_to_discover),listOf(30,60,120,240),config.optInt("target"),!saving,{tr(R.string.ui_tracks_89, it)}){change("target",it)}}
                item{PoolChoices(tr(R.string.ui_daily_refresh_share),listOf(10,20,30,50),config.optInt("dailyPercent"),!saving,{"$it%"}){change("dailyPercent",it)}}
                item{PoolChoices(tr(R.string.ui_qualified_listens_to_keep_permanently),listOf(2,3,5,10),config.optInt("residentPlays"),!saving,{tr(R.string.ui_plays, it)}){change("residentPlays",it)}}
                item{Text(tr(R.string.ui_listen_for_30_seconds_or_half_of_a_short_song_to_count_adding_a_s),fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=10.dp))}
                item{PoolChoices(tr(R.string.ui_music_pool_storage),listOf(1,2,5,10), (config.optLong("budgetBytes")/1073741824).toInt(),!saving,{"$it GB"}){change("budgetBytes",it.toLong()*1073741824)}}
                item{Text(tr(R.string.ui_server_storage_used_heard_and_permanent_songs_count_toward_the_li, size(snapshot.optLong("bytes"))),fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=10.dp))}
                item{HorizontalDivider(Modifier.padding(horizontal=24.dp,vertical=20.dp),color=Raised)}
                item{PoolToggle(tr(R.string.ui_analyze_listening_preferences_daily),tr(R.string.ui_based_on_actual_listening_repeats_favorites_and_intentional_skips),config.optBoolean("aiEnabled"),!saving){change("aiEnabled",it)}}
                val analysis=snapshot.getJSONObject("analysis")
                listeningReport(analysis)
                item{Text((if(analysis.optString("day").isNotBlank())tr(R.string.ui_analyzed, analysis.optString("day")) else "")+RemoteText.message(analysis.optString("status")),fontSize=11.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=14.dp))}
                item{Text(tr(R.string.ui_uses_listening_statistics_and_available_audio_evidence_pauses_and),fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=10.dp))}
            }
        }
    }
}
@Composable private fun PoolToggle(title:String,detail:String,checked:Boolean,enabled:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){Text(title,fontSize=15.sp);Text(detail,fontSize=12.sp,lineHeight=19.sp,color=Muted,modifier=Modifier.padding(top=5.dp))};Switch(checked,change,enabled=enabled)}
}
@Composable private fun PoolChoices(title:String,values:List<Int>,selected:Int,enabled:Boolean,label:(Int)->String,change:(Int)->Unit){
    Column(Modifier.padding(top=18.dp)){Text(title,fontSize=14.sp,modifier=Modifier.padding(horizontal=24.dp));LazyRow(contentPadding=PaddingValues(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items(values){value->FilterChip(selected==value,onClick={change(value)},enabled=enabled,label={Text(label(value),fontSize=12.sp)})}}}
}
