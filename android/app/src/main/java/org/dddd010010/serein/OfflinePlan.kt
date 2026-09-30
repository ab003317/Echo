package org.dddd010010.serein

/** Input lists are ranked; round-robin gives each selected source a fair share. */
object OfflinePlan {
    data class Entry(val id: String, val bytes: Long)
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
