package org.dddd010010.serein

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

val LocalArtworkActive=compositionLocalOf { true }

object ArtworkImages {
    private val tones=mutableStateMapOf<String,Color>()
    fun loader(context:Context):ImageLoader {
        // Keep image traffic separate from API/audio calls, and bound decode pressure.
        val dispatcher=Dispatcher().apply{maxRequests=3;maxRequestsPerHost=3}
        val regular=OkHttpClient.Builder().dispatcher(dispatcher).connectTimeout(8,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
        // Reserve one image connection for the current player; a grid cannot queue ahead of it.
        val playback=regular.newBuilder().dispatcher(Dispatcher().apply{maxRequests=1;maxRequestsPerHost=1}).build()
        return ImageLoader.Builder(context)
            .callFactory { okhttp3.Call.Factory { request ->
                val client=if(request.header("X-Echo-Image-Priority")=="playback")playback else regular
                client.newCall(request.newBuilder().removeHeader("X-Echo-Image-Priority").build())
            } }
            .memoryCache{MemoryCache.Builder(context).maxSizePercent(.15).build()}
            .diskCache{DiskCache.Builder().directory(File(context.cacheDir,"image_cache")).maxSizeBytes(192L*1024*1024).build()}
            .bitmapFactoryMaxParallelism(2)
            .crossfade(false)
            .build()
    }
    fun key(song:Song)=Library.art(song)
    fun source(song:Song,local:Boolean):Any=if(local)File(Library.mediaDir,song.id+".cover") else key(song)
    /** A tiny decode of the cover for page atmospheres; stretching it is the blur. Shares the disk cache with the full cover. */
    fun ambient(context:Context,data:Any,cacheKey:String)=ImageRequest.Builder(context)
        .data(data).size(24,24)
        .memoryCacheKey("$cacheKey#ambient").diskCacheKey(cacheKey)
        .addHeader("User-Agent","Echo/0.7").addHeader("X-Echo-Image-Priority","list")
        .build()
    fun request(context:Context,song:Song,local:Boolean,tone:Boolean,priority:Boolean)=ImageRequest.Builder(context)
        .data(source(song,local))
        .memoryCacheKey(key(song)).diskCacheKey(key(song))
        .addHeader("User-Agent","Echo/0.7").addHeader("X-Echo-Image-Priority",if(priority)"playback" else "list")
        .allowHardware(!tone).build()
    fun tone(song:Song)=(if(song.hasCover)tones[key(song)] else null) ?: RecordFallbackTone
    suspend fun sample(song:Song,drawable:Drawable){
        val key=key(song)
        if(key in tones)return
        val color=withContext(Dispatchers.Default){runCatching{
            val bitmap=drawable.toBitmap(32,32)
            var red=0.0;var green=0.0;var blue=0.0;var weight=0.0
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){
                val p=bitmap.getPixel(x,y);val r=android.graphics.Color.red(p)/255.0;val g=android.graphics.Color.green(p)/255.0;val b=android.graphics.Color.blue(p)/255.0
                val max=maxOf(r,g,b);val min=minOf(r,g,b);val w=(max-min)*(max-min)+.025
                if(max>.12 && min<.9){red+=r*w;green+=g*w;blue+=b*w;weight+=w}
            }
            if(weight==0.0)Panel else Color((red/weight).toFloat(),(green/weight).toFloat(),(blue/weight).toFloat())
        }.getOrDefault(Panel)}
        if(tones.size>=48)tones.remove(tones.keys.first())
        tones[key]=color
    }
}
