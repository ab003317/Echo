package org.dddd010010.serein

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FeedContinuityTest {
    @Test fun refreshKeepsSomeFamiliarSongsAmongNewSongs() {
        val old=(0 until 36).toList();val fresh=(100 until 118).toList()
        val next=FeedContinuity.refresh(old,fresh,{it},Random(1))
        assertEquals(21,next.size)
        assertEquals(3,next.count{it in old})
        assertEquals(fresh,next.filter{it in fresh})
        assertTrue(next.first() in fresh)
        assertEquals(next.size,next.toSet().size)
        assertTrue(next.withIndex().filter{it.value in old}.map{it.index}.distinct().size==3)
    }
    @Test fun duplicatesAndFailedEmptyRefreshDoNotDestroyTheOldList() {
        val old=(0 until 20).toList()
        assertEquals(old,FeedContinuity.refresh(old,emptyList(),{it},Random(1)))
        val mixed=FeedContinuity.refresh(old,listOf(1,1,2,3,4,100,101,102,103,104),{it},Random(2))
        assertEquals(mixed.size,mixed.toSet().size)
    }
    @Test fun backgroundListStaysWithinADayAndChangesTheNextDay() {
        val old=(0 until 40).toList();val fresh=(100 until 140).toList()
        // Same day: removed songs are replaced from the fresh list, the rest stay put.
        val valid=(old-setOf(3,7)+fresh).toSet()
        val sameDay=FeedContinuity.daily(old,fresh,valid,sameDay=true)
        assertEquals(old-setOf(3,7)+listOf(100,101),sameDay)
        // New day: the day's recommendations replace yesterday's offline set.
        assertEquals(fresh,FeedContinuity.daily(old,fresh,valid,sameDay=false))
        // A failed fetch never empties the list.
        assertEquals(old,FeedContinuity.daily(old,emptyList(),old.toSet(),sameDay=false))
    }
    @Test fun retainedSongsAndPositionsVaryBetweenRefreshes() {
        val outcomes=(1..12).map { seed -> FeedContinuity.refresh((0..35).toList(),(100..117).toList(),{it},Random(seed)) }
        assertTrue(outcomes.distinct().size>10)
    }
}
