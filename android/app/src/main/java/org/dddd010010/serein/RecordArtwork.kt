package org.dddd010010.serein

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision

private val recordBackgrounds=intArrayOf(
    R.drawable.echo_doodle_01,R.drawable.echo_doodle_02,R.drawable.echo_doodle_03,R.drawable.echo_doodle_04,
    R.drawable.echo_doodle_05,R.drawable.echo_doodle_06,R.drawable.echo_doodle_07,R.drawable.echo_doodle_08,
    R.drawable.echo_doodle_09,R.drawable.echo_doodle_10,R.drawable.echo_doodle_11,R.drawable.echo_doodle_12,
    R.drawable.echo_doodle_13,R.drawable.echo_doodle_14,R.drawable.echo_doodle_15,R.drawable.echo_doodle_16,
    R.drawable.echo_doodle_17,R.drawable.echo_doodle_18,R.drawable.echo_doodle_19,R.drawable.echo_doodle_20
)
val RecordFallbackTone=Color(0xFF242424)
private val monochrome=ColorFilter.colorMatrix(ColorMatrix().apply{setToSaturation(0f)})

@Composable fun rememberRecordArtwork(seed:String,loadBackground:Boolean=true):Painter{
    val context=LocalContext.current
    val active=LocalArtworkActive.current
    val resource=recordBackgrounds[seed.hashCode().ushr(1)%recordBackgrounds.size]
    // Use Coil's bounded background decoder and shared memory cache; never decode all 20 on launch.
    val request=remember(context,resource,loadBackground,active){
        if(loadBackground && active)ImageRequest.Builder(context).data(resource).size(512).precision(Precision.EXACT)
            .memoryCacheKey("record-doodle-$resource").build() else null
    }
    val background=rememberAsyncImagePainter(request)
    return remember(seed,background){RecordArtwork(seed,background)}
}

/** A vector record sleeve: crisp at thumbnail size, with finer grooves on larger covers. */
private class RecordArtwork(private val seed:String,private val background:Painter):Painter(){
    override val intrinsicSize=Size.Unspecified
    override fun DrawScope.onDraw(){
        val side=size.minDimension
        drawRect(Color(0xFF111111))
        with(background){draw(size,colorFilter=monochrome)}
        val center=Offset(size.width*.5f,size.height*.5f)
        val radius=side*.415f
        // The rim, reflection and groove density share the same geometry at every size.
        drawCircle(Color.Black.copy(alpha=.22f),radius*1.025f,center+Offset(side*.015f,side*.025f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1B1B1B),Color(0xFF0B0B0B),Color(0xFF252525)),center,radius),radius,center)
        drawCircle(Color.White.copy(alpha=.16f),radius,center,style=Stroke((side*.002f).coerceAtLeast(.6f)))
        val grooves=if(side<180f)10 else 26
        repeat(grooves){i->
            val r=radius*(.40f+.55f*i/(grooves-1))
            drawCircle(Color.White.copy(alpha=if(i%3==0).09f else .045f),r,center,style=Stroke((side*.0012f).coerceAtLeast(.5f)))
        }
        rotate(18f+(seed.hashCode().ushr(8)%38),center){
            drawCircle(Brush.sweepGradient(listOf(
                Color.Transparent,Color.White.copy(alpha=.04f),Color.White.copy(alpha=.22f),
                Color.Transparent,Color.Transparent,Color.White.copy(alpha=.12f),Color.Transparent
            ),center),radius*.99f,center)
        }
        val labelRadius=radius*.32f
        val label=Color(0xFFB6B6B6)
        drawCircle(Brush.linearGradient(listOf(lerp(label,Color.White,.16f),label,lerp(label,Color.Black,.14f)),center-Offset(labelRadius,labelRadius),center+Offset(labelRadius,labelRadius)),labelRadius,center)
        drawCircle(Color.Black.copy(alpha=.16f),labelRadius,center,style=Stroke((side*.002f).coerceAtLeast(.6f)))
        drawCircle(Color.Black.copy(alpha=.16f),labelRadius*.48f,center,style=Stroke((side*.001f).coerceAtLeast(.5f)))
        drawCircle(Color(0xFF0B0B0B),radius*.045f,center)
        drawCircle(Color.White.copy(alpha=.25f),radius*.046f,center,style=Stroke((side*.0015f).coerceAtLeast(.6f)))
    }
}
