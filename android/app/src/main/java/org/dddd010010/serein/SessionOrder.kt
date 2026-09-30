package org.dddd010010.serein

/** Session IDs identify a listen; a song ID intentionally does not. */
object SessionOrder {
    fun <T> merge(rows:List<T>,id:(T)->String,seq:(T)->Int,updated:(T)->Double,started:(T)->Double):List<T> =
        rows.groupBy(id).values.map{group->group.maxWith(compareBy<T>{seq(it)}.thenBy{updated(it)})}
            .sortedWith(compareByDescending<T>{started(it)}.thenByDescending{id(it)})
}
