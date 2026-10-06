package org.dddd010010.serein

/** Input lists are ranked; round-robin gives each selected source a fair share. */
object OfflinePlan {
    data class Entry(val id: String, val bytes: Long)
    /** Ranked automatic sources. Songs skipped more often than heard leave every source except favorites;
     *  manually kept songs are handled separately and never dropped. */
    fun <S> sources(songs: List<S>, id: (S) -> String, enabled: Set<String>, recommended: List<String>, favorites: Set<String>,
                    disliked: Set<String>, last: (S) -> Long, plays: (S) -> Int): List<List<S>> {
        val wanted = songs.filter { id(it) !in disliked }
        val byId = wanted.associateBy(id)
        return listOfNotNull(
            if("recent" in enabled) wanted.filter { last(it) > 0 }.sortedByDescending(last) else null,
            if("frequent" in enabled) wanted.filter { plays(it) > 0 }.sortedByDescending(plays) else null,
            if("favorite" in enabled) songs.filter { id(it) in favorites } else null,
            if("recommended" in enabled) recommended.mapNotNull { byId[it] } else null)
    }
    fun choose(pinned: List<Entry>, groups: List<List<Entry>>, excluded: Set<String>, budget: Long): List<Entry> {
        val selected = pinned.distinctBy { it.id }.toMutableList()
        val seen = selected.map { it.id }.toMutableSet()
        var used = selected.sumOf { it.bytes }
        for(index in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
            for(group in groups) {
                val entry = group.getOrNull(index) ?: continue
                if(entry.id in excluded || entry.id in seen || entry.bytes <= 0 || entry.bytes > budget - used) continue
                selected.add(entry); seen.add(entry.id); used += entry.bytes
            }
        }
        return selected
    }
}
