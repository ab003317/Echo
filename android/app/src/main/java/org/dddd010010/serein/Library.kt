package org.dddd010010.serein

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Song(val id: String, val title: String, val artist: String, val album: String,
                val duration: Double, val size: Long, val mtime: Double, val hasCover: Boolean, val pool:String="") {
    fun json()=JSONObject().put("id",id).put("title",title).put("artist",artist).put("album",album)
        .put("duration",duration).put("size",size).put("mtime",mtime).put("cover",if(hasCover)1 else 0).put("pool",pool)
    companion object {
        fun from(j: JSONObject) = Song(j.getString("id"), j.optString("title"), j.optString("artist"),
            j.optString("album"), j.optDouble("duration", 0.0), j.optLong("size"), j.optDouble("mtime", 0.0), j.optInt("cover") == 1,j.optString("pool"))
    }
}
class ApiError(val statusCode: Int, message: String) : IllegalStateException(message)

class SereinApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader() = ArtworkImages.loader(this)
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config); AppLocale.changed()
    }
    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
        Library.init(this)
        OfflineWorker.schedule(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var wifiReady = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ready = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ready && !wifiReady) {
                    OfflineWorker.kick(this@SereinApp)
                }
                wifiReady = ready
                Library.wifiAvailable = ready
                Library.online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) { wifiReady = false; Library.wifiAvailable=false; Library.online=false }
        })
    }
}

