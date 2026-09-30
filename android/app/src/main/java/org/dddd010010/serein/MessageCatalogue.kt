package org.dddd010010.serein

/** Translates known system templates without ever translating their metadata arguments. */
class MessageCatalogue(entries: List<Entry>) {
    data class Entry(val source: String, val zh: String, val en: String)
    private data class Template(val entry: Entry, val regex: Regex, val arguments: List<Int>)
    private val token = Regex("\\{(\\d+)\\}")
    private val exact = mutableMapOf<String, Entry>()
    private val templates = mutableListOf<Template>()
    init {
        for (entry in entries) for (text in listOf(entry.source, entry.zh, entry.en).distinct()) {
            val arguments = mutableListOf<Int>()
            val pattern = StringBuilder(); var at = 0
            for (part in token.findAll(text)) {
                pattern.append(Regex.escape(text.substring(at, part.range.first))).append("([\\s\\S]+?)")
                arguments.add(part.groupValues[1].toInt()); at = part.range.last + 1
            }
            pattern.append(Regex.escape(text.substring(at)))
            if (arguments.isEmpty()) exact[text] = entry
            templates.add(Template(entry, Regex(pattern.toString()), arguments))
        }
    }
    private fun render(template: Template, match: MatchResult, english: Boolean): String {
        val args = template.arguments.mapIndexed { i, key -> key to match.groupValues[i + 1] }.toMap()
        return token.replace(if (english) template.entry.en else template.entry.zh) { args[it.groupValues[1].toInt()].orEmpty() }
    }
    fun message(text: String, english: Boolean, fallback: String): String {
        if (text.isBlank()) return text
        exact[text]?.let { return if (english) it.en else it.zh }
        for (template in templates) template.regex.matchEntire(text)?.let { return render(template, it, english) }
        // Unrecognized technical errors in English remain useful; unknown Chinese system
        // copy falls back to a localized error. Never call this on song/user metadata.
        return if (Regex("[\\u3400-\\u9fff]").containsMatchIn(text)) fallback else text
    }
    fun summary(text: String, english: Boolean, fallback: String): String {
        var at = 0; val result = mutableListOf<String>()
        while (at < text.length) {
            if (text[at].isWhitespace()) { at++; continue }
            val found = templates.firstNotNullOfOrNull { template ->
                template.regex.matchAt(text, at)?.let { template to it }
            } ?: return fallback
            result.add(render(found.first, found.second, english))
            at = found.second.range.last + 1
        }
        return result.joinToString(if (english) " " else "")
    }
}
