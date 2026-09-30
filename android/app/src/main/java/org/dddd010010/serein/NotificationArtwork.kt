package org.dddd010010.serein

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*

/** The notification uses the same versioned disk cache as the visible artwork. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NotificationArtwork(private val context:Context,private val scope:CoroutineScope):BitmapLoader {
    override fun supportsMimeType(mimeType:String)=mimeType in setOf("image/jpeg","image/png","image/webp","image/gif","image/bmp")
    override fun decodeBitmap(data:ByteArray):ListenableFuture<Bitmap> = load(data)
    override fun loadBitmap(uri:Uri):ListenableFuture<Bitmap> = load(uri)
    private fun load(data:Any):ListenableFuture<Bitmap>{
        val future=SettableFuture.create<Bitmap>()
        val job=scope.launch {
            try {
                val result=context.imageLoader.execute(ImageRequest.Builder(context).data(data).size(384).allowHardware(false)
                    .addHeader("User-Agent","Echo/0.7").addHeader("X-Echo-Image-Priority","playback").build())
                if(result is SuccessResult)future.set(result.drawable.toBitmap())
                else future.setException((result as ErrorResult).throwable)
            }catch(e:CancellationException){future.cancel(false);throw e}
            catch(e:Exception){future.setException(e)}
        }
        job.invokeOnCompletion{cause->if(cause!=null)future.setException(cause)}
        future.addListener({if(future.isCancelled)job.cancel()},MoreExecutors.directExecutor())
        return future
    }
}
