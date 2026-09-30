package org.dddd010010.serein

import kotlin.random.Random

/** A refresh changes the feed while retaining a few familiar items in new positions. */
object FeedContinuity {
    fun <T,K> refresh(previous:List<T>,fresh:List<T>,key:(T)->K,random:Random=Random.Default):List<T> {
        val next=fresh.distinctBy(key).toMutableList()
        if(next.isEmpty())return previous.distinctBy(key)
        val freshIds=next.map(key).toSet()
        val count=(next.size/5).coerceAtMost(4)
        val retained=previous.take(36).distinctBy(key).filter{key(it) !in freshIds}.shuffled(random).take(count)
        // Spread familiar songs through the new list; keep the first item fresh.
        retained.forEachIndexed { index,item ->
            val segment=next.size/(retained.size+1)
            val start=(1+index*segment).coerceAtMost(next.size)
            val end=(start+segment).coerceAtMost(next.size+1)
            next.add(random.nextInt(start,end.coerceAtLeast(start+1)),item)
        }
        return next
    }
}
