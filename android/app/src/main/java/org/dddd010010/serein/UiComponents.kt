@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.text.ExperimentalTextApi::class)
package org.dddd010010.serein

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Night=Color(0xFF0B0C0D)
val Panel=Color(0xFF1B1C20)
val Raised=Color(0xFF2A2C31)
val TextBright=Color(0xFFFFFFFF)
val Muted=Color(0xFFA4A7AE)
val Gold=TextBright
// Translucent layers over the cover atmosphere. Compose has no backdrop blur, so these stay light enough to read as glass.
val GlassSoft=Color.White.copy(alpha=.07f)
val Glass=Color.White.copy(alpha=.10f)
val GlassStrong=Color.White.copy(alpha=.14f)
val Hairline=Color.White.copy(alpha=.08f)
val Secondary=Color.White.copy(alpha=.62f)
val Danger=Color(0xFFFF8A7A)
val SereinFont=FontFamily(
    Font(R.font.manrope,FontWeight.Normal,variationSettings=FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope,FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope,FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope,FontWeight.Bold,variationSettings=FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope,FontWeight.ExtraBold,variationSettings=FontVariation.Settings(FontVariation.weight(800)))
)
val EchoLatinTitle=SereinFont
val EchoChineseTitle=FontFamily(
    Font(R.font.noto_sans_tc,FontWeight.Normal,variationSettings=FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_sans_tc,FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.noto_sans_tc,FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.noto_sans_tc,FontWeight.Bold,variationSettings=FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.noto_sans_tc,FontWeight.ExtraBold,variationSettings=FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.noto_sans_tc,FontWeight.Black,variationSettings=FontVariation.Settings(FontVariation.weight(900)))
)
val EchoTitleFont:FontFamily get()=if(AppLocale.language=="en")EchoLatinTitle else EchoChineseTitle
val EchoPageWeight:FontWeight get()=if(AppLocale.language=="en")FontWeight.ExtraBold else FontWeight.Black
private val Tabular=TextStyle(fontFeatureSettings="tnum")

@Composable fun PageTitle(title:String,modifier:Modifier=Modifier,size:Int=32){
    Text(title,modifier=modifier,fontFamily=EchoTitleFont,fontSize=size.sp,lineHeight=(size+8).sp,
        fontWeight=EchoPageWeight,letterSpacing=if(AppLocale.language=="en")(-.6).sp else .2.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
}
@Composable fun HeaderActions(content:@Composable RowScope.()->Unit){
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically,content=content)
}
/** Round translucent control used for header and sheet actions. The touch target stays at least 48dp. */
@Composable fun GlassButton(onClick:()->Unit,modifier:Modifier=Modifier,size:Dp=40.dp,enabled:Boolean=true,fill:Color=GlassStrong,content:@Composable BoxScope.()->Unit){
    Box(modifier.minimumInteractiveComponentSize().size(size).clip(CircleShape).background(fill).clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center,content=content)
}
@Composable fun GlassIcon(icon:ImageVector,description:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    GlassButton(onClick,modifier,enabled=enabled){Icon(icon,description,Modifier.size(20.dp),tint=if(enabled)TextBright else Secondary)}
}
@Composable fun EchoWordmark(modifier:Modifier=Modifier){
    Text("Echo",modifier=modifier,fontFamily=SereinFont,fontSize=23.sp,lineHeight=28.sp,
        fontWeight=FontWeight.Bold,letterSpacing=(-.5).sp,style=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false)))
}

