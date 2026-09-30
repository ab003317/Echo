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
    @Test fun retainedSongsAndPositionsVaryBetweenRefreshes() {
        val outcomes=(1..12).map { seed -> FeedContinuity.refresh((0..35).toList(),(100..117).toList(),{it},Random(seed)) }
        assertTrue(outcomes.distinct().size>10)
    }
}
