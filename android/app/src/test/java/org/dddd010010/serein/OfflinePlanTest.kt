package org.dddd010010.serein
import org.junit.Assert.*
import org.junit.Test
class OfflinePlanTest {
    private fun e(id: String, size: Long=10) = OfflinePlan.Entry(id,size)
    @Test fun protectsPinnedAndExcludesDeleted() {
        val result = OfflinePlan.choose(listOf(e("keep")), listOf(listOf(e("deleted"),e("a"),e("b"))),setOf("deleted"),25)
        assertEquals(listOf("keep","a"), result.map { it.id })
    }
    @Test fun roundRobinAndDeduplicated() {
        val result = OfflinePlan.choose(emptyList(),listOf(listOf(e("a"),e("b")),listOf(e("a"),e("c"))),emptySet(),30)
        assertEquals(listOf("a","b","c"),result.map { it.id })
    }
    @Test fun budgetBelowPinnedDoesNotDeleteUserChoices() {
        assertEquals(listOf(e("keep",30)),OfflinePlan.choose(listOf(e("keep",30)),listOf(listOf(e("new"))),emptySet(),20))
    }
    @Test fun dislikedSongsLeaveAutomaticCopiesButFavoritesAndPinsStay() {
        // Mirrors the emulator check: songs 0-9 heard, 3 skipped more than heard, 5 a favorite, 10-19 only recommended.
        val songs = (0 until 20).map { "s$it" }
        val heard = (0 until 10).associate { "s$it" to (100L - it) }
        val all = setOf("recent", "frequent", "favorite", "recommended")
        val groups = OfflinePlan.sources(songs, { it }, all, songs.reversed(), setOf("s5"), setOf("s3"),
            { heard[it] ?: 0L }, { if(it in heard) 2 else 0 })
        val plan = OfflinePlan.choose(emptyList(), groups.map { g -> g.map { e(it) } }, emptySet(), 1000).map { it.id }
        assertEquals((songs - "s3").toSet(), plan.toSet())
        // Recommended songs arrive even when nothing new has been played.
        assertTrue(plan.take(8).any { it.removePrefix("s").toInt() >= 10 })
        val liked = OfflinePlan.sources(songs, { it }, all, emptyList(), setOf("s3"), setOf("s3"), { 0L }, { 0 })
        assertEquals(listOf(listOf("s3")), liked.filter { it.isNotEmpty() })
        val pinned = OfflinePlan.choose(listOf(e("s3")), groups.map { g -> g.map { e(it) } }, emptySet(), 1000).map { it.id }
        assertTrue("s3" in pinned)
        // Turning a source off removes it from the plan entirely.
        assertEquals(1, OfflinePlan.sources(songs, { it }, setOf("recommended"), songs, emptySet(), setOf("s3"), { 0L }, { 0 }).size)
    }
    @Test fun skipsOversizedAndEmpty() {
        assertEquals(listOf(e("small")),OfflinePlan.choose(emptyList(),listOf(listOf(e("huge",100),e("zero",0),e("small"))),emptySet(),20))
    }
}