@Composable fun SereinTheme(content:@Composable ()->Unit){
    MaterialTheme(colorScheme=darkColorScheme(primary=TextBright,onPrimary=Night,primaryContainer=Raised,onPrimaryContainer=TextBright,
        secondary=Muted,secondaryContainer=Raised,onSecondaryContainer=TextBright,background=Night,onBackground=TextBright,
        surface=Night,onSurface=TextBright,surfaceVariant=Panel,onSurfaceVariant=Muted,outline=Muted.copy(alpha=.4f),
        surfaceContainer=Panel,surfaceContainerHigh=Raised,surfaceContainerHighest=Raised,error=Danger),
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp),extraLarge=RoundedCornerShape(26.dp)),
        typography=Typography(
            bodyLarge=TextStyle(fontFamily=SereinFont,fontSize=16.sp),
            bodyMedium=TextStyle(fontFamily=SereinFont,fontSize=14.sp),
            bodySmall=TextStyle(fontFamily=SereinFont,fontSize=12.sp),
            headlineSmall=TextStyle(fontFamily=EchoTitleFont,fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold),
            titleLarge=TextStyle(fontFamily=EchoTitleFont,fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold),
            titleMedium=TextStyle(fontFamily=SereinFont,fontSize=16.sp,fontWeight=FontWeight.Bold),
            labelLarge=TextStyle(fontFamily=SereinFont,fontSize=14.sp,fontWeight=FontWeight.Bold),
            labelMedium=TextStyle(fontFamily=SereinFont,fontSize=12.sp,fontWeight=FontWeight.SemiBold),
            labelSmall=TextStyle(fontFamily=SereinFont,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
        ),content=content)
}
@Composable fun FullSheet(close:()->Unit,containerColor:Color=Night,fullHeight:Boolean=true,backdrop:(@Composable BoxScope.()->Unit)?=null,content:@Composable (()->Unit)->Unit){
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val scope=rememberCoroutineScope()
    var closing by remember{mutableStateOf(false)}
    val latestClose by rememberUpdatedState(close)
    val dismiss:()->Unit={if(!closing){closing=true;scope.launch{try{sheet.hide();if(!sheet.isVisible)latestClose()}finally{closing=false}}}}
    // Material 3 1.3 derives dialog system-bar contrast from this configuration.
    val configuration=Configuration(LocalConfiguration.current).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
    CompositionLocalProvider(LocalConfiguration provides configuration){
        ModalBottomSheet(onDismissRequest=close,containerColor=containerColor,contentColor=TextBright,sheetState=sheet,shape=RoundedCornerShape(topStart=26.dp,topEnd=26.dp),scrimColor=Color.Black.copy(alpha=.55f),dragHandle=null){
            // The handle lives inside the content box so a cover backdrop can run under it without a seam.
            Box(Modifier.fillMaxWidth()){
                backdrop?.invoke(this)
                Column{
                    // Full-height sheets reach the status bar, so their handle starts below it.
                    Box(Modifier.fillMaxWidth().then(if(fullHeight)Modifier.statusBarsPadding() else Modifier).padding(top=10.dp,bottom=2.dp),contentAlignment=Alignment.Center){Box(Modifier.size(38.dp,5.dp).background(Color.White.copy(alpha=.3f),CircleShape))}
                    content(dismiss)
                }
            }
        }
    }
}
@Composable fun PlayerScene(visible:Boolean,close:()->Unit,content:@Composable ()->Unit){
    val state=remember{MutableTransitionState(false)}
    LaunchedEffect(visible){state.targetState=visible}
    if(!visible && !state.currentState && state.isIdle)return
    val configuration=Configuration(LocalConfiguration.current).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
    CompositionLocalProvider(LocalConfiguration provides configuration){
        Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
            val view=LocalView.current
            SideEffect{(view.parent as? DialogWindowProvider)?.window?.let{window->
                window.setDimAmount(0f);window.setWindowAnimations(0)
                WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false}
            }}
            Box(Modifier.fillMaxSize()){
                AnimatedVisibility(visibleState=state,enter=fadeIn(tween(340)),exit=fadeOut(tween(260))){
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.45f)))
                }
                AnimatedVisibility(visibleState=state,
                    enter=slideInVertically(tween(340,easing=FastOutSlowInEasing)){it},
                    exit=slideOutVertically(tween(260,easing=FastOutSlowInEasing)){it}){
                    Surface(Modifier.fillMaxSize(),color=Night,contentColor=TextBright){content()}
                }
            }
        }
    }
}
/** Echo's mark: an inverted pyramid above a smaller peak, with the gap left open. */
@Composable fun BrandMark(modifier:Modifier=Modifier,color:Color=TextBright){
    Canvas(modifier){
        val u=size.minDimension/48f
        val ox=(size.width-48f*u)/2f;val oy=(size.height-48f*u)/2f
        fun p(x:Float,y:Float)=Offset(ox+x*u,oy+y*u)
        fun tri(a:Offset,b:Offset,c:Offset,fill:Color)=drawPath(Path().apply{moveTo(a.x,a.y);lineTo(b.x,b.y);lineTo(c.x,c.y);close()},fill)
        // Small sizes merge the two faces and widen the gap so the mark stays legible.
        if(u*48f<28.dp.toPx()){
            tri(p(5f,6f),p(43f,6f),p(24f,27f),color);tri(p(24f,33f),p(14f,44f),p(34f,44f),color)
        }else{
            tri(p(6f,7f),p(24f,7f),p(24f,28f),color);tri(p(24f,7f),p(42f,7f),p(24f,28f),color.copy(alpha=color.alpha*.62f))
            tri(p(24f,33f),p(15.4f,43f),p(32.6f,43f),color)
        }
    }
}
/** Pill tab. Selected tabs are solid white; the rest sit on glass. */
@Composable fun LineTab(label:String,selected:Boolean,click:()->Unit){
    val fill by animateColorAsState(if(selected)TextBright else Glass,label="tab fill")
    val ink by animateColorAsState(if(selected)Night else TextBright.copy(alpha=.86f),label="tab ink")
    Box(Modifier.padding(vertical=6.dp).height(36.dp).clip(CircleShape).background(fill).selectable(selected=selected,role=Role.Tab,onClick=click)
        .widthIn(min=48.dp).padding(horizontal=16.dp),contentAlignment=Alignment.Center){
        Text(label,fontSize=14.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.SemiBold,color=ink,maxLines=1)
    }
}
/** Segmented control with a white thumb, used for mutually exclusive settings. */
@Composable fun <T> Segmented(options:List<Pair<T,String>>,selected:T?,select:(T)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,tabular:Boolean=false){
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Glass).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){
        options.forEach{(value,label)->
            val on=value==selected
            val fill by animateColorAsState(if(on)TextBright else Color.Transparent,label="segment")
            Box(Modifier.weight(1f).heightIn(min=40.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                .selectable(selected=on,enabled=enabled,role=Role.RadioButton){select(value)},contentAlignment=Alignment.Center){
                Text(label,fontSize=13.sp,fontWeight=if(on)FontWeight.ExtraBold else FontWeight.SemiBold,color=if(on)Night else TextBright.copy(alpha=if(enabled).78f else .4f),maxLines=1,style=if(tabular)Tabular else TextStyle.Default)
            }
        }
    }
}
@Composable fun GlassCard(modifier:Modifier=Modifier,fill:Color=GlassSoft,content:@Composable ColumnScope.()->Unit){
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(fill),content=content)
}
@Composable fun CardDivider(start:Dp=16.dp){Box(Modifier.padding(start=start).fillMaxWidth().height(1.dp).background(Hairline))}
@Composable fun SectionLabel(text:String,modifier:Modifier=Modifier){Text(text,fontSize=13.sp,fontWeight=FontWeight.Bold,color=TextBright.copy(alpha=.5f),modifier=modifier.padding(start=32.dp,end=32.dp,top=26.dp,bottom=8.dp))}
@Composable fun PrimaryPill(text:String,onClick:()->Unit,modifier:Modifier=Modifier,icon:ImageVector?=Icons.Rounded.PlayArrow,enabled:Boolean=true){
    Row(modifier.height(46.dp).clip(CircleShape).background(if(enabled)TextBright else Glass).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=22.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
        if(icon!=null){Icon(icon,null,Modifier.size(20.dp),tint=if(enabled)Night else Secondary);Spacer(Modifier.width(6.dp))}
        Text(text,fontSize=15.sp,fontWeight=FontWeight.ExtraBold,color=if(enabled)Night else Secondary,maxLines=1)
    }
}
@Composable fun SecondaryPill(text:String,onClick:()->Unit,modifier:Modifier=Modifier,icon:ImageVector?=null,enabled:Boolean=true,selected:Boolean=false,height:Dp=46.dp){
    Row(modifier.height(height).clip(CircleShape).background(if(selected)TextBright else GlassStrong).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=if(height<40.dp)12.dp else 20.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
        val ink=if(selected)Night else if(enabled)TextBright else Secondary
        if(icon!=null){Icon(icon,null,Modifier.size(if(height<40.dp)15.dp else 18.dp),tint=ink);Spacer(Modifier.width(6.dp))}
        Text(text,fontSize=if(height<40.dp)12.sp else 15.sp,fontWeight=FontWeight.Bold,color=ink,maxLines=1)
    }
}
@Composable fun EchoSwitch(checked:Boolean,change:(Boolean)->Unit,enabled:Boolean=true){
    Switch(checked,change,enabled=enabled,colors=SwitchDefaults.colors(checkedThumbColor=Night,checkedTrackColor=TextBright,checkedBorderColor=Color.Transparent,
        uncheckedThumbColor=TextBright.copy(alpha=.7f),uncheckedTrackColor=GlassStrong,uncheckedBorderColor=Color.Transparent))
}
@Composable fun StatusPill(text:String,emphasis:Int=1){
    // 2 = current, 1 = neutral, 0 = quiet (skips and interruptions)
    val (fill,ink)=when(emphasis){2->TextBright to Night;1->GlassStrong to TextBright.copy(alpha=.9f);else->Color.Transparent to TextBright.copy(alpha=.6f)}
    Text(text,fontSize=11.sp,fontWeight=FontWeight.Bold,color=ink,maxLines=1,
        modifier=Modifier.clip(CircleShape).background(fill).then(if(emphasis==0)Modifier.border(1.dp,Color.White.copy(alpha=.25f),CircleShape) else Modifier).padding(horizontal=8.dp,vertical=2.dp))
}

