package org.dddd010010.serein
import org.junit.Assert.*
import org.junit.Test

class PlaybackTelemetryTest {
    @Test fun pausesAndSeekingNeverCountAsListening() {
        val p=PlaybackTelemetry("song",180.0,0.0,0)
        p.sample(0,true,0);p.sample(1000,true,1000)
        p.sample(1000,true,170000);p.sample(2000,false,171000)
        p.sample(62000,false,171000)
        val event=p.checkpoint(62.0,"skipped")!!
        assertEquals(2.0,event.seconds,.01)
        assertEquals(171.0,event.position,.01)
        assertFalse(event.qualifiedNow)
        assertEquals("skipped",event.outcome)
    }
    @Test fun checkpointsKeepIdentityAndQualifyOnlyOnce() {
        val p=PlaybackTelemetry("song",180.0,0.0,0)
        p.sample(0,true,0)
        for(i in 1..29)p.sample(i*1000L,true,i*1000L)
        val first=p.checkpoint(29.0)!!
        assertFalse(first.qualifiedNow)
        p.sample(30000,true,30000)
        val second=p.checkpoint(30.0)!!
        assertTrue(second.qualifiedNow);assertEquals(first.event,second.event);assertTrue(second.seq>first.seq)
        p.sample(31000,true,31000)
        assertNull(p.checkpoint(31.0))
        val last=p.checkpoint(31.0,"error")!!
        assertFalse(last.qualifiedNow);assertEquals(31.0,last.seconds,.01)
        assertNull(p.checkpoint(32.0,"completed",true))
    }
    @Test fun untouchedRestoredQueueProducesNoEvidence() {
        val p=PlaybackTelemetry("song",180.0,0.0,0)
        p.sample(60000,false,20000)
        assertNull(p.checkpoint(60.0,"stopped",true))
    }
}
