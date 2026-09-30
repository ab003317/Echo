package org.dddd010010.serein

/** A value belongs to its exact serialized input, not to unrelated UI revisions. */
class ParsedValue<T>(private val parse:(String)->T) {
    private var source:String?=null
    private var value:T?=null
    @Synchronized fun get(raw:String):T {
        if(source!=raw){value=parse(raw);source=raw}
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