private val AmbientFilter=ColorFilter.colorMatrix(ColorMatrix().apply{
    setToSaturation(1.6f)
    timesAssign(ColorMatrix(floatArrayOf(.55f,0f,0f,0f,0f, 0f,.55f,0f,0f,0f, 0f,0f,.55f,0f,0f, 0f,0f,0f,1f,0f)))
})
/**
 * Cover-colored atmosphere. The cover is decoded at a tiny size and stretched, which already reads as a blur on every
 * Android version; Android 12+ adds a real blur on top. [fade] lets the colour dissolve into the page background.
 */
@Composable fun AmbientBackdrop(data:Any?,cacheKey:String?,modifier:Modifier=Modifier,fade:Boolean=true){
    Box(modifier.clipToBounds()){
        if(data!=null && cacheKey!=null){
            val context=LocalContext.current
            val request=remember(cacheKey){ArtworkImages.ambient(context,data,cacheKey)}
            AsyncImage(request,null,Modifier.fillMaxSize().graphicsLayer{scaleX=1.5f;scaleY=1.5f}
                .then(if(Build.VERSION.SDK_INT>=31)Modifier.blur(48.dp,BlurredEdgeTreatment.Unbounded) else Modifier),
                contentScale=ContentScale.Crop,filterQuality=FilterQuality.High,colorFilter=AmbientFilter)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            if(fade)listOf(Night.copy(alpha=0f),Night.copy(alpha=.38f),Night) else listOf(Color.Black.copy(alpha=.08f),Color.Black.copy(alpha=.2f),Color.Black.copy(alpha=.55f)))))
    }
}
@Composable fun SongBackdrop(song:Song?,modifier:Modifier=Modifier,fade:Boolean=true){
    val local=song!=null && song.id in Library.coverFiles
    val data=if(song!=null && song.hasCover)ArtworkImages.source(song,local) else null
    AmbientBackdrop(data,song?.takeIf{it.hasCover}?.let{ArtworkImages.key(it)},modifier,fade)
}

