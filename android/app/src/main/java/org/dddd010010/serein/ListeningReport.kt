package org.dddd010010.serein

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

fun LazyListScope.listeningReport(analysis:JSONObject){
    val report=analysis.optJSONObject("report")
    item{ReportHeading(tr(R.string.ui_report_title))}
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
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            AnalysisMetric(today.optInt("effectivePlays"),tr(R.string.ui_report_qualified),Modifier.weight(1f))
            AnalysisMetric(today.optInt("seconds")/60,tr(R.string.ui_report_minutes),Modifier.weight(1f))
            AnalysisMetric(today.optInt("earlySkips"),tr(R.string.ui_report_skips),Modifier.weight(1f))
        }
    }
    item{ReportText(tr(R.string.ui_report_recent,recent.optInt("tracks"),recent.optInt("effectivePlays"),recent.optInt("seconds")/60))}
    if(today.optBoolean("limited") || recent.optBoolean("limited"))item{ReportText(tr(R.string.ui_report_limited))}
    if(today.optInt("effectivePlays")==0)item{ReportText(tr(R.string.ui_report_no_daily_evidence))}
    val signals=report.optJSONArray("signals")?.objects().orEmpty()
    if(signals.isNotEmpty())item{ReportHeading(tr(R.string.ui_report_signals))}
    signals.forEach{signal->item{
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=10.dp)){
            Text(signal.optString("title"),fontSize=15.sp,fontWeight=FontWeight.Medium,lineHeight=22.sp)
            Text(signal.optString("artist"),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=3.dp))
            Text(tr(R.string.ui_report_signal_metrics,
                tr(if(signal.optString("period")=="today")R.string.ui_report_day else R.string.ui_report_month),
                signal.optInt("effectivePlays"),signal.optInt("seconds"),signal.optInt("completed"),signal.optInt("earlySkips")),
                fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=7.dp))
            Text(AppLocale.named("ui_report_signal_"+signal.optString("kind")),fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(top=4.dp))
            HorizontalDivider(Modifier.padding(top=12.dp),color=Raised)
        }
    }}
    item{ReportHeading(tr(R.string.ui_report_changes))}
    val changes=report.optJSONArray("changes")?.objects().orEmpty()
    if(changes.isEmpty())item{ReportText(tr(R.string.ui_report_no_changes))}
    // Imported directions share one modest signal; do not repeat the same explanation per artist.
    val changeRows=changes.filter{it.optString("basis")!="reference"}+
        changes.filter{it.optString("basis")=="reference"}.groupBy{it.optString("direction")}.values.map{group->
            JSONObject(group.first().toString()).put("artist",group.joinToString(" · "){it.optString("artist")})
        }
    changeRows.forEach{change->item{
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp)){
            Text(tr(if(change.optString("direction")=="less")R.string.ui_report_less else R.string.ui_report_more,change.optString("artist")),fontSize=14.sp,lineHeight=22.sp)
            Text(AppLocale.named("ui_report_basis_"+change.optString("basis"),change.optInt("count")),fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
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
        TextButton(onClick={definitions=!definitions},modifier=Modifier.padding(horizontal=16.dp)){
            Text(tr(if(definitions)R.string.ui_report_hide_method else R.string.ui_report_show_method),fontSize=12.sp)
        }
        if(definitions)ReportText(tr(R.string.ui_report_method))
    }
}

@Composable private fun ReportHeading(text:String){Text(text,fontSize=19.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(start=24.dp,end=24.dp,top=22.dp,bottom=10.dp))}
@Composable private fun ReportText(text:String){Text(text,fontSize=13.sp,lineHeight=22.sp,color=Muted,modifier=Modifier.padding(horizontal=24.dp,vertical=5.dp))}
@Composable private fun AnalysisMetric(value:Int,label:String,modifier:Modifier){Column(modifier){Text(value.toString(),fontSize=30.sp,fontWeight=FontWeight.Medium);Text(label,fontSize=12.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(top=5.dp))}}