object Library {
    lateinit var context: Context
    private val prefs get(): android.content.SharedPreferences {
        // Subscribe at the actual UI read site, including remembered sheet/item lambdas.
        // Controller progress and queue persistence do not increment this revision.
        revision
        return context.getSharedPreferences(profile, Context.MODE_PRIVATE)
    }
    private val connections get()=context.getSharedPreferences("servers",Context.MODE_PRIVATE)
    val profile get():String { revision;return connections.getString("profile","library")!! }
    val mediaDir get()=if(profile=="library")context.filesDir else File(context.filesDir,profile).apply{mkdirs()}
    var revision by mutableIntStateOf(0)
        private set
    val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).build()
    private val cacheScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    var ready by mutableStateOf(false)
        private set
    private val catalogCache=ParsedValue { raw -> runCatching{JSONArray(raw).objects().map{Song.from(it)}}.getOrDefault(emptyList()) }
    private val statsCache=ParsedValue { raw ->
        val data=runCatching{JSONObject(raw)}.getOrDefault(JSONObject())
        data.keys().asSequence().associateWith{id->data.optJSONObject(id).let{(it?.optInt("plays") ?: 0) to (it?.optLong("last") ?: 0L)}}
    }
    private val recommendationsCache=ParsedValue { raw -> runCatching{JSONArray(raw).let{a->(0 until a.length()).map{a.getString(it)}}}.getOrDefault(emptyList()) }
    private var downloadedFiles by mutableStateOf<Map<String,Long>>(emptyMap())
    var partialFiles by mutableStateOf<Set<String>>(emptySet())
        private set
    var coverFiles by mutableStateOf<Set<String>>(emptySet())
        private set
    var offlineBytes by mutableStateOf(0L)
        private set
    fun init(ctx: Context) {
        context=ctx.applicationContext
        cacheScope.launch {
            downloads.mkdirs();prefs.edit().remove("secret").apply()
            songs;statsCache.get(prefs.getString("stats","{}")!!);recIds
            scanFiles();ready=true
        }
    }
    private val fileIndexLock=Any()
    private fun scanFiles()=synchronized(fileIndexLock){
        val files=downloads.listFiles().orEmpty()
        downloadedFiles=files.filter{it.extension=="audio"}.associate{it.nameWithoutExtension to it.length()}
        offlineBytes=files.sumOf{it.length()}
        partialFiles=files.filter{it.extension=="part"}.map{it.nameWithoutExtension}.toSet()
        coverFiles=mediaDir.listFiles().orEmpty().filter{it.extension=="cover"}.map{it.nameWithoutExtension}.toSet()
    }
    fun refreshFiles(){cacheScope.launch{scanFiles()}}
    /** Presentation-only index; playback and downloads still verify the actual file. */
    fun downloaded(s:Song)=downloadedFiles[s.id]==s.size && prefs.getString("version_${s.id}","")==s.mtime.toString()
    val downloads get() = File(mediaDir, "downloads")
    val base get() = prefs.getString("base", "")!!.trimEnd('/')
    var online by mutableStateOf(false)
    var wifiAvailable by mutableStateOf(false)
    var activeDownload by mutableStateOf("")
    var downloadBytes by mutableStateOf(0L)
    var downloadTotal by mutableStateOf(0L)
    var downloadPaused: Boolean
        get() = prefs.getBoolean("downloadPaused", false)
        set(value) { prefs.edit().putBoolean("downloadPaused", value).apply(); changed() }
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply(); changed() }
    var budget: Long
        get() = prefs.getLong("budget", 2L * 1024 * 1024 * 1024)
        set(v) { prefs.edit().putLong("budget", v).apply(); changed() }
    var sources: Set<String>
        get() = prefs.getStringSet("sources", setOf("recent", "frequent", "favorite"))!!.toSet()
        set(v) { prefs.edit().putStringSet("sources", v.toSet()).apply(); changed() }
    val status: String
        get() {
            val saved=obj("downloadStatusV2")
            val key=saved.optString("key")
            if(key.isBlank())return RemoteText.message(prefs.getString("downloadStatus",tr(R.string.ui_connect_to_wi_fi_to_prepare_offline_music))!!)
            val args=saved.optJSONArray("args") ?: JSONArray()
            return AppLocale.named(key,*(0 until args.length()).map{args.get(it)}.toTypedArray())
        }
    fun setStatus(@androidx.annotation.StringRes id:Int,vararg args:Any) {
        save("downloadStatusV2",JSONObject().put("key",AppLocale.name(id)).put("args",JSONArray(args.toList())))
    }
    val songs: List<Song> get() = catalogCache.get(prefs.getString("catalog","[]")!!)
    fun knownSong(id:String?):Song? {
        if(id.isNullOrBlank())return null
        return songs.find{it.id==id} ?: mixEditions().flatMap{it.songs}.find{it.id==id}
            ?: (array("historyLocal").objects()+array("historyRemote").objects()).firstNotNullOfOrNull{row->
                row.optJSONObject("song")?.takeIf{it.optString("id")==id}?.let{Song.from(it)}
            }
    }
    val pinned: Set<String> get() = prefs.getStringSet("pinned", emptySet())!!.toSet()
    val excluded: Set<String> get() = prefs.getStringSet("excluded", emptySet())!!.toSet()
    val favorites: Set<String> get() = prefs.getStringSet("favorites", emptySet())!!.toSet()
    val contentExcluded: Set<String> get() = prefs.getStringSet("contentExcluded", emptySet())!!.toSet()
    @Synchronized fun applyContentExclusions(data:JSONObject) {
        val ids=data.optJSONArray("excluded") ?: return
        val next=(0 until ids.length()).map{ids.getString(it)}.toSet()
        if(next!=contentExcluded){prefs.edit().putStringSet("contentExcluded",next).apply();changed()}
    }
    @Synchronized fun hideReportedTrack(id:String) {
        save("catalog",JSONArray(array("catalog").objects().filter{it.getString("id")!=id}))
    }
    val used get() = downloads.listFiles()?.sumOf { it.length() } ?: 0L
    val playlists get() = obj("playlists")
    val recIds get() = recommendationsCache.get(prefs.getString("recommendations","[]")!!)
    fun file(s: Song) = File(downloads, s.id + ".audio")
    fun available(s: Song) = file(s).isFile && file(s).length() == s.size && prefs.getString("version_${s.id}", "") == s.mtime.toString()
    fun art(s: Song) = "$base/api/tracks/${s.id}/cover?v=${s.mtime}"
    fun audio(s: Song) = "$base/api/tracks/${s.id}/audio"
    fun plays(s: Song) = statsCache.get(prefs.getString("stats","{}")!!)[s.id]?.first ?: 0
    fun last(s: Song) = statsCache.get(prefs.getString("stats","{}")!!)[s.id]?.second ?: 0L
    fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    fun obj(key: String) = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
    @Synchronized fun save(key: String, data: Any, notify: Boolean = true) {
        val raw=data.toString()
        if(prefs.getString(key,null)==raw)return
        prefs.edit().putString(key,raw).apply()
        if(key=="catalog")catalogCache.get(raw)
        if(key=="stats")statsCache.get(raw)
        if(notify)changed()
    }
    fun setting(key: String, default: Int = 0) = prefs.getInt(key, default)
    fun setting(key: String, value: Int, write: Boolean) { prefs.edit().putInt(key, value).apply() }
    fun changed() { revision++ }
    @Synchronized fun keep(s: Song) {
        prefs.edit().putStringSet("pinned", pinned + s.id).putStringSet("excluded", excluded - s.id).apply()
        changed(); OfflineWorker.kick(context)
    }
    @Synchronized fun remove(s: Song) {
        prefs.edit().putStringSet("pinned", pinned - s.id).putStringSet("excluded", excluded + s.id).apply()
        file(s).delete(); File(downloads, s.id + ".part").delete()
        downloadedFiles=downloadedFiles-s.id;refreshFiles()
        setStatus(R.string.ui_offline_songs_ready, songs.count { available(it) }); changed()
    }
    fun resetExclusions() { prefs.edit().remove("excluded").apply(); changed(); OfflineWorker.kick(context) }
    fun setVersion(s: Song) { prefs.edit().putString("version_${s.id}", s.mtime.toString()).apply(); refreshFiles();changed() }
    @Synchronized fun toggleFavorite(s: Song) {
        val liked = s.id !in favorites
        prefs.edit().putStringSet("favorites", if(liked) favorites + s.id else favorites - s.id).apply()
        val out = obj("favoriteOutbox").put(s.id, JSONObject().put("liked", liked).put("updated", System.currentTimeMillis()/1000.0))
        save("favoriteOutbox", out); OfflineWorker.kick(context)
    }
    @Synchronized fun record(s: Song, seconds: Double) {
        val stats = obj("stats"); val previous = stats.optJSONObject(s.id) ?: JSONObject()
        stats.put(s.id, previous.put("plays", previous.optInt("plays") + 1).put("last", System.currentTimeMillis()))
        save("stats", stats)
        val event = JSONObject().put("event", UUID.randomUUID().toString()).put("track", s.id).put("seconds", seconds).put("at", System.currentTimeMillis()/1000.0)
        save("listenOutbox", array("listenOutbox").put(event))
        rememberHistory(JSONObject(event.toString()).put("started",event.getDouble("at")).put("outcome","legacy").put("seq",0).put("song",s.json()).put("available",true))
        OfflineWorker.kick(context)
    }
    @Synchronized fun recordPlayback(p:PlaybackTelemetry.Checkpoint) {
        val value=JSONObject().put("event",p.event).put("track",p.track).put("seconds",p.seconds)
            .put("position",p.position).put("duration",p.duration).put("started",p.started)
            .put("at",p.at).put("outcome",p.outcome).put("seq",p.seq)
        val out=obj("playbackOutbox").put(p.event,value)
        val edit=prefs.edit().putString("playbackOutbox",out.toString())
        if(p.qualifiedNow) {
            val stats=obj("stats");val old=stats.optJSONObject(p.track) ?: JSONObject()
            stats.put(p.track,old.put("plays",old.optInt("plays")+1).put("last",(p.at*1000).toLong()))
            edit.putString("stats",stats.toString())
        }
        edit.apply()
        val song=knownSong(p.track)
        if(song!=null && p.seconds>0)rememberHistory(JSONObject(value.toString()).put("song",song.json()).put("available",true))
        if(p.qualifiedNow || p.outcome!="progress" || p.seq==1)changed()
    }
    @Synchronized private fun rememberHistory(entry:JSONObject) {
        val rows=array("historyLocal").objects().filter{it.optString("event")!=entry.getString("event")}+entry
        save("historyLocal",JSONArray(rows.sortedByDescending{it.optDouble("started",0.0)}.take(2000)),notify=false)
    }
    @Synchronized fun acceptHistory(entries:JSONArray) {
        val rows=array("historyRemote").objects()+entries.objects()
        val merged=SessionOrder.merge(rows,{it.getString("event")},{it.optInt("seq")},{it.optDouble("at",0.0)},{it.optDouble("started",0.0)})
        save("historyRemote",JSONArray(merged.take(2000)))
    }
    @Synchronized fun acceptMixFeed(data:JSONObject) {
        val pending=obj("mixSavedOutbox")
        val incoming=data.getJSONArray("mixes").objects().associateBy{it.getString("id")}.toMutableMap()
        for(old in obj("mixFeed").optJSONArray("mixes")?.objects().orEmpty()) {
            val id=old.getString("id")
            if(pending.has(id) && !incoming.containsKey(id))incoming[id]=JSONObject(old.toString()).put("active",false)
        }
        incoming.forEach{(id,row)->pending.optJSONObject(id)?.let{row.put("saved",it.getBoolean("saved"))}}
        save("mixFeed",data.put("mixes",JSONArray(incoming.values.toList())))
    }
    @Synchronized fun toggleMix(mix:MixEdition) {
        val data=obj("mixFeed");val rows=data.optJSONArray("mixes")?.objects().orEmpty().toMutableList()
        val row=rows.find{it.getString("id")==mix.id} ?: JSONObject(mix.raw.toString()).also{rows.add(it)}
        val saved=!row.optBoolean("saved")
        val pending=obj("mixSavedOutbox")
        val updated=maxOf(System.currentTimeMillis()/1000.0,(pending.optJSONObject(mix.id)?.optDouble("updated",0.0) ?: 0.0)+.001)
        row.put("saved",saved)
        // Cache and outbox are committed together, so offline likes cannot disappear between writes.
        prefs.edit().putString("mixFeed",data.put("mixes",JSONArray(rows)).toString())
            .putString("mixSavedOutbox",pending.put(mix.id,JSONObject().put("saved",saved).put("updated",updated)).toString()).apply()
        changed();OfflineWorker.kick(context)
    }
    fun refreshMixes(client:OkHttpClient=http)=synchronized(syncLock) {
        flush(client);acceptMixFeed(api("mixes",client=client))
    }
    @Synchronized fun acceptReadyTracks(tracks:JSONArray) {
        if(tracks.length()==0)return
        val merged=array("catalog").objects().associateBy{it.getString("id")}.toMutableMap()
        tracks.objects().forEach{merged[it.getString("id")]=it}
        merge(JSONArray(merged.values.toList()))
    }
    @Synchronized fun playlist(name: String, song: Song? = null) {
        val all = playlists; val list = all.optJSONArray(name) ?: JSONArray()
        if (song != null && (0 until list.length()).none { list.getString(it) == song.id }) list.put(song.id)
        save("playlists", all.put(name, list))
    }
    @Synchronized fun deletePlaylist(name: String) { val all = playlists; all.remove(name); save("playlists", all) }
    @Synchronized fun removeFromPlaylist(name: String, s: Song) {
        val all = playlists; val old = all.optJSONArray(name) ?: return
        val next = JSONArray(); for(i in 0 until old.length()) if(old.getString(i) != s.id) next.put(old.getString(i))
        save("playlists", all.put(name, next))
    }
    fun request(path: String) = Request.Builder().url("$base/api/$path").header("User-Agent", "Echo/0.8").header("Accept-Language", AppLocale.language)
    fun api(path: String, method: String = "GET", body: JSONObject? = null, client: OkHttpClient = http): JSONObject {
        val serverProfile=profile
        val req = request(path)
        if(method != "GET") req.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        client.newCall(req.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if(profile!=serverProfile)throw kotlinx.coroutines.CancellationException("Server changed")
            if(!r.isSuccessful) throw ApiError(r.code, runCatching { RemoteText.message(JSONObject(text).optString("detail")) }.getOrDefault(tr(R.string.ui_connection_failed, r.code)))
            return JSONObject(text)
        }
    }
    suspend fun changeServer(url: String) = withContext(Dispatchers.IO) {
        val normalized = try { ServerAddress.normalize(url) } catch(e:Exception) { throw IllegalArgumentException(tr(R.string.setup_address_hint)) }
        val request = Request.Builder().url("$normalized/api/library").header("User-Agent", "Echo/0.8").header("Accept-Language", AppLocale.language).build()
        val tracks=http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { tr(R.string.ui_library_unavailable, response.code) }
            JSONObject(response.body!!.string()).getJSONArray("tracks")
        }
        tracks.objects().forEach { Song.from(it) }
        val identity=runCatching {
            http.newCall(Request.Builder().url("$normalized/api/health").build()).execute().use {
                JSONObject(it.body!!.string()).optString("instanceId").takeIf { id->id.matches(Regex("[a-fA-F0-9-]{36}")) }
            }
        }.getOrNull() ?: normalized
        synchronized(syncLock) {
            val mapping="server_"+java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
            val legacy=prefs.getString("base","").orEmpty()
            val selected=connections.getString(mapping,null) ?: if(profile=="library" && (legacy==normalized || (legacy.isEmpty() && songs.isNotEmpty())))"library" else mapping
            connections.edit().putString(mapping,selected).putString("profile",selected).commit()
            downloads.mkdirs()
            prefs.edit().putString("base",normalized).commit()
            merge(tracks)
            scanFiles()
            changed()
        }
        OfflineWorker.kick(context)
    }
    @Synchronized fun renamePlaylist(old: String, name: String) {
        val all = playlists
        require(name.isNotBlank() && (name == old || !all.has(name))) { tr(R.string.ui_a_playlist_with_this_name_already_exists) }
        if(name != old) { val items = all.getJSONArray(old); all.remove(old); all.put(name, items); save("playlists", all) }
    }
    fun unpin(s: Song) { prefs.edit().putStringSet("pinned", pinned - s.id).apply(); changed() }
    fun pauseDownloads() { downloadPaused=true; setStatus(R.string.ui_downloads_paused); androidx.work.WorkManager.getInstance(context).cancelUniqueWork("offline-sync") }
    fun resumeDownloads() { downloadPaused=false; setStatus(if(wifiAvailable) R.string.ui_preparing_downloads else R.string.ui_waiting_for_wi_fi); OfflineWorker.kick(context) }
    @Synchronized private fun merge(tracks: JSONArray) {
        val pending = obj("favoriteOutbox"); val fav = favorites.toMutableSet(); val stats = obj("stats")
        for(s in tracks.objects()) {
            val id = s.getString("id")
            if(!pending.has(id)) { if(s.optInt("favorite") == 1) fav.add(id) else fav.remove(id) }
            val old = stats.optJSONObject(id) ?: JSONObject()
            old.put("plays", maxOf(old.optInt("plays"), s.optInt("plays")))
            old.put("last", maxOf(old.optLong("last"), (s.optDouble("lastPlayed", 0.0)*1000).toLong()))
            stats.put(id, old)
        }
        val statsRaw=stats.toString();val catalogRaw=tracks.toString()
        if(fav==favorites && statsRaw==prefs.getString("stats",null) && catalogRaw==prefs.getString("catalog",null))return
        catalogCache.get(catalogRaw);statsCache.get(statsRaw)
        prefs.edit().putStringSet("favorites", fav).putString("stats", statsRaw).putString("catalog", catalogRaw).apply(); changed()
    }
    fun refresh(client: OkHttpClient = http, seed: String = "") = synchronized(syncLock) {
        flush(client)
        val library=api("library", client=client)
        applyContentExclusions(library)
        merge(library.getJSONArray("tracks"))
        val recs = api("recommendations?seed=" + java.net.URLEncoder.encode(seed,"UTF-8"), client=client).getJSONArray("tracks").objects().map { it.getString("id") }
        val valid=songs.map{it.id}.toSet()
        val ordered=if(seed.isNotBlank())FeedContinuity.refresh(recIds,recs,{it}).take(40)
            else (recIds.filter{it in valid}+recs).distinct().take(40)
        save("recommendations", JSONArray(ordered))
        runCatching{acceptMixFeed(api("mixes",client=client))}
    }
    private val syncLock = Any()
    fun flush(client: OkHttpClient = http) = synchronized(syncLock) {
        val playback=obj("playbackOutbox")
        for(id in playback.keys()) {
            val value=playback.getJSONObject(id)
            try { api("playback","POST",value,client) } catch(e:ApiError) { if(e.statusCode!=404)throw e }
            synchronized(this) {
                val current=obj("playbackOutbox")
                if(current.optJSONObject(id)?.optInt("seq")==value.optInt("seq"))current.remove(id)
                save("playbackOutbox",current,notify=false)
            }
        }
        val listens = array("listenOutbox").objects()
        for(event in listens) {
            try { api("listens", "POST", event, client) } catch(e: ApiError) { if(e.statusCode != 404) throw e }
            synchronized(this) { save("listenOutbox", JSONArray(array("listenOutbox").objects().filter { it.getString("event") != event.getString("event") })) }
        }
        val out = obj("favoriteOutbox")
        for(id in out.keys()) {
            val value = out.getJSONObject(id)
            try { api("tracks/$id/favorite", "PUT", value, client) } catch(e: ApiError) { if(e.statusCode != 404) throw e }
            synchronized(this) {
                val current = obj("favoriteOutbox")
                if(current.optJSONObject(id)?.toString() == value.toString()) current.remove(id)
                save("favoriteOutbox", current)
            }
        }
        val saved=obj("mixSavedOutbox")
        for(id in saved.keys()) {
            val value=saved.getJSONObject(id)
            api("mixes/$id/saved","PUT",value,client)
            synchronized(this){
                val current=obj("mixSavedOutbox")
                if(current.optJSONObject(id)?.toString()==value.toString())current.remove(id)
                save("mixSavedOutbox",current)
            }
        }
    }
    suspend fun apiAsync(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val builder = request(path)
            if(method != "GET") builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            val call = http.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if(continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching { response.use { r ->
                        val text = r.body?.string().orEmpty()
                        if(!r.isSuccessful) throw ApiError(r.code, runCatching { RemoteText.message(JSONObject(text).optString("detail")) }.getOrDefault(tr(R.string.ui_connection_failed, r.code)))
                        JSONObject(text)
                    } }
                    if(continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
