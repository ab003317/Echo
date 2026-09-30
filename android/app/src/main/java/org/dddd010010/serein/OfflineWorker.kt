package org.dddd010010.serein

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read

class OfflineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { Library.serverLock.read {
        if(Library.base.isBlank() || Library.downloadPaused) return@withContext Result.success()
        val serverProfile=Library.profile
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true } ?: run { Library.setStatus(R.string.ui_waiting_for_wi_fi); return@withContext Result.retry() }
        // Bind sockets AND DNS to Wi-Fi. An OS route change cannot silently use cellular.
        val client = Library.http.newBuilder().socketFactory(network.socketFactory)
            .dns(object : Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() }).readTimeout(20, TimeUnit.SECONDS).build()
        try {
            Library.setStatus(R.string.ui_preparing_offline_music)
            Library.refresh(client)
            val all = Library.songs
            val groups = mutableListOf<List<Song>>()
            if(Library.auto) {
                if("recent" in Library.sources) groups.add(all.filter { Library.last(it)>0 }.sortedByDescending { Library.last(it) })
                if("frequent" in Library.sources) groups.add(all.filter { Library.plays(it)>0 }.sortedByDescending { Library.plays(it) })
                if("favorite" in Library.sources) groups.add(all.filter { it.id in Library.favorites })
                if("recommended" in Library.sources) groups.add(Library.recIds.mapNotNull { id -> all.find { it.id == id } })
            } else groups.add(all.filter { Library.available(it) }.sortedByDescending { Library.last(it) })
            fun entries(songs: List<Song>) = songs.map { OfflinePlan.Entry(it.id, it.size) }
            val pinned = all.filter { it.id in Library.pinned }
            val plan = OfflinePlan.choose(entries(pinned), groups.map { entries(it) }, Library.excluded, Library.budget)
            val ids = plan.map { it.id }.toSet()
            // Automatic copies rotate; manually pinned copies survive reduced budgets.
            for(file in Library.downloads.listFiles().orEmpty()) {
                if(file.name.substringBefore('.') !in ids && file.name.substringBefore('.') !in Library.pinned) file.delete()
            }
            if(pinned.sumOf { it.size } > Library.budget) {
                Library.setStatus(R.string.ui_manually_kept_songs_exceed_the_limit_increase_storage_or_remove_s)
                return@withContext Result.success()
            }
            for((index, entry) in plan.withIndex()) {
                if(isStopped || Library.profile!=serverProfile) return@withContext Result.retry()
                val s = all.first { it.id == entry.id }
                if(s.id in Library.excluded) continue
                if(Library.available(s)) continue
                Library.setStatus(R.string.ui_saving, index+1, plan.size, s.title)
                Library.activeDownload=s.id
                Library.downloadTotal=s.size
                val part = File(Library.downloads, s.id + ".part")
                val versionFile = File(applicationContext.cacheDir, serverProfile+"_"+s.id + ".version")
                if(!versionFile.exists() || versionFile.readText() != s.mtime.toString()) { part.delete(); Library.file(s).delete() }
                versionFile.writeText(s.mtime.toString())
                val offset = part.length()
                if(offset > s.size) part.delete()
                val remaining = s.size - part.length()
                Library.downloadBytes=part.length()
                if(Library.downloads.usableSpace < remaining + 100L*1024*1024 || Library.used + remaining > Library.budget) {
                    Library.setStatus(R.string.ui_not_enough_storage_completed_downloads_are_kept); return@withContext Result.success()
                }
                if(part.length() < s.size) {
                    val start = part.length()
                    val request = Library.request("tracks/${s.id}/audio").header("Range", "bytes=$start-").build()
                    client.newCall(request).execute().use { response ->
                        downloadCheck(response.isSuccessful,R.string.ui_download_failed,response.code)
                        val append = start>0 && response.code == 206
                        if(response.code == 206) downloadCheck(response.header("Content-Range")?.startsWith("bytes $start-") == true,R.string.ui_server_could_not_resume_this_download_correctly)
                        java.io.FileOutputStream(part, append).use { out -> response.body!!.byteStream().use { input ->
                            val buffer = ByteArray(65536)
                            var lastProgress=0L
                            while(true) {
                                if(isStopped || Library.profile!=serverProfile || Library.downloadPaused || s.id in Library.excluded || cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) throw DownloadFailure(R.string.ui_downloads_paused)
                                val count = input.read(buffer); if(count<0) break
                                downloadCheck(part.length()+count <= s.size,R.string.ui_the_song_file_has_changed_try_again)
                                out.write(buffer,0,count)
                                if(System.currentTimeMillis()-lastProgress>300) { Library.downloadBytes=part.length(); lastProgress=System.currentTimeMillis() }
                            }
                            out.fd.sync()
                        } }
                    }
                }
                downloadCheck(part.length() == s.size,R.string.ui_download_incomplete_it_will_resume_automatically)
                if(s.id !in Library.excluded) {
                    downloadCheck(part.renameTo(Library.file(s)),R.string.ui_could_not_save_the_file); Library.setVersion(s)
                    if(s.hasCover) runCatching {
                        client.newCall(Library.request("tracks/${s.id}/cover").build()).execute().use { r ->
                            if(r.isSuccessful) File(Library.mediaDir, s.id + ".cover").writeBytes(r.body!!.bytes())
                        }
                    }
                }
            }
            Library.setStatus(R.string.ui_offline_songs_ready, all.count { Library.available(it) })
            Result.success()
        } catch(e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch(e: Exception) {
            if(e is DownloadFailure)Library.setStatus(e.id,*e.arguments) else Library.setStatus(R.string.ui_connection_lost_waiting_for_the_next_wi_fi_sync)
            Result.retry()
        } finally { Library.refreshFiles();Library.activeDownload=""; Library.downloadBytes=0; Library.downloadTotal=0 }
    } }
    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build()
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("offline-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OfflineTrigger>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun kick(ctx: Context) {
            if(Library.downloadPaused) return
            if(!Library.wifiAvailable) Library.setStatus(R.string.ui_waiting_for_wi_fi)
            WorkManager.getInstance(ctx).enqueueUniqueWork("offline-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OfflineWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}
class OfflineTrigger(ctx: Context, params: WorkerParameters): Worker(ctx,params) {
    override fun doWork(): Result { OfflineWorker.kick(applicationContext); return Result.success() }
}

private class DownloadFailure(val id:Int,vararg val arguments:Any): java.io.IOException()
private fun downloadCheck(condition:Boolean,id:Int,vararg arguments:Any) {
    if(!condition)throw DownloadFailure(id,*arguments)
}
