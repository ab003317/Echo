package org.dddd010010.serein

import org.json.JSONArray

/** Explicit presentation boundary for persisted system copy from older servers/clients. */
object RemoteText {
    private val catalogue by lazy {
        val array = JSONArray(Library.context.assets.open("system-copy.json").bufferedReader().use { it.readText() })
        MessageCatalogue(array.objects().map { MessageCatalogue.Entry(it.getString("source"), it.getString("zh"), it.getString("en")) })
    }
    fun message(text: String): String = catalogue.message(text, AppLocale.language == "en", tr(R.string.request_failed))
    fun summary(text: String): String = catalogue.summary(text, AppLocale.language == "en", tr(R.string.analysis_summary_unavailable))
}
