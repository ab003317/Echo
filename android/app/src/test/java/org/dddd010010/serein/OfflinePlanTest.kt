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
    @Test fun skipsOversizedAndEmpty() {
        assertEquals(listOf(e("small")),OfflinePlan.choose(emptyList(),listOf(listOf(e("huge",100),e("zero",0),e("small"))),emptySet(),20))
    }
}
