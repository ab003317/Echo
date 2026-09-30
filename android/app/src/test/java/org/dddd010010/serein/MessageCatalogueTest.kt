package org.dddd010010.serein

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCatalogueTest {
    private val catalogue=MessageCatalogue(listOf(
        MessageCatalogue.Entry("正在保存 {0}/{1} · {2}","正在儲存 {0}/{1} · {2}","Saving {0}/{1} · {2}"),
        MessageCatalogue.Entry("你把《{0}》加入了最爱。","你把《{0}》加入了最愛。","You added “{0}” to Favorites."),
        MessageCatalogue.Entry("旧版《{0}》有 {1} 次。","舊版《{0}》有 {1} 次。","Older records show {1} plays of “{0}”."),
        MessageCatalogue.Entry("新歌池已准备好","新歌池已準備好","New music is ready")
    ))
    @Test fun translatesCachedMessagesWithoutChangingSongTitles() {
        assertEquals("Saving 2/9 · 发现 新歌池已准备好",catalogue.message("正在保存 2/9 · 发现 新歌池已准备好",true,"error"))
        assertEquals("正在儲存 2/9 · 发现",catalogue.message("Saving 2/9 · 发现",false,"error"))
    }
    @Test fun reordersArgumentsAndCanSwitchBack() {
        val english=catalogue.message("旧版《发现》有 3 次。",true,"error")
        assertEquals("Older records show 3 plays of “发现”.",english)
        assertEquals("舊版《发现》有 3 次。",catalogue.message(english,false,"error"))
    }
    @Test fun summaryKeepsQuotedMetadataAndSeparatesSentences() {
        val summary="你把《新歌池已准备好》加入了最爱。旧版《发现》有 3 次。"
        assertEquals("You added “新歌池已准备好” to Favorites. Older records show 3 plays of “发现”.",catalogue.summary(summary,true,"error"))
    }
    @Test fun unknownChineseSystemCopyUsesLocalizedFallback() {
        assertEquals("Try again",catalogue.message("未知错误文案",true,"Try again"))
        assertEquals("HTTP 503",catalogue.message("HTTP 503",true,"Try again"))
    }
}
