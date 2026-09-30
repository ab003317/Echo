package org.dddd010010.serein

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** UI language is independent of music metadata and persistent library identifiers. */
object AppLocale {
    private lateinit var app: Context
    private val resources = java.util.concurrent.ConcurrentHashMap<String, android.content.res.Resources>()
    private val counts=mapOf(
        R.string.ui_offline_songs_ready to (R.plurals.ui_offline_songs_ready_count to 0),
        R.string.ui_tracks to (R.plurals.ui_tracks_count to 1),
        R.string.ui_tracks_89 to (R.plurals.ui_tracks_89_count to 0),
        R.string.ui_a_different_mix_every_hours to (R.plurals.ui_a_different_mix_every_hours_count to 0),
        R.string.ui_every_hours to (R.plurals.ui_every_hours_count to 0),
        R.string.ui_tracks_111 to (R.plurals.ui_tracks_111_count to 0),
        R.string.ui_tracks_added_order to (R.plurals.ui_tracks_added_order_count to 0),
        R.string.ui_songs_excluded to (R.plurals.ui_songs_excluded_count to 0),
        R.string.ui_allow_these_excluded_songs_to_be_downloaded_automatically_again to (R.plurals.ui_allow_these_excluded_songs_to_be_downloaded_automatically_again_count to 0),
        R.string.ui_stop_in_minutes to (R.plurals.ui_stop_in_minutes_count to 0),
        R.string.ui_songs_preparing to (R.plurals.ui_songs_preparing_count to 0),
        R.string.ui_plays to (R.plurals.ui_plays_count to 0)
    )
    var language by mutableStateOf("zh-Hant")
        private set
    val locale: Locale get() = Locale.forLanguageTag(language)
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private fun normalize(tag: String) = if (tag.startsWith("en")) "en" else "zh-Hant"
    private fun preference(ctx: Context) = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private fun selected(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            if (!locales.isEmpty) return normalize(locales[0].toLanguageTag())
        }
        return normalize(preference(ctx).getString("language", "zh-Hant")!!)
    }
    fun init(ctx: Context) {
        app = ctx
        language = selected(ctx)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = ctx.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList(locale)
        }
    }
    fun wrap(ctx: Context): Context {
        val config = Configuration(ctx.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(selected(ctx))))
        return ctx.createConfigurationContext(config)
    }
    fun changed() {
        resources.clear()
        language = selected(app)
        Locale.setDefault(locale)
        listeners.forEach { it() }
    }
    fun choose(activity: Activity, tag: String) {
        val next = normalize(tag)
        if (language == next) return
        preference(app).edit().putString("language", next).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(next)
            changed()
        } else {
            language = next
            Locale.setDefault(locale)
            listeners.forEach { it() }
            activity.recreate()
        }
    }
    fun text(@StringRes id: Int, vararg args: Any): String {
        // Read Compose state at the call site, including sheets and remembered lazy items.
        val tag = language
        val localized = resources.getOrPut(tag) {
            val config = Configuration(app.resources.configuration)
            config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            app.createConfigurationContext(config).resources
        }
        counts[id]?.let{(resource, index)->
            args.getOrNull(index)?.toString()?.toIntOrNull()?.let{return localized.getQuantityString(resource,it,*args)}
        }
        // Literal percentages in explanatory copy are not format placeholders.
        return if(args.isEmpty())localized.getString(id) else localized.getString(id, *args)
    }
    fun named(name: String, vararg args: Any): String {
        val id = app.resources.getIdentifier(name, "string", app.packageName)
        return text(if(id != 0) id else R.string.request_failed, *args)
    }
    fun name(@StringRes id: Int) = app.resources.getResourceEntryName(id)
}

fun tr(@StringRes id: Int, vararg args: Any): String = AppLocale.text(id, *args)