@Composable fun SheetTitle(title:String,close:()->Unit,subtitle:String?=null){
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=10.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){PageTitle(title,size=28);if(subtitle!=null)Text(subtitle,fontSize=14.sp,color=TextBright.copy(alpha=.55f),modifier=Modifier.padding(top=2.dp))}
        GlassIcon(Icons.Rounded.Close,tr(R.string.ui_close),close)
    }
}
@Composable fun Heading(title:String,action:String?=null,onClick:()->Unit={}){
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,fontFamily=EchoTitleFont,fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,letterSpacing=0.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
        if(action!=null)TextButton(onClick=onClick){Text(action,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Secondary)}
    }
}
@Composable fun EmptyState(title:String,body:String,action:String?=null,click:()->Unit={}){Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally){
    Box(Modifier.size(64.dp).background(Glass,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Rounded.GraphicEq,null,Modifier.size(30.dp),tint=TextBright)}
    Text(title,fontFamily=EchoTitleFont,fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp))
    Text(body,color=Secondary,fontSize=13.sp,lineHeight=21.sp,modifier=Modifier.padding(top=8.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    if(action!=null)SecondaryPill(action,click,Modifier.padding(top=18.dp),height=40.dp)
}}
@Composable fun Artwork(song:Song?,modifier:Modifier=Modifier,radius:Int=8,tone:Boolean=false,priority:Boolean=tone){Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Raised),contentAlignment=Alignment.Center){
    var failed by remember(song?.id,song?.mtime,Library.base){mutableStateOf(false)}
    val record=rememberRecordArtwork(song?.let{it.artist+it.album+it.title} ?: "Echo",song?.hasCover!=true || failed)
    if(song!=null && song.hasCover){
        val context=LocalContext.current
        val active=LocalArtworkActive.current
        var started by remember(song.id){mutableStateOf(false)}
        LaunchedEffect(song.id,active){if(active)started=true}
        val local=song.id in Library.coverFiles
        val base=Library.base
        val request=remember(song,local,base,tone,priority){ArtworkImages.request(context,song,local,tone,priority)}
        val scope=rememberCoroutineScope()
        if(started)AsyncImage(request,tr(R.string.ui_cover_for,song.title),placeholder=record,error=record,fallback=record,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize(),
            onError={failed=true},onSuccess={failed=false;if(tone)scope.launch{ArtworkImages.sample(song,it.result.drawable)}})
    }else Image(record,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
}}
@Composable fun SongRow(song:Song,play:()->Unit,menu:()->Unit,subtitle:String=song.artist,trailing:@Composable (() -> Unit)?=null){Row(Modifier.fillMaxWidth().clickable(onClick=play).padding(start=20.dp,end=6.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
    Artwork(song,Modifier.size(48.dp))
    Column(Modifier.weight(1f).padding(horizontal=14.dp)){
        Text(song.title,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
        Row(Modifier.padding(top=2.dp),verticalAlignment=Alignment.CenterVertically){
            if(Library.downloaded(song)){Icon(Icons.Rounded.DownloadForOffline,tr(R.string.ui_downloaded),Modifier.size(13.dp),tint=Secondary);Spacer(Modifier.width(5.dp))}
            Text(subtitle,fontSize=13.sp,color=Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
    if(trailing!=null)trailing() else IconButton(onClick=menu){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_options_for, song.title),tint=Secondary)}
}}
@Composable fun SearchBox(value:String,change:(String)->Unit,placeholder:String,submit:()->Unit={},clear:()->Unit={change("")},modifier:Modifier=Modifier){
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    fun search(){keyboard?.hide();focus.clearFocus();submit()}
    TextField(value,change,placeholder={Text(placeholder,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=TextBright.copy(alpha=.5f))},singleLine=true,
        leadingIcon={IconButton(onClick={search()}){Icon(Icons.Rounded.Search,tr(R.string.ui_search),Modifier.size(20.dp),tint=Secondary)}},
        trailingIcon={if(value.isNotBlank())IconButton(onClick=clear){Icon(Icons.Rounded.Close,tr(R.string.ui_clear_search),Modifier.size(18.dp),tint=Secondary)}},
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),shape=RoundedCornerShape(12.dp),
        textStyle=TextStyle(fontFamily=SereinFont,fontSize=15.sp),
        colors=TextFieldDefaults.colors(focusedContainerColor=Glass,unfocusedContainerColor=Glass,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,cursorColor=TextBright),
        modifier=modifier.fillMaxWidth().padding(horizontal=20.dp))
}
/** Row inside a [GlassCard]: optional icon tile, label, subtitle and trailing content. */
@Composable fun CardRow(text:String,icon:ImageVector?=null,subtitle:String?=null,color:Color=TextBright,tile:Boolean=false,click:(()->Unit)?=null,trailing:@Composable (RowScope.()->Unit)?=null){
    Row(Modifier.fillMaxWidth().then(if(click!=null)Modifier.clickable(onClick=click) else Modifier).heightIn(min=54.dp).padding(horizontal=16.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
        if(icon!=null){
            if(tile)Box(Modifier.size(30.dp).background(GlassStrong,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){Icon(icon,null,Modifier.size(17.dp),tint=color)}
            else Icon(icon,null,Modifier.size(21.dp),tint=color)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)){
            Text(text,fontSize=15.sp,fontWeight=FontWeight.Bold,color=color)
            if(subtitle!=null)Text(subtitle,fontSize=12.sp,lineHeight=17.sp,color=TextBright.copy(alpha=.55f),modifier=Modifier.padding(top=2.dp))
        }
        if(trailing!=null)Row(verticalAlignment=Alignment.CenterVertically,content=trailing)
    }
}
@Composable fun Chevron(){Icon(Icons.Rounded.ChevronRight,null,Modifier.size(20.dp),tint=TextBright.copy(alpha=.35f))}
@Composable fun Action(text:String,icon:ImageVector,subtitle:String?=null,click:()->Unit){CardRow(text,icon,subtitle,click=click)}
@Composable fun TextDialog(title:String,label:String,dismiss:()->Unit,initial:String="",submit:(String)->Unit){var value by remember{mutableStateOf(initial)};AlertDialog(onDismissRequest=dismiss,title={Text(title)},containerColor=Panel,text={OutlinedTextField(value,{value=it},label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={submit(value.trim())},enabled=value.isNotBlank()){Text(tr(R.string.ui_save))}},dismissButton={TextButton(onClick=dismiss){Text(tr(R.string.ui_cancel))}})}
val TabularNumbers:TextStyle get()=Tabular
fun size(bytes:Long):String=if(bytes>=1073741824)"%.2f GB".format(bytes/1073741824.0) else "%.1f MB".format(bytes/1048576.0)
fun timeLabel(ms:Long):String="%d:%02d".format(ms.coerceAtLeast(0)/60000,(ms.coerceAtLeast(0)/1000)%60)
