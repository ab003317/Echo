package org.dddd010010.serein

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class ParsedValueTest {
    @Test fun unchangedInputIsParsedOnceEvenUnderConcurrentReads(){
        var parses=0
        val cache=ParsedValue{raw:String->parses++;raw.split(',')}
        val pool=Executors.newFixedThreadPool(4)
        try{
            val reads=pool.invokeAll((1..1000).map{java.util.concurrent.Callable{cache.get("a,b")}})
            assertEquals(1,parses)
            assertTrue(reads.all{it.get()===reads.first().get()})
            assertEquals(listOf("c"),cache.get("c"));assertEquals(2,parses)
            assertEquals(listOf("a","b"),cache.get("a,b"));assertEquals(3,parses)
        }finally{pool.shutdownNow()}
    }
    @Test fun failedParseDoesNotReplaceLastGoodValue(){
        val cache=ParsedValue{raw:String->raw.toInt()}
        assertEquals(7,cache.get("7"))
        assertThrows(NumberFormatException::class.java){cache.get("bad")}
        assertEquals(7,cache.get("7"))
        assertEquals(8,cache.get("8"))
    }
}
