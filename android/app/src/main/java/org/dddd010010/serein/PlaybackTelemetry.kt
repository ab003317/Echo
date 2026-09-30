package org.dddd010010.serein

import java.util.UUID

/** Counts audible wall time. Seeking changes position, never time listened. */
class PlaybackTelemetry(val track:String,val duration:Double,val started:Double,now:Long) {
    val event=UUID.randomUUID().toString()
    var seconds=0.0; private set
    var position=0.0; private set
    var finished=false; private set
    private var last=now
    private var playing=false
    private var saved=0.0
    private var qualified=false
    private var seq=0
    data class Checkpoint(val event:String,val track:String,val seconds:Double,val position:Double,
        val duration:Double,val started:Double,val at:Double,val outcome:String,val seq:Int,val qualifiedNow:Boolean)
    fun sample(now:Long,isPlaying:Boolean,positionMs:Long) {
        if(finished)return
        if(playing)seconds+=(now-last).coerceIn(0,5000)/1000.0
        last=now;playing=isPlaying;position=(positionMs/1000.0).coerceAtLeast(0.0)
    }
    fun checkpoint(at:Double,outcome:String="progress",force:Boolean=false):Checkpoint? {
        if(finished || seconds<=0)return null
        val effective=seconds>=if(duration>0)minOf(30.0,duration/2) else 30.0
        if(!force && outcome=="progress" && saved>0 && seconds-saved<15 && !(effective && !qualified))return null
        if(outcome!="progress")finished=true
        val firstEffective=effective && !qualified
        qualified=qualified || effective;saved=seconds;seq++
        return Checkpoint(event,track,seconds,position,duration,started,at,outcome,seq,firstEffective)
    }
}
