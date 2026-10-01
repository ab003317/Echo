package org.dddd010010.serein

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(bottom=34.dp)) {
            item{Text(tr(R.string.ui_your_next_song_is_ready),fontFamily=EchoTitleFont,fontSize=20.sp,fontWeight=FontWeight.Bold,lineHeight=28.sp,color=TextBright.copy(alpha=.85f),modifier=Modifier.padding(start=20.dp,end=20.dp,top=2.dp,bottom=8.dp))}
            item{Text(tr(R.string.ui_new_music_is_stored_in_your_library_before_you_tap_play_unheard_s),fontSize=13.sp,lineHeight=21.sp,color=Secondary,modifier=Modifier.padding(horizontal=20.dp))}
            if(error.isNotBlank())item{Text(error,fontSize=12.sp,color=Danger,modifier=Modifier.padding(20.dp))}
            val snapshot=data
            if(snapshot==null && error.isBlank())item{LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp),color=TextBright,trackColor=Glass)}
            if(snapshot!=null) {
                val config=snapshot.getJSONObject("config")
                val counts=snapshot.getJSONObject("counts")
                item{Row(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    listOf(tr(R.string.ui_to_discover) to "explore",tr(R.string.ui_kept) to "kept",tr(R.string.ui_permanent) to "resident").forEach{(label,key)->MetricCard(counts.optInt(key),label,Modifier.weight(1f),Glass)}
                }}
                item{Text(RemoteText.message(snapshot.optString("status"))+(if(snapshot.optInt("pending")>0)tr(R.string.ui_songs_preparing, snapshot.optInt("pending")) else ""),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=TextBright.copy(alpha=.5f),modifier=Modifier.padding(horizontal=20.dp,vertical=10.dp))}
                item{GlassCard(Modifier.padding(start=16.dp,end=16.dp,top=6.dp)){
                    CardRow(tr(R.string.ui_prepare_new_music_automatically),Icons.Rounded.AutoAwesome,tr(R.string.ui_refresh_unheard_songs_daily_at_4_am),tile=true){EchoSwitch(config.optBoolean("enabled"),{change("enabled",it)},enabled=!saving)}
                    CardDivider(60.dp)
                    PoolChoices(tr(R.string.ui_songs_to_discover),listOf(30,60,120,240),config.optInt("target"),!saving,{it.toString()}){change("target",it)}
                    PoolChoices(tr(R.string.ui_daily_refresh_share),listOf(10,20,30,50),config.optInt("dailyPercent"),!saving,{"$it%"}){change("dailyPercent",it)}
                    PoolChoices(tr(R.string.ui_qualified_listens_to_keep_permanently),listOf(2,3,5,10),config.optInt("residentPlays"),!saving,{it.toString()}){change("residentPlays",it)}
                    Text(tr(R.string.ui_listen_for_30_seconds_or_half_of_a_short_song_to_count_adding_a_s),fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(horizontal=16.dp,vertical=10.dp))
                    PoolChoices(tr(R.string.ui_music_pool_storage),listOf(1,2,5,10),(config.optLong("budgetBytes")/1073741824).toInt(),!saving,{"$it GB"}){change("budgetBytes",it.toLong()*1073741824)}
                    Text(tr(R.string.ui_server_storage_used_heard_and_permanent_songs_count_toward_the_li, size(snapshot.optLong("bytes"))),fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(start=16.dp,end=16.dp,top=10.dp,bottom=14.dp))
                }}
                item{GlassCard(Modifier.padding(start=16.dp,end=16.dp,top=12.dp)){
                    CardRow(tr(R.string.ui_analyze_listening_preferences_daily),Icons.Rounded.Insights,tr(R.string.ui_based_on_actual_listening_repeats_favorites_and_intentional_skips),tile=true){EchoSwitch(config.optBoolean("aiEnabled"),{change("aiEnabled",it)},enabled=!saving)}
                }}
                val analysis=snapshot.getJSONObject("analysis")
                listeningReport(analysis)
                item{Text((if(analysis.optString("day").isNotBlank())tr(R.string.ui_analyzed, analysis.optString("day")) else "")+RemoteText.message(analysis.optString("status")),fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(horizontal=20.dp,vertical=14.dp))}
                item{Text(tr(R.string.ui_uses_listening_statistics_and_available_audio_evidence_pauses_and),fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.45f),modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
            }
        }
    }
}
@Composable fun MetricCard(value:Int,label:String,modifier:Modifier=Modifier,fill:Color=GlassSoft,muted:Boolean=false){
    GlassCard(modifier,fill){Column(Modifier.padding(14.dp)){
        Text(value.toString(),fontFamily=SereinFont,fontSize=28.sp,lineHeight=32.sp,fontWeight=FontWeight.ExtraBold,color=if(muted)TextBright.copy(alpha=.7f) else TextBright,style=TabularNumbers)
        Text(label,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,color=Secondary,modifier=Modifier.padding(top=2.dp))
    }}
}
@Composable private fun PoolChoices(title:String,values:List<Int>,selected:Int,enabled:Boolean,label:(Int)->String,change:(Int)->Unit){
    Column(Modifier.padding(start=16.dp,end=16.dp,top=12.dp)){
        Text(title,fontSize=13.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.6f),modifier=Modifier.padding(bottom=8.dp))
        Segmented(values.map{it to label(it)},selected.takeIf{it in values},change,enabled=enabled,tabular=true)
    }
}
