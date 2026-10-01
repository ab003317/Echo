package org.dddd010010.serein

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

fun LazyListScope.listeningReport(analysis:JSONObject){
    val report=analysis.optJSONObject("report")
    item{ReportHeading(tr(R.string.ui_report_title),large=true)}
    if(report==null){
        item{ReportText(tr(R.string.ui_report_pending))}
        return
    }
    val periods=report.getJSONObject("periods")
    val today=periods.getJSONObject("today")
    val recent=periods.getJSONObject("recent")
    val sources=report.getJSONObject("sources")
    item{ReportText(tr(R.string.ui_report_date,report.optString("day")))}
    item{
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            MetricCard(today.optInt("effectivePlays"),tr(R.string.ui_report_qualified),Modifier.weight(1f))
            MetricCard(today.optInt("seconds")/60,tr(R.string.ui_report_minutes),Modifier.weight(1f))
            MetricCard(today.optInt("earlySkips"),tr(R.string.ui_report_skips),Modifier.weight(1f),muted=true)
        }
    }
    item{ReportText(tr(R.string.ui_report_recent,recent.optInt("tracks"),recent.optInt("effectivePlays"),recent.optInt("seconds")/60))}
    if(today.optBoolean("limited") || recent.optBoolean("limited"))item{ReportText(tr(R.string.ui_report_limited))}
    if(today.optInt("effectivePlays")==0)item{ReportText(tr(R.string.ui_report_no_daily_evidence))}
    val signals=report.optJSONArray("signals")?.objects().orEmpty()
    if(signals.isNotEmpty())item{ReportHeading(tr(R.string.ui_report_signals))}
    // Bars compare each signal with the strongest one in this snapshot, so the list reads as a ranking.
    val strongest=signals.maxOfOrNull{it.optInt("effectivePlays")}?.coerceAtLeast(1) ?: 1
    signals.forEach{signal->item{
        GlassCard(Modifier.padding(horizontal=16.dp,vertical=5.dp)){Column(Modifier.padding(horizontal=16.dp,vertical=14.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(signal.optString("title"),fontSize=15.sp,fontWeight=FontWeight.Bold,lineHeight=21.sp)
                    Text(signal.optString("artist"),fontSize=13.sp,color=Secondary)
                }
                Text(signal.optInt("effectivePlays").toString(),fontFamily=SereinFont,fontSize=20.sp,fontWeight=FontWeight.ExtraBold,style=TabularNumbers)
            }
            Box(Modifier.padding(top=8.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha=.12f))){
                Box(Modifier.fillMaxHeight().fillMaxWidth((signal.optInt("effectivePlays").toFloat()/strongest).coerceIn(.04f,1f)).background(if(signal.optInt("earlySkips")>signal.optInt("completed"))TextBright.copy(alpha=.5f) else TextBright))
            }
            Text(tr(R.string.ui_report_signal_metrics,
                tr(if(signal.optString("period")=="today")R.string.ui_report_day else R.string.ui_report_month),
                signal.optInt("effectivePlays"),signal.optInt("seconds"),signal.optInt("completed"),signal.optInt("earlySkips")),
                fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.5f),modifier=Modifier.padding(top=8.dp))
            Text(AppLocale.named("ui_report_signal_"+signal.optString("kind")),fontSize=13.sp,lineHeight=19.sp,color=TextBright.copy(alpha=.85f),modifier=Modifier.padding(top=4.dp))
        }}
    }}
    item{ReportHeading(tr(R.string.ui_report_changes))}
    val changes=report.optJSONArray("changes")?.objects().orEmpty()
    if(changes.isEmpty())item{ReportText(tr(R.string.ui_report_no_changes))}
    // Imported directions share one modest signal; do not repeat the same explanation per artist.
    val changeRows=changes.filter{it.optString("basis")!="reference"}+
        changes.filter{it.optString("basis")=="reference"}.groupBy{it.optString("direction")}.values.map{group->
            JSONObject(group.first().toString()).put("artist",group.joinToString(" · "){it.optString("artist")})
        }
    if(changeRows.isNotEmpty())item{GlassCard(Modifier.padding(horizontal=16.dp)){
        changeRows.forEachIndexed{i,change->
            if(i>0)CardDivider(62.dp)
            val less=change.optString("direction")=="less"
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.size(32.dp).background(if(less)GlassStrong else TextBright,CircleShape),contentAlignment=Alignment.Center){
                    Icon(if(less)Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,null,Modifier.size(17.dp),tint=if(less)TextBright else Night)
                }
                Column(Modifier.weight(1f).padding(start=14.dp)){
                    Text(tr(if(less)R.string.ui_report_less else R.string.ui_report_more,change.optString("artist")),fontSize=14.sp,fontWeight=FontWeight.Bold,lineHeight=20.sp,color=if(less)TextBright.copy(alpha=.85f) else TextBright)
                    Text(AppLocale.named("ui_report_basis_"+change.optString("basis"),change.optInt("count")),fontSize=12.sp,lineHeight=18.sp,color=TextBright.copy(alpha=.5f),modifier=Modifier.padding(top=2.dp))
                }
            }
        }
    }}
    item{ReportText(tr(R.string.ui_report_searches,report.optInt("searchDirections")))}
    item{ReportHeading(tr(R.string.ui_report_sources))}
    item{ReportText(tr(R.string.ui_report_references,sources.optInt("references"),sources.optInt("favorites"),sources.optInt("legacyPlays")))}
    item{ReportText(tr(R.string.ui_report_audio,sources.optInt("audioTracks"),sources.optInt("audioSeconds")))}
    report.optJSONArray("audioFeatures")?.objects().orEmpty().forEach{feature->item{
        ReportText(tr(R.string.ui_report_feature,AppLocale.named("ui_report_audio_"+feature.optString("tag")),feature.optInt("tracks"),sources.optInt("audioTracks")))
    }}
    item{ReportText(tr(R.string.ui_report_audio_limit))}
    item{
        var definitions by rememberSaveable{mutableStateOf(false)}
        SecondaryPill(tr(if(definitions)R.string.ui_report_hide_method else R.string.ui_report_show_method),{definitions=!definitions},Modifier.padding(horizontal=20.dp,vertical=10.dp),height=38.dp)
        if(definitions)ReportText(tr(R.string.ui_report_method))
    }
}

@Composable private fun ReportHeading(text:String,large:Boolean=false){Text(text,fontFamily=EchoTitleFont,fontSize=if(large)24.sp else 17.sp,fontWeight=if(large)EchoPageWeight else FontWeight.Bold,modifier=Modifier.padding(start=20.dp,end=20.dp,top=if(large)34.dp else 22.dp,bottom=8.dp))}
@Composable private fun ReportText(text:String){Text(text,fontSize=13.sp,lineHeight=21.sp,color=Secondary,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
