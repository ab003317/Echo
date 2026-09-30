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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.imageLoader
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.io.File

val Night=Color(0xFF08090B)
val Panel=Color(0xFF17191D)
val Raised=Color(0xFF25282E)
val TextBright=Color(0xFFF5F5F2)
val Muted=Color(0xFFA2A5AD)
val Gold=TextBright
val SereinFont=FontFamily(
    Font(R.font.manrope,FontWeight.Normal,variationSettings=FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope,FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope,FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope,FontWeight.Bold,variationSettings=FontVariation.Settings(FontVariation.weight(700)))
)
val EchoLatinTitle=FontFamily(
    Font(R.font.space_grotesk,FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk,FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600)))
)
val EchoChineseTitle=FontFamily(
    Font(R.font.noto_sans_tc,FontWeight.Medium,variationSettings=FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.noto_sans_tc,FontWeight.SemiBold,variationSettings=FontVariation.Settings(FontVariation.weight(600)))
)
val EchoTitleFont:FontFamily get()=if(AppLocale.language=="en")EchoLatinTitle else EchoChineseTitle

@Composable fun SereinTheme(content:@Composable ()->Unit){
    MaterialTheme(colorScheme=darkColorScheme(primary=Gold,onPrimary=Night,primaryContainer=Raised,onPrimaryContainer=TextBright,
        secondary=Muted,secondaryContainer=Raised,onSecondaryContainer=TextBright,background=Night,onBackground=TextBright,
        surface=Night,onSurface=TextBright,surfaceVariant=Panel,onSurfaceVariant=Muted,outline=Muted.copy(alpha=.4f),
        surfaceContainer=Panel,surfaceContainerHigh=Raised,error=Color(0xFFEBA5A0)),
        typography=Typography(
            bodyLarge=TextStyle(fontFamily=SereinFont,fontSize=16.sp),
            bodyMedium=TextStyle(fontFamily=SereinFont,fontSize=14.sp),
            bodySmall=TextStyle(fontFamily=SereinFont,fontSize=12.sp),
            titleLarge=TextStyle(fontFamily=EchoTitleFont,fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.Medium),
            titleMedium=TextStyle(fontFamily=SereinFont,fontSize=16.sp,fontWeight=FontWeight.Medium),
            labelLarge=TextStyle(fontFamily=SereinFont,fontSize=14.sp,fontWeight=FontWeight.Medium),
            labelMedium=TextStyle(fontFamily=SereinFont,fontSize=12.sp,fontWeight=FontWeight.Medium),
            labelSmall=TextStyle(fontFamily=SereinFont,fontSize=11.sp)
        ),content=content)
}
@Composable fun FullSheet(close:()->Unit,content:@Composable (()->Unit)->Unit){
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val scope=rememberCoroutineScope()
    var closing by remember{mutableStateOf(false)}
    val latestClose by rememberUpdatedState(close)
    val dismiss:()->Unit={if(!closing){closing=true;scope.launch{try{sheet.hide();if(!sheet.isVisible)latestClose()}finally{closing=false}}}}
    // Material 3 1.3 derives dialog system-bar contrast from this configuration.
    val configuration=Configuration(LocalConfiguration.current).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
    CompositionLocalProvider(LocalConfiguration provides configuration){
        ModalBottomSheet(onDismissRequest=close,containerColor=Night,sheetState=sheet,dragHandle=null){Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top=6.dp)){content(dismiss)}}
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
@Composable fun BrandMark(modifier:Modifier=Modifier){
    Image(androidx.compose.ui.res.painterResource(R.drawable.echo_logo),null,
        modifier.clip(RoundedCornerShape(7.dp)),contentScale=ContentScale.Crop)
}
@Composable fun LineTab(label:String,selected:Boolean,click:()->Unit){
    Column(Modifier.selectable(selected=selected,role=Role.Tab,onClick=click).padding(end=24.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.heightIn(min=48.dp),contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=if(selected)TextBright else Muted)}
        Box(Modifier.width(22.dp).height(2.dp).background(if(selected)TextBright else Color.Transparent,CircleShape))
    }
}
@Composable fun SheetTitle(title:String,close:()->Unit){Row(Modifier.fillMaxWidth().padding(start=22.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){Text(title,fontFamily=EchoTitleFont,fontSize=23.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));IconButton(onClick=close){Icon(Icons.Rounded.Close,tr(R.string.ui_close))}}}
@Composable fun Heading(title:String,action:String?=null,onClick:()->Unit={}){Row(Modifier.fillMaxWidth().padding(start=24.dp,end=14.dp,top=18.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){Text(title,fontFamily=EchoTitleFont,fontSize=21.sp,fontWeight=FontWeight.Medium,letterSpacing=0.sp,modifier=Modifier.weight(1f));if(action!=null)TextButton(onClick=onClick){Text(action,fontSize=12.sp,color=Muted)}}}
@Composable fun EmptyState(title:String,body:String,action:String?=null,click:()->Unit={}){Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.GraphicEq,null,Modifier.size(38.dp),tint=Muted);Text(title,fontSize=19.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=18.dp));Text(body,color=Muted,fontSize=13.sp,lineHeight=21.sp,modifier=Modifier.padding(top=10.dp));if(action!=null)TextButton(onClick=click,modifier=Modifier.padding(top=8.dp)){Text(action)}}}
@Composable fun Artwork(song:Song?,modifier:Modifier=Modifier,radius:Int=4,tone:Boolean=false,priority:Boolean=tone){Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Raised),contentAlignment=Alignment.Center){
    Icon(Icons.Rounded.Album,null,Modifier.fillMaxSize(.4f),tint=Muted.copy(alpha=.5f))
    if(song!=null && song.hasCover){
        val context=LocalContext.current
        val active=LocalArtworkActive.current
        var started by remember(song.id){mutableStateOf(false)}
        LaunchedEffect(active){if(active)started=true}
        val local=song.id in Library.coverFiles
        val base=Library.base
        val request=remember(song,local,base,tone,priority){ArtworkImages.request(context,song,local,tone,priority)}
        val scope=rememberCoroutineScope()
        if(started)AsyncImage(request,tr(R.string.ui_cover_for,song.title),contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize(),
            onSuccess={if(tone)scope.launch{ArtworkImages.sample(song,it.result.drawable)}})
    }
}}
@Composable fun SongRow(song:Song,play:()->Unit,menu:()->Unit,subtitle:String=song.artist,trailing:@Composable (() -> Unit)?=null){Row(Modifier.fillMaxWidth().clickable(onClick=play).padding(start=22.dp,end=8.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){
    Artwork(song,Modifier.size(52.dp),4)
    Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(song.title,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically){if(Library.downloaded(song)){Icon(Icons.Rounded.OfflinePin,tr(R.string.ui_downloaded),Modifier.size(12.dp),tint=Gold);Spacer(Modifier.width(4.dp))};Text(subtitle,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}}
    if(trailing!=null)trailing() else IconButton(onClick=menu){Icon(Icons.Rounded.MoreHoriz,tr(R.string.ui_options_for, song.title),tint=Muted)}
}}
@Composable fun SearchBox(value:String,change:(String)->Unit,placeholder:String,submit:()->Unit={},clear:()->Unit={change("")}){
    val keyboard=LocalSoftwareKeyboardController.current
    TextField(value,change,placeholder={Text(placeholder,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)},singleLine=true,leadingIcon={IconButton(onClick={keyboard?.hide();submit()}){Icon(Icons.Rounded.Search,tr(R.string.ui_search),Modifier.size(20.dp))}},trailingIcon={if(value.isNotBlank())IconButton(onClick=clear){Icon(Icons.Rounded.Close,tr(R.string.ui_clear_search),Modifier.size(18.dp))}},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();submit()}),shape=RoundedCornerShape(8.dp),colors=TextFieldDefaults.colors(focusedContainerColor=Panel,unfocusedContainerColor=Panel,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp))
}
@Composable fun Action(text:String,icon:ImageVector,subtitle:String?=null,click:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(horizontal=24.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(22.dp),tint=Gold);Column(Modifier.padding(start=18.dp)){Text(text,fontSize=15.sp);if(subtitle!=null)Text(subtitle,fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=3.dp))}}}
@Composable fun TextDialog(title:String,label:String,dismiss:()->Unit,initial:String="",submit:(String)->Unit){var value by remember{mutableStateOf(initial)};AlertDialog(onDismissRequest=dismiss,title={Text(title)},containerColor=Panel,text={OutlinedTextField(value,{value=it},label={Text(label)},singleLine=true)},confirmButton={TextButton(onClick={submit(value.trim())},enabled=value.isNotBlank()){Text(tr(R.string.ui_save))}},dismissButton={TextButton(onClick=dismiss){Text(tr(R.string.ui_cancel))}})}
fun size(bytes:Long):String=if(bytes>=1073741824)"%.2f GB".format(bytes/1073741824.0) else "%.1f MB".format(bytes/1048576.0)
fun timeLabel(ms:Long):String="%d:%02d".format(ms.coerceAtLeast(0)/60000,(ms.coerceAtLeast(0)/1000)%60)
