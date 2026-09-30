package org.dddd010010.serein

import org.junit.Assert.*
import org.junit.Test

class SessionOrderTest {
    data class Session(val id:String,val song:String,val seq:Int,val at:Double,val started:Double)
    private fun merge(rows:List<Session>)=SessionOrder.merge(rows,{it.id},{it.seq},{it.at},{it.started})
    @Test fun checkpointsMergeButRepeatedSongRemainsInHistory(){
        val rows=listOf(Session("a","song",1,100.0,80.0),Session("a","song",2,120.0,80.0),Session("b","song",1,150.0,140.0))
        assertEquals(listOf("b","a"),merge(rows).map{it.id})
        assertEquals(2,merge(rows).last().seq)
    }
    @Test fun delayedOfflineProgressCannotReplaceNewerCheckpoint(){
        val terminal=Session("a","song",4,200.0,100.0)
        assertEquals(listOf(terminal),merge(listOf(terminal,Session("a","song",1,210.0,100.0))))
    }
    @Test fun equalTimesUseStableEventOrderAcrossPages(){
        val a=Session("a","x",1,200.0,100.0);val b=Session("b","y",1,200.0,100.0)
        assertEquals(listOf(b,a),merge(listOf(a,b,a,b)))
    }
}
